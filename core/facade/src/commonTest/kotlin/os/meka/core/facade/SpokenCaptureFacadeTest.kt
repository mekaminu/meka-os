package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DirectAddRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "The mic should just add it", slice 2: the capture sheet's 🎙 adds what he said without the ↑ Add tap. */
class SpokenCaptureFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun whatHeSaidGoesStraightInAndUndoTakesItBack() = runTest {
        val c = core()
        val titles = { c.today.value.let { listOfNotNull(it.upNext) + it.yourDay }.map { it.title } }
        val added = assertNotNull(c.spokenCapture("buy milk."))
        assertFalse(added.fill)
        assertEquals("Added “Buy milk”", added.line)
        assertEquals("Buy milk", added.words)
        assertEquals(listOf("Buy milk"), titles())
        assertTrue(c.spokenCaptureUndo(added))
        assertTrue(titles().isEmpty())
        // A day said is its due day.
        assertEquals("Added “Call the garage” · Tomorrow", assertNotNull(c.spokenCapture("call the garage tomorrow")).line)
        // The shopping.
        val shop = assertNotNull(c.spokenCapture("add milk and eggs to the shopping"))
        assertEquals("Added milk and eggs to shopping", shop.line)
        // A timer.
        val timer = assertNotNull(c.spokenCapture("timer 10 min"))
        assertNotNull(timer.alarmId)
        assertEquals(1, c.quickAlarms.value.size)
        assertTrue(c.spokenCaptureUndo(timer))
        assertTrue(c.quickAlarms.value.isEmpty())
        assertNull(c.spokenCapture("  "))
    }

    @Test
    fun askFirstOrPausedPutsTheWordsInTheField() = runTest {
        val c = core()
        c.resetTrust(DirectAddRules.ENTITY_ID)
        val held = assertNotNull(c.spokenCapture("buy milk"))
        assertTrue(held.fill)
        assertEquals("Buy milk", held.words)
        assertTrue(c.today.value.let { listOfNotNull(it.upNext) + it.yourDay }.isEmpty())
        c.resetTrust(DirectAddRules.ENTITY_ID)
        c.setAutonomyPaused(true)
        assertTrue(assertNotNull(c.spokenCapture("buy milk")).fill)
        c.setAutonomyPaused(false)
        // Only day words: nothing to add, so the field.
        assertTrue(assertNotNull(c.spokenCapture("tomorrow")).fill)
    }
}
