package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Edit your calendars from MEKA (build plan M1), slice 2a: **the write path**. Non-AI, pure rules plus a small store.
 *
 * A change Meka makes to a real Google or Outlook event (add, change, move, delete) is one synced `event_edit` entity
 * (ADR-008 addendum), written by the device that made it. It waits [CalendarEditRules.UNDO_MS] (5 s) so Undo can take
 * it back, then the server sends it to the provider (backend/integrations/CalendarWriter.kt) and writes the outcome
 * back into the same entity, so every device sees "Added to Google" or why not. Offline, the edit waits on the device
 * and is sent once it syncs.
 *
 * Guards, checked again by the server against the provider's own copy just before sending:
 * - only accounts where editing is allowed ([CalendarAccessRules]) and only Google or Microsoft events (never the
 *   fixtures feed);
 * - a change or delete carries the event as MEKA last saw it ([EventEdit.base]); if the provider's copy differs in
 *   anything the edit would overwrite, nothing is sent and the edit reads CLASH with their version beside it, so Meka
 *   chooses (never overwritten silently);
 * - MEKA never changes or deletes an event someone else organises (the server refuses);
 * - deleting an event with guests needs an explicit second confirmation ([EventEdit.guestsOk]): without it the server
 *   refuses with "This cancels it for 4 people", and the app asks again.
 *
 * MEKA itself never creates an edit: only Meka's own taps do (ADR-006); anything MEKA proposes later goes through
 * Needs you first.
 */
object EventEditFields {
    /** [EventEditKind] name. */
    const val KIND = "kind"
    /** "google" · "microsoft". */
    const val PROVIDER = "provider"
    /** The account's email (as in Calendars). */
    const val ACCOUNT = "account"
    /** The mirrored `event` entity changed or deleted; null for an add. */
    const val EVENT_ID = "eventId"
    /** [EventEditChange] names, comma separated: what a change changes (an add or delete leaves it empty). */
    const val CHANGES = "changes"
    const val TITLE = "title"
    const val START_AT = "startAtMs"
    const val END_AT = "endAtMs"
    const val ALL_DAY = "allDay"
    const val LOCATION = "location"
    const val NOTES = "notes"
    /** The event as MEKA last saw it (for the clash check). */
    const val BASE_TITLE = "baseTitle"
    const val BASE_START = "baseStartAtMs"
    const val BASE_END = "baseEndAtMs"
    const val BASE_ALL_DAY = "baseAllDay"
    const val BASE_LOCATION = "baseLocation"
    const val BASE_NOTES = "baseNotes"
    /** A delete Meka confirmed although it cancels the event for its guests. */
    const val GUESTS_OK = "guestsOk"
    const val CREATED_AT = "createdAtMs"
    /** Not sent before this moment (the end of the undo window). */
    const val SEND_AFTER = "sendAfterMs"
    /** Undone within the window: never sent. */
    const val UNDONE = "undone"

    // Written by the server only.
    /** [EventEditStatus] name. */
    const val STATUS = "status"
    const val STATUS_AT = "statusAtMs"
    /** Why it was refused or failed, in words. */
    const val DETAIL = "detail"
    /** On a clash: the provider's version of the event. */
    const val THEIR_TITLE = "theirTitle"
    const val THEIR_START = "theirStartAtMs"
    const val THEIR_END = "theirEndAtMs"
    const val THEIR_ALL_DAY = "theirAllDay"
    /** How many other people are invited (when the server looked). */
    const val GUESTS = "guests"
}

enum class EventEditKind { ADD, CHANGE, DELETE }

/** What a change changes. TIME is the start, end and all-day together. */
enum class EventEditChange { TITLE, TIME, LOCATION, NOTES }

/** The outcome the server writes. */
enum class EventEditStatus { DONE, CLASH, REFUSED, FAILED }

