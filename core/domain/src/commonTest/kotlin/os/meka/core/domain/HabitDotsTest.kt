package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitDotsTest {
    private fun chip(id: String, done: Boolean = false, behind: Boolean = false, name: String = id) =
        HabitChip(id, HabitChipRules.shortTitle(name), done, behind, (if (done) "Untick " else "Tick ") + "$name for today", name = name)

    @Test
    fun calmTodayHabitsSitAsDotsCentredOnSixOClock() {
        val dots = HabitDotRules.dots(listOf(chip("a"), chip("b", done = true), chip("c", behind = true)))
        assertEquals(listOf(156f, 180f, 204f), dots.map { it.degrees })
        assertEquals(listOf(false, true, false), dots.map { it.done })
        assertEquals(listOf(false, false, true), dots.map { it.behind })
        // One habit sits right at the foot; two straddle it.
        assertEquals(listOf(180f), HabitDotRules.dots(listOf(chip("a"))).map { it.degrees })
        assertEquals(listOf(168f, 192f), HabitDotRules.dots(listOf(chip("a"), chip("b"))).map { it.degrees })
        // Eight still stay clear of 12 o'clock.
        val eight = HabitDotRules.dots((1..8).map { chip("h$it") })
        assertTrue(eight.all { it.degrees in 90f..270f })
    }

    @Test
    fun theChipsRowShowsOnlyWhenTheFaceIsHiddenOrTheHabitsDontFit() {
        val three = listOf(chip("a"), chip("b"), chip("c"))
        assertFalse(HabitDotRules.chipsShown(faceShown = true, three))
        assertTrue(HabitDotRules.chipsShown(faceShown = false, three))
        val nine = (1..9).map { chip("h$it") }
        assertTrue(HabitDotRules.dots(nine).isEmpty())
        assertTrue(HabitDotRules.chipsShown(faceShown = true, nine))
        // No habits today: neither.
        assertFalse(HabitDotRules.chipsShown(faceShown = false, emptyList()))
        assertTrue(HabitDotRules.dots(emptyList()).isEmpty())
    }

    @Test
    fun dotsSitInsideTheMarkersOnBothHeaderFaces() {
        for (size in listOf(DayRingHeader.COMPACT_DP, DayRingHeader.WIDE_DP)) {
            val radius = WatchFaceRules.rimRadiusDp(size)
            val markerInner = radius - WatchFaceRules.rimStrokeDp(size) / 2f - 2f - radius * WatchFaceRules.MAJOR_MARKER_LENGTH
            val ring = HabitDotRules.ringRadiusDp(size)
            assertTrue(ring + HabitDotRules.dotDp(size) / 2f <= markerInner - HabitDotRules.GAP_DP + 0.01f, "size $size")
            assertTrue(ring > radius / 2f, "the dots stay near the rim, not the hub ($size)")
        }
        assertEquals(HabitDotRules.COMPACT_DOT_DP, HabitDotRules.dotDp(DayRingHeader.COMPACT_DP))
        assertEquals(HabitDotRules.DOT_DP, HabitDotRules.dotDp(DayRingHeader.WIDE_DP))
        // The dot at 6 o'clock is straight below the centre.
        val foot = HabitDotRules.place(HabitDotRules.dots(listOf(chip("a"))).single(), DayRingHeader.WIDE_DP)
        assertTrue(abs(foot.xDp) < 0.01f)
        assertTrue(abs(HabitDotRules.ringRadiusDp(DayRingHeader.WIDE_DP) - foot.yDp) < 0.01f)
    }

    @Test
    fun aTapTicksTheNearestDotAndAnywhereElseOpensTheDay() {
        val dots = HabitDotRules.dots(listOf(chip("a"), chip("b"), chip("c")))
        for (size in listOf(DayRingHeader.COMPACT_DP, DayRingHeader.WIDE_DP)) {
            for (d in dots) {
                val p = HabitDotRules.place(d, size)
                assertEquals(d.id, HabitDotRules.hit(dots, p.xDp + 2f, p.yDp - 2f, size)?.id, "size $size")
            }
            // The centre and the top of the face open the Day ring.
            assertNull(HabitDotRules.hit(dots, 0f, 0f, size))
            assertNull(HabitDotRules.hit(dots, 0f, -HabitDotRules.ringRadiusDp(size), size))
        }
        assertNull(HabitDotRules.hit(emptyList(), 0f, HabitDotRules.ringRadiusDp(DayRingHeader.WIDE_DP), DayRingHeader.WIDE_DP))
    }

    @Test
    fun dotsComeUpAsTheRimDrawsPastThem() {
        assertEquals(0f, HabitDotRules.shown(0.4f, 180f))
        assertTrue(abs(0.5f - HabitDotRules.shown((180f + 15f) / 360f, 180f)) < 0.001f)
        assertEquals(1f, HabitDotRules.shown(0.6f, 180f))
        assertEquals(1f, HabitDotRules.shown(1f, 359f))
    }

    @Test
    fun theUndoBarAndAScreenReaderSayTheHabitsWholeName() {
        val dot = HabitDotRules.dots(listOf(chip("r", name = "Read 20 pages of a novel"))).single()
        assertEquals("Read 20 pages of a novel", dot.title)
        assertEquals("Read 20 pages of a novel · done today", HabitDotRules.tickedLine(dot, nowDone = true))
        assertEquals("Read 20 pages of a novel · not done today", HabitDotRules.tickedLine(dot, nowDone = false))
        val two = HabitDotRules.dots(listOf(chip("s", done = true, name = "Stretch"), chip("r", name = "Read")))
        assertEquals(" Habits: Stretch, done; Read, not yet.", HabitDotRules.spokenLine(two))
        assertEquals("", HabitDotRules.spokenLine(emptyList()))
    }

    @Test
    fun chipsFromGoalsCarryTheWholeName() {
        val h = HabitItem(
            id = "r", title = "Read 20 pages of a novel", targetPerWeek = 7, timing = HabitTiming.ANYTIME, minutes = 30, goalId = null,
            doneThisWeek = 0, weekTarget = 7, doneToday = false, pace = HabitPace.DUE, streak = 0, streakUnit = "day",
            week = List(7) { false }, meta = "", streakLine = null, hasConflict = false,
        )
        val chip = HabitChipRules.build(listOf(h)).single()
        assertEquals("Read 20 pages of…", chip.title)
        assertEquals("Read 20 pages of a novel", chip.name)
    }
}
