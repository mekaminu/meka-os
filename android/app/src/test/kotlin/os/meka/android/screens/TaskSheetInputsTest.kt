package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.ChecklistSection
import os.meka.android.today.NotesSection
import os.meka.android.today.TodayActions
import os.meka.core.domain.ChecklistRules
import os.meka.core.domain.GoalsView
import os.meka.core.domain.Lifecycle
import os.meka.core.domain.Task
import os.meka.core.domain.TaskNotesRules

/**
 * Walk-through 2026-11-22 A17: the task sheet had two open boxes, "Add notes…" and "Add an item… (or paste a list)".
 * Each is now a row that says what it does; a tap opens its field, focused, with Done; Done saves (notes) or adds
 * (items) and folds the field back. Invented words only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class TaskSheetInputsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun task(notes: String?) = Task(
        id = "t1", title = "Big shop", notes = notes, lifecycle = Lifecycle.ACTIVE, dueAtMs = null, scheduledAtMs = null,
        estimateMinutes = null, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L, completedAtMs = null,
        hasConflict = false, checklist = emptyList(),
    )

    private val added = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    private fun actions() = TodayActions(
        add = {}, complete = {}, select = {}, rename = { _, _ -> }, delete = {}, resolve = { _, _ -> },
        repeatChoices = { emptyList() }, setRepeat = { _, _ -> }, skip = {}, snooze = {},
        addStep = { _, t -> added += t }, setStepDone = { _, _ -> }, removeStep = {}, moveStep = { _, _, _ -> },
        untickAll = {}, someday = {}, goals = MutableStateFlow(GoalsView.EMPTY), setGoal = { _, _ -> },
        whenOf = { error("not drawn here") }, setWhen = { _, _, _ -> }, setNotes = { _, t -> notes += t },
        reminderOf = { error("not drawn here") }, setReminder = { _, _ -> },
    )

    private fun show(task: Task) {
        val a = actions()
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    ChecklistSection(task, a)
                    NotesSection(task, a)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fields() = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun noOpenBoxUntilARowIsTapped() {
        show(task(null))
        val name = "Task sheet"
        if (fields() != 0) fail("$name: an open input box shows before anything is tapped")
        ScreenShots.assertTallTarget(compose, name, hasContentDescription(ChecklistRules.ADD_ROW) and hasClickAction())
        ScreenShots.assertTallTarget(compose, name, hasText(TaskNotesRules.ADD) and hasClickAction())

        compose.onNode(hasContentDescription(ChecklistRules.ADD_ROW) and hasClickAction()).performClick()
        compose.waitForIdle()
        val field = compose.onNode(hasContentDescription("Add checklist items") and hasSetTextAction())
        field.performTextInput("eggs, milk")
        field.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("eggs, milk"), added)
        // Enter keeps the field open for the next; Done adds what's typed and folds it back to the row.
        compose.onNode(hasContentDescription("Add checklist items") and hasSetTextAction()).performTextInput("bread")
        compose.onNode(hasText(ChecklistRules.ADD_DONE) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf("eggs, milk", "bread"), added)
        if (fields() != 0) fail("$name: Done left the item field open")

        compose.onNode(hasText(TaskNotesRules.ADD) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription(TaskNotesRules.LABEL) and hasSetTextAction()).performTextInput(" Bring the blue bag ")
        compose.onNode(hasText(TaskNotesRules.DONE) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("Bring the blue bag", notes.last())
        if (fields() != 0) fail("$name: Done left the notes field open")
        if (compose.onAllNodes(hasText("Bring the blue bag")).fetchSemanticsNodes().isEmpty()) fail("$name: the saved notes don't show")
    }

    @Test
    fun notesShowWholeAndOpenOnATap() {
        show(task("Ring first\nParking round the back"))
        val name = "Task sheet with notes"
        if (fields() != 0) fail("$name: an open input box shows before anything is tapped")
        if (compose.onAllNodes(hasText(TaskNotesRules.ADD)).fetchSemanticsNodes().isNotEmpty()) fail("$name: + Add notes shows over notes")
        compose.onNode(hasContentDescription(TaskNotesRules.EDIT_DESCRIPTION) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription(TaskNotesRules.LABEL) and hasSetTextAction()).performTextReplacement("")
        compose.onNode(hasText(TaskNotesRules.DONE) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("", notes.last(), "an emptied field clears the notes")
        if (compose.onAllNodes(hasText(TaskNotesRules.ADD)).fetchSemanticsNodes().isEmpty()) fail("$name: cleared notes don't bring back + Add notes")
    }
}
