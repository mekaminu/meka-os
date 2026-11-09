package os.meka.android.today

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaColors
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ShotApplication
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.TimelineRow

/**
 * The Today rebuild, point (i): gold only on things to tap. A running event's "Now · …" line, the now line's words and
 * a clash with work are not drawn in the accent; the now line's time stays the gold now mark. Invented titles (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class)
class GoldForTapsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun colourOf(text: String): Color {
        val node = compose.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first().layoutInput.style.color
    }

    private fun row(id: String, kind: TimelineKind, time: String, title: String, detail: String?, running: Boolean, clash: String? = null) =
        TimelineRow(id = id, kind = kind, time = time, title = title, detail = detail, task = null, event = null,
            running = running, startMs = 0L, clash = clash)

    @Test
    fun linesThatOnlyTellYouSomethingAreNotGold() {
        lateinit var colors: MekaColors
        compose.setContent {
            MekaTheme {
                colors = Meka.colors
                Column {
                    TimelineEventRow(row("e-1", TimelineKind.EVENT, "09:30", "Swim lesson", "Leisure centre", running = true,
                        clash = "Clashes with work · who's taking Sam?"), past = false)
                    NowLine(row("now", TimelineKind.NOW, "10:05", "", "1 h free until Work", running = false))
                }
            }
        }
        compose.waitForIdle()
        val live = colourOf("Leisure centre")
        assertNotEquals(colors.accent, live)
        assertEquals(colors.textSecondary, live)
        assertEquals(colors.calendar3, colourOf("Clashes with work"))
        assertEquals(colors.textSecondary, colourOf("1 h free until Work"))
        assertEquals(colors.accent, colourOf("10:05"))
    }
}
