package os.meka.android.goals

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaLightColors
import os.meka.core.domain.BedsideTickerRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walk-through 2026-11-08, A18 (light contrast): Fasting's rings for days to come, its markers ahead, its knob and the
 * stage dots not reached yet, and the bedside's news tiles, each measured in both themes against where they sit.
 */
class FastingInkTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val themes = listOf(true to MekaDarkColors, false to MekaLightColors)

    @Test
    fun marksAreAtLeastThreeToOneOnThePageAndTheCard() {
        themes.forEach { (dark, c) ->
            val ink = FastingInk.of(c, dark)
            for (bg in listOf(c.background, c.surface)) {
                // Dark's 0.7 dim of a not-yet row is the approved quiet look; Light, where A18 found them, keeps them whole.
                val ahead = ink.stageAhead.copy(alpha = if (dark) 1f else ink.notYetAlpha).compositeOver(bg)
                assertTrue(contrast(ink.markAhead, bg) >= 3.0, "dark=$dark: a marker ahead is ${contrast(ink.markAhead, bg)}:1")
                assertTrue(contrast(ahead, bg) >= 3.0, "dark=$dark: a not-yet stage dot is ${contrast(ahead, bg)}:1")
                assertTrue(contrast(ink.stageHere, bg) >= 3.0, "dark=$dark: the stage he's in is ${contrast(ink.stageHere, bg)}:1")
            }
        }
    }

    @Test
    fun tracksAreSeenAsShapes() {
        // The floor the token's own gate holds (design/generate.mjs): 1.5:1 in Light, 1.25:1 in Dark.
        themes.forEach { (dark, c) ->
            val floor = if (dark) 1.25 else 1.5
            for (bg in listOf(c.background, c.surface)) {
                val r = contrast(FastingInk.of(c, dark).track, bg)
                assertTrue(r >= floor, "dark=$dark: the track is $r:1 (needs $floor)")
            }
        }
        // Light's track was surfaceRaised, 1.1:1 on the page.
        assertTrue(contrast(MekaLightColors.surfaceRaised, MekaLightColors.background) < 1.5)
    }

    @Test
    fun theKnobHasAnEdgeInLight() {
        val light = FastingInk.of(MekaLightColors, dark = false)
        val edge = light.knobEdge!!
        assertTrue(contrast(light.knob, MekaLightColors.background) < 3.0) // why it needs one
        assertTrue(contrast(edge, MekaLightColors.background) >= 3.0)
        assertNull(FastingInk.of(MekaDarkColors, dark = true).knobEdge) // Dark keeps its glowing knob as it was
    }

    @Test
    fun notYetStagesDimOnlyInDark() {
        assertEquals(1f, FastingInk.of(MekaLightColors, dark = false).notYetAlpha)
        assertTrue(FastingInk.of(MekaDarkColors, dark = true).notYetAlpha < 1f)
    }

    @Test
    fun theBedsideNewsMetaReadsInLight() {
        // The tile's meta (tertiary on the white tile) once the strip's opacity is applied over the page.
        val c = MekaLightColors
        val tile = c.surface.copy(alpha = BedsideTickerRules.alpha(dark = false)).compositeOver(c.background)
        val meta = c.textTertiary.copy(alpha = BedsideTickerRules.alpha(dark = false)).compositeOver(tile)
        assertTrue(contrast(meta, tile) >= 3.0, "the bedside meta is ${contrast(meta, tile)}:1")
        val was = c.textTertiary.copy(alpha = BedsideTickerRules.ALPHA).compositeOver(c.surface.copy(alpha = BedsideTickerRules.ALPHA).compositeOver(c.background))
        assertTrue(contrast(was, tile) < 3.0)
    }
}
