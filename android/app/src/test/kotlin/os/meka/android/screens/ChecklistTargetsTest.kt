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
import kotlin.test.assertEquals
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
import os.meka.android.today.TodayActions
import os.meka.core.domain.ChecklistItem
import os.meka.core.domain.ChecklistRules
import os.meka.core.domain.GoalsView
import os.meka.core.domain.Lifecycle
import os.meka.core.domain.ShopModeRules
import os.meka.core.domain.Task

/**
 * Walk-through 2026-11-15 A20: a task's checklist had "Shop mode" and "Untick all" as bare caption links about 32 dp
 * tall to hit. Each is now a whole 48 dp target (the text stays where it was, centred in the height), and a tap on it
 * still does its job. Invented words only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class ChecklistTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val task = Task(
        id = "t1", title = "Big shop", notes = null, lifecycle = Lifecycle.ACTIVE, dueAtMs = null, scheduledAtMs = null,
        estimateMinutes = null, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L, completedAtMs = null,
        hasConflict = false,
        checklist = listOf(
            ChecklistItem("i1", "t1", "Eggs", checked = false, position = 1),
            ChecklistItem("i2", "t1", "Bread", checked = false, position = 2),
            ChecklistItem("i3", "t1", "Milk", checked = true, position = 3),
        ),
    )

    @Test
    fun shopModeAndUntickAllAreWholeTargets() {
        val unticked = mutableListOf<String>()
        val actions = TodayActions(
            add = {}, complete = {}, select = {}, rename = { _, _ -> }, delete = {}, resolve = { _, _ -> },
            repeatChoices = { emptyList() }, setRepeat = { _, _ -> }, skip = {}, snooze = {},
            addStep = { _, _ -> }, setStepDone = { _, _ -> }, removeStep = {}, moveStep = { _, _, _ -> },
            untickAll = { unticked += it }, someday = {}, goals = MutableStateFlow(GoalsView.EMPTY), setGoal = { _, _ -> },
            whenOf = { error("not drawn here") }, setWhen = { _, _, _ -> }, setNotes = { _, _ -> },
            reminderOf = { error("not drawn here") }, setReminder = { _, _ -> },
        )
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) { ChecklistSection(task, actions) }
            }
        }
        compose.waitForIdle()
        val name = "Checklist"
        if (ShopModeRules.offer(3)) ScreenShots.assertTallTarget(compose, name, hasText(ShopModeRules.OPEN) and hasClickAction())
        ScreenShots.assertTallTarget(compose, name, hasText(ChecklistRules.UNTICK_ALL) and hasClickAction())
        compose.onNodeWithText(ChecklistRules.UNTICK_ALL).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), unticked)
    }
}
