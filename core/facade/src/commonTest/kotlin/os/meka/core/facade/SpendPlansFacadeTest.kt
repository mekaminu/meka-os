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
import kotlin.time.Duration.Companion.minutes

/** Spending plans through the facade (slice 1): made on the Fold, ticked on the Mac; no Can I afford it? while Money is locked. */
class SpendPlansFacadeTest {
    private var now = 1_794_477_600_000L // Thu 12 Nov 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private companion object { val SLOW_RUNNER = 5.minutes }

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

    @Test
    fun christmasOnCountdownsCarriesItsPlanOnBothAppsButNotOnTodayOrTheWidget() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(null, fold.countdownsView.value.hero?.planLine)
        val id = fold.addSpendPlan("Christmas gifts 20 Dec", "GBP").id!!
        assertFalse(fold.addSpendThing(id, "Football boots", "", "45", 0).refused)
        assertFalse(fold.addSpendThing(id, "Lego set", "", "140", 0).refused)
        val hero = fold.countdownsView.value.hero!!
        assertEquals("Christmas", hero.name)
        assertEquals("Christmas gifts plan · £185 to go", hero.planLine)
        // The plan's money stays off Today's tile, the widget and what Obi reads.
        fold.showCountdownOnToday(hero.key)
        val widget = fold.countdownWidget()!!
        assertFalse(widget.toString().contains("£"))
        fold.syncNow(); mac.syncNow()
        val row = mac.spendPlansView.value.plan(id)!!.rows.first { it.name == "Lego set" }
        assertTrue(mac.setSpendThingDone(row.id, true))
        assertEquals("Christmas gifts plan · £45 to go", mac.countdownsView.value.hero!!.planLine)
        mac.syncNow(); fold.syncNow()
        assertEquals("Christmas gifts plan · £45 to go", fold.countdownsView.value.hero!!.planLine)
        assertTrue(fold.removeSpendPlan(id))
        assertEquals(null, fold.countdownsView.value.hero!!.planLine)
    }

    @Test
    fun countInMyForecastTakesWhatsStillToBuyOffMoneysForecast() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Aug–Oct: £2,500 in, £300 eating out and £150 groceries out each month: about £2,050 left over.
        val csv = "Date,Description,Amount\n" + listOf("08", "09", "10").joinToString("") { m ->
            "01/$m/2026,SALARY OAKFIELD LTD,2500.00\n10/$m/2026,NANDOS,-300.00\n12/$m/2026,TESCO STORES 2041,-150.00\n28/$m/2026,TESCO STORES 2041,-0.50\n"
        }
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        assertEquals("+£12,300", fold.moneyView.value.forecast!!.rows[0].amount)
        val id = fold.addSpendPlan("Christmas gifts 20 Dec", "GBP").id!!
        val dec = 2026 * 12 + 11
        val boots = fold.addSpendThing(id, "Football boots", "", "450", dec).id!!
        // Off until he turns it on: the forecast is as it was.
        assertEquals("+£12,300", fold.moneyView.value.forecast!!.rows[0].amount)
        assertEquals(null, fold.moneyView.value.forecast!!.plansLine)
        assertTrue(fold.setSpendPlanCounted(id, true))
        val f = fold.moneyView.value.forecast!!
        assertEquals("+£11,850", f.rows[0].amount)
        assertEquals("all that's left over, at your pace · after £450 planned", f.rows[0].line)
        assertEquals("Counting Christmas gifts · £450 still to buy", f.plansLine)
        // Can I afford it? still reads the plain average month.
        assertEquals("Fits · £1,600 left this month", fold.spendPlansView.value.plan(id)!!.afford.first().verdict)
        // The switch syncs to the Mac.
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.spendPlansView.value.plan(id)!!.counted)
        // Bought: nothing left to take off.
        assertTrue(fold.setSpendThingDone(boots, true))
        assertEquals("+£12,300", fold.moneyView.value.forecast!!.rows[0].amount)
        assertEquals("Counting Christmas gifts · nothing left to buy", fold.moneyView.value.forecast!!.plansLine)
        assertTrue(fold.setSpendPlanCounted(id, false))
        assertEquals(null, fold.moneyView.value.forecast!!.plansLine)
    }
}
