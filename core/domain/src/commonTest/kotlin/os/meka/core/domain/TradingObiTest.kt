package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money → Trading's Obi risk line and Kestrel card (approved mockup): read-only, each part only when its figures are there. */
class TradingObiTest {
    private val day = 86_400_000L
    private val now = 20_400L * day + 10 * 3_600_000L
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(day)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(day) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * day + minuteOfDay * 60_000L
    }

    private fun wallet(positions: List<TradingPosition> = emptyList(), trades: List<TradingCoinStats> = emptyList(), equity: Long? = 650_000) =
        TradingWallet(
            "w1", "0x" + "a".repeat(40), "Main", TradingTracker.OK, now, now, equity, null, null, null, null, 0, positions,
            trades = trades, tradesAtMs = if (trades.isEmpty()) null else now,
        )

    private fun worth(have: Long, trading: Long) = MoneyWorthView(
        MoneyWorth.HEAD, "£1", false, null, null, emptyList(), null, emptyList(), null, null, "", "", "", have, trading,
    )

    private fun coin(funding: Long) = TradingCoinStats("BTC", 3, 2, 1, 0, 0, funding, 0, 0, 0, 0)

    @Test
    fun obiSaysTheShareTheNearestLiquidationAndFunding() {
        val btc = TradingPosition("BTC", "0.1", "60000", "60000", "49800", -1_000, 5)
        val eth = TradingPosition("ETH", "-2", "3000", "3000", "3900", 500, 3)
        val r = assertNotNull(TradingRisk.view(listOf(wallet(listOf(eth, btc), listOf(coin(-3_820)))), worth(7_000_000, 630_000), TradingCcy.USD))
        assertEquals(
            "Trading is 9% of everything you hold. Your BTC long is 5×: a 17% drop reaches its liquidation price. " +
                "Funding has cost $38.20 in the last 30 days.",
            r.line,
        )
        assertFalse(r.lit)
        assertTrue(r.spoken.startsWith("Obi on your risk."))
        // A short: a rise reaches it, and nearer than the alert's level lights the line.
        val short = TradingPosition("SOL", "-10", null, "100", "105", 0, null)
        val s = assertNotNull(TradingRisk.view(listOf(wallet(listOf(short))), null, TradingCcy.USD))
        assertEquals("Your SOL short: a 5% rise reaches its liquidation price.", s.line)
        assertTrue(s.lit)
    }

    @Test
    fun nothingToSayMeansNoLine() {
        assertNull(TradingRisk.view(emptyList(), null, TradingCcy.USD))
        assertNull(TradingRisk.view(listOf(wallet(equity = null)), worth(1_000, 1_000), TradingCcy.USD))
        // No positions, no funding, no pounds yet: nothing worth a sentence.
        assertNull(TradingRisk.view(listOf(wallet()), null, TradingCcy.USD))
        // Funding received is said as paid to him; a few cents isn't said.
        assertEquals("Funding has paid you $12.00 in the last 30 days.", TradingRisk.view(listOf(wallet(trades = listOf(coin(1_200)))), null, TradingCcy.USD)?.line)
        assertNull(TradingRisk.view(listOf(wallet(trades = listOf(coin(-40)))), null, TradingCcy.USD))
    }

    private fun approval(id: String, askedAt: Long, title: String = "Buy 0.05 BTC at market", approved: Boolean = false, declined: Boolean = false,
                         expires: Long = askedAt + 3_600_000L, kind: ApprovalKind = ApprovalKind.TRADE) =
        Approval(id, kind, "kestrel", title, listOf("About £2,410", "Stop loss 5%", "Strategy: breakout"), askedAt, expires,
            approved, declined, null, null, null)

    @Test
    fun kestrelsCardShowsWhatWaitsAndTodaysTally() {
        val all = listOf(
            approval("a1", now - 3 * 3_600_000L, approved = true),
            approval("a2", now - 2 * 3_600_000L, declined = true),
            approval("a3", now - 10 * 60_000L, title = "Go long ETH 0.8 at 3,420"),
            approval("p1", now - 60_000L, kind = ApprovalKind.PAYMENT),
        )
        val k = assertNotNull(TradingKestrel.view(all, now, cal))
        assertEquals("KESTREL", k.head)
        assertEquals("1 trade waiting for your OK", k.status)
        assertTrue(k.lit)
        assertEquals("Go long ETH 0.8 at 3,420", k.waitingTitle)
        assertEquals("About £2,410 · Stop loss 5% · expires in 50 min", k.waitingLine)
        assertEquals("a3", k.approvalId)
        assertEquals(TradingKestrel.REVIEW, k.review)
        assertEquals("Today: 3 asked · 1 approved · 1 declined", k.tally)
    }

    @Test
    fun kestrelsCardRestsWhenNothingWaitsAndHidesWhenItNeverAsked() {
        assertNull(TradingKestrel.view(emptyList(), now, cal))
        assertNull(TradingKestrel.view(listOf(approval("p", now, kind = ApprovalKind.PAYMENT)), now, cal))
        // Asked long ago: no card.
        assertNull(TradingKestrel.view(listOf(approval("old", now - 40 * day, approved = true)), now, cal))
        val k = assertNotNull(TradingKestrel.view(listOf(approval("y", now - 2 * day, approved = true)), now, cal))
        assertEquals(TradingKestrel.NOTHING, k.status)
        assertFalse(k.lit)
        assertNull(k.review)
        assertNull(k.waitingTitle)
        assertTrue(k.tally!!.startsWith("Last asked "))
    }
}
