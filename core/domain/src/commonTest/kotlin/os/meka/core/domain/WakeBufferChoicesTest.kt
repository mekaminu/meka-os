package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Shut down's "Time to get ready" chip row is a row opening a sheet. */
class WakeBufferChoicesTest {
    private val work = WakeCommitment(9 * 60, "Work", isWork = true, commuteMin = 80)
    private fun view(buffer: Int = 60, commitment: WakeCommitment? = work, set: Int? = null): WakeView {
        val s = AlarmRules.suggest(commitment, buffer)
        return WakeView.EMPTY.copy(
            suggestion = s, setMinute = set, minute = set ?: s?.minute ?: AlarmRules.DEFAULT_WAKE_MIN,
            bufferMin = buffer, bufferLine = AlarmRules.bufferLine(buffer),
            bufferChoices = (AlarmRules.BUFFER_CHOICES + buffer).distinct().sorted(),
        )
    }

    @Test
    fun theRowSaysTheBufferInUse() {
        assertEquals(NotifyChoiceRow("Time to get ready", "1 h", "Time to get ready, 1 h. Opens the choices"), WakeBufferChoiceRules.row(view()))
        assertEquals("1 h 15", WakeBufferChoiceRules.row(view(75)).value)
        assertEquals("45 min", WakeBufferChoiceRules.row(view(45)).value)
    }

    @Test
    fun theSheetSaysTheTimeEachWouldSuggest() {
        val s = WakeBufferChoiceRules.sheet(view())
        assertEquals("Time to get ready", s.title)
        assertEquals(listOf("30 min", "45 min", "1 h", "1 h 15", "1 h 30", "2 h"), s.choices.map { it.label })
        assertEquals(listOf("1 h"), s.choices.filter { it.lit }.map { it.label })
        // Work at 09:00 less a 1 h 20 commute is 07:40 out of the door; 1 h before that is 06:40.
        assertEquals("Suggests 06:40 · for Work at 09:00", s.choices[2].line)
        assertEquals("Suggests 07:10 · for Work at 09:00", s.choices[0].line)
        assertTrue(s.choices[2].spoken.endsWith(", chosen"))
        assertTrue("alarm you've set" in s.note)
    }

    @Test
    fun aSetAlarmStaysAndTheSheetSaysSo() {
        val s = WakeBufferChoiceRules.sheet(view(set = 6 * 60 + 40))
        assertEquals("Suggests 06:40 · for Work at 09:00", s.choices[2].line)
        assertEquals("Suggests 06:55 · for Work at 09:00 · your alarm stays at 06:40", s.choices[1].line)
    }

    @Test
    fun nothingEarlySaysHowLongBeforeTheFirstThing() {
        val s = WakeBufferChoiceRules.sheet(view(commitment = null))
        assertEquals("45 min before the first thing, on a day that starts early", s.choices[1].line)
    }

    @Test
    fun anOddBufferIsOnOfferAndLit() {
        val s = WakeBufferChoiceRules.sheet(view(50))
        assertTrue("50 min" in s.choices.map { it.label })
        assertEquals(listOf("50 min"), s.choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun aPickNamesItsBuffer() {
        assertEquals(45, WakeBufferChoiceRules.named(view(), "45"))
        assertNull(WakeBufferChoiceRules.named(view(), "60"))
        assertNull(WakeBufferChoiceRules.named(view(), "50"))
        assertNull(WakeBufferChoiceRules.named(view(), "soon"))
    }
}
