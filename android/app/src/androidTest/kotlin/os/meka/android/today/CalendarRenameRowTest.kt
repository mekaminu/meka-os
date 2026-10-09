package os.meka.android.today

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.CalendarChoice

/**
 * Fold review 2026-10-09 07:26, item 9: the Calendar key said "meka@gmail.com". The main calendar reads "Personal" and
 * Calendars renames any calendar in place: Rename unfolds a field under the row (the default as its hint), Done saves.
 * Runs on the Fold or an emulator (412 dp, the closed Fold's width); CI compiles it.
 */
class CalendarRenameRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renameUnfoldsAFieldAndDoneSavesTheName() {
        val c = CalendarChoice("google|meka@gmail.com|meka@gmail.com", "Personal", "Google · meka@gmail.com", true, "Personal", canRename = true)
        var saved: String? = null
        var toggled = false
        compose.setContent {
            MekaTheme {
                CalendarSwitchRow(c, Modifier.width(412.dp), onRename = { saved = it }) { toggled = true }
            }
        }
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rename Personal").performClick()
        compose.onNodeWithContentDescription("Name for Personal").assertIsDisplayed().performTextInput("Home")
        compose.onNodeWithContentDescription("Name for Personal").performImeAction()
        compose.waitUntil(2_000) { saved != null }
        assertEquals("Home", saved)
        assertFalse("Rename doesn't flip the switch", toggled)
    }

    @Test
    fun fixturesCantBeRenamed() {
        val c = CalendarChoice("fixtures||", "Fixtures", "FC Barcelona", true)
        compose.setContent { MekaTheme { CalendarSwitchRow(c, Modifier.width(412.dp)) {} } }
        compose.onNodeWithText("Fixtures").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescriptionCount("Rename Fixtures"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(d: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d)).fetchSemanticsNodes().size
}
