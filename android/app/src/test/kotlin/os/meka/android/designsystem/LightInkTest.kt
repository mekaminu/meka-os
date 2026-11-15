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
    fun workModesOffDaysAndChipsHaveAnEdgeInLightAndTheirWordsRead() {
        // Work mode's off day circles and commute chips are surfaceRaised: alone they fade into Light's page.
        assertTrue(contrast(light.surfaceRaised, light.background) < 1.5)
        assertTrue(contrast(LightInk.pillEdge(light, dark = false)!!, light.surfaceRaised) >= 1.3)
        // Their letters and labels stay the secondary ink, readable on the chip.
        assertTrue(contrast(light.textSecondary, light.surfaceRaised) >= 4.5, "a chip's word is ${contrast(light.textSecondary, light.surfaceRaised)}:1")
    }

    @Test
    fun routinesTilesAndAPausedRoutineReadInLight() {
        // Routines' day tile is the surface on the raised card, the card the raised surface on the page: both ~1.1:1.
        assertTrue(contrast(light.surface, light.surfaceRaised) < 1.5)
        assertTrue(contrast(light.surfaceRaised, light.background) < 1.5)
        val edge = LightInk.pillEdge(light, dark = false)!!
        listOf(light.surface, light.surfaceRaised, light.background).forEach { assertTrue(contrast(edge, it) >= 1.3) }
        // A paused routine's name, day and time (and a page tile's label) were tertiary: under 4.5:1 on the card.
        assertTrue(contrast(light.textTertiary, light.surfaceRaised) < 4.5)
        listOf(light.surface, light.surfaceRaised).forEach { bg ->
            val r = contrast(LightInk.small(light, dark = false), bg)
            assertTrue(r >= 4.5, "a paused routine's words are $r:1")
        }
    }

    @Test
    fun voiceNotesCardsHaveAnEdgeAndTheirQuietLinesReadInLight() {
        // A note's card is the surface on the page (the Mac's is the page colour on the sheet's surface): 1.0:1 alone.
        assertTrue(contrast(light.surface, light.background) < 1.1)
        val edge = LightInk.pillEdge(light, dark = false)!!
        listOf(light.surface, light.background).forEach { assertTrue(contrast(edge, it) >= 1.5) }
        // Its cards line, "Your words" and the keep line were tertiary; Keep and Delete were already the secondary ink.
        pageAndCard.forEach { bg ->
            val r = contrast(LightInk.small(light, dark = false), bg)
            assertTrue(r >= 4.5, "a note's quiet line is $r:1")
            assertTrue(contrast(light.textSecondary, bg) >= 4.5, "Keep and Delete are ${contrast(light.textSecondary, bg)}:1")
        }
    }

    @Test
    fun asksTilesCardsAndSwitchHaveAnEdgeAndTheirQuietWordsReadInLight() {
        // Ask's Life tiles, Daily's card, Settings' boxes, the Life · Daily · Settings track and a Life page's number
        // tiles are the surface on the page (1.0:1); the page arrows' circles are surfaceRaised on the page (1.1:1).
        assertTrue(contrast(light.surface, light.background) < 1.1)
        assertTrue(contrast(light.surfaceRaised, light.background) < 1.5)
        val edge = LightInk.pillEdge(light, dark = false)!!
        listOf(light.surface, light.surfaceRaised, light.background).forEach { assertTrue(contrast(edge, it) >= 1.3) }
        // The page line, To set up and its peek, a box's peek and a page tile's label were tertiary (under 4.5:1).
        assertTrue(contrast(light.textTertiary, light.background) < 4.5)
        pageAndCard.forEach { bg ->
            val r = contrast(LightInk.small(light, dark = false), bg)
            assertTrue(r >= 4.5, "Ask's quiet line is $r:1")
        }
        // A tile's live line was already the secondary ink.
        pageAndCard.forEach { assertTrue(contrast(light.textSecondary, it) >= 4.5) }
    }

    @Test
    fun moneysOverUsualGoldAlreadyReadsInLight() {
        // The reviewer's gold "£151 over usual" is the accent: measured, it is already over 4.5:1 on page and card.
        pageAndCard.forEach { bg -> assertTrue(contrast(light.accent, bg) >= 4.5, "the accent is ${contrast(light.accent, bg)}:1") }
    }
}
