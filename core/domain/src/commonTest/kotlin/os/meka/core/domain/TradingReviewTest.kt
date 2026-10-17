package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 9: last month in review, facts only, and its heads-up. Invented addresses and figures. */
class TradingReviewTest {
    private val cal = LocalCalendar.UTC
    private fun at(y: Int, m: Int, d: Int, minute: Int) = cal.toEpochMs(CivilDate.toEpochDay(y, m, d), minute)
    private val now = at(2026, 10, 17, 10 * 60)

    private fun wallet(n: Int, month: String?, review: List<TradingCoinStats>, prev: List<TradingCoinStats>?, at: Long? = null): TradingWallet {
        val address = "0x" + n.toString().repeat(40)
        return TradingWallet(TradingTracker.walletId(address), address, "Account $n", TradingTracker.OK, now, now, 1_000_000, null, null, null, null, 0,
            emptyList(), reviewMonth = month, review = review, reviewPrev = prev, reviewAtMs = at)
    }

    private val eth = TradingCoinStats("ETH", 4, 3, 1, 30_000, 2_000, -1_000, 40_000, -10_000, 18_000, -10_000)
    private val sol = TradingCoinStats("SOL", 2, 0, 2, -8_000, 500, 0, 0, -8_000, -3_000, -5_000)

    @Test
    fun monthsRunOnTheCalendar() {
        assertEquals(TradingMonth(2026, 9), TradingMonth.lastFinished(now, cal))
        assertEquals(TradingMonth(2025, 12), TradingMonth.lastFinished(at(2026, 1, 1, 0), cal))
        assertEquals("2026-09", TradingMonth(2026, 9).key)
        assertEquals(TradingMonth(2026, 9), TradingMonth.parse("2026-09"))
        assertNull(TradingMonth.parse("2026-13"))
        assertEquals(at(2026, 9, 1, 0), TradingMonth(2026, 9).fromMs(cal))
        assertEquals(at(2026, 10, 1, 0), TradingMonth(2026, 9).toMs(cal))
        assertTrue(TradingReviewRules.due(wallet(1, "2026-08", emptyList(), null), TradingMonth(2026, 9)))
        assertTrue(!TradingReviewRules.due(wallet(1, "2026-09", emptyList(), null), TradingMonth(2026, 9)))
    }

    @Test
    fun theReviewSaysWhatHappenedInPlainSentences() {
        val v = TradingReviewRules.view(listOf(wallet(1, "2026-09", listOf(eth, sol), listOf(eth.copy(realisedCents = 10_000)))), now, cal)!!
        assertEquals("SEPTEMBER IN REVIEW", v.head)
        assertEquals("+$185.00", v.amount)
        assertEquals(true, v.up)
        assertEquals(listOf(
            "You closed 6 trades in September and won 3 of them (50%).",
            "Closed trades +$220.00, fees −$25.00, funding −$10.00: +$185.00 after fees and funding.",
            "$115.00 better than August (+$70.00).",
            "ETH did best (+$270.00 over 4 trades); SOL did worst (−$85.00 over 2 trades).",
            "Your biggest win was +$180.00 on ETH, your biggest loss −$100.00 on ETH.",
            "Wins averaged +$133.33 and losses −$60.00.",
            "Fees came to 11% of what the closed trades made.",
        ), v.sentences)
        assertTrue(v.foot.contains("not trading advice"))
        // Nothing reads as advice.
        assertTrue(v.sentences.none { it.contains("should") || it.contains("try ") })
    }

    @Test
    fun accountsAddUpAndAnOldOrMissingMonthIsLeftOut() {
        val a = wallet(1, "2026-09", listOf(sol), emptyList())
        val b = wallet(2, "2026-09", listOf(sol), null)
        val c = wallet(3, "2026-08", listOf(eth), emptyList())
        val v = TradingReviewRules.view(listOf(a, b, c), now, cal)!!
        assertEquals("You closed 4 trades in September and won none of them.", v.sentences[0])
        // b has no August read, so no comparison; one coin traded.
        assertTrue(v.sentences.none { it.contains("August") })
        assertTrue("All of it on SOL." in v.sentences)
        assertTrue(v.foot.endsWith("1 account still being read"))
        assertNull(TradingReviewRules.view(listOf(c), now, cal))
        assertEquals("No trades, fees or funding in September.",
            TradingReviewRules.view(listOf(wallet(1, "2026-09", emptyList(), emptyList())), now, cal)!!.summary)
    }

    @Test
    fun oneHeadsUpFromNineOnTheFirstWithNoAmounts() {
        val w = wallet(1, "2026-09", listOf(eth), emptyList(), at = at(2026, 10, 1, 1 * 60))
        val n = TradingReviewRules.notice(listOf(w), at(2026, 10, 1, 8 * 60), cal)!!
        assertEquals(at(2026, 10, 1, 9 * 60), n.atMs)
        assertEquals("Your September trading review is ready", n.title)
        assertEquals(NoticeTarget.MONEY, n.target)
        assertTrue('$' !in n.title + n.text)
        assertEquals("trading:review:2026-09", n.key)
        // It's in TRADING's notices too, and gone after a week.
        assertTrue(TradingAlerts.notices(listOf(w), TradingAlertSetting.DEFAULT, at(2026, 10, 2, 9 * 60), cal).any { it.key == n.key })
        assertNull(TradingReviewRules.notice(listOf(w), at(2026, 10, 8, 0), cal))
        assertNull(TradingReviewRules.notice(listOf(wallet(1, null, emptyList(), null)), at(2026, 10, 1, 10 * 60), cal))
    }
}
