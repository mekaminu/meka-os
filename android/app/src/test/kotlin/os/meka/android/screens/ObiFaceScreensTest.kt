package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.HelloOrbView
import os.meka.android.designsystem.LocalMekaColors
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.FaceGather
import os.meka.core.domain.HelloEntrance
import os.meka.core.domain.OrbState

/**
 * Obi's face, slice 3d (the quality gate's shot of it): the stage's constellation as its points gather into his face —
 * the sphere (nothing gathered), half way (a swirl, the lines letting go), and gathered (an oval head of points, the
 * shape the face film then shows through) — on the night scene the stage always draws, and Light's app around it.
 * The face's own film is Meka's file on the server, never in this repo, so the shot shows the points only; the
 * dissolve's timing and the points' spots are pinned by the core's ObiFaceTest. Saved under `obi-face/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ObiFaceScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun gathering(name: String, theme: ThemeChoice, faceIn: List<Float>) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                // The stage is the night scene in both themes (HelloStage), so the orb draws its Dark gold on black.
                CompositionLocalProvider(LocalMekaColors provides MekaDarkColors) {
                    Column(
                        Modifier.fillMaxSize().background(Color.Black).testTag("screen"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        faceIn.forEach { f ->
                            HelloOrbView(
                                OrbState.SPEAKING, 0.5f, size = HelloEntrance.STAGE_SIZE.dp,
                                gather = FaceGather.gather(f, reduced = false),
                                modifier = Modifier.graphicsLayer { alpha = FaceGather.orbAlpha(f, reduced = false) },
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("obi-face", name))
    }

    // The sphere, half way, and gathered (before the points fade for the face).
    private val steps = listOf(0f, FaceGather.GATHER_SHARE / 2, FaceGather.GATHER_SHARE)

    @Test @Config(qualifiers = ScreenShots.COVER) fun gatherCover() = gathering("cover-dark-gather", ThemeChoice.DARK, steps)
    @Test @Config(qualifiers = ScreenShots.COVER) fun gatherCoverLight() = gathering("cover-light-gather", ThemeChoice.LIGHT, steps)
}
