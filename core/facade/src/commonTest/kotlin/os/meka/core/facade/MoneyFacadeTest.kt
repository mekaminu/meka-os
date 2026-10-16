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
}
