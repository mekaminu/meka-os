package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayTilesTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val start = 1_791_244_800_000L - hour // Tue 6 Oct 2026, local midnight in London (BST)
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false, title: String = id) =
        CalendarEvent(id, title, from, to, allDay, null, "google", null, null)

    private fun fast(started: Long, reached: Boolean = false) =
        FastNow("f", started, 16, started + 16 * hour, reached, "Started 20:00 yesterday", "Goal 16 h · at 12:00")

    private fun tiles(
        events: List<CalendarEvent> = emptyList(), now: Long = at(9, 5), fast: FastNow? = null,
        done: Int = 0, today: Int = 0, renewals: Int = 0,
    ) = DayTileRules.build(events, now, day, fast, done, today, renewals)

    @Test
    fun allFourInOrderEachOnlyWithSomethingToSay() {
        assertEquals(emptyList(), tiles())
        val all = tiles(listOf(ev("standup", at(9, 30), at(9, 45), title = "Standup")), fast = fast(at(-4)), done = 1, today = 3, renewals = 2)
        assertEquals(listOf(DayTileKind.NEXT_EVENT, DayTileKind.FAST, DayTileKind.HABITS, DayTileKind.RENEWALS), all.map { it.kind })
        assertEquals(listOf("25 min", "13 h", "1 of 3", "2"), all.map { it.valueText })
        assertEquals(listOf("until Standup", "Fasting · goal 16 h", "habits today", "renewals due"), all.map { it.label })
        assertEquals("25 min until Standup", all[0].spokenLine)
        assertEquals("13 h Fasting, goal 16 h", all[1].spokenLine)
    }

    @Test
    fun theCountdownIsToTheNextEventThatHasNotStarted() {
        val events = listOf(
            ev("running", at(9), at(10)),
            ev("holiday", start, start + 24 * hour, allDay = true),
            ev("lunch", at(12), at(13), title = "Lunch"),
            ev("review", at(10, 45), at(11), title = "Review"),
            ev("tomorrow", day.endMs + hour, day.endMs + 2 * hour),
        )
        val t = tiles(events, now = at(9, 5)).single()
        assertEquals(100, t.value)
        assertEquals("1 h 40", t.valueText)
        assertEquals("until Review", t.label)
        // Part of a minute counts as a whole one: 09:05:30 → 10:45 is "1 h 40".
        assertEquals(100, tiles(events, now = at(9, 5) + 30_000).single().value)
        // Nothing left to start today: no tile (tomorrow's isn't counted down).
        assertEquals(emptyList(), tiles(events, now = at(12, 30)))
        assertEquals("2 h", tiles(listOf(ev("x", at(11, 5), at(12))), now = at(9, 5)).single().valueText)
        assertEquals("until your next event", tiles(listOf(ev("x", at(11, 5), at(12), title = " "))).single().label)
    }

    @Test
    fun aRunningFastCountsHoursAndSaysWhenTheGoalIsReached() {
        assertEquals("40 min", tiles(fast = fast(at(8, 25))).single().valueText)
        assertEquals("1 h", tiles(fast = fast(at(8, 0))).single().valueText) // 65 min: whole hours past the first
        val reached = tiles(fast = fast(at(-8), reached = true)).single()
        assertEquals("17 h", reached.valueText)
        assertEquals("Goal reached", reached.label)
        assertEquals(emptyList(), tiles(fast = fast(at(10)))) // a start in the future (clock skew): nothing
    }

    @Test
    fun habitsAndRenewals() {
        assertEquals("0 of 2", tiles(done = 0, today = 2).single().valueText)
        assertEquals("3 of 3", tiles(done = 5, today = 3).single().valueText) // never more than today's
        assertEquals("renewal due", tiles(renewals = 1).single().label)
    }

    @Test
    fun numbersCountUpThroughTheSameWords() {
        val t = tiles(fast = fast(at(-4))).single()
        assertEquals("0 min", t.text(0))
        assertEquals("6 h", t.text(390))
        assertEquals("1 of 3", DayTileRules.valueText(DayTileKind.HABITS, 1, 3))
        assertEquals("1 h 05", DayTileRules.duration(65, withMinutes = true))
        assertTrue(DayTileRules.MAX_TILES >= 4)
    }
}
