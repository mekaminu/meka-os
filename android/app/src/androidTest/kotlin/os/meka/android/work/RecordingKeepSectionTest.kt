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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.VoiceRecordingRules

/**
 * "Keep callers' recordings" on the closed Fold's width (412 dp): the three choices, the chosen one's line, and a tap
 * choosing another. Runs on the Fold or an emulator; CI compiles it.
 */
class RecordingKeepSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theThreeChoicesAndTheLineFollowTheChoice() {
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
        compose.onNodeWithText(VoiceRecordingRules.settingLine(30)).assertIsDisplayed()
        compose.onNodeWithContentDescription("${VoiceRecordingRules.SETTING_TITLE}: Don't keep").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(VoiceRecordingRules.settingLine(0))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("${VoiceRecordingRules.SETTING_TITLE}: 30 days").performClick() // back
        compose.onNodeWithContentDescription("${VoiceRecordingRules.SETTING_TITLE}: 30 days").performClick() // already chosen: nothing
        assertEquals(listOf(0, 30), chosen)
    }
}
