package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue

/**
 * Fields of a `travel_time` (weekend football, slice 2b): the drive from home to one club fixture's ground as MEKA's
 * server last worked it out with traffic (Google Routes API, Needs Meka #19). Written only by the server.
 */
object TravelTimeFields {
    /** The fixture's event id. */
    const val EVENT_ID = "eventId"
    /** The ground's key when it was looked up ([FootballRules.venueKey]); a changed place asks again. */
    const val PLACE_KEY = "placeKey"
    /** Minutes of driving with traffic (Int, 1–[TravelRules.MAX_DRIVE_MIN]). */
    const val DRIVE_MIN = "driveMin"
    /** The departure the drive was worked out for. */
    const val DEPART_AT = "departAtMs"
    /** The kick-off it was worked out for; a moved kick-off asks again. */
    const val KICK_OFF = "kickOffMs"
    /** When the server asked. */
    const val CHECKED_AT = "checkedAtMs"
}

/** One fixture's drive from home as the server last worked it out. */
data class TravelTime(val eventId: String, val placeKey: String, val driveMin: Int, val departAtMs: Long, val checkedAtMs: Long, val kickOffMs: Long = 0L)

/**
 * Weekend football, slice 2b (Meka chose Google's Routes API 2026-10-10, Needs Meka #19): the leave-by from a drive
 * time worked out with traffic. Non-AI, pure, unit-tested; the server decides with it when to ask and the devices
 * turn the answer into the detail's offer ([FootballRules.leaveOffer]).
 *
 * - **Leave by** = kick-off − [MEET_EARLY_MIN] (be there before the warm-up) − the drive − [BUFFER_MIN] to spare;
 *   the event's travel time (the existing Leave by) is therefore drive + 25 minutes ([travelMin]).
 * - **When the server asks** ([isDue]): once a club fixture with a place is in the next [AHEAD_DAYS] days (the first
 *   look), again from 18:00 the evening before, and again [BEFORE_LEAVE_MIN] minutes before leaving (traffic on the
 *   day); also when the place changed or kick-off moved. Never once leaving has gone.
 * - **What is sent:** home's coordinates as the weather knows them (the town, not an address) and the ground's place
 *   as the fixture has it — no names, no title, no notes.
 * - Without the key the feed is [STATUS_OFF]: Setup says "Travel times · add the Google key" and the detail keeps the
 *   ground's remembered time ("as last time").
 */
object TravelRules {
    /** The server's feed id (Calendars lists it as "Travel times"). */
    const val PROVIDER = "travel"
    const val LABEL = "Google Routes · drive times to football"
    /** The feed's status while the server has no Google key. */
    const val STATUS_OFF = "off"
    const val MEET_EARLY_MIN = 15
    const val BUFFER_MIN = 10
    /** The drive assumed for the first look's departure time, before any answer. */
    const val GUESS_DRIVE_MIN = 30
    /** A drive longer than this is not a football trip MEKA trusts (and keeps the travel time under 240). */
    const val MAX_DRIVE_MIN = 180
    const val AHEAD_DAYS = 8
    /** 18:00 the evening before. */
    const val EVENING_MIN = 18 * 60
    const val BEFORE_LEAVE_MIN = 60
    /** At most this many lookups a day (Meka capped Google at 100 a day; MEKA stays well under). */
    const val MAX_PER_DAY = 40
    const val DRIVE = "drive"
    private const val MIN_MS = 60_000L

    /** The `travel_time` entity's id for a fixture: the same on every device and the server. */
    fun entityId(eventId: String): String = "tt" + ActivityRules.fnv64("travel:$eventId")

    /** The event's travel time (minutes before kick-off to leave) for a [driveMin] drive. */
    fun travelMin(driveMin: Int): Int = driveMin + MEET_EARLY_MIN + BUFFER_MIN

    /** When to leave for [e] with a [driveMin] drive. */
    fun leaveMs(e: CalendarEvent, driveMin: Int, cal: LocalCalendar): Long =
        FootballRules.kickOffMs(e, cal) - travelMin(driveMin) * MIN_MS

    /** A drive the server can keep: whole minutes, rounded up, 1–[MAX_DRIVE_MIN]; null otherwise. */
    fun driveMin(seconds: Long): Int? {
        if (seconds < 0) return null
        val min = ((seconds + 59) / 60).toInt().coerceAtLeast(1)
        return min.takeIf { it <= MAX_DRIVE_MIN }
    }

    /** A stored lookup; null when incomplete. */
    fun read(s: EntitySnapshot): TravelTime? = read(s.ref.entityId, s.fields)

