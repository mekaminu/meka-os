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
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.ConnectCard
import os.meka.android.today.SignInLineView
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.domain.SignInLine
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * Walk-through 2026-11-15 A20: the open Connect card's Connect and Not now (about 22 dp tall) and its recovery link
 * (about 32 dp), and the sign-in line's unfolded Reconnect (about 24 dp), are each a whole 48 dp target now; the words
 * keep their size, and Not now still folds the card back to its one line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class ConnectTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val folded = "This device isn't syncing yet · Connect"

    @Test
    fun theOpenConnectCardsButtonsAreWholeTargetsAndNotNowStillFolds() {
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    ConnectCard("https://example.invalid", connect = { _, _ -> null }, recover = { _, _ -> null })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(folded).performClick()
        compose.waitForIdle()
        val name = "ConnectCard"
        listOf("Connect", "Not now", RecoveryKeyRules.RECOVER).forEach {
            ScreenShots.assertTallTarget(compose, name, hasText(it) and hasClickAction())
        }
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not now").assertDoesNotExist()
        compose.onNodeWithText(folded).assertExists()
    }

    @Test
    fun theSignInLinesReconnectIsAWholeTarget() {
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
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    SignInLineView(line, core)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(line.text).performClick()
        compose.waitForIdle()
        ScreenShots.assertTallTarget(compose, "SignInLine", hasText("Reconnect") and hasClickAction())
    }
}
