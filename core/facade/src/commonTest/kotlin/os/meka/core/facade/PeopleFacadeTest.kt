package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** People, slice 2, through the facade: promises and a birthday made on the Fold make People rows on the Mac. */
class PeopleFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun promisesBothWaysAndABirthdayMakeOneRowPerPersonOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.peopleView.value.isEmpty)
        fold.addPromise("Fix Theo's bike", "Theo", 2)
        fold.addWaiting("Football photos", "Jordan", null)
        fold.addOccasion("Theo 15 Oct 2016")
        val theo = fold.peopleView.value.rows.first { it.name == "Theo" }
        assertEquals("You promised 1", theo.owedLine)
        assertEquals("Birthday Thu 15 Oct · in 5 days · turns 10", theo.birthday)
        assertTrue(theo.lit)

        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertEquals(listOf("Theo", "Jordan"), mac.peopleView.value.rows.map { it.name })
        assertEquals("Waiting for 1", mac.peopleView.value.rows[1].owedLine)
        assertEquals("2 people", mac.peopleView.value.summary)

        val id = mac.listsView.value.promised.single().id
        mac.keptPromise(id)
        mac.tick()
        assertEquals(null, mac.peopleView.value.rows.first { it.name == "Theo" }.owedLine)
    }

    @Test
    fun theFoldsListsMakeRowsOnTheFoldOnlyAndNeverSync() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addPromise("Fix Theo's bike", "Theo", 2)
        fold.setListedPeople(listOf("Jordan", "Theo"), listOf("Mum"), listOf("Coach Dave"))
        assertEquals(
            listOf("Coach Dave" to "Watched for requests", "Jordan" to "Family", "Mum" to "Always notify", "Theo" to "Family"),
            fold.peopleView.value.rows.map { it.name to it.listed },
        )
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertEquals(listOf("Theo" to null), mac.peopleView.value.rows.map { it.name to it.listed })
        // Taken off the lists on the Fold: the row goes unless something else made it.
        fold.setListedPeople(emptyList(), emptyList(), emptyList())
        assertEquals(listOf("Theo" to null), fold.peopleView.value.rows.map { it.name to it.listed })
    }
}
