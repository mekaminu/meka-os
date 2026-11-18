package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.LifePageHeader
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.LifePageRules

/**
 * Walk-through 2026-11-15 A20: a Life page title's ⓘ took the tap only on its 24 dp circle's width. It is now a whole
 * 48 × 48 target laid over the title's end and the gap before + Add, so the title keeps its room ("Appointments" stays on one line on
 * the closed Fold, which a plain 48 dp square broke), and a tap near either outer edge of the target, past the circle,
 * still unfolds or folds what the page does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class LifeTitleInfoTargetTest {
    @get:Rule
    val compose = createComposeRule()

    private val info = "Visits you keep up with: the dentist, the optician, the GP."

    private fun show(title: String) {
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            MekaTheme {
                // The closed Fold's page: 344 dp wide less the page's 16 dp each side.
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(horizontal = 16.dp)) {
                    LifePageHeader(title = title, info = info, infoOpen = open, onInfo = { open = !open }, onAdd = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun lines(text: String): Int {
        val n = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount ?: throw AssertionError("no text layout for $text")
    }

    @Test
    fun theInfoIsAWholeSquareAndTheTitleKeepsItsLine() {
        show("Appointments")
        val target = hasContentDescription(LifePageRules.INFO_LABEL)
        ScreenShots.assertTallTarget(compose, "Appointments", target, square = true)
        ScreenShots.assertHeaderWhole(compose, "Appointments")
        if (lines("Appointments") != 1) fail("Appointments breaks across ${lines("Appointments")} lines on the closed Fold")
        // The target reaches past the drawn circle on both sides: a tap 2 dp inside its right edge unfolds the words and
        // one 2 dp inside its left edge folds them (read from the ⓘ's own state, so the fold's animation doesn't count).
        val d = compose.density.density
        fun state() = compose.onNode(target).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        // Tap from the root at the square's own position: it starts 12 dp left of the drawn circle and ends 12 dp right of it.
        fun tapAt(x: Float) {
            val at = compose.onNode(target).fetchSemanticsNode().positionInRoot
            compose.onRoot().performTouchInput { click(Offset(at.x + x * d, at.y + 24f * d)) }
            compose.waitForIdle()
        }
        val size = compose.onNode(target).fetchSemanticsNode().size
        if (size.width / d < 47.5f) fail("the ⓘ's square is ${size.width / d} dp wide")
        // 6 dp inside the right edge: the square's last 4 dp may lie under + Add's own target when the title fills its room.
        tapAt(42f)
        if (state() != "Shown") {
            val i = compose.onNode(target).fetchSemanticsNode()
            val add = compose.onNode(hasContentDescription("Add")).fetchSemanticsNode()
            val t = compose.onNode(hasText("Appointments"), useUnmergedTree = true).fetchSemanticsNode()
            fail("a tap 6 dp inside the target's right edge didn't unfold the words (state ${state()}); " +
                "title x ${t.positionInRoot.x / d}..${(t.positionInRoot.x + t.size.width) / d} dp, " +
                "ⓘ x ${i.positionInRoot.x / d}..${(i.positionInRoot.x + i.size.width) / d} dp, " +
                "Add x ${add.positionInRoot.x / d}..${(add.positionInRoot.x + add.size.width) / d} dp")
        }
        compose.onNode(hasText(LifePageRules.shown(info), substring = true)).assertExists()
        tapAt(2f)
        if (state() != "Hidden") fail("a tap 2 dp inside the target's left edge didn't fold the words (state ${state()})")
    }

    @Test
    fun aLongTitleStillLeavesAddWhole() {
        show("What Navilife knows")
        ScreenShots.assertTallTarget(compose, "Facts", hasContentDescription(LifePageRules.INFO_LABEL), square = true)
        ScreenShots.assertHeaderWhole(compose, "Facts")
    }
}
