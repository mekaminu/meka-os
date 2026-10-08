package os.meka.core.domain

/**
 * Edit your calendars from MEKA (build plan M1), slice 2c: **change, move and delete from the event detail**. Non-AI,
 * pure.
 *
 * Edit opens the Add event form filled in from the event (same chips, steps and lengths), on the event's own account.
 * Save makes one `event_edit` of only what changed ([CalendarEdits.change]); a change of time alone is a move. A field
 * Meka didn't touch keeps the event's own value word for word (never trimmed or cut to MEKA's limits), so saving a new
 * title can't also rewrite long notes. Delete makes a delete edit; when the server finds guests it refuses with "This
 * cancels it for 4 people" and the detail offers **Delete anyway** (Meka's second tap, [CalendarEdits.delete] with
 * guestsOk). Both wait five seconds for Undo like an add.
 *
 * The time can be changed only on an event that hasn't started and isn't longer than a day (a timed event) — a
 * running, past or very long event keeps its time and says where to change it ([EditEventRules.TIME_NOTE]). An all-day
 * event that spans several days keeps its length when moved to another day.
 */
object EditEventRules {
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    /** Why the When rows aren't offered. */
    const val TIME_NOTE = "Its time can't be changed from MEKA once it's started (or when it runs over a day)"

    /** Whether MEKA offers changing [e]'s time: not started yet, and a timed event no longer than a day. */
    fun timeEditable(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar): Boolean =
        if (e.allDay) e.startAtMs.floorDiv(DAY_MS) >= calendar.epochDayOf(nowMs)
        else e.startAtMs > nowMs && e.endAtMs > e.startAtMs && e.endAtMs - e.startAtMs <= AddEventRules.MAX_LENGTH * MINUTE_MS

    /** Whether the notes can be changed: Google only (Outlook mirrors a preview) and not longer than MEKA writes. */
    fun notesEditable(e: CalendarEvent): Boolean =
        CalendarEditRules.canChangeNotes(e.provider) && (e.description?.length ?: 0) <= CalendarEditRules.MAX_NOTES

    /** The form filled in from [e] (its account fixed; the time as it is, even off the quarter hour). */
    fun start(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar): AddEventForm {
        val today = calendar.epochDayOf(nowMs)
        val day = if (e.allDay) e.startAtMs.floorDiv(DAY_MS) else calendar.epochDayOf(e.startAtMs)
        val lengthMin = if (e.allDay) AddEventRules.DEFAULT_LENGTH else ((e.endAtMs - e.startAtMs) / MINUTE_MS).toInt().coerceAtLeast(0)
        return AddEventForm(
            title = e.title,
            day = day,
            minute = if (e.allDay) null else calendar.minuteOfDay(e.startAtMs),
            lengthMin = lengthMin,
            accountKey = e.account?.let { AddEventRules.accountKey(e.provider, it) },
            location = e.location.orEmpty(),
            notes = e.description.orEmpty(),
            today = today,
            nowMinute = calendar.minuteOfDay(nowMs),
        )
    }

    /**
     * The event [form] describes, compared with [e]: a field left as it was opened keeps the event's own value; the
     * time is the event's own unless the day, start, length or all day moved (a multi-day all-day event keeps its
     * span).
     */
    fun draft(form: AddEventForm, e: CalendarEvent, nowMs: Long, calendar: LocalCalendar): EventDraft {
        val opened = start(e, nowMs, calendar)
        val timeMoved = timeEditable(e, nowMs, calendar) &&
            (form.day != opened.day || form.minute != opened.minute || (form.minute != null && form.lengthMin != opened.lengthMin))
        val (startMs, endMs) = when {
            !timeMoved -> e.startAtMs to e.endAtMs
            form.minute == null -> {
                val span = if (e.allDay) maxOf(1L, (e.endAtMs - e.startAtMs) / DAY_MS) else 1L
                form.day * DAY_MS to (form.day + span) * DAY_MS
            }
            else -> {
                val s = calendar.toEpochMs(form.day, form.minute)
                s to s + form.lengthMin * MINUTE_MS
            }
        }
        return EventDraft(
            title = if (form.title == opened.title) e.title else form.title.trim(),
            startAtMs = startMs,
            endAtMs = endMs,
            allDay = if (timeMoved) form.minute == null else e.allDay,
            location = if (form.location == opened.location) e.location else form.location.trim().ifEmpty { null },
            notes = if (form.notes == opened.notes || !notesEditable(e)) e.description else form.notes.trim().ifEmpty { null },
        )
    }

