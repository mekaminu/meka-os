package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Email triage completeness: Draft replies and Send all — only short replies he has read, two or more, each from its own account. */
class MailSendAllTest {
    private fun row(id: String, from: String) = MailNeedsRow(id, from, "Personal · 09:12", "Hi", null, false, from)

    @Test
    fun anEasyReplyIsShortAndAtMostThreeParagraphs() {
        assertTrue(MailSendAllRules.easy("Hi Sam,\n\nYes, Saturday works.\n\nM"))
        assertTrue(MailSendAllRules.easy("Thanks, sorted."))
        assertFalse(MailSendAllRules.easy("Hi,\n\nOne.\n\nTwo.\n\nM"))
        assertFalse(MailSendAllRules.easy("x".repeat(MailSendAllRules.MAX_EASY + 1)))
        assertFalse(MailSendAllRules.easy("   "))
        // Lines of one paragraph count once.
        assertTrue(MailSendAllRules.easy("Thanks,\nMeka"))
    }

    @Test
    fun sendAllNeedsTwoEasyRepliesInTheCardsOrderAndNamesWhoTheyGoTo() {
        val rows = listOf(row("a", "Sam Taylor"), row("b", "Alex Reed"), row("c", "Pat Lee"), row("d", "Sam Taylor"))
        val all = MailSendAllRules.sendAll(rows, mapOf("c" to " Yes please. ", "a" to "Sounds good.", "b" to "x".repeat(900), "d" to "Done, thanks."))!!
        assertEquals(listOf("a", "c", "d"), all.ids)
        assertEquals(listOf("Sounds good.", "Yes please.", "Done, thanks."), all.texts)
        assertEquals("Send all 3", all.label)
        assertEquals("Send the 3 short replies to Sam Taylor and Pat Lee, each from the account its email came to", all.spoken)
        // One easy reply has its own Send; an email without an open editor isn't counted.
        assertNull(MailSendAllRules.sendAll(rows, mapOf("a" to "Sounds good.", "b" to "")))
        assertNull(MailSendAllRules.sendAll(rows, emptyMap()))
    }

    @Test
    fun draftRepliesIsOfferedForTwoOrMoreAndSaysWhatItDid() {
        assertNull(MailSendAllRules.draftLabel(1))
        assertEquals("Draft replies to 3", MailSendAllRules.draftLabel(3))
        assertEquals("Drafted 3 replies · read each one and change anything · nothing goes until you tap Send all or Send", MailSendAllRules.draftedLine(3, 3, null))
        assertEquals("Drafted 1 reply of 3 · write the others yourself · nothing goes until you tap", MailSendAllRules.draftedLine(1, 3, null))
        assertEquals("Navilife's AI is off", MailSendAllRules.draftedLine(0, 3, "Navilife's AI is off"))
        assertEquals(MailSendAllRules.NONE_DRAFTED, MailSendAllRules.draftedLine(0, 2, null))
        assertEquals(MailSendAllRules.ALL_HIS, MailSendAllRules.draftedLine(0, 0, null))
        assertEquals(
            "3 replies sending at Navilife's next check, each from the account its email came to · Undo stops them all",
            MailSendAllRules.sendingLine(3),
        )
    }
}
