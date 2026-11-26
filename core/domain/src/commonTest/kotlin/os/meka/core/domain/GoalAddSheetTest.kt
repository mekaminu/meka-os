package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walk-through week 5, A17 (g4b 12): Goals' bare "New habit…" and "New goal…" boxes are rows opening a sheet. The
 * rows say what they add; the sheets start from the old defaults; a blank name can't be added.
 */
class GoalAddSheetTest {
    @Test
    fun rowsSayWhatTheyAddAndReadWhole() {
        assertEquals("+ New habit", GoalAddRules.habitRow.label)
        assertEquals("+ New goal", GoalAddRules.goalRow.label)
        assertEquals("New habit, Something to do, or stop doing, most weeks", GoalAddRules.rowSpoken(GoalAddRules.habitRow))
        assertEquals("New goal, Something you're working towards", GoalAddRules.rowSpoken(GoalAddRules.goalRow))
    }

    @Test
    fun sheetsHaveATitleAnExampleAndAdd() {
        listOf(GoalAddRules.habitSheet, GoalAddRules.goalSheet).forEach { s ->
            assertTrue(s.title.isNotBlank() && s.hint.isNotBlank() && s.note.isNotBlank() && s.spokenHint.isNotBlank())
            assertEquals("Add", s.addLabel)
            assertFalse(s.hint.endsWith("…"), "an example, not an open box's prompt")
        }
    }

    @Test
    fun defaultsAreTheOldBoxes() {
        assertEquals(7, GoalAddRules.DEFAULT_PER_WEEK)
        assertEquals("Every day", GoalRules.targetLabel(GoalAddRules.DEFAULT_PER_WEEK))
        assertEquals(HabitTiming.ANYTIME, GoalAddRules.DEFAULT_TIMING)
        assertEquals(GoalHorizon.MONTH, GoalAddRules.DEFAULT_HORIZON)
    }

    @Test
    fun aBlankNameCantBeAddedAndANameIsTrimmedAndCapped() {
        assertFalse(GoalAddRules.canAdd(""))
        assertFalse(GoalAddRules.canAdd("   "))
        assertNull(GoalAddRules.cleaned(" \n "))
        assertEquals("Read 20 pages", GoalAddRules.cleaned("  Read 20 pages "))
        assertEquals(GoalAddRules.MAX_TITLE, GoalAddRules.cleaned("x".repeat(300))!!.length)
        assertNull(GoalAddRules.cleanedTarget("  "))
        assertEquals("21 km", GoalAddRules.cleanedTarget(" 21 km "))
    }

    @Test
    fun studysAddCourseIsARowOpeningASheetThatReadsTheNameLikeTheOldBox() {
        assertEquals("+ Add course", GoalAddRules.courseRow.label)
        assertEquals("Add course, A course you're working through, its lessons to tick", GoalAddRules.rowSpoken(GoalAddRules.courseRow))
        val s = GoalAddRules.courseSheet
        assertEquals("Add", s.addLabel)
        assertFalse(s.hint.endsWith("…"))
        assertFalse(GoalAddRules.canAddCourse("   "))
        assertFalse(GoalAddRules.canAddCourse("Course: "), "the prefix alone is no name")
        assertFalse(GoalAddRules.canAddCourse("…"))
        assertTrue(GoalAddRules.canAddCourse(" Data analytics "))
        assertEquals("Data analytics", GoalAddRules.courseName("Add a course: Data analytics"))
        assertEquals(StudyRules.courseTitle("Spanish"), GoalAddRules.courseName("Spanish\nGreetings"))
    }
}
