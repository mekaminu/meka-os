package os.meka.android.update

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ScreenShots
import os.meka.android.screens.ShotApplication
import os.meka.core.facade.AppRelease

/**
 * Calmer Today, slice 2 (the approved mockup): the update card is a slim one-line banner, "Update ready · build 2221"
 * with Later and Install at its end, no taller than a tap target and a little; its taps reach the updater.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class UpdateBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val release = AppRelease("android", 2221, "0.1.2221", "ab", 24_300_000, 24)

    @Test
    fun readyIsOneSlimLineWithLaterAndInstall() {
        val taps = mutableListOf<String>()
        compose.setContent {
            MekaTheme {
                UpdateBanner(
                    UpdateState.Ready(release),
                    install = { taps += "install ${it.versionCode}" }, later = { taps += "later" },
                    allow = {}, showPrompt = null, undo = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Update ready · build 2221").assertExists()
        val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
        val height = compose.onNodeWithTag(UPDATE_BANNER_TAG).fetchSemanticsNode().boundsInRoot.height / density
        // One 48 dp row (was a ~120 dp card): the line, Later and Install side by side.
        assertTrue("the banner is $height dp tall", height <= 56f)
        val top = { t: String -> compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot.center.y }
        assertTrue("Later and Install sit on the line's row", kotlin.math.abs(top("Install") - top("Update ready · build 2221")) < 2f)
        assertTrue(kotlin.math.abs(top("Later") - top("Install")) < 2f)
        compose.onNodeWithText("Install").performClick()
        compose.onNodeWithText("Later").performClick()
        assertEquals(listOf("install 2221", "later"), taps)
    }

    @Test
    fun hiddenSaysWhereItWentWithUndo() {
        var undone = 0
        compose.setContent {
            MekaTheme {
                UpdateBanner(UpdateState.Hidden(release), install = {}, later = {}, allow = {}, showPrompt = null, undo = { undone++ })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1, undone)
    }
}
