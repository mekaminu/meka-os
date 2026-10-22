package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GreetingFitTest {
    @Test fun aGreetingThatFitsKeepsItsSize() {
        assertEquals(1f, GreetingFitRules.scale(widthAtBase = 200f, available = 230f))
        assertFalse(GreetingFitRules.wraps(200f, 230f))
    }

    @Test fun goodAfternoonOnTheCoverScreenStepsDownToOneLine() {
        // ~220 dp at 34 sp beside the cover's watch face, ~210 dp free: 0.88 (≈ 194 dp) fits.
        assertEquals(0.88f, GreetingFitRules.scale(220f, 210f))
        assertFalse(GreetingFitRules.wraps(220f, 210f))
    }

    @Test fun aLongerGreetingTakesTheSmallestStep() {
        assertEquals(0.76f, GreetingFitRules.scale(270f, 210f))
        assertFalse(GreetingFitRules.wraps(270f, 210f))
    }

    @Test fun tooLongEvenAtTheSmallestStepWrapsThere() {
        assertEquals(0.76f, GreetingFitRules.scale(400f, 210f))
        assertTrue(GreetingFitRules.wraps(400f, 210f))
    }

    @Test fun nothingMeasuredYetKeepsItsSize() {
        assertEquals(1f, GreetingFitRules.scale(0f, 210f))
        assertEquals(1f, GreetingFitRules.scale(220f, 0f))
        assertFalse(GreetingFitRules.wraps(220f, 0f))
    }

    @Test fun theStepsGoDownAndClamp() {
        assertEquals(3, GreetingFitRules.STEPS)
        assertEquals(1f, GreetingFitRules.scaleAt(0))
        assertTrue(GreetingFitRules.scaleAt(1) < GreetingFitRules.scaleAt(0))
        assertEquals(GreetingFitRules.scaleAt(2), GreetingFitRules.scaleAt(9))
        assertEquals(1f, GreetingFitRules.scaleAt(-1))
    }
}
