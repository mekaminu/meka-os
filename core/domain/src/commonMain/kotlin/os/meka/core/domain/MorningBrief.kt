package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Morning brief (build plan M1), non-AI: one look at the day when it starts — work hours, events and what's planned
 * or due, what you're waiting on (and who to chase), anything on the radar that needs you, habits and a running fast.
 * News headlines join it when server-side news ingestion lands (the next slice of this item).
 *
 * It is offered from the end of quiet hours (07:00 when quiet hours are off or end outside 05:00–10:00) until noon.
 * "Got it" is stored on one `context_mode` entity with the fixed id [ENTITY_ID] (the local day and when), so reading
 * it on the Mac also puts the Fold's card away. Both fields are plain last-writer-wins. Nothing is changed by the
 * brief itself: it only reads what the other parts of MEKA already know.
 */
object BriefFields {
    /** Local epoch day the brief was last read ("Got it"). */
    const val SEEN_DAY = "briefSeenDay"
    const val SEEN_AT = "briefSeenAtMs"
}

/** Something on the radar or in Lists that needs you today: a renewal, a cancel-by date, a decision to review. */
data class BriefLine(val id: String, val title: String, val detail: String?)

data class MorningBriefView(
    /** Show the "Morning brief" card in Today: it's the morning and it hasn't been read today. */
    val offered: Boolean,
    val seenToday: Boolean,
    /** Local minute of the day the brief starts today. */
    val startMinute: Int,
    /** "Good morning" (before noon), "Good afternoon", "Good evening": the brief can be opened any time. */
    val greeting: String,
    /** "Tue 6 Oct" */
    val dateLabel: String,
    /** "Work 09:00–17:30" when today is a work day, else null. */
    val workLine: String?,
    /** Today in time order: all-day events, then events and planned tasks, then open tasks with no time. */
    val day: List<TomorrowRow>,
    val eventCount: Int,
    val taskCount: Int,
    /** "2 events · 4 tasks · first at 09:30" or "Nothing planned yet". */
    val daySummary: String,
    val overdueCount: Int,
    /** Open waiting-for items: chases due first (at most [BriefRules.MAX_WAITING]). */
    val waiting: List<WaitingItem>,
    val waitingTotal: Int,
    /** "Waiting on 3 things · 1 to chase today"; null when you're waiting on nothing. */
    val waitingLine: String?,
    /** Renewals, bills and cancel-by dates that need doing, and decisions due a review. */
    val attention: List<BriefLine>,
    /** "1 habit behind · 2 to do today"; null with no habits. */
    val habitsLine: String?,
    /** "Fasting since 20:05 yesterday · goal at 12:05"; null when not fasting. */
    val fastingLine: String?,
    /** The card's second line: "2 events · 4 tasks · first at 09:30 · 1 to chase". */
    val cardLine: String,
) {
    companion object {
        val EMPTY = MorningBriefView(
            offered = false, seenToday = false, startMinute = BriefRules.DEFAULT_START_MIN, greeting = "Good morning",
            dateLabel = "", workLine = null, day = emptyList(), eventCount = 0, taskCount = 0, daySummary = "Nothing planned yet",
            overdueCount = 0, waiting = emptyList(), waitingTotal = 0, waitingLine = null, attention = emptyList(),
            habitsLine = null, fastingLine = null, cardLine = "",
        )
    }
}

/** Pure rules, unit-tested without a replica. */
object BriefRules {
    /** When the brief starts when quiet hours don't say otherwise. */
    const val DEFAULT_START_MIN = 7 * 60
    /** Quiet hours that end inside this range start the brief; outside it (a night owl, a night shift) 07:00 does. */
    val QUIET_END_RANGE = 5 * 60..10 * 60
    /** The card goes at noon (the brief can still be opened from Today's header). */
    const val END_MIN = 12 * 60
    /** Waiting-for items shown in the brief; the rest are in Lists. */
    const val MAX_WAITING = 5

    fun startMinute(quiet: QuietHours): Int =
        if (quiet.enabled && quiet.startMinute != quiet.endMinute && quiet.endMinute in QUIET_END_RANGE) quiet.endMinute else DEFAULT_START_MIN

    fun greeting(minute: Int): String = when {
        minute < 12 * 60 -> "Good morning"
        minute < 18 * 60 -> "Good afternoon"
        else -> "Good evening"
    }

    /** "Waiting on 3 things · 1 to chase today"; null for none. */
    fun waitingLine(total: Int, due: Int): String? {
        if (total == 0) return null
        val head = "Waiting on " + ShutdownRules.count(total, "thing")
        return if (due > 0) "$head · $due to chase today" else head
    }

    /** "2 events · 4 tasks · first at 09:30 · 1 to chase · 1 renewal due", or "Nothing planned yet". */
    fun cardLine(daySummary: String, hasDay: Boolean, chaseDue: Int, attention: Int): String = listOfNotNull(
        if (hasDay) daySummary else "Nothing planned yet",
        chaseDue.takeIf { it > 0 }?.let { "$it to chase" },
        attention.takeIf { it > 0 }?.let { ShutdownRules.count(it, "thing") + " on your lists" },
    ).joinToString(" · ")
}

