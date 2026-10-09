package os.meka.android.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.AfterWorkSummaries
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.VoiceRecordingRules

/**
 * Call assistant polish 8c on the closed Fold's width (412 dp): a voice message the server kept shows ▶ Play once its
 * person is unfolded; Play asks for the recording, and one that can't be had says so. Runs on the Fold or an
 * emulator; CI compiles it.
 */
class VoiceMessagePlayerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aKeptVoiceMessageOffersPlayAndSaysWhenItCantBePlayed() {
        val asked = mutableListOf<String>()
        val kept = CapturedItem("h0123456789abcdef", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "Garage", "Your car is ready", null, 1_000L, hasAudio = true)
        val summary = AfterWorkSummaries.build(listOf(kept), PeopleLists())
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) {
                    AfterWorkPane(summary, onDone = {}, onClose = {}, audio = { id -> asked += id; null })
                }
            }
        }
        compose.onNodeWithText(VoiceRecordingRules.PRIVACY).assertIsDisplayed()
        compose.onNodeWithText("Garage").performClick()
        compose.onNodeWithContentDescription("Play the voice message").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTextCount(VoiceRecordingRules.FAILED) > 0 }
        compose.onNodeWithText(VoiceRecordingRules.FAILED).assertIsDisplayed()
        assertEquals(listOf("h0123456789abcdef"), asked)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
