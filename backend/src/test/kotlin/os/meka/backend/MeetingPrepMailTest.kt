package os.meka.backend

import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.EventGuests
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.InMemoryMailStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MailHeader
import os.meka.backend.integrations.MailProvider
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.GuestMail
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Meeting prep, slice 4: the server looks each guest of a meeting in the next day and a half up in Meka's mailboxes by
 * their address and writes their newest email (name, when, subject) on the event; the address never leaves the server.
 * Invented people only (rule 13).
 */
class MeetingPrepMailTest {
    private val hour = 3_600_000L
    private var now = 1_790_985_600_000L // 2026-10-03T00:00Z

    private class NoCipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private class Calendar(var events: List<RemoteEvent>) : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "calendar.events"
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) = ""
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) = TokenSet("a", "r", 3600)
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean) = TokenSet("a", null, 3600)
        override fun accountEmail(accessToken: String) = "me@example.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long) = events
    }

    private object Mailbox : MailProvider {
        override val id = "gmail"
        override val oauth = "google"
        override val requiredScopes = emptyList<String>()
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) = ""
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) = TokenSet("a", "r", 3600)
        override fun refresh(client: OAuthClient, refreshToken: String) = TokenSet("a", null, 3600)
        override fun accountEmail(accessToken: String) = "me@example.com"
        override fun inbox(accessToken: String): os.meka.backend.integrations.MailboxCheck = throw UnsupportedOperationException()
    }

    private fun header(id: String, from: String, at: Long, subject: String) =
        MailHeader(id, null, from, null, subject, at, unread = false, inInbox = true)

    private fun meeting(id: String, startH: Long, people: List<Pair<String, String?>>) = RemoteEvent(
        id, "Quote review", now + startH * hour, now + (startH + 1) * hour, false, null, "Work",
        guests = people.map { it.first }, guestAddresses = people.mapNotNull { (n, a) -> a?.let { n to it } }.toMap(),
    )

    @Test
    fun eachGuestsNewestEmailIsWrittenOnMeetingsInTheNextDayAndAHalf() {
        val store = InMemoryIntegrationStore()
        val ops = InMemoryServerOpStore()
        val ms = InMemoryMailStore()
        val cal = Calendar(emptyList())
        val i = Integrations(
            store, ops, mapOf("google" to cal), { OAuthClient("cid", "s") }, NoCipher(), "https://meka.example", { now },
            mail = mapOf("gmail" to Mailbox), mailStore = ms,
        )
        var n = 0
        val calId = store.upsertAccount("home", "google", "me@example.com", "r".toByteArray()) { "c" + n++ }
        val box1 = store.upsertAccount("home", "gmail", "me@example.com", "r".toByteArray()) { "m" + n++ }
        val box2 = store.upsertAccount("home", "gmail", "work@example.com", "r".toByteArray()) { "m" + n++ }
        // Another household's mailbox never counts.
        val other = store.upsertAccount("away", "gmail", "x@example.com", "r".toByteArray()) { "m" + n++ }
        ms.put("home", box1, header("1", "sam.taylor@example.com", now - 30 * hour, "Quote"))
        ms.put("home", box2, header("2", "Sam.Taylor@example.com", now - 2 * hour, "Figures\tattached"))
        ms.put("home", box1, header("3", "sam.taylor@example.com", now + hour, "From the future"))
        ms.put("away", other, header("4", "alex@example.com", now - hour, "Not his"))
        ms.put("home", box1, header("5", "robin@example.com", now - hour, "Lunch"))
        cal.events = listOf(
            meeting("soon", 3, listOf("Sam Taylor" to "sam.taylor@example.com", "Alex Green" to "alex@example.com", "Jo" to null)),
            meeting("later", 48, listOf("Robin Lee" to "robin@example.com")),
        )
        i.syncAccount(calId)

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + (n++) }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        val events = CalendarEvents(r).all().associateBy { it.title + it.startAtMs }
        val soon = events.values.single { it.startAtMs == now + 3 * hour }
        assertEquals(listOf(GuestMail("Sam Taylor", now - 2 * hour, "Figures attached")), soon.guestMail)
        assertEquals(listOf("Sam Taylor", "Alex Green", "Jo"), soon.guests)
        // Two days out: nothing looked up yet.
        assertEquals(emptyList(), events.values.single { it.startAtMs == now + 48 * hour }.guestMail)
        // No address is ever written.
        assertTrue(ops.after("home", 0, 10_000).filter { it.op.field == "guests" || it.op.field == "guestMail" }
            .none { it.op.value.toString().contains("@") })

        // Once it's over, the field is cleared.
        now += 4 * hour
        i.syncAccount(calId)
        val r2 = Replica("home", "mac", HlcClock("mac", { now }), InMemoryReplicaStore(), MekaSchema) { "e" + (n++) }
        r2.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(emptyList(), CalendarEvents(r2).all().single { it.startAtMs == now - hour }.guestMail)
    }

    @Test
    fun withoutMailAccountsNothingIsWritten() {
        val store = InMemoryIntegrationStore()
        val ops = InMemoryServerOpStore()
        val cal = Calendar(listOf(meeting("soon", 3, listOf("Sam Taylor" to "sam@example.com"))))
        val i = Integrations(
            store, ops, mapOf("google" to cal), { OAuthClient("cid", "s") }, NoCipher(), "https://meka.example", { now },
            mail = mapOf("gmail" to Mailbox), mailStore = InMemoryMailStore(),
        )
        val calId = store.upsertAccount("home", "google", "me@example.com", "r".toByteArray()) { "c1" }
        i.syncAccount(calId)
        assertTrue(ops.after("home", 0, 10_000).none { it.op.field == "guestMail" })
    }

    @Test
    fun providersKeepEachGuestsAddressBesideTheName() {
        val attendees = Json.parseToJsonElement("""[
            {"email":"me@example.com","self":true},
            {"email":"Sam.Taylor@Example.com","displayName":"Sam Taylor"},
            {"email":"alex_green@example.org"}
        ]""").jsonArray
        assertEquals(
            listOf("Sam Taylor" to "sam.taylor@example.com", "Alex Green" to "alex_green@example.org"),
            EventGuests.googlePeople(attendees, null),
        )
        val graph = Json.parseToJsonElement("""[{"type":"required","emailAddress":{"name":"Sam Taylor","address":"sam@example.com"}}]""").jsonArray
        val org = Json.parseToJsonElement("""{"emailAddress":{"name":"Me","address":"me@example.com"}}""").jsonObject
        assertEquals(mapOf("Sam Taylor" to "sam@example.com"), EventGuests.addresses(EventGuests.graphPeople(graph, org, "me@example.com")))
    }
}
