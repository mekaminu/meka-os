package os.meka.backend

import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.CalendarWriter
import os.meka.backend.integrations.EntityReader
import os.meka.backend.integrations.GoogleCalendar
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MicrosoftCalendar
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.ReconnectRequired
import os.meka.backend.integrations.RemoteCopy
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.backend.integrations.WriteRefused
import os.meka.core.domain.CalendarEditRules
import os.meka.core.domain.CalendarEdits
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.EventDraft
import os.meka.core.domain.EventEditChange
import os.meka.core.domain.EventEditResult
import os.meka.core.domain.EventEditState
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PushRequest
import os.meka.core.sync.Replica
import os.meka.core.sync.SyncService
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calendar editing, slice 2a: the server sends Meka's edits to the provider after the undo window. */
class CalendarWriterTest {
    private val hour = 3_600_000L
    private var now = 1_791_446_400_000L // 2026-10-08T08:00Z

    private class FakeProvider : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "https://www.googleapis.com/auth/calendar.events"
        var events = listOf<RemoteEvent>()
        val copies = HashMap<String, RemoteCopy>()
        val created = mutableListOf<Pair<EventDraft, String>>()
        val updated = mutableListOf<Triple<String, EventDraft, Set<EventEditChange>>>()
        val notified = mutableListOf<Boolean>()
        val deleted = mutableListOf<String>()
        var refreshed = 0
        var fail: (() -> Nothing)? = null
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) =
            "https://accounts.example/auth?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) =
            TokenSet("access-1", "refresh-1", 3600, "openid email calendar.readonly https://www.googleapis.com/auth/calendar.events")
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean): TokenSet { refreshed++; return TokenSet("access-2", null, 3600) }
        override fun accountEmail(accessToken: String) = "Meka@Gmail.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long) = events
        override fun event(accessToken: String, remoteId: String): RemoteCopy? { fail?.invoke(); return copies[remoteId] }
        override fun createEvent(accessToken: String, draft: EventDraft, idemKey: String): String {
            fail?.invoke(); created += draft to idemKey; return "cal1/new"
        }
        override fun updateEvent(accessToken: String, remoteId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean) {
            fail?.invoke(); updated += Triple(remoteId, draft, changes); notified += notifyGuests
        }
        override fun deleteEvent(accessToken: String, remoteId: String, notifyGuests: Boolean): Boolean {
            fail?.invoke(); deleted += remoteId; notified += notifyGuests; return true
        }
    }

    private class PlainCipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher.reversedArray()
    }

    private val provider = FakeProvider()
    private val store = InMemoryIntegrationStore(listOf("home"))
    private val ops = InMemoryServerOpStore()
    private val integrations = Integrations(
        store, ops, mapOf("google" to provider), { OAuthClient("cid", "secret") }, PlainCipher(), "https://meka.example", { now },
    )
    private var woken = 0
    private val writer = integrations.writer(EntityReader.scanning(ops)) { woken++ }

    private val account: String = run {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google", editing = true)).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", state, "code", null)).accountId
    }

    // The Fold, as a replica that pushes to and pulls from the server's op log.
    private val replicaStore = InMemoryReplicaStore()
    private var n = 0
    private val fold = Replica("home", "fold", HlcClock("fold", { now }), replicaStore, MekaSchema) { "f" + (n++) }
    private val edits = CalendarEdits(fold, { now }, { "0123456789abcdef0123456789abcd" + (10 + n++) }) { _, _ -> true }
    private var pulled = 0L
    private fun sync() {
        val pending = replicaStore.pendingPush(10_000)
        if (pending.isNotEmpty()) SyncService(ops).push(PushRequest("home", "fold", pending))
        replicaStore.markPushed(pending.map { it.opId })
        val page = ops.after("home", pulled, 10_000)
        fold.applyRemoteBatch(page.map { it.op })
        page.lastOrNull()?.let { pulled = it.seq }
    }

    private val dentist = RemoteEvent("cal1/dent", "Dentist", now + 5 * hour, now + 6 * hour, false, "High St", "Personal")
    private fun mine(e: EventDraft, guests: Int = 0, organiser: Boolean = true) = RemoteCopy(e, organiser, guests)
    private fun mirrored(): os.meka.core.domain.CalendarEvent {
        provider.events = listOf(dentist)
        integrations.syncAccount(account)
        sync()
        return CalendarEvents(fold).all().single { it.title == "Dentist" }
    }
    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id
    private fun state(id: String) = edits.edit(id)!!.state(now)

    @Test
    fun anAddIsSentOnceAfterTheUndoWindowAndEveryDeviceHearsSo() {
        val id = made(edits.add("google", "meka@gmail.com", EventDraft("Gym", now + 8 * hour, now + 9 * hour, false)))
        sync()
        writer.sweep()
        assertTrue(provider.created.isEmpty(), "sent inside the undo window")
        now += CalendarEditRules.UNDO_MS + CalendarEditRules.SERVER_GRACE_MS
        val refreshes = provider.refreshed
        writer.sweep()
        assertEquals(listOf(EventDraft("Gym", now - 7_000 + 8 * hour, now - 7_000 + 9 * hour, false)), provider.created.map { it.first })
        assertEquals(CalendarWriter.idemKey(id), provider.created.single().second)
        assertTrue(provider.created.single().second.all { it in '0'..'9' || it in 'a'..'f' })
        // The account is synced straight after, and the devices are woken.
        assertTrue(provider.refreshed >= refreshes + 2)
        assertEquals(1, woken)
        sync()
        assertEquals(EventEditState.DONE, state(id))
        assertEquals("Added “Gym” to Google", CalendarEditRules.line(edits.edit(id)!!, now))
        // Never twice.
        writer.sweep()
        assertEquals(1, provider.created.size)
        assertEquals(1, woken)
    }

    @Test
    fun anUndoneEditIsNeverSent() {
        val id = made(edits.add("google", "meka@gmail.com", EventDraft("Gym", now + 8 * hour, now + 9 * hour, false)))
        now += 2_000
        assertTrue(edits.undo(id))
        sync()
        now += hour
        writer.sweep()
        assertTrue(provider.created.isEmpty())
        sync()
        assertEquals(EventEditState.UNDONE, state(id))
    }

    @Test
    fun aMoveIsCheckedAgainstGooglesCopyAndSendsOnlyTheTime() {
        val ev = mirrored()
        provider.copies["cal1/dent"] = mine(EventDraft("Dentist", dentist.startMs, dentist.endMs, false, "Elm Rd", null))
        val id = made(edits.move(ev, ev.startAtMs + 2 * hour))
        sync()
        now += 10_000
        writer.sweep()
        // Google's location changed meanwhile, but a move doesn't touch it: no clash, only the time is sent.
        val (remote, draft, changes) = provider.updated.single()
        assertEquals("cal1/dent", remote)
        assertEquals(setOf(EventEditChange.TIME), changes)
        assertEquals(dentist.startMs + 2 * hour, draft.startAtMs)
        assertEquals(dentist.endMs + 2 * hour, draft.endAtMs)
        assertEquals(listOf(false), provider.notified)
        sync()
        assertEquals("Moved “Dentist” in Google", CalendarEditRules.line(edits.edit(id)!!, now))
    }

    @Test
    fun aClashSendsNothingAndHandsTheirVersionBack() {
        val ev = mirrored()
        provider.copies["cal1/dent"] = mine(EventDraft("Dentist", dentist.startMs - hour, dentist.endMs - hour, false, "High St", null))
        val id = made(edits.move(ev, ev.startAtMs + 2 * hour))
        sync()
        now += 10_000
        writer.sweep()
        assertTrue(provider.updated.isEmpty())
        sync()
        val e = edits.edit(id)!!
        assertEquals(EventEditState.CLASH, e.state(now))
        assertEquals(EventDraft("Dentist", dentist.startMs - hour, dentist.endMs - hour, false, "High St", null), e.theirs)
    }

    @Test
    fun keepMineSendsTheEditAgainAgainstTheirVersionAndKeepTheirsSendsNothing() {
        val ev = mirrored()
        val theirs = EventDraft("Dentist", dentist.startMs - hour, dentist.endMs - hour, false, "Elm Rd", "Bring the form")
        provider.copies["cal1/dent"] = mine(theirs)
        val id = made(edits.move(ev, ev.startAtMs + 2 * hour))
        sync()
        now += 10_000
        writer.sweep()
        sync()
        // Their place and notes come back with the clash, so the resend is checked against all of their version.
        assertEquals(theirs, edits.edit(id)!!.theirs)

        val again = made(edits.keepMine(id))
        sync()
        now += 10_000
        writer.sweep()
        sync()
        assertEquals(EventEditState.DONE, state(again))
        val (remote, draft, changes) = provider.updated.single()
        assertEquals("cal1/dent", remote)
        assertEquals(setOf(EventEditChange.TIME), changes)
        assertEquals(dentist.startMs + 2 * hour, draft.startAtMs)
        // Their other changes are kept, not overwritten with what MEKA saw.
        assertEquals("Elm Rd", draft.location)
        assertEquals("Bring the form", draft.notes)

        // Another clash, kept as theirs: nothing more is sent.
        provider.copies["cal1/dent"] = mine(theirs.copy(title = "Dentist (moved)"))
        val renamed = made(edits.change(ev, CalendarEditRules.draftOf(ev).copy(title = "Dentist check-up")))
        sync()
        now += 10_000
        writer.sweep()
        sync()
        assertEquals(EventEditState.CLASH, state(renamed))
        assertTrue(edits.keepTheirs(renamed))
        sync()
        now += hour
        writer.sweep()
        assertEquals(1, provider.updated.size)
    }

    @Test
    fun otherPeoplesEventsAreNeverChanged() {
        val ev = mirrored()
        provider.copies["cal1/dent"] = mine(CalendarEditRules.draftOf(ev), guests = 2, organiser = false)
        val id = made(edits.delete(ev, guestsOk = true))
        sync()
        now += 10_000
        writer.sweep()
        assertTrue(provider.deleted.isEmpty())
        sync()
        assertEquals(EventEditState.REFUSED, state(id))
        assertEquals("Someone else organises it · MEKA only changes your own events", CalendarEditRules.line(edits.edit(id)!!, now))
    }

    @Test
    fun deletingAMeetingWithGuestsNeedsMekasSecondTap() {
        val ev = mirrored()
        provider.copies["cal1/dent"] = mine(CalendarEditRules.draftOf(ev), guests = 3)
        val first = made(edits.delete(ev))
        sync()
        now += 10_000
        writer.sweep()
        assertTrue(provider.deleted.isEmpty())
        sync()
        assertEquals("This cancels it for 3 people", CalendarEditRules.line(edits.edit(first)!!, now))
        assertEquals(3, edits.edit(first)!!.guests)

        made(edits.delete(ev, guestsOk = true))
        sync()
        now += 10_000
        writer.sweep()
        assertEquals(listOf("cal1/dent"), provider.deleted)
        assertEquals(listOf(true), provider.notified) // the guests get Google's cancellation
    }

    @Test
    fun anAccountThatStoppedEditingRefusesAtOnce() {
        val id = made(edits.add("google", "meka@gmail.com", EventDraft("Gym", now + 8 * hour, now + 9 * hour, false)))
        sync()
        integrations.stopEditing("home", "google", "meka@gmail.com")
        now += 10_000
        writer.sweep()
        assertTrue(provider.created.isEmpty())
        sync()
        assertEquals("Editing isn't allowed for meka@gmail.com · Allow editing in Calendars", CalendarEditRules.line(edits.edit(id)!!, now))
    }

    @Test
    fun providerTroubleIsRetriedWithPausesAndGivenUpAfterADay() {
        val id = made(edits.add("google", "meka@gmail.com", EventDraft("Gym", now + 8 * hour, now + 9 * hour, false)))
        sync()
        now += 10_000
        provider.fail = { throw IllegalStateException("provider HTTP 503") }
        val next = writer.sweep()
        assertEquals(now + CalendarWriter.RETRY_MS, next)
        writer.sweep() // too soon: not tried again
        sync()
        assertEquals(EventEditState.SENDING, state(id))
        now += CalendarWriter.RETRY_MS
        assertEquals(now + 2 * CalendarWriter.RETRY_MS, writer.sweep())
        now += 25 * hour
        writer.sweep()
        sync()
        assertEquals("Couldn't send to Google · Google couldn't be reached for a day", CalendarEditRules.line(edits.edit(id)!!, now))
    }

    @Test
    fun aLapsedSignInOrARefusalSaysWhatToDo() {
        val a = made(edits.add("google", "meka@gmail.com", EventDraft("Gym", now + 8 * hour, now + 9 * hour, false)))
        sync()
        now += 10_000
        provider.fail = { throw ReconnectRequired("gone") }
        writer.sweep()
        sync()
        assertEquals("Couldn't send to Google · Reconnect Google in Calendars", CalendarEditRules.line(edits.edit(a)!!, now))

        val b = made(edits.add("google", "meka@gmail.com", EventDraft("Run", now + 8 * hour, now + 9 * hour, false)))
        sync()
        now += 10_000
        provider.fail = { throw WriteRefused("403") }
        writer.sweep()
        sync()
        assertEquals(EventEditState.REFUSED, state(b))
    }

    @Test
    fun anEventMirroredBeforeItsIdWasKeptWaitsForTheNextPoll() {
        val ev = mirrored()
        val row = store.mirror("home", account).values.single()
        assertEquals("cal1/dent", row.remoteId)
        store.putMirror("home", row.copy(remoteId = null))
        provider.copies["cal1/dent"] = mine(CalendarEditRules.draftOf(ev))
        val id = made(edits.move(ev, ev.startAtMs + hour))
        sync()
        now += 10_000
        writer.sweep()
        assertTrue(provider.updated.isEmpty())
        // The poll fills the id in; the retry then sends it.
        integrations.syncAccount(account)
        assertEquals("cal1/dent", store.mirror("home", account).values.single().remoteId)
        now += CalendarWriter.RETRY_MS
        writer.sweep()
        assertEquals(1, provider.updated.size)
        sync()
        assertEquals(EventEditState.DONE, state(id))
    }

    @Test
    fun aDeletedEventIsAlreadyGoneForADeleteAndRefusedForAChange() {
        val ev = mirrored()
        val del = made(edits.delete(ev))
        val change = made(edits.move(ev, ev.startAtMs + hour))
        sync()
        now += 10_000
        writer.sweep()
        sync()
        assertEquals(EventEditState.DONE, state(del))
        assertEquals("It was deleted in Google meanwhile", CalendarEditRules.line(edits.edit(change)!!, now))
        assertTrue(provider.deleted.isEmpty() && provider.updated.isEmpty())
    }

    @Test
    fun theProvidersWriteBodiesCarryOnlyWhatChanged() {
        val g = GoogleCalendar()
        val d = EventDraft("Dentist", 1_791_478_800_000L, 1_791_482_400_000L, false, null, "Bring the form")
        assertEquals(
            """{"id":"mekaabc","summary":"Dentist","start":{"dateTime":"2026-10-08T17:00:00Z"},"end":{"dateTime":"2026-10-08T18:00:00Z"},"location":null,"description":"Bring the form"}""",
            g.googleBody(d, EventEditChange.entries.toSet(), "mekaabc").toString(),
        )
        assertEquals(
            """{"start":{"dateTime":"2026-10-08T17:00:00Z","date":null},"end":{"dateTime":"2026-10-08T18:00:00Z","date":null}}""",
            g.googleBody(d, setOf(EventEditChange.TIME), null).toString(),
        )
        val day = EventDraft("Holiday", 1_791_417_600_000L, 1_791_504_000_000L, true)
        assertEquals(
            """{"start":{"date":"2026-10-08","dateTime":null},"end":{"date":"2026-10-09","dateTime":null}}""",
            g.googleBody(day, setOf(EventEditChange.TIME), null).toString(),
        )
        assertEquals(
            """{"subject":"Dentist","start":{"dateTime":"2026-10-08T17:00:00","timeZone":"UTC"},"end":{"dateTime":"2026-10-08T18:00:00","timeZone":"UTC"},"isAllDay":false}""",
            MicrosoftCalendar().graphBody(d, setOf(EventEditChange.TITLE, EventEditChange.TIME)).toString(),
        )
        assertEquals("""{"location":{"displayName":""}}""", MicrosoftCalendar().graphBody(d, setOf(EventEditChange.LOCATION)).toString())
    }

    @Test
    fun outcomesHaveFixedOpIds() {
        assertEquals(CalendarWriter.opId("abc", "status"), CalendarWriter.opId("abc", "status"))
        assertTrue(CalendarWriter.opId("abc", "status") != CalendarWriter.opId("abd", "status"))
        assertTrue(CalendarWriter.opId("abc", "statusAtMs").all { it.isLetterOrDigit() })
        assertNull(store.mirror("home", account).values.firstOrNull())
    }
}
