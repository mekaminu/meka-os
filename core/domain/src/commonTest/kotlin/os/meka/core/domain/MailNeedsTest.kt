package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6a: the emails that need Meka, as cards with Done and Snooze. */
class MailNeedsTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun item(id: String, agoMs: Long, account: String = "Personal", thread: Int = 1, extra: Map<String, FieldValue> = emptyMap()) =
        MailNeedsRules.read(id, MailNeedsRules.serverFields(account, "Sam Taylor", "sam@example.com", "Subject $id", "Hello there", now - agoMs, thread) + extra)!!

    @Test
    fun previewsAreCleanedAndCutAtAWord() {
        assertEquals("Tom & Jerry's \"plan\" – £20", MailNeedsRules.cleanPreview("Tom &amp; Jerry&#39;s &quot;plan&quot; &ndash; &pound;20"))
        assertEquals("Hi Alex, see you at 6", MailNeedsRules.cleanPreview("  Hi Alex,\n\n see​ you &zwnj;at 6 ͏ "))
        assertNull(MailNeedsRules.cleanPreview(" ‌ &nbsp; "))
        assertNull(MailNeedsRules.cleanPreview(null))
        assertEquals("a &unknown; b", MailNeedsRules.cleanPreview("a &unknown; b"))
        val long = MailNeedsRules.cleanPreview(List(60) { "word" }.joinToString(" "))!!
        assertTrue(long.endsWith("word…"))
        assertTrue(long.length <= MailNeedsRules.MAX_PREVIEW + 1)
        assertEquals("x".repeat(MailNeedsRules.MAX_PREVIEW - 1) + "…", MailNeedsRules.cleanPreview("x".repeat(400)))
    }

    @Test
    fun theServersFieldsReadBackWithoutTheAddress() {
        val f = MailNeedsRules.serverFields("Business", null, "alex@shop.example", "  ", "", now, 0)
        val i = MailNeedsRules.read("mi1", f)!!
        assertEquals("shop.example", i.from)
        assertEquals("(no subject)", i.subject)
        assertNull(i.preview)
        assertEquals(1, i.threadCount)
        assertNull(i.goneAtMs)
        assertFalse(f.values.any { it == FieldValue.Text("alex@shop.example") })
        assertEquals(MailNeedsRules.itemId("acc", "m1"), MailNeedsRules.itemId("acc", "m1"))
        assertTrue(MailNeedsRules.itemId("acc", "m1") != MailNeedsRules.itemId("acc2", "m1"))
    }

    @Test
    fun theCardListsNewestFirstWithAccountTimeAndThread() {
        val items = listOf(
            item("a", hour),
            item("b", day + 2 * hour, "Business", thread = 3),
            item("c", 5 * day),
            item("old", 15 * day), // outside the window
            item("gone", hour, extra = MailNeedsRules.goneFields(now)),
        )
        val card = assertNotNull(MailNeedsRules.card(items, now, cal))
        assertEquals("3 emails need you", card.headline)
        assertEquals(listOf("a", "b", "c"), card.rows.map { it.id })
        assertEquals("Personal · 07:30", card.rows[0].line)
        assertEquals("Business · yesterday 06:30 · 3 in this thread", card.rows[1].line)
        assertTrue(card.rows[1].business)
        assertFalse(card.rows[0].business)
        assertEquals("Personal · ${CivilDate.shortLabel(cal.epochDayOf(now - 5 * day))}", card.rows[2].line)
        assertEquals("Hello there", card.rows[0].preview)
        assertNull(card.moreLine)
        assertNull(MailNeedsRules.card(listOf(item("old", 15 * day)), now, cal))
        val many = (1..7).map { item("m$it", it * hour) }
        assertEquals("+ 2 more · newest shown first", MailNeedsRules.card(many, now, cal)!!.moreLine)
        assertEquals("1 email needs you", MailNeedsRules.headline(1))
    }

    @Test
    fun doneUndoAndWhatTheServerDoes() {
        val asked = item("a", hour, extra = MailNeedsRules.doneFields(now))
        assertTrue(MailNeedsRules.done(asked))
        assertTrue(MailNeedsRules.archiveDue(asked))
        assertFalse(MailNeedsRules.putBackDue(asked))
        assertNull(MailNeedsRules.card(listOf(asked), now, cal))
        // Undo before the server acted: nothing for the server to do, the card is back.
        val undoneEarly = item("a", hour, extra = MailNeedsRules.doneFields(now) + MailNeedsRules.undoDoneFields(now + 1))
        assertFalse(MailNeedsRules.done(undoneEarly))
        assertFalse(MailNeedsRules.archiveDue(undoneEarly))
        assertFalse(MailNeedsRules.putBackDue(undoneEarly))
        assertNotNull(MailNeedsRules.card(listOf(undoneEarly), now, cal))
        // The server archived it: not due again.
        val archived = item("a", hour, extra = MailNeedsRules.doneFields(now) + MailNeedsRules.archivedFields(now + 10))
        assertFalse(MailNeedsRules.archiveDue(archived))
        // Undo after: put back, once.
        val undo = item("a", hour, extra = MailNeedsRules.doneFields(now) + MailNeedsRules.archivedFields(now + 10) + MailNeedsRules.undoDoneFields(now + 20))
        assertTrue(MailNeedsRules.putBackDue(undo))
        val back = item("a", hour, extra = MailNeedsRules.doneFields(now) + MailNeedsRules.archivedFields(now + 10) +
            MailNeedsRules.undoDoneFields(now + 20) + MailNeedsRules.putBackFields(now + 30))
        assertFalse(MailNeedsRules.putBackDue(back))
        // Done again later: archived again.
        val again = item("a", hour, extra = MailNeedsRules.doneFields(now) + MailNeedsRules.archivedFields(now + 10) +
            MailNeedsRules.undoDoneFields(now + 20) + MailNeedsRules.putBackFields(now + 30) + MailNeedsRules.doneFields(now + 40))
        assertTrue(MailNeedsRules.archiveDue(again))
    }

    @Test
    fun snoozeLastsTillTomorrowEight() {
        val until = MailNeedsRules.snoozeUntil(now, cal)
        assertEquals(cal.toEpochMs(cal.epochDayOf(now) + 1, 8 * 60), until)
        val snoozed = item("a", hour, extra = MailNeedsRules.snoozeFields(until))
        val card = MailNeedsRules.card(listOf(snoozed, item("b", hour)), now, cal)!!
        assertEquals(listOf("b"), card.rows.map { it.id })
        assertEquals("1 snoozed", card.snoozedLine)
        assertNull(MailNeedsRules.card(listOf(snoozed), now, cal))
        assertNotNull(MailNeedsRules.card(listOf(snoozed), until, cal))
        // Cleared (Undo): back.
        assertNotNull(MailNeedsRules.card(listOf(item("a", hour, extra = MailNeedsRules.snoozeFields(until) + MailNeedsRules.snoozeFields(0))), now, cal))
    }

    // ---- Slice 6b-1: Task and Waiting for ----

    private fun subject(s: String) = MailNeedsRules.read("mi9", MailNeedsRules.serverFields("Personal", "Sam Taylor", null, s, null, now, 1))!!

    @Test
    fun aTasksTitleIsTheSubjectWithoutItsReplyPrefixes() {
        assertEquals("Football on Saturday", MailNeedsRules.taskTitle(subject("Re: RE: Fwd: Football on Saturday")))
        assertEquals("Kit list", MailNeedsRules.taskTitle(subject("FW:Kit list")))
        assertEquals("Email from Sam Taylor", MailNeedsRules.taskTitle(subject("Re:")))
        assertEquals("Email from Sam Taylor", MailNeedsRules.taskTitle(subject("")))
        // Words that only look like a prefix stay.
        assertEquals("Reminder: pay the club", MailNeedsRules.taskTitle(subject("Reminder: pay the club")))
        assertEquals("Q3: numbers", MailNeedsRules.taskTitle(subject("Q3: numbers")))
        assertEquals("Email from Sam Taylor · Personal · Sat 17 Oct", MailNeedsRules.taskNotes(subject("x"), cal))
        assertTrue(MailNeedsRules.taskId("mi1") != MailNeedsRules.waitingId("mi1"))
        assertEquals(MailNeedsRules.taskId("mi1"), MailNeedsRules.taskId("mi1"))
    }

    @Test
    fun taskAndWaitingForAreADoneThatRemembersWhatTheyMade() {
        val made = item("m1", hour, extra = MailNeedsRules.taskFields("t1", now))
        assertTrue(MailNeedsRules.done(made))
        assertTrue(MailNeedsRules.archiveDue(made))
        assertEquals("t1", made.taskId)
        assertNull(made.waitingId)
        assertNull(MailNeedsRules.card(listOf(made), now, cal))
        val waiting = item("m2", hour, extra = MailNeedsRules.waitingFields("w1", now))
        assertEquals("w1", waiting.waitingId)
        assertTrue(MailNeedsRules.done(waiting))
        // Undo: back on the card, nothing recorded as made.
        val undone = item("m1", hour, extra = MailNeedsRules.taskFields("t1", now) + MailNeedsRules.undoMadeFields(now + 1))
        assertFalse(MailNeedsRules.done(undone))
        assertNull(undone.taskId)
        assertNull(undone.waitingId)
        assertEquals(1, MailNeedsRules.card(listOf(undone), now + 1, cal)?.rows?.size)
    }

    // ---- Slice 6b-2a: Reply, sent only on his tap ----

    @Test
    fun aReplyNeedsWordsAndAFairLength() {
        assertEquals(MailNeedsRules.REPLY_EMPTY, MailNeedsRules.replyProblem("   \n "))
        assertEquals(MailNeedsRules.REPLY_TOO_LONG, MailNeedsRules.replyProblem("x".repeat(MailNeedsRules.MAX_REPLY + 1)))
        assertNull(MailNeedsRules.replyProblem("Yes, I can take them."))
        val sent = item("a", hour, extra = MailNeedsRules.replyFields("  Yes, I can take them.  ", now))
        assertEquals("Yes, I can take them.", sent.replyText)
        assertEquals("Reply sending from Personal at Navilife's next check · Undo stops it", MailNeedsRules.replySendingLine("Personal"))
        assertEquals("To Sam Taylor · sent from Personal only when you tap Send", MailNeedsRules.card(listOf(item("b", hour)), now, cal)!!.rows.single().replyLine)
    }

    @Test
    fun theServerSendsOnlyAfterTheHoldAndArchivesOnlyOnceItWent() {
        val asked = item("a", hour, extra = MailNeedsRules.replyFields("Yes", now))
        // The card goes at once; nothing is sent or archived inside Undo's window.
        assertNull(MailNeedsRules.card(listOf(asked), now, cal))
        assertTrue(MailNeedsRules.replyWaiting(asked))
        assertFalse(MailNeedsRules.replyDue(asked, now + MailNeedsRules.REPLY_HOLD_MS - 1))
        assertFalse(MailNeedsRules.archiveDue(asked))
        assertTrue(MailNeedsRules.replyDue(asked, now + MailNeedsRules.REPLY_HOLD_MS))
        // Undo inside the window: never sent, card back.
        val undone = item("a", hour, extra = MailNeedsRules.replyFields("Yes", now) + MailNeedsRules.undoDoneFields(now + 5_000))
        assertFalse(MailNeedsRules.replyDue(undone, now + day))
        assertFalse(MailNeedsRules.archiveDue(undone))
        assertEquals(1, MailNeedsRules.card(listOf(undone), now + 6_000, cal)?.rows?.size)
        // Sent: not again, and now archived.
        val sent = item("a", hour, extra = MailNeedsRules.replyFields("Yes", now) + MailNeedsRules.replySentFields(now + 30_000))
        assertTrue(MailNeedsRules.replySent(sent))
        assertFalse(MailNeedsRules.replyDue(sent, now + day))
        assertTrue(MailNeedsRules.archiveDue(sent))
        // Undo after it went: the card comes back saying it was replied to (the reply can't be taken back).
        val back = item("a", hour, extra = MailNeedsRules.replyFields("Yes", now) + MailNeedsRules.replySentFields(now + 30_000) +
            MailNeedsRules.archivedFields(now + 30_001) + MailNeedsRules.undoDoneFields(now + 60_000))
        assertTrue(MailNeedsRules.putBackDue(back))
        assertEquals("Personal · 07:30 · replied 08:30", MailNeedsRules.card(listOf(back), now + 61_000, cal)!!.rows.single().line)
    }

    @Test
    fun aFailedReplyBringsTheCardBackWithHisWords() {
        val failed = item("a", hour, extra = MailNeedsRules.replyFields("Yes, I can take them.", now) +
            MailNeedsRules.replyFailedFields(now + 30_000, MailNeedsRules.whyRefused("Gmail")))
        assertTrue(MailNeedsRules.replyFailed(failed))
        assertFalse(MailNeedsRules.done(failed))
        assertFalse(MailNeedsRules.archiveDue(failed))
        assertFalse(MailNeedsRules.replyDue(failed, now + day))
        val row = MailNeedsRules.card(listOf(failed), now + 31_000, cal)!!.rows.single()
        assertEquals("Reply didn't send · Gmail didn't take it · your words are kept", row.notice)
        assertEquals("Yes, I can take them.", row.replyDraft)
        assertTrue(row.spoken.endsWith("your words are kept"))
        // Send again: waiting again, the notice gone.
        val again = item("a", hour, extra = MailNeedsRules.replyFields("Yes, I can take them.", now) +
            MailNeedsRules.replyFailedFields(now + 30_000, "x") + MailNeedsRules.replyFields("Yes, I can take them!", now + 40_000))
        assertTrue(MailNeedsRules.replyDue(again, now + 60_000))
        assertNull(MailNeedsRules.card(listOf(again), now + 41_000, cal))
        // Done instead after a failure: archived, nothing sent.
        val doneInstead = item("a", hour, extra = MailNeedsRules.replyFields("Yes", now) +
            MailNeedsRules.replyFailedFields(now + 30_000, "x") + MailNeedsRules.doneFields(now + 40_000))
        assertTrue(MailNeedsRules.archiveDue(doneInstead))
        assertFalse(MailNeedsRules.replyDue(doneInstead, now + day))
    }
}