/** Where an edit stands, as the apps show it. */
enum class EventEditState {
    /** Inside the undo window. */
    WAITING,
    /** Past the window, not yet answered by the server (or offline). */
    SENDING,
    DONE, CLASH, REFUSED, FAILED, UNDONE,
}

/** An event as Meka writes it: what an add creates, or the values a change sets. */
data class EventDraft(
    val title: String,
    val startAtMs: Long,
    val endAtMs: Long,
    val allDay: Boolean,
    val location: String? = null,
    val notes: String? = null,
)

data class EventEdit(
    val id: String,
    val kind: EventEditKind,
    val provider: String,
    val account: String,
    val eventId: String?,
    val changes: Set<EventEditChange>,
    /** The values written: the whole event for an add, the changed fields for a change (others as in [base]). */
    val draft: EventDraft?,
    /** The event as MEKA saw it when Meka made the change (change and delete). */
    val base: EventDraft?,
    val guestsOk: Boolean,
    val createdAtMs: Long,
    val sendAfterMs: Long,
    val undone: Boolean,
    val status: EventEditStatus?,
    val statusAtMs: Long?,
    val detail: String?,
    /** On a clash, the provider's version. */
    val theirs: EventDraft?,
    val guests: Int?,
) {
    fun state(nowMs: Long): EventEditState = when {
        status != null -> EventEditState.valueOf(status.name)
        undone -> EventEditState.UNDONE
        nowMs < sendAfterMs -> EventEditState.WAITING
        else -> EventEditState.SENDING
    }

    /** Only a move: a change of time and nothing else. */
    val isMove: Boolean get() = kind == EventEditKind.CHANGE && changes == setOf(EventEditChange.TIME)
}

object CalendarEditRules {
    /** Undo window before anything is sent. */
    const val UNDO_MS = 5_000L
    /** The server waits this much past the window, so an Undo still in flight arrives first. */
    const val SERVER_GRACE_MS = 2_000L
    /** An edit the server couldn't send for this long (provider down) is given up as FAILED. */
    const val GIVE_UP_MS = 24 * 3_600_000L
    const val MAX_TITLE = 500
    const val MAX_LOCATION = 500
    const val MAX_NOTES = 2_000
    /** Longest event MEKA writes. */
    const val MAX_LENGTH_MS = 31 * 86_400_000L
    /** How far back and ahead an event may be written. */
    const val MAX_PAST_MS = 366 * 86_400_000L
    const val MAX_AHEAD_MS = 2 * 366 * 86_400_000L
    private const val DAY_MS = 86_400_000L

    /** The providers MEKA can write to (never the fixtures feed or other public feeds). */
    val WRITABLE = setOf("google", "microsoft")

    fun providerName(provider: String): String = when (provider) {
        "google" -> "Google"
        "microsoft" -> "Outlook"
        else -> provider
    }

    /**
     * Outlook only mirrors the start of an event's notes (its preview), so changing them from MEKA would cut the rest:
     * notes are written only when adding an Outlook event.
     */
    fun canChangeNotes(provider: String): Boolean = provider == "google"

    /** Whether MEKA may offer editing on [event]: a Google/Outlook event of an account where editing is allowed. */
    fun editable(event: CalendarEvent, canEdit: (provider: String, account: String) -> Boolean): Boolean =
        event.provider in WRITABLE && !event.isFixture && event.account != null && canEdit(event.provider, event.account)

    /** The draft cleaned up (trimmed, blanks to null), or null when it can't be written ([problem] says why). */
    fun clean(d: EventDraft): EventDraft = d.copy(
        title = d.title.trim().take(MAX_TITLE),
        location = d.location?.trim()?.take(MAX_LOCATION)?.ifEmpty { null },
        notes = d.notes?.trim()?.take(MAX_NOTES)?.ifEmpty { null },
    )

