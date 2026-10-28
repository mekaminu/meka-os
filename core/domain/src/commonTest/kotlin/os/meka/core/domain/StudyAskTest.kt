package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Obi sees everything, slice 1: his Study plan as Ask's and Live's `study` lines. */
class StudyAskTest {
    private val today = CivilDate.toEpochDay(2026, 10, 28) // Wed 28 Oct 2026
    private val dayMs = CivilDate.DAY_MS
    private val doneDay: (Long) -> Long = { it.floorDiv(dayMs) }
    private val clock: (Int) -> String = { LocalClock.formatMinute(it) }

    private val excel = StudyCourse("c1", "Data analytics", 0L, finishByDay = today + 28, planFromDay = today, planLeft = 3, slots = setOf("eve"))
    private val spanish = StudyCourse("c2", "Spanish", 1L)

    private fun lesson(id: String, course: String, title: String, pos: Long, done: Boolean = false, planned: Long? = null, mins: Int? = 30, module: String = "1 · Foundations") =
        StudyLesson(id, course, module, title, mins, pos, done, if (done) today * dayMs + 3_600_000 else null, planned)

    private val lessons = listOf(
        lesson("l1", "c1", "Welcome", 1, done = true),
        lesson("l2", "c1", "Pivot tables", 2, planned = today),
        lesson("l3", "c1", "Dashboards", 3, planned = today + 1),
        lesson("l4", "c1", "SQL basics", 4, planned = today + 2),
        lesson("s1", "c2", "Greetings", 1, module = StudyRules.DEFAULT_MODULE),
    )

    @Test
    fun aStudyQuestionGetsEveryCourseTheNextLessonsAndTheWeek() {
        val lines = StudyAskRules.askLines(listOf(excel, spanish), lessons, listOf(StudySession(today, 45, "c1", 19 * 60 + 30, "Study · Data")), today, full = true, doneDay, clock)
        assertEquals("Study · 2 courses · 1 of 5 lessons done", lines[0])
        assertEquals("Course “Data analytics” · 1 of 4 lessons · 25% · On track for ${CivilDate.shortLabel(today + 28)} · 1 session a week", lines[1])
        assertTrue(lines[2].startsWith("Course “Data analytics” next lessons: “Pivot tables”, 1 · Foundations, 30 min, planned today · “Dashboards”"), lines[2])
        assertEquals("Course “Spanish” · 0 of 1 lesson · 0% · no finish date set", lines[3])
        assertEquals("Course “Spanish” next lessons: “Greetings”, 30 min", lines[4])
        assertEquals("Study planned today · 1 lesson · “Pivot tables” (Data analytics)", lines[5])
        assertEquals("Study planned tomorrow · 1 lesson · “Dashboards” (Data analytics)", lines[6])
        assertEquals("Study planned ${CivilDate.shortLabel(today + 2)} · 1 lesson · “SQL basics” (Data analytics)", lines[7])
        assertEquals("Study session tonight 19:30 · 45 min · “Data analytics” · Study · Data", lines.last())
        assertTrue(lines.size <= StudyAskRules.ASK_MAX)
    }

    @Test
    fun otherwiseOneLineSoObiKnowsStudyIsThere() {
        val lines = StudyAskRules.askLines(listOf(excel, spanish), lessons, emptyList(), today, full = false, doneDay, clock)
        assertEquals(listOf("Study · 2 courses · “Data analytics” 1 of 4 · “Spanish” 0 of 1 · 1 lesson planned today (ask about study for the lessons and the plan)"), lines)
        assertTrue(StudyAskRules.askLines(emptyList(), lessons, emptyList(), today, full = true, doneDay, clock).isEmpty())
    }

    @Test
    fun aLessonFromAnEarlierDayRollsOverToToday() {
        val late = listOf(lesson("l2", "c1", "Pivot tables", 2, planned = today - 2))
        val lines = StudyAskRules.askLines(listOf(excel), late, emptyList(), today, full = true, doneDay, clock)
        assertTrue(lines.any { it == "Study planned today · 1 lesson · “Pivot tables” (Data analytics, from ${CivilDate.shortLabel(today - 2)})" }, lines.joinToString("\n"))
    }

    @Test
    fun studyWordsOrACourseNameMakeItAStudyQuestion() {
        val titles = listOf("Data analytics", "Spanish")
        listOf("How's my study going?", "what lessons tonight?", "plan module 4 for tomorrow", "Am I on track with the course?", "how's spanish going", "any analytics today?")
            .forEach { assertTrue(StudyAskRules.isAbout(it, titles), it) }
        listOf("what's on Thursday?", "Why is the sky blue?", "how much did I spend on data?").forEach { assertFalse(StudyAskRules.isAbout(it, titles), it) }
    }

    @Test
    fun theAreasAnswerAsAsksLinesOrSayWhereTheyWouldBe() {
        assertEquals("study: Study · 1 course", ObiSees.answer(ObiArea.STUDY, listOf("Study ·  1 course")))
        assertEquals("Navilife has nothing in Study yet; it would be on the Goals → Study screen. Say so plainly.", ObiSees.answer(ObiArea.STUDY, emptyList()))
        assertEquals(ObiArea.TRIPS, ObiArea.of(" Trips "))
        assertEquals(null, ObiArea.of("money"))
        assertTrue("look_up_my_money" in ObiSees.NO_AREA && "study" in ObiSees.NO_AREA)
        // Every area is its own Ask kind, with room for at least one line.
        assertEquals(ObiArea.entries.size, ObiArea.entries.map { it.kind }.toSet().size)
        assertTrue(ObiArea.entries.all { it.max >= 1 && it.key == it.kind.wire })
        assertTrue("no view" in ObiSees.NEVER_NO_VIEW && "which screen" in ObiSees.NEVER_NO_VIEW)
    }
}
