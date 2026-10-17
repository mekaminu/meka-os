package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spam-call lookup (2026-10-17): reading report sites into a verdict, and what screening does with it. Invented numbers and pages. */
class NumberLookupTest {
    private val n = "+44 1632 960691"
    private val t = 1_791_360_000_000L

    private fun report(site: String, text: String, title: String = "01632 960691 - Who called me?") =
        WebHit("https://www.$site/number/01632960691", title, text)

    @Test
    fun theNumberIsSearchedTheWayReportSitesListItAndNeverForEmergencyOrWithheld() {
        assertEquals("01632960691", NumberLookupRules.national(n))
        assertEquals("01632960691", NumberLookupRules.national("01632 960-691"))
        assertEquals("\"01632960691\"", NumberLookupRules.query("+441632960691"))
        assertEquals("33612345678", NumberLookupRules.national("+33 6 12 34 56 78"))
        assertNull(NumberLookupRules.national("999"))
        assertNull(NumberLookupRules.national("101"))
        assertNull(NumberLookupRules.national(null))
        assertNull(NumberLookupRules.national("Withheld"))
        assertNull(NumberLookupRules.national("12345"))
        assertFalse(NumberLookupRules.lookUpable("112"))
    }

    @Test
    fun severalScamReportsMakeASureScamNamedByTheClaimMostReportsMake() {
        val hits = listOf(
            report("who-called.co.uk", "23 reports. Scam call claiming to be the police, said my bank account was used for fraud."),
            report("tellows.co.uk", "Rated dangerous. Pretended to be a police officer and asked for money. Scam!"),
            report("shouldianswer.co.uk", "Police impersonation scam, hung up when I asked for a name."),
            WebHit("https://example.org/how-to-spot-scam-calls", "How to spot scam calls", "Scammers pretend to be the police."),
        )
        val r = NumberLookupRules.classify(n, hits)
        assertEquals(NumberVerdict.SCAM, r.verdict)
        assertEquals(23, r.reports)
        assertTrue(r.sure)
        assertEquals("a police-impersonation scam", r.why)
        assertEquals("reported 23× as a police-impersonation scam", NumberLookupRules.line(r))
        assertTrue(NumberLookupRules.shouldBlock(r))
    }

    @Test
    fun pagesThatDontNameTheNumberNeverCount() {
        val generic = List(5) { WebHit("https://who-called.co.uk/blog/$it", "Scam calls rise", "Fraud and scam calls claiming to be HMRC.") }
        assertEquals(NumberVerdict.UNKNOWN, NumberLookupRules.classify(n, generic).verdict)
        // The number written another way still counts.
        val spaced = WebHit("https://tellows.co.uk/num/x", "Who called from 01632 960 691?", "Scam, fraud, fake HMRC")
        assertTrue(NumberLookupRules.mentions(spaced, "01632960691"))
        assertTrue(NumberLookupRules.mentions(WebHit("https://x.test", "+44 1632 960691", ""), "01632960691"))
    }

    @Test
    fun salesCallersAreNuisanceAndGoToTheAssistantNeverBlocked() {
        val hits = listOf(
            report("who-called.co.uk", "5 comments. Cold call about an accident claim, very annoying."),
            report("unknownphone.com", "Sales call, keeps calling every day."),
        )
        val r = NumberLookupRules.classify(n, hits)
        assertEquals(NumberVerdict.NUISANCE, r.verdict)
        assertEquals("reported 5× as nuisance or sales calls", NumberLookupRules.line(r))
        assertFalse(NumberLookupRules.shouldBlock(r))
        assertTrue(NumberLookupRules.toAssistant(r))
    }

    @Test
    fun aBusinessListingItsOwnNumberIsLegitAndAStrayScamMentionIsNotSure() {
        val shop = WebHit("https://www.oakfield-dental.test/contact", "Contact us", "Call us on 01632 960691. Opening hours 9-5.")
        val legit = NumberLookupRules.classify(n, listOf(shop))
        assertEquals(NumberVerdict.LEGIT, legit.verdict)
        assertEquals("oakfield-dental.test", legit.why)
        assertNull(NumberLookupRules.line(legit))
        assertFalse(NumberLookupRules.shouldBlock(legit))

        val mixed = NumberLookupRules.classify(n, listOf(
            shop, shop.copy(url = "https://www.oakfield-dental.test/team", text = "Our helpline 01632 960691"),
            report("who-called.co.uk", "Thought it was a scam but it was my dentist. 4 reports"),
            report("tellows.co.uk", "Not a scam, the dental surgery reminder"),
        ))
        assertEquals(NumberVerdict.SCAM, mixed.verdict)
        assertFalse(mixed.sure)
        assertFalse(NumberLookupRules.shouldBlock(mixed))
        assertTrue(NumberLookupRules.toAssistant(mixed))
        assertEquals(NumberReputation.UNKNOWN, NumberLookupRules.classify(n, emptyList()))
        assertNull(NumberLookupRules.line(null))
    }

