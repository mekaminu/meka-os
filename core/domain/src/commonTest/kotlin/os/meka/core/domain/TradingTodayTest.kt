package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 6: Today's line and the brief's sentence, never an amount. Invented addresses and figures. */
class TradingTodayTest {
    private val now = CivilDate.toEpochDay(2026, 10, 17) * 86_400_000L + 7 * 3_600_000L
    private val address = "0x" + "cd34".repeat(10)

    private fun wallet(
        positions: List<TradingPosition> = emptyList(), day: Long? = 12_000, readAt: Long? = now - 60_000, label: String = "Main",
        drawdown: Long? = null, base: Long? = null, addr: String = address,
    ) = TradingWallet(
        TradingTracker.walletId(addr), addr, label, TradingTracker.OK, readAt, readAt, 1_000_000, day, null, null, null, 0,
        positions, drawdownCents = drawdown, drawdownBaseCents = base,
    )

    private val eth = TradingPosition("ETH", "2", "3000", "3000", "1500", 4_000, 3) // 50% from liquidation
    private val near = TradingPosition("BTC", "-0.1", "60000", "60000", "64800", -2_000, 20) // 8% away, short

    private fun noAmounts(l: TradingTodayLine) {
        assertFalse('$' in l.text || '£' in l.text || '$' in l.spoken || '£' in l.spoken, l.toString())
    }

    @Test
    fun upOrDownAndHowManyAreOpen() {
        val up = TradingTodayRules.todayLine(listOf(wallet(listOf(eth, eth.copy(coin = "SOL")))), TradingAlertSetting.DEFAULT, now)!!
        assertEquals("Trading · up today · 2 positions open", up.text)
        assertEquals("Trading: you're up over the last day, and 2 positions are open.", up.spoken)
        assertFalse(up.lit)
        noAmounts(up)
        val down = TradingTodayRules.todayLine(listOf(wallet(listOf(eth), day = -500)), TradingAlertSetting.DEFAULT, now)!!
        assertEquals("Trading · down today · 1 position open", down.text)
        // Two accounts add up: +120 and −200 is down.
        val two = TradingTodayRules.todayLine(listOf(wallet(day = 12_000), wallet(day = -20_000, label = "Bot", addr = "0x" + "ef56".repeat(10))),
            TradingAlertSetting.DEFAULT, now)!!
        assertEquals("Trading · down today · no positions open", two.text)
    }

    @Test
    fun nothingWhenThereIsNothingFreshToSay() {
        assertNull(TradingTodayRules.todayLine(emptyList(), TradingAlertSetting.DEFAULT, now))
        // A flat day with nothing open says nothing.
        assertNull(TradingTodayRules.todayLine(listOf(wallet(day = 0)), TradingAlertSetting.DEFAULT, now))
        // An old read or one never made says nothing.
        assertNull(TradingTodayRules.todayLine(listOf(wallet(listOf(eth), readAt = now - TradingTodayRules.STALE_MS - 1)), TradingAlertSetting.DEFAULT, now))
        assertNull(TradingTodayRules.todayLine(listOf(wallet(listOf(eth), readAt = null)), TradingAlertSetting.DEFAULT, now))
    }

    @Test
    fun nearLiquidationAndADropAreLitAtHisLevels() {
        val liq = TradingTodayRules.todayLine(listOf(wallet(listOf(eth, near))), TradingAlertSetting.DEFAULT, now)!!
        assertEquals("Trading · BTC short 8% from liquidation", liq.text)
        assertEquals("Trading: your BTC short is 8% from liquidation.", liq.spoken)
        assertTrue(liq.lit)
        noAmounts(liq)
        // At 5% it isn't near; with the alert off the line still uses 10% to light it.
        assertFalse(TradingTodayRules.todayLine(listOf(wallet(listOf(near))), TradingAlertSetting(liquidation = TradingLiqAlert.FIVE), now)!!.lit)
        assertTrue(TradingTodayRules.todayLine(listOf(wallet(listOf(near))), TradingAlertSetting(liquidation = TradingLiqAlert.OFF), now)!!.lit)

        val drop = TradingTodayRules.todayLine(listOf(wallet(day = -3_000, drawdown = 22_000, base = 100_000)), TradingAlertSetting.DEFAULT, now)!!
        assertEquals("Trading · down today · Main 22% below this month's high", drop.text)
        assertEquals("Trading: you're down over the last day, and Main is 22% below this month's high.", drop.spoken)
        assertTrue(drop.lit)
        // Under his level (30%) it's the quiet line.
        val quiet = TradingTodayRules.todayLine(listOf(wallet(day = -3_000, drawdown = 22_000, base = 100_000)),
            TradingAlertSetting(drop = TradingDropAlert.THIRTY), now)!!
        assertEquals("Trading · down today · no positions open", quiet.text)
        assertFalse(quiet.lit)
    }

    @Test
    fun theBriefSaysItAndShortOnlyWhenLit() {
        val up = TradingTodayRules.todayLine(listOf(wallet(listOf(eth))), TradingAlertSetting.DEFAULT, now)
        assertEquals("On Hyperliquid, you're up over the last day, and 1 position is open.", TradingTodayRules.briefSentence(up, BriefLength.STANDARD))
        assertNull(TradingTodayRules.briefSentence(up, BriefLength.SHORT))
        val liq = TradingTodayRules.todayLine(listOf(wallet(listOf(near))), TradingAlertSetting.DEFAULT, now)
        assertEquals("On Hyperliquid, your BTC short is 8% from liquidation.", TradingTodayRules.briefSentence(liq, BriefLength.SHORT))
        assertNull(TradingTodayRules.briefSentence(null, BriefLength.FULL))
        // In the script, after the habits.
        val story = BriefStory(habits = listOf("gym"), trading = TradingTodayRules.briefSentence(up, BriefLength.STANDARD))
        val v = MorningBriefView.EMPTY.copy(greeting = "Good morning", story = story)
        val script = BriefScriptRules.script(v)
        assertTrue("On Hyperliquid, you're up over the last day" in script, script)
        assertTrue(script.indexOf("Today's habit: gym.") < script.indexOf("On Hyperliquid"), script)
    }
}
