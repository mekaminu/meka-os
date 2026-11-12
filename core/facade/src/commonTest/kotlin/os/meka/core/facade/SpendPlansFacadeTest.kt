package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.SpendPlanRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spending plans through the facade (slice 1): made on the Fold, ticked on the Mac; no Can I afford it? while Money is locked. */
class SpendPlansFacadeTest {
    private var now = 1_794_477_600_000L // Thu 12 Nov 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aPlanMadeOnTheFoldAddsUpOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(SpendPlanRules.ROW_EMPTY, fold.spendPlansView.value.rowLine)
        val made = fold.addSpendPlan("Christmas gifts 20 Dec", "GBP")
        assertFalse(made.refused)
        val id = made.id!!
        assertFalse(fold.addSpendThing(id, "Football boots", "For Sam", "45", 0).refused)
        assertFalse(fold.addSpendThing(id, "Lego set", "", "60", 0).refused)
        assertTrue(fold.addSpendThing(id, "Coat", "", "", 0).refused)
        val p = fold.spendPlansView.value.plan(id)!!
        assertEquals("£105", p.total)
        assertEquals("needed by 20 Dec", p.by)
        // Money is locked: no Can I afford it?, the reason instead.
        assertTrue(p.afford.isEmpty())
        assertEquals(SpendPlanRules.AFFORD_NOT_READY, p.affordNote)
        assertEquals("Christmas gifts · £105 to go · 20 Dec", fold.spendPlansView.value.rowLine)

        fold.syncNow(); mac.syncNow()
        val row = mac.spendPlansView.value.plan(id)!!.rows.first()
        assertEquals("Football boots", row.name)
        assertTrue(mac.setSpendThingDone(row.id, true))
        mac.syncNow(); fold.syncNow()
        assertEquals("£45 bought · £60 to go", fold.spendPlansView.value.plan(id)!!.spentLine)
        assertTrue(fold.removeSpendPlan(id))
        assertTrue(fold.spendPlansView.value.plans.isEmpty())
    }
}
