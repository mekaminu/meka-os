package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Study plan, slice 2b rest: the 10-minute nudge card on Today. */
class StudyNudgeTest {
    private val mon = 20752L // Mon 26 Oct 2026
    private val day = CivilDate.DAY_MS
    private val dayOf: (Long) -> Long = { it.floorDiv(day) }
    private val timeOf: (Long) -> String = { LocalClock.formatMinute(((it.mod(day)) / 60_000L).toInt()) }

    private val course = StudyCourse("c1", "Data analytics", 0L, sessionMins = 60, slots = setOf("train"))

    private fun lesson(id: String, mins: Int?, pos: Long, done: Boolean = false) =
        StudyLesson(id, "c1", "1 · Foundations", "Lesson $id", mins, pos, done, null, null)

    private fun week(lessons: List<StudyLesson>, started: Map<String, Long> = emptyMap(), c: StudyCourse = course): Map<String, List<StudyWeekRow>> {
        val (s, _) = StudyAutoRules.sessions(listOf(c), lessons, emptyList(), mon, dayOf, { true }, { null })
        val suggested = StudyAutoRules.suggest(listOf(c), lessons, s, mon, dayOf)
        return mapOf("c1" to StudyWeekRules.rows("c1", suggested, s, mon, started, dayOf, timeOf))
    }

    private val lessons = listOf(lesson("a", 30, 1), lesson("b", 30, 2), lesson("c", 45, 3))

    @Test
    fun itShowsFromTenMinutesBeforeTheTrainSessionUntilFifteenAfter() {
        val w = week(lessons)
        val start = 7 * 60 + 45 // Train morning
        assertNull(StudyNudgeRules.nudge(listOf(course), w, mon, start - 11, emptySet()))
        val n = assertNotNull(StudyNudgeRules.nudge(listOf(course), w, mon, start - 10, emptySet()))
        assertEquals("Study in 10 min · Data analytics", n.headline)
        assertEquals("07:45 · Lesson a + 1 more · 1 h · Train morning", n.line)
        assertEquals(StudyWeekRules.START, n.button)
        assertEquals("c1:$mon", n.key)
        assertEquals("Study now · Data analytics", StudyNudgeRules.nudge(listOf(course), w, mon, start + 14, emptySet())?.headline)
        assertNull(StudyNudgeRules.nudge(listOf(course), w, mon, start + 15, emptySet()))
        // Put away, or the nudge turned off: nothing.
        assertNull(StudyNudgeRules.nudge(listOf(course), w, mon, start - 5, setOf(n.key)))
        assertNull(StudyNudgeRules.nudge(listOf(course.copy(nudge = false)), w, mon, start - 5, emptySet()))
    }

    @Test
    fun startedItStaysWithDoneUntilFifteenMinutesAfterItsEnd() {
        val key = StudyWeekRules.key("c1", mon)
        val startedAt = mon * day + (7 * 60 + 40) * 60_000L
        val w = week(lessons, mapOf(key to startedAt))
        val start = 7 * 60 + 45
        val on = assertNotNull(StudyNudgeRules.nudge(listOf(course), w, mon, start + 60 + 14, emptySet()))
        assertEquals("Studying · Data analytics", on.headline)
        assertEquals("Started 07:40 · Lesson a + 1 more · 1 h · Train morning", on.line)
        assertEquals(StudyWeekRules.DONE, on.button)
        assertNull(StudyNudgeRules.nudge(listOf(course), w, mon, start + 60 + 15, emptySet()))
    }

    @Test
    fun aSessionWithNothingLeftInItHasNoNudge() {
        val done = lessons.map { it.copy(done = true) }
        assertNull(StudyNudgeRules.nudge(listOf(course), week(done), mon, 7 * 60 + 40, emptySet()))
    }
}
