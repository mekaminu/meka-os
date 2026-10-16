package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drafted replies, slice 4: the draft on the wrist, Send only for a draft read whole, never one that's gone. */
class WatchDraftTest {
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val hour = 3_600_000L

    private fun item(
        id: String, agoMs: Long = hour, draft: String? = "Yes, I can take them. See you at 10.", name: String = "Sam Taylor",
        preview: String = "Hi Alex, can you take the boys on Saturday? Sam", extra: Map<String, FieldValue> = emptyMap(),
    ): MailItem {
        val subject = "Saturday"
        val needs = MailAutoDraftRules.needsReply(MailSortReason.PERSON, "sam@example.com", subject, preview)
        return MailNeedsRules.read(
            id,
            MailNeedsRules.serverFields("Personal", name, "sam@example.com", subject, preview, now - agoMs, 1) +
                MailAutoDraftRules.serverFields(needs, MailAutoDraftRules.wants(subject, preview)) +
                (draft?.let { MailAutoDraftRules.draftedFields(it, now - agoMs + 60_000L) } ?: emptyMap()) + extra,
        )!!
    }

    @Test
    fun theNewestDraftWithWhoAndWhatTheyWant() {
        val v = assertNotNull(WatchDraftRules.view(listOf(item("old", agoMs = 3 * hour), item("new")), now))
        assertEquals("new", v.mailId)
        assertEquals("DRAFTED REPLY · 1 OF 2", v.label)
        assertEquals("Sam Taylor · Personal", v.from)
        assertEquals("Can you take the boys on Saturday?", v.wants)
        assertEquals("Yes, I can take them. See you at 10.", v.draft)
        assertTrue(v.canSend)
        assertEquals(WatchDraftRules.NOTE, v.note)
        assertEquals("+1 more on your phone", v.more)
        assertTrue(v.spoken.contains("goes only when you tap Send"))

        val one = assertNotNull(WatchDraftRules.view(listOf(item("a")), now))
        assertEquals(WatchDraftRules.LABEL, one.label)
        assertNull(one.more)
    }

    @Test
    fun nothingWithoutADraftOrOnceHeHasDealtWithIt() {
        assertNull(WatchDraftRules.view(emptyList(), now))
        // Needs a reply but no draft (the AI couldn't make one): the wrist shows nothing; the count stays on the phone.
        assertNull(WatchDraftRules.view(listOf(item("a", draft = null)), now))
        // An FYI email never had a draft.
        assertNull(WatchDraftRules.view(listOf(item("a", draft = null, preview = "Here are the photos.")), now))
        // Not needed, sent, or snoozed: gone from the wrist.
        assertNull(WatchDraftRules.view(listOf(item("a", extra = MailAutoDraftRules.dismissFields(now))), now))
        assertNull(WatchDraftRules.view(listOf(item("a", extra = MailNeedsRules.replyFields("Yes", now))), now))
        val snoozed = MailNeedsRules.snoozeFields(now + 10 * hour)
        assertNull(WatchDraftRules.view(listOf(item("a", extra = snoozed)), now))
        // A reply he took back with Undo before it went: the draft comes back (on the phone too).
        val undone = MailNeedsRules.replyFields("Yes", now - 2_000L) + MailNeedsRules.undoDoneFields(now - 1_000L)
        assertEquals("a", WatchDraftRules.view(listOf(item("a", extra = undone)), now)?.mailId)
        assertTrue(MailAutoDraftRules.needsReply(item("a", extra = undone)))
        // Once the server has sent it, it never comes back.
        val sent = MailNeedsRules.replyFields("Yes", now - 2_000L) + MailNeedsRules.replySentFields(now - 1_000L)
        assertNull(WatchDraftRules.view(listOf(item("a", extra = sent)), now))
        // Undo on Not needed (written as 0) brings it back.
        assertNotNull(WatchDraftRules.view(listOf(item("a", extra = MailAutoDraftRules.dismissFields(0L))), now))
    }

    @Test
    fun aLongDraftIsCutAndNeverSentFromTheWrist() {
        val long = List(120) { "word" }.joinToString(" ") + "."
        val v = assertNotNull(WatchDraftRules.view(listOf(item("a", draft = long)), now))
        assertFalse(v.canSend)
        assertEquals(WatchDraftRules.TOO_LONG, v.note)
        assertTrue(v.draft.endsWith("…") && v.draft.length <= WatchDraftRules.MAX_CHARS)
        assertNull(WatchDraftRules.sendable(listOf(item("a", draft = long)), "a", now))
    }

    @Test
    fun onlyAShowingDraftCanBeSentAndExactlyAsWritten() {
        val items = listOf(item("a"), item("b", extra = MailAutoDraftRules.dismissFields(now)))
        assertEquals("Yes, I can take them. See you at 10.", WatchDraftRules.sendable(items, "a", now))
        assertNull(WatchDraftRules.sendable(items, "b", now))
        assertNull(WatchDraftRules.sendable(items, "missing", now))
        assertEquals("Sending to Sam · Undo stops it", WatchDraftRules.sendingLine("Sam Taylor"))
        assertEquals("Sending to them · Undo stops it", WatchDraftRules.sendingLine(" "))
        // The watch's Undo ends inside the server's hold, so it always stops the send.
        assertTrue(WatchDraftRules.UNDO_MS < MailNeedsRules.REPLY_HOLD_MS)
    }

    @Test
    fun theHomeViewCarriesIt() {
        val d = WatchDraftRules.view(listOf(item("a")), now)
        val cal = LocalCalendar.UTC
        val start = cal.epochDayOf(now) * CivilDate.DAY_MS
        val window = DayWindow(start, start + 24 * hour, 0)
        val nowCard = CoverNowRules.now(TodayProjection.project(emptyList(), now, window, emptyList(), cal), now, cal)
        assertEquals(d, WatchHomeRules.view(nowCard, FastingView.EMPTY, now, draft = d).draft)
        assertNull(WatchHomeRules.view(nowCard, FastingView.EMPTY, now).draft)
    }
}
