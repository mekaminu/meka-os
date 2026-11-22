package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tidied mail, slice 4: Select, Select all, Trash · Put back, and "Trash all 38 promos" behind a confirm. */
class MailTidiedBulkTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private val now = cal.toEpochMs(day, 20 * 60)
    private fun at(minute: Int) = cal.toEpochMs(day, minute)

    private fun email(id: String, from: String, tidy: MailTidy = MailTidy.ARCHIVE, minute: Int = 9 * 60) =
        MailTidiedEmail(MailTidiedRules.key("acc1", id), tidy, MailSort.PROMO, at(minute), from, "${from.lowercase()}.example", "Mail $id", null)

    private fun batch(id: String, emails: List<MailTidiedEmail>, asks: List<MailTidiedAsk> = emptyList(), done: List<MailTidiedDone> = emptyList()): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(at(12 * 60), "Personal", emails.groupingBy { it.tidy }.eachCount()) +
            MailTidiedRules.fields(emails) +
            (if (asks.isEmpty()) emptyMap() else mapOf(MailTidyBatchFields.EMAIL_ASKS to FieldValue.Text(MailTidiedAskRules.encodeAsks(asks)))) +
            (if (done.isEmpty()) emptyMap() else mapOf(MailTidyBatchFields.EMAIL_DONE to FieldValue.Text(MailTidiedAskRules.encodeDone(done))))
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    private fun MailTidyBatch.apply(f: Map<String, FieldValue>?): MailTidyBatch {
        val asks = f?.get(MailTidyBatchFields.EMAIL_ASKS)?.let { MailTidiedAskRules.decodeAsks((it as FieldValue.Text).value) } ?: emailAsks
        return batch(id, emails, asks, emailDone)
    }

    @Test
    fun theBarCountsWhatEachMoveWillActOnAndSelectAllPicksEveryRowShown() {
        val promo1 = email("p1", "Deliveroo"); val promo2 = email("p2", "Amazon", minute = 10 * 60)
        val spam = email("j1", "Prize Desk", MailTidy.JUNK_TRASH, minute = 11 * 60)
        val b = batch("mt1", listOf(promo1, promo2, spam))
        val rows = ArchivedAllRules.list(listOf(b), null, now, cal).days.flatMap { it.rows }
        val none = MailTidiedBulkRules.bar(rows, emptySet())
        assertEquals(MailTidiedBulkRules.NONE_SELECTED, none.line)
        assertEquals(MailTidiedBulkRules.SELECT_ALL, none.toggle)
        assertNull(none.trash); assertNull(none.putBack)
        // Junk already in Trash: it can be put back (in Junk) but not trashed again.
        val all = MailTidiedBulkRules.toggled(rows, emptySet())
        assertEquals(3, all.size)
        val bar = MailTidiedBulkRules.bar(rows, all)
        assertEquals("3 selected", bar.line)
        assertEquals("Trash 2", bar.trash)
        assertEquals("Put back 3", bar.putBack)
        assertEquals(MailTidiedBulkRules.CLEAR, bar.toggle)
        assertEquals("3 emails selected. 2 can go to Trash, 3 can be put back", bar.spoken)
        assertTrue(MailTidiedBulkRules.toggled(rows, all).isEmpty())
        // An id that isn't shown doesn't count.
        assertEquals("1 selected", MailTidiedBulkRules.bar(rows, setOf(rows[0].id, "mt9|nope")).line)
    }

    @Test
    fun aBulkAskWritesOncePerTidyLeavesOutWhatCantBeAskedAndUndoTakesItOff() {
        val p1 = email("p1", "Deliveroo"); val p2 = email("p2", "Amazon"); val p3 = email("p3", "Boots")
        val b1 = batch("mt1", listOf(p1, p2), asks = listOf(MailTidiedAsk(p2.key, MailTidiedAction.PUT_BACK, at(13 * 60))))
        val b2 = batch("mt2", listOf(p3))
        val batches = listOf(b1, b2)
        val ids = listOf("mt1|${p1.key}", "mt1|${p2.key}", "mt2|${p3.key}", "mt3|gone", "broken")
        // p2 is on its way back already, so it isn't trashed.
        assertEquals(2, MailTidiedBulkRules.asked(batches, ids, MailTidiedAction.TRASH, now))
        val writes = MailTidiedBulkRules.ask(batches, ids, MailTidiedAction.TRASH, now)
        assertEquals(setOf("mt1", "mt2"), writes.keys)
        val a1 = b1.apply(writes["mt1"])
        assertEquals(MailTidiedAction.TRASH.pending, MailTidiedAskRules.state(a1, p1.key))
        assertEquals(MailTidiedAction.PUT_BACK.pending, MailTidiedAskRules.state(a1, p2.key))
        val a2 = b2.apply(writes["mt2"])
        // Asked again: nothing left to ask.
        assertTrue(MailTidiedBulkRules.ask(listOf(a1, a2), ids, MailTidiedAction.TRASH, now).isEmpty())
        // Undo: the Trash asks come off; p2's Put back stays.
        val undo = MailTidiedBulkRules.unask(listOf(a1, a2), ids, MailTidiedAction.TRASH)
        val u1 = a1.apply(undo["mt1"])
        assertNull(MailTidiedAskRules.state(u1, p1.key))
        assertEquals(MailTidiedAction.PUT_BACK.pending, MailTidiedAskRules.state(u1, p2.key))
        assertNull(MailTidiedAskRules.state(a2.apply(undo["mt2"]), p3.key))
        // Once the server has acted, Undo leaves it.
        val acted = batch("mt2", listOf(p3), a2.emailAsks, listOf(MailTidiedDone(p3.key, MailTidiedAction.TRASH, now + 60_000, MailTidiedAskRules.IN_TRASH)))
        assertTrue(MailTidiedBulkRules.unask(listOf(acted), listOf("mt2|${p3.key}"), MailTidiedAction.TRASH).isEmpty())
        assertEquals("Moving 38 promos to Trash at Navilife's next check", MailTidiedBulkRules.barLine(MailTidiedAction.TRASH, 38, TidiedKind.PROMOS))
        assertEquals("Putting 1 email back at Navilife's next check", MailTidiedBulkRules.barLine(MailTidiedAction.PUT_BACK, 1))
    }

    @Test
    fun trashAllShowsOnAChosenKindCountsWhatCanGoAndSaysItIsNeverDeletedForGood() {
        val promos = (1..3).map { email("p$it", if (it == 3) "Amazon" else "Deliveroo", minute = 9 * 60 + it) }
        val spam = email("j1", "Prize Desk", MailTidy.JUNK_TRASH)
        val scam = email("s1", "Bank Alerts", MailTidy.SCAM_JUNK)
        val b = batch("mt1", promos + spam + scam)
        val p = ArchivedAllRules.list(listOf(b), null, now, cal, TidiedKind.PROMOS.id)
        val t = assertNotNull(p.trashAll)
        assertEquals("Trash all 3 promos", t.label)
        assertEquals("Move 3 promos to Trash?", t.confirmTitle)
        assertEquals("Move 3 to Trash", t.confirmButton)
        assertTrue("can be put back for 30 days" in t.confirmBody && "for good" !in t.confirmBody)
        assertEquals(3, t.ids.size)
        // A sender within the kind: theirs only.
        val d = assertNotNull(ArchivedAllRules.list(listOf(b), "deliveroo", now, cal, TidiedKind.PROMOS.id).trashAll)
        assertEquals("Trash all 2 promos from Deliveroo", d.label)
        // Junk & scams: the junk is in Trash already, so only the scam counts.
        assertEquals("Trash all 1 junk or scam email", ArchivedAllRules.list(listOf(b), null, now, cal, TidiedKind.JUNK.id).trashAll?.label)
        // Not on the whole of today or the last 30 days.
        assertNull(ArchivedAllRules.list(listOf(b), null, now, cal, TidiedKind.ALL.id).trashAll)
        assertNull(ArchivedAllRules.list(listOf(b), null, now, cal).trashAll)
        // Once they're asked, it's gone.
        val asked = b.apply(MailTidiedBulkRules.ask(listOf(b), t.ids, MailTidiedAction.TRASH, now)["mt1"])
        assertNull(ArchivedAllRules.list(listOf(asked), null, now, cal, TidiedKind.PROMOS.id).trashAll)
        assertFalse(MailTidiedBulkRules.split("x|y") == null)
        assertNull(MailTidiedBulkRules.split("|y"))
    }

    /** Walk-through week 5, A9: Mail's header promised "Nothing is ever deleted for good" while Trash's confirm says 30 days. */
    @Test
    fun mailHeaderAndTrashConfirmTellTheSameStoryAboutTrash() {
        for (line in listOf(MailAccountRules.HEADER, MailTidiedBulkRules.TRASH_BODY)) {
            assertTrue("30 days" in line, "every word about Trash should say how long it keeps mail: $line")
            assertTrue("for good" !in line, "no promise that Trash keeps mail for ever: $line")
        }
    }
}
