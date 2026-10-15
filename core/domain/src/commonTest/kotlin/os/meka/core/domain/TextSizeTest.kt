package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextSizeTest {
    @Test fun nothingStoredIsDefault() {
        assertEquals(TextSizeChoice.DEFAULT, TextSizeRules.choice(null))
        assertEquals(TextSizeChoice.DEFAULT, TextSizeRules.choice(""))
        assertEquals(TextSizeChoice.DEFAULT, TextSizeRules.choice("huge"))
        assertEquals(1.0, TextSizeRules.multiplier(null))
    }

    @Test fun theFourMultipliers() {
        assertEquals(listOf(0.9, 1.0, 1.15, 1.3), TextSizeRules.all.map { it.multiplier })
        assertEquals(listOf("Smaller", "Default", "Larger", "Largest"), TextSizeRules.all.map { it.label })
    }

    @Test fun biggerAndSmallerStepAndStopAtTheEnds() {
        assertEquals(TextSizeChoice.LARGER, TextSizeRules.bigger(null))
        assertEquals(TextSizeChoice.LARGEST, TextSizeRules.bigger("larger"))
        assertEquals(TextSizeChoice.LARGEST, TextSizeRules.bigger("largest"))
        assertEquals(TextSizeChoice.SMALLER, TextSizeRules.smaller("default"))
        assertEquals(TextSizeChoice.SMALLER, TextSizeRules.smaller("smaller"))
        assertEquals(TextSizeChoice.LARGER, TextSizeRules.smaller("largest"))
        assertFalse(TextSizeRules.canGrow("largest"))
        assertTrue(TextSizeRules.canGrow("default"))
        assertFalse(TextSizeRules.canShrink("smaller"))
        assertTrue(TextSizeRules.canShrink(null))
    }

    @Test fun sizesRoundToTheHalfPoint() {
        assertEquals(13.0, TextSizeRules.size(13.0, "default"))
        assertEquals(11.5, TextSizeRules.size(13.0, "smaller")) // 11.7
        assertEquals(15.0, TextSizeRules.size(13.0, "larger")) // 14.95
        assertEquals(17.0, TextSizeRules.size(13.0, "largest")) // 16.9
        assertEquals(10.0, TextSizeRules.size(11.0, "smaller")) // 9.9
    }

    @Test fun theLineSaysHowToChangeOrReset() {
        assertTrue(TextSizeRules.line(null).contains("⌘+"))
        assertTrue(TextSizeRules.line("larger").contains("⌘0"))
        assertTrue(TextSizeRules.line("smaller").contains("smaller"))
    }
}
