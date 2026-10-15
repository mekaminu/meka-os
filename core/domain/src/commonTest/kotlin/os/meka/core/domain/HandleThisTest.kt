package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** "Handle this" (build plan V1, slice 1): the rules, no AI. */
class HandleThisTest {
    private val cal = LocalCalendar.UTC
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private val at = cal.toEpochMs(sat, 21 * 60 + 40)

    @Test
    fun aBareLinkOrAFewLettersAreNotWorthSending() {
        assertFalse(HandleThisRules.worthAsking("https://example.com/a/very/long/path?with=letters", null))
        assertFalse(HandleThisRules.worthAsking("ok thanks", null))
        assertFalse(HandleThisRules.worthAsking("", ""))
        assertTrue(HandleThisRules.worthAsking("https://school.example/trip", "Year 4 trip form due Friday"))
        assertTrue(HandleThisRules.worthAsking("Please return the reply slip by Friday.", null))
    }

    @Test
    fun theTextGoesOutCleanAndCutOnAWord() {
        assertEquals("Line one\n\nLine two", HandleThisRules.outgoingText("Line one\r\n\r\n\r\n\r\nLine two\u0007"))
        val long = "word ".repeat(2_000)
        val out = HandleThisRules.outgoingText(long)
        assertTrue(out.length <= HandleThisRules.MAX_TEXT)
        assertTrue(out.endsWith("word"))
        assertEquals("Trip form · due Friday", HandleThisRules.outgoingSubject("  Trip form ·\n due Friday "))
        assertEquals("", HandleThisRules.outgoingSubject(null))
        assertEquals(HandleThisRules.MAX_SUBJECT, HandleThisRules.outgoingSubject("x".repeat(500)).length)
    }

    @Test
    fun theSameShareOnTheSameDayIsTheSameMessage() {
        val a = HandleThisRules.messageId("Parents' evening Tue 6pm", "School", sat)
        assertTrue(HandleThisRules.isShared(a))
        assertEquals(a, HandleThisRules.messageId("Parents' evening Tue 6pm\r\n", " School ", sat))
        assertNotEquals(a, HandleThisRules.messageId("Parents' evening Tue 6pm", "School", sat + 1))
        assertNotEquals(a, HandleThisRules.messageId("Parents' evening Wed 6pm", "School", sat))
        assertFalse(HandleThisRules.isShared("wa-Wife-1"))
    }

    @Test
    fun onlyTasksEventsAndRemindersAndTheCardSaysYouSharedIt() {
        val raw = listOf(
            RawRequestProposal("work_from_home", date = "2026-10-20"),
            RawRequestProposal("event", "parents' evening", time = "18:00", words = "Tuesday 20 October"),
            RawRequestProposal("task", "Return the reply slip", words = "by Friday"),
        )
        val allowed = HandleThisRules.allowed(raw)
        assertEquals(listOf("event", "task"), allowed.map { it.kind })
        val checked = MessageRequestRules.check(allowed, sat, 21 * 60 + 40)
        assertEquals(CivilDate.toEpochDay(2026, 10, 20), checked[0].day)
        assertEquals(18 * 60, checked[0].minute)

        val id = HandleThisRules.messageId("Parents' evening …", "School", sat)
        val message = RequestMessage(id, HandleThisRules.PERSON, HandleThisRules.quoteText("Parents' evening is Tuesday 20 October.", "School"), at)
        val card = MessageRequestRules.card(message, 0, checked[0], sat, cal)
        assertEquals("You shared · 21:40", card.from)
        assertEquals("“School · Parents' evening is Tuesday 20 October.”", card.quote)
        assertEquals("Add event: Parents' evening · Tue 20 Oct · 18:00", card.action)
        assertTrue(card.spoken.startsWith("You shared “School"), card.spoken)
        // A watched person's card still reads "From …".
        val wife = MessageRequestRules.card(RequestMessage("wa-1", "Wife", "milk?", at), 0, checked[1], sat, cal)
        assertEquals("From Wife · 21:40", wife.from)

        // A shared card never notifies; a watched person's still does.
        val notices = NoticeSources.requestNotices(listOf(card, wife), NotificationSettings.DEFAULT)
        assertEquals(listOf("request:${wife.id}"), notices.map { it.key })
    }

    @Test
    fun aDeadlineIsItsDay() {
        val fri = CivilDate.toEpochDay(2026, 10, 16)
        assertEquals(fri, RequestDates.resolve("by Friday", sat, 0)?.day)
        assertEquals(fri, RequestDates.resolve("before Fri", sat, 0)?.day)
        assertEquals(CivilDate.toEpochDay(2026, 10, 15), RequestDates.resolve("no later than the 15th", sat, 0)?.day)
        assertEquals(null, RequestDates.resolve("by", sat, 0))
    }

    @Test
    fun linesForTheSheet() {
        assertEquals("1 thing to add", HandleThisRules.readyLine(1))
        assertEquals("3 things to add", HandleThisRules.readyLine(3))
    }

