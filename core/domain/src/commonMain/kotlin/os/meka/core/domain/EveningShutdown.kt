package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Evening shutdown (build plan M1), non-AI: at the end of the day, tick off what got done, carry what's left over
 * (to tomorrow, to Someday, or skip a repeating one) and look at tomorrow, then call it a day.
 *
 * "Shut down" is stored on one `context_mode` entity with the fixed id [ENTITY_ID] (the local day it was done and
 * when), so shutting down on the Mac also puts the Fold's card away. Both fields are plain last-writer-wins.
 * Carrying over is the same "Tomorrow" as everywhere else ([Tasks.snoozeOccurrence]); nothing new is written to tasks.
 */
object ShutdownFields {
    /** Local epoch day of the last shutdown. */
    const val DONE_DAY = "shutdownDay"
    const val DONE_AT = "shutdownAtMs"
}

/** One thing left from today, with what can be done with it in the shutdown. */
data class ShutdownItem(
    val task: Task,
    /** "Overdue" · "Planned 14:00" · "↻ Every weekday"; null when there's nothing to say. */
    val line: String?,
    val overdue: Boolean,
    /** A repeating task can skip this occurrence (the next one comes on its day). */
    val canSkip: Boolean,
    /** A one-off task can go to Someday. */
    val canSomeday: Boolean,
)

/** One line of tomorrow: an event or a task. [time] is "All day", "09:30", or null for a task with no time. */
data class TomorrowRow(val id: String, val time: String?, val title: String, val isEvent: Boolean, val detail: String?)

data class TomorrowPreview(
    /** "Tomorrow · Wed 7 Oct" */
    val label: String,
    /** "Work 09:00–17:30" when tomorrow is a work day, else null. */
    val workLine: String?,
    val rows: List<TomorrowRow>,
    val eventCount: Int,
    val taskCount: Int,
    /** "2 events · 4 tasks · first at 09:30", or "Nothing planned yet". */
    val summary: String,
    /** The first thing that starts tomorrow (not an all-day event, not one still running from tonight), if any. */
    val first: TomorrowRow? = null,
    /** Tomorrow at a glance, for Today in the evening: "Tomorrow: first thing 09:00 Standup · 3 events · 2 tasks". */
    val glance: String = ShutdownRules.GLANCE_EMPTY,
)

data class ShutdownView(
    /** Show the "Shut down the day" card in Today: it's the evening, you're not at work and you haven't yet. */
    val offered: Boolean,
    val doneToday: Boolean,
    /** "Day shut down at 18:42" once done today, else null. */
    val doneLine: String?,
    /** Local minute of the day the card appears today. */
    val startMinute: Int,
    val doneCount: Int,
    /** "4 done today" · "No tasks finished today" */
    val doneCountLine: String,
    val left: List<ShutdownItem>,
    /** "3 left from today" · "Nothing left from today" */
    val leftLine: String,
    val tomorrow: TomorrowPreview,
    /** The card's second line: "3 left · tomorrow: 2 events, first at 09:30". */
    val cardLine: String,
    /**
     * The evening has started ([startMinute] has passed today), so Today shows tomorrow at a glance
     * ([TomorrowPreview.glance]) when the shutdown card isn't there to show it: after shutting down, or while still at work.
     */
    val evening: Boolean = false,
) {
    companion object {
        val EMPTY = ShutdownView(
            offered = false, doneToday = false, doneLine = null, startMinute = ShutdownRules.DEFAULT_START_MIN, doneCount = 0,
            doneCountLine = "No tasks finished today", left = emptyList(), leftLine = "Nothing left from today",
            tomorrow = TomorrowPreview("Tomorrow", null, emptyList(), 0, 0, "Nothing planned yet"), cardLine = "",
        )
    }
}

/** Pure rules, unit-tested without a replica. */
object ShutdownRules {
    /** When the card appears on a day without work hours. */
    const val DEFAULT_START_MIN = 18 * 60
    /** Work that ends inside this range starts the evening; outside it (early finish, late or night shift) 18:00 does. */
    val WORK_END_RANGE = 15 * 60..22 * 60

    /** The minute the shutdown is offered on local day [epochDay]: the end of work on a work day, else 18:00. */
    fun startMinute(schedule: WorkSchedule, epochDay: Long, holidays: HolidayCalendar = HolidayCalendar.NONE): Int {
        val workDay = schedule.enabled && CivilDate.isoDayOfWeek(epochDay) in schedule.days && !schedule.crossesMidnight &&
            !holidays.isHoliday(epochDay)
        return if (workDay && schedule.endMinute in WORK_END_RANGE) schedule.endMinute else DEFAULT_START_MIN
    }