    /** Why [d] can't be written, in words, or null when it can. */
    fun problem(d: EventDraft, nowMs: Long): String? = when {
        d.title.isBlank() -> "Give it a title"
        d.endAtMs <= d.startAtMs -> "It has to end after it starts"
        d.endAtMs - d.startAtMs > MAX_LENGTH_MS -> "That's longer than a month"
        d.allDay && (d.startAtMs.mod(DAY_MS) != 0L || d.endAtMs.mod(DAY_MS) != 0L) -> "An all-day event runs whole days"
        d.startAtMs < nowMs - MAX_PAST_MS -> "That's more than a year ago"
        d.startAtMs > nowMs + MAX_AHEAD_MS -> "That's more than two years ahead"
        else -> null
    }

    /** The event as MEKA has it, as a draft (the base of a change or delete). */
    fun draftOf(e: CalendarEvent): EventDraft = EventDraft(e.title, e.startAtMs, e.endAtMs, e.allDay, e.location, e.description)

    /** What [after] changes compared with [before] (notes only where they can be written). */
    fun changes(before: EventDraft, after: EventDraft, provider: String): Set<EventEditChange> = buildSet {
        if (before.title.trim() != after.title.trim()) add(EventEditChange.TITLE)
        if (before.startAtMs != after.startAtMs || before.endAtMs != after.endAtMs || before.allDay != after.allDay) add(EventEditChange.TIME)
        if (norm(before.location) != norm(after.location)) add(EventEditChange.LOCATION)
        if (canChangeNotes(provider) && norm(before.notes) != norm(after.notes)) add(EventEditChange.NOTES)
    }

    private fun norm(s: String?) = s?.trim()?.ifEmpty { null }

    /**
     * Whether the provider's copy ([theirs]) moved on from what MEKA saw ([base]) in anything the edit would touch:
     * the changed fields of a change, or the title and time of a delete (deleting something that has since become
     * something else). Texts are compared as the server mirrors them (trimmed, cut to the mirror's lengths).
     */
    fun clashes(kind: EventEditKind, changes: Set<EventEditChange>, base: EventDraft, theirs: EventDraft): Boolean {
        val touched = if (kind == EventEditKind.DELETE) setOf(EventEditChange.TITLE, EventEditChange.TIME) else changes
        return touched.any { c ->
            when (c) {
                EventEditChange.TITLE -> base.title.trim().take(MAX_TITLE) != theirs.title.trim().take(MAX_TITLE)
                EventEditChange.TIME -> base.startAtMs != theirs.startAtMs || base.endAtMs != theirs.endAtMs || base.allDay != theirs.allDay
                EventEditChange.LOCATION -> norm(base.location)?.take(MAX_LOCATION) != norm(theirs.location)?.take(MAX_LOCATION)
                EventEditChange.NOTES -> norm(base.notes)?.take(MAX_NOTES) != norm(theirs.notes)?.take(MAX_NOTES)
            }
        }
    }

    /** The event a change leaves: [base] with the changed fields from [draft]. */
    fun merged(base: EventDraft, draft: EventDraft, changes: Set<EventEditChange>): EventDraft = EventDraft(
        title = if (EventEditChange.TITLE in changes) draft.title else base.title,
        startAtMs = if (EventEditChange.TIME in changes) draft.startAtMs else base.startAtMs,
        endAtMs = if (EventEditChange.TIME in changes) draft.endAtMs else base.endAtMs,
        allDay = if (EventEditChange.TIME in changes) draft.allDay else base.allDay,
        location = if (EventEditChange.LOCATION in changes) draft.location else base.location,
        notes = if (EventEditChange.NOTES in changes) draft.notes else base.notes,
    )

