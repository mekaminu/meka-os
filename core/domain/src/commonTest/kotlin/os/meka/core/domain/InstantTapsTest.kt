package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Taps feel instant (Meka 2026-10-25): a decided card hides at once and never comes back while the write catches up. */
class InstantTapsTest {
    private val now = 1_792_000_000_000L

    private fun msg(id: String, group: String = "Five-a-side", at: Long = now) =
        TriageCard(id, TriageLane.FYI, People.key("Alex"), "Alex in $group · 14:00", "Lineup talk", null, CaptureApp.WHATSAPP, false, at, "spoken", group)

    @Test
    fun doneHidesTheWholeChatAtOnceAndANewMessageStillShows() {
        val cards = (1..130).map { msg("m$it", at = now - it) } + msg("solo", group = "Other")
        val chat = TriageChatRules.chats(cards).first { it.title == "Five-a-side" }
        val hidden = InstantTapRules.hide(emptySet(), chat.ids)
        assertTrue(InstantTapRules.chatHidden(chat, hidden))
        val shown = InstantTapRules.triage(cards, hidden)
        assertEquals(listOf("solo"), shown.map { it.id })
        // A message arriving in that chat before the write lands still shows (it wasn't decided).
        val later = cards + msg("m131", at = now + 1)
        assertEquals(setOf("solo", "m131"), InstantTapRules.triage(later, hidden).map { it.id }.toSet())
    }

    @Test
    fun theSetForgetsIdsOnceTheViewDropsThemAndRestoresOnFailure() {
        val hidden = InstantTapRules.hide(emptySet(), listOf("a", "b"))
        // The view still holds both (the write hasn't landed): both stay hidden.
        assertEquals(setOf("a", "b"), InstantTapRules.settle(hidden, listOf("a", "b", "c")))
        // The write landed for a: forgotten.
        assertEquals(setOf("b"), InstantTapRules.settle(hidden, listOf("b", "c")))
        assertEquals(emptySet(), InstantTapRules.settle(hidden, emptyList()))
        // The work failed: the cards come back, with a line saying why.
        assertEquals(setOf("b"), InstantTapRules.restore(hidden, listOf("a")))
        assertEquals("Done didn't go through · it's back: offline", InstantTapRules.failedLine("Done", "offline"))
        assertEquals("Approve didn't go through · it's back", InstantTapRules.failedLine("Approve", " "))
    }

    @Test
    fun anApprovedCleanUpLeavesTheStackAtOnce() {
        val card = DecisionCard("mb1", DecisionKind.values().first(), "Clean up Personal", "3,256 older emails", null, "Approve", "Later", "Review",
            DecisionEffect.APPROVE_BACKLOG, DecisionEffect.SET_ASIDE, DecisionEffect.OPEN_REVIEW, urgent = false)
        val other = card.copy(id = "t1", title = "Pay the window cleaner")
        val hidden = InstantTapRules.hide(emptySet(), listOf("mb1"))
        assertEquals(listOf("t1"), InstantTapRules.stack(listOf(card, other), hidden).map { it.id })
    }

    /**
     * 4b's check: after Approve the server keeps re-writing the plan's fields each check ([MailBacklogRules.fields], which
     * re-sends READY_AT). Its scan keeps readyMs fixed once set, so the approval (later than readyAt) stays applying and
     * Approve is never offered again.
     */
    @Test
    fun theServersNextPlanWriteNeverHidesTheApproval() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 3000, MailBacklogKind.JUNK to 256)
        val ready = now - 3_600_000
        val first = MailBacklogRules.fields("Personal", now - 7_200_000, 3256, counts, null, ready)
        val approved = first + MailBacklogRules.approveFields(now)
        // The next check writes the same fields again (same readyMs): merged over the approval.
        val next = approved + MailBacklogRules.fields("Personal", now - 7_200_000, 3256, counts, null, ready)
        val b = assertNotNull(MailBacklogRules.read("mb1", next))
        assertTrue(b.approved)
        assertFalse(MailBacklogRules.canApprove(b))
        assertNull(MailBacklogRules.row(b).approveId)
        assertEquals(MailBacklogRules.APPROVED_LINE, MailBacklogRules.row(b).line)
    }
}
