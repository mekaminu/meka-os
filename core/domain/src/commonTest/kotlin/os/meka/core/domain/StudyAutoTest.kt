package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Study plan, slice 2b part 1: lessons he hasn't planned are suggested for his next study sessions. */
class StudyAutoTest {
    private val mon = 20752L // Mon 26 Oct 2026
    private val day = CivilDate.DAY_MS
    private val dayOf: (Long) -> Long = { it.floorDiv(day) }

    private val course = StudyCourse("c1", "Data analytics certificate", 0L, sessionMins = 60, slots = setOf("sat", "eve"))
    private val other = StudyCourse("c2", "Spanish for travel", 1L, sessionMins = 30)

    private fun lesson(id: String, mins: Int?, pos: Long, course: String = "c1", done: Boolean = false, doneAt: Long? = null, planned: Long? = null) =
        StudyLesson(id, course, "1 · Foundations", "Lesson $id", mins, pos, done, doneAt, planned)

    private fun event(id: String, title: String, d: Long, h: Int, mins: Int) =
        CalendarEvent(id, title, d * day + h * 3_600_000L, d * day + h * 3_600_000L + mins * 60_000L, false, null, "google", "alex@x", "Personal")

    @Test
    fun studyEventsAreSessionsMatchedToTheirCourse() {
        assertTrue(StudyAutoRules.isStudy("Study · Data analytics"))
        assertTrue(StudyAutoRules.isStudy("study: SQL"))
        assertTrue(StudyAutoRules.isStudy("Study"))
        assertFalse(StudyAutoRules.isStudy("Students' evening"))
        assertFalse(StudyAutoRules.isStudy("Team standup"))
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 20, 1, course = "c2"))
        val (s, cal) = StudyAutoRules.sessions(
            listOf(course, other), lessons,
            listOf(
                event("e1", "Study · Spanish", mon + 1, 19, 30),
                event("e2", "Study · Data analytics", mon + 2, 7, 45),
                event("e3", "Study", mon + 3, 20, 60),
                event("e4", "Dentist", mon + 3, 10, 30),
                event("e5", "Study · Data analytics", mon + 20, 7, 45), // past the horizon
            ),
            mon, dayOf, { false },
        )
        assertTrue(cal)
        assertEquals(listOf(StudySession(mon + 1, 30, "c2"), StudySession(mon + 2, 45, "c1"), StudySession(mon + 3, 60, "c1")), s.map { it.copy(startMin = null, label = null) })
    }

    @Test
    fun withNoStudyEventsThePickedTimesGiveTheSessions() {
        val trains = course.copy(slots = setOf("train", "sat"))
        val (s, cal) = StudyAutoRules.sessions(listOf(trains), listOf(lesson("a", 30, 1)), emptyList(), mon, dayOf, { d -> CivilDate.isoDayOfWeek(d) <= 5 })
        assertFalse(cal)
        // Train mornings: three office days a week at most; Saturday morning.
        assertEquals(listOf(mon, mon + 1, mon + 2, mon + 5, mon + 7, mon + 8, mon + 9, mon + 12), s.map { it.day })
        assertTrue(s.all { it.mins == 60 })
        // Nothing left to study, or no times picked: no sessions.
        assertTrue(StudyAutoRules.sessions(listOf(trains), listOf(lesson("a", 30, 1, done = true)), emptyList(), mon, dayOf, { true }).first.isEmpty())
        assertTrue(StudyAutoRules.sessions(listOf(course.copy(slots = emptySet())), listOf(lesson("a", 30, 1)), emptyList(), mon, dayOf, { true }).first.isEmpty())
    }

    @Test
    fun lessonsFillSessionsInCourseOrderAndHandPlannedOnesNeverMove() {
        val lessons = listOf(
            lesson("a", 30, 1), lesson("b", 20, 2), lesson("c", 40, 3, planned = mon + 9), lesson("d", null, 4), lesson("e", 90, 5),
            lesson("f", 10, 6),
        )
        val sessions = listOf(StudySession(mon + 1, 60, "c1"), StudySession(mon + 3, 45, "c1"), StudySession(mon + 5, 60, "c1"))
        val out = StudyAutoRules.suggest(listOf(course), lessons, sessions, mon, dayOf).associateBy { it.id }
        // Tue: a + b (50 of 60). Thu: d counts as the average (38) → 38 of 45. Sat: e (90) is longer than the session but takes an empty one.
        assertEquals(mon + 1, out.getValue("a").plannedDay); assertTrue(out.getValue("a").suggested)
        assertEquals(mon + 1, out.getValue("b").plannedDay)
        assertEquals(mon + 3, out.getValue("d").plannedDay)
        assertEquals(mon + 5, out.getValue("e").plannedDay)
        // f doesn't fit after e and there's no later session: not suggested.
        assertNull(out.getValue("f").plannedDay); assertFalse(out.getValue("f").suggested)
        // c is his: kept on its day, not a suggestion.
        assertEquals(mon + 9, out.getValue("c").plannedDay); assertFalse(out.getValue("c").suggested)
    }

    @Test
    fun aHandPlannedLessonUsesItsSessionsTimeFirst() {
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 45, 2, planned = mon + 1))
        val out = StudyAutoRules.suggest(listOf(course), lessons, listOf(StudySession(mon + 1, 60, "c1"), StudySession(mon + 2, 60, "c1")), mon, dayOf)
        assertEquals(mon + 2, out.first { it.id == "a" }.plannedDay)
    }

    @Test
    fun aLessonNotDoneMovesOnAndOneTickedTodayCountsOnToday() {
        val tickedToday = mon * day + 9 * 3_600_000L
        val lessons = listOf(lesson("a", 30, 1, done = true, doneAt = tickedToday), lesson("b", 30, 2), lesson("c", 30, 3))
        val out = StudyAutoRules.suggest(listOf(course), lessons, listOf(StudySession(mon, 60, "c1"), StudySession(mon + 2, 60, "c1")), mon, dayOf)
        // Today's hour: a (ticked) + b; c on Wednesday.
        assertEquals(listOf(mon, mon, mon + 2), out.map { it.plannedDay })
        val on = StudyRules.onDay(out, listOf(course), mon, rollOver = true, doneDay = dayOf)
        assertEquals("Study today · 1 of 2 done · about 30 min · next: Lesson b", StudyRules.todayLine(on, mon)?.text)
        // Tomorrow it's worked out again from that day: what's left goes into the next session.
        val next = StudyAutoRules.suggest(listOf(course), lessons, listOf(StudySession(mon + 2, 60, "c1")), mon + 1, dayOf)
        assertEquals(listOf(null, mon + 2, mon + 2), next.map { it.plannedDay })
    }

    @Test
    fun theCourseShowsSuggestionsQuietlyWithTheirLine() {
        val lessons = StudyAutoRules.suggest(
            listOf(course), listOf(lesson("a", 30, 1), lesson("b", 30, 2), lesson("c", 30, 3), lesson("d", 30, 4)),
            listOf(StudySession(mon + 1, 60, "c1"), StudySession(mon + 3, 30, "c1")), mon, dayOf,
        )
        val v = StudyRules.view(course, lessons, mon, fromCalendar = true)
        val rows = v.modules.flatMap { it.lessons }
        assertEquals(listOf("Tomorrow · suggested", "Tomorrow · suggested", "Thu 29 Oct · suggested", null), rows.map { it.planned })
        assertTrue(rows.take(3).all { it.suggested && it.plannedDay == null && !it.late })
        assertEquals("Lesson a, 30 minutes, suggested for Tomorrow", rows.first().spoken)
        assertEquals("3 lessons suggested for your next 2 sessions · Tue, Thu · from your calendar", v.suggested)
        assertEquals("1 lesson suggested for your next session · Tue · from the times you picked", StudyAutoRules.line("c1", lessons.take(1), false))
        assertNull(StudyRules.view(course, listOf(lesson("a", 30, 1)), mon).suggested)
        // The Week board and Tomorrow read them like planned lessons.
        assertEquals("Study · 2 lessons · about 1 h · Lesson a, Lesson b", StudyRules.tomorrowLine(StudyRules.onDay(lessons, listOf(course), mon + 1, false, dayOf))?.text)
    }

    @Test
    fun anEventNamingNoCourseGoesToTheFirstWithLessonsLeft() {
        val lessons = listOf(lesson("a", 30, 1, done = true), lesson("b", 20, 1, course = "c2"))
        val (s, _) = StudyAutoRules.sessions(listOf(course, other), lessons, listOf(event("e", "Study", mon, 19, 30)), mon, dayOf, { false })
        assertEquals("c2", s.single().courseId)
        assertEquals("c1", StudyAutoRules.courseFor("Study · data analytics", listOf(course, other)))
        assertNull(StudyAutoRules.courseFor("Study · physics", listOf(course, other)))
    }
}
