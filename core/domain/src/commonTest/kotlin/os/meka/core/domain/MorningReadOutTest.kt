package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningReadOutTest {
    private val today = 20_400L

    private fun hold(
        lastDay: Long? = today - 1, minute: Int = 7 * 60 + 15, start: Int = 7 * 60, ready: Boolean = true, seen: Boolean = false,
        quiet: Boolean = false, atWork: Boolean = false, onCall: Boolean = false, silent: Boolean = false, private: Boolean = false,
    ) = MorningReadOutRules.hold(lastDay, today, minute, start, ready, seen, quiet, atWork, onCall, silent, private)

    @Test
    fun theFirstMorningOpenReadsItAndTheSecondDoesNot() {
        assertNull(hold())
        assertNull(hold(lastDay = null))
        // Once read today (the first open, or the wake alarm's), the next open is quiet.
        assertEquals(MorningReadOutRules.Hold.ALREADY_TODAY, hold(lastDay = today))
        assertFalse(MorningReadOutRules.reads(today, today, 8 * 60, 7 * 60, true, false, false, false, false, false, false))
    }

    @Test
    fun onlyInTheMorningOnceTheBriefHasItsDay() {
        assertEquals(MorningReadOutRules.Hold.NOT_MORNING, hold(minute = 6 * 60 + 59))
        assertEquals(MorningReadOutRules.Hold.NOT_MORNING, hold(minute = 12 * 60))
        assertNull(hold(minute = 11 * 60 + 59))
        assertNull(hold(minute = 6 * 60 + 30, start = 6 * 60))
        assertEquals(MorningReadOutRules.Hold.NOT_READY, hold(ready = false))
        assertEquals(MorningReadOutRules.Hold.SEEN, hold(seen = true))
    }

    @Test
    fun itKeepsQuietWhereSpeakingWouldBeWrong() {
        assertEquals(MorningReadOutRules.Hold.QUIET_HOURS, hold(quiet = true))
        assertEquals(MorningReadOutRules.Hold.AT_WORK, hold(atWork = true))
        assertEquals(MorningReadOutRules.Hold.ON_CALL, hold(onCall = true))
        assertEquals(MorningReadOutRules.Hold.SILENT, hold(silent = true))
        // On silent with headphones (or the car) it still reads, privately.
        assertNull(hold(silent = true, private = true))
    }

    @Test
    fun theLineSaysWhatItIsDoing() {
        assertEquals("Reading your morning brief", MorningReadOutRules.line(preparing = false))
        assertTrue(MorningReadOutRules.line(preparing = true).startsWith("Getting"))
    }
}
