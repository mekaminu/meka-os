package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.TalkStartRules

/**
 * Fold review 2026-10-09 07:26, item 5: the Talk mic sits at the capture bar's right end on the closed Fold's width
 * (412 dp) and a tap starts Talk; without a talk handler there is no mic. Runs on the Fold or an emulator; CI compiles it.
 */
class CaptureBarMicTest {
    @get:Rule
    val compose = createComposeRule()

    private var talks = 0

    private fun bar(withMic: Boolean) {
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) { QuickCapture(onAdd = {}, onTalk = if (withMic) ({ talks++ }) else null) }
            }
        }
    }

    @Test
    fun micSitsInTheCaptureBarAndStartsTalk() {
        bar(withMic = true)
        compose.onNodeWithTag(CAPTURE_BAR_TAG).assertIsDisplayed()
        compose.onNode(hasContentDescription(TalkStartRules.MIC_LABEL) and hasAnyAncestorTag()).assertIsDisplayed()
        compose.onNodeWithContentDescription(TalkStartRules.MIC_LABEL).performClick()
        assertEquals(1, talks)
    }

    @Test
    fun noHandlerNoMic() {
        bar(withMic = false)
        compose.onNodeWithTag(CAPTURE_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(TalkStartRules.MIC_LABEL).assertDoesNotExist()
    }

    private fun hasAnyAncestorTag() = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(CAPTURE_BAR_TAG))
}
