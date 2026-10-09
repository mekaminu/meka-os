package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayRingHeaderTest {
    @Test fun openFoldAndMacGetTheWideDialWithItsCentre() {
        assertEquals(150, DayRingHeader.sizeDp(compact = false))
        assertTrue(DayRingHeader.showsCentre(DayRingHeader.sizeDp(compact = false)))
        assertEquals(10f, DayRingHeader.strokeDp(150))
    }

    @Test fun closedFoldGetsACompactDialWithoutCentreText() {
        val size = DayRingHeader.sizeDp(compact = true)
        assertEquals(96, size)
        assertFalse(DayRingHeader.showsCentre(size))
        assertEquals(6f, DayRingHeader.strokeDp(size))
    }

    @Test fun bigDialsKeepTheirCentreAndStroke() {
        // The bedside clock and the old hero sizes are unchanged.
        assertTrue(DayRingHeader.showsCentre(196))
        assertEquals(10f, DayRingHeader.strokeDp(196))
    }

    @Test fun trackRadiusFollowsTheStroke() {
        assertEquals(91f, DayRingHeader.trackRadiusDp(196)) // 98 - 5 - 2, as the dial has always drawn it
        assertEquals(43f, DayRingHeader.trackRadiusDp(96)) // 48 - 3 - 2
    }
}
