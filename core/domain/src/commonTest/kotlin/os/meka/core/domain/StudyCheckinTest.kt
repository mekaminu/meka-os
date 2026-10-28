package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Study plan, slice 2b rest: the Sunday check-in card on Today. */
class StudyCheckinTest {
    private val mon = 20752L // Mon 26 Oct 2026
    private val sun = mon + 6
    private val day = CivilDate.DAY_MS
    private val doneDay: (Long) -> Long = { it.floorDiv(day) }

    private val course = StudyCourse("c1", "Data analytics", 0L, sessionMins = 60, slots = setOf("eve"))

    private fun lesson(id: String, mins: Int?, pos: Long, doneOn: Long? = null) =
        StudyLesson(id, "c1", "1 · Foundations", "Lesson $id", mins, pos, doneOn != null, doneOn?.let { it * day + 3_600_000L }, null)

    private fun checkin(lessons: List<StudyLesson>, c: StudyCourse = course, today: Long = sun, nowMin: Int = 17 * 60): StudyCheckin? {
        val (s, _) = StudyAutoRules.sessions(listOf(c), lessons, emptyList(), today, doneDay, { false })
        val suggested = StudyAutoRules.suggest(listOf(c), lessons, s, today, doneDay)
        val plan = StudyRules.view(c, suggested, today).plan
        return StudyCheckinRules.checkin(listOf(c), suggested, s, mapOf(c.id to plan), today, nowMin, doneDay)
    }

    @Test
    fun itShowsFromSundayAtFiveToMondayNoon() {
        assertNull(StudyCheckinRules.sundayFor(sun, 16 * 60 + 59))
        assertEquals(sun, StudyCheckinRules.sundayFor(sun, 17 * 60))
        assertEquals(sun, StudyCheckinRules.sundayFor(sun + 1, 11 * 60 + 59))
        assertNull(StudyCheckinRules.sundayFor(sun + 1, 12 * 60))
        assertNull(StudyCheckinRules.sundayFor(sun - 1, 20 * 60))
        assertEquals(sun, StudyCheckinRules.dayOf(StudyCheckinRules.key(sun)))
        assertNull(StudyCheckinRules.dayOf("c1:$sun"))
    }

    @Test
    fun itSaysWhatWasTickedThisWeekAndNextWeeksSessions() {
        val lessons = listOf(lesson("a", 30, 1, doneOn = mon - 1), lesson("b", 45, 2, doneOn = mon + 2), lesson("c", 30, 3), lesson("d", 30, 4))
        val c = assertNotNull(checkin(lessons))
        val row = c.rows.single()
        // Lesson a was ticked the Sunday before: not this week.
        assertEquals("This week · 1 lesson · about 45 min", row.week)
        assertEquals("Next week · 2 sessions · Mon, Wed · 2 lessons", row.next)
        assertNull(row.status) // no finish date
        // Monday morning it's the same card, for the same Sunday.
        assertEquals(c.key, checkin(lessons, today = sun + 1, nowMin = 8 * 60)?.key)
        // Looks good on it: gone; off: gone.
        assertNull(checkin(lessons, course.copy(checkinSeen = sun)))
        assertNull(checkin(lessons, course.copy(checkin = false)))
        // A course added on Monday morning has no week behind it yet.
        assertNull(checkin(lessons, course.copy(addedAtMs = (sun + 1) * day + 3_600_000L), today = sun + 1, nowMin = 9 * 60))
    }

    @Test
    fun noTimesPickedAsksForThemAndBehindSaysSo() {
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 30, 2), lesson("c", 30, 3), lesson("d", 30, 4))
        val none = assertNotNull(checkin(lessons, course.copy(slots = emptySet())))
        assertEquals("This week · nothing ticked", none.rows.single().week)
        assertEquals("Next week · ${StudyCheckinRules.NO_SESSIONS}", none.rows.single().next)
        assertEquals("Pick when you can study and Obi will plan next week’s sessions.", none.obi)
        val late = course.copy(finishByDay = sun + 14, planFromDay = mon - 7, planLeft = 4)
        val behind = assertNotNull(checkin(lessons, late)).rows.single()
        assertEquals(true, behind.behind)
        assertEquals(true, behind.status?.contains("behind"))
    }

    @Test
    fun aCourseFinishedBeforeTheWeekHasNoCardButOneFinishedThisWeekDoes() {
        assertNull(checkin(listOf(lesson("a", 30, 1, doneOn = mon - 3))))
        val c = assertNotNull(checkin(listOf(lesson("a", 30, 1, doneOn = mon), lesson("b", 30, 2, doneOn = mon + 1))))
        assertEquals("All 2 lessons done", c.rows.single().next)
        assertEquals("Course done. Well played.", c.obi)
    }
}