    @Test
    fun keptThirtyDaysAWeekForNothingKnownADayAfterAFailure() {
        assertEquals(30, NumberLookupRules.keepDays(NumberReputation(NumberVerdict.SCAM, 3, true)))
        assertEquals(30, NumberLookupRules.keepDays(NumberReputation(NumberVerdict.LEGIT)))
        assertEquals(7, NumberLookupRules.keepDays(NumberReputation.UNKNOWN))
        assertEquals(1, NumberLookupRules.keepDays(null))
    }

    private val lists = PeopleLists(family = setOf("Jordan")).withNumber("Jordan", "+441632960111")
    private val scam = NumberReputation(NumberVerdict.SCAM, 12, true, "an HMRC scam")
    private val nuisance = NumberReputation(NumberVerdict.NUISANCE, 4)

    private fun decide(number: String, signals: CallSignals, on: Boolean = false, protection: Boolean = true) =
        CallScreeningRules.decide(on, false, number, lists, emptyList(), t, emptySet(), signals, spamProtection = protection)

    @Test
    fun aSureScamIsBlockedBeforeItRingsButNeverSomeoneHeKnowsOrAClaimedOfficialBody() {
        val d = decide(n, CallSignals(reputation = scam))
        assertEquals(CallVerdict.BLOCK, d.verdict)
        assertEquals(CallReason.REPORTED_SCAM, d.reason)
        assertNull(CallScreeningRules.activityLine(d, n)) // the facade logs it as an automatic action with Undo
        assertTrue(decide(n, CallSignals(reputation = scam, knownContact = true)).rings)
        assertTrue(decide(n, CallSignals(reputation = scam, calledRecently = true)).rings)
        assertTrue(decide(n, CallSignals(reputation = scam, household = true)).rings)
        assertTrue(decide("+441632960111", CallSignals(reputation = scam)).rings)
        assertEquals(CallReason.OFFICIAL_CLAIM, decide(n, CallSignals(reputation = scam, networkName = "HMRC")).reason)
        // Spam protection off: no block; with the assistant on it goes there instead.
        assertTrue(decide(n, CallSignals(reputation = scam), protection = false).rings)
        assertEquals(CallReason.REPORTED_NUISANCE, decide(n, CallSignals(reputation = scam), on = true, protection = false).reason)
    }

    @Test
    fun aNuisanceCallerGoesToTheAssistantOnlyWhileItsOnAndIsWatchedAfterwards() {
        assertTrue(decide(n, CallSignals(reputation = nuisance)).rings)
        val d = decide(n, CallSignals(reputation = nuisance), on = true)
        assertEquals(CallVerdict.DECLINE, d.verdict)
        assertEquals(CallReason.REPORTED_NUISANCE, d.reason)
        assertEquals("Sent a call from 01632 960691 to the assistant", CallScreeningRules.activityLine(d, n)!!.first)
        assertTrue(UnknownCallRules.watch(d, n, CallSignals(reputation = nuisance)))
        // A business or nothing known: as before.
        assertEquals(CallReason.SWITCHED_OFF, decide(n, CallSignals(reputation = NumberReputation(NumberVerdict.LEGIT))).reason)
    }

    @Test
    fun notificationsSayWhatPeopleReported() {
        val cal = LocalCalendar.UTC
        val at = 1_791_381_720_000L
        val before = CallNoticeRules.autoBlockNotice(n, AutoBlockTrigger.REPORTED, at, cal, reported = "reported 12× as an HMRC scam")
        assertTrue(before.big.startsWith("Blocked before it rang · "), before.big)
        assertTrue(before.big.contains("reported 12× as an HMRC scam"))
        val after = CallNoticeRules.autoBlockNotice(n, AutoBlockTrigger.REPORTED_AFTER_CALL, at, cal)
        assertTrue(after.big.startsWith("Rang · ") && after.big.contains("reported online as a scam"), after.big)
        assertEquals("People online have reported 12× as an HMRC scam; it didn't ring",
            AutoBlockRules.detail(AutoBlockTrigger.REPORTED, reported = "reported 12× as an HMRC scam"))

        val offer = UnknownCallRules.offer(
            n, CallOutcome.MISSED, 0, at, at + 60_000, emptySet(), emptyList(), cal, reputation = "reported 4× as nuisance or sales calls",
        )!!
        assertEquals("01632 960691 · not in your contacts · reported 4× as nuisance or sales calls", offer.title)
        val toAssistant = UnknownCallRules.offer(n, CallOutcome.TO_ASSISTANT, 0, at, at + 60_000, emptySet(), emptyList(), cal, reputation = "reported 4× as nuisance or sales calls")!!
        assertFalse(toAssistant.line.contains("couldn't verify"))
    }
}