class MorningBrief(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun seenDay(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(BriefFields.SEEN_DAY)?.longOrNull

    /** "Got it": the card is put away on every device until tomorrow morning. */
    fun markSeen() {
        val now = nowMs()
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(BriefFields.SEEN_DAY to calendar.epochDayOf(now).fv(), BriefFields.SEEN_AT to now.fv()),
        )
    }

    fun view(
        all: List<Task>,
        events: List<CalendarEvent>,
        schedule: WorkSchedule,
        quiet: QuietHours,
        lists: ListsView,
        goals: GoalsView,
        fasting: FastingView,
        today: DayWindow,
    ): MorningBriefView {
        val now = nowMs()
        val minute = calendar.minuteOfDay(now)
        val start = BriefRules.startMinute(quiet)
        val seenToday = seenDay() == today.epochDay

        // Today: events of the day and what's left of it (planned, overdue, due, and the rest Today shows).
        val dayEvents = events.filter { it.overlaps(today) }
        val dayTasks = ShutdownRules.left(all, today)
        data class Keyed(val key: Long, val row: TomorrowRow)
        val keyed = buildList {
            dayEvents.forEach { e ->
                val (time, key) = when {
                    e.allDay -> "All day" to Long.MIN_VALUE
                    e.startAtMs < today.startMs -> "Until ${hhmm(e.endAtMs)}" to today.startMs
                    else -> hhmm(e.startAtMs) to e.startAtMs
                }
                val detail = listOfNotNull(e.location, if (e.provider == "fixtures") "Fixtures" else null).joinToString(" · ").ifEmpty { null }
                add(Keyed(key, TomorrowRow("e-" + e.id, time, e.title, true, detail)))
            }
            dayTasks.filter { it.scheduledAtMs != null && it.scheduledAtMs >= today.startMs }.forEach { t ->
                add(Keyed(t.scheduledAtMs!!, TomorrowRow("t-" + t.id, hhmm(t.scheduledAtMs), t.title, false, t.repeatLabel?.let { "↻ $it" })))
            }
        }.sortedWith(compareBy<Keyed> { it.key }.thenBy { it.row.title })
        val untimed = dayTasks.filter { it.scheduledAtMs == null || it.scheduledAtMs < today.startMs }.map { t ->
            val detail = listOfNotNull(
                when {
                    t.dueAtMs != null && t.dueAtMs < now -> "Overdue"
                    t.dueAtMs != null -> "Due ${hhmm(t.dueAtMs)}"
                    t.scheduledAtMs != null -> "Planned ${CivilDate.shortLabel(calendar.epochDayOf(t.scheduledAtMs))}"
                    else -> null
                },
                t.repeatLabel?.let { "↻ $it" },
            ).joinToString(" · ").ifEmpty { null }
            TomorrowRow("t-" + t.id, null, t.title, false, detail)
        }
        val rows = keyed.map { it.row } + untimed
        val first = keyed.firstOrNull { it.key > today.startMs || (it.key == today.startMs && !it.row.time!!.startsWith("Until")) }?.row?.time
        val daySummary = if (rows.isEmpty()) "Nothing planned yet" else listOfNotNull(
            ShutdownRules.count(dayEvents.size, "event").takeIf { dayEvents.isNotEmpty() },
            ShutdownRules.count(dayTasks.size, "task").takeIf { dayTasks.isNotEmpty() },
            first?.let { "first at $it" },
        ).joinToString(" · ")
        val overdue = dayTasks.count { it.dueAtMs != null && it.dueAtMs < now }

        val workDay = schedule.enabled && CivilDate.isoDayOfWeek(today.epochDay) in schedule.days
        val workLine = if (workDay) "Work ${LocalClock.formatMinute(schedule.startMinute)}–${LocalClock.formatMinute(schedule.endMinute)}" else null

        // Lists: what you're waiting on (already sorted: due chases first), and what needs doing on the radar.
        val attention = buildList {
            lists.renewals.attention.forEach { add(BriefLine("r-" + it.id, it.title, it.meta)) }
            lists.decisions.filter { it.state == DueState.DUE }.forEach { add(BriefLine("d-" + it.id, "Review: ${it.statement}", it.meta)) }
        }

        val fastingLine = fasting.current?.let { f ->
            "Fasting · ${f.startedLine.replaceFirstChar { it.lowercase() }} · " +
                if (f.reachedGoal) "goal reached" else "goal at ${hhmm(f.goalAtMs)}"
        }

        return MorningBriefView(
            offered = !seenToday && minute >= start && minute < BriefRules.END_MIN,
            seenToday = seenToday,
            startMinute = start,
            greeting = BriefRules.greeting(minute),
            dateLabel = CivilDate.shortLabel(today.epochDay),
            workLine = workLine,
            day = rows,
            eventCount = dayEvents.size,
            taskCount = dayTasks.size,
            daySummary = daySummary,
            overdueCount = overdue,
            waiting = lists.waiting.take(BriefRules.MAX_WAITING),
            waitingTotal = lists.waiting.size,
            waitingLine = BriefRules.waitingLine(lists.waiting.size, lists.chaseDue),
            attention = attention,
            habitsLine = goals.paceLine,
            fastingLine = fastingLine,
            cardLine = BriefRules.cardLine(daySummary, rows.isNotEmpty(), lists.chaseDue, attention.size),
        )
    }

    private fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

    companion object {
        const val ENTITY_ID = "brief"
    }
}
