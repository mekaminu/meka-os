package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.AiSpendRow
import os.meka.android.ask.LifePageCard
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaLightColors
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.work.ObiCallsEntry
import os.meka.android.goals.ProgressRing
import os.meka.core.domain.PageCard
import os.meka.core.domain.PageRow

/**
 * Walk-through 2026-11-15, A21 part 2 (Light contrast): the white cards on AI, Obi on calls, Setup, Health, Activity
 * and Recovery sat on Light's off-white page with no edge; part 3 adds every Life page's card (Trips, School, Reading…). In Light they now draw a 1 dp edge in the track ink (as
 * Money's, [MoneyLightEdgeTest]); in Dark none. Measured on the card's left edge, half way down. Invented words only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class LightCardEdgeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.04f && abs(a.green - b.green) < 0.04f && abs(a.blue - b.blue) < 0.04f

    private fun leftEdge(choice: ThemeChoice, card: @Composable (Modifier) -> Unit): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(320.dp).background(Meka.colors.background).padding(16.dp)) {
                    card(Modifier.testTag("card"))
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("card").captureToImage().toPixelMap()
        return px[0, px.height / 2]
    }

    private val obiCalls: @Composable (Modifier) -> Unit = { m -> ObiCallsEntry("Obi rings businesses for you", m) {} }
    private val ai: @Composable (Modifier) -> Unit = { m -> AiSpendRow(null, m) }

    @Test
    fun obiOnCallsHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, obiCalls)
        if (!close(edge, MekaLightColors.track)) fail("Light: Obi on calls' edge is $edge, not the track ink")
    }

    @Test
    fun theAiRowHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, ai)
        if (!close(edge, MekaLightColors.track)) fail("Light: the AI row's edge is $edge, not the track ink")
    }

    private val lifeCard: @Composable (Modifier) -> Unit = { m ->
        LifePageCard(
            PageCard("trips", "Your trips", 1, listOf(PageRow("trip:1", "Lisbon", "3 nights", "Fri 4 Dec", false)), null),
            m, onMore = {}, onRow = {},
        )
    }

    @Test
    fun aLifePageCardHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, lifeCard)
        if (!close(edge, MekaLightColors.track)) fail("Light: a Life page card's edge is $edge, not the track ink")
    }

    @Test
    fun aLifePageCardHasNoEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, lifeCard)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: a Life page card's edge is $edge, not the surface")
    }

    @Test
    fun noEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, obiCalls)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Obi on calls' edge is $edge, not the surface")
    }

    /** A21 part 4: Goals' empty ring drew its track in surfaceRaised (~1.1:1 on Light's page); now the bars' track ink. */
    private fun ringTop(choice: ThemeChoice): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.background(Meka.colors.background).padding(16.dp).testTag("ring")) { ProgressRing(0f, 0) }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("ring").captureToImage().toPixelMap()
        // The ring is 40 dp inside 16 dp of padding (72 dp across); its 4 dp stroke's middle sits 18 dp from the top.
        return px[px.width / 2, px.height * 18 / 72]
    }

    @Test
    fun goalsEmptyRingIsSeenInLight() {
        val top = ringTop(ThemeChoice.LIGHT)
        if (!close(top, MekaLightColors.track)) fail("Light: Goals' empty ring is $top, not the track ink")
    }

    @Test
    fun goalsEmptyRingIsUnchangedInDark() {
        val top = ringTop(ThemeChoice.DARK)
        if (!close(top, MekaDarkColors.surfaceRaised)) fail("Dark: Goals' empty ring is $top, not surfaceRaised")
    }
}
