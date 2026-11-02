package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ada plans with you, part 3 slice 4: the errands he asked Obi for in Obi is working on (summary, Daily row, Ask's
 * lines and whether they carry web words) and the Ask card that starts one ("Obi, find someone to service the boiler").
 */
class ObiErrandAppsTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 11, 2)
    private val now = cal.toEpochMs(today, 11 * 60)

    private fun job(id: String, state: ObiJobState, need: String? = null, found: String? = null, draft: String? = null, stopped: Boolean = false) =
        ObiJob(id, "Job $id", now - 1_000, state, stopped, emptyList(), emptyList(), found, need, draft, now, null, null, 0, 0, null)

    private fun view(jobs: List<ObiJob>, setting: WorkingOnSetting = WorkingOnSetting()) = WorkingOnRules.view(
        setting, RenewalsView.EMPTY, GiftsView.EMPTY, emptyList(), today, jobs = ObiJobRules.rows(jobs, now, cal),
    )

    @Test
    fun hisErrandsCountInTheSummaryAndTheDailyRow() {
        assertEquals(WorkingOnRules.EMPTY_LINE, view(emptyList()).summary)
        val one = view(listOf(job("a", ObiJobState.WORKING)))
        assertEquals("On 1 errand for you · nothing needs you", one.summary)
        assertEquals(DailyLine("On 1 errand", false), WorkingOnRules.dailyLine(one))
        val needs = view(listOf(job("a", ObiJobState.NEEDS_YOU, need = "Which day suits you?"), job("b", ObiJobState.WORKING)))
        assertEquals("On 2 errands for you · 1 needs you", needs.summary)
        assertEquals(1, needs.needCount)
        assertEquals(DailyLine("1 needs you", true), WorkingOnRules.dailyLine(needs))
        // Every standing errand off still shows the errands he asked for.
        val allOff = view(listOf(job("a", ObiJobState.WORKING)), WorkingOnSetting(StandingErrand.entries.toSet()))
        assertEquals("On 1 errand for you · nothing needs you", allOff.summary)
        // A finished one stays listed but isn't open.
        val done = view(listOf(job("a", ObiJobState.DONE, found = "Three engineers nearby")))
        assertEquals(0, done.openJobs)
        assertFalse(done.isEmpty)
    }

    @Test
    fun askLinesNameEachErrandAndSayWhenTheyCarryWebWords() {
        val v = view(listOf(job("a", ObiJobState.NEEDS_YOU, need = "Which day suits you?", found = "Two firms service boilers nearby", draft = "Hello, …")))
        val head = WorkingOnRules.askLines(v, full = false)
        assertEquals(1, head.size)
        assertTrue(head.single().contains("1 errand he asked for open"), head.single())
        val full = WorkingOnRules.askLines(v, full = true)
        val line = full.first { it.startsWith("Errand · ") }
        assertTrue(line.contains("Job a · Needs you · Two firms service boilers nearby · needs you: Which day suits you?"), line)
        assertTrue(line.contains("a draft is waiting"), line)
        assertFalse(line.contains("Hello"), "the draft itself is never quoted: $line")
        assertTrue(WorkingOnRules.fromWeb(v, full = true))
        assertFalse(WorkingOnRules.fromWeb(v, full = false))
        assertFalse(WorkingOnRules.fromWeb(view(listOf(job("b", ObiJobState.ASKED))), full = true))
        assertTrue(WorkingOnRules.asked("what did you find about the boiler?"))
        assertTrue(WorkingOnRules.asked("how are the errands I asked you for?"))
    }

    @Test
    fun theStartErrandCardFromTheModel() {
        val ctx = AskContext("2026-11-02", "Monday 2 November 2026 · 11:00", emptyList(), emptyMap(), untrusted = false)
        fun card(a: AskRawAction) = AskRules.card(a, ctx, emptyMap(), now, cal)
        val c = card(AskRawAction("start_errand", title = "  find someone to service the boiler. "))!!
        val p = assertIs<AskProposal.StartErrand>(c.proposal)
        assertEquals("Find someone to service the boiler", p.text)
        assertEquals("Ask Obi to work on: Find someone to service the boiler", c.line)
        assertEquals(ObiJobRules.CARD_BUTTON, c.button)
        assertEquals("Obi is on it · Find someone to service the boiler · see Obi is working on", AskRules.doneLine(p, today))
        assertEquals("start an errand: Find someone to service the boiler", TalkRules.phrase(p, today, past = false))
        assertNull(card(AskRawAction("start_errand", title = "   ")))
        assertNull(card(AskRawAction("start_errand")))
        assertNull(TrustRules.kindOf(p))
        assertTrue("start_errand" in AskRules.KINDS)
    }
}
