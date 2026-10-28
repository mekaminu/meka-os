package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.meka.core.testing.SyncWorld

/** Study plan, slice 2b part 2: THIS WEEK, with Start and Done on a session ticking its lessons. */
class StudyWeekTest {
    private val mon = 20752L // Mon 26 Oct 2026
    private val day = CivilDate.DAY_MS
    private val dayOf: (Long) -> Long = { it.floorDiv(day) }
    private val timeOf: (Long) -> String = { LocalClock.formatMinute(((it.mod(day)) / 60_000L).toInt()) }

    private val course = StudyCourse("c1", "Data analytics certificate", 0L, sessionMins = 60, slots = setOf("train", "sat"))

    private fun lesson(id: String, mins: Int?, pos: Long, done: Boolean = false, doneAt: Long? = null, planned: Long? = null) =
        StudyLesson(id, "c1", "1 · Foundations", "Lesson $id", mins, pos, done, doneAt, planned)

    private fun event(id: String, title: String, d: Long, h: Int, m: Int, mins: Int) =
        CalendarEvent(id, title, d * day + (h * 60 + m) * 60_000L, d * day + (h * 60 + m + mins) * 60_000L, false, null, "google", "alex@x", "Personal")

    @Test
    fun thePickedTimesGiveAWeekOfSessionsWithTheirTimesAndLessons() {
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 30, 2), lesson("c", 45, 3), lesson("d", 60, 4))
        val (s, _) = StudyAutoRules.sessions(listOf(course), lessons, emptyList(), mon, dayOf, { d -> CivilDate.isoDayOfWeek(d) <= 3 }, { null })
        assertEquals(listOf(7 * 60 + 45, 7 * 60 + 45, 7 * 60 + 45, 9 * 60), s.take(4).map { it.startMin })
        assertEquals("Train morning", s.first().label)
        val suggested = StudyAutoRules.suggest(listOf(course), lessons, s, mon, dayOf)
        val rows = StudyWeekRules.rows("c1", suggested, s, mon, emptyMap(), dayOf, timeOf)
        // Mon, Tue, Wed trains and Saturday; next Monday is past the week.
        assertEquals(listOf("Today", "Tue", "Wed", "Sat"), rows.map { it.dayLabel })
        assertEquals("07:45", rows.first().time)
        assertEquals("Lesson a + 1 more · 1 h", rows[0].lessons)
        assertEquals("Lesson c · 45 min", rows[1].lessons)
        assertEquals("Lesson d · 1 h", rows[2].lessons)
        assertEquals(listOf("a", "b"), rows[0].lessonIds)
        assertEquals("Train morning", rows[0].place)
        assertEquals(StudyWeekRules.START, rows[0].button)
        // Nothing left for Saturday: a catch-up with no button.
        assertTrue(rows[3].catchUp); assertNull(rows[3].button)
        assertEquals("Catch-up · 1 h", rows[3].lessons)
        assertEquals(StudyWeekRules.CATCH_UP_LINE, rows[3].place)
        assertEquals("c1:$mon", rows[0].key)
        assertEquals(mon, StudyWeekRules.dayOf(rows[0].key)); assertEquals("c1", StudyWeekRules.courseOf(rows[0].key))
    }

    @Test
    fun aCalendarSessionKeepsItsTimeAndTitleAndTwoOnADayAreOneRow() {
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 30, 2), lesson("c", 30, 3))
        val (s, cal) = StudyAutoRules.sessions(
            listOf(course), lessons,
            listOf(event("e1", "Study · Data analytics", mon + 1, 19, 0, 30), event("e2", "Study · Data analytics", mon + 1, 7, 15, 30)),
            mon, dayOf, { false }, { ms -> ((ms.mod(day)) / 60_000L).toInt() },
        )
        assertTrue(cal)
        val rows = StudyWeekRules.rows("c1", StudyAutoRules.suggest(listOf(course), lessons, s, mon, dayOf), s, mon, emptyMap(), dayOf, timeOf)
        val r = rows.single()
        assertEquals("Tue", r.dayLabel); assertEquals("07:15", r.time)
        assertEquals("Lesson a + 1 more · 1 h", r.lessons)
        assertEquals("Study · Data analytics", r.place)
    }

    @Test
    fun startThenDoneAndADoneSessionIsQuiet() {
        val s = listOf(StudySession(mon, 60, "c1", 7 * 60 + 45, "Train morning"))
        val lessons = listOf(lesson("a", 30, 1), lesson("b", 30, 2))
        val sug = StudyAutoRules.suggest(listOf(course), lessons, s, mon, dayOf)
        val started = mapOf("c1:$mon" to mon * day + (14 * 60 + 5) * 60_000L)
        val on = StudyWeekRules.rows("c1", sug, s, mon, started, dayOf, timeOf).single()
        assertEquals(StudyWeekState.ON, on.state); assertEquals(StudyWeekRules.DONE, on.button); assertEquals("Started 14:05", on.note)
        assertEquals("Today, 07:45, Lesson a + 1 more, 1 h, Train morning, Started 14:05", on.spoken)
        // Both ticked today: the session shows done.
        val ticked = lessons.map { it.copy(done = true, doneAtMs = mon * day + 15 * 3_600_000L) }
        val done = StudyWeekRules.rows("c1", StudyAutoRules.suggest(listOf(course), ticked, s, mon, dayOf), s, mon, started, dayOf, timeOf).single()
        assertEquals(StudyWeekState.DONE, done.state); assertEquals(StudyWeekRules.DONE, done.button)
        assertNull(done.note); assertTrue(done.lessonIds.isEmpty())
        assertEquals("Lesson a + 1 more · 1 h", done.lessons)
        assertEquals("Ticked 2 lessons", StudyWeekRules.doneSaid(listOf("a", "b"), mon, mon))
        assertEquals("Ticked “Pivot tables” · done ahead of Thu", StudyWeekRules.doneSaid(listOf("Pivot tables"), mon + 3, mon))
    }

    @Test
    fun doneTicksTheSessionsLessonsOnBothDevicesAndUndoPutsThemBack() {
        val world = SyncWorld()
        var n = 0
        val fold = world.device("android"); val mac = world.device("mac")
        world.clock.nowMs = 1_793_008_800_000L
        val sFold = Study(fold.replica, { "s${n++}" }, { world.clock.nowMs })
        val sMac = Study(mac.replica, { "s${n++}" }, { world.clock.nowMs })
        val id = assertNotNull(sFold.addCourse("Data analytics\nWelcome 30 min\nBasics 30 min\nCharts 30 min").courseId)
        val ls = sFold.lessons().filter { it.courseId == id }
        assertTrue(sFold.planLesson(ls[2].id, mon + 4))
        // Done on a session three days ahead: the two are ticked and planned for today, where they were done.
        val token = assertNotNull(sFold.finishSession(listOf(ls[0].id, ls[1].id), planFor = mon))
        assertNull(sFold.finishSession(listOf(ls[0].id), planFor = null))
        fold.sync(); mac.sync()
        val onMac = sMac.lessons().associateBy { it.id }
        assertTrue(onMac.getValue(ls[0].id).done); assertEquals(mon, onMac.getValue(ls[0].id).plannedDay)
        assertFalse(onMac.getValue(ls[2].id).done)
        assertTrue(sMac.undoFinish(token))
        mac.sync(); fold.sync()
        val back = sFold.lessons().associateBy { it.id }
        assertFalse(back.getValue(ls[0].id).done); assertNull(back.getValue(ls[0].id).plannedDay)
        assertEquals(mon + 4, back.getValue(ls[2].id).plannedDay)
    }
}