    /**
     * What is left from today: open tasks that Today shows or that were planned for today or earlier, in the order
     * they'd be met — planned ones by time, then overdue by due date, then the rest by priority and age.
     * Later occurrences and snoozed items (waiting for their day), Someday and done items are not left over.
     */
    fun left(tasks: List<Task>, today: DayWindow): List<Task> = tasks
        .filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && !it.waitsForItsDay(today.epochDay) }
        .filter { t -> t.scheduledAtMs?.let { it < today.endMs } ?: (t.dueAtMs == null || t.dueAtMs < today.endMs) }
        .sortedWith(
            compareBy<Task> { it.scheduledAtMs == null }
                .thenBy { it.scheduledAtMs ?: Long.MAX_VALUE }
                .thenBy { it.dueAtMs ?: Long.MAX_VALUE }
                .thenByDescending { it.priority }
                .thenBy { it.createdAtMs },
        )

    /** Open tasks that belong to [tomorrow]: their day comes then, or they're planned or due then. */
    fun tomorrowTasks(tasks: List<Task>, tomorrow: DayWindow, leftIds: Set<String>): List<Task> = tasks
        .filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && it.id !in leftIds }
        .filter { t ->
            t.showsFromDay == tomorrow.epochDay ||
                (t.scheduledAtMs != null && t.scheduledAtMs in tomorrow) ||
                (t.scheduledAtMs == null && t.dueAtMs != null && t.dueAtMs in tomorrow && !t.waitsForItsDay(tomorrow.epochDay))
        }

    fun count(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    const val GLANCE_EMPTY = "Tomorrow: nothing planned yet"
    /** Titles in the glance are cut at a word to keep it one line on the Fold's cover screen. */
    const val GLANCE_TITLE_MAX = 32

    /**
     * Tomorrow at a glance: what comes first, then how much is on. "Tomorrow: first thing 09:00 Standup · 3 events ·
     * 2 tasks"; with nothing timed, an all-day event leads ("Tomorrow: Bank holiday all day · 1 task"); counts are left
     * out when the first thing is all there is ("Tomorrow: first thing 09:00 Dentist").
     */
    fun glance(rows: List<TomorrowRow>, first: TomorrowRow?, events: Int, tasks: Int): String {
        if (rows.isEmpty()) return GLANCE_EMPTY
        val allDay = rows.firstOrNull { it.time == "All day" }
        val lead = when {
            first != null -> "first thing ${first.time} ${shorten(first.title)}"
            allDay != null -> "${shorten(allDay.title)} all day"
            else -> null
        }
        val counts = if (lead != null && rows.size == 1) emptyList() else listOfNotNull(
            count(events, "event").takeIf { events > 0 },
            count(tasks, "task").takeIf { tasks > 0 },
        )
        return "Tomorrow: " + (listOfNotNull(lead) + counts).joinToString(" · ")
    }

    /** Cuts [title] at a word boundary to at most [GLANCE_TITLE_MAX] characters, with "…". */
    fun shorten(title: String): String {
        val t = title.trim()
        if (t.length <= GLANCE_TITLE_MAX) return t
        val cut = t.substring(0, GLANCE_TITLE_MAX - 1)
        val space = cut.lastIndexOf(' ')
        return (if (space >= GLANCE_TITLE_MAX / 2) cut.substring(0, space) else cut).trimEnd(' ', ',', '·', '-', '–') + "…"
    }
}

