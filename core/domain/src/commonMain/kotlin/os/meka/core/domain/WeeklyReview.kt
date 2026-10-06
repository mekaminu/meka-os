package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Weekly review (build plan M1, ADR-013), non-AI: a look back at one Monday–Sunday week — what got done, how the
 * habits went, fasts, what closed on your lists, what is still open — then the week ahead, and the north-star
 * numbers. Everything is counted from what MEKA already stores; nothing is inferred from free text and nothing is
 * changed except "Done reviewing", which is stored on one `context_mode` entity with the fixed id [ENTITY_ID] (the
 * week's Monday as an epoch day, and when), plain last-writer-wins, so reviewing on the Mac shows on the Fold too.
 *
 * Which week opens first: from Thursday the week in progress, Monday to Wednesday the week just gone (the usual
 * time for a review is Sunday evening or Monday morning). The screen can step back [ReviewRules.MAX_WEEKS_BACK]
 * weeks.
 */
object ReviewFields {
    /** The Monday (local epoch day) of the last week marked "Done reviewing". */
    const val REVIEWED_WEEK = "reviewedWeek"
    const val REVIEWED_AT = "reviewedAtMs"
}

/** A number at the top of the review; it counts up on appear. */
data class ReviewTile(val label: String, val value: Int, val detail: String?)

/** A task finished in the week. */
data class ReviewDone(val id: String, val title: String, val dayLabel: String)

data class ReviewHabit(
    val id: String,
    val title: String,
    val done: Int,
    /** The week's target (pro rata in the week the habit was added). */
    val target: Int,
    val met: Boolean,
    /** Monday..Sunday. */
    val days: List<Boolean>,
    /** "5 of 7 · met" */
    val line: String,
)

data class ReviewGoal(val id: String, val title: String, val progressPct: Int, val line: String)

/** Something still open from the week: planned or due in it and not done. */
data class ReviewOpen(val id: String, val title: String, val detail: String)

/** One north-star metric (ADR-013). [value] is null while MEKA can't count it yet; [line] then says when it will. */
data class NorthStarMetric(val key: String, val label: String, val value: String?, val line: String)

data class WeeklyReviewView(
    /** The week's Monday (local epoch day). */
    val weekStart: Long,
    /** 0 is the week in progress, -1 last week, and so on. */
    val offset: Int,
    /** "This week", "Last week", "Week of 21 Sep". */
    val title: String,
    /** "Mon 5 – Sun 11 Oct" */
    val rangeLabel: String,
    val isCurrent: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val tiles: List<ReviewTile>,
    /** "3 more done than the week before" / "the same as the week before"; null with nothing to compare. */
    val comparedLine: String?,
    val done: List<ReviewDone>,
    /** Finished tasks not listed in [done]. */
    val doneMore: Int,
    val habits: List<ReviewHabit>,
    /** "2 of 3 habits met their week"; null with no habits. */
    val habitsLine: String?,
    val goals: List<ReviewGoal>,
    /** "4 fasts · average 16 h 10 m · 3 reached the goal"; null with none. */
    val fastingLine: String?,
    /** "2 things you were waiting for arrived", "1 decision made", "1 renewal or bill dealt with". */
    val listsLines: List<String>,
    val stillOpen: List<ReviewOpen>,
    val stillOpenMore: Int,
    /** "Next week" or "This week" (when reviewing last week on a Monday); null when the following week is past. */
    val aheadTitle: String?,
    /** "3 events · 2 tasks planned · 1 renewal due · 1 decision to review · 2 to chase". */
    val aheadLines: List<String>,
    val northStar: List<NorthStarMetric>,
    val reviewed: Boolean,
    /** "Reviewed Sun 11 Oct at 18:42"; null until reviewed. */
    val reviewedLine: String?,
    /** The Sunday-evening card in Today (and its heads-up), for the week that is ending or just ended. */
    val card: ReviewCard = ReviewCard.NONE,
) {
    companion object {
        val EMPTY = WeeklyReviewView(
            weekStart = 0, offset = 0, title = "This week", rangeLabel = "", isCurrent = true, canGoBack = false, canGoForward = false,
            tiles = emptyList(), comparedLine = null, done = emptyList(), doneMore = 0, habits = emptyList(), habitsLine = null,
            goals = emptyList(), fastingLine = null, listsLines = emptyList(), stillOpen = emptyList(), stillOpenMore = 0,
            aheadTitle = null, aheadLines = emptyList(), northStar = ReviewRules.NORTH_STAR_PENDING, reviewed = false, reviewedLine = null,
        )
    }
}

/**
 * The weekly review's card in Today: offered from Sunday evening ([ReviewRules.CARD_START_MIN]) through Monday until
 * that week is reviewed on either device. Its heads-up goes out when the card starts ([NoticeSources]).
 */
data class ReviewCard(
    /** Show the card in Today now. */
    val offered: Boolean,
    /** The week it is about: Monday's epoch day (0 for [NONE]). On a Monday that is last week, otherwise this week. */
    val weekStart: Long,
    /** The review offset that week has today (0 or -1), for opening the Review tab on it. */
    val offset: Int,
    /** That week has been reviewed already. */
    val reviewed: Boolean,
    /** "Review your week" (Sunday) or "Review last week" (Monday). */
    val title: String,
    /** "12 done · 3 of 4 habits met · 2 still open"; empty while not offered. */
    val line: String,
) {
    companion object {
        val NONE = ReviewCard(offered = false, weekStart = 0, offset = 0, reviewed = false, title = "", line = "")
    }
}

/** Pure rules, unit-tested without a replica. */
object ReviewRules {
    /** The card (and its heads-up) starts at 18:00 on Sunday and stays through Monday. */
    const val CARD_START_MIN = 18 * 60

    /** The week the card is about on [today]: last week on a Monday, otherwise this week (the offset, 0 or -1). */
    fun cardOffset(today: Long): Int = if (CivilDate.isoDayOfWeek(today) == 1) -1 else 0

    /** Whether the card's window is open: Sunday from [CARD_START_MIN], or any time on Monday. */
    fun cardWindow(today: Long, minute: Int): Boolean = when (CivilDate.isoDayOfWeek(today)) {
        7 -> minute >= CARD_START_MIN
        1 -> true
        else -> false
    }

    fun cardTitle(offset: Int): String = if (offset == 0) "Review your week" else "Review last week"

    /** "12 done · 3 of 4 habits met · 2 still open", or "A look back, and the week ahead" with nothing to count. */
    fun cardLine(done: Int, habitsMet: Int, habits: Int, stillOpen: Int): String = listOfNotNull(
        "$done done".takeIf { done > 0 },
        "$habitsMet of ${ShutdownRules.count(habits, "habit")} met".takeIf { habits > 0 },
        "$stillOpen still open".takeIf { stillOpen > 0 },
    ).joinToString(" · ").ifEmpty { "A look back, and the week ahead" }

    const val MAX_WEEKS_BACK = 12
    const val MAX_DONE = 12
    const val MAX_OPEN = 5

    /** The week the review opens on: Monday–Wednesday the week just gone, otherwise this week. */
    fun defaultOffset(today: Long): Int = if (CivilDate.isoDayOfWeek(today) <= 3) -1 else 0

    fun clampOffset(offset: Int): Int = offset.coerceIn(-MAX_WEEKS_BACK, 0)

    fun title(offset: Int, weekStart: Long): String = when (offset) {
        0 -> "This week"
        -1 -> "Last week"
        else -> "Week of " + CivilDate.fromEpochDay(weekStart).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }
    }

    /** "Mon 5 – Sun 11 Oct", "Mon 28 Sep – Sun 4 Oct", "Mon 29 Dec 2025 – Sun 4 Jan 2026" (year only across years). */
    fun rangeLabel(weekStart: Long): String {
        val a = CivilDate.fromEpochDay(weekStart)
        val b = CivilDate.fromEpochDay(weekStart + 6)
        val m = Recurrence.MONTH_SHORT
        return when {
            a.year != b.year -> "Mon ${a.day} ${m[a.month - 1]} ${a.year} – Sun ${b.day} ${m[b.month - 1]} ${b.year}"
            a.month != b.month -> "Mon ${a.day} ${m[a.month - 1]} – Sun ${b.day} ${m[b.month - 1]}"
            else -> "Mon ${a.day} – Sun ${b.day} ${m[b.month - 1]}"
        }
    }

    /** "3 more done than the week before", "2 fewer …", "The same as the week before"; null when both are 0. */
    fun comparedLine(thisWeek: Int, before: Int): String? = when {
        thisWeek == 0 && before == 0 -> null
        thisWeek > before -> "${thisWeek - before} more done than the week before"
        thisWeek < before -> "${before - thisWeek} fewer done than the week before"
        else -> "The same as the week before"
    }

    fun habitLine(done: Int, target: Int, met: Boolean, inProgress: Boolean): String =
        "$done of $target" + when {
            met -> " · met"
            inProgress -> " so far"
            else -> ""
        }

    /** "4 fasts · average 16 h 10 m · 3 reached the goal" */
    fun fastingLine(fasts: List<EndedFast>): String? {
        if (fasts.isEmpty()) return null
        val avg = fasts.sumOf { it.endedAtMs - it.startedAtMs } / fasts.size
        return "${ShutdownRules.count(fasts.size, "fast")} · average ${FastingRules.duration(avg)} · ${fasts.count { it.reachedGoal }} reached the goal"
    }

    /**
     * The north-star metrics (ADR-013). None can be counted yet: they measure what MEKA does for you (acting,
     * reminding, asking, notifying), which starts with the activity log, approvals and the assistant layer (V1).
     * Interruptions are counted once the governor records what it posts (the next slice of this item).
     */
    val NORTH_STAR_PENDING: List<NorthStarMetric> = listOf(
        NorthStarMetric("handled", "Handled without you", null, "Counted once MEKA acts for you"),
        NorthStarMetric("prevented", "Prevented misses", null, "Counted once MEKA's reminders are logged"),
        NorthStarMetric("interruptions", "Interruptions", null, "Notifications that reached you; counting starts soon"),
        NorthStarMetric("approval", "Approval rate", null, "Counted once MEKA asks you to approve things"),
        NorthStarMetric("false-positive", "Not relevant", null, "Suggestions you dismissed; counted with the assistant"),
        NorthStarMetric("time", "Time returned", null, "An estimate, once MEKA acts for you"),
    )
}

