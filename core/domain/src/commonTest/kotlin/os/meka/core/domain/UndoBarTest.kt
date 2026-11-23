package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UndoBarTest {
    @Test
    fun theBackdropFadesInAboveTheBarAndIsSolidUnderIt() {
        assertEquals(0f, UndoBarRules.backdropAlpha(0f))
        assertEquals(0.5f, UndoBarRules.backdropAlpha(UndoBarRules.BACKDROP_FADE_DP / 2f))
        assertEquals(1f, UndoBarRules.backdropAlpha(UndoBarRules.BACKDROP_FADE_DP.toFloat()))
        assertEquals(1f, UndoBarRules.backdropAlpha(200f))
        assertEquals(0f, UndoBarRules.backdropAlpha(-4f))
    }

    @Test
    fun theFadeIsAtLeastAMetaLineTall() {
        // A line of the card under the bar (itemMeta, about 18 dp with its gap) dissolves within the fade.
        assertTrue(UndoBarRules.BACKDROP_FADE_DP >= 18)
    }
}
