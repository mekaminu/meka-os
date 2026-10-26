package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.ImportAccountChips
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice

/**
 * Money bugs (A5, A6), Meka 2026-10-24 screenshots: (A5) Import a statement's third account chip was squeezed into one
 * letter per line; (A6) a faded strip of the screen behind (Ask's "Add a habit · Plan this week") showed above every
 * pane in Dark. Invented names only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class MoneyBugsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun importChipsWrapAndEachStaysOneLine() {
        val names = listOf("Current account", "Credit card", "Joint savings account", "Holiday pot")
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(280.dp).background(Meka.colors.background)) { ImportAccountChips(names, chosen = "Credit card") {} }
            }
        }
        compose.waitForIdle()
        val heights = names.map { n ->
            val node = compose.onAllNodesWithText(n, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                ?: fail("\"$n\" isn't shown as one chip (every account gets one, not the first four)")
            n to node.boundsInRoot.height
        }
        val one = heights.first().second
        heights.forEach { (n, h) -> if (h > one * 1.2f) fail("\"$n\" is ${h}px tall, the first chip ${one}px: it wrapped or was squeezed") }
        // Four chips can't share one 280 dp line, so they wrapped onto a second row rather than squashing.
        val tops = names.map { n -> compose.onAllNodesWithText(n, useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot.top }
        if (tops.distinct().size < 2) fail("all four chips are on one line at 280 dp: they must wrap, not squash")
    }

    @Test
    fun aPaneInDarkShowsNothingOfTheScreenBehindAboveIt() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val behind = Color(0xFFFF0000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    // The screen behind: bright red where Ask's tiles would be.
                    Box(Modifier.fillMaxSize().background(behind))
                    MekaPane(visible = true) { Text("Money") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        val px = compose.onNodeWithTag("screen").captureToImage().toPixelMap()
        val density = context.resources.displayMetrics.density
        // The sliver above the sheet (8 dp) and the wedges under its rounded corners.
        val ys = listOf(1, (4 * density).toInt(), (10 * density).toInt())
        val xs = listOf(2, px.width / 2, px.width - 3)
        for (y in ys) for (x in xs) {
            val c = px[x, y]
            if (c.red > c.green + 0.15f && c.red > c.blue + 0.15f)
                fail("the screen behind shows through above the pane at ($x, $y): $c")
        }
    }
}
