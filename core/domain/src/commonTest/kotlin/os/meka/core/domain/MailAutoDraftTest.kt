package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drafted replies come to him: which emails get a draft, the daily cap, and nothing sent without his tap. */
class MailAutoDraftTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun item(
        id: String, agoMs: Long = hour, subject: String = "Saturday", preview: String = "Hi Alex, can you take the boys on Saturday? Sam",
        reason: MailSortReason? = MailSortReason.PERSON, from: String = "sam@example.com", name: String = "Sam Taylor", extra: Map<String, FieldValue> = emptyMap(),
    ): MailItem {
        val needs = MailAutoDraftRules.needsReply(reason, from, subject, preview)
        return MailNeedsRules.read(
            id,
            MailNeedsRules.serverFields("Personal", name, from, subject, preview, now - agoMs, 1) +
                MailAutoDraftRules.serverFields(needs, MailAutoDraftRules.wants(subject, preview)) + extra,
        )!!
    }

    @Test
    fun aPersonAskingSomethingNeedsAReply() {
        assertTrue(MailAutoDraftRules.needsReply(MailSortReason.PERSON, "sam@example.com", "Saturday", "Can you take the boys?"))
        assertTrue(MailAutoDraftRules.needsReply(MailSortReason.KNOWN_PERSON, "sam@example.com", "Quote", "Please let me know by Friday"))
        assertTrue(MailAutoDraftRules.needsReply(MailSortReason.AI_NEEDS_YOU, "sam@example.com", "Dinner next week?", null))
        // FYI from a person: no question, no request.
        assertFalse(MailAutoDraftRules.needsReply(MailSortReason.PERSON, "sam@example.com", "Photos", "Here are the photos from the weekend."))
        // Newsletters, automated senders and public bodies' systems never.
        assertFalse(MailAutoDraftRules.needsReply(MailSortReason.MAILING_LIST, "news@shop.example", "Can you believe these prices?", null))
        assertFalse(MailAutoDraftRules.needsReply(MailSortReason.PERSON, "no-reply@shop.example", "Can you rate us?", null))
        assertFalse(MailAutoDraftRules.needsReply(MailSortReason.PUBLIC_BODY, "office@school.example", "Can you sign the form?", null))
        assertFalse(MailAutoDraftRules.needsReply(null, "sam@example.com", "Can you?", null))
        // Whole words only: "pleased" isn't "please".
        assertFalse(MailAutoDraftRules.asks("Update", "I was pleased to see the results."))
        assertTrue(MailAutoDraftRules.asks("Re: Re: Plans", "Let me know what suits."))
    }

    @Test
    fun whatTheyWantComesFromTheirOwnWords() {
        assertEquals("Can you take the boys on Saturday?", MailAutoDraftRules.wants("Saturday", "Hi Alex, can you take the boys on Saturday? Sam"))
        assertEquals("Could we move it to 3pm?", MailAutoDraftRules.wants("Meeting", "Thanks for yesterday. Could we move it to 3pm? Best, Sam"))
        assertEquals("Dinner next week?", MailAutoDraftRules.wants("Re: Dinner next week?", "Hello! All good here."))
        val long = MailAutoDraftRules.wants("x", "Could you " + List(40) { "please" }.joinToString(" ") + "?")!!
        assertTrue(long.endsWith("…") && long.length <= MailAutoDraftRules.MAX_WANTS)
        assertNull(MailAutoDraftRules.wants(null, null))
    }

    @Test
    fun dueOncePerEmailInsideTheCapAndNeverOldMail() {
        val a = item("a")
        assertTrue(a.replyNeeded)
        assertEquals("Can you take the boys on Saturday?", a.wants)
        assertTrue(MailAutoDraftRules.due(a, now))
        // Too old, an FYI, already drafted, already tried, put away, replied, done: not due.
        assertFalse(MailAutoDraftRules.due(item("old", agoMs = 4 * day), now))
        assertFalse(MailAutoDraftRules.due(item("fyi", preview = "Here are the photos."), now))
        val drafted = item("d", extra = MailAutoDraftRules.draftedFields("Yes, happy to.", now - hour))
        assertFalse(MailAutoDraftRules.due(drafted, now))
        assertFalse(MailAutoDraftRules.due(item("t", extra = MailAutoDraftRules.triedFields(now - hour)), now))
        assertFalse(MailAutoDraftRules.due(item("n", extra = MailAutoDraftRules.dismissFields(now)), now))
        assertFalse(MailAutoDraftRules.due(item("r", extra = MailNeedsRules.replyFields("Sure", now)), now))
        assertFalse(MailAutoDraftRules.due(item("x", extra = MailNeedsRules.doneFields(now)), now))

        // The household's cap: ten a day (drafted or tried), three a check, newest first.
        val fresh = (1..6).map { item("f$it", agoMs = it * hour) }
        assertEquals(listOf("f1", "f2", "f3"), MailAutoDraftRules.toDraft(fresh, fresh, now, cal).map { it.id })
        val usedToday = (1..9).map { item("u$it", extra = MailAutoDraftRules.draftedFields("Ok", now - it * 60_000L)) }
        assertEquals(listOf("f1"), MailAutoDraftRules.toDraft(fresh, fresh + usedToday, now, cal).map { it.id })
        val full = usedToday + item("u10", extra = MailAutoDraftRules.triedFields(now - 60_000L))
        assertTrue(MailAutoDraftRules.toDraft(fresh, fresh + full, now, cal).isEmpty())
        // Yesterday's don't count.
        val yesterday = (1..10).map { item("y$it", extra = MailAutoDraftRules.draftedFields("Ok", now - day)) }
        assertEquals(3, MailAutoDraftRules.toDraft(fresh, fresh + yesterday, now, cal).size)
    }

    @Test
    fun theCardShowsTheDraftAndTheCountsAndNothingIsSentByItself() {
        val drafted = item("a", extra = MailAutoDraftRules.draftedFields("Yes, I can take them.", now))
        val plain = item("b", agoMs = 2 * hour, subject = "Quote", preview = "Could you send the quote? Thanks", name = "Alex Reed", from = "alex@example.com")
        val fyi = item("c", agoMs = 3 * hour, subject = "Photos", preview = "Here are the photos.", name = "Jo Park", from = "jo@example.com")
        val card = MailNeedsRules.card(listOf(drafted, plain, fyi), now, cal)!!
        assertEquals("2 emails need a reply · 1 drafted", card.replyLine)
        val rows = card.rows.associateBy { it.id }
        assertEquals("Yes, I can take them.", rows.getValue("a").autoDraft)
        assertEquals("Can you take the boys on Saturday?", rows.getValue("a").wants)
        assertTrue(rows.getValue("a").spoken.contains("Navilife drafted a reply"))
        assertNull(rows.getValue("b").autoDraft)
        assertEquals("Could you send the quote?", rows.getValue("b").wants)
        assertNull(rows.getValue("c").wants)
        // A draft is never a reply: nothing waits to be sent until his Send.
        assertFalse(MailNeedsRules.replyWaiting(drafted))
        assertFalse(MailNeedsRules.replyDue(drafted, now + day))

        // Not needed: the draft and the "needs a reply" go; the email stays on the card.
        val away = MailNeedsRules.read("a", fieldsOf(drafted) + MailAutoDraftRules.dismissFields(now))!!
        val after = MailNeedsRules.card(listOf(away, plain, fyi), now, cal)!!
        assertEquals(3, after.rows.size)
        assertNull(after.rows.first { it.id == "a" }.autoDraft)
        assertNull(after.rows.first { it.id == "a" }.wants)
        assertEquals("1 email needs a reply", after.replyLine)
        assertEquals(MailAutoDraftRules.NONE_WAITING, MailAutoDraftRules.waitingLine(listOf(away, fyi), now))

        // Once he sends (or the send failed and the card came back), the draft doesn't show.
        val sent = MailNeedsRules.read("a", fieldsOf(drafted) + MailNeedsRules.replyFields("Yes, I can take them.", now))!!
        assertFalse(MailAutoDraftRules.draftShowing(sent))
    }

    @Test
    fun todayAndMailSayHowManyWaitOnHim() {
        val drafted = item("a", extra = MailAutoDraftRules.draftedFields("Yes, I can take them.", now))
        val plain = item("b", agoMs = 2 * hour, subject = "Quote", preview = "Could you send the quote? Thanks", name = "Alex Reed", from = "alex@example.com")
        val fyi = item("c", agoMs = 3 * hour, subject = "Photos", preview = "Here are the photos.", name = "Jo Park", from = "jo@example.com")

        val line = MailAutoDraftRules.todayLine(listOf(drafted, plain, fyi), now)!!
        assertEquals("2 emails need a reply · 1 drafted", line.text)
        assertTrue(line.lit)
        assertEquals("2 emails need your reply. Navilife has drafted 1; nothing goes until you tap Send.", line.spoken)
        assertEquals("1 email needs your reply. Navilife has drafted it; nothing goes until you tap Send.", MailAutoDraftRules.todayLine(listOf(drafted), now)!!.spoken)
        assertEquals("1 email needs a reply", MailAutoDraftRules.todayLine(listOf(plain, fyi), now)!!.text)
        // None waiting: Today stays quiet; Mail says so while an account is connected.
        assertNull(MailAutoDraftRules.todayLine(listOf(fyi), now))
        assertNull(MailAutoDraftRules.todayLine(emptyList(), now))
        assertEquals(MailAutoDraftRules.NONE_WAITING, MailAutoDraftRules.paneLine(listOf(fyi), now, connected = true))
        assertEquals("2 emails need a reply · 1 drafted", MailAutoDraftRules.paneLine(listOf(drafted, plain, fyi), now, connected = true))
        assertNull(MailAutoDraftRules.paneLine(listOf(drafted), now, connected = false))
        // Not needed takes it off the count.
        val away = MailNeedsRules.read("a", fieldsOf(drafted) + MailAutoDraftRules.dismissFields(now))!!
        assertEquals("1 email needs a reply", MailAutoDraftRules.todayLine(listOf(away, plain), now)!!.text)
    }

    private fun fieldsOf(i: MailItem): Map<String, FieldValue> =
        MailNeedsRules.serverFields(i.account, i.from, "sam@example.com", i.subject, i.preview, i.receivedAtMs, i.threadCount) +
            MailAutoDraftRules.serverFields(i.replyNeeded, i.wants) +
            (i.autoDraft?.let { MailAutoDraftRules.draftedFields(it, i.autoDraftAtMs!!) } ?: emptyMap())
}
