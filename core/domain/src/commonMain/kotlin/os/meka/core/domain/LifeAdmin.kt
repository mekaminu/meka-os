package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Life admin hour (V1), slice 1: once a week MEKA books one hour and gathers the small admin jobs (forms, calls,
 * renewals, replies) into it, instead of nagging all week.
 *
 * Meka picks a day and a time once (Saturday 10:00 by default). Each week the hour is booked afresh around what's there,
 * like Gym's sessions ([SessionRules]): the first free hour on that day from that time (clear of events with room either
 * side, work hours and date night), else the same time on a later day of that week; so a meeting that lands on it moves
 * it and nothing goes stale. The planner ([DayPlanner]) and Gym bookings keep it clear; Today's header says "Life admin
 * hour at 10:00 · 4 jobs" on the day; a heads-up at its start lists the jobs ([LifeAdminRules.notices]). One week can be
 * skipped (that hour is free again).
 *
 * The jobs are gathered, never moved: open tasks whose words say admin ([LifeAdminRules.isAdmin]: "Call the GP", "Renew
 * passport", "Fill in the school form", "Reply to the council") and the renewals radar's items needing attention, read
 * from what's already in MEKA. Nothing is sent, booked or paid.
 *
 * Stored as one `context_mode` entity, id [LifeAdminRules.ENTITY_ID] (ADR-008 addendum 2026-10-12): `weekday` (ISO
 * 1..7; 0 = off), `startMin`, `skipped` (comma-separated epoch days of the Mondays of skipped weeks). All LWW. Plain,
 * non-AI rules; nothing leaves Meka's own synced data.
 */
object LifeAdminFields {
    const val WEEKDAY = "weekday"
    const val START = "startMin"
    const val SKIPPED = "skipped"
}

/** The setting as stored. [weekday] 0 is off. */
data class LifeAdminSetting(val weekday: Int, val startMin: Int, val skipped: Set<Long> = emptySet()) {
    val on: Boolean get() = weekday in 1..7
}

enum class LifeAdminJobKind { TASK, RENEWAL }

/** One job gathered into the hour: "Renew passport" · "due Thu 12 Nov", or a task with no line. */
data class LifeAdminJob(val id: String, val kind: LifeAdminJobKind, val title: String, val line: String?, val spoken: String)

/** A week's hour as booked. */
data class LifeAdminSlot(
    val day: Long,
    /** The Monday of its week. */
    val weekStart: Long,
    val startMs: Long,
    val endMs: Long,
    /** "Sat 17 Oct 10:00–11:00". */
    val whenLine: String,
)

/** Today's header on the hour's day: "Life admin hour at 10:00 · 4 jobs". */
data class LifeAdminLine(val text: String, val spoken: String)

data class LifeAdminView(
    val on: Boolean,
    /** The chosen weekday (ISO 1..7), or the default when off, so the chips have one lit. */
    val weekday: Int,
    val startMin: Int,
    /** "Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · 4 jobs", or [LifeAdminRules.OFF_LINE]. */
    val summary: String,
    /** This week's hour while it's ahead or on (skipped or not); null when off, over or with no room. */
    val thisWeek: LifeAdminSlot?,
    /** This week's hour is skipped (the pane offers Keep it). */
    val skipped: Boolean,
    /** The next hour that will happen (this week's, else next week's); null when off or with no room. */
    val next: LifeAdminSlot?,
    /** What the hour gathers, renewals first; at most [LifeAdminRules.SHOWN]. */
    val jobs: List<LifeAdminJob>,
    /** All jobs gathered (more than [jobs] when cut). */
    val jobCount: Int,
) {
    companion object {
        val EMPTY = LifeAdminView(
            false, LifeAdminRules.DEFAULT_WEEKDAY, LifeAdminRules.DEFAULT_START, LifeAdminRules.OFF_LINE, null, false, null, emptyList(), 0,
        )
    }
}

object LifeAdminRules {
    const val ENTITY_ID = "life_admin"
    const val TITLE = "Life admin hour"
    const val DEFAULT_WEEKDAY = 6
    const val DEFAULT_START = 10 * 60
    const val LENGTH_MIN = 60
    /** The latest an hour may end on any day. */
    const val LAST_END_MIN = 21 * 60 + 30
    /** Jobs listed in the pane and the notice's count. */
    const val SHOWN = 8
    /** Today's header says it from this time on the day (or from midnight for an earlier hour) until it ends. */
    const val TODAY_FROM_MIN = 7 * 60

    val START_CHOICES: List<Int> = listOf(9 * 60, 10 * 60, 13 * 60, 18 * 60, 19 * 60, 20 * 60)
    val WEEKDAY_CHOICES: List<String> = LocalClock.DAY_SHORT

    const val OFF_LINE = "Off · pick a day and MEKA books one hour a week for the small admin jobs"
    const val NOTE = "MEKA gathers tasks like calls, forms, renewals and replies into the hour, and the renewals that need you. " +
        "The planner and Gym leave it clear; if a meeting lands on it, it moves. Skip a week and that hour is free again."
    const val OFF_LABEL = "Turn off"
    const val SKIP_LABEL = "Skip this week"
    const val KEEP_LABEL = "Keep it"
    const val NO_JOBS = "Nothing waiting yet · tasks like “Call the GP” or “Renew passport” gather here"

    /** First words that make a task admin ("Call the GP", "Renew passport", "Reply to Sam"). */
    val ADMIN_VERBS: Set<String> = setOf(
        "call", "ring", "phone", "renew", "reply", "respond", "email", "pay", "book", "sign", "register", "post", "return",
        "cancel", "apply", "fill", "chase", "submit", "claim", "contact", "print", "scan", "file", "update", "sort", "complete",
        "check", "send", "arrange", "request", "order", "transfer", "switch",
    )

    /** Words anywhere in a title that make a task admin ("School trip form", "Car insurance quote"). */
    val ADMIN_WORDS: Set<String> = setOf(
        "form", "forms", "insurance", "tax", "council", "bank", "passport", "licence", "license", "admin", "paperwork",
        "invoice", "refund", "mortgage", "pension", "hmrc", "dvla", "gp", "nhs", "solicitor", "bill", "bills", "letter",
        "renewal", "appointment", "prescription", "receipt", "warranty", "application", "direct", "debit", "isa",
        "subscription", "visa", "dentist", "optician", "vet", "mot",
    )

    fun read(fields: Map<String, FieldValue>?): LifeAdminSetting? {
        if (fields == null) return null
        val weekday = fields[LifeAdminFields.WEEKDAY]?.longOrNull?.toInt() ?: return null
        val start = fields[LifeAdminFields.START]?.longOrNull?.toInt()?.takeIf { it in 0..LAST_END_MIN - LENGTH_MIN } ?: DEFAULT_START
        return LifeAdminSetting(weekday, start, DateNightRules.decodeDays(fields[LifeAdminFields.SKIPPED]?.textOrNull))
    }

    /** The words of a title, lower case, split on anything that isn't a letter or a digit (no regex: Kotlin/Native). */
    fun words(title: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in title.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * A task title that reads as admin: it starts with one of [ADMIN_VERBS] (or "fill in", "sort out" and the like), or
     * names one of [ADMIN_WORDS]. "Call the GP", "Renew passport", "School trip form", "Car insurance"; not "Gym",
     * "Read a book" (book isn't its first word) or "Plan the garden".
     */
    fun isAdmin(title: String): Boolean {
        val w = words(title)
        if (w.isEmpty()) return false
        return w.first() in ADMIN_VERBS || w.any { it in ADMIN_WORDS }
    }

    /**
     * The jobs the hour gathers: the renewals radar's items needing attention (overdue, cancel-by or due within their
     * lead time; [renewals] is the radar's `attention` list), then open tasks (inbox or active, not waiting for a later
     * day, not a calendar event's prep) that read as admin and aren't planned for a set time other than within the hour
     * [slot], higher priority first, then oldest first.
     */
    fun jobs(tasks: List<Task>, renewals: List<RenewalItem>, today: Long, slot: LifeAdminSlot?, cal: LocalCalendar): List<LifeAdminJob> {
        val out = mutableListOf<LifeAdminJob>()
        for (r in renewals.sortedBy { it.dueDay }) {
            val line = when {
                r.state == RenewalState.OVERDUE -> "overdue since ${CivilDate.shortLabel(r.dueDay)}"
                else -> "due ${dueLabel(r.dueDay, today)}"
            }
            out += LifeAdminJob("renewal:${r.id}", LifeAdminJobKind.RENEWAL, r.title, line, "${r.title}, $line")
        }
        val open = tasks.filter { t ->
            (t.lifecycle == Lifecycle.INBOX || t.lifecycle == Lifecycle.ACTIVE) && !t.waitsForItsDay(today) && t.eventId == null &&
                isAdmin(t.title) && (t.scheduledAtMs == null || (slot != null && t.scheduledAtMs >= slot.startMs && t.scheduledAtMs < slot.endMs))
        }.sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.createdAtMs }.thenBy { it.id })
        for (t in open) {
            val line = t.dueAtMs?.let { "due " + dueLabel(cal.epochDayOf(it), today) }
            out += LifeAdminJob("task:${t.id}", LifeAdminJobKind.TASK, t.title, line, listOfNotNull(t.title, line).joinToString(", "))
        }
        return out
    }

    private fun dueLabel(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** "Saturdays from 10:00". */
    fun everyLine(s: LifeAdminSetting): String = "${CivilDate.DAY_LONG[s.weekday - 1]}s from ${LocalClock.formatMinute(s.startMin)}"

    /**
     * The week's hour (the week of Monday [weekStart]): the first free [LENGTH_MIN] on the chosen day from its time (ending
     * by [LAST_END_MIN]), else from the same time on each later day of that week. [busy] gives a day's busy blocks
     * ([SessionRules.busyOn] plus date night). Worked out from the whole day, so it stays put while the day goes by. Null
     * when off or the week has no room.
     */
    fun slot(s: LifeAdminSetting?, weekStart: Long, cal: LocalCalendar, busy: (Long) -> List<DayPlanner.Slot>): LifeAdminSlot? {
        if (s == null || !s.on) return null
        val first = weekStart + s.weekday - 1
        val window = s.startMin until LAST_END_MIN
        for (d in first..weekStart + 6) {
            val slot = SessionRules.slotOn(d, window, LENGTH_MIN, busy(d), Long.MIN_VALUE, cal) ?: continue
            val whenLine = "${CivilDate.shortLabel(d)} ${LocalClock.formatMinute(cal.minuteOfDay(slot.startMs))}–" +
                LocalClock.formatMinute(cal.minuteOfDay(slot.endMs))
            return LifeAdminSlot(d, weekStart, slot.startMs, slot.endMs, whenLine)
        }
        return null
    }

    /** This week's hour as kept (not skipped), for the planner, Gym and moving a late task; null otherwise. */
    fun kept(s: LifeAdminSetting?, slot: LifeAdminSlot?): LifeAdminSlot? =
        slot?.takeIf { s != null && s.on && it.weekStart !in s.skipped }

    /** The planner's block on [day] ("Life admin hour", shown with the kept-free blocks), or null. */
    fun block(s: LifeAdminSetting?, slot: LifeAdminSlot?, day: Long): DayPlanner.MealBlock? =
        kept(s, slot)?.takeIf { it.day == day }?.let { DayPlanner.MealBlock(TITLE, it.startMs, it.endMs) }

    /** The planner's slot on [day], or null. */
    fun keptSlot(s: LifeAdminSetting?, slot: LifeAdminSlot?, day: Long): DayPlanner.Slot? =
        kept(s, slot)?.takeIf { it.day == day }?.let { DayPlanner.Slot(it.startMs, it.endMs) }

    fun jobsCount(n: Int): String = when (n) { 0 -> "nothing waiting"; 1 -> "1 job"; else -> "$n jobs" }

    /**
     * The pane's view. [thisWeek] is this week's hour ([slot] for this week's Monday), [nextWeek] next week's; this week's
     * shows while it's ahead or on (skipped or not), the next kept one is "next".
     */
    fun view(
        s: LifeAdminSetting?, thisWeek: LifeAdminSlot?, nextWeek: LifeAdminSlot?, jobs: List<LifeAdminJob>, nowMs: Long,
    ): LifeAdminView {
        if (s == null || !s.on) return LifeAdminView.EMPTY.copy(
            weekday = s?.weekday?.takeIf { it in 1..7 } ?: DEFAULT_WEEKDAY, startMin = s?.startMin ?: DEFAULT_START,
        )
        val current = thisWeek?.takeIf { it.endMs > nowMs }
        val skipped = current != null && current.weekStart in s.skipped
        val next = current?.takeIf { !skipped } ?: nextWeek?.takeIf { it.weekStart !in s.skipped }
        val nextPart = when {
            current != null && !skipped -> "this week ${current.whenLine}"
            next != null -> (if (skipped) "skipped this week · " else "") + "next ${next.whenLine}"
            else -> "no free hour this week"
        }
        val summary = "${everyLine(s)} · $nextPart · ${jobsCount(jobs.size)}"
        return LifeAdminView(true, s.weekday, s.startMin, summary, current, skipped, next, jobs.take(SHOWN), jobs.size)
    }

    /** Today's header on the hour's day, from [TODAY_FROM_MIN] (or its start, if earlier) until it ends; null otherwise. */
    fun todayLine(v: LifeAdminView, today: Long, nowMs: Long, cal: LocalCalendar): LifeAdminLine? {
        val h = v.thisWeek ?: return null
        if (v.skipped || h.day != today || nowMs >= h.endMs) return null
        val startMin = cal.minuteOfDay(h.startMs)
        if (cal.minuteOfDay(nowMs) < minOf(TODAY_FROM_MIN, startMin)) return null
        val jobs = jobsCount(v.jobCount)
        val hhmm = LocalClock.formatMinute(startMin)
        return if (nowMs < h.startMs) LifeAdminLine("$TITLE at $hhmm · $jobs", "$TITLE at $hhmm today, $jobs.")
        else LifeAdminLine("$TITLE now · $jobs", "It's your $TITLE, $jobs.")
    }

    /**
     * The heads-up at the hour's start (source [NoticeSource.LIFE_ADMIN]) through the governor: "Life admin hour · 4 jobs"
     * · "Renew passport · Call the GP · and 2 more", stale once it ends; none when skipped or nothing is waiting.
     */
    fun notices(v: LifeAdminView, nowMs: Long): List<Notice> {
        val h = v.thisWeek ?: return emptyList()
        if (v.skipped || v.jobCount == 0 || nowMs >= h.endMs) return emptyList()
        val named = v.jobs.take(2).joinToString(" · ") { it.title }
        val more = v.jobCount - minOf(2, v.jobs.size)
        val text = if (more > 0) "$named · and $more more" else named
        return listOf(Notice(
            key = "life-admin:${h.day}:start", source = NoticeSource.LIFE_ADMIN, tier = NoticeTier.HEADS_UP,
            title = "$TITLE · ${jobsCount(v.jobCount)}", text = text, atMs = h.startMs, target = NoticeTarget.TODAY,
            expiresAtMs = h.endMs, precision = NoticePrecision.CLOCK,
        ))
    }

    /** The line after Skip this week / Keep it: "Skipped this week's life admin hour · Sat 17 Oct is free again". */
    fun skipLine(slot: LifeAdminSlot?, skip: Boolean): String {
        val whenPart = slot?.let { CivilDate.shortLabel(it.day) }
        return if (skip) "Skipped this week's life admin hour" + (whenPart?.let { " · $it is free again" } ?: "")
        else "This week's life admin hour is kept" + (slot?.let { " · ${it.whenLine}" } ?: "")
    }
}

/** The life admin hour's setting on a replica. Every write is an op: offline-first, synced. */
class LifeAdmin(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun setting(): LifeAdminSetting? = LifeAdminRules.read(replica.entity(EntityTypes.CONTEXT_MODE, LifeAdminRules.ENTITY_ID)?.fields)

    private fun thisWeek(): Long = GoalRules.weekStart(calendar.epochDayOf(nowMs()))

    /** Sets the day (ISO 1..7) and time. False (nothing written) when they don't make sense or nothing changes. */
    fun set(weekday: Int, startMin: Int): Boolean {
        if (weekday !in 1..7 || startMin !in 0..LifeAdminRules.LAST_END_MIN - LifeAdminRules.LENGTH_MIN) return false
        val old = setting()
        if (old != null && old.weekday == weekday && old.startMin == startMin) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, LifeAdminRules.ENTITY_ID, mapOf(
            LifeAdminFields.WEEKDAY to weekday.toLong().fv(),
            LifeAdminFields.START to startMin.toLong().fv(),
        ))
        return true
    }

    /** Turns the hour off; the day and time are kept for turning it on again. */
    fun off(): Boolean {
        setting()?.takeIf { it.on } ?: return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, LifeAdminRules.ENTITY_ID, mapOf(LifeAdminFields.WEEKDAY to 0L.fv()))
        return true
    }

    /** Skips this week's hour (it's free again), or keeps it. False when nothing changes. */
    fun skipThisWeek(skip: Boolean): Boolean {
        val s = setting()?.takeIf { it.on } ?: return false
        val week = thisWeek()
        if ((week in s.skipped) == skip) return false
        // Weeks already gone are dropped as the list is written.
        val kept = s.skipped.filter { it >= week }.toSet()
        val next = if (skip) kept + week else kept - week
        replica.commitLocal(EntityTypes.CONTEXT_MODE, LifeAdminRules.ENTITY_ID, mapOf(LifeAdminFields.SKIPPED to DateNightRules.encodeDays(next).fv()))
        return true
    }
}
