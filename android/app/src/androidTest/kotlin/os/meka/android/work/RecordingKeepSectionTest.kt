package os.meka.android.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.VoiceRecordingRules

/**
 * "Keep callers' recordings" on the closed Fold's width (412 dp): the two choices (7 days · Don't keep since 2026-10-20), the chosen one's line, and a tap
 * choosing another. Runs on the Fold or an emulator (the motion workflow). Taps go through the chip's click action, not
 * injected touches: the emulator refused the touch here (motion runs 76–80), and what's under test is the choice.
 */
class RecordingKeepSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theChoicesAndTheLineFollowTheChoice() {
        val chosen = mutableListOf<Int>()
        compose.setContent {
            var days by remember { mutableIntStateOf(VoiceRecordingRules.DEFAULT_KEEP_DAYS) }
            MekaTheme {
                Box(Modifier.width(412.dp)) {
                    RecordingKeepSection(days) { d -> chosen += d; days = d }
                }
            }
        }
        compose.onNodeWithText(VoiceRecordingRules.SETTING_TITLE).assertIsDisplayed()
        compose.onNodeWithText(VoiceRecordingRules.settingLine(7)).assertIsDisplayed()
        fun chip(label: String) = compose.onNodeWithContentDescription("${VoiceRecordingRules.SETTING_TITLE}: $label")
        chip("Don't keep").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(3_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(VoiceRecordingRules.settingLine(0))).fetchSemanticsNodes().isNotEmpty() }
        chip("7 days").performSemanticsAction(SemanticsActions.OnClick) // back
        compose.waitForIdle()
        chip("7 days").performSemanticsAction(SemanticsActions.OnClick) // already chosen: nothing
        compose.waitForIdle()
        assertEquals(listOf(0, 7), chosen)
    }
}
