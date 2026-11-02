package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskUndo
import os.meka.core.domain.ObiJobRules
import kotlin.test.assertIs
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ada plans with you, part 3 slice 3: an errand asked of Obi on one app shows, and stops, on the other. */
class ObiErrandsFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London
    private val server = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun anErrandAskedOnTheFoldShowsAndStopsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.obiJobRows().isEmpty())
        assertEquals(ObiJobRules.BLANK, fold.askObiErrand("  ").line)
        val asked = fold.askObiErrand("find someone to service the boiler")
        val id = assertNotNull(asked.id)
        fold.syncNow(); mac.syncNow()
        val row = mac.obiJobRows().single()
        assertEquals("Find someone to service the boiler", row.title)
        assertEquals("Starting · Obi is planning it", row.status)
        assertTrue(mac.stopObiErrand(id))
        assertFalse(mac.stopObiErrand(id))
        mac.syncNow(); fold.syncNow()
        assertEquals("Stopped", fold.obiJobRows().single().status)
    }

    @Test
    fun theOrbsCardStartsAnErrandAndUndoStopsIt() = runTest {
        // Part 3 slice 4: "Obi, find someone to service the boiler" said to the orb is a Start card; Start asks it,
        // Obi is working on lists it, and Undo stops it.
        val fold = core("android")
        val offer = fold.livePropose(mapOf("kind" to "start_errand", "title" to "find someone to service the boiler"))
        val card = assertNotNull(offer.card)
        assertIs<AskProposal.StartErrand>(card.proposal)
        assertEquals("Ask Obi to work on: Find someone to service the boiler", card.line)
        val done = fold.doAsk(card)
        assertEquals("Obi is on it · Find someone to service the boiler · see Obi is working on", done.line)
        val v = fold.workingOnView()
        assertEquals("Find someone to service the boiler", v.jobs.single().title)
        assertEquals(1, v.openJobs)
        assertTrue(fold.undoAsk(assertIs<AskUndo.StopErrand>(done.undo)))
        assertEquals("Stopped", fold.workingOnView().jobs.single().status)
        assertEquals(0, fold.workingOnView().openJobs)
    }
}
