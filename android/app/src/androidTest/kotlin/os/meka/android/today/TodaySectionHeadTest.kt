package os.meka.android.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.TimelineRow

/**
 * Fold review 2026-10-09 07:26, item 6: on a work day "TODAY" sat alone over the capture bar. On the closed Fold's
 * width (412 dp) the section's label says what it holds and comes in one item with the now line, the work band right
 * under it. Runs on the Fold or an emulator; CI compiles it.
 */
class TodaySectionHeadTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = TimelineRow("now", TimelineKind.NOW, "07:26", "Now", "1 h 30 free until Work", null, null, false, 0L)
    private val work = TimelineRow("w-540", TimelineKind.WORK, "09:00–17:30", "Work", null, null, null, false, 1L)

    @Test
    fun theLabelSaysWhatTheSectionHoldsAndTheNowLineComesWithIt() {
        compose.setContent {
            MekaTheme {
                LazyColumn(Modifier.width(412.dp).height(400.dp), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    item(key = "r-now") { Headed("Today · Work 09:00–17:30") { m -> NowLine(now, m) } }
                    item(key = "r-work") { WorkTimelineRow(work) }
                }
            }
        }
        val label = compose.onNodeWithText("TODAY · WORK 09:00–17:30").assertIsDisplayed().getUnclippedBoundsInRoot()
        val line = compose.onNodeWithContentDescription("Now, 07:26, 1 h 30 free until Work").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val band = compose.onNodeWithContentDescription("Work, 09:00–17:30").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("the now line sits right under the label", line.top >= label.bottom && line.top - label.bottom <= 16.dp)
        assertTrue("the work band follows the now line", band.top >= line.bottom)
    }

    @Test
    fun noLabelIsJustTheRow() {
        compose.setContent {
            MekaTheme { Headed(null) { m -> NowLine(now, m) } }
        }
        compose.onNodeWithContentDescription("Now, 07:26, 1 h 30 free until Work").assertIsDisplayed()
        compose.onNodeWithText("TODAY", substring = true).assertDoesNotExist()
    }
}