class WeeklyReview(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun reviewedWeek(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(ReviewFields.REVIEWED_WEEK)?.longOrNull

    /** "Done reviewing" for the week starting [weekStart] (a Monday); synced. */
    fun markReviewed(weekStart: Long) {
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(ReviewFields.REVIEWED_WEEK to GoalRules.weekStart(weekStart).fv(), ReviewFields.REVIEWED_AT to nowMs().fv()),
        )
    }

    /**
     * The review of the week [offset] weeks from this one (null: [ReviewRules.defaultOffset]). [window] gives the
     * local day window of an epoch day (for events).
     */
    fun view(
        offset: Int?,
        all: List<Task>,
        events: List<CalendarEvent>,
        goals: GoalsView,
        fasts: List<EndedFast>,
        window: (Long) -> DayWindow,
    ): WeeklyReviewView {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        val off = ReviewRules.clampOffset(offset ?: ReviewRules.defaultOffset(today))
        val main = build(off, all, events, goals, fasts, window)
        // The card: the week ending (Sunday) or just ended (Monday), until it is reviewed.
        val cardOff = ReviewRules.cardOffset(today)
        val cardWs = GoalRules.weekStart(today) + 7L * cardOff
        val reviewed = reviewedWeek() == cardWs
        val offered = !reviewed && ReviewRules.cardWindow(today, calendar.minuteOfDay(now))
        val line = if (!offered) "" else (if (cardOff == off) main else build(cardOff, all, events, goals, fasts, window)).let { v ->
            ReviewRules.cardLine(v.done.size + v.doneMore, v.habits.count { it.met }, v.habits.size, v.stillOpen.size + v.stillOpenMore)
        }
        return main.copy(card = ReviewCard(offered, cardWs, cardOff, reviewed, ReviewRules.cardTitle(cardOff), line))
    }

    private fun build(
        off: Int,
        all: List<Task>,
        events: List<CalendarEvent>,
        goals: GoalsView,
        fasts: List<EndedFast>,
        window: (Long) -> DayWindow,
    ): WeeklyReviewView {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        val ws = GoalRules.weekStart(today) + 7L * off
        val we = ws + 6
        val isCurrent = off == 0
        val startMs = calendar.toEpochMs(ws, 0)
        val endMs = calendar.toEpochMs(ws + 7, 0)
        fun inWeek(ms: Long?) = ms != null && ms >= startMs && ms < endMs

        // Done: finished tasks, newest first.
        val doneTasks = all.filter { it.isDone && inWeek(it.completedAtMs) }.sortedByDescending { it.completedAtMs }
        val beforeStart = calendar.toEpochMs(ws - 7, 0)
        val doneBefore = all.count { it.isDone && it.completedAtMs != null && it.completedAtMs >= beforeStart && it.completedAtMs < startMs }
        val done = doneTasks.take(ReviewRules.MAX_DONE).map {
            ReviewDone(it.id, it.title, LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(calendar.epochDayOf(it.completedAtMs!!)) - 1])
        }

        // Habits: ticks in the week against its target (habits added after the week are left out).
        val ticks = habitTicks()
        val habits = replica.entities(EntityTypes.HABIT).mapNotNull { s ->
            val id = s.ref.entityId
            val createdDay = calendar.epochDayOf(s[ActionableFields.CREATED_AT].longOrNull ?: now)
            if (createdDay > we) return@mapNotNull null
            val perWeek = (s[HabitFields.TARGET_PER_WEEK].longOrNull?.toInt() ?: 7).coerceIn(1, 7)
            val target = GoalRules.weekTarget(perWeek, minOf(today, we), createdDay)
            val days = ticks[id].orEmpty()
            val weekDays = (0..6).map { (ws + it) in days }
            val n = weekDays.count { it }
            val met = n >= target
            ReviewHabit(id, s[ActionableFields.TITLE].textOrNull ?: "", n, target, met, weekDays,
                ReviewRules.habitLine(n, target, met, isCurrent))
        }.sortedWith(compareBy<ReviewHabit> { it.met }.thenBy { it.title.lowercase() }.thenBy { it.id })
        val habitsLine = habits.takeIf { it.isNotEmpty() }?.let { hs ->
            "${hs.count { it.met }} of ${ShutdownRules.count(hs.size, "habit")} ${if (isCurrent) "met so far" else "met their week"}"
        }

        // Goals: where open ones stand now (progress isn't stored by week).
        val reviewGoals = goals.goals.map { g ->
            val linkedDone = doneTasks.count { it.goalId == g.id }
            ReviewGoal(g.id, g.title, g.progressPct,
                listOfNotNull("${g.progressPct} %", linkedDone.takeIf { it > 0 }?.let { "${ShutdownRules.count(it, "task")} done this week" }).joinToString(" · "))
        }

        val weekFasts = fasts.filter { inWeek(it.endedAtMs) }

        // Lists: what closed in the week.
        val received = replica.entities(EntityTypes.COMMITMENT).count { s ->
            !s.deleted && lifecycle(s) == Lifecycle.DONE && inWeek(s[ActionableFields.COMPLETED_AT].longOrNull)
        }
        val decided = replica.entities(EntityTypes.DECISION).count { s ->
            !s.deleted && inWeek(s[DecisionFields.DECIDED_AT].longOrNull ?: s[ActionableFields.CREATED_AT].longOrNull)
        }
        val renewed = replica.entities(EntityTypes.OBLIGATION).count { s -> !s.deleted && inWeek(s[ObligationFields.LAST_DONE_AT].longOrNull) }
        val listsLines = listOfNotNull(
            received.takeIf { it > 0 }?.let { if (it == 1) "1 thing you were waiting for arrived" else "$it things you were waiting for arrived" },
            decided.takeIf { it > 0 }?.let { ShutdownRules.count(it, "decision") + " made" },
            renewed.takeIf { it > 0 }?.let { (if (it == 1) "1 renewal or bill" else "$it renewals and bills") + " dealt with" },
        )

        // Still open: planned or due in the week (up to now) and not done.
        val upTo = minOf(endMs, now)
        val open = all.filter { t ->
            t.lifecycle.consumesCapacity && !t.waitsForItsDay(today) &&
                listOfNotNull(t.dueAtMs, t.scheduledAtMs).any { it >= startMs && it < upTo }
        }.sortedBy { minOf(it.dueAtMs ?: Long.MAX_VALUE, it.scheduledAtMs ?: Long.MAX_VALUE) }
        val stillOpen = open.take(ReviewRules.MAX_OPEN).map { t ->
            val due = t.dueAtMs?.takeIf { it >= startMs && it < upTo }
            val at = due ?: t.scheduledAtMs!!
            ReviewOpen(t.id, t.title, (if (due != null) "Was due " else "Planned ") + CivilDate.shortLabel(calendar.epochDayOf(at)))
        }

        // Ahead: the week after the one reviewed, while it isn't over.
        val aheadStart = ws + 7
        val aheadTitle = when (off) {
            0 -> "Next week"
            -1 -> "This week"
            else -> null
        }
        val aheadLines = if (aheadTitle == null) emptyList() else ahead(aheadStart, all, events, window)

        // Tiles.
        val habitTarget = habits.sumOf { it.target }
        val tiles = buildList {
            add(ReviewTile("Done", doneTasks.size, if (doneTasks.size == 1) "task" else "tasks"))
            if (habits.isNotEmpty()) add(ReviewTile("Habit ticks", habits.sumOf { it.done }, "of $habitTarget"))
            if (weekFasts.isNotEmpty()) add(ReviewTile("Fasts", weekFasts.size, "${weekFasts.count { it.reachedGoal }} reached the goal"))
            add(ReviewTile("Lists", received + decided + renewed, "closed"))
        }

        val reviewedWeek = reviewedWeek()
        val reviewed = reviewedWeek == ws
        val reviewedAt = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(ReviewFields.REVIEWED_AT)?.longOrNull
        val reviewedLine = if (reviewed && reviewedAt != null) {
            "Reviewed ${CivilDate.shortLabel(calendar.epochDayOf(reviewedAt))} at ${LocalClock.formatMinute(calendar.minuteOfDay(reviewedAt))}"
        } else null

        return WeeklyReviewView(
            weekStart = ws,
            offset = off,
            title = ReviewRules.title(off, ws),
            rangeLabel = ReviewRules.rangeLabel(ws),
            isCurrent = isCurrent,
            canGoBack = off > -ReviewRules.MAX_WEEKS_BACK,
            canGoForward = off < 0,
            tiles = tiles,
            comparedLine = ReviewRules.comparedLine(doneTasks.size, doneBefore),
            done = done,
            doneMore = doneTasks.size - done.size,
            habits = habits,
            habitsLine = habitsLine,
            goals = reviewGoals,
            fastingLine = ReviewRules.fastingLine(weekFasts),
            listsLines = listsLines,
            stillOpen = stillOpen,
            stillOpenMore = open.size - stillOpen.size,
            aheadTitle = aheadTitle,
            aheadLines = aheadLines,
            northStar = ReviewRules.NORTH_STAR_PENDING,
            reviewed = reviewed,
            reviewedLine = reviewedLine,
        )
    }

    /** Lines for the week starting [ws]: events, planned/due tasks, renewals due, decisions to review, chases. */
    private fun ahead(ws: Long, all: List<Task>, events: List<CalendarEvent>, window: (Long) -> DayWindow): List<String> {
        val startMs = calendar.toEpochMs(ws, 0)
        val endMs = calendar.toEpochMs(ws + 7, 0)
        fun inWeek(ms: Long?) = ms != null && ms >= startMs && ms < endMs
        val days = (0..6).map { window(ws + it) }
        val eventCount = events.count { e -> days.any { e.overlaps(it) } }
        val tasks = all.count { t -> t.lifecycle.consumesCapacity && (inWeek(t.scheduledAtMs) || inWeek(t.dueAtMs)) }
        val renewals = replica.entities(EntityTypes.OBLIGATION).count { s ->
            !s.deleted && lifecycle(s)?.isTerminal != true && (inWeek(s[ActionableFields.DUE_AT].longOrNull) || inWeek(s[ObligationFields.CANCEL_BY_AT].longOrNull))
        }
        val reviews = replica.entities(EntityTypes.DECISION).count { s ->
            !s.deleted && s[DecisionFields.STATUS].textOrNull != DecisionStatus.SUPERSEDED.name && inWeek(s[DecisionFields.REVIEW_AT].longOrNull)
        }
        val chases = replica.entities(EntityTypes.COMMITMENT).count { s ->
            !s.deleted && lifecycle(s)?.isTerminal != true && inWeek(s[CommitmentFields.FOLLOW_UP_AT].longOrNull)
        }
        val parts = listOfNotNull(
            eventCount.takeIf { it > 0 }?.let { ShutdownRules.count(it, "event") },
            tasks.takeIf { it > 0 }?.let { ShutdownRules.count(it, "task") + " planned or due" },
            renewals.takeIf { it > 0 }?.let { (if (it == 1) "1 renewal or bill" else "$it renewals and bills") + " due" },
            reviews.takeIf { it > 0 }?.let { ShutdownRules.count(it, "decision") + " to review" },
            chases.takeIf { it > 0 }?.let { "$it to chase" },
        )
        return parts.ifEmpty { listOf("Nothing in the diary yet") }
    }

    private fun habitTicks(): Map<String, Set<Long>> =
        replica.entities(EntityTypes.HABIT_COMPLETION)
            .filter { it[HabitCompletionFields.DONE].boolOrNull == true }
            .mapNotNull { s ->
                val h = s[HabitCompletionFields.HABIT_ID].textOrNull ?: return@mapNotNull null
                val d = s[HabitCompletionFields.DAY].longOrNull ?: return@mapNotNull null
                h to d
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun lifecycle(s: EntitySnapshot): Lifecycle? = s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) }

    companion object {
        const val ENTITY_ID = "review"
    }
}
