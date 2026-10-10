package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreventedMissesTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private fun now() = world.clock.nowMs

    private val a = world.device("android")
    private val m = world.device("mac")
    private val log = ActivityLog(a.replica, { "x${n++}" }, ::now)
    private val tasks = Tasks(a.replica, { "t${n++}" }, ::now)

    /** Monday of the week the tests run in (Thursday 10:00 UTC). */
    private val monday: Long

    init {
        val today = world.clock.nowMs.floorDiv(dayMs)
        val thursday = today + (11 - CivilDate.isoDayOfWeek(today)) % 7 + 7
        world.clock.nowMs = thursday * dayMs + 10 * hourMs
        monday = thursday - 3
    }

    private fun at(day: Long, h: Int) = day * dayMs + h * hourMs
    private fun r(about: String, atMs: Long) = PreventedMissRules.Reminder(about, atMs)
    private fun f(ref: String, doneAt: Long, due: Long? = null) = PreventedMissRules.Finished(ref, doneAt, due)
    private val weekStart get() = at(monday, 0)
    private val weekEnd get() = at(monday + 7, 0)

    // ---- Rules ----

    @Test
    fun aboutComesFromTheNoticeKeyForTasksAndCancelByDatesOnly() {
        assertEquals("task:t1", PreventedMissRules.aboutOf("task:t1:remind:12345"))
        assertEquals("task:s1.d20000", PreventedMissRules.aboutOf("task:s1.d20000:remind:12345"))
        assertEquals("renewal:r9:20400", PreventedMissRules.aboutOf("renewal:r9:cancel:20400"))
        assertNull(PreventedMissRules.aboutOf("renewal:r9:20400")) // the digest line, not a cancel-by heads-up
        assertNull(PreventedMissRules.aboutOf("event:e1:123:remind:10"))
        assertNull(PreventedMissRules.aboutOf("shutdown:20400"))
        assertNull(PreventedMissRules.aboutOf("renewal:r9:cancel:soon"))
    }

    @Test
    fun aTaskCountsWhenDoneAfterItsReminderAndByItsDueTime() {
        val due = at(monday + 2, 17)
        val week = PreventedMissRules.count(
            listOf(r("task:a", at(monday + 2, 9)), r("task:late", at(monday + 1, 9)), r("task:early", at(monday + 2, 12)), r("task:nodue", at(monday, 9))),
            listOf(
                f("task:a", at(monday + 2, 11), due), // counts
                f("task:late", at(monday + 2, 18), due), // done after it was due
                f("task:early", at(monday + 2, 11), due), // done before the reminder reached you
                f("task:nodue", at(monday, 10)), // no due time
                f("task:unreminded", at(monday, 10), due), // no reminder
            ),
            weekStart, weekEnd, LocalCalendar.UTC,
        )
        assertEquals(PreventedMissRules.Week(1, 0), week)
    }

    @Test
    fun aRenewalCountsWhenSortedByTheEndOfItsCancelByDay() {
        val cancelBy = monday + 3
        val reminder = r("renewal:r1:$cancelBy", at(cancelBy - 1, 9))
        fun count(doneAt: Long) = PreventedMissRules.count(listOf(reminder), listOf(f("renewal:r1", doneAt)), weekStart, weekEnd, LocalCalendar.UTC)
        assertEquals(1, count(at(cancelBy, 23)).renewals)
        assertEquals(0, count(at(cancelBy + 1, 0)).renewals) // too late
        assertEquals(0, count(at(cancelBy - 1, 8)).renewals) // before the heads-up
    }

    @Test
    fun onlyTheWeekItWasDoneInCountsIt() {
        val due = at(monday + 8, 17)
        val reminders = listOf(r("task:a", at(monday + 6, 9)))
        val finished = listOf(f("task:a", at(monday + 7, 9), due))
        assertEquals(0, PreventedMissRules.count(reminders, finished, weekStart, weekEnd, LocalCalendar.UTC).total)
        assertEquals(1, PreventedMissRules.count(reminders, finished, weekEnd, weekEnd + 7 * dayMs, LocalCalendar.UTC).total)
    }

    @Test
    fun metricSaysWhenCountingStarts() {
        val none = PreventedMissRules.Week(0, 0)
        assertNull(PreventedMissRules.metric(monday, null, none).value)
        assertEquals(PreventedMissRules.PENDING_LINE, PreventedMissRules.metric(monday, null, none).line)
        val earlier = PreventedMissRules.metric(monday - 7, monday + 2, none)
        assertNull(earlier.value)
        assertEquals("Counted from ${CivilDate.shortLabel(monday + 2)}", earlier.line)
        val zero = PreventedMissRules.metric(monday, monday - 30, none)
        assertEquals("0", zero.value)
        assertEquals("Nothing a reminder caught in time this week", zero.line)
        val some = PreventedMissRules.metric(monday, monday + 1, PreventedMissRules.Week(2, 1))
        assertEquals("3", some.value)
        assertEquals(
            "2 tasks done before they were due · 1 renewal sorted by the cancel-by date · counting since ${CivilDate.shortLabel(monday + 1)}",
            some.line,
        )
        assertEquals("1 task done before it was due", PreventedMissRules.metric(monday, monday - 7, PreventedMissRules.Week(1, 0)).line)
    }

    // ---- On a replica ----

    private fun notice(key: String, source: NoticeSource) =
        Notice(key, source, NoticeTier.HEADS_UP, "Pay the window cleaner", "Due Thu", now(), NoticeTarget.TODAY)

    @Test
    fun theLogKeepsWhatAReminderWasAboutAndTheReviewCountsIt() {
        val id = tasks.create(NewTask(title = "Pay the window cleaner", dueAtMs = at(monday + 3, 17)))
        log.recordPosted(listOf(notice("task:$id:remind:${now()}", NoticeSource.TASK_REMINDER), notice("brief:1", NoticeSource.BRIEF)))
        val reminded = log.items().filter { it.kind == ActivityKind.REMINDED }
        assertEquals(setOf("task:$id", null), reminded.map { it.about }.toSet())

        world.clock.nowMs += hourMs
        tasks.complete(id)
        val review = WeeklyReview(a.replica, ::now)
        val v = review.view(0, tasks.all(), emptyList(), GoalsView.EMPTY, emptyList()) { DayWindow(it * dayMs, (it + 1) * dayMs) }
        val metric = v.northStar.single { it.key == PreventedMissRules.KEY }
        assertEquals("1", metric.value)
        assertTrue(metric.line.startsWith("1 task done before it was due"))
    }

    @Test
    fun theOtherDeviceCountsTheSameAfterSync() {
        val due = at(monday + 3, 17)
        val id = tasks.create(NewTask(title = "Renew passport", dueAtMs = due))
        log.recordPosted(listOf(notice("task:$id:remind:${now()}", NoticeSource.TASK_REMINDER)))
        world.clock.nowMs += hourMs
        tasks.complete(id)
        a.syncWithRetry(); m.syncWithRetry()
        val onMac = Tasks(m.replica, { "m${n++}" }, ::now).all()
        assertEquals("1", PreventedMisses(m.replica).metric(monday, onMac).value)
    }

    @Test
    fun aRenewalSortedAfterItsHeadsUpCounts() {
        val cancelBy = monday + 4
        a.replica.commitLocal(
            EntityTypes.OBLIGATION, "r1",
            mapOf(ActionableFields.TITLE to "Netflix".fv(), ObligationFields.CANCEL_BY_AT to at(cancelBy, 9).fv()),
        )
        log.recordPosted(listOf(notice("renewal:r1:cancel:$cancelBy", NoticeSource.RENEWAL_CANCEL_BY)))
        world.clock.nowMs += hourMs
        a.replica.commitLocal(EntityTypes.OBLIGATION, "r1", mapOf(ObligationFields.LAST_DONE_AT to now().fv()))
        val metric = PreventedMisses(a.replica).metric(monday, emptyList())
        assertEquals("1", metric.value)
        assertTrue(metric.line.startsWith("1 renewal sorted by the cancel-by date"))
    }
}
