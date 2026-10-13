package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6b-2b: Draft it's rules — his tone from his own sent replies, the draft's screen, never over his words. */
class MailDraftTest {
    private fun item(id: String, reply: String?, askedAt: Long?, sentAt: Long?, failedAt: Long? = null) = MailItem(
        id = id, account = "Personal", from = "Sam Taylor", subject = "Hi", preview = null, receivedAtMs = 1L, threadCount = 1,
        goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = askedAt, undoAskedAtMs = null, snoozedUntilMs = null,
        replyText = reply, replyAskedAtMs = askedAt, replySentAtMs = sentAt, replyFailedAtMs = failedAt,
    )

    @Test
    fun examplesAreHisSentRepliesNewestFirstAndDifferent() {
        val items = listOf(
            item("a", "Sounds good, see you at 7.", 10, 20),
            item("b", "Thanks Alex, sorted.", 30, 40),
            item("c", "Never sent", 50, null),
            item("d", "Failed one", 60, null, failedAt = 70),
            item("e", null, null, null),
            item("f", "Thanks Alex, sorted.", 80, 90),
            item("g", "  Yes please  ", 100, 110),
            item("h", "x".repeat(900), 120, 130),
            item("i", "Old one", 1, 2),
        )
        val ex = MailDraftRules.examples(items)
        assertEquals(3, ex.size)
        assertEquals(MailDraftRules.MAX_EXAMPLE, ex[0].length)
        assertEquals(listOf("Yes please", "Thanks Alex, sorted."), ex.drop(1))
        assertTrue(MailDraftRules.examples(emptyList()).isEmpty())
    }

    @Test
    fun aDraftWithALinkAnAddressANumberOrAPlaceholderIsDroppedWhole() {
        assertEquals("Hi Sam,\n\nYes, Saturday works.\n\nM", MailDraftRules.check("\"Hi Sam,\n\nYes,   Saturday works.\n\n\nM\""))
        assertNull(MailDraftRules.check("Pay at https://example.com"))
        assertNull(MailDraftRules.check("Write to me at someone@example.com"))
        assertNull(MailDraftRules.check("Call 07700 900123"))
        assertNull(MailDraftRules.check("Hi [Name], yes."))
        assertNull(MailDraftRules.check("x".repeat(HandleThisRules.MAX_REPLY + 1)))
        assertNull(MailDraftRules.check("   "))
        assertNull(MailDraftRules.check(null))
    }

    @Test
    fun draftItNeverReplacesHisOwnWords() {
        assertTrue(MailDraftRules.canDraft("", null))
        assertTrue(MailDraftRules.canDraft("  ", "Old draft"))
        assertTrue(MailDraftRules.canDraft("Old draft ", "Old draft"))
        assertFalse(MailDraftRules.canDraft("Old draft, and my words", "Old draft"))
        assertFalse(MailDraftRules.canDraft("My words", null))
        assertEquals(MailDraftRules.DRAFT, MailDraftRules.label(""))
        assertEquals(MailDraftRules.AGAIN, MailDraftRules.label("Old draft"))
    }

    @Test
    fun theDraftCountsAsMailInActivity() {
        assertEquals(AiTodayRules.Group.MAIL, AiTodayRules.groupOf("mail.draft"))
    }
}
