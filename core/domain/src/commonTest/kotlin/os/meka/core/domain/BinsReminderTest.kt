package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bin days completeness: the evening reminder at his time (synced), and tonight's bins on the watch. */
class BinsReminderTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val bFold = Bins(fold.replica, { "bin${n++}" }, { world.clock.nowMs }, cal)
    private val bMac = Bins(mac.replica, { "bin${n++}" }, { world.clock.nowMs }, cal)
    private val none = HolidayCalendar.NONE

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun bin(name: String, weekday: Int, every: Int, anchor: Long, moves: List<BinMove> = emptyList()) =
        BinItem(name, name, weekday, every, anchor, 0, moves)

    @Test
    fun theReminderIsAt19UntilHePicksAnotherTimeAndBothDevicesFollow() {
        assertEquals(BinRules.NOTICE_MIN, bFold.remindMinute())
        assertEquals("Reminder the evening before at 19:00 · Today says it from 16:00", bFold.view(none).remindLine)
        assertTrue(bFold.setRemind(20 * 60))
        assertFalse(bFold.setRemind(20 * 60), "nothing changes")
        assertFalse(bFold.setRemind(23 * 60), "past 22:00")
        assertFalse(bFold.setRemind(15 * 60), "before Today's line at 16:00")
        assertFalse(bFold.setRemind(20 * 60 + 10), "not a quarter hour")
        sync()
        assertEquals(20 * 60, bMac.remindMinute())
        val v = bMac.view(none)
        assertEquals(20 * 60, v.remindMinute)
        assertEquals(BinRules.REMIND_CHOICES, v.remindChoices)
        assertEquals("Reminder the evening before at 20:00 · Today says it from 16:00", v.remindLine)
        assertEquals("Reminder at 20:00 · on both devices", BinRules.remindSaid(20 * 60))
        // A minute off the chips (still valid) is shown among them.
        assertTrue(bMac.setRemind(19 * 60 + 30))
        assertTrue((19 * 60 + 30) in bMac.view(none).remindChoices)
        assertEquals(bMac.view(none).remindChoices.sorted(), bMac.view(none).remindChoices)
    }

    @Test
    fun theHeadsUpComesAtHisTimeAndAStoredNonsenseMinuteFallsBackTo19() {
        val caddy = bin("Food caddy", 2, 1, d(10, 13))
        val now = cal.toEpochMs(mon, 10 * 60)
        val at20 = BinRules.notices(listOf(caddy), now, cal, none, 20 * 60).first { it.key == "bins:${d(10, 13)}" }
        assertEquals(cal.toEpochMs(mon, 20 * 60), at20.atMs)
        assertEquals("Food caddy out tonight", at20.title)
        val fallback = BinRules.notices(listOf(caddy), now, cal, none, 3 * 60).first { it.key == "bins:${d(10, 13)}" }
        assertEquals(cal.toEpochMs(mon, BinRules.NOTICE_MIN), fallback.atMs)
        assertEquals(BinRules.NOTICE_MIN, BinRules.readRemind(mapOf(BinRules.REMIND_FIELD to os.meka.core.sync.FieldValue.Int64(3L))))
    }

    @Test
    fun theWatchShowsTonightsBinsFrom16AndLightsThemAtHisTime() {
        val black = bin("Black bin", 2, 2, d(10, 13))
        val caddy = bin("Food caddy", 2, 1, d(10, 13))
        val bins = listOf(black, caddy)
        assertNull(BinRules.watch(bins, mon, 15 * 60 + 59, none), "before 16:00")
        val early = BinRules.watch(bins, mon, 16 * 60, none, 20 * 60)!!
        assertEquals("OUT TONIGHT", early.label)
        assertEquals("Black bin and food caddy", early.line)
        assertFalse(early.lit)
        assertNull(early.note)
        assertEquals("Bins out tonight · Black bin and food caddy", early.short)
        assertEquals("Black bin and food caddy go out tonight.", early.spoken)
        assertTrue(BinRules.watch(bins, mon, 20 * 60, none, 20 * 60)!!.lit)
        // No collection tomorrow: nothing on the watch.
        assertNull(BinRules.watch(bins, mon + 1, 21 * 60, none))
        // A bank holiday Monday moves Tuesday's collection to Wednesday: Tuesday evening shows it with the note.
        val bankHoliday = HolidayCalendar(mapOf(mon to "Bank holiday"))
        val shifted = BinRules.watch(listOf(caddy), mon + 1, 19 * 60, bankHoliday)!!
        assertEquals("Food caddy", shifted.line)
        assertEquals(BinRules.SHIFT_NOTE, shifted.note)
        assertNull(BinRules.watch(listOf(caddy), mon, 19 * 60, bankHoliday), "not out on Monday evening that week")
    }

    @Test
    fun aDroppedCollectionSaysNothingGoesOut() {
        val caddy = bin("Food caddy", 2, 1, d(10, 13), listOf(BinMove("m", "Food caddy", d(10, 13), null)))
        val w = BinRules.watch(listOf(caddy), mon, 19 * 60, none)!!
        assertEquals("BINS", w.label)
        assertFalse(w.lit)
        assertEquals("No food caddy collection", w.line)
        assertEquals("Nothing goes out tonight", w.note)
    }
}
