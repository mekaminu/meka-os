package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alarms, slice 2: quick alarms and timers typed into capture. */
class QuickAlarmsTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026
    private val fri = thu + 1

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun at(d: Long, minute: Int) { world.clock.nowMs = ms(d, minute) }
    private fun alarms(dev: Device) = Alarms(dev.replica, { world.clock.nowMs }, cal)
    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun parse(text: String) = QuickAlarmRules.parse(text, world.clock.nowMs, cal)

    @Test
    fun alarmsRingAtTheNextSuchTime() {
        at(thu, 14 * 60 + 37)
        // Still ahead today, or tomorrow once it's gone.
        assertEquals(ms(thu, 18 * 60 + 30), parse("alarm 18:30")!!.atMs)
        assertEquals(ms(fri, 6 * 60 + 30), parse("alarm 6:30")!!.atMs)
        assertEquals(ms(fri, 6 * 60 + 30), parse("Alarm at 06.30")!!.atMs)
        assertEquals(ms(thu, 19 * 60), parse("set an alarm for 7pm")!!.atMs)
        assertEquals(ms(fri, 7 * 60), parse("wake me up at 7am")!!.atMs)
        assertEquals(ms(fri, 0), parse("alarm 12am")!!.atMs)
        assertEquals(ms(thu, 18 * 60 + 45), parse("alarm for 6.45 p.m.")!!.atMs)
        assertEquals(ms(fri, 7 * 60), parse("alarm 7")!!.atMs)
        val a = parse("alarm 6:30 to call Mum")!!
        assertEquals(AlarmKind.ALARM, a.kind)
        assertEquals("call Mum", a.label)
        assertNull(a.lengthSec)
        assertEquals("Gym", parse("alarm 7pm Gym")!!.label)
    }

    @Test
    fun timersRunForWhatWasTyped() {
        at(thu, 14 * 60 + 32)
        fun len(t: String) = parse(t)?.lengthSec
        assertEquals(20 * 60, len("timer 20 min"))
        assertEquals(20 * 60, len("Timer for 20 minutes"))
        assertEquals(25 * 60, len("timer 25"))
        assertEquals(90 * 60, len("timer 1 h 30"))
        assertEquals(90 * 60, len("timer 1h30"))
        assertEquals(90 * 60, len("set a timer for 1.5 h"))
        assertEquals(150, len("timer 2m30"))
        assertEquals(90, len("timer 90 s"))
        assertEquals(3600 + 15 * 60, len("start a timer for 1 hour and 15 minutes"))
        assertEquals(20 * 60, len("20 min timer"))
        val t = parse("timer 20 min pasta")!!
        assertEquals(AlarmKind.TIMER, t.kind)
        assertEquals(world.clock.nowMs + 20 * min, t.atMs)
        assertEquals("pasta", t.label)
        assertEquals("Pasta water", parse("10 min timer for Pasta water")!!.label)
    }

    @Test
    fun everythingElseIsATask() {
        at(thu, 9 * 60)
        listOf(
            "Buy alarm clock batteries", "alarm", "timer", "alarm 2 things to fix", "alarm 25:00", "alarm 6:75",
            "alarm 18:30pm", "timer 25 h", "timer for the oven", "Check the timer on the boiler", "",
            "alarm 6:30\nsecond line", "set timer", "20 mince pies",
        ).forEach { assertNull(parse(it), it) }
    }

    @Test
    fun confirmationLines() {
        at(thu, 14 * 60 + 32)
        assertEquals("Alarm set for 06:30 tomorrow", QuickAlarmRules.setLine(parse("alarm 6:30")!!, world.clock.nowMs, cal))
        assertEquals("Alarm set for 18:30 · Gym", QuickAlarmRules.setLine(parse("alarm 18:30 Gym")!!, world.clock.nowMs, cal))
        assertEquals("Timer set · 20 min · ends 14:52 · pasta", QuickAlarmRules.setLine(parse("timer 20 min pasta")!!, world.clock.nowMs, cal))
        assertEquals("Timer set · 1 h 30 · ends 16:02", QuickAlarmRules.setLine(parse("timer 1h30")!!, world.clock.nowMs, cal))
        assertEquals("45 s", QuickAlarmRules.length(45))
        assertEquals("1 min 30 s", QuickAlarmRules.length(90))
    }

    @Test
    fun aTimerRingsOnBothDevicesAndCancelsEverywhere() {
        at(thu, 14 * 60 + 32)
        val a = alarms(fold)
        val id = a.setQuick(parse("timer 20 min pasta")!!)!!
        sync()
        // Today lists it on both devices; the Mac's next alarm is the timer, rung by its length.
        val items = alarms(mac).quickItems()
        assertEquals(1, items.size)
        assertEquals("pasta", items[0].title)
        assertEquals("20 min · ends 14:52 · 20 min left", items[0].detail)
        assertEquals("Cancel the 20 min timer", items[0].cancelLabel)
        val ring = alarms(mac).next()!!
        assertEquals(id, ring.id)
        assertEquals(world.clock.nowMs + 20 * min, ring.ringAtMs)
        assertEquals("Timer", ring.title)
        assertEquals("20 min", ring.timeLabel)
        assertEquals("pasta", ring.line)
        assertFalse(ring.opensBrief)

        world.clock.nowMs += 6 * min + 30_000
        assertEquals("20 min · ends 14:52 · 14 min left", alarms(fold).quickItems()[0].detail)
        // Cancelled on the Mac: gone from both.
        assertTrue(alarms(mac).cancel(id))
        assertFalse(alarms(mac).cancel(id))
        sync()
        assertTrue(alarms(fold).quickItems().isEmpty())
        assertNull(alarms(fold).next())
    }

    @Test
    fun aQuickAlarmRingsSnoozesAndIsDismissed() {
        at(thu, 22 * 60)
        val a = alarms(fold)
        val id = a.setQuick(parse("alarm 6:30")!!)!!
        // Typed again on the Mac: the same alarm, not a second one.
        assertEquals(id, alarms(mac).setQuick(parse("alarm 06:30")!!))
        sync()
        assertEquals(1, alarms(fold).quickItems().size)
        assertEquals("06:30 · Tomorrow", alarms(fold).quickItems()[0].detail)
        assertEquals("Alarm", alarms(fold).quickItems()[0].title)
        val ring = alarms(fold).next()!!
        assertEquals("Alarm", ring.title)
        assertEquals("06:30", ring.timeLabel)
        assertFalse(ring.opensBrief)

        at(fri, 6 * 60 + 30)
        assertEquals("Ringing", alarms(fold).quickItems()[0].detail)
        assertNotNull(alarms(fold).snooze(id))
        assertEquals("Snoozed until 06:39", alarms(fold).quickItems()[0].detail)
        sync()
        at(fri, 6 * 60 + 39)
        assertEquals("Ringing", alarms(mac).quickItems()[0].detail)
        // Dismissed on the Mac: it ends on both.
        assertTrue(alarms(mac).dismiss(id))
        sync()
        assertTrue(alarms(fold).quickItems().isEmpty())
    }

    @Test
    fun theWakeAlarmIsNotAQuickOneAndStillOpensTheBrief() {
        at(thu, 21 * 60)
        val a = alarms(fold)
        assertTrue(a.setWake(7 * 60, null))
        assertTrue(a.quickItems().isEmpty())
        assertTrue(a.next()!!.opensBrief)
        // A timer that ends first rings first.
        a.setQuick(parse("timer 10 min")!!)
        assertEquals(AlarmKind.TIMER, a.next()!!.kind)
    }

    @Test
    fun aMomentAlreadyGoneIsRefused() {
        at(thu, 9 * 60)
        assertNull(alarms(fold).setQuick(QuickAlarmRequest(AlarmKind.TIMER, world.clock.nowMs, 0, null)))
    }
}
