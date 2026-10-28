package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Obi sees everything, slice 2: Ask's Study cards, tick a lesson and plan a lesson (or a module) for a day. */
class StudyAskCardsTest {
    private val today = CivilDate.toEpochDay(2026, 10, 28) // Wed 28 Oct 2026
    private val excel = StudyCourse("c1", "Data analytics", 0L)
    private val spanish = StudyCourse("c2", "Spanish", 1L)
    private val courses = listOf(excel, spanish)

    private fun lesson(id: String, course: String, title: String, pos: Long, module: String, done: Boolean = false, planned: Long? = null) =
        StudyLesson(id, course, module, title, 30, pos, done, null, planned)

    private val lessons = listOf(
        lesson("l1", "c1", "Welcome", 1, "1 · Foundations", done = true),
        lesson("l2", "c1", "Pivot tables", 2, "1 · Foundations"),
        lesson("l3", "c1", "Dashboards", 3, "4 · Reporting"),
        lesson("l4", "c1", "Charts that work", 4, "4 · Reporting", planned = today + 1),
        lesson("l5", "c1", "Review", 5, "4 · Reporting"),
        lesson("s1", "c2", "Greetings", 1, "Lessons"),
        lesson("s2", "c2", "Review", 2, "Lessons"),
    )

    @Test
    fun aTickCardForTheOneLessonItNames() {
        val p = StudyAskCards.tick(courses, lessons, "pivot tables")!!
        assertEquals(AskProposal.StudyTick("l2", "Pivot tables", "Data analytics"), p)
        assertEquals("Tick off “Pivot tables” · Data analytics", StudyAskCards.tickCard(p).line)
        assertEquals("Done", StudyAskCards.tickCard(p).button)
        assertEquals("Ticked off “Pivot tables” in Study", AskRules.doneLine(p, today))
        // Part of a name is enough when only one lesson holds it.
        assertEquals("l3", StudyAskCards.tick(courses, lessons, "dashboard")?.lessonId)
    }

    @Test
    fun noGuessWhenTwoCoursesHaveItUnlessHisCourseIsNamed() {
        assertNull(StudyAskCards.tick(courses, lessons, "Review"))
        assertEquals("s2", StudyAskCards.tick(courses, lessons, "Spanish: Review")?.lessonId)
        assertEquals("l5", StudyAskCards.tick(courses, lessons, "Data analytics: Review")?.lessonId)
        // A lesson already ticked, or one that isn't there, makes no card.
        assertNull(StudyAskCards.tick(courses, lessons, "Welcome"))
        assertNull(StudyAskCards.tick(courses, lessons, "Calculus"))
        assertNull(StudyAskCards.tick(courses, lessons, ""))
    }

    @Test
    fun aPlanCardForALessonOrAModulesLessonsNotDone() {
        val one = StudyAskCards.plan(courses, lessons, "Pivot tables", today + 1, today)!!
        assertEquals(AskProposal.StudyPlan(listOf("l2"), "“Pivot tables”", "Data analytics", today + 1), one)
        assertEquals("Plan “Pivot tables” for tomorrow · Data analytics", StudyAskCards.planCard(one, today).line)
        assertEquals("Planned “Pivot tables” for tomorrow", AskRules.doneLine(one, today))

        // "plan module 4 for tomorrow": its lessons not done and not already on that day, in course order.
        val module = StudyAskCards.plan(courses, lessons, "module 4", today + 1, today)!!
        assertEquals(listOf("l3", "l5"), module.lessonIds)
        assertEquals("Module 4, Reporting · 2 lessons", module.what)
        assertEquals("Plan Module 4, Reporting · 2 lessons for tomorrow · Data analytics", StudyAskCards.planCard(module, today).line)
        // As he'd say it, as Study shows it, or as he typed it in the outline.
        assertEquals(module, StudyAskCards.plan(courses, lessons, "Module 4: Reporting", today + 1, today))
        assertEquals(module, StudyAskCards.plan(courses, lessons, "4 · Reporting", today + 1, today))
        assertEquals(module, StudyAskCards.plan(courses, lessons, "Data analytics: reporting", today + 1, today))
        assertEquals(listOf("l2"), StudyAskCards.plan(courses, lessons, "foundations", today + 2, today)?.lessonIds)
        assertEquals("Planned Module 4, Reporting · 2 lessons for ${CivilDate.shortLabel(today + 3)}",
            AskRules.doneLine(module.copy(day = today + 3), today))
    }

    @Test
    fun noPlanCardForADayGoneByOrNothingToChange() {
        assertNull(StudyAskCards.plan(courses, lessons, "Pivot tables", today - 1, today))
        assertNull(StudyAskCards.plan(courses, lessons, "Pivot tables", null, today))
        assertNull(StudyAskCards.plan(courses, lessons, "Charts that work", today + 1, today))
        assertNull(StudyAskCards.plan(courses, lessons, "module 9", today, today))
    }

    @Test
    fun askReadsBothKindsFromTheModelAndTheyAreNeverAnEarnedTrustKind() {
        val cal = LocalCalendar.fixedOffset(0L)
        val now = cal.toEpochMs(today, 10 * 60)
        val ctx = AskContext("2026-10-28", "Wednesday 28 October 2026 · 10:00", emptyList(), emptyMap(), untrusted = false)
        fun card(a: AskRawAction) = AskRules.card(a, ctx, emptyMap(), now, cal, studyCourses = courses, studyLessons = lessons)
        assertIs<AskProposal.StudyTick>(card(AskRawAction("study_done", title = "Pivot tables"))?.proposal)
        val plan = card(AskRawAction("plan_lesson", title = "Module 4", date = "2026-10-30"))!!
        assertEquals(listOf("l3", "l4", "l5"), (plan.proposal as AskProposal.StudyPlan).lessonIds)
        assertEquals("Plan", plan.button)
        assertNull(card(AskRawAction("plan_lesson", title = "Module 4", date = "30 Oct")))
        assertNull(card(AskRawAction("plan_lesson", title = "Module 4")))
        assertNull(TrustRules.kindOf(plan.proposal))
        assertEquals("plan Module 4, Reporting, 3 lessons for Friday 30 October", TalkRules.phrase(plan.proposal, today, past = false))
    }
}
