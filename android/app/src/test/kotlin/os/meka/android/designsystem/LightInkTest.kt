package os.meka.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walk-through 2026-11-08, A31 (Light contrast): Money's share figures, its bars' tracks and its unchosen tabs, measured
 * on Light's page and card; Dark keeps the inks it had.
 */
class LightInkTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val light = MekaLightColors
    private val pageAndCard = listOf(light.background, light.surface)

    @Test
    fun smallGreyWordsReadAsSmallTextInLight() {
        pageAndCard.forEach { bg ->
            val r = contrast(LightInk.small(light, dark = false), bg)
            assertTrue(r >= 4.5, "a small grey word is $r:1")
        }
        // Why: the tertiary ink was under 4.5:1 on the page.
        assertTrue(contrast(light.textTertiary, light.background) < 4.5)
        assertEquals(MekaDarkColors.textTertiary, LightInk.small(MekaDarkColors, dark = true))
    }

    @Test
    fun aBarsTrackIsSeenInLight() {
        pageAndCard.forEach { bg -> assertTrue(contrast(LightInk.barTrack(light, dark = false), bg) >= 1.5) }
        assertTrue(contrast(light.surfaceRaised, light.background) < 1.5)
        assertEquals(MekaDarkColors.surfaceRaised, LightInk.barTrack(MekaDarkColors, dark = true))
    }

    @Test
    fun anUnchosenPillHasAnEdgeOnlyInLight() {
        assertTrue(contrast(light.surface, light.background) < 1.1) // the pill alone
        assertTrue(contrast(LightInk.pillEdge(light, dark = false)!!, light.background) >= 1.5)
        assertNull(LightInk.pillEdge(MekaDarkColors, dark = true))
    }

    @Test
    fun moneysOverUsualGoldAlreadyReadsInLight() {
        // The reviewer's gold "£151 over usual" is the accent: measured, it is already over 4.5:1 on page and card.
        pageAndCard.forEach { bg -> assertTrue(contrast(light.accent, bg) >= 4.5, "the accent is ${contrast(light.accent, bg)}:1") }
    }
}
