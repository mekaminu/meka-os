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
    /** Done for the day, completeness: "Later" — the local day it was put off and when the card comes back. */
    const val LATER_DAY = "shutdownLaterDay"
    const val LATER_UNTIL = "shutdownLaterUntilMs"
    /** Done for the day, completeness: 1 when the evening card and its heads-up are switched off (absent = on). */
    const val CARD_OFF = "shutdownCardOff"
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

/** One thing done today (Done for the day): the task and when it was ticked ("14:02"). */
data class DoneRow(val id: String, val title: String, val time: String)

/** One line of tomorrow: an event or a task. [time] is "All day", "09:30", or null for a task with no time. */
data class TomorrowRow(val id: String, val time: String?, val title: String, val isEvent: Boolean, val detail: String?)

/**
 * The Tomorrow peek on Today (Meka 2026-10-26: "on this screen how do I see tomorrow's stuff?"): a slim card from
 * [ShutdownRules.PEEK_FROM_MIN] (or once the day is shut down), "Tomorrow · Tue 27 Oct · 2 tasks · first 07:40 Gym",
 * that opens the Tomorrow sheet. [spoken] is the same for TalkBack and VoiceOver.
 */
data class TomorrowPeek(val line: String, val spoken: String)

/**
 * Tomorrow on Today, slice 2 (Meka 2026-10-26: the Tomorrow sheet should show "bills due"): what from Lists → Renewals
 * falls tomorrow. [due] "Due tomorrow · Car tax £190 · Netflix £10.99" (bills and renewals due that day), [cancel]
 * "Last day to cancel · Gym £35" (a cancel-by day tomorrow); either may be null. [count] is how many in all, for the peek.
 * Read from the renewals radar he keeps on Lists (not the locked Money tab), so nothing new shows on Today unlocked.
 */
