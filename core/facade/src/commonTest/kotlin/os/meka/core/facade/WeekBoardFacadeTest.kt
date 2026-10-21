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
    fun anEstimateSetOnACardCountsOnItsDaySyncsAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val id = fold.addOnBoard("Book MOT", today + 2)!!
        assertEquals("“Book MOT” · about 30m", fold.setEstimate(id, 30))
        val day = fold.weekBoard.value.day(today + 2)!!
        assertEquals("30m", day.cards.single().estimate)
        assertEquals(30, day.capacity!!.plannedMin)

        fold.syncNow(); mac.syncNow()
        assertEquals("30m", mac.weekBoard.value.day(today + 2)!!.cards.single().estimate)

        assertEquals("“Book MOT” · about 1h", fold.setEstimate(id, 60))
        assertTrue(fold.undoEstimate())
        assertEquals("30m", fold.weekBoard.value.day(today + 2)!!.cards.single().estimate)
        assertFalse(fold.undoEstimate())
        assertEquals("“Book MOT” · no estimate", fold.setEstimate(id, null))
        assertNull(fold.weekBoard.value.day(today + 2)!!.cards.single().estimate)
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

    @Test
    fun aCardParkedInTheWeekLeavesTodaySyncsAndGoesToSomedayAndBack() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        fold.tick()
        val board = fold.weekBoard.value
        val today = board.todayEpochDay
        val monday = board.weeks[0].startEpochDay
        assertEquals(mot, fold.today.value.upNext?.id)

        assertEquals("Moved “Book MOT” to This week", fold.parkOnBoard(mot, monday, 0))
        assertEquals(listOf(mot), fold.weekBoard.value.weeks[0].lane.map { it.id })
        assertTrue(fold.weekBoard.value.day(today)!!.cards.none { it.id == mot })
        assertTrue(fold.today.value.upNext?.id != mot && fold.today.value.yourDay.none { it.id == mot })
        assertNull(fold.parkOnBoard(mot, monday, 0), "already there")

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(mot), mac.weekBoard.value.weeks[0].lane.map { it.id })

        assertEquals("Moved “Book MOT” to Someday", mac.somedayOnBoard(mot))
        assertEquals(listOf(mot), mac.weekBoard.value.someday.map { it.id })
        assertTrue(mac.weekBoard.value.weeks[0].lane.isEmpty())
        assertNull(mac.somedayOnBoard(mot), "already on Someday")

        assertEquals("Moved “Book MOT” to Tomorrow", mac.moveOnBoard(mot, today + 1, 0))
        assertEquals(listOf(mot), mac.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        assertTrue(mac.weekBoard.value.someday.isEmpty())
        assertTrue(mac.undoBoardMove())
        assertEquals(listOf(mot), mac.weekBoard.value.someday.map { it.id })
    }
}
