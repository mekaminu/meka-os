package os.meka.android.today

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ShotApplication
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.TimelineRow

/**
 * Calmer Today, slice 3 (the approved mockup, Meka 2026-10-25 15:03): section labels are 12 sp caps, an event's title
 * in the day's timeline is 16 sp regular (was body 17) and its time is the 13 sp caption (was 15), so events sit at the
 * task rows' scale. Invented titles only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class)
class CalmerTypeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun sizeOf(text: String): TextUnit {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        check(node.config.getOrNull(SemanticsProperties.Text) != null)
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first().layoutInput.style.fontSize
    }

    @Test
    fun sectionLabelsEventTitlesAndTimesFollowTheCalmerScale() {
        val row = TimelineRow(
            id = "e-1", kind = TimelineKind.EVENT, time = "09:30–10:00", title = "Swim lesson",
            detail = "Leisure centre · Family", task = null, event = null, running = false, startMs = 0L,
        )
        compose.setContent {
            MekaTheme {
                Column {
                    SectionLabel("Anytime today")
                    TimelineEventRow(row, past = false)
                    AllDayLabel("All day")
                }
            }
        }
        assertEquals(12.sp, sizeOf("ANYTIME TODAY"))
        assertEquals(16.sp, sizeOf("Swim lesson"))
        assertEquals(13.sp, sizeOf("09:30–10:00"))
        assertEquals(13.sp, sizeOf("Leisure centre · Family"))
        assertEquals(13.sp, sizeOf("All day"))
    }
}
