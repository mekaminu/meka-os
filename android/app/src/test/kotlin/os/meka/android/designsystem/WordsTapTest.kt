package os.meka.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.screens.ShotApplication

/**
 * Meka 2026-11-08 22:09, Today on the folded phone: the first letter of lines was cut off ("Orochi" read "Jrochi", "Next
 * week" read "\ext week") because a 16 dp rounded clip sat right on the words of a tappable line. [wordsTap] keeps the
 * tap's rounded highlight but bleeds it past the words, so a card's words draw exactly as untapped words do: the first
 * glyph sits whole inside the card's padding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WordsTapTest {
    @get:Rule
    val compose = createComposeRule()

    private val style = TextStyle(fontSize = 14.sp, lineHeight = 16.sp, color = Color.Black)

    /** A card (white, 16 dp padding) holding two lines that start and end on a glyph's edge. */
    @Composable
    private fun Card(tag: String, line: @Composable (String) -> Unit) {
        Box(Modifier.testTag(tag).width(240.dp).background(Color.White).padding(16.dp)) {
            Column {
                line("Nvidia Orochi")
                line("Next week")
            }
        }
    }

    private fun pixels(tag: String) = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    @Test
    fun wordsTapDrawsTheWordsWholeWhereUntappedWordsSit() {
        compose.setContent {
            Column {
                Card("plain") { Text(it, style = style) }
                Card("tap") { Text(it, style = style, modifier = Modifier.wordsTap { }) }
                // The old way, kept here so the test shows it can see the bug.
                Card("clipped") { Text(it, style = style, modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable { }) }
            }
        }
        val plain = pixels("plain")
        val tap = pixels("tap")
        val clipped = pixels("clipped")
        assertEquals(plain.width, tap.width)
        assertEquals(plain.height, tap.height)
        var differ = 0
        var oldDiffer = 0
        var ink = 0
        // Ink left of the padding would mean the words moved; none must be lost or moved inside it.
        val padPx = (plain.width * 16f / 240f).toInt()
        var inkLeftOfPadding = 0
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            val p = plain[x, y]
            if (p.red < 0.5f) { ink++; if (x < padPx - 1) inkLeftOfPadding++ }
            if (p != tap[x, y]) differ++
            if (p != clipped[x, y]) oldDiffer++
        }
        assertTrue(ink > 0, "the card drew its words")
        assertEquals(0, inkLeftOfPadding, "the first glyph starts inside the card's padding")
        assertEquals(0, differ, "wordsTap draws the words exactly as untapped words: no letter clipped or moved")
        assertTrue(oldDiffer > 0, "a rounded clip right on the words cuts the first letter (the bug this guards)")
    }

    @Test
    fun theBleedReachesPastTheCornerSoTheCurveMissesTheWords() {
        // On a one-line row the corner's reach is at most the radius; the bleed covers what a short line coerces it to.
        assertTrue(TextTap.BLEED >= MekaSpace.xs)
        assertTrue(TextTap.RADIUS <= MekaRadius.s)
    }
}