class EveningShutdown(
    private val replica: Replica,
    private val tasks: Tasks,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun doneDay(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(ShutdownFields.DONE_DAY)?.longOrNull

    private fun doneAtMs(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(ShutdownFields.DONE_AT)?.longOrNull

    /** Calls it a day: the card is put away on every device until tomorrow evening. */
    fun shutDown() {
        val now = nowMs()
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(ShutdownFields.DONE_DAY to calendar.epochDayOf(now).fv(), ShutdownFields.DONE_AT to now.fv()),
        )
    }

    /** "Move the rest to tomorrow": every task still left from today waits for tomorrow. Returns how many moved. */
    fun carryAllToTomorrow(today: DayWindow): Int {
        val left = ShutdownRules.left(tasks.all(), today)
        left.forEach { tasks.snoozeOccurrence(it.id, 1) }
        return left.size
    }

    fun view(
        all: List<Task>,
        events: List<CalendarEvent>,
        schedule: WorkSchedule,
        atWork: Boolean,
        today: DayWindow,
        tomorrow: DayWindow,
        /** Bank holidays are days off: no work hours that evening or in tomorrow's preview. */
        holidays: HolidayCalendar = HolidayCalendar.NONE,
    ): ShutdownView {
        val now = nowMs()
        val minute = calendar.minuteOfDay(now)
        val start = ShutdownRules.startMinute(schedule, today.epochDay, holidays)
        val doneToday = doneDay() == today.epochDay
        val doneCount = all.count { it.lifecycle == Lifecycle.DONE && it.completedAtMs != null && it.completedAtMs in today }

        val left = ShutdownRules.left(all, today).map { t ->
            val overdue = t.dueAtMs != null && t.dueAtMs < now
            val line = when {
                overdue -> "Overdue"
                t.scheduledAtMs != null && t.scheduledAtMs < today.startMs -> "Planned ${CivilDate.shortLabel(calendar.epochDayOf(t.scheduledAtMs))}"
                t.scheduledAtMs != null -> "Planned ${hhmm(t.scheduledAtMs)}"
                else -> t.repeatMeta(today.epochDay)?.let { "↻ $it" }
            }
            ShutdownItem(t, line, overdue, canSkip = t.recurrence != null, canSomeday = !t.isRepeating)
        }
        val preview = preview(all, events, schedule, tomorrow, left.map { it.task.id }.toSet(), holidays)

        val leftLine = if (left.isEmpty()) "Nothing left from today" else "${left.size} left from today"
        val tomorrowBit = when {
            preview.rows.isEmpty() -> "nothing planned for tomorrow yet"
            else -> "tomorrow: " + preview.summary.replaceFirstChar { it.lowercase() }.replace(" · ", ", ")
        }
        val cardLine = (if (left.isEmpty()) "Nothing left" else "${left.size} left") + " · " + tomorrowBit
        return ShutdownView(
            offered = !doneToday && !atWork && minute >= start,
            doneToday = doneToday,
            doneLine = if (doneToday) doneAtMs()?.let { "Day shut down at ${hhmm(it)}" } ?: "Day shut down" else null,
            startMinute = start,
            doneCount = doneCount,
            doneCountLine = if (doneCount == 0) "No tasks finished today" else "$doneCount done today",
            left = left,
            leftLine = leftLine,
            tomorrow = preview,
            cardLine = cardLine,
            evening = minute >= start,
        )
    }

    private fun preview(
        all: List<Task>, events: List<CalendarEvent>, schedule: WorkSchedule, tomorrow: DayWindow, leftIds: Set<String>, holidays: HolidayCalendar,
    ): TomorrowPreview {
        val dayEvents = events.filter { it.overlaps(tomorrow) }
        val dayTasks = ShutdownRules.tomorrowTasks(all, tomorrow, leftIds)

        // All-day events first, then everything with a time in time order, then tasks with no time (priority, age).
        data class Keyed(val key: Long, val row: TomorrowRow)
        val keyed = buildList {
            dayEvents.forEach { e ->
                val (time, key) = when {
                    e.allDay -> "All day" to Long.MIN_VALUE
                    e.startAtMs < tomorrow.startMs -> "Until ${hhmm(e.endAtMs)}" to tomorrow.startMs
                    else -> hhmm(e.startAtMs) to e.startAtMs
                }
                val detail = listOfNotNull(e.location, providerLabel(e.provider)).joinToString(" · ").ifEmpty { null }
                add(Keyed(key, TomorrowRow("e-" + e.id, time, e.title, true, detail)))
            }
            dayTasks.filter { it.scheduledAtMs != null }.forEach { t ->
                add(Keyed(t.scheduledAtMs!!, TomorrowRow("t-" + t.id, hhmm(t.scheduledAtMs), t.title, false, t.repeatLabel?.let { "↻ $it" })))
            }
        }.sortedWith(compareBy<Keyed> { it.key }.thenBy { it.row.title })
        val untimed = dayTasks.filter { it.scheduledAtMs == null }
            .sortedWith(compareBy<Task> { it.dueAtMs ?: Long.MAX_VALUE }.thenByDescending { it.priority }.thenBy { it.createdAtMs })
            .map { t ->
                val detail = listOfNotNull(t.dueAtMs?.let { "Due ${hhmm(it)}" }, t.repeatLabel?.let { "↻ $it" }).joinToString(" · ").ifEmpty { null }
                TomorrowRow("t-" + t.id, null, t.title, false, detail)
            }
        val rows = keyed.map { it.row } + untimed

        // The first thing that starts tomorrow (not an all-day event, not one still running from tonight).
        val firstRow = keyed.firstOrNull { it.key > tomorrow.startMs || (it.key == tomorrow.startMs && !it.row.time!!.startsWith("Until")) }?.row
        val summary = if (rows.isEmpty()) "Nothing planned yet" else listOfNotNull(
            ShutdownRules.count(dayEvents.size, "event").takeIf { dayEvents.isNotEmpty() },
            ShutdownRules.count(dayTasks.size, "task").takeIf { dayTasks.isNotEmpty() },
            firstRow?.let { "first at ${it.time}" },
        ).joinToString(" · ")

        val workLine = BriefRules.workLine(schedule, holidays, tomorrow.epochDay)
        return TomorrowPreview(
            label = "Tomorrow · ${CivilDate.shortLabel(tomorrow.epochDay)}",
            workLine = workLine,
            rows = rows,
            eventCount = dayEvents.size,
            taskCount = dayTasks.size,
            summary = summary,
            first = firstRow,
            glance = ShutdownRules.glance(rows, firstRow, dayEvents.size, dayTasks.size),
        )
    }

    private fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

    private fun providerLabel(p: String): String? = when (p) {
        "google" -> null // the default calendar needs no label
        "microsoft" -> "Outlook"
        "fixtures" -> "Fixtures"
        else -> null
    }

    companion object {
        const val ENTITY_ID = "shutdown"
    }
}