    fun read(entityId: String, fields: Map<String, FieldValue>): TravelTime? {
        val event = fields[TravelTimeFields.EVENT_ID]?.textOrNull ?: return null
        if (entityId != entityId(event)) return null
        val key = fields[TravelTimeFields.PLACE_KEY]?.textOrNull ?: return null
        val drive = fields[TravelTimeFields.DRIVE_MIN]?.longOrNull?.toInt()?.takeIf { it in 1..MAX_DRIVE_MIN } ?: return null
        return TravelTime(event, key, drive, fields[TravelTimeFields.DEPART_AT]?.longOrNull ?: 0L, fields[TravelTimeFields.CHECKED_AT]?.longOrNull ?: 0L,
            fields[TravelTimeFields.KICK_OFF]?.longOrNull ?: 0L)
    }

    /** The fields the server writes for an answer. */
    fun fields(l: Lookup, driveMin: Int, nowMs: Long): Map<String, FieldValue> = linkedMapOf(
        TravelTimeFields.EVENT_ID to FieldValue.Text(l.eventId),
        TravelTimeFields.PLACE_KEY to FieldValue.Text(l.placeKey),
        TravelTimeFields.DRIVE_MIN to FieldValue.Int64(driveMin.toLong()),
        TravelTimeFields.DEPART_AT to FieldValue.Int64(l.departAtMs),
        TravelTimeFields.KICK_OFF to FieldValue.Int64(l.kickOffMs),
        TravelTimeFields.CHECKED_AT to FieldValue.Int64(nowMs),
    )

    /** One lookup to make: [place] is all that leaves MEKA about the fixture, [departAtMs] the planned departure. */
    data class Lookup(val eventId: String, val place: String, val placeKey: String, val departAtMs: Long, val kickOffMs: Long)

    /** A club fixture MEKA works a drive out for: timed, with a place, not hidden, not on its way to Google. */
    fun eligible(e: CalendarEvent, hidden: Set<String>): Boolean =
        !e.allDay && FootballRules.isClubFixture(e) && e.id !in hidden && FootballRules.venueKey(LeaveAlarmRules.place(e)) != null

    /**
     * Whether the server should ask about [e] now, given what it [known]. Only for an [eligible] fixture starting
     * within [AHEAD_DAYS] whose leaving is still ahead.
     */
    fun isDue(e: CalendarEvent, known: TravelTime?, nowMs: Long, cal: LocalCalendar): Boolean {
        if (e.startAtMs <= nowMs || e.startAtMs > nowMs + AHEAD_DAYS * CivilDate.DAY_MS) return false
        val key = FootballRules.venueKey(LeaveAlarmRules.place(e)) ?: return false
        if (known == null || known.placeKey != key) return leaveMs(e, known?.driveMin ?: GUESS_DRIVE_MIN, cal) > nowMs
        val leave = leaveMs(e, known.driveMin, cal)
        if (leave <= nowMs) return false
        if (known.kickOffMs != FootballRules.kickOffMs(e, cal)) return true
        val beforeLeave = leave - BEFORE_LEAVE_MIN * MIN_MS
        if (nowMs >= beforeLeave) return known.checkedAtMs < beforeLeave
        val evening = cal.toEpochMs(FootballRules.matchDay(e, cal) - 1, EVENING_MIN)
        return nowMs >= evening && known.checkedAtMs < evening
    }

    /** The lookups due now, soonest kick-off first. */
    fun due(events: List<CalendarEvent>, hidden: Set<String>, known: Map<String, TravelTime>, nowMs: Long, cal: LocalCalendar): List<Lookup> =
        events.asSequence()
            .filter { eligible(it, hidden) && isDue(it, known[it.id], nowMs, cal) }
            .sortedBy { it.startAtMs }
            .map { e ->
                val place = LeaveAlarmRules.place(e)!!
                val drive = known[e.id]?.driveMin ?: GUESS_DRIVE_MIN
                Lookup(e.id, place.take(MAX_PLACE), FootballRules.venueKey(place)!!, leaveMs(e, drive, cal), FootballRules.kickOffMs(e, cal))
            }.toList()

    /** The longest place sent (a calendar's location can carry a whole address; more is never needed). */
    const val MAX_PLACE = 200

    /** The drive [e] has from the server, while it is for the fixture's current place; null otherwise. */
    fun current(e: CalendarEvent, routes: Map<String, TravelTime>): TravelTime? {
        val key = FootballRules.venueKey(LeaveAlarmRules.place(e)) ?: return null
        return routes[e.id]?.takeIf { it.placeKey == key }
    }

    /** "25 min drive" · "1 h 10 min drive". */
    fun driveLabel(driveMin: Int): String = when {
        driveMin < 60 -> "$driveMin min $DRIVE"
        driveMin % 60 == 0 -> "${driveMin / 60} h $DRIVE"
        else -> "${driveMin / 60} h ${driveMin % 60} min $DRIVE"
    }
}
