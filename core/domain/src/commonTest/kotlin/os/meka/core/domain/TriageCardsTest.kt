package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages assistant, slice 2: triaged messages as synced cards — lane, gist and draft, never the message text. */
class TriageCardsTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(day: Long, minute: Int) = cal.toEpochMs(day, minute)
    private fun cards(d: Device) = TriageCards(d.replica, { world.clock.nowMs }, cal)
    private val fold = cards(a)
    private val mac = cards(m)

    init { world.clock.nowMs = at(fri, 14 * 60 + 3) }

    private val text = "are you coming Saturday? my number is 07700 900123"
    private val tunde = CapturedItem("wa-1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Tunde", text, null, at(fri, 14 * 60 + 2))
    private val reply = MessageTriage(TriageLane.NEEDS_REPLY, draft = "Yes, I'll be there — what time?", summary = "Asks if you're coming Saturday")

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    @Test
    fun aNeedsAReplyCardShowsOnTheMacWithItsDraftButNeverTheMessage() {
        val card = assertNotNull(fold.save(tunde, reply))
        assertEquals("Tunde · 14:02", card.from)
        assertEquals("Asks if you're coming Saturday", card.gist)
        assertEquals("Yes, I'll be there — what time?", card.draft)
        assertEquals("Tunde, Needs a reply: Asks if you're coming Saturday. Suggested reply: Yes, I'll be there — what time?", card.spoken)
        syncBoth()
        assertEquals(listOf(card), mac.open())
        // Only the gist and the draft travel: the message itself stays on the phone.
        val stored = m.replica.entities(EntityTypes.TRIAGE_CARD).single()
        assertTrue(stored.fields.values.none { it.toString().contains("07700") }, stored.fields.toString())
    }

    @Test
    fun aRepostWritesNothingAndAResolvedCardNeverComesBack() {
        assertNotNull(fold.save(tunde, reply))
        assertNull(fold.save(tunde, reply))
        assertTrue(fold.known("wa-1"))
        syncBoth()
        assertTrue(mac.resolve("wa-1", TriageResolution.DISMISSED))
        assertFalse(mac.resolve("wa-1", TriageResolution.DISMISSED))
        syncBoth()
        assertTrue(fold.open().isEmpty())
        assertNull(fold.save(tunde, reply))
        assertTrue(fold.open().isEmpty())
        val e = a.replica.entities(EntityTypes.TRIAGE_CARD).single()
        assertNull(e[TriageCardFields.DRAFT].textOrNull)
        assertNull(e[TriageCardFields.SUMMARY].textOrNull)
        assertEquals("dismissed", e[TriageCardFields.RESOLUTION].textOrNull)
    }

    @Test
    fun repliesComeFirstThenFyiNewestFirstAndActionsAreLeftToRequestCards() {
        val group = CapturedItem("wa-2", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Femi", "@Meka tickets are out", "Barça lads", at(fri, 13 * 60))
        val fyi = CapturedItem("sms-3", CaptureApp.SMS, CaptureKind.MESSAGE, "Mum", "landed safely", null, at(fri, 14 * 60))
        val action = CapturedItem("wa-4", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Wife", "get milk", null, at(fri, 14 * 60 + 1))
        fold.save(fyi, MessageTriage(TriageLane.FYI, summary = "Landed safely"))
        fold.save(group, MessageTriage(TriageLane.NEEDS_REPLY, summary = "Tickets are out"))
        assertNull(fold.save(action, MessageTriage(TriageLane.ACTION, summary = "Get milk")))
        fold.save(tunde, reply)
        assertEquals(listOf("wa-1", "wa-2", "sms-3"), fold.open().map { it.id })
        val g = fold.find("wa-2")!!
        assertEquals("Femi in Barça lads · 13:00", g.from)
        assertTrue(g.mentioned)
        assertNull(g.draft) // the model's draft didn't pass the checks
        assertTrue(fold.known("wa-4"))
        // An FYI drops off after two days, a reply after a week.
        world.clock.nowMs = at(fri + 3, 9 * 60)
        assertEquals(listOf("wa-1", "wa-2"), fold.open().map { it.id })
        world.clock.nowMs = at(fri + 8, 9 * 60)
        assertTrue(fold.open().isEmpty())
    }

    @Test
    fun aMessageKeptFromTheAiSaysSo() {
        val mum = CapturedItem("wa-5", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Mum", "private", null, at(fri, 14 * 60))
        val card = assertNotNull(fold.save(mum, MessageTriage(TriageLane.FYI), local = true))
        assertEquals(TriageCard.LOCAL_LINE, card.gist)
        assertEquals(CaptureApp.WHATSAPP, card.app)
    }
}
