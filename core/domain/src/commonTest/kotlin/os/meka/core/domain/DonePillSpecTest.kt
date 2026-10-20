package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Done pill on Needs you cards (Meka, 2026-10-20 17:33): drawn small, tapped big, a press and a flash. */
class DonePillSpecTest {
    @Test
    fun theDrawnPillStaysSmallAndTheTouchTargetSitsOutsideIt() {
        assertTrue(DonePillSpec.HEIGHT_DP <= DonePillSpec.MAX_DRAWN_HEIGHT_DP)
        assertTrue(DonePillSpec.MAX_DRAWN_HEIGHT_DP <= 36)
        assertTrue(DonePillSpec.TOUCH_DP >= 48)
        assertTrue(DonePillSpec.TOUCH_DP > DonePillSpec.HEIGHT_DP)
        assertEquals(1, DonePillSpec.BORDER_DP)
    }

    @Test
    fun pressScalesAndFlashesOnlyWhenMotionIsOn() {
        assertEquals(0.96f, DonePillSpec.scale(pressed = true, reduced = false))
        assertEquals(1f, DonePillSpec.scale(pressed = false, reduced = false))
        assertEquals(1f, DonePillSpec.scale(pressed = true, reduced = true))
        assertTrue(DonePillSpec.flashMs(reduced = false) in 80L..200L)
        assertEquals(0L, DonePillSpec.flashMs(reduced = true))
    }
}
