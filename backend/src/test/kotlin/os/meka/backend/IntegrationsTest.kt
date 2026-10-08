package os.meka.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.GoogleCalendar
import os.meka.backend.integrations.MicrosoftCalendar
import os.meka.backend.integrations.grants
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.backend.integrations.httpsOrNull
import os.meka.backend.integrations.plainText
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.wire.WireCodec
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntegrationsTest {
    private val hour = 3_600_000L
    private var now = 1_790_985_600_000L // 2026-10-03T00:00Z

    /** Records what it was asked and serves whatever events the test sets. */
    private class FakeProvider : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "https://www.googleapis.com/auth/calendar.events"
        var grantedScope: String? = null
        var askedEditing: Boolean? = null
        var exchangedEditing: Boolean? = null
        var refreshedEditing: Boolean? = null
        var events = listOf<RemoteEvent>()
        var lastVerifier: String? = null
        var refreshed = 0
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean): String {
            askedEditing = editing
            return "https://accounts.example/auth?state=$state&challenge=$codeChallenge&redirect=$redirectUri"
        }
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean): TokenSet {
            lastVerifier = verifier; exchangedEditing = editing; return TokenSet("access-1", "refresh-1", 3600, grantedScope)
        }
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean): TokenSet {
            check(refreshToken == "refresh-1") { "decrypted the wrong token" }
            refreshed++; refreshedEditing = editing; return TokenSet("access-2", null, 3600)
        }
        override fun accountEmail(accessToken: String) = "Meka@Gmail.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long) = events
    }

    /** Reversible stand-in for KMS that still enforces the encryption context. */
    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = (context.toString() + "|").toByteArray() + plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray {
            val prefix = (context.toString() + "|").toByteArray()
            check(cipher.copyOfRange(0, prefix.size).contentEquals(prefix)) { "wrong encryption context" }
            return cipher.copyOfRange(prefix.size, cipher.size).reversedArray()
        }
    }

    private val provider = FakeProvider()
    private val store = InMemoryIntegrationStore()
    private val ops = InMemoryServerOpStore()
    private var configured = true
    private val integrations = Integrations(
        store, ops, mapOf("google" to provider), { if (configured) OAuthClient("cid", "secret") else null }, FakeCipher(),
        "https://meka.example", { now },
    )

    private fun stateOf(url: String) = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")

    private fun connect(editing: Boolean = false): String = connected(editing).accountId

    private fun connected(editing: Boolean = false): Integrations.CallbackResult.Connected {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google", editing)).url
        return assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", stateOf(url), "code-1", null))
    }

    @Test
    fun allowEditingAsksForTheWriteScopeAndOnlyAGrantedOneLetsTheAccountEdit() {
        val readOnly = "openid email https://www.googleapis.com/auth/calendar.readonly"
        // A plain connect never asks to change anything.
        provider.grantedScope = readOnly
        val plain = connected()
        assertEquals(false, provider.askedEditing)
        assertEquals(false, plain.canEdit)
        assertEquals(false, store.account(plain.accountId)!!.canEdit)
        // Allow editing asks; the owner unticks the permission: still connected, read-only, and the page says so.
        val unticked = connected(editing = true)
        assertEquals(true, provider.askedEditing)
        assertEquals(true, provider.exchangedEditing)
        assertEquals(false, unticked.canEdit)
        assertTrue(unticked.editingAsked)
        assertTrue(connectedText(unticked).contains("read-only: editing wasn't allowed"))
        // A look-alike scope isn't the write scope.
        provider.grantedScope = "$readOnly https://www.googleapis.com/auth/calendar.events.readonly"
        assertEquals(false, connected(editing = true).canEdit)
        // Granted: the account may edit, the list says so, and refreshes ask for it.
        provider.grantedScope = "$readOnly https://www.googleapis.com/auth/calendar.events"
        val ok = connected(editing = true)
        assertTrue(ok.canEdit)
        assertEquals(ok.accountId, plain.accountId) // the same account, now editable
        assertTrue(connectedText(ok).contains("may now add and change events"))
        assertEquals(listOf(true), integrations.accounts("home").map { it.canEdit })
        integrations.syncAccount(ok.accountId)
        assertEquals(true, provider.refreshedEditing)
        // Reconnecting read-only gives editing up (the token no longer carries it).
        provider.grantedScope = readOnly
        connected()
        assertEquals(listOf(false), integrations.accounts("home").map { it.canEdit })
    }

    @Test
    fun stopEditingTurnsItOffAtOnceAndOnlyForThatAccount() {
        provider.grantedScope = "openid email https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/calendar.events"
        val acc = connect(editing = true)
        assertTrue(store.account(acc)!!.canEdit)
        assertEquals(false, integrations.stopEditing("home", "google", "someone@else.com"))
        assertEquals(false, integrations.stopEditing("home", "fixtures", "meka@gmail.com"))
        assertEquals(false, integrations.stopEditing("other", "google", "meka@gmail.com"))
        assertTrue(store.account(acc)!!.canEdit)
        assertTrue(integrations.stopEditing("home", "google", "Meka@Gmail.com"))
        assertEquals(false, store.account(acc)!!.canEdit)
        integrations.syncAccount(acc)
        assertEquals(false, provider.refreshedEditing)
    }

    @Test
    fun grantedScopesAreMatchedWordForWord() {
        assertTrue(grants("openid https://www.googleapis.com/auth/calendar.events", "https://www.googleapis.com/auth/calendar.events"))
        assertTrue(grants("https://graph.microsoft.com/Calendars.ReadWrite openid", "Calendars.ReadWrite"))
        assertTrue(grants("Calendars.ReadWrite offline_access", "Calendars.ReadWrite"))
        assertEquals(false, grants("https://www.googleapis.com/auth/calendar.events.readonly", "https://www.googleapis.com/auth/calendar.events"))
        assertEquals(false, grants("https://graph.microsoft.com/Calendars.Read", "Calendars.ReadWrite"))
        assertEquals(false, grants(null, "Calendars.ReadWrite"))
        assertEquals(false, grants("", "Calendars.ReadWrite"))
    }

    @Test
    fun theRealProvidersAskForTheWriteScopeOnlyWithAllowEditing() {
        val client = OAuthClient("cid", "secret")
        val g = GoogleCalendar()
        val gRead = java.net.URLDecoder.decode(g.authorizeUrl(client, "https://meka.example/cb", "s", "c"), Charsets.UTF_8)
        val gEdit = java.net.URLDecoder.decode(g.authorizeUrl(client, "https://meka.example/cb", "s", "c", editing = true), Charsets.UTF_8)
        assertEquals(false, gRead.contains("calendar.events"))
        assertTrue(gEdit.contains("auth/calendar.readonly https://www.googleapis.com/auth/calendar.events"))
        val m = MicrosoftCalendar()
        val mRead = java.net.URLDecoder.decode(m.authorizeUrl(client, "https://meka.example/cb", "s", "c"), Charsets.UTF_8)
        val mEdit = java.net.URLDecoder.decode(m.authorizeUrl(client, "https://meka.example/cb", "s", "c", editing = true), Charsets.UTF_8)
        assertTrue(mRead.contains("Calendars.Read") && !mRead.contains("Calendars.ReadWrite"))
        assertTrue(mEdit.contains("Calendars.ReadWrite"))
    }

    private fun ev(id: String, title: String, startH: Long, allDay: Boolean = false) =
        RemoteEvent(id, title, now + startH * hour, now + (startH + 1) * hour, allDay, null, "Personal")

    /** A device's view: everything the server has written, replayed through the normal merge. */
    private fun deviceEvents(): List<String> {
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + (counter++) }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        return CalendarEvents(r).all().map { it.title }.sorted()
    }
    private var counter = 0

    @Test
    fun handshakeUsesPkceAndSingleUseStateAndStoresOnlyCiphertext() {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        assertTrue(url.contains("redirect=https://meka.example/v1/oauth/google/callback"))
        val state = stateOf(url)
        val challenge = URI(url).query.split("&").first { it.startsWith("challenge=") }.removePrefix("challenge=")
        val ok = assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", state, "code-1", null))
        assertEquals("meka@gmail.com", ok.email)
        assertEquals(challenge, Integrations.challenge(provider.lastVerifier!!))
        // Replaying the state, or inventing one, never connects anything.
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", state, "code-1", null))
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", "forged", "code-1", null))
        val row = store.account(ok.accountId)!!
        assertTrue(!String(row.refreshTokenEnc).contains("refresh-1"), "refresh token stored in clear")
    }

    @Test
    fun expiredOrCancelledHandshakesFail() {
        val url1 = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        now += Integrations.STATE_TTL_MS + 1
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", stateOf(url1), "code", null))
        val url2 = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", stateOf(url2), null, "access_denied"))
        assertTrue(store.accounts("home").isEmpty())
    }

    @Test
    fun aConsentWithoutCalendarAccessIsRefusedRatherThanConnectedAndBroken() {
        provider.grantedScope = "openid email"
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", stateOf(url), "code-1", null))
        assertTrue(store.accounts("home").isEmpty())
    }

    @Test
    fun longEventsThatStartedBeforeTheWindowAreStillRemovedWhenCancelled() {
        val acc = connect()
        val trip = RemoteEvent("trip", "Lisbon trip", now - 72 * hour, now + 48 * hour, true, null, "Personal")
        provider.events = listOf(trip)
        integrations.syncAccount(acc)
        assertEquals(listOf("Lisbon trip"), deviceEvents())
        provider.events = emptyList()
        integrations.syncAccount(acc)
        assertEquals(emptyList(), deviceEvents())
    }

    @Test
    fun startReportsMissingCredentialsAndUnknownProviders() {
        configured = false
        assertEquals(Integrations.StartResult.NotConfigured, integrations.start("home", "google"))
        assertEquals(Integrations.StartResult.UnknownProvider, integrations.start("home", "yahoo"))
    }

    @Test
    fun syncMirrorsEventsWritesOnlyChangesAndTracksRemovals() {
        val acc = connect()
        provider.events = listOf(ev("a", "Dentist", 9), ev("b", "Barça v Real Madrid", 20), ev("c", "Bank holiday", 0, allDay = true))
        integrations.syncAccount(acc)
        assertEquals(listOf("Bank holiday", "Barça v Real Madrid", "Dentist"), deviceEvents())
        assertEquals(1, provider.refreshed)

        // Nothing changed: no new ops at all.
        val before = ops.size
        integrations.syncAccount(acc)
        assertEquals(before, ops.size)

        // A rename is one op, chained on the previous one, so devices never see a conflict.
        provider.events = listOf(ev("a", "Dentist (moved)", 9), ev("b", "Barça v Real Madrid", 20), ev("c", "Bank holiday", 0, allDay = true))
        integrations.syncAccount(acc)
        assertEquals(before + 1, ops.size)
        val rename = ops.after("home", 0, 10_000).last().op
        assertEquals(1, rename.baseOpIds.size)

        // Cancelled at the provider: hidden on devices. Restored: visible again.
        provider.events = listOf(ev("a", "Dentist (moved)", 9), ev("c", "Bank holiday", 0, allDay = true))
        integrations.syncAccount(acc)
        assertEquals(listOf("Bank holiday", "Dentist (moved)"), deviceEvents())
        provider.events = listOf(ev("a", "Dentist (moved)", 9), ev("b", "Barça v Real Madrid", 20), ev("c", "Bank holiday", 0, allDay = true))
        integrations.syncAccount(acc)
        assertEquals(listOf("Bank holiday", "Barça v Real Madrid", "Dentist (moved)"), deviceEvents())
        assertEquals("ok", store.account(acc)!!.status)
    }

    @Test
    fun notesAndCallLinksAreMirroredOnlyOnceAnEventHasThem() {
        val acc = connect()
        provider.events = listOf(ev("a", "Standup", 9))
        integrations.syncAccount(acc)
        val before = ops.size
        // Notes and a Meet link appear: two ops, nothing else rewritten.
        provider.events = listOf(ev("a", "Standup", 9).copy(description = "Daily sync", joinUrl = "https://meet.google.com/abc"))
        integrations.syncAccount(acc)
        assertEquals(before + 2, ops.size)
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + (counter++) }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        val e = CalendarEvents(r).all().single()
        assertEquals("Daily sync", e.description)
        assertEquals("https://meet.google.com/abc", e.joinUrl)
        // A link that isn't https is never mirrored; removing the notes clears them.
        provider.events = listOf(ev("a", "Standup", 9).copy(joinUrl = "http://meet.google.com/abc"))
        integrations.syncAccount(acc)
        val r2 = Replica("home", "mac", HlcClock("mac", { now }), InMemoryReplicaStore(), MekaSchema) { "m" + (counter++) }
        r2.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        val e2 = CalendarEvents(r2).all().single()
        assertEquals(null, e2.description)
        assertEquals(null, e2.joinUrl)
    }

    @Test
    fun theEventsWebPageIsMirroredSoItCanBeOpenedToEdit() {
        val acc = connect()
        provider.events = listOf(ev("a", "Standup", 9))
        integrations.syncAccount(acc)
        val before = ops.size
        // An event polled before web links existed gains one op, nothing else is rewritten.
        val link = "https://www.google.com/calendar/event?eid=YWJj"
        provider.events = listOf(ev("a", "Standup", 9).copy(webUrl = link))
        integrations.syncAccount(acc)
        assertEquals(before + 1, ops.size)
        integrations.syncAccount(acc)
        assertEquals(before + 1, ops.size)
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + (counter++) }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(link, CalendarEvents(r).all().single().webUrl)
        // Not https, or too long to keep whole: not mirrored (a cut link would be a broken one).
        provider.events = listOf(ev("a", "Standup", 9).copy(webUrl = "http://www.google.com/calendar/event?eid=YWJj"))
        integrations.syncAccount(acc)
        val r2 = Replica("home", "mac", HlcClock("mac", { now }), InMemoryReplicaStore(), MekaSchema) { "m" + (counter++) }
        r2.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(null, CalendarEvents(r2).all().single().webUrl)
        provider.events = listOf(ev("a", "Standup", 9).copy(webUrl = "https://outlook.live.com/owa/?itemid=" + "A".repeat(2_100)))
        integrations.syncAccount(acc)
        val r3 = Replica("home", "mac2", HlcClock("mac2", { now }), InMemoryReplicaStore(), MekaSchema) { "n" + (counter++) }
        r3.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(null, CalendarEvents(r3).all().single().webUrl)
    }

    @Test
    fun calendarNotesBecomePlainText() {
        val html = "Hi all,<br>Agenda:<ul><li>One</li><li>Two &amp; three</li></ul><p>Join <a href=\"https://meet.google.com/x\">here</a></p>" +
            "<a href=\"https://zoom.us/j/1\">https://zoom.us/j/1</a>&nbsp;&#169;"
        assertEquals("Hi all,\nAgenda:\nOne\nTwo & three\nJoin here (https://meet.google.com/x)\nhttps://zoom.us/j/1 \u00a9", plainText(html))
        assertEquals("Line one\n\nLine two", plainText("Line one\r\n\r\n\r\nLine two  "))
        assertEquals(null, plainText(" <br> "))
        assertEquals(null, plainText(null))
        assertEquals(null, httpsOrNull("http://meet.google.com/a"))
        assertEquals(null, httpsOrNull("https://meet.google.com/a b"))
        assertEquals("https://meet.google.com/a", httpsOrNull(" https://meet.google.com/a "))
    }

    @Test
    fun routesRequireADeviceAndTheCallbackPageReportsTheOutcome() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val secret = devices.enrol("home", "fold")
        application { mekaSync(ops, devices, integrations = integrations, background = { it() }) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/integrations/google/connect").status)
        // Personal-data routes need a device with a registered hardware key, not just its bearer secret.
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/integrations/google/connect") { header("Authorization", "Bearer $secret") }.status)
        val key = TestDeviceKey()
        val reg = WireCodec.encodeDeviceKey(key.publicB64)
        assertEquals(HttpStatusCode.OK, client.post("/v1/devices/key") { with(key) { signed(secret, "/v1/devices/key", reg) } }.status)
        val r = client.post("/v1/integrations/google/connect") { with(key) { signed(secret, "/v1/integrations/google/connect", "") } }
        assertEquals(HttpStatusCode.OK, r.status)
        val url = WireCodec.decodeConnectUrl(r.bodyAsText())
        provider.events = listOf(ev("a", "Dentist", 9))
        val page = client.get("/v1/oauth/google/callback?state=${stateOf(url)}&code=abc").bodyAsText()
        assertTrue(page.contains("is connected"), page)
        assertEquals(listOf("Dentist"), deviceEvents()) // first sync ran right after connecting
        val bad = client.get("/v1/oauth/google/callback?state=nope&code=abc").bodyAsText()
        assertTrue(bad.contains("Not connected"))
        val list = client.post("/v1/integrations/list") { with(key) { signed(secret, "/v1/integrations/list", "") } }.bodyAsText()
        assertEquals(listOf("meka@gmail.com"), WireCodec.decodeAccounts(list).map { it.email })
        // Allow editing over HTTP: the signed body asks for the write scope; Stop editing gives it up.
        provider.grantedScope = "openid email calendar.readonly https://www.googleapis.com/auth/calendar.events"
        val edit = WireCodec.encodeConnectRequest(true)
        val r2 = client.post("/v1/integrations/google/connect") { with(key) { signed(secret, "/v1/integrations/google/connect", edit) } }
        assertEquals(true, provider.askedEditing)
        val page2 = client.get("/v1/oauth/google/callback?state=${stateOf(WireCodec.decodeConnectUrl(r2.bodyAsText()))}&code=abc").bodyAsText()
        assertTrue(page2.contains("may now add and change events"), page2)
        val listed = client.post("/v1/integrations/list") { with(key) { signed(secret, "/v1/integrations/list", "") } }.bodyAsText()
        assertEquals(listOf(true), WireCodec.decodeAccounts(listed).map { it.canEdit })
        val path = "/v1/integrations/google/editing"
        val on = WireCodec.encodeEditingChange(WireCodec.EditingChange("meka@gmail.com", editing = true))
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(key) { signed(secret, path, on) } }.status)
        val off = WireCodec.encodeEditingChange(WireCodec.EditingChange("meka@gmail.com", editing = false))
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $secret"); setBody(off) }.status)
        val stranger = WireCodec.encodeEditingChange(WireCodec.EditingChange("nobody@gmail.com", editing = false))
        assertEquals(HttpStatusCode.NotFound, client.post(path) { with(key) { signed(secret, path, stranger) } }.status)
        val stopped = client.post(path) { with(key) { signed(secret, path, off) } }
        assertEquals(HttpStatusCode.OK, stopped.status)
        assertEquals(listOf(false), WireCodec.decodeAccounts(stopped.bodyAsText()).map { it.canEdit })
    }

    /** The store on Postgres (CI's service container); skipped when MEKA_TEST_DB_URL is unset. */
    @Test
    fun postgresStoreKeepsWhetherAnAccountMayEdit() {
        val url = System.getenv("MEKA_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            PostgresDeviceRegistry(ds).enrol("edit-hh", "fold", "Fold")
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM integration_account WHERE household_id = 'edit-hh'") }
            val pg = os.meka.backend.integrations.PostgresIntegrationStore(PostgresOpStore(ds))
            pg.transaction { pg.saveState("edit-state", os.meka.backend.integrations.PendingConnect("edit-hh", "google", "v".repeat(40), now, editing = true)) }
            assertEquals(true, pg.transaction { pg.takeState("edit-state") }!!.editing)
            val id = pg.transaction { pg.upsertAccount("edit-hh", "google", "e@gmail.com", byteArrayOf(1), canEdit = true) { "accedit1" } }
            assertEquals(true, pg.account(id)!!.canEdit)
            assertEquals(false, pg.transaction { pg.stopEditing("edit-hh", "google", "x@gmail.com") })
            assertTrue(pg.transaction { pg.stopEditing("edit-hh", "google", "e@gmail.com") })
            assertEquals(false, pg.account(id)!!.canEdit)
            pg.transaction { pg.upsertAccount("edit-hh", "google", "e@gmail.com", byteArrayOf(2), canEdit = true) { "accedit2" } }
            assertEquals(true, pg.accounts("edit-hh").single().canEdit)
            pg.transaction { pg.upsertAccount("edit-hh", "google", "e@gmail.com", byteArrayOf(3)) { "accedit3" } }
            assertEquals(false, pg.accounts("edit-hh").single().canEdit) // a read-only reconnect gives it up
        }
    }

    /** Calendar editing, slice 2a, on Postgres: the mirror keeps provider ids, and edits are read back field by field. */
    @Test
    fun postgresKeepsRemoteIdsAndReadsEditsBack() {
        val url = System.getenv("MEKA_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            PostgresDeviceRegistry(ds).enrol("edits-hh", "fold", "Fold")
            ds.connection.use { c ->
                c.createStatement().execute("DELETE FROM integration_account WHERE household_id = 'edits-hh'")
                c.createStatement().execute("DELETE FROM op_log WHERE household_id = 'edits-hh'")
            }
            val opStore = PostgresOpStore(ds)
            val pg = os.meka.backend.integrations.PostgresIntegrationStore(opStore)
            val acc = pg.transaction { pg.upsertAccount("edits-hh", "google", "e@gmail.com", byteArrayOf(1), canEdit = true) { "acceditsrid" } }
            val row = os.meka.backend.integrations.MirrorRow("evpg1", acc, 10, false, emptyMap(), 20, false, "cal1/abc")
            pg.transaction { pg.putMirror("edits-hh", row) }
            assertEquals("cal1/abc", pg.mirror("edits-hh", acc)["evpg1"]!!.remoteId)
            // A row written without an id keeps the one it had.
            pg.transaction { pg.putMirror("edits-hh", row.copy(startMs = 11, remoteId = null)) }
            assertEquals("cal1/abc", pg.mirror("edits-hh", acc)["evpg1"]!!.remoteId)
            assertEquals(11L, pg.mirror("edits-hh", acc)["evpg1"]!!.startMs)

            fun op(id: String, entity: String, field: String, v: os.meka.core.sync.FieldValue, wall: Long) = os.meka.core.sync.Op(
                id, "edits-hh", os.meka.core.domain.EntityTypes.EVENT_EDIT, entity, field, v, os.meka.core.sync.Hlc(wall, 0, "fold"), emptyList(), "fold",
            )
            opStore.transaction {
                opStore.append(op("o1", "e1", "kind", os.meka.core.sync.FieldValue.Text("ADD"), 1))
                opStore.append(op("o2", "e1", "undone", os.meka.core.sync.FieldValue.Bool(false), 1))
                opStore.append(op("o3", "e1", "undone", os.meka.core.sync.FieldValue.Bool(true), 2))
                opStore.append(op("o4", "e2", "kind", os.meka.core.sync.FieldValue.Text("DELETE"), 1))
            }
            val all = opStore.latestFields("edits-hh", os.meka.core.domain.EntityTypes.EVENT_EDIT)
            assertEquals(setOf("e1", "e2"), all.keys)
            assertEquals(os.meka.core.sync.FieldValue.Bool(true), all.getValue("e1")["undone"])
            assertEquals(mapOf("kind" to os.meka.core.sync.FieldValue.Text("DELETE")), opStore.latestFields("edits-hh", os.meka.core.domain.EntityTypes.EVENT_EDIT, "e2")["e2"])
            assertTrue(opStore.latestFields("edits-hh", os.meka.core.domain.EntityTypes.TASK).isEmpty())
        }
    }
}
