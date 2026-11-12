package os.meka.backend

import os.meka.backend.integrations.OpenPoundRates
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.SpendPlanRules
import os.meka.core.domain.SpendPlans
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spending plans, slice 3: today's rate for a plan in another currency is looked up and reaches the Fold. Invented figures. */
class SpendPlanRatesTest {
    private var nowMs = 1_794_477_600_000L // Thu 12 Nov 2026
    private val day = java.time.LocalDate.of(2026, 11, 12).toEpochDay()
    private val ops = InMemoryServerOpStore()
    private val woken = mutableListOf<String>()

    private val body = """{"result":"success","provider":"https://www.exchangerate-api.com","time_last_update_unix":1794441600,
        "base_code":"GBP","rates":{"GBP":1,"NGN":2050.5,"GHS":19.25,"USD":1.27,"XXX":-3}}"""

    private var gets = 0
    private var down = false
    private val rates = OpenPoundRates({ url ->
        assertEquals(OpenPoundRates.URL, url)
        gets++
        if (down) 503 to "" else 200 to body
    }, { nowMs })
    private val sync = SpendPlanRates(ops, BankSync.scanning(ops), { "hh" }, rates, { nowMs }, onWritten = { woken += it })

    private val store = InMemoryReplicaStore()
    private var n = 0
    private val fold = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold-${n++}" }
    private val plans = SpendPlans(fold, { "p${n++}" }, { nowMs })

    private fun push() = store.pendingPush(100_000).also { p -> p.forEach { ops.append(it) }; store.markPushed(p.map { it.opId }) }
    private fun pull() { fold.applyRemoteBatch(ops.after("hh", 0, 100_000).map { it.op }) }

    @Test
    fun theServicesAnswerIsReadAsUnitsToThePound() {
        val r = OpenPoundRates.parse(body)!!
        assertEquals(day, r.day)
        assertEquals(2_050_500L, r.milliPerPound["NGN"])
        assertEquals(19_250L, r.milliPerPound["GHS"])
        assertNull(r.milliPerPound["XXX"])
        assertNull(OpenPoundRates.parse(body.replace("success", "error")))
        assertNull(OpenPoundRates.parse(body.replace("\"base_code\":\"GBP\"", "\"base_code\":\"USD\"")))
        assertNull(OpenPoundRates.parse("not json"))
    }

    @Test
    fun aNairaPlanGetsTodaysRateOnTheFoldAndAPoundPlanNothing() {
        // No plan in another currency: nothing is fetched at all.
        plans.add("Christmas gifts 20 Dec", "GBP")
        push()
        assertFalse(sync.tick())
        assertEquals(0, gets)

        val id = plans.add("Money home", "NGN").id!!
        plans.setEach(id, "50,000")
        plans.addThing(id, "Mum", "", "", 0)
        push()
        assertTrue(sync.tick())
        assertEquals(listOf("hh"), woken)
        pull()
        val p = plans.plans().first { it.id == id }
        assertEquals(2_050_500L, p.dayRateMilli)
        assertEquals(day, p.dayRateDay)
        assertTrue(p.onTodaysRate)
        assertEquals("£24", SpendPlanRules.view(plans.plans(), plans.things(), null, day).plan(id)!!.total)
        // The same rate isn't written twice, and within six hours it isn't fetched again.
        assertFalse(sync.tick())
        assertEquals(1, gets)
        // After, a failed fetch keeps the last rate and writes nothing new.
        nowMs += OpenPoundRates.EVERY_MS
        down = true
        assertFalse(sync.tick())
        assertEquals(2, gets)
        // His own rate is never touched by the server.
        plans.setRate(id, "2,000")
        push()
        down = false
        nowMs += OpenPoundRates.RETRY_MS
        sync.tick()
        pull()
        assertEquals(2_000_000L, plans.plans().first { it.id == id }.rateMilli)
    }

    @Test
    fun aRemovedPlanIsLeftAlone() {
        val id = plans.add("Money home", "NGN").id!!
        plans.remove(id)
        push()
        assertFalse(sync.tick())
        assertEquals(0, gets)
    }
}
