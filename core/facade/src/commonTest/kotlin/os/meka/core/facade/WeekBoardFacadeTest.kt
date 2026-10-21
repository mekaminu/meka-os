package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 2: the Board view on both apps — drag a card to another day or within one, with Undo, synced. */
class WeekBoardFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aCardDraggedAboveAnotherAndToTomorrowSyncsToTheMacAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        now += 60_000L
        val gate = fold.addTask("Fix the gate")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        assertEquals(listOf(mot, gate), fold.weekBoard.value.day(today)!!.cards.map { it.id })

        assertEquals("Moved “Fix the gate” up", fold.moveOnBoard(gate, today, 0))
        assertEquals(listOf(gate, mot), fold.weekBoard.value.day(today)!!.cards.map { it.id })

        assertEquals("Moved “Book MOT” to Tomorrow", fold.moveOnBoard(mot, today + 1, 0))
        assertEquals(listOf(mot), fold.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        assertTrue(fold.today.value.yourDay.none { it.id == mot } && fold.today.value.upNext?.id != mot)

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(gate), mac.weekBoard.value.day(today)!!.cards.map { it.id })
        assertEquals(listOf(mot), mac.weekBoard.value.day(today + 1)!!.cards.map { it.id })

        assertTrue(fold.undoBoardMove())
        assertEquals(setOf(gate, mot), fold.weekBoard.value.day(today)!!.cards.map { it.id }.toSet())
        assertFalse(fold.undoBoardMove())
    }

    @Test
    fun theKeyboardNudgesAndQuickAddLandOnTheirDay() = runTest {
        val c = core("mac")
        c.tick()
        val today = c.weekBoard.value.todayEpochDay
        val id = c.addOnBoard("Order the cake", today + 2)!!
        assertEquals(listOf(id), c.weekBoard.value.day(today + 2)!!.cards.map { it.id })
        assertEquals("Moved “Order the cake” to Tomorrow", c.nudgeOnBoard(id, -1, 0))
        assertEquals(listOf(id), c.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        c.nudgeOnBoard(id, -1, 0)
        assertNull(c.nudgeOnBoard(id, -1, 0), "never before today")
        assertTrue(c.weekBoard.value.day(today)!!.cards.any { it.id == id })
    }
}
