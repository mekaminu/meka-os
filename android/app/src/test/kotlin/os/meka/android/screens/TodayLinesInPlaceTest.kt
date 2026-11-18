package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.BatteryCareLine
import os.meka.android.today.ConnectCard
import os.meka.android.today.SignInLineView
import os.meka.core.domain.BatteryCareStatus
import os.meka.core.domain.BatteryCareView
import os.meka.core.domain.SignInLine
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * Walk-through 2026-11-15 A20: Today's one-line links (the battery line, the folded "This device isn't syncing yet ·
 * Connect" line and the sign-in line) were caption words with 8 dp above and below, about 33 dp to hit. Each now takes
 * a whole 48 dp tap but keeps its 33 dp in the layout (a plain 48 dp line pushed the open Fold's Schedule below Health
 * and Setup, TodayScreensTest.innerSchedule), so what sits under it doesn't move; a tap near the target's top edge,
 * outside the line's own height, still unfolds it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class TodayLinesInPlaceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    Box(Modifier.testTag("line")) { content() }
                    Text("Below", Modifier.testTag("below"))
                }
            }
        }
        compose.waitForIdle()
    }

    /** The target is 48 dp, the line keeps its own height (under 40 dp) and a tap 2 dp inside the target's top acts. */
    private fun checkInPlace(name: String, target: SemanticsMatcher, opened: String) {
        ScreenShots.assertTallTarget(compose, name, target)
        val d = compose.density.density
        val line = compose.onNodeWithTag("line").fetchSemanticsNode()
        val kept = line.size.height / d
        if (kept >= 40f) fail("$name: the line takes $kept dp of the layout; it should keep its own height (about 33)")
        val below = compose.onNodeWithTag("below").fetchSemanticsNode()
        if (below.boundsInRoot.top < line.boundsInRoot.bottom - 0.5f) fail("$name: Below starts inside the line")
        val node = compose.onNode(target)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        if (bounds.top >= line.boundsInRoot.top - 1f) fail("$name: the target doesn't reach above the line")
        node.performTouchInput { click(Offset(centerX, 2f * d)) }
        compose.waitForIdle()
        compose.onNodeWithText(opened, substring = true).assertExists()
    }

    @Test
    fun theBatteryLineIsAWholeTargetInPlace() {
        val text = "Android may pause Navilife · Keep Navilife awake"
        show {
            BatteryCareLine(
                BatteryCareView(
                    BatteryCareStatus.NOT_EXEMPT, text, critical = false,
                    steps = listOf("Allow Navilife to run in the background"),
                    allowInAndroid = true, openSamsung = false, gap = null,
                ),
                onDismiss = {},
            )
        }
        checkInPlace("BatteryLine", hasText(text) and hasClickAction(), "Allow in Android")
    }

    @Test
    fun theFoldedConnectLineIsAWholeTargetInPlace() {
        val text = "This device isn't syncing yet · Connect"
        show { ConnectCard("https://example.invalid", connect = { _, _ -> null }) }
        checkInPlace("ConnectLine", hasText(text) and hasClickAction(), "Connect this device")
    }

    @Test
    fun theSignInLineIsAWholeTargetInPlace() {
        val line = SignInLine(
            text = "Google sign-in ends tomorrow at 14:05 · Reconnect",
            detail = "Google asks you to sign in again every so often.",
            critical = false, provider = "google", account = "alex@example.com", editing = false,
            spoken = "Google sign-in ends tomorrow at 14:05. Reconnect.",
        )
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 1_792_599_300_000L },
        )
        show { SignInLineView(line, core) }
        checkInPlace("SignInLine", hasText(line.text) and hasClickAction(), line.detail)
    }
}
