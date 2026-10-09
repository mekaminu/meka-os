package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-09 07:26, item 2: the Day ring becomes a 12-hour watch face with the next 12 hours on its rim. */
class WatchFaceTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h): local midnight = 5 Oct 23:00 UTC.
    private val start = 1_791_244_800_000L - hour
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun near(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "expected $expected, was $actual")

    private fun ev(id: String, from: Long, to: Long, title: String = id, provider: String = "google", allDay: Boolean = false) =
        CalendarEvent(id, title, from, to, allDay, null, provider, null, null)

    @Test
    fun atTwentyOneMinutesPastSevenTheHandsReadTheTime() {
        // The 24-hour dial put 07:21 near "4 o'clock"; the face's hour hand sits just past 7, the minute hand at 21.
        val h = WatchFaceRules.hands(7 * 60 + 21, 0, 0)
        near(7 * 30f + 21 * 0.5f, h.hourDegrees)
        near(21 * 6f, h.minuteDegrees)
        // 19:21 reads the same on a 12-hour face; midnight and noon are at the top.
        near(h.hourDegrees, WatchFaceRules.hands(19 * 60 + 21, 0, 0).hourDegrees)
        near(0f, WatchFaceRules.hands(0, 0, 0).hourDegrees)
        near(0f, WatchFaceRules.hands(12 * 60, 0, 0).hourDegrees)
        // The hands creep between minutes: half a minute on moves the minute hand 3°.
        near(21 * 6f + 3f, WatchFaceRules.hands(7 * 60 + 21, 30_000, 0).minuteDegrees)
    }

    @Test
    fun theHandsFollowTheLocalClockAndTheSecondHandSweeps() {
        val h = WatchFaceRules.handsAt(at(7, 21) + 15_000, hour)
        near(21 * 6f + 1.5f, h.minuteDegrees)
        near(90f, h.secondDegrees)
    }

    @Test
    fun twelveMarkersWithTwelveThreeSixAndNineHeavier() {
        val m = WatchFaceRules.markers()
        assertEquals(12, m.size)
        assertEquals(listOf(12, 3, 6, 9), m.filter { it.major }.map { it.hour })
        near(90f, m[3].degrees)
    }

    @Test
    fun theRimShowsTheNextTwelveHoursAtTheirClockPlaces() {
        val face = WatchFaceRules.build(
            listOf(
                ev("standup", at(9, 30), at(9, 45)),
                ev("over", at(6), at(7)),
                ev("tonight", at(20), at(21)), // 20:00 is past the 12 hours from 07:21
                ev("holiday", at(0), at(24), allDay = true),
            ),
            emptyList(), emptyList(), at(7, 21), cal,
        )
        assertEquals(listOf("e-standup"), face.arcs.map { it.id })
        near(9 * 30f + 15f, face.arcs.single().startDegrees) // half past nine
        near(7.5f, face.arcs.single().sweepDegrees) // 15 minutes on a 12-hour dial
        assertEquals("standup 09:30", face.next)
        assertEquals(1, face.upcoming)
    }

    @Test
    fun theWindowCrossesMidnight() {
        // At 20:00 tomorrow's 07:00 standup is on the rim at 7 o'clock; 08:30 tomorrow is past the window.
        val face = WatchFaceRules.build(
            listOf(ev("standup", at(24 + 7), at(24 + 7, 15)), ev("late", at(24 + 8, 30), at(24 + 9))),
            emptyList(), emptyList(), at(20), cal,
        )
        assertEquals(listOf("e-standup"), face.arcs.map { it.id })
        near(210f, face.arcs.single().startDegrees)
    }

    @Test
    fun somethingOnNowStartsAtNowAndGlows() {
        val face = WatchFaceRules.build(listOf(ev("call", at(10), at(11))), emptyList(), emptyList(), at(10, 30), cal)
        val arc = face.arcs.single()
        assertTrue(arc.current)
        near(10 * 30f + 15f, arc.startDegrees)
        near(15f, arc.sweepDegrees)
        assertNull(face.next) // nothing still to start
    }

    @Test
    fun anEventRunningPastTheWindowIsClipped() {
        val face = WatchFaceRules.build(listOf(ev("trip", at(8), at(23))), emptyList(), emptyList(), at(7), cal)
        near(330f, face.arcs.single().sweepDegrees) // 08:00 to 19:00 (the window's end): 11 hours
    }

    @Test
    fun gymFixturesAndTrainingStandOut() {
        val gym = BookedSession("gym", "Gym", "Push", 0, at(17, 45), at(18, 45), "Today 17:45")
        val face = WatchFaceRules.build(
            listOf(
                ev("barca", at(15), at(17), "Barça v Getafe", provider = "fixtures"),
                ev("kids", at(18), at(19), "Training - 3G"),
                ev("dentist", at(12), at(13), "Dentist"),
            ),
            emptyList(), listOf(gym), at(9), cal,
        )
        assertEquals(mapOf("e-dentist" to false, "e-barca" to true, "s-gym" to true, "e-kids" to true),
            face.arcs.associate { it.id to it.highlighted })
        assertTrue(WatchFaceRules.isTraining("U12 training"))
        assertTrue(!WatchFaceRules.isTraining("Trainingsplan"))
    }

    @Test
    fun plannedOpenTasksAndWorkAreOnTheRim() {
        val task = Task("t1", "Write report", null, Lifecycle.ACTIVE, null, at(14), 60, 0, null, null, 0, null, false)
        val done = task.copy(id = "t2", lifecycle = Lifecycle.DONE)
        val work = listOf(WorkBlock(at(9), at(17, 30), 9 * 60, 17 * 60 + 30))
        val face = WatchFaceRules.build(emptyList(), listOf(task, done), emptyList(), at(10), cal, work + work)
        assertEquals(listOf("t-t1"), face.arcs.map { it.id })
        near(30f, face.arcs.single().sweepDegrees)
        val band = face.work.single() // the same block twice is one band
        assertTrue(band.current)
        near(300f, band.startDegrees) // from now, 10 o'clock
        near(225f, band.sweepDegrees) // to 17:30
    }

    @Test
    fun aShortCallStillShowsAndTheLineIsSpoken() {
        val face = WatchFaceRules.build(listOf(ev("ping", at(11), at(11, 2), "Quick call")), emptyList(), emptyList(), at(10), cal)
        near(WatchFaceRules.MIN_SWEEP_DEGREES, face.arcs.single().sweepDegrees)
        assertEquals("Watch face. Next 12 hours: 1 thing. Next: Quick call 11:00. Tap for your whole day.", face.spokenLine)
        assertEquals("Watch face. Next 12 hours: nothing booked. Tap for your whole day.", WatchFace.EMPTY.spokenLine)
    }

    @Test
    fun theCompactFaceHasAThinnerRim() {
        assertEquals(4f, WatchFaceRules.rimStrokeDp(DayRingHeader.COMPACT_DP))
        assertEquals(6f, WatchFaceRules.rimStrokeDp(DayRingHeader.WIDE_DP))
        near(150 / 2f - 3f - 2f, WatchFaceRules.rimRadiusDp(DayRingHeader.WIDE_DP))
    }
}
