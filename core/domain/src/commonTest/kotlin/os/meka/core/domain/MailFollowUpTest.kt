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
    fun writtenFromADayUntilAnswered() {
        // The server writes it from a day on (a one-day person); the apps wait that person's days.
        assertFalse(MailFollowUpRules.due(sentDay, sentDay))
        assertTrue(MailFollowUpRules.due(sentDay, sentDay + 1))
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

    @Test
    fun eachPersonWaitsTheirOwnDaysElseThree() {
        val fu = read("Quote for the kitchen", "Could you send the quote?")!!
        val f = MailRenewalRules.read("mf9", MailFollowUpRules.serverFields(fu, "Personal", "Quote for the kitchen", sentDay))!!
        // The default: hidden on days 1–2, shown from day 3.
        assertFalse(MailFollowUpRules.showable(f, emptyList(), sentDay + 2))
        assertTrue(MailFollowUpRules.showable(f, emptyList(), sentDay + 3))
        // His own week for Sam (the name folded, the newest choice winning); someone else keeps three.
        val wid = MailFollowUpRules.waitId("Sam Taylor")
        assertEquals(wid, MailFollowUpRules.waitId("  sam   taylor "))
        val old = MailFollowUpRules.readWait(wid, MailFollowUpRules.waitFields("Sam Taylor", 1, 1L))!!
        val week = MailFollowUpRules.readWait(wid, MailFollowUpRules.waitFields("sam taylor", 7, 2L))!!
        val waits = listOf(old, week)
        assertEquals(7, MailFollowUpRules.daysFor("Sam Taylor", waits))
        assertEquals(3, MailFollowUpRules.daysFor("Ann Lee", waits))
        assertFalse(MailFollowUpRules.showable(f, emptyList(), sentDay + 6, waits))
        assertTrue(MailFollowUpRules.showable(f, emptyList(), sentDay + 7, waits))
        assertTrue(MailFollowUpRules.showable(f, emptyList(), sentDay + 7 + MailFollowUpRules.STALE_DAYS, waits))
        assertFalse(MailFollowUpRules.showable(f, emptyList(), sentDay + 8 + MailFollowUpRules.STALE_DAYS, waits))
        // One day: shown the day after.
        val day = MailFollowUpRules.readWait(wid, MailFollowUpRules.waitFields("Sam Taylor", 1, 3L))!!
        assertTrue(MailFollowUpRules.showable(f, emptyList(), sentDay + 1, listOf(day)))
        // 0 (Undo of a first choice) or off the scale reads as none.
        assertNull(MailFollowUpRules.readWait(wid, MailFollowUpRules.waitFields("Sam Taylor", 0, 4L)))
        assertNull(MailFollowUpRules.readWait(wid, MailFollowUpRules.waitFields("Sam Taylor", 30, 4L)))
        // The row: the pill, the heading and the chips, his choice marked.
        val row = MailFollowUpRules.row(f, sentDay + 7, waits)
        assertEquals("Wait 1 week ▾", row.waitPill)
        assertEquals("How long to wait for Sam Taylor's replies before offering a chase", row.waitHeading)
        assertEquals(listOf("1 day", "2 days", "3 days", "5 days", "1 week", "2 weeks"), row.waits.map { it.label })
        assertEquals("Wait 1 week for Sam Taylor, chosen", row.waits.single { it.chosen }.spoken)
        // A renewal's row has none.
        assertNull(MailRenewalRules.card(listOf(f.copy(kind = MailRenewalRules.KIND_RENEWAL, renewDay = sentDay + 30)), emptyList(), cal.toEpochMs(sentDay, 600), cal)!!.rows.single().waitPill)
        // The bar's lines.
        assertEquals("Waiting 1 week for Sam Taylor's replies · this one comes back Mon 19 Oct", MailFollowUpRules.waitSetLine(f, 7, sentDay + 3))
        assertEquals("Waiting 2 days for Sam Taylor's replies from now on", MailFollowUpRules.waitSetLine(f, 2, sentDay + 3))
    }

    @Test
    fun theChasePointsAtHisEmailAndATheirReplyWaitsForGotIt() {
        assertEquals("Hi Sam,\n\nJust following up on my email below — have you had a chance to look at it?\n\nThanks,\nAlex", MailChaseRules.followUpDraft("Sam Taylor", "Alex"))
        assertEquals("Hi,\n\nJust following up on my email below — have you had a chance to look at it?\n\nThanks", MailChaseRules.followUpDraft("sam@example.com", null))
        val f = MailRenewalRules.read("mf1", MailFollowUpRules.serverFields(MailFollowUp("Sam Taylor", "Reply about Quote"), "Personal", "Quote", sentDay))!!
        assertFalse(MailChaseRules.repliedOpen(f))
        val replied = f.copy(answeredAtMs = 2_000L, answeredWhy = MailFollowUpRules.ANSWERED_REPLIED)
        assertTrue(MailChaseRules.repliedOpen(replied))
        // His own chase counts as "wrote again", never as their reply; Got it or Not yet since puts it aside.
        assertFalse(MailChaseRules.repliedOpen(replied.copy(answeredWhy = MailFollowUpRules.ANSWERED_WROTE_AGAIN)))
        assertFalse(MailChaseRules.repliedOpen(replied.copy(chase = ChaseMarks(seenAtMs = 3_000L))))
        // A chase read back from the find's fields is due after Undo's hold, once.
        val asked = MailRenewalRules.read("mf1", MailFollowUpRules.serverFields(MailFollowUp("Sam Taylor", "Reply about Quote"), "Personal", "Quote", sentDay) +
            MailChaseRules.sendFields("Any news?", 10_000L))!!
        assertFalse(MailChaseRules.due(asked.chase, 10_000L))
        assertTrue(MailChaseRules.due(asked.chase, 10_000L + MailNeedsRules.REPLY_HOLD_MS))
        val sent = MailRenewalRules.read("mf1", MailFollowUpRules.serverFields(MailFollowUp("Sam Taylor", "Reply about Quote"), "Personal", "Quote", sentDay) +
            MailChaseRules.sendFields("Any news?", 10_000L) + MailChaseRules.sentFields(40_000L))!!
        assertFalse(MailChaseRules.due(sent.chase, 90_000L))
    }

    @Test
    fun chaseWaitsListsEachPersonsNewestWaitByNameWithTheUsualForEveryoneElse() {
        val empty = FollowUpWaitsRules.panel(emptyList(), cal)
        assertEquals("Chase waits", empty.title)
        assertTrue(empty.rows.isEmpty())
        assertTrue(empty.line.startsWith("Navilife offers a chase after 3 days without a reply."), empty.line)
        assertTrue("Wait 3 days ▾" in empty.line)

        val setAt = cal.toEpochMs(sentDay, 600)
        val waits = listOf(
            FollowUpWait(MailFollowUpRules.waitId("Sam Taylor"), "Sam Taylor", 7, setAt),
            FollowUpWait(MailFollowUpRules.waitId("alex brown"), "Alex Brown", 1, setAt + 86_400_000L),
            FollowUpWait("fwold", "alex  BROWN", 14, setAt),
        )
        val p = FollowUpWaitsRules.panel(waits, cal)
        assertEquals("3 days for everyone else · your own wait for 2 people", p.line)
        assertEquals(listOf("Alex Brown", "Sam Taylor"), p.rows.map { it.who })
        val sam = p.rows[1]
        assertEquals("Waits 1 week before a chase is offered · set Mon 12 Oct", sam.line)
        assertEquals(listOf(7), sam.choices.filter { it.chosen }.map { it.days })
        assertEquals("Back to usual", sam.resetLabel)
        assertEquals("Put Sam Taylor back on the usual 3 days", sam.resetSpoken)
        assertEquals("Waits 1 day before a chase is offered · set Tue 13 Oct", p.rows[0].line)
        assertEquals("3 days for everyone else · your own wait for 1 person", FollowUpWaitsRules.panel(waits.take(1), cal).line)
        assertEquals("Waiting 2 weeks for Sam Taylor's replies from now on", FollowUpWaitsRules.changedLine("Sam Taylor", 14))
        assertEquals("Sam Taylor back to the usual 3 days", FollowUpWaitsRules.resetLine("Sam Taylor"))
    }

    @Test
    fun theAiChaseNeverWritesOverHisOwnWords() {
        val D = MailChaseDraftRules
        val plain = MailChaseRules.followUpDraft("Sam Taylor", "Alex")
        val ai = "Hi Sam,\n\nAny news on the quote?\n\nThanks,\nAlex"
        assertTrue(D.canDraft("", plain, null))
        assertTrue(D.canDraft(plain, plain, null))
        assertTrue(D.canDraft(" $ai \n", plain, ai))
        assertFalse(D.canDraft("Sam, nudge on the quote please", plain, ai))
        assertEquals(D.DRAFT, D.label(plain, null))
        assertEquals(D.AGAIN, D.label(ai, ai))
        val row = MailChaseRow("f", "w", "Sam Taylor", "Reply about Quote", "Personal", false, plain, "To Sam", null, "")
        assertTrue(D.autoDraft(row))
        assertFalse(D.autoDraft(row.copy(notice = "Chase didn't send · try again · your words are kept")))
    }
}
