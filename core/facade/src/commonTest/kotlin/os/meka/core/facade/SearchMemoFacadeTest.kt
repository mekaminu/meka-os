package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Taps feel instant, slice 12, through the facade: Search, School, Gifts, Trips, Documents and the call log are kept
 * while what they're built from is unchanged. A new email keeps them all (a shopping item all but Search); each one's own change
 * rebuilds it at once; a new minute rebuilds the ones that show the time.
 */
class SearchMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(12), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvsm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    private val parts = listOf("Search", "School", "Gifts", "Trips", "Documents", "Call log")
    private fun builds(c: MekaCore) = parts.associateWith { c.viewBuilds(it) }

    @Test
    fun aNewEmailOrShoppingItemKeepsThemAll() = runTest(timeout = 5.minutes) {
        for (i in 0 until 30) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        c.addTask("Ring the garage")
        c.search("garage")
        val first = builds(c)
        parts.forEach { assertTrue(first.getValue(it) >= 1, "$it built") }
        val found = c.searchView.value
        assertTrue(found.total > 0, "the search finds the task")

        email("new", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(first, builds(c), "a new email rebuilds none of them")
        assertTrue(found === c.searchView.value)

        // A shopping item can be found, so Search looks again; the rest are kept.
        c.addShopping("Milk")
        assertTrue(c.viewBuilds("Search") > first.getValue("Search"), "a shopping item rebuilds Search")
        val afterShopping = builds(c)
        parts.filter { it != "Search" }.forEach {
            assertEquals(first.getValue(it), afterShopping.getValue(it), "a shopping item doesn't rebuild $it")
        }

        // Nothing changed at all: every part kept.
        c.tick()
        assertEquals(afterShopping, builds(c), "a refresh with nothing new rebuilds none of them")
    }

    @Test
    fun eachOnesOwnChangeRebuildsItAtOnce() = runTest(timeout = 5.minutes) {
        val c = core()
        c.addTask("Ring the garage")
        c.search("garage")

        var before = builds(c)
        c.addTask("Garage invoice")
        assertTrue(c.viewBuilds("Search") > before.getValue("Search"), "a task rebuilds Search")
        assertTrue(c.searchView.value.total >= 2, "and the new task is found")
        assertEquals(before.getValue("Trips"), c.viewBuilds("Trips"), "a task doesn't rebuild Trips")

        before = builds(c)
        c.search("milk")
        assertTrue(c.viewBuilds("Search") > before.getValue("Search"), "a new query searches again")

        before = builds(c)
        c.addSchool("INSET 2 Nov")
        assertTrue(c.viewBuilds("School") > before.getValue("School"), "a school date rebuilds School")
        assertEquals(before.getValue("Documents"), c.viewBuilds("Documents"), "a school date doesn't rebuild Documents")

        before = builds(c)
        c.addOccasion("Sam's birthday 3 Nov")
        assertTrue(c.viewBuilds("Gifts") > before.getValue("Gifts"), "an occasion rebuilds Gifts")

        before = builds(c)
        c.addTrip("Barcelona 20–24 Nov")
        assertTrue(c.viewBuilds("Trips") > before.getValue("Trips"), "a trip rebuilds Trips")
        assertEquals(before.getValue("Gifts"), c.viewBuilds("Gifts"), "a trip doesn't rebuild Gifts")

        before = builds(c)
        c.addDocument("Passport expires 3 Mar 2029")
        assertTrue(c.viewBuilds("Documents") > before.getValue("Documents"), "a document rebuilds Documents")
        assertTrue(c.viewBuilds("Trips") > before.getValue("Trips"), "and Trips, which shows the documents a trip needs")

        // A new minute rebuilds the ones that show the time.
        before = builds(c)
        now += 60_000
        c.tick()
        listOf("Search", "School", "Call log").forEach {
            assertTrue(c.viewBuilds(it) > before.getValue(it), "the minute rebuilds $it")
        }
        listOf("Gifts", "Trips", "Documents").forEach {
            assertEquals(before.getValue(it), c.viewBuilds(it), "the same day keeps $it")
        }
    }
}
