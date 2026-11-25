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
    const val ON_LINE = "Puts each school date in School and each school event in your calendar, with reminders and " +
        "tasks the day before and on the day · each with Undo · never pays, signs or replies"
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
    fun eligible(f: MailFind, items: List<SchoolItem>, today: Long, childYears: Set<Int>? = null): Boolean {
        if (f.kind != SchoolMailRules.KIND_SCHOOL || f.state != MailFindState.OPEN || f.autoAtMs != null) return false
        // Slice 4a: an email for another year group is summarised, never added on its own.
        if (SchoolYearRules.otherYear(f, childYears)) return false
        // Slice 4b: a date that can't be read for sure stays a card to confirm, never a guess.
        if (f.unclearDate) return false
        if (SchoolMailRules.isWeekly(f)) return false
        if (SchoolMailRules.isAsk(f) && SchoolMailRules.undated(f)) return false
        if (f.renewDay < today) return false
        return SchoolMailRules.showable(f, items, today)
    }

    /** The finds to add now: [eligible], soonest first, at most what is left of today's [PER_DAY] after [addedToday]. */
    fun due(finds: List<MailFind>, items: List<SchoolItem>, today: Long, addedToday: Int, childYears: Set<Int>? = null): List<MailFind> {
        val room = PER_DAY - addedToday
        if (room <= 0) return emptyList()
        // Slice 4a: the same thing sent again (a reminder, the other account, "Re: …") is added once: never when one
        // like it was added on its own before (kept through Undo, so an undone one doesn't come back), and once a batch.
        val done = finds.filter { it.kind == SchoolMailRules.KIND_SCHOOL && it.autoAtMs != null }
        val out = mutableListOf<MailFind>()
        for (f in finds.filter { eligible(it, items, today, childYears) }.sortedWith(compareBy<MailFind> { it.renewDay }.thenBy { it.receivedDay }.thenBy { it.id })) {
            if (done.any { sameThing(it, f) } || out.any { sameThing(it, f) }) continue
            out += f
            if (out.size >= room) break
        }
        return out
    }

    private val NOISE = Regex("""\b(?:reminder|re|fwd?|update|updated|urgent|important|final|change|changed|school|the|our|a)\b""")

    private fun normTitle(s: String?): String =
        (s ?: "").lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(NOISE, " ").replace(Regex("""\s+"""), " ").trim()

    /** Slice 4a: whether [a] and [b] are the same school thing: the same day and kind, the same child (or either for all), the same name give or take "Reminder:". */
    fun sameThing(a: MailFind, b: MailFind): Boolean {
        if (a.id == b.id) return true
        if (a.renewDay != b.renewDay || (a.schoolKind ?: "") != (b.schoolKind ?: "")) return false
        // A trip and its payment on the same day are two things.
        if (SchoolRules.isDue(a.what ?: "") != SchoolRules.isDue(b.what ?: "")) return false
        val wa = a.who.orEmpty(); val wb = b.who.orEmpty()
        if (wa.isNotEmpty() && wb.isNotEmpty() && !wa.equals(wb, ignoreCase = true)) return false
        val ta = normTitle(a.what); val tb = normTitle(b.what)
        if (ta.length < 3 || tb.length < 3) return ta == tb && ta.isNotEmpty()
        return ta == tb || ta.contains(tb) || tb.contains(ta)
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

/*
 * School emails first, slice 3b (Meka 2026-11-24 17:38: "I need the AI to auto create stuff in my calendar"): **the
 * school's events go into his real calendar too**. For a find [SchoolAutoRules] added on its own that is an event (not a
 * deadline, an ask or a day off) and is still to come, one ordinary calendar add (`event_edit`, five-second Undo, sent by
 * the server) goes to the account Add event remembers, else the first that allows editing: its title, its time (all day
 * when the email gave none), the place, and what to bring or do in its notes. The edit's id is fixed per find
 * ([editId]), so both devices land on one add, and nothing is added when that day already holds an event of the same
 * name (the school's own invite, or one he added himself). The reminders stay on the tasks (Navilife's own reminders,
 * 3 days and 1 day before), so his calendar is not given its own alerts twice.
 *
 * Inside Navilife the task on the day stands for that time (as Plan my day's blocks do), so the event is left out of
 * Today and the Calendar tab while that task is there ([withoutSchoolEvents]); it shows again if he deletes the task.
 *
 * Undo in Activity takes it back too: inside the five seconds the add is undone; once the calendar has it, it is
 * deleted there ([deleteId], fixed too); while it is still on its way, it is deleted as soon as it lands.
 */
object SchoolCalendarRules {
    /** An event with a start and no end lasts this long. */
    const val DEFAULT_MINUTES = 60
    private const val DAY_MS = 86_400_000L
    /** Undo keeps trying to take a sent event back for this long. */
    const val TAKE_BACK_MS = 7 * DAY_MS

    fun editId(findId: String): String = "se" + ActivityRules.fnv64("schoolcal:add:$findId")
    fun deleteId(findId: String): String = "sx" + ActivityRules.fnv64("schoolcal:delete:$findId")

    /** Whether [plan] puts [f] in his calendar: an event (it has a task on the day) still to come. */
    fun wanted(f: MailFind, plan: SchoolAutoPlan): Boolean =
        plan.schoolLine && plan.tasks.any { it.id == SchoolAutoRules.taskId(f.id, "day") }

    /** The event: its title, its time (all day without one), the place, and the notes. */
    fun draft(f: MailFind, cal: LocalCalendar): EventDraft {
        val day = f.renewDay
        val start = f.startMinute
        val (s, e) = if (start == null) {
            day * DAY_MS to (maxOf(day, f.endDay ?: day) + 1) * DAY_MS
        } else {
            val from = cal.toEpochMs(day, start)
            from to (f.endMinute?.takeIf { it > start }?.let { cal.toEpochMs(day, it) } ?: (from + DEFAULT_MINUTES * 60_000L))
        }
        return CalendarEditRules.clean(EventDraft(SchoolAutoRules.eventTitle(f), s, e, start == null, f.place, notes(f)))
    }

    /** The event's notes: what it costs and what to bring, where it came from, and that Navilife added it. */
    fun notes(f: MailFind): String = listOfNotNull(
        f.pence?.let { "Cost: " + RenewalRules.formatPence(it) },
        f.notes?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { "Bring / do: " + it.joinToString(" · ") },
        "From ${SchoolMailRules.title(f)}'s email" + (f.subject?.let { " “$it”" } ?: "") + ".",
        "Added by Navilife · Undo in Navilife's Activity.",
    ).joinToString("\n")

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    private fun dayOf(e: CalendarEvent, cal: LocalCalendar): Long = if (e.allDay) e.startAtMs.floorDiv(DAY_MS) else cal.epochDayOf(e.startAtMs)

    /**
     * Whether [f]'s day already holds an event of the same name in [events] (the school's own invite, or his), so
     * nothing is added. Navilife's own provisional adds don't count.
     */
    fun alreadyThere(f: MailFind, events: List<CalendarEvent>, cal: LocalCalendar): Boolean {
        val what = norm(f.what ?: return false).takeIf { it.length >= 4 } ?: return false
        return events.any { e ->
            if (e.isProvisional || dayOf(e, cal) != f.renewDay) return@any false
            val t = norm(e.title)
            t.length >= 4 && (t.contains(what) || what.contains(t))
        }
    }

    /** The event a sent add became in the mirror [events] (same account, title and start), or null while it isn't there. */
    fun sentEvent(edit: EventEdit, events: List<CalendarEvent>): CalendarEvent? {
        val d = edit.draft ?: return null
        return events.firstOrNull { e ->
            !e.isProvisional && e.provider == edit.provider && e.account == edit.account &&
                e.startAtMs == d.startAtMs && e.allDay == d.allDay && e.title.trim() == d.title.trim()
        }
    }

    /** What Undo still has to do with [edit] (the school add) once its Activity entry is undone. */
    sealed class TakeBack {
        data object Nothing : TakeBack()
        /** Still inside its five seconds: undo the add. */
        data object UndoAdd : TakeBack()
        /** In the calendar: delete [event]. */
        data class Delete(val event: CalendarEvent) : TakeBack()
        /** Sent or on its way, not in the mirror yet: wait. */
        data object Wait : TakeBack()
    }

    fun takeBack(edit: EventEdit?, deleted: Boolean, events: List<CalendarEvent>, nowMs: Long): TakeBack {
        if (edit == null || edit.undone || deleted) return TakeBack.Nothing
        return when (edit.state(nowMs)) {
            EventEditState.WAITING -> TakeBack.UndoAdd
            EventEditState.REFUSED, EventEditState.FAILED, EventEditState.UNDONE, EventEditState.CLASH -> TakeBack.Nothing
            EventEditState.SENDING, EventEditState.DONE -> when {
                nowMs - edit.createdAtMs > TAKE_BACK_MS -> TakeBack.Nothing
                else -> sentEvent(edit, events)?.let { TakeBack.Delete(it) } ?: TakeBack.Wait
            }
        }
    }

    /** Activity's line once the event is in his calendar: "… · in Google and School, reminders …". */
    fun withCalendar(summary: String, provider: String): String {
        val p = CalendarEditRules.providerName(provider)
        return when {
            summary.contains(" · in $p and School") -> summary
            summary.contains(" · in School") -> summary.replaceFirst(" · in School", " · in $p and School")
            else -> "$summary · in $p"
        }
    }

    /**
     * [events] without the school events Navilife added whose task on the day is still there ([liveDayTasks] holds
     * those finds' ids): the provisional event of such an add and the mirrored event it became.
     */
    fun withoutSchoolEvents(events: List<CalendarEvent>, edits: List<EventEdit>, liveDayTasks: Set<String>): List<CalendarEvent> {
        if (liveDayTasks.isEmpty()) return events
        val ids = liveDayTasks.mapTo(HashSet()) { editId(it) }
        val adds = edits.filter { it.id in ids && it.kind == EventEditKind.ADD && !it.undone && it.status != EventEditStatus.REFUSED && it.status != EventEditStatus.FAILED }
        if (adds.isEmpty()) return events
        val addIds = adds.mapTo(HashSet()) { it.id }
        return events.filterNot { e ->
            val pending = PendingEditRules.editIdOf(e.id)
            if (pending != null) pending in addIds
            else adds.any { sentEvent(it, listOf(e)) != null }
        }
    }
}

/*
 * School emails first, slice 3c (Meka 2026-11-24 17:38: "…set a notification… so I remember"): what was done for him
 * from the school's emails is **said**, as his wife's requests are: one "Done for you" row per school date in Needs you
 * (beside From the school, both apps, the same row as hers: "From the school · 22:14" over Activity's line, with Undo,
 * Change and Clear) and one heads-up as it is done ("Done for you · from the school" · "Christmas fair · Sat 28 Nov,
 * 12:00–16:00 · in Google and School, reminders Wed and Fri, tasks Fri and Sat"). Undo is Activity's Undo (everything
 * it made goes, the calendar event too, and the card comes back for Add); Change opens the task on the day (else the
 * task it made); Clear only takes the row away (a mark on the Activity entry, synced). A date added from an older
 * email (the 30-day catch-up) gets its row but no heads-up of its own. The line is Navilife's words built from the
 * server's fixed finds, never the email's text beyond its title.
 */
object SchoolDoneRules {
    /** The row ids start with this, so Undo / Clear know a school row from one of hers. */
    const val PREFIX = "school:"
    const val PERSON = "the school"
    /** Clear's mark on the Activity entry (an `agent_action` field, LWW). */
    const val CLEARED_AT = "doneClearedAtMs"
    /** A date notifies only when its email came in this many days ago at most (today or yesterday). */
    const val FRESH_DAYS = 1L

    fun rowId(findId: String): String = PREFIX + findId
    fun findIdOf(rowId: String): String? = rowId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    /** The tasks a find's plan makes, the one Change opens first: the task on the day, a deadline's, then the day before's. */
    fun taskIds(findId: String): List<String> =
        listOf("day", "due", "prep").map { SchoolAutoRules.taskId(findId, it) }

    /** "Christmas fair · Sat 28 Nov, 12:00–16:00 · in School, …" (Activity's line without its "From the school: "). */
    fun line(summary: String): String = summary.removePrefix("From the school: ").trim()

    /**
     * The rows: each school find added on its own whose Activity entry is not undone or cleared and was made in the last
     * [HandleForMeRules.SHOWN_MS]; newest first. [clearedAt] reads Clear's mark of an entry; [taskTitle] is an open task's
     * title, null once it is gone or done (Change opens the first still open; the Mac finds it by title and id).
     */
    fun rows(
        finds: List<MailFind>,
        entries: Map<String, ActivityItem>,
        clearedAt: (String) -> Long?,
        taskTitle: (String) -> String?,
        nowMs: Long,
        cal: LocalCalendar,
    ): List<DoneForYou> = finds.mapNotNull { f ->
        if (f.kind != SchoolMailRules.KIND_SCHOOL || f.autoAtMs == null) return@mapNotNull null
        val e = entries[SchoolAutoRules.activityId(f.id)] ?: return@mapNotNull null
        if (e.source != SchoolAutoRules.SOURCE || e.undoneAtMs != null || clearedAt(e.id) != null) return@mapNotNull null
        if (e.atMs < nowMs - HandleForMeRules.SHOWN_MS) return@mapNotNull null
        val line = line(e.summary).ifEmpty { return@mapNotNull null }
        DoneForYou(
            id = rowId(f.id),
            from = HandleForMeRules.from("the school", cal.minuteOfDay(e.atMs)),
            line = line,
            spoken = HandleForMeRules.spoken(PERSON, line),
            atMs = e.atMs,
            taskIds = taskIds(f.id).filter { taskTitle(it) != null },
            homeDay = null,
            changeTitle = taskIds(f.id).firstNotNullOfOrNull(taskTitle),
            person = PERSON,
        )
    }.sortedByDescending { it.atMs }

    /** The rows that notify: those whose email came in today or yesterday (the catch-up's older ones only show). */
    fun notifiable(rows: List<DoneForYou>, finds: List<MailFind>, nowMs: Long, cal: LocalCalendar): List<DoneForYou> {
        val today = cal.epochDayOf(nowMs)
        val byId = finds.associateBy { it.id }
        return rows.filter { r -> findIdOf(r.id)?.let(byId::get)?.let { today - it.receivedDay <= FRESH_DAYS } == true }
    }

    /** The heads-ups ([HandleForMeRules.notices]): "Done for you · from the school" · the line, once each, opening Needs you. */
    fun notices(rows: List<DoneForYou>, finds: List<MailFind>, nowMs: Long, cal: LocalCalendar): List<Notice> =
        HandleForMeRules.notices(notifiable(rows, finds, nowMs, cal))
}

/*
 * School emails first, slice 4a (Meka 2026-11-24 17:38; the board's "(4) Care"): **an email for another year group is
 * summarised but nothing is added**. A school find keeps the year or class its sentence (or its email, when it names
 * only one) is for ([MailFind.forWhom]: "Year 3", "Years 5 and 6", "Reception", "KS2"; "the whole school" is everyone).
 * The children's school years come from About you's club age groups: in England the football age group and the school
 * year share the 31 August cut-off, so an under-7 is in Year 2 and an under-10 in Year 5 (U(n) → Year n − 5). When any
 * child has no age group, or the find names no year, nothing is held back (it is added as before). Held back, its card
 * row says "Not your children's year" and Add still adds it on his tap.
 */
object SchoolYearRules {
    const val NOT_YOURS = "Not your children's year"
    /** A club age group U(n) is school Year n − [OFFSET] (Reception is 0, Nursery −1). */
    const val OFFSET = 5

    private val RANGE = Regex("""years?\s+(\d{1,2})\s*(?:-|–|to)\s*(\d{1,2})""")
    private val PAIR = Regex("""years?\s+(\d{1,2})\s*(?:and|&)\s*(\d{1,2})""")
    private val ONE = Regex("""years?\s+(\d{1,2})""")

    /** The school year a club age group stands for: U7 → 2, U5 → 0 (Reception); null outside Nursery to Year 13. */
    fun yearOf(ageGroup: Int?): Int? = ageGroup?.minus(OFFSET)?.takeIf { it in -1..13 }

    /** The children's school years, or null when there are none or any child has no age group (then nothing is held back). */
    fun childYears(children: List<AboutChild>): Set<Int>? {
        if (children.isEmpty()) return null
        val ys = children.map { yearOf(it.ageGroup) ?: return null }
        return ys.toSet()
    }

    /** The years [forWhom] names: "Year 3" → {3}, "Years 5 and 6" → {5, 6}, "Years 3-6" → 3..6, "KS1" → {1, 2}; null = everyone or not read. */
    fun years(forWhom: String?): Set<Int>? {
        val t = forWhom?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.contains("whole") || t.startsWith("all")) return null
        if (t.startsWith("reception")) return setOf(0)
        if (t.startsWith("nursery")) return setOf(-1)
        if (Regex("""^(?:ks\s?1|key\s+stage\s+1)$""").matches(t)) return setOf(1, 2)
        if (Regex("""^(?:ks\s?2|key\s+stage\s+2)$""").matches(t)) return (3..6).toSet()
        RANGE.find(t)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            return if (a <= b && b - a <= 13) (a..b).toSet() else null
        }
        PAIR.find(t)?.let { m -> return setOf(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        ONE.find(t)?.let { m -> return setOf(m.groupValues[1].toInt()) }
        return null
    }

    /** Whether [f] is a school find for a year none of [childYears] is in (null years: never). */
    fun otherYear(f: MailFind, childYears: Set<Int>?): Boolean {
        if (childYears == null || f.kind != SchoolMailRules.KIND_SCHOOL) return false
        val ys = years(f.forWhom) ?: return false
        return ys.none { it in childYears }
    }
}