    /**
     * The edit's line: "Adding “Dentist” to Google" · "Added “Dentist” to Google" · "Moved “Dentist” in Google" ·
     * "“Dentist” changed in Google meanwhile · choose a version" · the refusal · "Couldn't send to Google · …" ·
     * "Undone".
     */
    fun line(e: EventEdit, nowMs: Long): String {
        val p = providerName(e.provider)
        val title = if (e.kind == EventEditKind.ADD || EventEditChange.TITLE in e.changes) e.draft?.title else e.base?.title
        val t = "“${title.orEmpty().trim().ifEmpty { "Event" }.take(60)}”"
        return when (e.state(nowMs)) {
            EventEditState.WAITING, EventEditState.SENDING -> when {
                e.kind == EventEditKind.ADD -> "Adding $t to $p"
                e.kind == EventEditKind.DELETE -> "Deleting $t from $p"
                e.isMove -> "Moving $t in $p"
                else -> "Changing $t in $p"
            }
            EventEditState.DONE -> when {
                e.kind == EventEditKind.ADD -> "Added $t to $p"
                e.kind == EventEditKind.DELETE -> "Deleted $t from $p"
                e.isMove -> "Moved $t in $p"
                else -> "Changed $t in $p"
            }
            EventEditState.CLASH -> "$t changed in $p meanwhile · choose a version"
            EventEditState.REFUSED -> e.detail ?: "$p didn't take the change"
            EventEditState.FAILED -> "Couldn't send to $p" + (e.detail?.let { " · $it" } ?: "")
            EventEditState.UNDONE -> "Undone"
        }
    }

    /** "This cancels it for 4 people" (the delete guard's words). */
    fun cancelsFor(guests: Int): String = if (guests == 1) "This cancels it for 1 person" else "This cancels it for $guests people"

    fun from(id: String, f: (String) -> FieldValue): EventEdit? {
        val kind = f(EventEditFields.KIND).textOrNull?.let { k -> EventEditKind.entries.firstOrNull { it.name == k } } ?: return null
        val provider = f(EventEditFields.PROVIDER).textOrNull ?: return null
        val account = f(EventEditFields.ACCOUNT).textOrNull ?: return null
        val changes = f(EventEditFields.CHANGES).textOrNull.orEmpty().split(',')
            .mapNotNull { c -> EventEditChange.entries.firstOrNull { it.name == c.trim() } }.toSet()
        // A change writes only what it changes; the rest of its draft is unused (see [merged]).
        val draft = if (kind == EventEditKind.DELETE) null else EventDraft(
            f(EventEditFields.TITLE).textOrNull.orEmpty(), f(EventEditFields.START_AT).longOrNull ?: 0L,
            f(EventEditFields.END_AT).longOrNull ?: 0L, f(EventEditFields.ALL_DAY).boolOrNull ?: false,
            f(EventEditFields.LOCATION).textOrNull, f(EventEditFields.NOTES).textOrNull,
        )
        val bStart = f(EventEditFields.BASE_START).longOrNull
        val bEnd = f(EventEditFields.BASE_END).longOrNull
        val base = if (bStart != null && bEnd != null) EventDraft(
            f(EventEditFields.BASE_TITLE).textOrNull.orEmpty(), bStart, bEnd, f(EventEditFields.BASE_ALL_DAY).boolOrNull ?: false,
            f(EventEditFields.BASE_LOCATION).textOrNull, f(EventEditFields.BASE_NOTES).textOrNull,
        ) else null
        val tStart = f(EventEditFields.THEIR_START).longOrNull
        val tEnd = f(EventEditFields.THEIR_END).longOrNull
        val theirs = if (tStart != null && tEnd != null) EventDraft(
            f(EventEditFields.THEIR_TITLE).textOrNull.orEmpty(), tStart, tEnd, f(EventEditFields.THEIR_ALL_DAY).boolOrNull ?: false,
        ) else null
        val created = f(EventEditFields.CREATED_AT).longOrNull ?: 0L
        return EventEdit(
            id = id, kind = kind, provider = provider, account = account,
            eventId = f(EventEditFields.EVENT_ID).textOrNull,
            changes = changes, draft = draft, base = base,
            guestsOk = f(EventEditFields.GUESTS_OK).boolOrNull ?: false,
            createdAtMs = created,
            sendAfterMs = f(EventEditFields.SEND_AFTER).longOrNull ?: (created + UNDO_MS),
            undone = f(EventEditFields.UNDONE).boolOrNull ?: false,
            status = f(EventEditFields.STATUS).textOrNull?.let { s -> EventEditStatus.entries.firstOrNull { it.name == s } },
            statusAtMs = f(EventEditFields.STATUS_AT).longOrNull,
            detail = f(EventEditFields.DETAIL).textOrNull,
            theirs = theirs,
            guests = f(EventEditFields.GUESTS).longOrNull?.toInt(),
        )
    }

