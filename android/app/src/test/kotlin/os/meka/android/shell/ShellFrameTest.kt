package os.meka.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ScreenShots
import os.meka.android.screens.ShotApplication
import os.meka.core.domain.TalkLayoutRules
import os.meka.core.domain.TalkPhase

/**
 * Meka 2026-11-03 13:17: "when I'm using the orb and the phone rotates it goes to a different tab and cuts my
 * discussion". Rotating the cover screen (portrait → landscape → portrait) and folding/unfolding swap the shell between
 * the bottom bar and the rail; the screens inside (Ask and its talk) must move, not be built again, and the bedside
 * clock must not take over mid-talk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class ShellFrameTest {
    @get:Rule
    val compose = createComposeRule()

    /** Stands in for Ask's TalkController: made once, released when Ask leaves the composition. */
    private class Talk { var released = false }

    @Test
    fun theSameTalkLivesThroughRotationAndFolding() {
        var layout by mutableStateOf(ShellLayout.BOTTOM_BAR)
        val made = mutableListOf<Talk>()
        compose.setContent {
            MekaTheme {
                ShellFrame(layout, rail = { Text("rail") }, bar = { Text("bar") }) { m ->
                    Box(m) {
                        val talk = remember { Talk().also { made += it } }
                        DisposableEffect(talk) { onDispose { talk.released = true } }
                        Text("Ask")
                    }
                }
            }
        }
        compose.waitForIdle()
        val first = made.single()
        // Cover portrait → landscape (rail), back to portrait, then unfold (rail) and fold again (bar).
        listOf(ShellLayout.RAIL, ShellLayout.BOTTOM_BAR, ShellLayout.RAIL, ShellLayout.BOTTOM_BAR).forEach { next ->
            layout = next
            compose.waitForIdle()
            compose.onNodeWithTextExists("Ask")
            compose.onNodeWithTextExists(if (next == ShellLayout.RAIL) "rail" else "bar")
        }
        assertEquals("the talk was made once", 1, made.size)
        assertSame(first, made.single())
        assertEquals("the talk was never released", false, first.released)
    }

    @Test
    fun theBedsideClockWaitsWhileTalking() {
        assertEquals(false, TalkLayoutRules.bedside(shows = true, talking = TalkLayoutRules.talking(TalkPhase.SPEAKING)))
        assertEquals(true, TalkLayoutRules.bedside(shows = true, talking = TalkLayoutRules.talking(TalkPhase.ENDED)))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTextExists(text: String) {
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().let { assertEquals("\"$text\" shows", 1, it.size) }
    }
}
