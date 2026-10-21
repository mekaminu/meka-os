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

/**
 * The Week board by hand, slice 1 (Meka's check on the Fold): add three tasks to Thursday without leaving the keyboard,
 * one with an estimate chip, drag the last to the top, tick one off and un-tick it — the order and the ticks synced to
 * the Mac.
 */
class BoardHandsFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun addThreeOnADayDragTheLastToTheTopAndTickOneOff() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val day = fold.weekBoard.value.todayEpochDay + 2
        val a = fold.addOnBoardSized("Book MOT", day, null)!!
        now += 1_000L
        val b = fold.addOnBoardSized("Fix the gate", day, 15)!!
        now += 1_000L
        val c = fold.addOnBoardSized("Order new bins", day, 60)!!
        assertNull(fold.addOnBoardSized("   ", day, 30))

        val board = fold.weekBoard.value.day(day)!!
        assertEquals(listOf(a, b, c), board.openCards.map { it.id })
        assertEquals(listOf(null, "15m", "1h"), board.openCards.map { it.estimate })

        // The drag's landing place: the top of the day.
        assertTrue(fold.moveOnBoard(c, day, 0)!!.startsWith("Moved “Order new bins”"))
        assertEquals(listOf(c, a, b), fold.weekBoard.value.day(day)!!.openCards.map { it.id })

        // Tick and un-tick from the card's own target.
        fold.complete(a)
        assertEquals(listOf(c, b), fold.weekBoard.value.day(day)!!.openCards.map { it.id })
        assertTrue(fold.weekBoard.value.day(day)!!.cards.single { it.id == a }.done)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.weekBoard.value.day(day)!!
        assertEquals(listOf(c, b), onMac.openCards.map { it.id })
        assertEquals("1h", onMac.cards.single { it.id == c }.estimate)

        fold.reopen(a)
        assertFalse(fold.weekBoard.value.day(day)!!.cards.single { it.id == a }.done)
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.weekBoard.value.day(day)!!.openCards.any { it.id == a })
    }
}
