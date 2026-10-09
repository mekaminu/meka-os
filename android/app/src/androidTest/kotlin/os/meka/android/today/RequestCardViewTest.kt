package os.meka.android.today

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
import os.meka.core.domain.CivilDate
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MessageRequestRules
import os.meka.core.domain.RequestKind
import os.meka.core.domain.RequestMessage
import os.meka.core.domain.RequestProposal

/**
 * V1, requests slice 4: a request card on the closed Fold's width (412 dp) reads who, what they wrote and what Add
 * does; Add · Change · Not a task answer it; a work-from-home card has no Change. Runs on the Fold or an emulator; CI
 * compiles it.
 */
class RequestCardViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val answers = mutableListOf<RequestChoice>()

    private fun show(p: RequestProposal, text: String) {
        val card = MessageRequestRules.card(RequestMessage("wa-1", "Wife", text, cal.toEpochMs(fri, 14 * 60 + 2)), 0, p, fri, cal)
        compose.setContent {
            MekaTheme { Box(Modifier.width(412.dp)) { RequestCardView(card, { answers += it }) } }
        }
    }

    @Test
    fun aTaskCardReadsWellAndEachButtonAnswers() {
        show(RequestProposal(RequestKind.TASK, "Pick up dry cleaning", fri + 1, null), "can you pick up the dry cleaning tomorrow?")
        compose.onNodeWithText("From Wife · 14:02").assertIsDisplayed()
        compose.onNodeWithText("Add task: Pick up dry cleaning · Tomorrow").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add: Add task: Pick up dry cleaning · Tomorrow").performClick()
        compose.onNodeWithContentDescription("Change Add task: Pick up dry cleaning · Tomorrow").performClick()
        compose.onNodeWithContentDescription("Not a task").performClick()
        assertEquals(listOf(RequestChoice.ADD, RequestChoice.CHANGE, RequestChoice.DECLINE), answers)
    }

    @Test
    fun workFromHomeSaysWhatItChangesAndHasNoChange() {
        show(RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", CivilDate.toEpochDay(2026, 10, 15), null), "can you work from home Thursday?")
        compose.onNodeWithText("Thu 15 Oct shows as work from home on both apps").assertIsDisplayed()
        compose.onNodeWithText("Change").assertDoesNotExist()
    }
}
