package os.meka.core.domain

/**
 * Edit your calendars from MEKA (build plan M1), slice 2b: **the Add event sheet**. Non-AI, pure.
 *
 * Meka adds an event to one of his Google/Outlook accounts where editing is allowed ([CalendarAccessRules]): a title,
 * the day (Today · Tomorrow · a picked day), a start time stepped a quarter hour at a time and a length, or all day;
 * which account (the one he added to last time, else the first); an optional place and notes. Add makes the
 * `event_edit` ([CalendarEdits.add]), which waits five seconds for Undo before the server sends it.
 *
 * The form is a screen state (never synced) and carries the day it was opened on, so its steps need nothing else.
 */
data class EditAccount(val provider: String, val email: String) {
    /** "google|meka@gmail.com": how the form names it. */
    val key: String get() = AddEventRules.accountKey(provider, email)
    /** "Google · meka@gmail.com". */
    val label: String get() = "${CalendarEditRules.providerName(provider)} · $email"
}

/** One length chip: "30 min" · "1 h" · "1 h 30" · "2 h". */
data class LengthChoice(val label: String, val minutes: Int, val selected: Boolean)

/** One account choice in the sheet. */
data class AccountChoice(val label: String, val key: String, val selected: Boolean)

/**
 * The Add event sheet's values. [minute] is the start in minutes past local midnight, null for all day.
 * [today] and [nowMinute] are when the sheet was opened (the suggested time and the earliest day).
 */
data class AddEventForm(
    val title: String,
    val day: Long,
    val minute: Int?,
    val lengthMin: Int,
    val accountKey: String?,
    val location: String,
    val notes: String,
    val today: Long,
    val nowMinute: Int,
) {
    val isAllDay: Boolean get() = minute == null
    /** For Swift: the start, or -1 for all day. */
    val minuteOrNone: Int get() = minute ?: -1

    fun withTitle(text: String): AddEventForm = copy(title = text.take(CalendarEditRules.MAX_TITLE))
    fun withLocation(text: String): AddEventForm = copy(location = text.take(CalendarEditRules.MAX_LOCATION))
    fun withNotes(text: String): AddEventForm = copy(notes = text.take(CalendarEditRules.MAX_NOTES))
    fun withAccount(key: String): AddEventForm = copy(accountKey = key)
    fun withLength(minutes: Int): AddEventForm = copy(lengthMin = minutes.coerceIn(AddEventRules.MIN_LENGTH, AddEventRules.MAX_LENGTH))

    /** Another day (never before today, at most two years ahead); a timed event keeps its time. */
    fun withDay(epochDay: Long): AddEventForm = copy(day = epochDay.coerceIn(today, today + TaskWhenRules.MAX_DAYS_AHEAD))

    /** All day on, or off again at the suggested time for the day. */
    fun withAllDay(on: Boolean): AddEventForm = when {
        on -> copy(minute = null)
        minute != null -> this
        else -> copy(minute = TaskWhenRules.suggestedMinute(day, today, nowMinute))
    }

    /** ‹ › on the start: [steps] quarter hours, kept within the day. */
    fun stepTime(steps: Int): AddEventForm = minute?.let { copy(minute = TaskWhenRules.step(it, steps)) } ?: this
}

/** The sheet as shown. */
data class AddEventView(
    /** Today · Tomorrow (+ the picked day). */
    val chips: List<WhenChoice>,
    /** "15:00", or null for all day. */
    val startLabel: String?,
    /** "16:00" (or "00:30 next day"), or null for all day. */
    val endLabel: String?,
    /** "Fri 9 Oct · 15:00–16:00" · "Tomorrow · all day". */
    val summary: String,
    val lengths: List<LengthChoice>,
    val accounts: List<AccountChoice>,
    /** Why it can't be added yet, in words (null while it can, or while only the title is missing). */
    val problem: String?,
    val canAdd: Boolean,
    /** "Add to Google". */
    val addLabel: String,
    /** A quiet line under the notes: Outlook keeps notes added here, but MEKA can't change them later. */
    val notesNote: String?,
)

object AddEventRules {
    val LENGTHS = listOf(30, 60, 90, 120)
    const val DEFAULT_LENGTH = 60
    const val MIN_LENGTH = 15
    const val MAX_LENGTH = 24 * 60
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    fun accountKey(provider: String, email: String): String = "$provider|$email"

    /**
     * A fresh form: on [day] (today when null or earlier), at the next quarter hour at least 15 minutes away (09:00 on
     * another day), an hour long, on the account last added to when it can still edit, else the first one.
     */
    fun start(today: Long, nowMinute: Int, day: Long?, accounts: List<EditAccount>, lastUsedKey: String?): AddEventForm {
        val d = (day ?: today).coerceIn(today, today + TaskWhenRules.MAX_DAYS_AHEAD)
        val keys = accounts.map { it.key }
        return AddEventForm(
            title = "",
            day = d,
            minute = TaskWhenRules.suggestedMinute(d, today, nowMinute),
            lengthMin = DEFAULT_LENGTH,
            accountKey = lastUsedKey?.takeIf { it in keys } ?: keys.firstOrNull(),
            location = "",
            notes = "",
            today = today,
            nowMinute = nowMinute,
        )
    }

