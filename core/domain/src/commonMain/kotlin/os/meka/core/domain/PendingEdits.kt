package os.meka.core.domain

/**
 * Edit your calendars (slice 2c-ii): **a change shows before Google answers**. Non-AI, pure.
 *
 * The mirrored events are only ever written by the server's calendar poll, so after Meka adds, moves, changes or
 * deletes an event the mirror still shows the old version until the provider has taken the change and the account has
 * been polled again. [PendingEditRules.apply] lays Meka's own edits over the mirror, so every view (Today's timeline,
 * the Calendar agenda, Up next, the brief, the shutdown, leave-by alarms…) shows the event as it will be:
 *
 * - an edit inside its undo window or on its way (WAITING / SENDING, including offline) is shown as made: an add
 *   appears as a provisional event ([PROVISIONAL_PREFIX] + the edit's id), a change moves or renames the event in
 *   place (same id, so a moved row glides to its new slot), a delete takes it away (the row collapses);
 * - once the server says DONE the change is real, but the mirror may lag the answer by a poll: for
 *   [EditLineRules.DONE_SHOWN_MS] it is still laid over, unless the mirror has caught up (an add with the same title
 *   and time on that account is in the mirror; a changed event no longer matches what MEKA saw before the change);
 * - undone, clashed, refused or failed edits change nothing: the mirror (the provider's own copy) is what's shown, and
 *   the edit's line says why.
 *
 * Edits apply oldest first, so two changes made one after the other end as the later one left it. Nothing here writes:
 * the overlay is worked out afresh on every refresh.
 */
object PendingEditRules {
    /** The id of a provisional event (an add not yet in the mirror): this prefix and the edit's id. */
    const val PROVISIONAL_PREFIX = "pending."

    fun isProvisional(eventId: String): Boolean = eventId.startsWith(PROVISIONAL_PREFIX)

    /** The edit behind a provisional event, or null for a mirrored one. */
    fun editIdOf(eventId: String): String? = eventId.takeIf { isProvisional(it) }?.removePrefix(PROVISIONAL_PREFIX)

    /** The mirror with Meka's own edits laid over (see the object's notes). */
    fun apply(events: List<CalendarEvent>, edits: List<EventEdit>, nowMs: Long): List<CalendarEvent> {
        val live = edits.filter { shows(it, nowMs) }.sortedWith(compareBy<EventEdit>({ it.createdAtMs }, { it.id }))
        if (live.isEmpty()) return events
        val out = LinkedHashMap<String, CalendarEvent>(events.size + live.size)
        for (e in events) out[e.id] = e
        for (edit in live) {
            val onItsWay = edit.status == null
            when (edit.kind) {
                EventEditKind.ADD -> {
                    val d = edit.draft ?: continue
                    if (!onItsWay && inMirror(edit, d, events)) continue
                    val id = PROVISIONAL_PREFIX + edit.id
                    out[id] = CalendarEvent(
                        id = id, title = d.title.trim().ifEmpty { "(No title)" }, startAtMs = d.startAtMs, endAtMs = d.endAtMs,
                        allDay = d.allDay, location = d.location, provider = edit.provider, account = edit.account,
                        calendarName = mainCalendarName(edit.provider, edit.account, events), description = d.notes,
                        pendingEditId = edit.id.takeIf { onItsWay },
                    )
                }
                EventEditKind.CHANGE -> {
                    val id = edit.eventId ?: continue
                    val current = out[id] ?: continue
                    val draft = edit.draft ?: continue
                    val before = CalendarEditRules.draftOf(current)
                    // Answered: lay it over only while the mirror still shows what MEKA saw (it hasn't been polled yet).
                    if (!onItsWay) {
                        val base = edit.base ?: continue
                        if (CalendarEditRules.clashes(EventEditKind.CHANGE, edit.changes, base, before)) continue
                    }
                    val after = CalendarEditRules.merged(before, draft, edit.changes)
                    out[id] = current.copy(
                        title = after.title.trim().ifEmpty { current.title }, startAtMs = after.startAtMs, endAtMs = after.endAtMs,
                        allDay = after.allDay, location = after.location?.trim()?.ifEmpty { null },
                        description = after.notes?.trim()?.ifEmpty { null },
                        pendingEditId = if (onItsWay) edit.id else current.pendingEditId,
                    )
                }
                EventEditKind.DELETE -> edit.eventId?.let { out.remove(it) }
            }
        }
        return out.values.toList()
    }

    /** Whether [e] is laid over the mirror now. */
    fun shows(e: EventEdit, nowMs: Long): Boolean = when (e.state(nowMs)) {
        EventEditState.WAITING, EventEditState.SENDING -> true
        EventEditState.DONE -> nowMs - (e.statusAtMs ?: e.createdAtMs) <= EditLineRules.DONE_SHOWN_MS
        else -> false
    }

    /** The added event is in the mirror: same account, title and time. */
    private fun inMirror(edit: EventEdit, d: EventDraft, events: List<CalendarEvent>): Boolean = events.any {
        it.provider == edit.provider && it.account == edit.account && it.startAtMs == d.startAtMs && it.endAtMs == d.endAtMs &&
            it.allDay == d.allDay && it.title.trim() == d.title.trim()
    }

    /**
     * The name the mirror gives the account's main calendar (where MEKA adds): Google names it after the address,
     * Outlook "Calendar". Null when no mirrored event says so, so the row shows no calendar until the real one lands.
     */
    fun mainCalendarName(provider: String, account: String, events: List<CalendarEvent>): String? {
        val name = when (provider) {
            "google" -> account
            "microsoft" -> "Calendar"
            else -> return null
        }
        return name.takeIf { n -> events.any { it.provider == provider && it.account == account && it.calendarName == n } }
    }
}