data class TomorrowBills(val due: String?, val cancel: String?, val count: Int, val spoken: String)

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
    /** Tomorrow's weather (Weather slice 2): "9–15°, light rain from 15:00 — take a coat"; set by the facade. */
    val weatherLine: String? = null,
    /** School rhythm, slice 2: "Tomorrow · Theo: PE kit" ([SchoolRules.tomorrowLine]); set by the facade. */
    val school: SchoolLine? = null,
    /**
     * Done for the day, the first train: "First train · the 07:42 · leave by 07:32 · on time the last 4 times" before
     * an office day, from the trains he took on recent mornings ([FirstTrainRules.line]); set by the facade.
     */
    val train: FirstTrainLine? = null,
    /** Bills and renewals due tomorrow, and cancel-by days ([ShutdownRules.bills]); set by the facade from Lists → Renewals. */
    val bills: TomorrowBills? = null,
    /** Lessons planned for tomorrow ([StudyRules.tomorrowLine]): "Study · 2 lessons · about 1 h · …"; set by the facade. */
    val study: StudyDayLine? = null,
    /**
     * Tomorrow on Today, slice 3: tomorrow's open tasks themselves, so a task in the sheet opens its full detail (title,
     * when, reminder, repeat, notes, checklist) and every edit shows at once. Same tasks as the rows, any order.
     */
    val tasks: List<Task> = emptyList(),
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
    /** The card's second line: "3 left · 4 planned for tomorrow · first at 09:30". */
    val cardLine: String,
    /**
     * The evening has started ([startMinute] has passed today), so Today shows tomorrow at a glance
     * ([TomorrowPreview.glance]) when the shutdown card isn't there to show it: after shutting down, or while still at work.
     */
    val evening: Boolean = false,
    /** Done for the day: what got done today, newest first, at most [ShutdownRules.DONE_SHOWN]. */
    val done: List<DoneRow> = emptyList(),
    /** "+ 3 more" when more were done than [done] shows, else null. */
    val doneMore: String? = null,
    /** Tomorrow's first commitment: "Tomorrow starts at 09:00 · Standup"; null when nothing timed is booked. */
    val firstLine: String? = null,
    /**
     * Done for the day: the day has been shut down, so Today's watch face dims to night (its second hand and breath
     * rest) until the next morning ([WatchFaceRules.nightAlpha]).
     */
    val night: Boolean = false,
    /**
     * Daily receipt: "MEKA handled 12 things today" with its biggest parts, for Today's evening line (tapping opens
     * Activity); null when nothing was handled today. Set by the facade from the activity log ([ReceiptRules]).
     */
    val receipt: DailyReceipt? = null,
    /**
     * Done for the day, completeness: when "Later" brings the card back today (epoch ms), 0 when it wasn't put off.
     * While that is ahead the card is away and the heads-up waits for it.
     */
    val laterUntilMs: Long = 0,
    /** "Later" can be offered now: the card is up (or due) and the comeback would still be before 23:00. */
    val laterOffered: Boolean = false,
    /** The evening card and its heads-up are switched off (the pane still opens from Ask → Daily). Synced. */
    val cardOff: Boolean = false,
    /** Today is booked as annual leave: no evening card or heads-up unless he opens the pane himself. */
    val onLeave: Boolean = false,
    /** The Tomorrow peek ([TomorrowPeek]) when Today shows it: from 17:00 (or the evening's start if earlier), or once shut down. */
    val peek: TomorrowPeek? = null,
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
    /** When the card appears on a day without work hours (Done for the day, Meka 2026-10-09: "from work end or 20:00"). */
    const val DEFAULT_START_MIN = 20 * 60
    /** Done for the day: how many of today's finished tasks the pane lists before "+ N more". */
    const val DONE_SHOWN = 6
    /** Work that ends inside this range starts the evening; outside it (early finish, late or night shift) 18:00 does. */
    val WORK_END_RANGE = 15 * 60..22 * 60

    /** Done for the day, completeness: how long "Later" puts the card off, and the latest it comes back. */
    const val LATER_MIN = 60
    const val LATER_LATEST_MIN = 23 * 60
    private const val LATER_AT_LEAST_MIN = 15
    /** The button on Today's card, in the pane and on the heads-up ([NoticeAction.SHUT_DOWN_LATER]). */
    const val LATER_LABEL = "Later"
    /** The heads-up's keys: "shutdown:<day>", and once put off a fresh "shutdown:<day>:later:<ms>" so it posts again. */
    const val NOTICE_PREFIX = "shutdown:"

    /**
     * When "Later" pressed at [minute] brings the card back: an hour on, rounded up to the quarter hour, never after
     * 23:00; null when that would be less than a quarter of an hour away.
     */
    fun laterMinute(minute: Int): Int? {
        val m = minOf(((minute + LATER_MIN + 14) / 15) * 15, LATER_LATEST_MIN)
        return m.takeIf { it - minute >= LATER_AT_LEAST_MIN }
    }

    /** What pressing it says: "Shut down the day comes back at 21:15". */
    fun laterSaid(minute: Int): String = "Shut down the day comes back at ${LocalClock.formatMinute(minute)}"

    fun laterKey(day: Long, untilMs: Long): String = "$NOTICE_PREFIX$day:later:$untilMs"

    /** What switching the card off or on says. */
    fun cardSaid(on: Boolean): String = if (on) "The evening card is back · it comes at the end of each work day, or 20:00"
    else "No evening card or heads-up · Shut down the day is still in Ask → Daily"

    /** The pane's line about the card. */
    fun cardLine(off: Boolean, onLeave: Boolean): String = when {
        off -> "Evening card · off · open it here whenever you like"
        onLeave -> "Evening card · on · not on annual leave days"
        else -> "Evening card · on · with a heads-up when the evening starts"
    }

    /** A kind line when nothing was ticked off, never a bare "0". */
    const val NOTHING_DONE = "Nothing ticked off today · some days are like that"

    /** The minute the shutdown is offered on local day [epochDay]: the end of work on a work day, else 20:00. */
    fun startMinute(schedule: WorkSchedule, epochDay: Long, holidays: HolidayCalendar = HolidayCalendar.NONE): Int {
        val iso = CivilDate.isoDayOfWeek(epochDay)
        val h = schedule.hoursOn(iso)
        val workDay = schedule.enabled && iso in schedule.days && !h.crossesMidnight && !holidays.isHoliday(epochDay)
        return if (workDay && h.endMinute in WORK_END_RANGE) h.endMinute else DEFAULT_START_MIN
    }

    /**
     * What is left from today: open tasks that Today shows or that were planned for today or earlier, in the order
     * they'd be met — planned ones by time, then overdue by due date, then the rest by priority and age.
     * Later occurrences and snoozed items (waiting for their day), Someday and done items are not left over.
     */
    fun left(tasks: List<Task>, today: DayWindow): List<Task> = tasks
        .filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && !it.waitsForItsDay(today.epochDay) }
        // Parked in a week on the Board with no day yet (Week board slice 4): not today's.
        .filter { !WeekBoardRules.parked(it) }
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

    /** Today's finished tasks, newest first (ties by title), as the pane lists them. */
    fun doneToday(tasks: List<Task>, today: DayWindow): List<Task> = tasks
        .filter { it.lifecycle == Lifecycle.DONE && it.completedAtMs != null && it.completedAtMs in today }
        .sortedWith(compareByDescending<Task> { it.completedAtMs }.thenBy { it.title })

    /** "+ 3 more" past the first [DONE_SHOWN]; null when everything shows. */
    fun doneMore(total: Int): String? = (total - DONE_SHOWN).takeIf { it > 0 }?.let { "+ $it more" }

    /** Tomorrow's first commitment: "Tomorrow starts at 09:00 · Standup" (title cut like the glance's), or null. */
    fun firstLine(first: TomorrowRow?): String? = first?.let { "Tomorrow starts at ${it.time} · ${shorten(it.title)}" }

    fun count(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    /** The Tomorrow peek shows on Today from 17:00 (Meka 2026-10-26), or from the evening's start when that is earlier. */
    const val PEEK_FROM_MIN = 17 * 60

    fun peekShown(minute: Int, startMinute: Int, doneToday: Boolean): Boolean = doneToday || minute >= minOf(PEEK_FROM_MIN, startMinute)

    /**
     * The peek's line: "Tomorrow · Tue 27 Oct · 1 event · 2 tasks · first 07:40 Gym", or "Tomorrow · Tue 27 Oct ·
     * nothing planned yet".
     */
    fun peek(p: TomorrowPreview, dayLabel: String): TomorrowPeek {
        val head = "Tomorrow · $dayLabel"
        val bills = p.bills?.count ?: 0
        val lessons = p.study?.left ?: 0
        if (p.rows.isEmpty() && bills == 0 && lessons == 0) return TomorrowPeek("$head · nothing planned yet", "Tomorrow, $dayLabel: nothing planned yet")
        val bits = listOfNotNull(
            count(p.eventCount, "event").takeIf { p.eventCount > 0 },
            count(p.taskCount, "task").takeIf { p.taskCount > 0 },
            "${count(bills, "bill")} due".takeIf { bills > 0 },
            "${count(lessons, "lesson")} to study".takeIf { lessons > 0 },
            p.first?.let { "first ${it.time} ${shorten(it.title)}" },
        )
        return TomorrowPeek("$head · " + bits.joinToString(" · "), "Tomorrow, $dayLabel: " + bits.joinToString(", "))
    }

    /**
     * Shut down's card line about tomorrow (Meka 2026-10-26: it said "nothing planned for tomorrow yet" beside a task
     * he'd just added for tomorrow): "1 planned for tomorrow · first at 09:30", counting tomorrow's events and every
     * open task dated tomorrow, whoever made it (Ask, Live, the Board, a carry-over).
     */
    fun tomorrowBit(p: TomorrowPreview): String = when {
        p.rows.isEmpty() -> "nothing planned for tomorrow yet"
        else -> "${p.rows.size} planned for tomorrow" + (p.first?.let { " · first at ${it.time}" } ?: "")
    }

    /**
     * The Tomorrow sheet's moves: "Today" brings a task of tomorrow's to today (keeping its time while that is still
     * ahead), "Day after" puts it on the day after tomorrow (keeping its time). The minute to keep, or null.
     */
    fun movedMinute(task: Task, toDay: Long, today: Long, nowMinute: Int, calendar: LocalCalendar): Int? {
        val at = task.scheduledAtMs ?: return null
        val m = calendar.minuteOfDay(at)
        return if (toDay == today && m <= nowMinute) null else m
    }

    /** The bar's line after a move from the Tomorrow sheet: "Moved “Gym” to Today" · "Moved “Gym” to Thu 29 Oct". */
    fun movedLine(title: String, toDay: Long, today: Long): String =
        "Moved “${title.trim()}” to " + (if (toDay == today) "Today" else CivilDate.shortLabel(toDay))

    /** The Tomorrow sheet names this many bills on a line before "+ N more". */
    const val BILLS_SHOWN = 3

    /**
     * Tomorrow's bills from the renewals radar ([RenewalsView.all]): those due on [tomorrowDay] ("Due tomorrow · Car tax
     * £190 · Netflix £10.99 · + 1 more") and those whose last day to cancel is [tomorrowDay] ("Last day to cancel · Gym
     * £35"); null when neither. A cost shows only when he gave one.
     */
    fun bills(items: List<RenewalItem>, tomorrowDay: Long): TomorrowBills? {
        val due = items.filter { it.dueDay == tomorrowDay }.sortedBy { it.title.lowercase() }
        val cancel = items.filter { it.cancelByDay == tomorrowDay && it.dueDay != tomorrowDay }.sortedBy { it.title.lowercase() }
        if (due.isEmpty() && cancel.isEmpty()) return null
        fun bit(r: RenewalItem) = shorten(r.title) + (r.costPence?.takeIf { it > 0 }?.let { " " + RenewalRules.formatPence(it) } ?: "")
        fun names(rs: List<RenewalItem>) = rs.take(BILLS_SHOWN).map(::bit) +
            listOfNotNull((rs.size - BILLS_SHOWN).takeIf { it > 0 }?.let { "+ $it more" })
        val dueLine = due.takeIf { it.isNotEmpty() }?.let { "Due tomorrow · " + names(it).joinToString(" · ") }
        val cancelLine = cancel.takeIf { it.isNotEmpty() }?.let { "Last day to cancel · " + names(it).joinToString(" · ") }
        val spoken = listOfNotNull(
            due.takeIf { it.isNotEmpty() }?.let { "Due tomorrow: " + it.joinToString(", ", transform = ::bit) },
            cancel.takeIf { it.isNotEmpty() }?.let { "Last day to cancel: " + it.joinToString(", ", transform = ::bit) },
        ).joinToString(". ")
        return TomorrowBills(dueLine, cancelLine, due.size + cancel.size, spoken)
    }

    /**
     * Tomorrow on Today, slice 3: the task the sheet opens for editing, while it is still tomorrow's (moved to another
     * day, done or deleted, it's null and the editor closes).
     */
    fun taskOf(p: TomorrowPreview, taskId: String?): Task? = taskId?.let { id -> p.tasks.firstOrNull { it.id == id } }

    /** Swipe across Today to Tomorrow (left) and back on the Tomorrow sheet (right): the least travel, in dp. */
    const val SWIPE_MIN_DP = 64f
    /** …or a fifth of the screen's width when that's more (the open Fold), so a sideways nudge while scrolling never pages. */
    const val SWIPE_WIDTH_SHARE = 0.2f
    /** A quick flick pages from this speed (dp/s) once it has travelled [SWIPE_FLICK_MIN_DP]. */
    const val SWIPE_FLICK_DP_S = 800f
    const val SWIPE_FLICK_MIN_DP = 24f

    /**
     * Whether a sideways drag pages: [dragDp] and [velocityDp] are signed (left is negative); [toTomorrow] is Today →
     * Tomorrow (a left swipe), else the sheet back to Today (a right swipe). A drag the wrong way never pages.
     */
    fun swipePages(dragDp: Float, velocityDp: Float, widthDp: Float, toTomorrow: Boolean): Boolean {
        val d = if (toTomorrow) -dragDp else dragDp
        val v = if (toTomorrow) -velocityDp else velocityDp
        if (d <= 0f) return false
        return d >= maxOf(SWIPE_MIN_DP, widthDp * SWIPE_WIDTH_SHARE) || (v >= SWIPE_FLICK_DP_S && d >= SWIPE_FLICK_MIN_DP)
    }

    /** The task behind a Tomorrow row ("t-<id>"), so the sheet can tick or move it; null for an event. */
    fun taskIdOf(r: TomorrowRow): String? = if (!r.isEvent && r.id.startsWith("t-")) r.id.removePrefix("t-") else null

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

    /** When "Later" brings today's card back, 0 when it wasn't put off today. */
    fun laterUntil(day: Long): Long {
        val s = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID) ?: return 0
        return if (s[ShutdownFields.LATER_DAY].longOrNull == day) s[ShutdownFields.LATER_UNTIL].longOrNull ?: 0 else 0
    }

    /** "Later": today's card and heads-up come back at [untilMs] on every device. */
    fun putOff(day: Long, untilMs: Long) {
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(ShutdownFields.LATER_DAY to day.fv(), ShutdownFields.LATER_UNTIL to untilMs.fv()),
        )
    }

    fun cardOff(): Boolean = (replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(ShutdownFields.CARD_OFF)?.longOrNull ?: 0L) == 1L

    /** Switches the evening card and its heads-up off ([on] false) or back on, on every device. */
    fun setCard(on: Boolean) {
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(ShutdownFields.CARD_OFF to (if (on) 0L else 1L).fv()))
    }

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
        /** Today is booked as annual leave (Done for the day, completeness): no card unless he opens it. */
        onLeave: Boolean = false,
    ): ShutdownView {
        val now = nowMs()
        val minute = calendar.minuteOfDay(now)
        val start = ShutdownRules.startMinute(schedule, today.epochDay, holidays)
        val doneToday = doneDay() == today.epochDay
        val later = laterUntil(today.epochDay)
        val off = cardOff()
        val doneTasks = ShutdownRules.doneToday(all, today)
        val doneCount = doneTasks.size

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
        val tomorrowBit = ShutdownRules.tomorrowBit(preview)
        val cardLine = (if (left.isEmpty()) "Nothing left" else "${left.size} left") + " · " + tomorrowBit
        return ShutdownView(
            offered = !doneToday && !atWork && minute >= start && now >= later && !off && !onLeave,
            doneToday = doneToday,
            doneLine = if (doneToday) doneAtMs()?.let { "Day shut down at ${hhmm(it)}" } ?: "Day shut down" else null,
            startMinute = start,
            doneCount = doneCount,
            doneCountLine = if (doneCount == 0) ShutdownRules.NOTHING_DONE else "$doneCount done today",
            left = left,
            leftLine = leftLine,
            tomorrow = preview,
            cardLine = cardLine,
            evening = minute >= start,
            done = doneTasks.take(ShutdownRules.DONE_SHOWN).map { DoneRow(it.id, it.title, hhmm(it.completedAtMs!!)) },
            doneMore = ShutdownRules.doneMore(doneCount),
            firstLine = ShutdownRules.firstLine(preview.first),
            night = doneToday,
            laterUntilMs = later,
            laterOffered = !doneToday && !off && !onLeave && !atWork && now >= later && minute >= start &&
                ShutdownRules.laterMinute(minute) != null,
            cardOff = off,
            onLeave = onLeave,
            peek = if (ShutdownRules.peekShown(minute, start, doneToday)) ShutdownRules.peek(preview, CivilDate.shortLabel(tomorrow.epochDay)) else null,
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
            tasks = dayTasks,
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
