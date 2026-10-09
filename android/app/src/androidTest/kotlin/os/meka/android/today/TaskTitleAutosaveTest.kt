package os.meka.android.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.TextAutosave

/**
 * Task title doesn't save (Meka, 2026-10-08 21:48): renamed a task, closed the detail, the edit was gone. The title
 * field saves itself on close, after a pause, never blank, and a save landing mid-typing doesn't eat the space.
 * Runs on the Fold or an emulator (`./gradlew :android:app:connectedDebugAndroidTest`); CI compiles it.
 */
class TaskTitleAutosaveTest {
    @get:Rule
    val compose = createComposeRule()

    private val saves = mutableListOf<Pair<String, String>>()
    private var saved by mutableStateOf("Buy mlik")
    private var open by mutableStateOf(true)

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                if (open) TaskTitleField("t1", saved, onSave = { id, t -> saves += id to t; saved = t })
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun renameThenCloseKeepsTheTitle() {
        show()
        compose.onNodeWithContentDescription("Title").performTextReplacement("Buy milk")
        compose.mainClock.advanceTimeBy(100) // closed well before the pause runs out
        open = false
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("t1" to "Buy milk"), saves)
    }

    @Test
    fun aPauseInTypingSavesAndTheSpaceBeforeTheNextWordStays() {
        show()
        val field = compose.onNodeWithContentDescription("Title")
        field.performTextReplacement("Buy ")
        compose.mainClock.advanceTimeBy(TextAutosave.DELAY_MS + 100)
        assertEquals(listOf("t1" to "Buy"), saves)
        field.performTextInput("milk")
        compose.mainClock.advanceTimeBy(TextAutosave.DELAY_MS + 100)
        assertEquals("t1" to "Buy milk", saves.last())
    }

    @Test
    fun aBlankTitleIsNeverSaved() {
        show()
        compose.onNodeWithContentDescription("Title").performTextClearance()
        compose.mainClock.advanceTimeBy(TextAutosave.DELAY_MS + 100)
        open = false
        compose.mainClock.advanceTimeByFrame()
        assertEquals(emptyList<Pair<String, String>>(), saves)
    }
}
