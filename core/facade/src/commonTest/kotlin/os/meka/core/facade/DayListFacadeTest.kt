package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BoardArea
import os.meka.core.domain.BoardAreaRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Day list, slice 1 (Meka's check on the Fold): type a few tasks for Saturday one after another with lengths, a
 * time and an area, paste a list, fit times from 9:00 and undo it, and the Mac sees the same list.
 */
class DayListFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun typePasteFitAndUndoThenTheMacSeesIt() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val day = fold.weekBoard.value.todayEpochDay + 2

        val one = fold.addToDayList("Complete feedbacks 30m #work", day)!!
        assertEquals("Added “Complete feedbacks” · 30m", one.line)
        now += 1_000L
        val two = fold.addToDayList("Dentist 14:00 1h", day)!!
        now += 1_000L
        val list = fold.addToDayList("- Email Sam 15m\n- Pay the nursery #money\n\n", day)!!
        assertEquals("Added 2 tasks · 15m", list.line)
        assertNull(fold.addToDayList("  #work 30m ", day))

        var v = fold.dayList(fold.weekBoard.value, day, timesOn = true)!!
        // With times on, the rows read as the day would run: the dentist holds 14:00, the rest from 9:00.
        assertEquals(listOf("Complete feedbacks", "Email Sam", "Pay the nursery", "Dentist"), v.rows.map { it.title })
        assertEquals(listOf("09:00", "09:30", "09:45", "14:00"), v.rows.map { it.time })
        assertEquals(listOf(false, false, false, true), v.rows.map { it.timeIsSet })
        assertEquals(listOf("30m", "15m", null, "1h"), v.rows.map { it.length })
        assertEquals(BoardArea.WORK, v.rows[0].area)
        assertEquals(BoardArea.MONEY, v.rows[2].area)
        assertEquals("ends about 15:00", v.endsLine)

        val fit = fold.fitDayList(day, 9 * 60)!!
        v = fold.dayList(fold.weekBoard.value, day, timesOn = true)!!
        assertEquals(listOf("09:00", "09:30", "09:45", "14:00"), v.rows.map { it.time })
        assertTrue(v.rows.all { it.timeIsSet })
        assertEquals("Fitted 3 tasks from 09:00 · ends about 15:00", fit.line)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.dayList(mac.weekBoard.value, day, timesOn = false)!!
        assertEquals(v.rows.map { it.id }, onMac.rows.map { it.id })
        assertEquals("09:45", onMac.rows[2].time)

        assertTrue(fold.undoFitDayList(fit))
        v = fold.dayList(fold.weekBoard.value, day, timesOn = false)!!
        assertEquals(listOf("14:00", null, null, null), v.rows.map { it.time })
        assertEquals(BoardAreaRules.raw(BoardArea.WORK), fold.weekBoard.value.day(day)!!.cards.single { it.id == one.ids.single() }.task.area)
        assertEquals(1, two.ids.size)
    }

    @Test
    fun dragToReorderMovesOnTheBoardUndoesAndTheMacSeesTheOrder() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val day = fold.weekBoard.value.todayEpochDay + 2
        fold.addToDayList("Complete feedbacks 30m\nEmail Sam 15m\nPay the nursery 30m\nDentist 14:00 1h", day)!!
        fun titles(timesOn: Boolean) = fold.dayList(fold.weekBoard.value, day, timesOn)!!.rows.map { it.title }
        assertEquals(listOf("Complete feedbacks", "Email Sam", "Pay the nursery", "Dentist"), titles(true))
        val pay = fold.dayList(fold.weekBoard.value, day, true)!!.rows[2].id

        assertEquals("Moved “Pay the nursery” up", fold.reorderDayList(day, pay, 0, timesOn = true))
        val v = fold.dayList(fold.weekBoard.value, day, true)!!
        assertEquals(listOf("Pay the nursery", "Complete feedbacks", "Email Sam", "Dentist"), v.rows.map { it.title })
        assertEquals(listOf("09:00", "09:30", "10:00", "14:00"), v.rows.map { it.time })

        // The dentist keeps its time: with Times on it doesn't drag.
        assertNull(fold.reorderDayList(day, v.rows[3].id, 0, timesOn = true))

        fold.syncNow(); mac.syncNow()
        assertEquals(v.rows.map { it.id }, mac.dayList(mac.weekBoard.value, day, true)!!.rows.map { it.id })

        assertTrue(fold.undoBoardMove())
        assertEquals(listOf("Complete feedbacks", "Email Sam", "Pay the nursery", "Dentist"), titles(true))
    }
}
