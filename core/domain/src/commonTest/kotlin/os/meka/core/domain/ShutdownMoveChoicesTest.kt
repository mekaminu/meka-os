package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Shut down's per-task Tomorrow · Skip · Someday is one Move › opening a sheet. */
class ShutdownMoveChoicesTest {
    private fun item(title: String, repeating: Boolean) = ShutdownItem(
        Task("t-$title", title, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false), line = null, overdue = false, canSkip = repeating, canSomeday = !repeating,
    )

    @Test fun aOneOffMovesToTomorrowOrSomeday() {
        val one = item("Fix the gate latch", repeating = false)
        val sheet = ShutdownMoveChoiceRules.sheet(one)
        assertEquals("Move “Fix the gate latch”", sheet.title)
        assertEquals(listOf("Tomorrow", "Someday"), sheet.choices.map { it.label })
        assertEquals(listOf(ShutdownMoveChoiceRules.TOMORROW_LINE, ShutdownMoveChoiceRules.SOMEDAY_LINE), sheet.choices.map { it.line })
        assertTrue(sheet.choices.none { it.lit })
        assertEquals("Move Fix the gate latch. Opens Tomorrow or Someday", ShutdownMoveChoiceRules.spoken(one))
    }

    @Test fun aRepeatingTaskMovesToTomorrowOrSkips() {
        val gym = item("Gym", repeating = true)
        assertEquals(listOf(ShutdownMove.TOMORROW, ShutdownMove.SKIP), ShutdownMoveChoiceRules.moves(gym))
        assertEquals("Move Gym. Opens Tomorrow or Skip", ShutdownMoveChoiceRules.spoken(gym))
        assertEquals("Skip, " + ShutdownMoveChoiceRules.SKIP_LINE, ShutdownMoveChoiceRules.sheet(gym).choices[1].spoken)
    }

    @Test fun onlyAMoveTheTaskCanMakeIsNamed() {
        val one = item("Fix the gate latch", repeating = false)
        assertEquals(ShutdownMove.SOMEDAY, ShutdownMoveChoiceRules.named(one, "SOMEDAY"))
        assertEquals(ShutdownMove.TOMORROW, ShutdownMoveChoiceRules.named(one, "TOMORROW"))
        assertNull(ShutdownMoveChoiceRules.named(one, "SKIP"))
        assertNull(ShutdownMoveChoiceRules.named(one, "LATER"))
    }
}
