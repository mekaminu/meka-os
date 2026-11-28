package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: the weekly reflection's time is a row opening a sheet, not an open chip row. */
class ReflectionChoicesTest {
    private val R = ReflectionChoiceRules

    @Test fun theRowSaysTheTimeAndThatItOpensChoices() {
        val row = R.row(18 * 60)
        assertEquals("Comes on Sundays", row.title)
        assertEquals("18:00", row.value)
        assertTrue(row.spoken.endsWith("Opens its choices"))
    }

    @Test fun theSheetLightsTheSetTimeAndEachSaysWhereLaterTonightPutsIt() {
        val s = R.sheet(19 * 60)
        assertEquals(listOf("16:00", "17:00", "18:00", "19:00", "20:00", "21:00"), s.choices.map { it.label })
        assertEquals(listOf("19:00"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Heads-up at 16:00 · Later tonight puts it to 18:00", s.choices[0].line)
        assertEquals("Heads-up at 21:00 · Later tonight puts it to 22:00", s.choices[5].line, "never after 22:00")
        assertTrue(s.choices[3].spoken.endsWith(", chosen"))
    }

    @Test fun aTimeSetElsewhereIsAmongTheChoicesLit() {
        val s = R.sheet(18 * 60 + 30)
        assertEquals("18:30", s.choices.single { it.lit }.label)
        assertEquals("Heads-up at 22:00 · ${R.NO_LATER}", R.line(22 * 60))
    }

    @Test fun aChoiceSetsItsMinuteButNotTheOneInForce() {
        assertEquals(20 * 60, R.named(18 * 60, "1200"))
        assertNull(R.named(18 * 60, "1080"))
        assertNull(R.named(18 * 60, "1110"), "not offered")
        assertNull(R.named(18 * 60, "soon"))
    }
}
