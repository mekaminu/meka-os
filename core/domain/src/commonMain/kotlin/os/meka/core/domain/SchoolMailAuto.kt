package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * School emails first, slice 3a (Meka 2026-11-24 17:38: "I need the AI to auto create stuff in my calendar, set a
 * notification a few days and a day before the event … add it as a task a day before and in my task list on the day";
 * non-AI, pure): **the school's dated things are done for him, no tap**. While "School events from the school's emails"
 * is on (What Navilife may do → Your mail; on unless he turns it off) and nothing is paused, each open school find with a
 * day still to come is added as Add would add it, and more:
 *
 * - an **event** (a fair, a trip, a non-uniform day): its School line (so Today, Kids' week and School's own heads-up
 *   at 19:00 the evening before follow), a **task on the day** at its time with a reminder **3 days before** at 09:00,
 *   and a **task the day before** ("Get ready for School fair: £1 · Bring a named water bottle") reminding at 09:00
 *   that day;
 * - a **deadline** (a payment, a form back): its School line (School reminds 2 days before at 09:00) and a task due that
 *   day; a dated ask ("please send in a coat by Friday") is a task due that day reminding 2 days before;
 * - a **day off**: its School line (Today's header says it).
 *
 * A reminder whose time has gone (or is under half an hour away) is left out, as is a task the day before when that day
 * has gone. Weekly kit days and undated asks still wait for his Add. Everything is one Activity entry per find ("Done for
 * you", Undo removes all it made and the card comes back with Add; it never adds itself again: [MailFindFields.AUTO_AT]).
 *
 * The finds come from the server's fixed rules ([SchoolMailRules], [SchoolBodyRules]), never a model, so this is a
 * deterministic rule his switch made (ADR-006 Level 4 under a rule); an email's words never lift it. Only his own School
 * lines and tasks change: nothing is paid, signed, booked, replied to or opened. At most [PER_DAY] finds a day.
 *
 * Stored as one `context_mode` entity, id [ENTITY_ID]: `on` (Bool) and `changedAtMs`, LWW. Absent = on (he asked for it).
 */

/** One task the plan makes ([SchoolAutoRules.plan]): on [day] at [minute] (null: no time), reminding at [remindAtMs] (null: none). */
data class SchoolAutoTask(
    val id: String,
    val title: String,
    val day: Long,
    val minute: Int?,
    val remindAtMs: Long?,
    val notes: String,
    /** A deadline's task is due that day (its due date set too). */
    val due: Boolean = false,
)

/** What one find makes: its School line (or not) and its tasks. */
data class SchoolAutoPlan(val findId: String, val schoolLine: Boolean, val tasks: List<SchoolAutoTask>)

object SchoolAutoFields {
    const val ON = "on"
    const val CHANGED_AT = "changedAtMs"
}

object SchoolAutoRules {
    const val ENTITY_ID = "school_mail_auto"
    const val RULE_ID = "mail.school"
    const val SOURCE = "auto:school_mail"
    const val TITLE = "School events from the school's emails"
    const val ON_LINE = "Puts each school date in School with reminders and tasks the day before and on the day · " +
        "each with Undo · never pays, signs or replies"
    const val OFF_LINE = "Off · each waits in Needs you for your Add"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"
    const val WHY = "“School events from the school's emails” is on in What Navilife may do"

    /** At most this many finds are added on their own in a day (the rest wait as cards). */
    const val PER_DAY = 10
    /** An event's early reminder: this many days before, at [REMIND_MINUTE]. */
    const val EARLY_DAYS = 3
    /** A dated ask's reminder: this many days before its day. */
    const val ASK_LEAD_DAYS = 2
    const val REMIND_MINUTE = 9 * 60
    /** A reminder closer than this to now is left out. */
    const val MIN_LEAD_MS = 30 * 60_000L
    private const val MAX_TITLE = 120

    fun read(fields: Map<String, FieldValue>?): Boolean = fields?.get(SchoolAutoFields.ON)?.boolOrNull != false

    fun switchFields(on: Boolean, nowMs: Long): Map<String, FieldValue> =
        mapOf(SchoolAutoFields.ON to on.fv(), SchoolAutoFields.CHANGED_AT to nowMs.fv())

    fun switchedLine(on: Boolean): String =
        if (on) "Navilife will put the school's dates in School with reminders and tasks for you · each in Activity with Undo"
        else "The school's dates wait for your Add again"

    /** The row on What Navilife may do (under Your mail). */
    fun row(on: Boolean, paused: Boolean) = AutonomyRow(
        key = ENTITY_ID,
        title = TITLE,
        line = when {
            on && paused -> AutonomyRules.PAUSED_LINE
            on -> ON_LINE
            else -> OFF_LINE
        },
        locked = false,
        action = if (on) TURN_OFF else TURN_ON,
        onItsOwn = on && !paused,
    )

    /** One Activity entry per find, so two devices adding the same find write the same entry. */
    fun activityId(findId: String): String = "sa" + ActivityRules.fnv64("schoolauto:$findId")

    /** One task per find and role ("day", "prep", "due"), so both devices land on the same task. */
    fun taskId(findId: String, role: String): String = "st" + ActivityRules.fnv64("schoolauto:$role:$findId")

    private fun isEvent(f: MailFind): Boolean =
        !SchoolMailRules.isAsk(f) && SchoolKind.of(f.schoolKind) != SchoolKind.OFF && !SchoolRules.isDue(f.what ?: "")

    /**
     * Whether [f] is added on its own: an open school find never added on its own before, with a day still to come
     * (from [today]), not a weekly kit day or an undated ask, and not already in School ([SchoolMailRules.showable]).
     */
    fun eligible(f: MailFind, items: List<SchoolItem>, today: Long): Boolean {
        if (f.kind != SchoolMailRules.KIND_SCHOOL || f.state != MailFindState.OPEN || f.autoAtMs != null) return false
        if (SchoolMailRules.isWeekly(f)) return false
        if (SchoolMailRules.isAsk(f) && SchoolMailRules.undated(f)) return false
        if (f.renewDay < today) return false
        return SchoolMailRules.showable(f, items, today)
    }

    /** The finds to add now: [eligible], soonest first, at most what is left of today's [PER_DAY] after [addedToday]. */
    fun due(finds: List<MailFind>, items: List<SchoolItem>, today: Long, addedToday: Int): List<MailFind> {
        val room = PER_DAY - addedToday
        if (room <= 0) return emptyList()
        return finds.filter { eligible(it, items, today) }
            .sortedWith(compareBy<MailFind> { it.renewDay }.thenBy { it.id }).take(room)
    }

    /** How many school finds were added on their own today (their marks). */
    fun addedToday(finds: List<MailFind>, nowMs: Long, cal: LocalCalendar): Int {
        val today = cal.epochDayOf(nowMs)
        return finds.count { f -> f.kind == SchoolMailRules.KIND_SCHOOL && f.autoAtMs?.let { cal.epochDayOf(it) == today } == true }
    }

    private fun clip(s: String): String = if (s.length <= MAX_TITLE) s else s.take(MAX_TITLE - 1).trimEnd(' ', ',', '·') + "…"

    private fun notesOf(f: MailFind): List<String> = f.notes?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** "School fair · Alex" (the child named, when one is). */
    fun eventTitle(f: MailFind): String {
        val what = f.what ?: "School event"
        val who = f.who?.takeIf { it.isNotBlank() && !what.contains(it, ignoreCase = true) }
        return clip(listOfNotNull(what, who).joinToString(" · "))
    }

    /** "Get ready for School fair: £1 · Bring a named water bottle" (what it costs and what to bring, wear or do). */
    fun prepTitle(f: MailFind): String {
        val bits = listOfNotNull(f.pence?.let(RenewalRules::formatPence)) + notesOf(f).take(2)
        return clip("Get ready for ${eventTitle(f)}" + if (bits.isEmpty()) "" else ": " + bits.joinToString(" · "))
    }

    /** The tasks' notes: where it came from, where and when it is, what to bring, and that nothing is paid or sent. */
    fun taskNotes(f: MailFind): String = listOfNotNull(
        "From ${SchoolMailRules.title(f)}'s email" + (f.subject?.let { " “$it”" } ?: "") + " · ${f.account} mail.",
        f.startMinute?.let { "When: " + SchoolBodyRules.timeLabel(it, f.endMinute) },
        f.place?.let { "Where: $it" },
        f.pence?.let { "Cost: " + RenewalRules.formatPence(it) },
        notesOf(f).takeIf { it.isNotEmpty() }?.let { "Bring / do: " + it.joinToString(" · ") },
        "Added for you ($TITLE is on). Navilife never pays, signs or replies.",
    ).joinToString("\n")

    private fun remindAt(day: Long, nowMs: Long, cal: LocalCalendar): Long? =
        cal.toEpochMs(day, REMIND_MINUTE).takeIf { it > nowMs + MIN_LEAD_MS }

    /** What adding [f] on its own makes, as of [nowMs]. */
    fun plan(f: MailFind, nowMs: Long, cal: LocalCalendar): SchoolAutoPlan {
        val today = cal.epochDayOf(nowMs)
        val day = f.renewDay
        val notes = taskNotes(f)
        if (SchoolMailRules.isAsk(f)) {
            val t = SchoolAutoTask(
                taskId(f.id, "due"), clip(SchoolMailRules.taskTitle(f)), day, null,
                remindAt(day - ASK_LEAD_DAYS, nowMs, cal), notes, due = true,
            )
            return SchoolAutoPlan(f.id, schoolLine = false, tasks = listOf(t))
        }
        if (SchoolKind.of(f.schoolKind) == SchoolKind.OFF) return SchoolAutoPlan(f.id, schoolLine = true, tasks = emptyList())
        if (!isEvent(f)) {
            // A deadline: School reminds DUE_LEAD_DAYS before; the task is due that day.
            val title = clip(listOfNotNull(eventTitle(f), f.pence?.let(RenewalRules::formatPence)).joinToString(" · "))
            return SchoolAutoPlan(f.id, schoolLine = true, tasks = listOf(SchoolAutoTask(taskId(f.id, "due"), title, day, null, null, notes, due = true)))
        }
        val tasks = mutableListOf<SchoolAutoTask>()
        if (day - 1 >= today) tasks += SchoolAutoTask(taskId(f.id, "prep"), prepTitle(f), day - 1, null, remindAt(day - 1, nowMs, cal), notes)
        tasks += SchoolAutoTask(
            taskId(f.id, "day"), eventTitle(f), day, f.startMinute?.takeIf { day > today || it > cal.minuteOfDay(nowMs) },
            remindAt(day - EARLY_DAYS, nowMs, cal), notes,
        )
        return SchoolAutoPlan(f.id, schoolLine = true, tasks = tasks)
    }

    private fun weekday(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        else -> CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(day) - 1].take(3)
    }

    private fun days(days: List<Long>, today: Long): String = days.distinct().sorted().map { weekday(it, today) }.let { w ->
        if (w.size <= 1) w.joinToString() else w.dropLast(1).joinToString(", ") + " and " + w.last()
    }

    /**
     * Activity's line: "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School, reminders Wed and Fri, tasks
     * Fri and Sat" / "From the school: Trip payment · due Fri 20 Nov · in School, task Fri".
     */
    fun summary(f: MailFind, p: SchoolAutoPlan, nowMs: Long, cal: LocalCalendar): String {
        val today = cal.epochDayOf(nowMs)
        val what = f.what ?: "School date"
        val whenText = when {
            SchoolKind.of(f.schoolKind) == SchoolKind.OFF -> SchoolRules.spanLabel(f.renewDay, maxOf(f.renewDay, f.endDay ?: f.renewDay))
            SchoolMailRules.isAsk(f) || !isEvent(f) -> "due " + RenewalRules.dayWord(f.renewDay, today)
            else -> RenewalRules.dayWord(f.renewDay, today) + (f.startMinute?.let { ", " + SchoolBodyRules.timeLabel(it, f.endMinute) } ?: "")
        }
        val reminders = p.tasks.mapNotNull { it.remindAtMs?.let(cal::epochDayOf) }
        val made = listOfNotNull(
            "in School".takeIf { p.schoolLine },
            reminders.takeIf { it.isNotEmpty() }?.let { (if (it.size == 1) "reminder " else "reminders ") + days(it, today) },
            p.tasks.takeIf { it.isNotEmpty() }?.let { t -> (if (t.size == 1) "task " else "tasks ") + days(t.map { it.day }, today) },
        ).joinToString(", ")
        return "From the school: $what · $whenText" + if (made.isEmpty()) "" else " · $made"
    }

    /** Activity's detail: where it came from ("Personal mail · “Christmas fair this Saturday”"). */
    fun detail(f: MailFind): String = MailRenewalRules.fromLine(f)
}