    @Test
    fun aRenewalNeedsADayAndReadsItsDetailsWithSafeDefaults() {
        val raw = listOf(
            RawRequestProposal("renewal", "Netflix", "2026-11-01", "08:00", null, "subscription", null, "10.99"),
            RawRequestProposal("renewal", "Passport", null, null, "next March", "licence"),
            RawRequestProposal("renewal", "Car insurance", "2026-10-01", type = "insurance"), // already gone
            RawRequestProposal("renewal", "Something", "2026-12-01", cost = "free"),
        )
        val got = MessageRequestRules.check(HandleThisRules.allowed(raw), sat, 600, allowRenewal = true)
        assertEquals(listOf("Netflix", "Something"), got.map { it.title })
        // A subscription repeats monthly when the text didn't say; no time on a renewal.
        assertEquals(RenewalDetails(ObligationKind.SUBSCRIPTION, RenewalRepeat.MONTHLY, 1_099), got[0].renewal)
        assertEquals(null, got[0].minute)
        assertEquals(RenewalDetails(ObligationKind.OTHER, RenewalRepeat.NONE, null), got[1].renewal)
        val card = MessageRequestRules.card(RequestMessage("share:x:1", HandleThisRules.PERSON, "Your Netflix…", at), 0, got[0], sat, cal)
        assertEquals("Add to renewals: Netflix · renews Sun 1 Nov", card.action)
        assertEquals("Subscription · every month · £10.99 a month", card.detail)
        assertEquals(null, card.changeLabel)
        assertEquals(RequestPlan.AddRenewal("Netflix", got[0].day!!, got[0].renewal!!), RequestAcceptRules.plan(got[0], canAddEvent = true))
        assertEquals(null, RequestAcceptRules.plan(got[0], canAddEvent = true, change = true))
    }

    @Test
    fun aReplyDraftKeepsItsParagraphsAndIsDroppedWhenUnsafe() {
        // Slice 3: the model's draft as Meka will copy it.
        assertEquals(
            "Hi Mrs Patel,\n\nYes, Theo will be on the trip.\nThank you!",
            HandleThisRules.cleanReply("  \"Hi   Mrs Patel,\r\n\r\n\r\nYes, Theo will be on the trip.\nThank you!\u0007\"  "),
        )
        assertEquals(null, HandleThisRules.cleanReply(null))
        assertEquals(null, HandleThisRules.cleanReply("   \n\n "))
        // A link, an address or a long number never goes in Meka's mouth; nor does a placeholder he might send unread.
        assertEquals(null, HandleThisRules.cleanReply("Yes please, details at www.trip-pay.co.uk"))
        assertEquals(null, HandleThisRules.cleanReply("Yes, copy in office@school.example please"))
        assertEquals(null, HandleThisRules.cleanReply("Call me on 07700 900123"))
        assertEquals(null, HandleThisRules.cleanReply("Dear [Teacher's name], yes."))
        assertEquals(null, HandleThisRules.cleanReply("x".repeat(HandleThisRules.MAX_REPLY + 1)))
        assertEquals(null, HandleThisRules.cleanReply((1..7).joinToString("\n\n") { "Para $it." }))
        assertEquals("Yes, 2 of us at 18:00.", HandleThisRules.cleanReply("Yes, 2 of us at 18:00."))
    }

    @Test
    fun aReplyCardCopiesItsDraftAndOnlyAShareMakesOne() {
        val raw = listOf(
            RawRequestProposal("reply", "reply to Mrs Patel", body = "Yes, Theo will be there."),
            RawRequestProposal("reply", "Second reply", body = "Another one."),
            RawRequestProposal("reply", "Unsafe", body = "Pay at https://x.example"),
        )
        // A watched person's message never becomes a reply card; a share makes at most one.
        assertTrue(MessageRequestRules.check(raw, sat, 600).isEmpty())
        val p = MessageRequestRules.check(raw, sat, 600, allowReply = true).single()
        assertEquals(RequestProposal(RequestKind.REPLY, "Reply to Mrs Patel", null, null, reply = "Yes, Theo will be there."), p)
        assertEquals(
            null,
            MessageRequestRules.check(listOf(RawRequestProposal("reply", "No words")), sat, 600, allowReply = true).singleOrNull(),
        )
        // No title: "Reply".
        assertEquals("Reply", MessageRequestRules.check(listOf(RawRequestProposal("reply", body = "Yes.")), sat, 600, allowReply = true).single().title)

        val message = RequestMessage(HandleThisRules.messageId("Can Theo come?", "Trip", sat), HandleThisRules.PERSON, "Trip · Can Theo come?", at)
        val card = MessageRequestRules.card(message, 0, p, sat, cal)
        assertEquals("Draft reply: Reply to Mrs Patel", card.action)
        assertEquals("Copy reply", card.addLabel)
        assertEquals(null, card.changeLabel)
        assertEquals("Not needed", card.declineLabel)
        assertEquals("Yes, Theo will be there.", card.draft)
        assertEquals(null, card.detail)
        assertTrue(card.spoken.endsWith("The draft says: Yes, Theo will be there."), card.spoken)

        val plan = RequestAcceptRules.plan(p, canAddEvent = true)
        assertEquals(RequestPlan.CopyReply("Reply to Mrs Patel", "Yes, Theo will be there."), plan)
        assertEquals(null, RequestAcceptRules.plan(p, canAddEvent = true, change = true))
        assertEquals(HandleThisRules.COPIED_LINE, RequestAcceptRules.doneLine(plan!!, RequestKind.REPLY, sat))
    }
}
