package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, item (11): "How are you feeling?" — Tired lightens tomorrow, Stretched also suggests what can wait. */
class BoardFeelingTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 19:00 UTC: the evening.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 19 * 3_600_000L
    private val thu = today + 1
    private val fri = today + 2
    private val sat = today + 3

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun task(
        id: String, title: String = id, due: Long? = null, scheduled: Long? = null, deferred: Long? = null, priority: Int = 0,
        recurrence: String? = null,
    ) = Task(id, title, null, Lifecycle.ACTIVE, due, scheduled, null, priority, null, null, 0, null, false, recurrenceRule = recurrence, deferredToDay = deferred)

    private fun board(tasks: List<Task>) = WeekBoardRules.build(tasks, emptyList(), now, cal)

    @Test
    fun goodChangesNothing() {
        val o = FeelingRules.offer(board(listOf(task("a", scheduled = at(thu, 10)))), Feeling.GOOD, today, cal)
        assertEquals("Good to hear. Tomorrow stays as it is.", o.line)
        assertTrue(o.moves.isEmpty())
        assertNull(o.lightenLabel)
    }

    @Test
    fun tiredMovesTwoOffTomorrowLateOnesFirstToTheLightestDays() {
        val tasks = listOf(
            task("mot", "Book MOT", scheduled = at(thu, 10), priority = 2),
            task("gym", "Gym", scheduled = at(thu, 20)),
            task("call", "Call bank", scheduled = at(thu, 12), priority = 1),
            task("form", "Trip form", due = at(thu, 17)),
            // Friday already has two; Saturday none.
            task("f1", scheduled = at(fri, 9)), task("f2", scheduled = at(fri, 11)),
        )
        val o = FeelingRules.offer(board(tasks), Feeling.TIRED, today, cal)
        // The 20:00 one first, then the lowest priority; the one due tomorrow stays.
        assertEquals(listOf("gym", "call"), o.moves.map { it.taskId })
        assertEquals(listOf(sat, sat + 1), o.moves.map { it.toDay })
        assertEquals("Let's go easy tomorrow: move “Gym” to Sat and “Call bank” to Sun?", o.line)
        assertEquals("Lighten tomorrow", o.lightenLabel)
        assertEquals("Tomorrow's lighter · “Gym” to Sat and “Call bank” to Sun", FeelingRules.lightenedLine(o.moves))
        assertNull(o.drop)
    }

    @Test
    fun neverPastItsDueDayAndRepeatsStay() {
        val tasks = listOf(
            task("rpt", "Stretch", scheduled = at(thu, 8), recurrence = "FREQ=DAILY"),
            task("dueFri", "Return parcel", scheduled = at(thu, 9), due = at(fri, 17)),
            task("x", scheduled = at(fri, 9)),
        )
        val o = FeelingRules.offer(board(tasks), Feeling.TIRED, today, cal)
        // Due Friday: only Friday will do, even though it has a card already.
        assertEquals(listOf(FeelingMove("dueFri", "Return parcel", fri, "Fri")), o.moves)
    }

    @Test
    fun stretchedAlsoSuggestsWhatCanWait() {
        val tasks = listOf(
            task("a", "Gym", scheduled = at(thu, 20)),
            task("b", "Call bank", scheduled = at(thu, 12), priority = 2),
            task("c", "Clean garage", deferred = thu),
            task("d", "Tax return", scheduled = at(thu, 15), priority = 3),
        )
        val o = FeelingRules.offer(board(tasks), Feeling.STRETCHED, today, cal)
        assertEquals(listOf("a", "c"), o.moves.map { it.taskId })
        assertEquals("b", o.drop?.taskId)
        assertEquals("Someday for “Call bank”", o.dropLabel)
        assertTrue(o.line.startsWith("Let's take some off tomorrow: move “Gym” to "))
        assertTrue(o.line.endsWith("And “Call bank” could wait on Someday."))
    }

    @Test
    fun aLightTomorrowSaysSo() {
        val o = FeelingRules.offer(board(emptyList()), Feeling.TIRED, today, cal)
        assertEquals("Tomorrow is light already. Rest well.", o.line)
        val due = FeelingRules.offer(board(listOf(task("f", "Trip form", scheduled = at(thu, 9), due = at(thu, 17)))), Feeling.TIRED, today, cal)
        assertEquals("Tomorrow has nothing that can move. Rest well.", due.line)
    }

    @Test
    fun theLogStaysOnTheDeviceForAWeek() {
        var log = FeelingRules.record(emptyMap(), today - 8, Feeling.TIRED)
        log = FeelingRules.record(log, today - 2, Feeling.GOOD)
        log = FeelingRules.record(log, today, Feeling.STRETCHED)
        assertEquals(mapOf(today - 2 to Feeling.GOOD, today to Feeling.STRETCHED), log)
        val text = FeelingRules.encode(log)
        assertEquals("${today - 2}:GOOD;$today:STRETCHED", text)
        assertEquals(log, FeelingRules.decode(text))
        assertEquals(mapOf(today to Feeling.GOOD), FeelingRules.decode("x;$today:GOOD;5:NOPE"))
        assertEquals("Tired · kept on this device for a week", FeelingRules.pickedLine(Feeling.TIRED))
    }
}
