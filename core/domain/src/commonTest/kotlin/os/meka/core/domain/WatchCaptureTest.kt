package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchCaptureTest {
    @Test
    fun cleanMakesOneTidyLineAndNothingFromSilence() {
        assertEquals("Buy milk", WatchCaptureRules.clean("  buy   milk. "))
        assertEquals("Call Mum when I leave work", WatchCaptureRules.clean("call Mum\nwhen I leave work"))
        assertEquals("Wait for it...", WatchCaptureRules.clean("wait for it..."))
        assertEquals("Timer 20 min", WatchCaptureRules.clean("timer 20 min"))
        assertNull(WatchCaptureRules.clean(null))
        assertNull(WatchCaptureRules.clean("   "))
        assertNull(WatchCaptureRules.clean(" . "))
    }

    @Test
    fun cleanCutsALongCaptureAtAWord() {
        val long = (1..80).joinToString(" ") { "word" }
        val c = WatchCaptureRules.clean(long)!!
        assertTrue(c.length <= WatchCaptureRules.MAX_CHARS, c)
        assertTrue(c.endsWith("word"), c)
    }

    @Test
    fun aTaskShowsAddedWithItsTitleCutToTheScreen() {
        val c = WatchCaptureRules.captured(CaptureOutcome.TaskAdded("t1"), "Buy milk")!!
        assertEquals("Added “Buy milk”", c.line)
        assertEquals("t1", c.taskId)
        assertNull(c.alarmId)
        val long = WatchCaptureRules.captured(CaptureOutcome.TaskAdded("t2"), "Book the car in for its service and the MOT next month")!!
        assertEquals("Added “Book the car in for its service…”", long.line)
        assertEquals("Added “Book the car in for its service…”. Undo for 5 seconds.", WatchCaptureRules.spoken(long))
    }

    @Test
    fun anAlarmOrTimerShowsItsOwnLineAndNothingCapturedShowsNothing() {
        val c = WatchCaptureRules.captured(CaptureOutcome.AlarmSet("a1", AlarmKind.TIMER, "Timer set · 20 min · ends 14:52"), "Timer 20 min")!!
        assertEquals("Timer set · 20 min · ends 14:52", c.line)
        assertEquals("a1", c.alarmId)
        assertNull(c.taskId)
        assertNull(WatchCaptureRules.captured(CaptureOutcome.Empty, ""))
    }

    @Test
    fun everyFailureSaysWhatToDo() {
        for (f in WatchListenFailure.entries) assertTrue(WatchCaptureRules.failLine(f).contains(" · "), f.name)
        assertTrue(WatchCaptureRules.failLine(WatchListenFailure.NO_RECOGNISER).contains("phone"))
    }
}
