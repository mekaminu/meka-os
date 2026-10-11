package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ErrandRules
import os.meka.core.domain.ErrandView
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Errands by location through the facade (slice 1): typed on the Fold, waiting on the Mac, ticked off on either. */
class ErrandsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun anErrandTypedOnTheFoldWaitsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(ErrandView.EMPTY.summary, fold.errandsView.value.summary)
        assertTrue(fold.addErrand("milk").refused)
        assertEquals("At Tesco: milk and batteries", fold.addErrand("when I'm near Tesco: milk, batteries").line)
        assertEquals("2 errands at Tesco", fold.errandsView.value.summary)

        fold.syncNow(); mac.syncNow()
        val tesco = mac.errandsView.value.places.single()
        assertEquals("milk, batteries", tesco.line)
        assertTrue(mac.errandDone(tesco.rows[0].id, true))
        assertTrue(mac.removeErrand(tesco.rows[1].id))
        mac.syncNow(); fold.syncNow()
        val v = fold.errandsView.value
        assertEquals(ErrandRules.EMPTY_SUMMARY, v.summary)
        assertEquals(listOf("milk"), v.done.map { it.title })
        assertTrue(fold.errandDone(v.done.single().id, false))
        assertEquals("1 errand at Tesco", fold.errandsView.value.summary)
    }
}
