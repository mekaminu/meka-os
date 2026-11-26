package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Study's plan choices are rows opening sheets, not open chip rows. */
class StudyPlanChoicesTest {
    private val today = CivilDate.toEpochDay(2026, 11, 26)

    private fun input(finish: Long? = null, session: Int = 45, slots: Set<String> = emptySet()) = StudyPlanInput(
        today = today, finishByDay = finish, sessionMins = session, slots = slots,
        leftLessons = 18, leftKnownMins = 18 * 45, leftUnknown = 0, avgKnownMins = 45, planFromDay = null, planLeft = null,
    )

    @Test
    fun eachRowSaysWhatIsChosen() {
        val none = input()
        assertEquals("Finish by", StudyPlanChoiceRules.finishRow(none).title)
        assertEquals("Pick a date", StudyPlanChoiceRules.finishRow(none).value)
        assertEquals(CivilDate.shortLabel(today + 42), StudyPlanChoiceRules.finishRow(input(today + 42)).value)
        assertEquals("45 min", StudyPlanChoiceRules.sessionRow(none).value)
        assertEquals("1 h", StudyPlanChoiceRules.sessionRow(input(session = 60)).value)
        assertEquals("None yet", StudyPlanChoiceRules.slotsRow(none).value)
        assertEquals("Sat morning", StudyPlanChoiceRules.slotsRow(input(slots = setOf("sat"))).value)
        assertEquals("2 times · 4 sessions", StudyPlanChoiceRules.slotsRow(input(slots = setOf("train", "sat"))).value)
        assertTrue(StudyPlanChoiceRules.slotsRow(none).spoken.endsWith("Opens the choices"))
    }

    @Test
    fun theFinishSheetSaysWhatEachDateTakes() {
        val i = input(today + 42)
        val sheet = StudyPlanChoiceRules.finishSheet(i)
        // The same choices the card's chips had, in the same order, the one in use lit.
        assertEquals(StudyRules.plan(i).ends.map { it.label }, sheet.choices.map { it.label })
        assertEquals(listOf((today + 42).toString()), sheet.choices.filter { it.lit }.map { it.name })
        // 18 lessons of 45 min over 6 weeks is 3 sessions a week; over 4 weeks, 5.
        assertEquals("3 sessions a week of 45 min", sheet.choices.single { it.lit }.line)
        assertEquals("5 sessions a week of 45 min", sheet.choices.single { it.name == (today + 28).toString() }.line)
        assertEquals(today + 28, StudyPlanChoiceRules.finishNamed((today + 28).toString()))
        assertNull(StudyPlanChoiceRules.finishNamed("soon"))
        // A date he set off the choices is offered first, and a past one says so.
        val past = StudyPlanChoiceRules.finishSheet(input(today - 2))
        assertEquals("This date has passed", past.choices.first().line)
        assertTrue(past.choices.first().lit)
    }

    @Test
    fun theSessionSheetSaysHowManyAWeekEachLengthTakes() {
        val noDate = StudyPlanChoiceRules.sessionSheet(input())
        assertEquals(listOf("30 min", "45 min", "1 h"), noDate.choices.map { it.label })
        assertEquals(listOf("45"), noDate.choices.filter { it.lit }.map { it.name })
        assertEquals("Sessions of 30 minutes", noDate.choices.first().line)
        val dated = StudyPlanChoiceRules.sessionSheet(input(today + 42))
        assertEquals("5 sessions a week to finish by ${CivilDate.shortLabel(today + 42)}", dated.choices.first().line)
        // A length set off the choices is offered first, lit.
        val odd = StudyPlanChoiceRules.sessionSheet(input(session = 20))
        assertEquals("20 min", odd.choices.first().label)
        assertTrue(odd.choices.first().lit)
        assertEquals(30, StudyPlanChoiceRules.sessionNamed("30"))
        assertNull(StudyPlanChoiceRules.sessionNamed("5"))
        assertNull(StudyPlanChoiceRules.sessionNamed("x"))
    }

    @Test
    fun theTimesSheetPicksSeveral() {
        val i = input(slots = setOf("train", "eve"))
        val sheet = StudyPlanChoiceRules.slotsSheet(i)
        assertEquals(StudyRules.SLOTS.map { it.name }, sheet.choices.map { it.label })
        assertEquals(listOf("train", "eve"), sheet.choices.filter { it.lit }.map { it.name })
        assertEquals("3 sessions a week", sheet.choices.first().line)
        assertEquals("sat" to true, StudyPlanChoiceRules.slotTapped(i, "sat"))
        assertEquals("eve" to false, StudyPlanChoiceRules.slotTapped(i, "eve"))
        assertNull(StudyPlanChoiceRules.slotTapped(i, "noon"))
        assertTrue(sheet.choices.single { it.name == "train" }.spoken.endsWith(", chosen"))
        assertTrue(listOf(sheet, StudyPlanChoiceRules.finishSheet(i), StudyPlanChoiceRules.sessionSheet(i)).all { it.note.isNotBlank() })
    }
}
