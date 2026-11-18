package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import os.meka.android.ask.MoneyCard
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaLightColors
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice

/**
 * Walk-through 2026-11-15, A21 (Light contrast): Money's white cards sat on Light's off-white page with no edge
 * (`money/inner-light-accounts.png`). In Light a card now draws a 1 dp edge in the track ink; in Dark it doesn't.
 * Measured on the card's left edge, half way down. Invented words only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class MoneyLightEdgeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.04f && abs(a.green - b.green) < 0.04f && abs(a.blue - b.blue) < 0.04f

    private fun leftEdge(choice: ThemeChoice): Pair<Color, Color> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(320.dp).background(Meka.colors.background).padding(16.dp)) {
                    MoneyCard(tag = "card") { Text("Everyday account"); Text("Spent this month") }
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("card").captureToImage().toPixelMap()
        val y = px.height / 2
        return px[0, y] to px[px.width / 2, y]
    }

    @Test
    fun aMoneyCardHasAnEdgeInLight() {
        val (edge, inside) = leftEdge(ThemeChoice.LIGHT)
        if (!close(edge, MekaLightColors.track)) fail("Light: the card's left edge is $edge, not the track ink ${MekaLightColors.track}")
        if (!close(inside, MekaLightColors.surface)) fail("Light: the card's inside is $inside, not the surface")
    }

    @Test
    fun aMoneyCardHasNoEdgeInDark() {
        val (edge, _) = leftEdge(ThemeChoice.DARK)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: the card's left edge is $edge, not the surface ${MekaDarkColors.surface}")
    }
}
