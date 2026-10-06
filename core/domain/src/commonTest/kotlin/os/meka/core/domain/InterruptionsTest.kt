package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterruptionsTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private fun now() = world.clock.nowMs

    private val a = world.device("android")
    private val m = world.device("mac")
    private val ia = Interruptions(a.replica)
    private val im = Interruptions(m.replica)

    /** Monday of the week the tests start in (Thursday 10:00 UTC). */
    private val monday: Long

    init {
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toThursday = (11 - CivilDate.isoDayOfWeek(today)) % 7
        val thursday = today + toThursday + 7
        world.clock.nowMs = thursday * dayMs + 10 * hourMs
        monday = thursday - 3
    }

    private fun at(day: Long, h: Int) = day * dayMs + h * hourMs
    private fun notice(key: String, tier: NoticeTier) =
        Notice(key, NoticeSource.SHUTDOWN, tier, "Secret title", "secret text", now(), NoticeTarget.TODAY)

    // ---- Rules ----

    @Test
    fun onlyTheInterruptingTiersCount() {
        val posted = listOf(
            notice("1", NoticeTier.CRITICAL), notice("2", NoticeTier.ACTION), notice("3", NoticeTier.HEADS_UP),
            notice("4", NoticeTier.HEADS_UP), notice("5", NoticeTier.DIGEST), notice("6", NoticeTier.SILENT),
        )
        assertEquals(Triple(1, 1, 2), InterruptionRules.counts(posted))
    }

    @Test
    fun metricSaysWhenCountingStarts() {
        val zero = InterruptionWeek(0, 0, 0, null)
        val never = InterruptionRules.metric(monday, null, zero)
        assertNull(never.value)
        assertEquals("Counted once MEKA can notify you on a device", never.line)
        val later = InterruptionRules.metric(monday - 7, monday + 1, zero)
        assertNull(later.value)
        assertEquals("Counted from ${CivilDate.shortLabel(monday + 1)}", later.line)
        val partial = InterruptionRules.metric(monday, monday + 1, InterruptionWeek(0, 0, 3, null))
        assertEquals("3", partial.value)
        assertEquals("3 heads-ups · counting since ${CivilDate.shortLabel(monday + 1)}", partial.line)
    }

    @Test
    fun metricComparesWithTheWeekBefore() {
        assertEquals("1 critical · 2 needed a decision · 1 heads-up · 3 fewer than the week before",
            InterruptionRules.metric(monday, monday - 14, InterruptionWeek(1, 2, 1, 7)).line)
        assertEquals("1 heads-up · 1 more than the week before", InterruptionRules.metric(monday, monday - 14, InterruptionWeek(0, 0, 1, 0)).line)
        assertEquals("2 heads-ups · the same as the week before", InterruptionRules.metric(monday, monday - 14, InterruptionWeek(0, 0, 2, 2)).line)
        val none = InterruptionRules.metric(monday, monday - 14, InterruptionWeek(0, 0, 0, 0))
        assertEquals("0", none.value)
        assertEquals("None reached you", none.line)
    }

    // ---- Recording ----

    @Test
    fun recordingStartsTheCountEvenWithNothingPosted() {
        assertNull(ia.countingSince())
        ia.record(emptyList(), now())
        assertEquals(monday + 3, ia.countingSince())
        assertEquals(0, ia.week(monday).total)
        // A second empty call writes nothing.
        val before = a.replica.pendingPushCount()
        ia.record(listOf(notice("d", NoticeTier.DIGEST)), now())
        assertEquals(before, a.replica.pendingPushCount())
    }

    @Test
    fun countsAddUpByDayAndNeverStoreText() {
        ia.record(listOf(notice("1", NoticeTier.HEADS_UP)), now())
        ia.record(listOf(notice("2", NoticeTier.HEADS_UP), notice("3", NoticeTier.CRITICAL)), now() + hourMs)
        world.clock.nowMs = at(monday + 4, 9)
        ia.record(listOf(notice("4", NoticeTier.ACTION)), now())
        val w = ia.week(monday)
        assertEquals(InterruptionWeek(1, 1, 2, null), w)
        val stored = a.replica.entities(EntityTypes.INTERRUPTION_DAY)
        assertEquals(2, stored.size)
        assertTrue(stored.none { s -> s.fields.values.any { it.textOrNull?.contains("ecret") == true } })
        // The week after doesn't see them.
        assertEquals(0, ia.week(monday + 7).total)
    }

    @Test
    fun bothDevicesCountAndSyncWithoutConflicts() {
        ia.record(listOf(notice("1", NoticeTier.HEADS_UP), notice("2", NoticeTier.HEADS_UP)), now())
        im.record(listOf(notice("1", NoticeTier.HEADS_UP)), now())
        a.syncWithRetry(); m.syncWithRetry(); a.syncWithRetry()
        assertEquals(3, ia.week(monday).total)
        assertEquals(3, im.week(monday).total)
        assertEquals(ia.countingSince(), im.countingSince())
        assertTrue(a.replica.conflicts(EntityTypes.CONTEXT_MODE, EntityTypes.INTERRUPTION_DAY).isEmpty())
    }

    @Test
    fun weekBeforeComparesOnlyWhenItWasCountedInFull() {
        world.clock.nowMs = at(monday - 7, 9) // the Monday before
        ia.record(listOf(notice("1", NoticeTier.HEADS_UP), notice("2", NoticeTier.HEADS_UP)), now())
        world.clock.nowMs = at(monday + 1, 9)
        ia.record(listOf(notice("3", NoticeTier.HEADS_UP)), now())
        assertEquals(2, ia.week(monday).before)
        assertEquals("1", ia.metric(monday).value)
        assertEquals("1 heads-up · 1 fewer than the week before", ia.metric(monday).line)
    }

    @Test
    fun theReviewShowsInterruptions() {
        ia.record(listOf(notice("1", NoticeTier.HEADS_UP)), now())
        val review = WeeklyReview(a.replica, ::now)
        val v = review.view(0, emptyList(), emptyList(), GoalsView.EMPTY, emptyList()) { DayWindow(it * dayMs, (it + 1) * dayMs) }
        val metric = v.northStar.single { it.key == "interruptions" }
        assertEquals("1", metric.value)
        assertTrue(metric.line.startsWith("1 heads-up"))
        assertEquals(5, v.northStar.count { it.value == null })
    }
}
