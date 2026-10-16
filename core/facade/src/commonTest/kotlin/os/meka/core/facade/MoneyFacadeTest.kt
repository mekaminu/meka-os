package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.LifeTile
import os.meka.core.domain.MoneyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money manager, slice 1, through the facade: locked until opened, a statement imported on one app shows on the other. */
class MoneyFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val statement = "Date,Description,Amount\n" +
        "10/09/2026,TESCO STORES 2041,-60.00\n" +
        "02/10/2026,TESCO STORES 2041,-40.00\n" +
        "03/10/2026,COSTA COFFEE,-3.20\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

    @Test
    fun lockedUntilOpenedThenSyncsToTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.moneyView.value.locked)
        assertTrue(fold.lifeLines.value.needsSetUp(LifeTile.MONEY))
        // Nothing goes in while it's locked.
        assertTrue(fold.importMoneyStatement("Current account", statement).refused)
        fold.openMoney()
        assertFalse(fold.moneyView.value.locked)
        assertEquals(MoneyRules.EMPTY, fold.moneyView.value.empty)
        val r = fold.importMoneyStatement("Current account", statement)
        assertEquals("Added 4 transactions to Current account · 10 Sep 2026 – 5 Oct 2026", r.line)
        val m = fold.moneyView.value.month!!
        assertEquals("October so far", m.label)
        assertEquals("Spent £43.20 · in £2,500", m.spentLine)
        assertEquals(listOf("Groceries", "Coffee"), m.categories.map { it.label })
        // The tile says which account and when, never an amount.
        assertEquals("Current account · updated Fri 16 Oct", fold.lifeLines.value.of(LifeTile.MONEY)?.text)
        // A month back, and forward again.
        fold.moneyStep(-1)
        assertEquals("September 2026", fold.moneyView.value.month!!.label)
        assertFalse(fold.moneyView.value.earlier)
        fold.moneyStep(1)
        assertEquals("October so far", fold.moneyView.value.month!!.label)
        // Costa as Eating out, from the Fold; the Mac sees it once it's opened there.
        val costa = fold.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        val saved = fold.setMoneyCategory(costa.id, "eating_out")
        assertEquals("Costa Coffee is Eating out from now on", saved.line)
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.moneyView.value.locked)
        assertEquals("Current account · updated Fri 16 Oct", mac.lifeLines.value.of(LifeTile.MONEY)?.text)
        mac.openMoney()
        assertEquals(listOf("Groceries", "Eating out"), mac.moneyView.value.month!!.categories.map { it.label })
        // Undo on the Fold puts it back.
        assertTrue(fold.undoMoneyCategory(saved.undo!!))
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Groceries", "Coffee"), mac.moneyView.value.month!!.categories.map { it.label })
        // Locking drops the figures; Delete all clears both devices.
        mac.closeMoney()
        assertTrue(mac.moneyView.value.month == null && mac.moneyView.value.locked)
        assertEquals(MoneyRules.DELETED_ALL, fold.deleteAllMoney().line)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals(MoneyRules.EMPTY, mac.moneyView.value.empty)
        assertTrue(mac.lifeLines.value.needsSetUp(LifeTile.MONEY))
    }
    @Test
    fun tipsHiddenOnTheFoldStayHiddenOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Ten coffees in the last ten days (£40) and three monthly gym payments.
        val csv = "Date,Description,Amount\n" +
            (7..16).joinToString("") { "$it/10/2026,COSTA COFFEE,-4.00\n" } +
            "01/08/2026,PUREGYM,-35.00\n01/09/2026,PUREGYM,-35.00\n01/10/2026,PUREGYM,-35.00\n"
        assertTrue(fold.hideMoneyTips("habit").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val v = fold.moneyView.value
        assertEquals(listOf("Coffee 10× · £40 in the last 30 days"), v.tips.filter { it.kind == "habit" }.map { it.title })
        assertEquals("1 regular payment · about £35 a month · £420 a year", v.regular?.head)
        val hid = fold.hideMoneyTips("habit")
        assertEquals("Tips on habits hidden · on both devices", hid.line)
        assertTrue(fold.moneyView.value.tips.none { it.kind == "habit" })
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.tips.none { it.kind == "habit" })
        assertEquals("Hidden: tips on habits", mac.moneyView.value.hiddenTips)
        // Show them again on the Mac; the Fold follows.
        mac.showMoneyTips()
        mac.syncNow(); fold.syncNow()
        assertEquals(1, fold.moneyView.value.tips.count { it.kind == "habit" })
        // Undo of a hide works through the same Undo as a category change.
        val again = fold.hideMoneyTips("habit")
        assertTrue(fold.undoMoneyCategory(again.undo!!))
        assertEquals(1, fold.moneyView.value.tips.count { it.kind == "habit" })
    }
    @Test
    fun anAlertPutAwayOnTheFoldIsAwayOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // The same £23.40 at the same shop twice on Tue 13 Oct.
        val csv = "Date,Description,Amount\n13/10/2026,AMAZON MARKETPLACE,-23.40\n13/10/2026,AMAZON MARKETPLACE,-23.40\n"
        assertTrue(fold.moneyAlertFine("w1").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val a = fold.moneyView.value.alerts.single()
        assertEquals("Possibly charged twice · Amazon Marketplace", a.title)
        assertEquals("£23.40 twice on Tue 13 Oct · Current account", a.line)
        val fine = fold.moneyAlertFine(a.id)
        assertEquals("Put away · on both devices", fine.line)
        assertTrue(fold.moneyView.value.alerts.isEmpty())
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.alerts.isEmpty())
        // Undo brings it back.
        assertTrue(fold.undoMoneyCategory(fine.undo!!))
        assertEquals(1, fold.moneyView.value.alerts.size)
        // Delete all money data clears it on both.
        fold.deleteAllMoney()
        assertTrue(fold.moneyView.value.alerts.isEmpty())
    }

    @Test
    fun aRefundBackInTheStatementClosesOnBothDevicesWithUndo() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Waiting since Fri 9 Oct for £40 from Amazon; the statement has it back on Thu 15 Oct.
        now -= 7 * 86_400_000L
        val wid = fold.addWaiting("Refund · Amazon order · £40", "Amazon", null)
        now += 7 * 86_400_000L
        val csv = "Date,Description,Amount\n01/10/2026,AMAZON.CO.UK,-40.00\n15/10/2026,AMAZON.CO.UK,40.00\n16/10/2026,COSTA COFFEE,-3.20\n"
        assertTrue(fold.moneyRefundClose(wid).refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val r = fold.moneyView.value.refunds!!.rows.single()
        assertEquals(os.meka.core.domain.MoneyRefunds.BACK, r.state)
        assertEquals("+£40 from Amazon.co.uk on Thu 15 Oct · Current account", r.line)
        val closed = fold.moneyRefundClose(wid)
        assertEquals("Closed Refund · Amazon order · £40 · it's back in your statement", closed.line)
        assertTrue(fold.moneyView.value.refunds == null)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.refunds == null)
        // Undo puts the row back on Waiting for, here and on the Mac.
        assertTrue(fold.undoMoneyCategory(closed.undo!!))
        assertEquals(1, fold.moneyView.value.refunds!!.rows.size)
        fold.syncNow(); mac.syncNow()
        assertEquals(1, mac.moneyView.value.refunds!!.rows.size)
        // A stale token does nothing.
        assertFalse(fold.undoMoneyCategory(closed.undo!!))
        // Copy the chase marks it chased (it isn't late here, but he can still copy one from a late row).
        assertEquals("Chase copied · paste it to the shop · next look Fri 23 Oct", fold.moneyRefundChased(wid).line)
    }

    @Test
    fun theForecastsWhatIfsStayOnThisDeviceAndGoWhenMoneyLocks() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Jul–Sep: £2,500 in, £300 eating out and £150 groceries out each month.
        val csv = "Date,Description,Amount\n" + listOf("07", "08", "09").joinToString("") { m ->
            "01/$m/2026,SALARY OAKFIELD LTD,2500.00\n10/$m/2026,NANDOS,-300.00\n12/$m/2026,TESCO STORES 2041,-150.00\n28/$m/2026,TESCO STORES 2041,-0.50\n"
        }
        assertTrue(fold.moneyForecastExtra("£200").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val f = fold.moneyView.value.forecast!!
        assertEquals("About £2,050 a month left over", f.left)
        assertEquals("Halve Eating out", f.cuts.first().label)
        fold.moneyForecastCut("eating_out")
        assertEquals("What if: £200 a month more coming in", fold.moneyForecastExtra("£200").line)
        assertTrue(fold.moneyForecastExtra("lots").refused)
        val g = fold.moneyView.value.forecast!!
        assertTrue(g.cuts.first().on)
        assertEquals("put by at your pace · +£2,100 with your what-ifs", g.rows[0].line)
        // Never synced: the Mac sees the plain forecast.
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("put by at your pace", mac.moneyView.value.forecast!!.rows[0].line)
        // Locking drops them.
        fold.closeMoney(); fold.openMoney()
        assertEquals("put by at your pace", fold.moneyView.value.forecast!!.rows[0].line)
    }
}