    /** Whether the server should send [e] now: not answered, not undone, and past the window and the grace. */
    fun due(e: EventEdit, serverNowMs: Long): Boolean =
        e.status == null && !e.undone && serverNowMs >= e.sendAfterMs + SERVER_GRACE_MS

    /** Whether the server should stop trying a due edit it can't send (provider unreachable for a day). */
    fun givenUp(e: EventEdit, serverNowMs: Long): Boolean = serverNowMs - e.sendAfterMs > GIVE_UP_MS
}

/** Why a change couldn't be made, in words (the app shows it), or the edit's id. */
sealed class EventEditResult {
    data class Made(val id: String) : EventEditResult()
    data class Refused(val reason: String) : EventEditResult()
}

/**
 * The device's side: makes edits from Meka's taps and reads where they stand. [canEdit] says whether an account
 * allows editing (from the server's account list); [ids] makes the edit's id.
 */
class CalendarEdits(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val ids: () -> String,
    private val canEdit: (provider: String, account: String) -> Boolean,
) {
    fun all(): List<EventEdit> = replica.entities(EntityTypes.EVENT_EDIT)
        .mapNotNull { s -> CalendarEditRules.from(s.ref.entityId) { s[it] } }
        .sortedBy { it.createdAtMs }

    fun edit(id: String): EventEdit? = replica.entity(EntityTypes.EVENT_EDIT, id)?.let { s -> CalendarEditRules.from(id) { s[it] } }

    /** Edits still waiting, sending, or needing Meka (a clash or a refusal). */
    fun open(): List<EventEdit> {
        val now = nowMs()
        return all().filter { it.state(now) in OPEN }
    }

    /** Adds an event to [account]'s main calendar. */
    fun add(provider: String, account: String, draft: EventDraft): EventEditResult {
        if (provider !in CalendarEditRules.WRITABLE) return EventEditResult.Refused("MEKA can't add events there")
        if (!canEdit(provider, account)) return refusedNotAllowed(provider, account)
        val d = CalendarEditRules.clean(draft)
        CalendarEditRules.problem(d, nowMs())?.let { return EventEditResult.Refused(it) }
        return EventEditResult.Made(write(EventEditKind.ADD, provider, account, null, emptySet(), d, null, false))
    }

    /** Changes [event] to [draft] (only what differs is sent). */
    fun change(event: CalendarEvent, draft: EventDraft): EventEditResult {
        guard(event)?.let { return it }
        val base = CalendarEditRules.draftOf(event)
        val d = CalendarEditRules.clean(draft)
        val changes = CalendarEditRules.changes(base, d, event.provider)
        if (changes.isEmpty()) return EventEditResult.Refused("Nothing changed")
        CalendarEditRules.problem(CalendarEditRules.merged(base, d, changes), nowMs())?.let { return EventEditResult.Refused(it) }
        return EventEditResult.Made(write(EventEditKind.CHANGE, event.provider, event.account!!, event.id, changes, d, base, false))
    }

    /** Moves [event] to start at [startAtMs], keeping its length. */
    fun move(event: CalendarEvent, startAtMs: Long): EventEditResult =
        change(event, CalendarEditRules.draftOf(event).copy(startAtMs = startAtMs, endAtMs = startAtMs + (event.endAtMs - event.startAtMs)))

    /** Deletes [event]; [guestsOk] when Meka confirmed it cancels the event for its guests. */
    fun delete(event: CalendarEvent, guestsOk: Boolean = false): EventEditResult {
        guard(event)?.let { return it }
        return EventEditResult.Made(
            write(EventEditKind.DELETE, event.provider, event.account!!, event.id, emptySet(), null, CalendarEditRules.draftOf(event), guestsOk),
        )
    }

    /** Takes an edit back inside its undo window. Returns false once it's past (it may already be on its way). */
    fun undo(id: String): Boolean {
        val e = edit(id) ?: return false
        if (e.state(nowMs()) != EventEditState.WAITING) return false
        replica.commitLocal(EntityTypes.EVENT_EDIT, id, mapOf(EventEditFields.UNDONE to true.fv()))
        return true
    }

    private fun guard(event: CalendarEvent): EventEditResult? {
        if (event.provider !in CalendarEditRules.WRITABLE || event.isFixture || event.account == null) {
            return EventEditResult.Refused("MEKA can't change this calendar")
        }
        if (!canEdit(event.provider, event.account)) return refusedNotAllowed(event.provider, event.account)
        return null
    }

    private fun refusedNotAllowed(provider: String, account: String) =
        EventEditResult.Refused("Editing isn't allowed for $account · Allow editing in Calendars")

    private fun write(
        kind: EventEditKind, provider: String, account: String, eventId: String?, changes: Set<EventEditChange>,
        draft: EventDraft?, base: EventDraft?, guestsOk: Boolean,
    ): String {
        val id = ids()
        val now = nowMs()
        val fields = linkedMapOf(
            EventEditFields.KIND to kind.name.fv(),
            EventEditFields.PROVIDER to provider.fv(),
            EventEditFields.ACCOUNT to account.fv(),
            EventEditFields.CREATED_AT to now.fv(),
            EventEditFields.SEND_AFTER to (now + CalendarEditRules.UNDO_MS).fv(),
        )
        eventId?.let { fields[EventEditFields.EVENT_ID] = it.fv() }
        if (changes.isNotEmpty()) fields[EventEditFields.CHANGES] = changes.sortedBy { it.ordinal }.joinToString(",") { it.name }.fv()
        if (draft != null) {
            val all = kind == EventEditKind.ADD
            if (all || EventEditChange.TITLE in changes) fields[EventEditFields.TITLE] = draft.title.fv()
            if (all || EventEditChange.TIME in changes) {
                fields[EventEditFields.START_AT] = draft.startAtMs.fv()
                fields[EventEditFields.END_AT] = draft.endAtMs.fv()
                fields[EventEditFields.ALL_DAY] = draft.allDay.fv()
            }
            if ((all || EventEditChange.LOCATION in changes) && draft.location != null) fields[EventEditFields.LOCATION] = draft.location.fv()
            if ((all || EventEditChange.NOTES in changes) && draft.notes != null) fields[EventEditFields.NOTES] = draft.notes.fv()
        }
        if (base != null) {
            fields[EventEditFields.BASE_TITLE] = base.title.fv()
            fields[EventEditFields.BASE_START] = base.startAtMs.fv()
            fields[EventEditFields.BASE_END] = base.endAtMs.fv()
            fields[EventEditFields.BASE_ALL_DAY] = base.allDay.fv()
            base.location?.let { fields[EventEditFields.BASE_LOCATION] = it.fv() }
            base.notes?.let { fields[EventEditFields.BASE_NOTES] = it.fv() }
        }
        if (guestsOk) fields[EventEditFields.GUESTS_OK] = true.fv()
        replica.commitLocal(EntityTypes.EVENT_EDIT, id, fields)
        return id
    }

    private companion object {
        val OPEN = setOf(EventEditState.WAITING, EventEditState.SENDING, EventEditState.CLASH, EventEditState.REFUSED, EventEditState.FAILED)
    }
}