    /** What Save would change (empty: nothing yet). */
    fun changes(form: AddEventForm, e: CalendarEvent, nowMs: Long, calendar: LocalCalendar): Set<EventEditChange> =
        CalendarEditRules.changes(CalendarEditRules.draftOf(e), draft(form, e, nowMs, calendar), e.provider)

    /**
     * The edit sheet as shown: the Add event sheet's chips, times and lengths (none when the time can't change), the
     * one account, "Save to Google", and whether Save can go (something changed and nothing's wrong).
     */
    fun view(form: AddEventForm, e: CalendarEvent, canEdit: Boolean, nowMs: Long, calendar: LocalCalendar): AddEventView {
        val account = e.account?.let { EditAccount(e.provider, it) }
        val accounts = listOfNotNull(account.takeIf { canEdit })
        val base = AddEventRules.view(form, accounts, nowMs, calendar)
        val timeOk = timeEditable(e, nowMs, calendar)
        val chips = if (!timeOk) emptyList() else base.chips
        val d = draft(form, e, nowMs, calendar)
        val changes = CalendarEditRules.changes(CalendarEditRules.draftOf(e), d, e.provider)
        val p = CalendarEditRules.providerName(e.provider)
        val problem = when {
            !canEdit || account == null -> "Editing isn't allowed for ${e.account ?: "this calendar"} · Allow editing in Calendars"
            d.title.isBlank() -> "Give it a title"
            changes.isEmpty() -> null
            else -> CalendarEditRules.problem(CalendarEditRules.merged(CalendarEditRules.draftOf(e), d, changes), nowMs)
                // An event long past may be renamed; only a new time is held to the past limit.
                ?.takeUnless { EventEditChange.TIME !in changes && it == "That's more than a year ago" }
        }
        val summary = if (timeOk) base.summary else EventDetails.build(e, nowMs, calendar).whenLine
        return base.copy(
            chips = chips,
            startLabel = if (timeOk) base.startLabel else null,
            endLabel = if (timeOk) base.endLabel else null,
            summary = summary,
            lengths = if (timeOk) base.lengths else emptyList(),
            accounts = accounts.map { AccountChoice(it.label, it.key, true) },
            problem = problem,
            canAdd = problem == null && changes.isNotEmpty(),
            addLabel = "Save to $p",
            notesNote = when {
                notesEditable(e) -> null
                e.provider == "microsoft" -> "Outlook's notes are changed in Outlook"
                else -> "These notes are long; change them in $p Calendar"
            },
            timeEditable = timeOk,
            notesEditable = notesEditable(e),
            deleteLabel = "Delete from $p",
        )
    }

    /** The undo bar's line for a delete ("Deleting “Dentist” from Google"). */
    fun deletingLine(e: CalendarEvent): String =
        "Deleting “${e.title.trim().ifEmpty { "Event" }.take(60)}” from ${CalendarEditRules.providerName(e.provider)}"

    /**
     * A delete the server held back because it cancels the event for its guests and Meka hasn't confirmed: the detail
     * then offers Delete anyway.
     */
    fun needsGuestsOk(e: EventEdit, nowMs: Long): Boolean {
        val guests = e.guests ?: 0
        return e.kind == EventEditKind.DELETE && e.state(nowMs) == EventEditState.REFUSED && !e.guestsOk && guests > 0 &&
            e.detail == CalendarEditRules.cancelsFor(guests)
    }

    /**
     * The newest edit of [eventId] worth saying in its detail (on its way, needing Meka from the last day, or just
     * done), or null. Undone ones say nothing.
     */
    fun note(eventId: String, edits: List<EventEdit>, nowMs: Long, calendar: LocalCalendar? = null): EventEditNote? {
        // An undone edit never happened, so it doesn't hide an earlier one (two made in the same millisecond: the later id).
        // A provisional event (slice 2c-ii) is its add's own edit.
        val addId = PendingEditRules.editIdOf(eventId)
        val e = edits.filter { (it.eventId == eventId || (addId != null && it.id == addId)) && !it.undone }
            .maxWithOrNull(compareBy<EventEdit>({ it.createdAtMs }, { it.id })) ?: return null
        val line = EditLineRules.lines(listOf(e), nowMs).firstOrNull() ?: return null
        val anyway = needsGuestsOk(e, nowMs)
        return EventEditNote(
            editId = e.id,
            text = when {
                anyway -> "${CalendarEditRules.line(e, nowMs)} · they'll be told if you delete it"
                // The detail is where the choice is made, so its line doesn't send Meka elsewhere.
                e.needsChoice -> CalendarEditRules.line(e, nowMs)
                else -> line.text
            },
            needsMeka = line.needsMeka,
            deleteAnyway = anyway,
            waiting = line.state == EventEditState.WAITING || line.state == EventEditState.SENDING,
            clash = calendar?.let { ClashRules.view(e, nowMs, it) },
        )
    }
}