    /** The account Meka last added an event to (the remembered default). */
    fun lastUsedKey(edits: List<EventEdit>): String? =
        edits.filter { it.kind == EventEditKind.ADD && !it.undone }.maxByOrNull { it.createdAtMs }?.let { accountKey(it.provider, it.account) }

    /** The form's account, if it can still edit. */
    fun account(form: AddEventForm, accounts: List<EditAccount>): EditAccount? = accounts.firstOrNull { it.key == form.accountKey }

    /**
     * The event the form describes. A timed event starts at the local time on its day; an all-day event runs whole
     * days as the calendars mirror them (UTC midnight to UTC midnight).
     */
    fun draft(form: AddEventForm, calendar: LocalCalendar): EventDraft {
        val minute = form.minute
        val (start, end) = if (minute == null) {
            form.day * DAY_MS to (form.day + 1) * DAY_MS
        } else {
            val s = calendar.toEpochMs(form.day, minute)
            s to s + form.lengthMin * MINUTE_MS
        }
        return CalendarEditRules.clean(
            EventDraft(form.title, start, end, minute == null, form.location.ifBlank { null }, form.notes.ifBlank { null }),
        )
    }

    fun view(form: AddEventForm, accounts: List<EditAccount>, nowMs: Long, calendar: LocalCalendar): AddEventView {
        val today = form.today
        val chips = buildList {
            add(WhenChoice("Today", today, form.day == today))
            add(WhenChoice("Tomorrow", today + 1, form.day == today + 1))
            if (form.day > today + 1) add(WhenChoice(CivilDate.shortLabel(form.day), form.day, true))
        }
        val minute = form.minute
        val endMinute = minute?.let { it + form.lengthMin }
        val startLabel = minute?.let(TaskWhenRules::timeLabel)
        val endLabel = endMinute?.let { endLabel(it) }
        val dayLabel = TaskWhenRules.label(form.day, null, today)
        val summary = if (minute == null) "$dayLabel · all day" else "$dayLabel · $startLabel–${endMinute?.let { TaskWhenRules.timeLabel(it % (24 * 60)) }}"
        val lengths = (LENGTHS + form.lengthMin).distinct().sorted().map { LengthChoice(lengthLabel(it), it, it == form.lengthMin) }
        val account = account(form, accounts)
        val choices = accounts.map { AccountChoice(it.label, it.key, it.key == account?.key) }
        val draft = draft(form, calendar)
        val problem = when {
            accounts.isEmpty() -> "Allow editing on an account in Calendars first"
            account == null -> "Choose a calendar"
            draft.title.isBlank() -> null // the Add button waits for a title; no need to say so
            else -> CalendarEditRules.problem(draft, nowMs)
        }
        return AddEventView(
            chips = chips,
            startLabel = startLabel,
            endLabel = endLabel,
            summary = summary,
            lengths = if (minute == null) emptyList() else lengths,
            accounts = choices,
            problem = problem,
            canAdd = account != null && draft.title.isNotBlank() && problem == null,
            addLabel = "Add to ${account?.let { CalendarEditRules.providerName(it.provider) } ?: "your calendar"}",
            notesNote = if (account?.provider == "microsoft") "Outlook keeps these notes; MEKA can't change them later" else null,
        )
    }

    /** "30 min" · "1 h" · "1 h 30" · "2 h". */
    fun lengthLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m"
        }
    }

    /** "16:00", or "00:30 next day" when it runs past midnight. */
    fun endLabel(endMinute: Int): String =
        if (endMinute >= 24 * 60) TaskWhenRules.timeLabel(endMinute - 24 * 60) + " next day" else TaskWhenRules.timeLabel(endMinute)
}

/** One line about an edit on the Calendar screen ("Adding “Dentist” to Google", "Added …", a clash, a refusal). */
data class EditLine(
    val id: String,
    val text: String,
    val state: EventEditState,
    /** A clash, refusal or failure: something for Meka to look at (lit). */
    val needsMeka: Boolean,
)

object EditLineRules {
    /** How long a sent edit's "Added …" line stays. */
    const val DONE_SHOWN_MS = 2 * 60_000L
    /** At most this many lines (the newest). */
    const val MAX_LINES = 3

    /**
     * The edits worth a line: those waiting or sending, those needing Meka (clash, refused, failed) from the last
     * day, and those done in the last two minutes; newest first. Undone ones say nothing (the undo bar said it).
     */
    fun lines(edits: List<EventEdit>, nowMs: Long): List<EditLine> = edits
        .filter { e ->
            when (e.state(nowMs)) {
                EventEditState.WAITING, EventEditState.SENDING -> true
                EventEditState.DONE -> nowMs - (e.statusAtMs ?: e.createdAtMs) <= DONE_SHOWN_MS
                EventEditState.CLASH, EventEditState.REFUSED, EventEditState.FAILED -> nowMs - (e.statusAtMs ?: e.createdAtMs) <= 86_400_000L
                EventEditState.UNDONE -> false
            }
        }
        .sortedByDescending { it.createdAtMs }
        .take(MAX_LINES)
        .map { e ->
            val s = e.state(nowMs)
            EditLine(e.id, CalendarEditRules.line(e, nowMs), s, s == EventEditState.CLASH || s == EventEditState.REFUSED || s == EventEditState.FAILED)
        }
}
