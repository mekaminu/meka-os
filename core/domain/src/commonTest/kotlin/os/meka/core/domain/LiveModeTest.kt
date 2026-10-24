package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi's Live mode, slice 1: the per-minute meter and the £15 cap. */
class LiveModeTest {
    @Test
    fun minutesArePricedEachWayAndRoundedUp() {
        // A minute heard and a minute spoken: half a cent and 1.8 cents.
        assertEquals(5_000 + 18_000, LiveModeRules.costMicroUsd(60, 60))
        assertEquals(0, LiveModeRules.costMicroUsd(0, -5))
        assertEquals(84, LiveModeRules.costMicroUsd(1, 0)) // 83.3… up to 84
        // A new session is counted at its whole ten-minute window until it reports.
        assertEquals(LiveModeRules.costMicroUsd(600, 600), LiveModeRules.reserveMicroUsd())
        // A report can lower it, never raise it past the window.
        assertEquals(LiveModeRules.reserveMicroUsd(), LiveModeRules.reportedMicroUsd(100_000, 100_000))
        assertEquals(LiveModeRules.costMicroUsd(90, 30), LiveModeRules.reportedMicroUsd(90, 30))
        assertTrue(LiveModeRules.reportedMicroUsd(90, 30) < LiveModeRules.reserveMicroUsd())
    }

    @Test
    fun theCapIsInPoundsWithAHeadsUpAtEightyPercent() {
        // $1 at 0.79 £/$ is 79p; an unreadable rate counts dear.
        assertEquals(79, LiveModeRules.pence(1_000_000, 790_000))
        assertEquals(85, LiveModeRules.pence(1_000_000, 0))
        assertEquals(1, LiveModeRules.pence(1, 790_000))
        assertEquals(0, LiveModeRules.pence(0, 790_000))

        val cap = LiveModeRules.capPence(null)
        assertEquals(1_500, cap)
        assertEquals(2_000, LiveModeRules.capPence(" 20 "))
        assertEquals(1_500, LiveModeRules.capPence("lots"))
        assertEquals(LiveModeRules.Level.OK, LiveModeRules.level(1_199, cap))
        assertEquals(LiveModeRules.Level.HEADS_UP, LiveModeRules.level(1_200, cap))
        assertEquals(LiveModeRules.Level.OVER, LiveModeRules.level(1_500, cap))
        // A cap of nothing means off.
        assertEquals(LiveModeRules.Level.OVER, LiveModeRules.level(0, LiveModeRules.capPence("0")))

        assertEquals("£3.40 of £15 this month", LiveModeRules.line(LiveModeRules.Level.OK, 340, cap))
        assertEquals("£12.05 of £15 this month · nearly used up", LiveModeRules.line(LiveModeRules.Level.HEADS_UP, 1_205, cap))
        assertEquals("Live is off until the 1st · this month's £15 is used up", LiveModeRules.line(LiveModeRules.Level.OVER, 1_500, cap))
    }

    @Test
    fun voiceAndModelNamesAreChecked() {
        assertEquals("Kore", LiveModeRules.voice(" Kore "))
        assertNull(LiveModeRules.voice("Kore\"},{\"x"))
        assertNull(LiveModeRules.voice(""))
        assertEquals("gemini-3.8-live", LiveModeRules.model(null))
        assertEquals("gemini-3.9-live", LiveModeRules.model("models/gemini-3.9-live"))
        assertEquals(LiveModeRules.DEFAULT_MODEL, LiveModeRules.model("gemini-3.8-flash"))
        assertEquals(LiveModeRules.DEFAULT_MODEL, LiveModeRules.model("../live"))
    }
}
