package os.meka.core.domain

import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 3: near-liquidation and drop alerts from what the server read, at his levels. Invented addresses and figures. */
class TradingAlertsTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val now = cal.toEpochMs(CivilDate.toEpochDay(2026, 10, 17), 2 * 60) // 02:00, in quiet hours
    private val address = "0x" + "ab12".repeat(10)

    private fun wallet(
        positions: List<TradingPosition> = emptyList(), readAt: Long? = now - 60_000, drawdown: Long? = null, base: Long? = null,
    ) = TradingWallet(
        TradingTracker.walletId(address), address, "Main", TradingTracker.OK, readAt, readAt, 1_000_000, null, null, null, null, 0,
        positions, drawdownCents = drawdown, drawdownBaseCents = base,
    )

    private val eth = TradingPosition("ETH", "2", "3000", "3000", "2760", -5_000, 10) // 8% from liquidation

    @Test
    fun aPositionNearLiquidationIsCriticalWithNoAmounts() {
        val n = TradingAlerts.notices(listOf(wallet(listOf(eth, eth.copy(coin = "BTC", liquidation = "2000")))), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals(NoticeTier.CRITICAL, n.tier)
        assertEquals(NoticeSource.TRADING, n.source)
        assertEquals(NoticeTarget.MONEY, n.target)
        assertEquals("ETH long near liquidation", n.title)
        assertTrue(n.text.startsWith("8% from liquidation · Main"))
        assertTrue('$' !in n.title + n.text)
        // It comes even in quiet hours (Critical), once per key.
        val g = Governor.evaluate(listOf(n), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), now, cal)
        assertEquals(listOf(n.key), g.post.map { it.key })
        assertTrue(Governor.evaluate(listOf(n), NotificationSettings.DEFAULT, DeviceAlerts.ALL, g.state, now, cal).post.isEmpty())
        // Within half his level it comes again, in the same place.
        val very = TradingAlerts.notices(listOf(wallet(listOf(eth.copy(liquidation = "2900")))), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals("ETH long very near liquidation", very.title)
        assertEquals(n.postId, very.postId)
        assertTrue(very.key != n.key)
    }

    @Test
    fun hisLevelsAndOldReadsDecide() {
        val w = wallet(listOf(eth))
        assertTrue(TradingAlerts.notices(listOf(w), TradingAlertSetting(liquidation = TradingLiqAlert.FIVE), now, cal).isEmpty())
        assertTrue(TradingAlerts.notices(listOf(w), TradingAlertSetting(liquidation = TradingLiqAlert.OFF), now, cal).isEmpty())
        assertEquals(1, TradingAlerts.notices(listOf(w), TradingAlertSetting(liquidation = TradingLiqAlert.TWENTY), now, cal).size)
        // A read older than half an hour never alerts.
        assertTrue(TradingAlerts.notices(listOf(wallet(listOf(eth), readAt = now - TradingAlerts.FRESH_MS - 1)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        assertTrue(TradingAlerts.notices(listOf(wallet(listOf(eth), readAt = null)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        // Lowering the source to the digest lowers the Critical one too.
        val n = TradingAlerts.notices(listOf(w), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals(NoticeTier.DIGEST, Governor.effectiveTier(n, NotificationSettings(tiers = mapOf(NoticeSource.TRADING to NoticeTier.DIGEST))))
    }

    @Test
    fun aDropFromTheMonthsHighIsAHeadsUpAtEachStep() {
        assertTrue(TradingAlerts.notices(listOf(wallet(drawdown = 150_000, base = 1_000_000)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        val n = TradingAlerts.notices(listOf(wallet(drawdown = 220_000, base = 1_000_000)), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("Main 22% below this month's high", n.title)
        assertTrue('$' !in n.title + n.text)
        val deeper = TradingAlerts.notices(listOf(wallet(drawdown = 410_000, base = 1_000_000)), TradingAlertSetting.DEFAULT, now, cal).single()
        assertTrue(deeper.key != n.key)
        assertEquals(n.postId, deeper.postId)
        assertEquals(1, TradingAlerts.notices(listOf(wallet(drawdown = 150_000, base = 1_000_000)), TradingAlertSetting(drop = TradingDropAlert.TEN), now, cal).size)
        assertTrue(TradingAlerts.notices(listOf(wallet(drawdown = 900_000, base = 1_000_000)), TradingAlertSetting(drop = TradingDropAlert.OFF), now, cal).isEmpty())
    }

    @Test
    fun theSettingIsReadWrittenAndShown() {
        assertEquals(TradingAlertSetting.DEFAULT, TradingAlerts.read(null))
        val s = TradingAlerts.with(TradingAlertSetting.DEFAULT, TradingAlerts.LIQ, "5")!!
        assertEquals(TradingLiqAlert.FIVE, s.liquidation)
        assertNull(TradingAlerts.with(s, TradingAlerts.DROP, "15"))
        assertNull(TradingAlerts.with(s, "nope", "10"))
        val off = TradingAlerts.with(s, TradingAlerts.DROP, "off")!!
        assertEquals(off, TradingAlerts.read(TradingAlerts.settingFields(off, now)))
        assertEquals(TradingLiqAlert.TEN, TradingAlerts.read(mapOf(TradingAlertFields.LIQ_PCT to 7L.fv())).liquidation)
        val v = TradingAlerts.view(off)
        assertEquals(listOf("5"), v.liqChips.filter { it.on }.map { it.key })
        assertEquals(listOf("off"), v.dropChips.filter { it.on }.map { it.key })
        assertTrue(v.line.startsWith("An alert when a position is within 5% of liquidation, even in quiet hours · none for a drop"))
        // The Money view: the account's row says how far below its high, lit at his level; ALERTS is there.
        val view = TradingTracker.view(listOf(wallet(drawdown = 220_000, base = 1_000_000)), now, cal)
        val row = view.wallets.single()
        assertTrue(row.line.contains("22% below this month's high"))
        assertTrue(row.lit)
        assertEquals(TradingAlerts.HEAD, view.alerts?.head)
        assertTrue(!TradingTracker.view(listOf(wallet(drawdown = 220_000, base = 1_000_000)), now, cal, TradingAlertSetting(drop = TradingDropAlert.THIRTY)).wallets.single().lit)
        assertNull(TradingTracker.view(emptyList(), now, cal).alerts)
    }

    // ---- Slice 7: a big order and high funding ----

    private fun walletWith(fills: List<TradingFill> = emptyList(), funding: Long? = null, readAt: Long? = now - 60_000) =
        wallet(readAt = readAt).copy(recentFills = fills, fundingDayCents = funding)

    @Test
    fun aBigOrderIsAHeadsUpOncePerOrderMeasuredAgainstTheAccount() {
        // The account is $10,000; 5× is $50,000.
        val big = TradingFill(now - 10 * 60_000, "ETH", "Open Long", 6_000_000, 0)
        val small = TradingFill(now - 5 * 60_000, "BTC", "Close Short", 2_000_000, 12_000)
        val n = TradingAlerts.notices(listOf(walletWith(listOf(big, small))), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("Big ETH order · Main", n.title)
        assertEquals("Open long worth 6× the account · 01:50 · the figures are in Money · Navilife can't trade", n.text)
        assertTrue('$' !in n.title + n.text)
        // A second big order replaces it in the same place with its own key.
        val next = TradingAlerts.notices(listOf(walletWith(listOf(big, big.copy(atMs = now - 60_000, coin = "SOL")))), TradingAlertSetting.DEFAULT, now, cal)
        assertEquals(2, next.size)
        assertEquals(1, next.map { it.postId }.toSet().size)
        // His level decides; an old order never alerts; Off is off.
        assertEquals(2, TradingAlerts.notices(listOf(walletWith(listOf(big, small))), TradingAlertSetting(fill = TradingFillAlert.TWO), now, cal).size)
        assertTrue(TradingAlerts.notices(listOf(walletWith(listOf(big))), TradingAlertSetting(fill = TradingFillAlert.TEN), now, cal).isEmpty())
        assertTrue(TradingAlerts.notices(listOf(walletWith(listOf(big.copy(atMs = now - TradingAlerts.FILL_FRESH_MS - 1)))), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        assertTrue(TradingAlerts.notices(listOf(walletWith(listOf(big))), TradingAlertSetting(fill = TradingFillAlert.OFF), now, cal).isEmpty())
        assertEquals("2.5×", TradingAlerts.times(2_500_000, 1_000_000))
        // The server's text round-trips, biggest kept.
        val many = (1..15).map { i -> TradingFill(now - i * 60_000L, "C$i", "Open Short", i * 100_000L, -i.toLong()) }
        val back = TradingTracker.decodeFills(TradingTracker.encodeFills(many))
        assertEquals(TradingTracker.MAX_FILLS, back.size)
        assertEquals((6..15).map { "C$it" }.toSet(), back.map { it.coin }.toSet())
    }

    @Test
    fun highFundingIsAHeadsUpOnceADay() {
        // 1% of $10,000 is $100 paid in a day.
        assertTrue(TradingAlerts.notices(listOf(walletWith(funding = -9_000)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        assertTrue(TradingAlerts.notices(listOf(walletWith(funding = 50_000)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        val n = TradingAlerts.notices(listOf(walletWith(funding = -12_000)), TradingAlertSetting.DEFAULT, now, cal).single()
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("Funding costs high · Main", n.title)
        assertEquals("Paid 1.2% of the account in funding over the last day · the figures are in Money", n.text)
        assertEquals(n.key, TradingAlerts.notices(listOf(walletWith(funding = -30_000)), TradingAlertSetting.DEFAULT, now + 60_000, cal).single().key)
        assertEquals(1, TradingAlerts.notices(listOf(walletWith(funding = -6_000)), TradingAlertSetting(funding = TradingFundingAlert.HALF), now, cal).size)
        assertTrue(TradingAlerts.notices(listOf(walletWith(funding = -12_000, readAt = now - TradingAlerts.FRESH_MS - 1)), TradingAlertSetting.DEFAULT, now, cal).isEmpty())
        assertEquals("0.6%", TradingAlerts.share(0.006))
        assertEquals("12%", TradingAlerts.share(0.123))
    }

    @Test
    fun theTwoNewChoicesAreSyncedAndShown() {
        val s = TradingAlerts.with(TradingAlerts.with(TradingAlertSetting.DEFAULT, TradingAlerts.FILL, "2")!!, TradingAlerts.FUNDING, "off")!!
        assertEquals(TradingFillAlert.TWO, s.fill)
        assertEquals(TradingFundingAlert.OFF, s.funding)
        assertEquals(s, TradingAlerts.read(TradingAlerts.settingFields(s, now)))
        assertNull(TradingAlerts.with(s, TradingAlerts.FILL, "3"))
        // Slice 3's stored setting (no new fields) keeps the defaults.
        assertEquals(TradingAlertSetting.DEFAULT.fill, TradingAlerts.read(mapOf(TradingAlertFields.LIQ_PCT to 10L.fv())).fill)
        val v = TradingAlerts.view(s)
        assertEquals(listOf("2"), v.fillChips.filter { it.on }.map { it.key })
        assertEquals(listOf("off"), v.fundingChips.filter { it.on }.map { it.key })
        assertTrue(v.line.contains("one for an order worth 2× the account · none for funding"))
        assertEquals("Heads-up for an order worth 2× the account · on both devices", TradingAlerts.switchedLine(TradingAlerts.FILL, s))
    }
}
