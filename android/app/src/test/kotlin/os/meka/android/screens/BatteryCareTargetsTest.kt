package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.BatteryCareLine
import os.meka.core.domain.BatteryCareStatus
import os.meka.core.domain.BatteryCareView
import os.meka.core.domain.BatteryGap

/**
 * Walk-through 2026-11-15 A20: Today's battery line unfolds "Allow in Android", "Open Samsung battery" and "Got it",
 * which were caption links about 24 dp tall. Each is now a whole 48 dp target (the words stay caption-sized), and
 * Got it still puts the line's steps away and tells Today. The line itself keeps its height, so Today's layout is
 * unchanged while it's folded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class BatteryCareTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val line = "Navilife stopped for a while today · tap to fix"

    private val view = BatteryCareView(
        status = BatteryCareStatus.STOPPED, line = line, critical = true,
        steps = listOf("Allow Navilife to run in the background", "Add it to Never sleeping apps"),
        allowInAndroid = true, openSamsung = true, gap = BatteryGap(1_000L, 2_000L),
    )

    @Test
    fun theUnfoldedButtonsAreWholeTargetsAndGotItStillActs() {
        var dismissed = 0
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    BatteryCareLine(view, onDismiss = { dismissed++ })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(line).performClick()
        compose.waitForIdle()
        val name = "BatteryCare"
        listOf("Allow in Android", "Open Samsung battery", "Got it").forEach {
            ScreenShots.assertTallTarget(compose, name, hasText(it) and hasClickAction())
        }
        compose.onNodeWithText("Got it").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithText("Got it").assertDoesNotExist()
    }
}
