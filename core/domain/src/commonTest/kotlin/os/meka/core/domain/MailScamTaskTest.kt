package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A possible scam never becomes a task (Meka 2026-11-19). */
class MailScamTaskTest {
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L
    private val scam = "⚠ Possible scam · sent from mail-x.example, not invest.example"

    private fun item(id: String, warning: String = "", subject: String = "Our bank account details are changing") =
        assertNotNull(MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", "Invest Co", "x@mail-x.example", subject, null, now, 1, warning = warning)))

    @Test
    fun aPossibleScamOrJunkIsBlockedAndACompanysOwnWarningOrNotAScamIsNot() {
        assertTrue(MailScamTaskRules.blocked(item("a", scam)))
        assertTrue(MailScamTaskRules.blocked(item("b").copy(junkAsked = true)))
        assertFalse(MailScamTaskRules.blocked(item("c")))
        // A real company's "log in yourself" line isn't a scam.
        assertFalse(MailScamTaskRules.blocked(item("d", "Log in yourself, not from a link")))
        assertFalse(MailScamTaskRules.blocked(item("e", scam).copy(notScamAtMs = now)))
        // Not a scam then its Undo: blocked again.
        assertTrue(MailScamTaskRules.blocked(item("f", scam).copy(notScamAtMs = now, notScamUndoAtMs = now + 1)))
    }

    @Test
    fun straysAreTheOpenTasksAndWaitingForItemsOfBlockedEmailsOnly() {
        val items = listOf(item("a", scam), item("b"), item("c", scam), item("d", scam).copy(taskId = "tmd"))
        val open = setOf(MailNeedsRules.taskId("a"), MailNeedsRules.taskId("b"), "tmd")
        val waiting = setOf(MailNeedsRules.waitingId("c"))
        val s = MailScamTaskRules.strays(items, { it in open }, { it in waiting })
        assertEquals(listOf("a", "c", "d"), s.map { it.itemId })
        assertEquals(MailNeedsRules.taskId("a"), s[0].taskId)
        assertEquals(MailNeedsRules.waitingId("c"), s[1].waitingId)
        assertEquals(null, s[1].taskId)
        assertEquals("Removed “Our bank account details are changing” from your list · it came from a possible scam email", MailScamTaskRules.summary(s[0]))
        assertEquals("Removed “Our bank account details are changing” from Waiting for · it came from a possible scam email", MailScamTaskRules.summary(s[1]))
        // One Activity id per email, the same on every device.
        assertEquals(MailScamTaskRules.activityId(s[0]), MailScamTaskRules.activityId(s[0].copy(title = "x")))
        assertTrue(MailScamTaskRules.strays(items, { false }, { false }).isEmpty())
    }
}
