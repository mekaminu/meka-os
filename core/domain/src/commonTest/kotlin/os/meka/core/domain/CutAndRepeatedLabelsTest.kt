package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through 2026-11-08 A11: labels said twice, or a control sitting over a line that says it does nothing. */
class CutAndRepeatedLabelsTest {
    @Test
    fun howItWorksPutsTheFullLineInTheShortOnesPlace() {
        val r = MailAutopilotPanelRules
        assertEquals(r.OFF_SHORT, r.shownLine(r.OFF_SHORT, r.OFF_LINE, open = false))
        assertEquals(r.OFF_LINE, r.shownLine(r.OFF_SHORT, r.OFF_LINE, open = true))
        // A switch with nothing more keeps its one line either way.
        assertEquals(r.OFF_SHORT, r.shownLine(r.OFF_SHORT, r.OFF_SHORT, open = true))
    }

    @Test
    fun theSwitchedLineDoesNotRepeatTheCardsLine() {
        val r = MailAutopilotPanelRules
        for (on in listOf(true, false)) {
            val said = r.switchedLine(on)
            assertTrue(said.startsWith(if (on) "Autopilot is on" else "Autopilot is off"), said)
            val card = r.panel(MailAutopilotSetting(on = on, changedAtMs = null), paused = false)
            val rest = card.shortLine.substringAfter(" · ")
            assertFalse(said.contains(rest), "\"$said\" repeats the card's \"$rest\"")
            assertFalse(said.contains("stays where it lands"), said)
        }
    }

    @Test
    fun noDraftRepliesOverTheAisNothingIsDraftedLine() {
        assertEquals("Draft replies to 2", MailSendAllRules.draftLabel(2, aiDown = false))
        assertNull(MailSendAllRules.draftLabel(2, aiDown = true))
        assertEquals(MailSendAllRules.draftLabel(3), MailSendAllRules.draftLabel(3, aiDown = false))
        assertNull(MailSendAllRules.draftLabel(1, aiDown = false))
        // The bar stays for the AI's line and Send all.
        assertTrue(MailSendAllRules.barShows(2))
        assertFalse(MailSendAllRules.barShows(1))
        val off = assertNotNull(AiFeatureRules.line(AiFeature.DRAFTS, AiStatusView("off", lit = false, canAsk = false, mode = AiMode.OFF)))
        assertTrue(off.contains("nothing is drafted"), off)
    }

    @Test
    fun theRangeChipsNeverSitUnderNothing() {
        fun range(points: Int) = TradingRange(TradingEquity.MONTH, TradingEquity.MONTH, null, null,
            (0 until points).map { MoneyWorthPoint("d$it", it * 100L, "day $it") }, "One month")
        assertEquals(TradingEquity.NO_LINE, TradingEquity.lineHint(range(0)))
        assertEquals(TradingEquity.NO_LINE, TradingEquity.lineHint(range(1)))
        assertNull(TradingEquity.lineHint(range(2)))
    }

    @Test
    fun theForecastTilesSayWholeLabels() {
        assertEquals("OUT / MONTH", MoneyPlanMore.OUT)
    }
}
