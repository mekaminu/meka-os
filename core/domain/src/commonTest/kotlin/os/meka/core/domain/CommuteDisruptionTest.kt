package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commute counts everywhere, slice 7: tomorrow's trains in trouble offer "wake 10 min earlier?", never moving it. */
class CommuteDisruptionTest {
    // Rule 13: the route comes from About you (an invented one here), never the code.
    @BeforeTest
    fun route() { Household.useRoute(InventedRoute.TYPED) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026
    private val fri = thu + 1
    private val sat = thu + 2
    private val work = WorkHours(WorkSchedule.DEFAULT)
    private val quiet = QuietHours.DEFAULT // 22:00–07:00

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun alarm(d: Long, minute: Int) =
        Alarm(AlarmRules.wakeId(d), AlarmKind.WAKE, d, minute, ms(d, minute), false, null, null, null)
    private fun wakeFor(d: Long, now: Long, set: Int? = null, hours: WorkHours = work) =
        AlarmRules.view(now, cal, d, set?.let { alarm(d, it) }, emptyList(), hours, 60)
    private fun planned(text: String, forDay: Long, read: Long) =
        LineStatusSnapshot(emptyList(), null, LineStatusCodec.decode(text), forDay, read)

    @Test
    fun anOfficeMorningWithTroubleOffersTenMinutesEarlier() {
        val now = ms(thu, 19 * 60)
        val s = planned("metro=10;north-rail=10;south-rail=4", fri, ms(thu, 18 * 60 + 30))
        // No alarm set yet: the suggestion is 06:40, so the offer is 06:30.
        val t = CommuteDisruptionRules.offer(s, wakeFor(fri, now), now, cal)!!
        assertEquals("South Rail planned closure tomorrow morning", t.title)
        assertEquals("North Rail to Riverside is running", t.advice)
        assertEquals(6 * 60 + 30, t.earlierMinute)
        assertEquals("Wake 10 min earlier · 06:30", t.actionLabel)
        assertEquals("Trains · South Rail planned closure tomorrow morning · North Rail to Riverside is running", t.line)
        // With an alarm set at 06:45, ten minutes before that.
        assertEquals(6 * 60 + 35, CommuteDisruptionRules.offer(s, wakeFor(fri, now, set = 6 * 60 + 45), now, cal)!!.earlierMinute)
        // Both legs, in the order travelled; North Rail down too.
        val both = planned("metro=6;north-rail=2;south-rail=2", fri, ms(thu, 18 * 60))
        val b = CommuteDisruptionRules.offer(both, wakeFor(fri, now), now, cal)!!
        assertEquals("South Rail suspended · Metro line severe delays tomorrow morning", b.title)
        assertEquals("North Rail suspended too", b.advice)
        // Only the Metro line: no fallback to name.
        assertEquals("Check before you leave", CommuteDisruptionRules.offer(planned("metro=1;south-rail=10", fri, now), wakeFor(fri, now), now, cal)!!.advice)
    }

    @Test
    fun onceMovedItSaysSoAndOffersNothingMore() {
        val now = ms(thu, 20 * 60)
        val s = planned("south-rail=6", fri, now)
        val t = CommuteDisruptionRules.offer(s, wakeFor(fri, now, set = 6 * 60 + 30), now, cal)!!
        assertNull(t.earlierMinute)
        assertNull(t.actionLabel)
        assertEquals("Trains · South Rail severe delays tomorrow morning · alarm already 10 min early", t.line)
        assertTrue(CommuteDisruptionRules.notices(s, wakeFor(fri, now, set = 6 * 60 + 30), quiet, now, cal).isEmpty())
    }

    @Test
    fun nothingIsOfferedWhenItDoesntApply() {
        val now = ms(thu, 19 * 60)
        val bad = "south-rail=2"
        // Good or minor trouble only.
        assertNull(CommuteDisruptionRules.offer(planned("south-rail=9;metro=10", fri, now), wakeFor(fri, now), now, cal))
        // Only North Rail (not on the usual route).
        assertNull(CommuteDisruptionRules.offer(planned("north-rail=2;south-rail=10", fri, now), wakeFor(fri, now), now, cal))
        // Plans for another day, or read too long ago.
        assertNull(CommuteDisruptionRules.offer(planned(bad, sat, now), wakeFor(fri, now), now, cal))
        assertNull(CommuteDisruptionRules.offer(planned(bad, fri, now - 7 * 60 * min), wakeFor(fri, now), now, cal))
        // Before 17:00 the evening before.
        val early = ms(thu, 16 * 60)
        assertNull(CommuteDisruptionRules.offer(planned(bad, fri, early), wakeFor(fri, early), early, cal))
        // Work from home, annual leave, a weekend: no commute, no offer.
        assertNull(CommuteDisruptionRules.offer(planned(bad, fri, now), wakeFor(fri, now, hours = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri))), now, cal))
        assertNull(CommuteDisruptionRules.offer(planned(bad, fri, now), wakeFor(fri, now, hours = WorkHours(WorkSchedule.DEFAULT, leaveDays = setOf(fri))), now, cal))
        assertNull(CommuteDisruptionRules.offer(planned(bad, sat, ms(fri, 19 * 60)), wakeFor(sat, ms(fri, 19 * 60)), ms(fri, 19 * 60), cal))
        // An event at home before the commute wins the morning: no commute in the wake time.
        val standup = CalendarEvent("s", "Standup", ms(fri, 7 * 60), ms(fri, 7 * 60 + 30), false, null, "google", null, null)
        val v = AlarmRules.view(now, cal, fri, null, listOf(standup), work, 60)
        assertNull(CommuteDisruptionRules.offer(planned(bad, fri, now), v, now, cal))
        // Nothing planned at all.
        assertNull(CommuteDisruptionRules.offer(LineStatusSnapshot.EMPTY, wakeFor(fri, now), now, cal))
    }

    @Test
    fun theHeadsUpComesOnceOutsideQuietHoursWithTheButton() {
        val read = ms(thu, 18 * 60 + 30)
        val s = planned("south-rail=4;north-rail=10", fri, read)
        val now = ms(thu, 18 * 60 + 35)
        val n = CommuteDisruptionRules.notices(s, wakeFor(fri, now), quiet, now, cal).single()
        assertEquals("wake-trains:$fri", n.key)
        assertEquals(NoticeSource.TRAINS, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("South Rail planned closure tomorrow morning", n.title)
        assertEquals("Wake at 06:30 instead of 06:40? · North Rail to Riverside is running", n.text)
        assertEquals(read, n.atMs)
        assertEquals(ms(fri, 6 * 60 + 30), n.expiresAtMs)
        assertEquals(listOf(NoticeAction.WAKE_EARLIER), n.actions)
        assertEquals("Wake 10 min earlier", NotifyRules.actionLabel(NoticeAction.WAKE_EARLIER))
        assertEquals(listOf(NoticeAction.WAKE_EARLIER), NotifyRules.actionsOn(n, send = false)) // the Mac shows it too
        assertTrue(CommuteDisruptionRules.isKey(n.key))
        // First read inside quiet hours: held to 07:00, which is after the alarm, so nothing wakes him.
        val late = ms(thu, 22 * 60 + 30)
        assertTrue(CommuteDisruptionRules.notices(planned("south-rail=4", fri, late), wakeFor(fri, late), quiet, late, cal).isEmpty())
        // Through the notice sources, with the wake view.
        val all = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), now, cal, lines = s, wake = wakeFor(fri, now),
        )
        assertEquals(listOf("wake-trains:$fri"), all.filter { it.source == NoticeSource.TRAINS }.map { it.key })
    }

    @Test
    fun plannedStatusReadsFromTheMirror() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, LineStatusStore.ENTITY_ID, mapOf(
            LineStatusFields.PLANNED to os.meka.core.sync.FieldValue.Text("metro=10;south-rail=4"),
            LineStatusFields.PLANNED_DAY to os.meka.core.sync.FieldValue.Int64(fri),
            LineStatusFields.PLANNED_CHECKED to os.meka.core.sync.FieldValue.Int64(ms(thu, 18 * 60)),
        ))
        a.syncWithRetry(); m.syncWithRetry()
        val s = LineStatusStore(m.replica).snapshot()
        assertEquals(4, s.plannedSeverityOf(InventedRoute.FIRST))
        assertEquals(fri, s.plannedDay)
        assertEquals(ms(thu, 18 * 60), s.plannedCheckedMs)
        assertTrue(s.lines.isEmpty())
    }
}
