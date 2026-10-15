package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailFollowUpTest {
    /** Mon 12 Oct 2026. */
    private val sentDay = CivilDate.toEpochDay(2026, 10, 12)
    private val cal = LocalCalendar.UTC
    private val own = setOf("alex@example.com", "alex@business.example")

    private fun facts(subject: String?, preview: String?, to: String? = "sam@example.com", name: String? = "Sam Taylor", recipients: Int = 1) =
        MailSentFacts(name, to, recipients, subject, preview, cal.toEpochMs(sentDay, 600))

    private fun read(subject: String?, preview: String?, to: String? = "sam@example.com", name: String? = "Sam Taylor", recipients: Int = 1, automated: Boolean = false) =
        MailFollowUpRules.read(facts(subject, preview, to, name, recipients), own, automated)

    @Test
    fun anEmailThatAsksSomethingIsOneToFollowUp() {
        val fu = read("Quote for the kitchen", "Hi Sam, could you send the quote over when you have a minute? Thanks, Alex")!!
        assertEquals("Sam Taylor", fu.who)
        assertEquals("Reply about Quote for the kitchen", fu.what)
        assertEquals("Reply about Tuesday", read("Re: Fwd: Tuesday", "Does Tuesday at 10 work?")!!.what)
        assertEquals("A reply", read(null, "Let me know when you're free")!!.what)
        // No name: the address.
        assertEquals("sam@example.com", read("Invoice", "Have you had a chance to look?", name = null)!!.who)
        // A long subject is cut at a word.
        val long = read("A very long subject line about the plans for the garden and the fence next spring", "Thoughts?")!!.what
        assertTrue(long.endsWith("…"), long)
        assertTrue(long.length <= "Reply about ".length + 46, long)
    }

    @Test
    fun nothingForThanksAutomatedOwnOrCrowdedEmails() {
        assertNull(read("Re: Quote", "Thanks!"))
        assertNull(read("Re: Quote", "Great, see you then.")) // asks nothing
        assertNull(read("Accepted: Team lunch", "Accepted?"))
        assertNull(read("Automatic reply: Away", "I'm away until Monday, can you email Sam?"))
        assertNull(read("Question", "Can you check?", to = "noreply@shop.example", automated = true))
        assertNull(read("Note to self", "Can you remember the milk?", to = "alex@example.com"))
        assertNull(read("Plans", "Can you all make Friday?", recipients = 4))
        assertNull(read("Plans", "Can you make Friday?", to = null))
        // "can you" as part of a word isn't an ask; a question mark is.
        assertNull(read("Pecan yogurt", "The pecan yogurt was lovely"))
        assertTrue(MailFollowUpRules.asks("Re: dates", "Which day suits you"))
    }

    @Test
    fun dueAfterThreeDaysUntilAnswered() {
        assertFalse(MailFollowUpRules.due(sentDay, sentDay + 2))
        assertTrue(MailFollowUpRules.due(sentDay, sentDay + 3))
        assertTrue(MailFollowUpRules.due(sentDay, sentDay + MailFollowUpRules.WINDOW_DAYS))
        assertFalse(MailFollowUpRules.due(sentDay, sentDay + MailFollowUpRules.WINDOW_DAYS + 1))
        val sentAt = cal.toEpochMs(sentDay, 600)
        // An answer from them after he wrote counts; one before, or his own, doesn't.
        assertTrue(MailFollowUpRules.replied(sentAt, listOf("sam@example.com" to sentAt + 1), own))
        assertFalse(MailFollowUpRules.replied(sentAt, listOf("sam@example.com" to sentAt - 1), own))
        assertFalse(MailFollowUpRules.replied(sentAt, listOf("alex@example.com" to sentAt + 1), own))
        assertNotEquals(MailFollowUpRules.findId("acc1", "s1"), MailFollowUpRules.findId("acc1", "s2"))
        assertNotEquals(MailFollowUpRules.findId("acc1", "s1"), MailPromiseRules.findId("acc1", "s1"))
    }

    @Test
    fun theCardShowsThemUntilAnsweredOrOnWaitingFor() {
        val fu = read("Quote for the kitchen", "Could you send the quote?")!!
        val id = MailFollowUpRules.findId("acc1", "s1")
        val fields = MailFollowUpRules.serverFields(fu, "Business", "Quote for the kitchen", sentDay)
        val f = MailRenewalRules.read(id, fields)!!
        assertEquals(MailFollowUpRules.KIND_FOLLOW_UP, f.kind)
        assertEquals(sentDay + 3, f.renewDay)
        val today = sentDay + 4
        val now = cal.toEpochMs(today, 600)
        val card = MailRenewalRules.card(listOf(f), emptyList(), now, cal)!!
        assertEquals("No reply yet", card.title)
        assertEquals("1 email with no reply · nothing goes on Waiting for until you tap Add", card.headline)
        val row = card.rows.single()
        assertEquals("Sam Taylor", row.title)
        assertEquals("No reply since you wrote on Mon 12 Oct · 4 days", row.line)
        assertEquals("Business mail · “Quote for the kitchen”", row.from)
        assertEquals("Add Sam Taylor to Waiting for, to chase", row.addSpoken)
        assertEquals("On Waiting for: Reply about Quote for the kitchen from Sam Taylor · chase today", MailFollowUpRules.addedLine(f))
        assertTrue(MailFollowUpRules.note(f).startsWith("You emailed them from your Business mail on Mon 12 Oct"))
        // With a promise: one card, "To add from your mail".
        val p = MailPromiseRules.read("Ann", "ann@example.com", "Re: photos", "I'll send the photos tomorrow", today)!!
        val fp = MailRenewalRules.read("fp", MailPromiseRules.serverFields(p, "Personal", "Re: photos", today))!!
        val both = MailRenewalRules.card(listOf(fp, f), emptyList(), now, cal)!!
        assertEquals("To add from your mail", both.title)
        assertEquals("2 to add · nothing goes on until you tap Add", both.headline)
        // Answered (the server's mark): gone.
        val answered = MailRenewalRules.read(id, fields + MailFollowUpRules.answeredFields(now, MailFollowUpRules.ANSWERED_REPLIED))!!
        assertNull(MailRenewalRules.card(listOf(answered), emptyList(), now, cal))
        // On Waiting for already: gone; long past: gone.
        val w = WaitingItem(MailPromiseRules.waitingId(id), "Reply about Quote for the kitchen", "Sam Taylor", null, now, null, null, DueState.NONE, "", false)
        assertFalse(MailFollowUpRules.showable(f, listOf(w), today))
        assertFalse(MailFollowUpRules.showable(f, emptyList(), f.renewDay + MailFollowUpRules.STALE_DAYS + 1))
        // Not needed: gone.
        val dismissed = f.copy(state = MailFindState.DISMISSED)
        assertFalse(MailFollowUpRules.showable(dismissed, emptyList(), today))
    }
}
