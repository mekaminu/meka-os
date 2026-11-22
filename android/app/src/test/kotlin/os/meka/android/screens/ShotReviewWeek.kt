package os.meka.android.screens

import os.meka.core.domain.CivilDate
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.ReviewDone
import os.meka.core.domain.ReviewGoal
import os.meka.core.domain.ReviewHabit
import os.meka.core.domain.ReviewOpen
import os.meka.core.domain.ReviewRules
import os.meka.core.domain.ReviewTile
import os.meka.core.domain.WeeklyReviewView
import os.meka.core.domain.Win

/**
 * One review week for every Review shot (the walk-through 2026-11-22, A6): ReviewScreensTest and
 * ReviewBalanceReflectScreensTest each kept their own "many" week, so Reflect's facts read "9 done · 2 still open" over a
 * still-open list of five and two more, and "2 wins" beside a Wins card of three. Both now read the same week, wins and
 * (for Reflect) earlier weeks' answers, which repeat no win of another week. Mon 19 Oct 2026; "today" is Saturday 24th,
 * so every "Was due" is a day already gone. Invented data only (rule 13).
 */
object ShotReviewWeek {
    enum class Fill { EMPTY, ONE, MANY }

    private val cal = LocalCalendar.fixedOffset(3_600_000L)

    /** Mon 19 Oct 2026. */
    val ws = CivilDate.toEpochDay(2026, 10, 19)

    /** Saturday 24th. */
    val today = ws + 5

    private fun at17(day: Long) = cal.toEpochMs(day, 17 * 60)

    /** The review week each fill shows, the same on every Review shot. */
    fun week(fill: Fill): WeeklyReviewView = when (fill) {
        Fill.EMPTY -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
            tiles = listOf(ReviewTile("Done", 0, "tasks"), ReviewTile("Lists", 0, "closed")),
            aheadTitle = "Next week", aheadLines = listOf("Nothing in the diary yet"),
        )
        Fill.ONE -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws - 7, offset = -1, title = ReviewRules.title(-1, ws - 7), rangeLabel = ReviewRules.rangeLabel(ws - 7),
            isCurrent = false, canGoBack = true, canGoForward = true,
            tiles = listOf(ReviewTile("Done", 1, "task"), ReviewTile("Habit ticks", 5, "of 7"), ReviewTile("Lists", 1, "closed")),
            comparedLine = ReviewRules.comparedLine(1, 3),
            done = listOf(ReviewDone("d1", "Book the boiler service", "Thu")),
            habits = listOf(ReviewHabit("h1", "Walk 10k steps", 5, 7, false, listOf(true, true, false, true, true, false, true), ReviewRules.habitLine(5, 7, false, false))),
            habitsLine = "0 of 1 habit met their week",
            goals = listOf(ReviewGoal("g1", "Run 5k", 40, "40 % · 1 task done this week")),
            listsLines = listOf("1 decision made"),
            stillOpen = listOf(ReviewOpen("o1", "Renew the car insurance", "Was due Wed 14 Oct")),
            aheadTitle = "This week", aheadLines = listOf("3 events · 2 tasks planned or due"),
            reviewed = true, reviewedLine = "Reviewed Sun 18 Oct at 18:42",
        )
        Fill.MANY -> {
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val titles = listOf(
                "Book the boiler service", "Send the school trip form back", "Call the garage about the MOT", "Pay the window cleaner",
                "Fix the gate latch", "Renew the parking permit", "Order the boys' football boots", "Reply to the landlord about the boiler",
                "Pick up the dry cleaning", "Back up the laptop", "Cancel the gym trial", "Write the quarterly report summary",
            )
            WeeklyReviewView.EMPTY.copy(
                weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
                tiles = listOf(ReviewTile("Done", 15, "tasks"), ReviewTile("Habit ticks", 14, "of 18"),
                    ReviewTile("Fasts", 4, "3 reached the goal"), ReviewTile("Lists", 5, "closed")),
                comparedLine = ReviewRules.comparedLine(15, 11),
                done = titles.mapIndexed { i, t -> ReviewDone("d$i", t, days[i % days.size]) },
                doneMore = 3,
                habits = listOf(
                    ReviewHabit("h1", "Stretch for ten minutes", 3, 6, false, listOf(true, false, true, false, true, false, false), ReviewRules.habitLine(3, 6, false, true)),
                    ReviewHabit("h2", "Read to the boys at bedtime", 5, 5, true, listOf(true, true, true, true, true, false, false), ReviewRules.habitLine(5, 5, true, true)),
                    ReviewHabit("h3", "Walk 10k steps", 6, 7, false, listOf(true, true, true, true, true, true, false), ReviewRules.habitLine(6, 7, false, true)),
                ),
                habitsLine = "1 of 3 habits met so far",
                goals = listOf(
                    ReviewGoal("g1", "Run 5k without stopping", 60, "60 % · 2 tasks done this week"),
                    ReviewGoal("g2", "Clear the garage", 25, "25 %"),
                ),
                fastingLine = "4 fasts · average 16 h 10 m · 3 reached the goal",
                listsLines = listOf("2 things you were waiting for arrived", "1 promise kept", "1 decision made", "1 renewal or bill dealt with"),
                stillOpen = listOf(
                    ReviewOpen("o1", "Renew the car insurance", "Was due Wed 21 Oct"),
                    ReviewOpen("o2", "Sort the loft boxes for the charity shop", "Planned Thu 22 Oct"),
                    ReviewOpen("o3", "Ring the dentist", "Planned Fri 23 Oct"),
                    ReviewOpen("o4", "Return the parcel", "Was due Fri 23 Oct"),
                    ReviewOpen("o5", "Book the half-term swimming lessons", "Planned Sat 24 Oct"),
                ),
                stillOpenMore = 2,
                aheadTitle = "Next week",
                aheadLines = listOf("9 events · 6 tasks planned or due · 1 renewal or bill due · 1 decision to review · 2 to chase"),
                northStar = ReviewRules.NORTH_STAR_PENDING.map {
                    when (it.key) {
                        "interruptions" -> it.copy(value = "7", line = "Heads-ups that reached a device this week")
                        "handled" -> it.copy(value = "12", line = "Cards Navilife dealt with for you")
                        else -> it
                    }
                },
            )
        }
    }

    /** Three wins this week (Tue, Thu, Fri), one in each of the two weeks before; each kept at 17:00 on its day. */
    val wins: List<Win> = listOf(
        Win("w1", "Shipped the release notes a day early", ws + 1, at17(ws + 1)),
        Win("w2", "Calm handover with the new starter", ws + 3, at17(ws + 3)),
        Win("w3", "Closed the audit actions", ws + 4, at17(ws + 4)),
        Win("w4", "Ran the team retro", ws - 10, at17(ws - 10)),
        Win("w5", "Fixed the rota clash", ws - 3, at17(ws - 3)),
    )

    /** The wins a fill keeps: none, last week's one, or all five. */
    fun wins(fill: Fill): List<Win> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> wins.filter { it.id == "w5" }
        Fill.MANY -> wins
    }
}
