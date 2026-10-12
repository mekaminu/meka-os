package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bedside clock as the charging screen saver (bedside clock on the Fold 8, slice 3). */
class BedsideDreamTest {
    private val pkg = "os.meka.android"
    private val bedside = BedsideView(
        time = "23:41", dateLabel = "Monday 12 October", alarmLine = "Alarm 06:30 · in 6 h 49", alarmSet = true, dim = true,
        section = BedsideSection.EVENING, heading = "Tomorrow · Tue 13 Oct", lines = listOf("09:00 · Stand-up", "Gym"),
        opens = BedsideOpens.SHUTDOWN,
    )

    @Test
    fun theDreamKeepsTheClockAndDropsTheTaps() {
        val v = BedsideDreamRules.view(bedside, 0L)
        assertEquals("23:41", v.time)
        assertEquals("Monday 12 October", v.dateLabel)
        assertEquals("Alarm 06:30 · in 6 h 49", v.alarmLine)
        assertTrue(v.alarmSet)
        assertTrue(v.dim)
        assertEquals("Tomorrow · Tue 13 Oct", v.heading)
        assertEquals(listOf("09:00 · Stand-up", "Gym"), v.lines)
        assertEquals(0 to 0, v.shiftXDp to v.shiftYDp)
    }

    @Test
    fun theClockDriftsEveryTwoMinutesAndStaysNearTheCentre() {
        val min = 60_000L
        val start = 1_791_552_300_000L - 1_791_552_300_000L % (2 * min)
        assertEquals(BedsideDreamRules.shift(start), BedsideDreamRules.shift(start + min))
        assertNotEquals(BedsideDreamRules.shift(start), BedsideDreamRules.shift(start + 2 * min))
        // A full loop comes back to where it started, and no place strays more than MAX_SHIFT_DP.
        val loop = BedsideDreamRules.SHIFTS.size * 2 * min
        assertEquals(BedsideDreamRules.shift(start), BedsideDreamRules.shift(start + loop))
        assertEquals(BedsideDreamRules.SHIFTS.size, BedsideDreamRules.SHIFTS.toSet().size)
        BedsideDreamRules.SHIFTS.forEach { (x, y) -> assertTrue(abs(x) <= BedsideDreamRules.MAX_SHIFT_DP && abs(y) <= BedsideDreamRules.MAX_SHIFT_DP) }
        assertEquals(0 to 0, BedsideDreamRules.SHIFTS.first())
        // Before 1970 (a clock gone wrong) still picks a place rather than failing.
        assertTrue(BedsideDreamRules.shift(-5 * min) in BedsideDreamRules.SHIFTS)
    }

    @Test
    fun androidsSettingsSayWhetherMekaIsTheScreenSaver() {
        val ours = "$pkg/os.meka.android.fold.BedsideDreamService"
        assertEquals(true, BedsideDreamRules.isMeka("1", ours, pkg))
        assertEquals(true, BedsideDreamRules.isMeka(null, "com.google.android.deskclock/com.android.deskclock.Screensaver, $ours", pkg))
        assertEquals(false, BedsideDreamRules.isMeka("0", ours, pkg))
        assertEquals(false, BedsideDreamRules.isMeka("1", "com.google.android.deskclock/com.android.deskclock.Screensaver", pkg))
        assertEquals(false, BedsideDreamRules.isMeka("1", "", pkg))
        assertEquals(false, BedsideDreamRules.isMeka("1", null, pkg))
        // A package that only starts with ours isn't ours.
        assertEquals(false, BedsideDreamRules.isMeka("1", "os.meka.android.debug/x.Dream", pkg))
        assertNull(BedsideDreamRules.isMeka(null, null, pkg))
    }

    @Test
    fun setupSaysWhetherTheScreenSaverIsOnWithAWayThere() {
        val on = BedsideDreamRules.setupStep(true)
        assertEquals(SetupState.DONE, on.state)
        assertEquals(null, on.fix)
        val off = BedsideDreamRules.setupStep(false)
        assertEquals(SetupState.TODO, off.state)
        assertEquals(SetupFix.SCREEN_SAVER, off.fix)
        assertEquals("Take me there", off.fixLabel)
        assertTrue(off.line.endsWith("Settings → Display → Screen saver → MEKA"))
        val unknown = BedsideDreamRules.setupStep(null)
        assertEquals(SetupState.UNKNOWN, unknown.state)
        assertFalse(unknown.toDo)
        assertEquals(SetupFix.SCREEN_SAVER, unknown.fix)
    }
}