/**
 * Edit your calendars, slice 2c-iii: **the clash chooser**. Non-AI, pure.
 *
 * When the server finds that the event changed in Google (or Outlook) meanwhile in something Meka's edit touches, it
 * sends nothing and hands their version back (CLASH). The event's detail then shows both versions of what the edit
 * touches, side by side, and asks: **Keep mine** sends Meka's edit again, checked against their version this time
 * ([CalendarEdits.keepMine], five seconds' Undo); **Keep Google's** sends nothing ([CalendarEdits.keepTheirs]). It is
 * never decided for him, and a resend is still checked against the provider's copy (another change meanwhile clashes
 * again).
 */
object ClashRules {
    private const val NOTES_SHOWN = 80

    /** The chooser for [e], or null when it isn't an unanswered clash with their version. */
    fun view(e: EventEdit, nowMs: Long, calendar: LocalCalendar): EventClashView? {
        if (!e.needsChoice) return null
        val theirs = e.theirs ?: return null
        val p = CalendarEditRules.providerName(e.provider)
        fun whenOf(d: EventDraft) = EventDetails.whenLine(d.startAtMs, d.endAtMs, d.allDay, calendar)
        fun text(s: String?, max: Int = 60) = s?.trim()?.replace(Regex("\\s+"), " ")?.let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }
            ?.ifEmpty { null } ?: "None"
        val rows = when (e.kind) {
            EventEditKind.ADD -> return null
            EventEditKind.DELETE -> listOf(
                ClashRow("Event", "Deleted", "${text(theirs.title)} · ${whenOf(theirs)}"),
            )
            EventEditKind.CHANGE -> {
                val draft = e.draft ?: return null
                val mine = CalendarEditRules.merged(theirs, draft, e.changes)
                EventEditChange.entries.filter { it in e.changes }.map { c ->
                    when (c) {
                        EventEditChange.TITLE -> ClashRow("Title", text(mine.title), text(theirs.title))
                        EventEditChange.TIME -> ClashRow("When", whenOf(mine), whenOf(theirs))
                        EventEditChange.LOCATION -> ClashRow("Place", text(mine.location), text(theirs.location))
                        EventEditChange.NOTES -> ClashRow("Notes", text(mine.notes, NOTES_SHOWN), text(theirs.notes, NOTES_SHOWN))
                    }
                }
            }
        }
        return EventClashView(
            editId = e.id,
            mineLabel = "Yours",
            theirsLabel = p,
            rows = rows,
            keepMineLabel = if (e.kind == EventEditKind.DELETE) "Delete it" else "Keep mine",
            keepTheirsLabel = "Keep $p's",
            explain = if (e.kind == EventEditKind.DELETE) "It changed in $p after you deleted it"
            else "It changed in $p after you edited it · only what you changed is shown",
        )
    }
}

/** One thing the clashed edit touches: Meka's version and theirs ("When" · "Fri 9 Oct · 16:00–17:00" · …). */
data class ClashRow(val label: String, val mine: String, val theirs: String)

/** The clash chooser in the event detail. */
data class EventClashView(
    /** The clashed edit (for Keep mine / Keep theirs). */
    val editId: String,
    /** "Yours". */
    val mineLabel: String,
    /** "Google" · "Outlook". */
    val theirsLabel: String,
    val rows: List<ClashRow>,
    /** "Keep mine" (a delete: "Delete it"). */
    val keepMineLabel: String,
    /** "Keep Google's". */
    val keepTheirsLabel: String,
    /** The line above the rows. */
    val explain: String,
)

/** What the event detail says about the event's own latest edit. */
data class EventEditNote(
    val editId: String,
    /** "Moving “Dentist” in Google" · "Deleted …" · "This cancels it for 4 people · they'll be told if you delete it". */
    val text: String,
    /** A clash, refusal or failure (lit). */
    val needsMeka: Boolean,
    /** The delete waits for Meka's second tap: offer Delete anyway. */
    val deleteAnyway: Boolean,
    /** Still on its way: Edit and Delete wait until it's answered. */
    val waiting: Boolean,
    /** An unanswered clash: both versions and Keep mine · Keep theirs (slice 2c-iii). */
    val clash: EventClashView? = null,
)
