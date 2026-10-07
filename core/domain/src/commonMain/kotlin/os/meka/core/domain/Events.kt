package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot

/**
 * Field names of a calendar event (ADR-008). Events are written only by the server's calendar ingestion, as a
 * single chain of ops per field, so every field merges last-writer-wins and never conflicts.
 */
object EventFields {
    const val TITLE = "title"
    const val START_AT = "startAtMs"
    const val END_AT = "endAtMs"
    /** All-day events: [START_AT]/[END_AT] are UTC midnights of the first day and the day after the last. */
    const val ALL_DAY = "allDay"
    const val LOCATION = "location"
    /** "google" | "microsoft". */
    const val PROVIDER = "provider"
    /** The account the event came from (its email address), so two accounts can be told apart. */
    const val ACCOUNT = "account"
    const val CALENDAR = "calendarName"
    /** The event's notes as plain text (the server strips any HTML). Untrusted: shown as text only (ADR-006). */
    const val DESCRIPTION = "description"
    /** The provider's own video-call link (Google Meet, Teams), https only. */
    const val JOIN_URL = "joinUrl"
    /**
     * The event's own page in the provider's web app (Google's `htmlLink`, Outlook's `webLink`), https only, so Meka
     * can edit the real event there (calendar actions, slice 3). Additive; absent on events polled before it existed.
     */
    const val WEB_URL = "webUrl"
    /** True when the event was cancelled or is no longer in the provider's window. Can flip back to false. */
    const val REMOVED = "removed"
}

/** Typed read model of a calendar event. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val startAtMs: Long,
    val endAtMs: Long,
    val allDay: Boolean,
    val location: String?,
    val provider: String,
    val account: String?,
    val calendarName: String?,
    /** Notes, plain text (see [EventFields.DESCRIPTION]). */
    val description: String? = null,
    /** The provider's video-call link (see [EventFields.JOIN_URL]). */
    val joinUrl: String? = null,
    /** The provider's page for this event (see [EventFields.WEB_URL]). */
    val webUrl: String? = null,
) {
    /** From the fixtures feed (FC Barcelona), marked in the Calendar tab. */
    val isFixture: Boolean get() = provider == "fixtures"

    /** Whether the event touches the local day [day]. All-day events are matched by calendar date. */
    fun overlaps(day: DayWindow): Boolean =
        if (allDay) {
            // The all-day range [start, end) is in UTC dates; shift the local day start to its calendar date.
            val localDateStartAsUtc = day.startMs + day.utcOffsetMs
            localDateStartAsUtc >= startAtMs && localDateStartAsUtc < endAtMs
        } else {
            startAtMs < day.endMs && endAtMs > day.startMs
        }

    companion object {
        /** Null for events that are removed or incomplete (no start). */
        fun from(s: EntitySnapshot): CalendarEvent? {
            if (s[EventFields.REMOVED].boolOrNull == true) return null
            val start = s[EventFields.START_AT].longOrNull ?: return null
            return CalendarEvent(
                id = s.ref.entityId,
                title = s[EventFields.TITLE].textOrNull?.takeIf { it.isNotBlank() } ?: "(No title)",
                startAtMs = start,
                endAtMs = s[EventFields.END_AT].longOrNull ?: start,
                allDay = s[EventFields.ALL_DAY].boolOrNull ?: false,
                location = s[EventFields.LOCATION].textOrNull?.takeIf { it.isNotBlank() },
                provider = s[EventFields.PROVIDER].textOrNull ?: "",
                account = s[EventFields.ACCOUNT].textOrNull,
                calendarName = s[EventFields.CALENDAR].textOrNull,
                description = s[EventFields.DESCRIPTION].textOrNull?.takeIf { it.isNotBlank() },
                joinUrl = s[EventFields.JOIN_URL].textOrNull?.takeIf { it.isNotBlank() },
                webUrl = s[EventFields.WEB_URL].textOrNull?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Read access to mirrored calendar events. Apps never write events; the server's ingestion does. */
class CalendarEvents(private val replica: os.meka.core.sync.Replica) {
    fun all(): List<CalendarEvent> = replica.entities(EntityTypes.EVENT).mapNotNull { CalendarEvent.from(it) }
}
