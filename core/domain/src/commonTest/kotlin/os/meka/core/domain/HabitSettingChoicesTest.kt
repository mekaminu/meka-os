package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: an open habit's Reminder, Do or avoid and List are rows opening sheets, not open chip rows. */
class HabitSettingChoicesTest {
    private val rules = HabitSettingChoiceRules
    private val lists = listOf(HabitBoardRules.MORNING, HabitBoardRules.ALL_DAY, HabitBoardRules.NIGHT, "Gym days")

    private fun row(avoid: Boolean = false, list: String = HabitBoardRules.MORNING, remindAt: Int? = null) = HabitBoardRow(
        id = "h1", name = "Drink water", meta = "", avoid = avoid, week = emptyList(), selectedIndex = 6,
        mark = HabitMark.CLEAR, markLabel = "", list = list, remindAt = remindAt,
    )

    @Test
    fun eachRowSaysWhatIsChosen() {
        assertEquals("Reminder", rules.reminderRow(row()).title)
        assertEquals("Off", rules.reminderRow(row()).value)
        assertEquals("Every day · 07:30", rules.reminderRow(row(remindAt = 7 * 60 + 30)).value)
        assertEquals("Do or avoid", rules.kindRow(row()).title)
        assertEquals("Do", rules.kindRow(row()).value)
        assertEquals("Avoid", rules.kindRow(row(avoid = true)).value)
        assertEquals("List", rules.listRow(row()).title)
        assertEquals("Morning", rules.listRow(row()).value)
        listOf(rules.reminderRow(row()), rules.kindRow(row()), rules.listRow(row())).forEach {
            assertTrue(it.spoken.startsWith("Drink water") && it.spoken.endsWith("Opens its choices"), it.spoken)
        }
    }

    @Test
    fun theReminderSheetLightsWhatIsInForceAndSaysTheTime() {
        val off = rules.reminderSheet(row(list = HabitBoardRules.NIGHT))
        assertEquals("Drink water reminder", off.title)
        assertEquals(listOf("Off"), off.choices.filter { it.lit }.map { it.label })
        assertTrue(off.choices.last().line.contains("21:30"), off.choices.last().line)
        val on = rules.reminderSheet(row(remindAt = 8 * 60))
        assertEquals(listOf("Every day"), on.choices.filter { it.lit }.map { it.label })
        assertTrue(on.choices.last().line.contains("08:00"))
        assertTrue(rules.reminderSheet(row(avoid = true)).choices.last().line.contains("kept off"))
    }

    @Test
    fun aReminderChoiceSetsOnlyWhatChanges() {
        assertEquals(HabitReminderPick(7 * 60 + 30), rules.reminderNamed(row(), "ON"))
        assertEquals(HabitReminderPick(9 * 60), rules.reminderNamed(row(list = "Gym days"), "ON"))
        assertNull(rules.reminderNamed(row(), "OFF"))
        assertEquals(HabitReminderPick(null), rules.reminderNamed(row(remindAt = 600), "OFF"))
        assertNull(rules.reminderNamed(row(remindAt = 600), "ON"))
        assertNull(rules.reminderNamed(row(), "LATER"))
    }

    @Test
    fun doOrAvoidFlipsOnlyWhenItChanges() {
        val s = rules.kindSheet(row())
        assertEquals(listOf("Do", "Avoid"), s.choices.map { it.label })
        assertEquals(listOf(true, false), s.choices.map { it.lit })
        assertEquals(true, rules.kindNamed(row(), HabitBoardRules.AVOID))
        assertNull(rules.kindNamed(row(), HabitBoardRules.DO))
        assertEquals(false, rules.kindNamed(row(avoid = true), HabitBoardRules.DO))
        assertNull(rules.kindNamed(row(), "maybe"))
    }

    @Test
    fun theListSheetOffersEveryListWithWhenItsFor() {
        val s = rules.listSheet(row(), lists)
        assertEquals("Move Drink water to", s.title)
        assertEquals(lists, s.choices.map { it.label })
        assertEquals(listOf("Morning"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Before work · reminders start at 07:30", s.choices[0].line)
        assertEquals("Your list · reminders start at 09:00", s.choices[3].line)
        s.choices.forEach { assertFalse(it.line.endsWith("·") || it.line.isBlank(), it.line) }
        assertEquals("Gym days", rules.listNamed(row(), lists, "Gym days"))
        assertNull(rules.listNamed(row(), lists, "Morning"))
        assertNull(rules.listNamed(row(), lists, "Nowhere"))
    }
}
