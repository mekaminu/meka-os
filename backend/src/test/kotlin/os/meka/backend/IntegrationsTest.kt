package os.meka.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.backend.integrations.CalendarProvider
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
        var grantedScope: String? = null
        var events = listOf<RemoteEvent>()
        var lastVerifier: String? = null
        var refreshed = 0
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) =
            "https://accounts.example/auth?state=$state&challenge=$codeChallenge&redirect=$redirectUri"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String): TokenSet {
            lastVerifier = verifier; return TokenSet("access-1", "refresh-1", 3600, grantedScope)
        }
        override fun refresh(client: OAuthClient, refreshToken: String): TokenSet {
            check(refreshToken == "refresh-1") { "decrypted the wrong token" }
            refreshed++; return TokenSet("access-2", null, 3600)
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

    private fun connect(): String {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        val r = assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", stateOf(url), "code-1", null))
        return r.accountId
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
    }
}
