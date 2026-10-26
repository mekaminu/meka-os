package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Travel mode (V1), slice 1: trips and their bookings, typed by Meka, as a day-by-day timeline. One field in
 * Ask → More → Trips takes both: "Barcelona 14–18 Nov" adds a trip; "Flight BA478 Sat 14 Nov 07:40 ref ABC123",
 * "Hotel Arts 14–18 Nov", "Train 18 Nov 13:05" add a booking to the trip whose days it falls on (or the day either
 * side, for a night flight). Today's header shows the trip a week ahead ("Barcelona in 3 days · Flight BA478 Sat
 * 07:40") and while it's on ("Barcelona · day 2 of 5 · 18:20 Flight BA479"); the week ahead lists the trip and each
 * booking on its day. Plain, non-AI rules; nothing leaves Meka's synced data.
 *
 * Bookings read from email wait on mail access (Needs Meka #4) and travel documents offline on the Vault; both would
 * write the same entities. Two synced entity types (ADR-008 addendum 2026-10-12): `trip` and `trip_booking`.
 */
object TripFields {
    /** "Barcelona" (at most [TripRules.MAX_NAME]). */
    const val NAME = "name"
    /** The first and last day away (local epoch days). */
    const val START = "startDay"
    const val END = "endDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
    /**
     * Trip lists slice 2d: who's going, the [TripGoer] wires joined by commas ("me,partner,kids"); empty when he took
     * everyone off, absent until he chooses. LWW.
     */
    const val GOING = "going"
    /** Trip lists slice 2d: the local minute he leaves home on the first day ("06:30"); absent when not set. LWW. */
    const val LEAVE_MINUTE = "leaveMinute"
    /**
     * Trip lists slice 3b: the suggestions put away with "Not now" on this trip ([TripSuggestRules] keys joined by
     * commas, at most [TripSuggestRules.MAX_DISMISSED]); absent when none. LWW.
     */
    const val SUGGEST_NO = "suggestNo"
}

/** Who's going on a trip (trip lists slice 2d): him, his partner, the children (names come from About you). */
enum class TripGoer(val wire: String) {
    ME("me"), PARTNER("partner"), KIDS("kids");

    companion object {
        fun of(wire: String?): TripGoer? = entries.firstOrNull { it.wire == wire }

        /** "me,kids" → {ME, KIDS}; unknown words are dropped; null stays null (never chosen). */
        fun read(text: String?): Set<TripGoer>? = text?.split(',')?.mapNotNull { of(it.trim()) }?.toSet()

        fun write(going: Set<TripGoer>): String = entries.filter { it in going }.joinToString(",") { it.wire }
    }
}

object TripBookingFields {
    const val TRIP = "tripId"
    /** [BookingKind.wire]. */
    const val KIND = "kind"
    /** "Flight BA478", "Hotel Arts" (at most [TripRules.MAX_TITLE]). */
    const val TITLE = "title"
    /** The day it happens (a stay's check-in day). */
    const val DAY = "day"
    /** A stay's or a hire car's last day (check-out, drop-off); absent for the rest. */
    const val END_DAY = "endDay"
    /** The local minute it starts (departure, check-in time); absent when no time was typed. */
    const val MINUTE = "minute"
    /** The booking reference, "ABC123"; absent when none was typed. */
    const val REF = "ref"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** What a booking is; [label] leads its line when it has no time. */
enum class BookingKind(val wire: String, val label: String) {
    FLIGHT("flight", "Flight"),
    TRAIN("train", "Train"),
    STAY("stay", "Stay"),
    CAR("car", "Car"),
    OTHER("other", "Booked");

    companion object { fun of(wire: String?): BookingKind = entries.firstOrNull { it.wire == wire } ?: OTHER }
}

data class TripBooking(
    val id: String, val tripId: String, val kind: BookingKind, val title: String, val day: Long, val endDay: Long?,
    val minute: Int?, val ref: String?, val addedAtMs: Long,
)

/** One stored trip with its live bookings, in time order. */
data class TripItem(
    val id: String, val name: String, val startDay: Long, val endDay: Long, val addedAtMs: Long,
    val bookings: List<TripBooking> = emptyList(),
    /** Slice 2d: who's going; null until he chooses. */
    val going: Set<TripGoer>? = null,
    /** Slice 2d: the minute he leaves home on [startDay]; null when not set. */
    val leaveMinute: Int? = null,
    /** Slice 3b: Obi's suggestions put away on this trip with "Not now". */
    val suggestNo: Set<String> = emptySet(),
) {
    val days: Int get() = (endDay - startDay + 1).toInt()
}

/** One line of a trip's timeline: "07:40" · "Flight BA478 · ref ABC123" (the kind's label leads when there's no time). */
data class TripLineRow(val key: String, val bookingId: String, val lead: String, val text: String, val spoken: String)

/** One day of a trip's timeline: "Sat 14 Nov · day 1" and what's booked that day. */
data class TripDayRow(val epochDay: Long, val label: String, val lines: List<TripLineRow>)

/**
 * A row in Trips: "Barcelona" · "Sat 14 – Wed 18 Nov · 5 days · 3 bookings" · "In 33 days" ([statusLit] while it's
 * on); unfolded, its days with bookings.
 */
data class TripRow(
    val id: String,
    val name: String,
    val line: String,
    val status: String,
    val statusLit: Boolean,
    val timeline: List<TripDayRow>,
    val spoken: String,
    /** Travel documents from the Vault ([TripDocRules]); null once the trip is over or when the Vault wasn't read. */
    val documents: TripDocs? = null,
)

data class TripsView(
    val rows: List<TripRow>,
    /** "On a trip: Barcelona · day 2 of 5", "Next: Barcelona · Sat 14 Nov · in 33 days", or the empty line. */
    val summary: String,
    /**
     * Trips that ended more than [TripRules.RECENT_DAYS] ago, newest first (completeness slice 4): kept, folded under
     * PAST TRIPS, each still opening its timeline; at most [TripRules.PAST_MAX].
     */
    val past: List<TripRow> = emptyList(),
    /** "PAST TRIPS · 4", or "" with none. */
    val pastLabel: String = "",
) {
    val isEmpty: Boolean get() = rows.isEmpty() && past.isEmpty()

    companion object { val EMPTY = TripsView(emptyList(), TripRules.EMPTY_LINE) }
}

/** Today's header: "Barcelona in 3 days · Flight BA478 Sat 07:40", "Barcelona · day 2 of 5 · 18:20 Flight BA479". */
data class TripLine(val text: String, val spoken: String, val lit: Boolean)

/** What adding a line did: the line under the field, and whether nothing was written. */
data class TripAdded(val line: String, val refused: Boolean)

/**
 * What removing a trip or a booking did, for Undo (completeness slice 4): "Removed Barcelona and its 3 bookings" and
 * the [token] that [Trips.undoRemove] takes to put exactly those back.
 */
data class TripRemoved(val line: String, val token: String)

/** A typed trip ([TripRules.read]). */
data class TripEntry(val name: String, val startDay: Long, val endDay: Long)

/** A typed booking ([TripRules.read]), for the trip [tripId]. */
data class BookingEntry(
    val tripId: String, val kind: BookingKind, val title: String, val day: Long, val endDay: Long?, val minute: Int?, val ref: String?,
)

object TripRules {
    const val MAX_NAME = 40
    const val MAX_TITLE = 60
    const val MAX_REF = 16
    /** Today's header shows a trip from this many days before it starts. */
    const val LEAD_DAYS = 7
    /** An ended trip stays among YOUR TRIPS this many days, then moves to PAST TRIPS (completeness slice 4). */
    const val RECENT_DAYS = 7
    /** PAST TRIPS shows at most this many, newest first. */
    const val PAST_MAX = 24
    const val PAST_LABEL = "PAST TRIPS"
    /** How long the Undo after a removal stays offered on both apps. */
    const val UNDO_MS = 6_000L
    /** A booking may fall this many days either side of its trip (a night flight, a hotel the night before). */
    const val SLACK_DAYS = 1

    /**
     * The add field's hint: one example that fits the pill on one line on the cover screen (quality gate slice 20; the
     * longer "Add… (…, …)" took three lines). With no trip yet it shows a trip; once there is one, a booking.
     * TalkBack reads [ADD_DESCRIPTION]; the empty line and [NOT_READ] carry the fuller examples.
     */
    const val ADD_HINT = "Barcelona 14–18 Nov"
    const val BOOKING_HINT = "Flight BA478 14 Nov 07:40"
    const val ADD_DESCRIPTION = "Add a trip with its dates or a booking with its day and time, for example Barcelona 14–18 Nov, or Flight BA478 14 Nov 07:40"
    fun addHint(hasTrips: Boolean): String = if (hasTrips) BOOKING_HINT else ADD_HINT
    const val EMPTY_LINE = "No trips yet. Type a trip with its dates, then each booking with its day, time and reference."
    const val NOT_READ = "Navilife couldn't read that. Try \"Barcelona 14–18 Nov\" or \"Flight BA478 Sat 14 Nov 07:40 ref ABC123\"."
    const val NO_DAY = "Navilife couldn't read a day in that booking. Try \"Flight BA478 Sat 14 Nov 07:40 ref ABC123\"."
    const val NO_TRIP = "Add the trip first, with its dates (\"Barcelona 14–18 Nov\"), then its bookings."
    const val NO_BOOKINGS = "Nothing booked yet. Type each booking above with its day: \"Hotel Arts 14–18 Nov\"."
    const val NOTE = "Typed by you, times as on your tickets: the way there in UK time, the rest local. Bookings from your email come with mail access."

    private val KINDS: Map<String, BookingKind> = buildMap {
        listOf("flight", "flights", "fly", "flying", "plane").forEach { put(it, BookingKind.FLIGHT) }
        listOf("train", "trains", "eurostar", "rail", "coach", "bus").forEach { put(it, BookingKind.TRAIN) }
        listOf("hotel", "airbnb", "stay", "staying", "apartment", "villa", "hostel", "checkin", "check-in", "checkout", "check-out")
            .forEach { put(it, BookingKind.STAY) }
        listOf("car", "hire", "rental", "taxi", "transfer", "uber", "cab", "parking").forEach { put(it, BookingKind.CAR) }
        listOf("ferry", "ticket", "tickets", "tour", "table", "dinner", "lunch", "restaurant", "reservation", "show", "museum", "match")
            .forEach { put(it, BookingKind.OTHER) }
    }
    private val REF_WORDS = setOf("ref", "reference", "booking", "confirmation", "conf", "pnr")
    private val REF_FILLER = setOf("ref", "reference", "no", "number", "code", "#", ":")

    /** A word with its surrounding punctuation trimmed (hyphens and apostrophes kept), lower case. */
    private fun bare(w: String): String = w.trim { !it.isLetterOrDigit() && it != '-' && it != '#' }.lowercase()

    /** The kind the line's first words name ("Flight …", "Check in …", "Car hire …"), or null for a trip. */
    fun kindOf(text: String): BookingKind? {
        val ws = text.split(' ').map(::bare).filter { it.isNotEmpty() }
        if (ws.isEmpty()) return null
        if (ws[0] == "check" && ws.getOrNull(1) in setOf("in", "out")) return BookingKind.STAY
        return KINDS[ws[0]]
    }

    /**
     * The booking reference ("ref ABC123", "booking ref: XKQPLM", "confirmation 4471-22") and the text without it. A
     * reference has a digit or was typed in capitals, so "booking for two" keeps its words.
     */
    fun takeRef(text: String): Pair<String?, String> {
        val ws = text.split(' ').filter { it.isNotEmpty() }
        for (i in ws.indices) {
            if (bare(ws[i]).trimEnd(':') !in REF_WORDS) continue
            var j = i + 1
            while (j < ws.size && bare(ws[j]).trimEnd(':').ifEmpty { ":" } in REF_FILLER) j++
            val raw = ws.getOrNull(j) ?: continue
            val ref = raw.trim { !it.isLetterOrDigit() && it != '-' }
            val ok = ref.length in 3..MAX_REF && ref.all { it.isLetterOrDigit() || it == '-' } &&
                (ref.any { it.isDigit() } || ref.all { !it.isLetter() || it.isUpperCase() })
            if (!ok) continue
            return ref.uppercase() to (ws.subList(0, i) + ws.subList(j + 1, ws.size)).joinToString(" ")
        }
        return null to text
    }

    /** What one typed line says. */
    sealed interface Read {
        data class Trip(val entry: TripEntry) : Read
        data class Booking(val entry: BookingEntry) : Read
        data class Refused(val line: String) : Read
    }

    /** Trips a booking can join or a name can update: not ended before yesterday. */
    fun open(trips: List<TripItem>, today: Long): List<TripItem> = trips.filter { it.endDay >= today - SLACK_DAYS }

    /** The trip whose days (or the day either side) [day] falls on, the earliest first. */
    fun tripFor(trips: List<TripItem>, day: Long): TripItem? =
        trips.sortedBy { it.startDay }.firstOrNull { day in (it.startDay - SLACK_DAYS)..(it.endDay + SLACK_DAYS) }

    /**
     * Reads a line typed in Trips. A line that starts with a booking word ([kindOf]) is a booking: its day or days
     * ("Sat 14 Nov", "14–18 Nov" for a stay), a time at the end ("07:40", "3pm"), a reference; it joins the trip
     * it falls on. Otherwise a date or a range makes a trip named by the words left ("Barcelona"), unless a time was
     * typed and a trip covers that day ("Dinner at Can Solé 15 Nov 20:30" joins the trip).
     */
    fun read(text: String, today: Long, nowMinute: Int, trips: List<TripItem>): Read {
        val s = SchoolRules.tidy(text)
        if (s.isEmpty()) return Read.Refused(NOT_READ)
        val (ref, s1) = takeRef(s)
        val kind = kindOf(s1)
        val span = SchoolRules.findSpan(s1, today, earliest = today - SLACK_DAYS)
        if (span == SpanFound.Bad) return Read.Refused(if (kind != null) NO_DAY else NOT_READ)
        val days = span as? SpanFound.Days
        val rest = days?.rest ?: s1
        val typed = EventTypingRules.parse(SchoolRules.tidy(rest), today, nowMinute)
        val live = open(trips, today)
        fun booking(k: BookingKind, title: String): Read {
            val day = days?.startDay ?: typed?.day ?: return Read.Refused(NO_DAY)
            val trip = tripFor(live, day) ?: return Read.Refused(
                if (live.isEmpty()) NO_TRIP else "No trip on ${CivilDate.shortLabel(day)} yet. $NO_TRIP",
            )
            val end = days?.endDay?.takeIf { it > day && (k == BookingKind.STAY || k == BookingKind.CAR) }
            val minute = typed?.takeIf { !it.allDay }?.minute
            val t = title.ifEmpty { k.label }.take(MAX_TITLE).trim()
            return Read.Booking(BookingEntry(trip.id, k, t, day, end, minute, ref))
        }
        if (kind != null) return booking(kind, SchoolRules.cleanTitle(typed?.title ?: rest))
        if (days == null) return Read.Refused(NOT_READ)
        if (days.startDay == days.endDay && typed?.minute != null && !typed.allDay && tripFor(live, days.startDay) != null) {
            return booking(BookingKind.OTHER, SchoolRules.cleanTitle(typed.title))
        }
        val name = SchoolRules.cleanTitle(rest).take(MAX_NAME).trim()
        if (name.isEmpty()) return Read.Refused(NOT_READ)
        return Read.Trip(TripEntry(name, days.startDay, days.endDay))
    }

    // ---- Stored entities ----

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun bookings(entities: Map<String, Map<String, FieldValue>>): Map<String, List<TripBooking>> = entities.mapNotNull { (id, s) ->
        if (s.f(TripBookingFields.DELETED).boolOrNull == true) return@mapNotNull null
        val trip = s.f(TripBookingFields.TRIP).textOrNull ?: return@mapNotNull null
        val day = s.f(TripBookingFields.DAY).longOrNull ?: return@mapNotNull null
        val title = s.f(TripBookingFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val minute = s.f(TripBookingFields.MINUTE).longOrNull?.toInt()?.takeIf { it in 0 until 24 * 60 }
        val end = s.f(TripBookingFields.END_DAY).longOrNull?.takeIf { it > day }
        TripBooking(
            id, trip, BookingKind.of(s.f(TripBookingFields.KIND).textOrNull), title, day, end, minute,
            s.f(TripBookingFields.REF).textOrNull?.takeIf { it.isNotBlank() }, s.f(TripBookingFields.ADDED_AT).longOrNull ?: 0L,
        )
    }.sortedWith(compareBy<TripBooking> { it.day }.thenBy { it.minute ?: -1 }.thenBy { it.addedAtMs }.thenBy { it.id })
        .groupBy { it.tripId }

    /** The live trips, soonest first, each with its bookings; unreadable ones are skipped. */
    fun items(trips: Map<String, Map<String, FieldValue>>, bookings: Map<String, List<TripBooking>>): List<TripItem> =
        trips.mapNotNull { (id, s) ->
            if (s.f(TripFields.DELETED).boolOrNull == true) return@mapNotNull null
            val name = s.f(TripFields.NAME).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = s.f(TripFields.START).longOrNull ?: return@mapNotNull null
            val end = s.f(TripFields.END).longOrNull?.takeIf { it >= start } ?: start
            TripItem(
                id, name, start, end, s.f(TripFields.ADDED_AT).longOrNull ?: 0L, bookings[id].orEmpty(),
                going = TripGoer.read(s.f(TripFields.GOING).textOrNull),
                leaveMinute = s.f(TripFields.LEAVE_MINUTE).longOrNull?.toInt()?.takeIf { it in 0 until 24 * 60 },
                suggestNo = s.f(TripFields.SUGGEST_NO).textOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
        }.sortedWith(compareBy<TripItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })

    fun fields(e: TripEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        TripFields.NAME to e.name.fv(),
        TripFields.START to e.startDay.fv(),
        TripFields.END to e.endDay.fv(),
        TripFields.ADDED_AT to nowMs.fv(),
        TripFields.DELETED to false.fv(),
    )

    fun fields(e: BookingEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        TripBookingFields.TRIP to e.tripId.fv(),
        TripBookingFields.KIND to e.kind.wire.fv(),
        TripBookingFields.TITLE to e.title.fv(),
        TripBookingFields.DAY to e.day.fv(),
        TripBookingFields.END_DAY to e.endDay.fv(),
        TripBookingFields.MINUTE to e.minute.fv(),
        TripBookingFields.REF to e.ref.fv(),
        TripBookingFields.ADDED_AT to nowMs.fv(),
        TripBookingFields.DELETED to false.fv(),
    )

    // ---- Words ----

    private fun time(m: Int?): String? = m?.let { LocalClock.formatMinute(it) }

    /** "Flight BA478 · ref ABC123". */
    fun bookingText(b: TripBooking): String = b.title + (b.ref?.let { " · ref $it" } ?: "")

    /** "In 33 days", "Tomorrow", "Day 2 of 5", "Ended Wed 18 Nov". */
    fun status(t: TripItem, today: Long): String = when {
        today < t.startDay -> when (val n = t.startDay - today) { 1L -> "Tomorrow"; else -> "In $n days" }
        today <= t.endDay -> "Day ${today - t.startDay + 1} of ${t.days}"
        else -> "Ended ${CivilDate.shortLabel(t.endDay)}" +
            CivilDate.fromEpochDay(t.endDay).year.takeIf { it != CivilDate.fromEpochDay(today).year }?.let { " $it" }.orEmpty()
    }

    /** The undo line after × or Remove trip: "Removed Barcelona and its 3 bookings", "Removed Flight BA478 from Barcelona". */
    fun removedLine(t: TripItem): String =
        "Removed ${t.name}" + t.bookings.size.takeIf { it > 0 }?.let { " and its ${plural(it, "booking")}" }.orEmpty()

    fun removedLine(b: TripBooking, tripName: String?): String = "Removed ${b.title}" + (tripName?.let { " from $it" } ?: "")

    /** Undo's token: "trip-id;booking-id,booking-id" (an empty trip part for a booking alone). */
    fun undoToken(tripId: String?, bookingIds: List<String>): String = (tripId ?: "") + ";" + bookingIds.joinToString(",")

    /** The trip id (or null) and booking ids in [token]; null when it isn't one. */
    fun readToken(token: String): Pair<String?, List<String>>? {
        val parts = token.split(';')
        if (parts.size != 2) return null
        val trip = parts[0].takeIf { it.isNotEmpty() }
        val bookings = parts[1].split(',').filter { it.isNotEmpty() }
        if (trip == null && bookings.isEmpty()) return null
        return trip to bookings
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /**
     * "Sat 14 – Wed 18 Nov · 5 days · 3 bookings", then "1 h ahead of UK time" when the destination's clock differs,
     * kept whole with no-break spaces so "· 1 h" never ends a line on the cover screen (quality gate slice 20).
     */
    fun rowLine(t: TripItem, clocks: TripClocks? = null): String = listOfNotNull(
        CalendarAgenda.spanLabel(t.startDay, t.endDay),
        plural(t.days, "day"),
        t.bookings.size.takeIf { it > 0 }?.let { plural(it, "booking") },
        TripZoneRules.tripLine(t, clocks)?.replace(' ', LifeTileRules.GLUE[0]),
    ).joinToString(" · ")

    /** Each booking's lines by day: its own day, and a stay's check-out (a hire car's drop-off) on its last day. */
    private fun linesByDay(t: TripItem, clocks: TripClocks?): Map<Long, List<TripLineRow>> {
        data class K(val day: Long, val minute: Int, val line: TripLineRow)
        val out = mutableListOf<K>()
        for (b in t.bookings) {
            val text = bookingText(b) + (if (b.endDay != null) " · until ${CivilDate.shortLabel(b.endDay)}" else "") +
                (TripZoneRules.ukTime(b, t, clocks)?.let { " · $it" } ?: "")
            val lead = time(b.minute) ?: b.kind.label
            out += K(b.day, b.minute ?: -1, TripLineRow("bk-${b.id}", b.id, lead, text, "$lead, $text"))
            if (b.endDay != null) {
                val word = if (b.kind == BookingKind.CAR) "Drop off" else "Check out"
                val outText = "$word · ${b.title}"
                out += K(b.endDay, -1, TripLineRow("bk-${b.id}-end", b.id, b.kind.label, outText, "${b.kind.label}, $outText"))
            }
        }
        return out.sortedWith(compareBy<K> { it.day }.thenBy { it.minute }).groupBy({ it.day }, { it.line })
    }

    /** The trip's days that have something booked (and its first day, always), "Sat 14 Nov · day 1". */
    fun timeline(t: TripItem, clocks: TripClocks? = null): List<TripDayRow> {
        val byDay = linesByDay(t, clocks)
        val days = (byDay.keys + t.startDay).distinct().sorted()
        return days.map { d ->
            val n = d - t.startDay + 1
            val label = CivilDate.shortLabel(d) + when {
                n < 1 -> " · the day before"
                d > t.endDay -> " · the day after"
                else -> " · day $n"
            }
            TripDayRow(d, label, byDay[d].orEmpty())
        }
    }

    /** A first day whose bookings all leave from this minute on is an evening departure: he may work first. */
    const val EVENING_DEPART_MIN = 16 * 60

    /**
     * Travel mode completeness (2026-10-18): the days he's away, each with its trip's name, for work mode
     * ([WorkHours.away]): every day of a trip from its first to its last, so none is an office day (no commute, trains,
     * leave-by or "who's covering?") and the brief and the week ahead say where he is. A first day whose timed
     * bookings all start from [EVENING_DEPART_MIN] (an evening flight) isn't counted. Two trips on one day: the earlier.
     */
    fun awayDays(trips: List<TripItem>): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        for (t in trips.sortedBy { it.startDay }) {
            val firstTimed = t.bookings.filter { it.day == t.startDay && it.minute != null }
            val evening = firstTimed.isNotEmpty() && firstTimed.all { it.minute!! >= EVENING_DEPART_MIN }
            val from = if (evening) t.startDay + 1 else t.startDay
            for (d in from..t.endDay) out.getOrPut(d) { t.name }
        }
        return out
    }

    /** The trips YOUR TRIPS shows: those not ended more than [RECENT_DAYS] ago; the rest are past trips. */
    private fun shown(trips: List<TripItem>, today: Long) = trips.filter { it.endDay >= today - RECENT_DAYS }

    private fun ongoing(trips: List<TripItem>, today: Long) = trips.firstOrNull { today in it.startDay..it.endDay }
    private fun upcoming(trips: List<TripItem>, today: Long) = trips.filter { it.startDay > today }.minByOrNull { it.startDay }

    private fun inDays(day: Long, today: Long): String = when (val n = day - today) {
        0L -> "today"; 1L -> "tomorrow"; else -> "in $n days"
    }

    /** The Trips pane: on now, then to come soonest first, then those just ended. */
    fun view(trips: List<TripItem>, today: Long, docs: List<DocumentItem>? = null, clocks: TripClocks? = null): TripsView {
        val list = shown(trips, today)
        val gone = trips.filter { it.endDay < today - RECENT_DAYS }.sortedWith(compareByDescending<TripItem> { it.endDay }.thenBy { it.id })
        if (list.isEmpty() && gone.isEmpty()) return TripsView.EMPTY
        val ordered = list.filter { it.endDay >= today } + list.filter { it.endDay < today }.sortedByDescending { it.endDay }
        fun row(t: TripItem, withDocs: Boolean): TripRow {
            val status = status(t, today)
            val line = rowLine(t, clocks)
            return TripRow(
                t.id, t.name, line, status, today in t.startDay..t.endDay, timeline(t, clocks), "${t.name}. $line. $status",
                if (withDocs) docs?.let { TripDocRules.forTrip(t, it, today) } else null,
            )
        }
        val rows = ordered.map { row(it, withDocs = true) }
        val past = gone.take(PAST_MAX).map { row(it, withDocs = false) }
        val now = ongoing(list, today)
        val next = upcoming(list, today)
        val summary = when {
            now != null -> "On a trip: ${now.name} · day ${today - now.startDay + 1} of ${now.days}"
            next != null -> "Next: ${next.name} · ${CivilDate.shortLabel(next.startDay)} · ${inDays(next.startDay, today)}"
            else -> "No trips coming up"
        }
        return TripsView(rows, summary, past, if (gone.isEmpty()) "" else "$PAST_LABEL · ${gone.size}")
    }

    /** "Flight BA478 Sat 07:40" (the day's short name only when it isn't today). */
    private fun nextText(b: TripBooking, today: Long): String {
        val day = when (b.day) {
            today -> null
            today + 1 -> "tomorrow"
            else -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(b.day) - 1]
        }
        return listOfNotNull(b.title, day, time(b.minute)).joinToString(" ")
    }

    /**
     * Today's header: a trip within [LEAD_DAYS] says when it starts and its first booking ("Barcelona in 3 days ·
     * Flight BA478 Sat 07:40", lit the day before); while it's on, which day it is and what's next today or tomorrow
     * ("Barcelona · day 2 of 5 · Flight BA479 18:20", lit when something is booked later today). Null otherwise.
     */
    fun todayLine(trips: List<TripItem>, today: Long, nowMinute: Int): TripLine? {
        ongoing(trips, today)?.let { t ->
            val next = t.bookings.firstOrNull { b ->
                (b.day == today && b.minute != null && b.minute >= nowMinute) || b.day == today + 1
            }
            val head = "${t.name} · day ${today - t.startDay + 1} of ${t.days}"
            val text = head + (next?.let { " · ${nextText(it, today)}" } ?: "")
            val spoken = "${t.name}, day ${today - t.startDay + 1} of ${t.days}." + (next?.let { " Next: ${nextText(it, today)}." } ?: "")
            return TripLine(text, spoken, lit = next?.day == today)
        }
        val t = upcoming(trips, today)?.takeIf { it.startDay - today <= LEAD_DAYS } ?: return null
        // The way there: the first timed booking by the first day (a departure), else the first at all.
        val first = t.bookings.firstOrNull { it.day <= t.startDay && it.minute != null } ?: t.bookings.firstOrNull()
        val text = "${t.name} ${inDays(t.startDay, today)}" + (first?.let { " · ${nextText(it, today)}" } ?: "")
        val spoken = "${t.name} ${inDays(t.startDay, today)}." + (first?.let { " ${nextText(it, today)}." } ?: "")
        return TripLine(text, spoken, lit = t.startDay == today + 1)
    }

    /**
     * The week ahead's lines on [day]: the trip on its first day in the week ("Barcelona trip · until Wed 18 Nov"),
     * then each booking on its day ([time] with its minute) and a stay's check-out. Each is (minute or null, text, id).
     */
    fun weekLines(trips: List<TripItem>, day: Long, weekFirst: Long): List<Triple<Int?, String, String>> {
        val out = mutableListOf<Triple<Int?, String, String>>()
        for (t in trips) {
            if (day == maxOf(t.startDay, weekFirst) && day <= t.endDay) {
                val until = if (t.endDay > day) " · until ${CivilDate.shortLabel(t.endDay)}" else ""
                out += Triple(null, "${t.name} trip$until", "t-${t.id}")
            }
            for (b in t.bookings) {
                if (b.day == day) out += Triple(b.minute, bookingText(b), "tb-${b.id}")
                if (b.endDay == day) {
                    val word = if (b.kind == BookingKind.CAR) "Drop off" else "Check out"
                    out += Triple(null, "$word · ${b.title}", "tb-${b.id}-end")
                }
            }
        }
        return out
    }

    /** The line after adding: "Added Barcelona · Sat 14 – Wed 18 Nov · in 33 days". */
    fun addedLine(e: TripEntry, today: Long, replaced: Boolean): String {
        val whenWord = if (e.startDay <= today) "on now" else inDays(e.startDay, today)
        return "${if (replaced) "Updated" else "Added"} ${e.name} · ${CalendarAgenda.spanLabel(e.startDay, e.endDay)} · $whenWord"
    }

    /** "Added to Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123". */
    fun addedLine(e: BookingEntry, trip: TripItem): String = "Added to " + bookingLine(e, trip.name)

    /** The evening-before heads-up comes at 18:00 the day before a trip's first day. */
    const val EVE_MIN = 18 * 60
    /** It stands until noon of that first day. */
    const val EVE_UNTIL_MIN = 12 * 60

    /**
     * Travel mode, slice 2b: the evening before a trip, at [EVE_MIN], one heads-up with the first booking's time and
     * reference — "Barcelona tomorrow · Flight BA478 07:40" · "ref ABC123 · then Hotel Arts until Wed 18 Nov" — or
     * "Nothing booked in Trips yet" with none. The trip's first day is its start, or an earlier booking's (a hotel the
     * night before). Standing until noon that day; one per trip and first day (moving the dates posts it again).
     */
    fun notices(trips: List<TripItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return trips.mapNotNull { t ->
            val first = minOf(t.startDay, t.bookings.minOfOrNull { it.day } ?: t.startDay)
            if (first !in today..today + 2) return@mapNotNull null
            val ordered = t.bookings.sortedWith(compareBy({ it.day }, { it.minute ?: Int.MAX_VALUE }, { it.addedAtMs }))
            val lead = ordered.firstOrNull { it.day == first && it.minute != null } ?: ordered.firstOrNull { it.day == first }
            // Posted late (added on the morning itself), it says today.
            val head = "${t.name} ${if (first <= today) "today" else "tomorrow"}" + (lead?.let { b -> " · " + b.title + (time(b.minute)?.let { " $it" } ?: "") } ?: "")
            val then = ordered.firstOrNull { it != lead }?.let { b ->
                "then " + b.title + when {
                    b.endDay != null -> " until ${CivilDate.shortLabel(b.endDay)}"
                    b.day != first -> " · ${CivilDate.shortLabel(b.day)}"
                    else -> time(b.minute)?.let { " $it" } ?: ""
                }
            }
            val text = when {
                lead == null -> "Nothing booked in Trips yet · ${CalendarAgenda.spanLabel(t.startDay, t.endDay)}"
                else -> listOfNotNull(lead.ref?.let { "ref $it" }, then).joinToString(" · ").ifEmpty { CalendarAgenda.spanLabel(t.startDay, t.endDay) }
            }
            Notice(
                key = "trip:${t.id}:$first", source = NoticeSource.TRIPS, tier = NoticeTier.HEADS_UP,
                title = head, text = text, atMs = cal.toEpochMs(first - 1, EVE_MIN), target = NoticeTarget.TODAY,
                expiresAtMs = cal.toEpochMs(first, EVE_UNTIL_MIN),
            )
        }
    }

    /** "Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123" (a stay "Sat 14 – Wed 18 Nov"). */
    fun bookingLine(e: BookingEntry, tripName: String): String {
        val whenText = (if (e.endDay != null) CalendarAgenda.spanLabel(e.day, e.endDay) else CivilDate.shortLabel(e.day)) +
            (time(e.minute)?.let { " $it" } ?: "")
        return listOfNotNull(tripName, e.title, whenText, e.ref?.let { "ref $it" }).joinToString(" · ")
    }
}

/**
 * Travel mode, slice 2a: Ask and Talk know the trips ("when do we fly to Barcelona?", "what's the hotel's
 * reference?"), and a model's `add_booking` ("Flight BA478 Sat 14 Nov 07:40 ref ABC123", written as Meka would type it
 * in Trips) becomes a card that adds the booking to the trip it falls on, on Meka's tap, with Undo. Plain rules.
 */
object TripAskRules {
    /** Trips the Ask lines name (on now first, then soonest). */
    const val ASK_TRIPS = 4
    /** Bookings named per trip. */
    const val ASK_BOOKINGS = 8
    /** At most this many lines in all, the head included. */
    const val ASK_LINES = 30
    const val GONE = "That trip isn't in Trips any more"
    const val ALREADY = "That booking is already in the trip"

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /**
     * The trips as MEKA describes them to the model (Ask's `trips` lines): "Trips · 1 on now or coming", then per trip
     * "Trip · Barcelona · Sat 14 – Wed 18 Nov · 5 days · in 33 days" and a line per booking: "Barcelona · Flight ·
     * Sat 14 Nov 07:40 · Flight BA478 · ref ABC123", "Barcelona · Stay · Sat 14 – Wed 18 Nov · Hotel Arts". Trips
     * already over are left out; nothing at all while none is on or coming.
     */
    fun askLines(trips: List<TripItem>, today: Long, clocks: TripClocks? = null): List<String> {
        val live = trips.filter { it.endDay >= today }.sortedBy { it.startDay }
        if (live.isEmpty()) return emptyList()
        val out = mutableListOf("Trips · ${live.size} on now or coming" + if (live.size > ASK_TRIPS) " · the first $ASK_TRIPS below" else "")
        for (t in live.take(ASK_TRIPS)) {
            out += listOfNotNull(
                "Trip", t.name, CalendarAgenda.spanLabel(t.startDay, t.endDay), plural(t.days, "day"),
                TripRules.status(t, today).replaceFirstChar { it.lowercase() },
                if (t.bookings.isEmpty()) "nothing booked yet" else plural(t.bookings.size, "booking"),
            ).joinToString(" · ")
            // Its own short line (Ask's lines are at most 160 characters).
            TripZoneRules.tripLine(t, clocks)?.let { out += "${t.name} · local time $it · the way there in UK time" }
            for (b in t.bookings.take(ASK_BOOKINGS)) {
                val whenText = (if (b.endDay != null) CalendarAgenda.spanLabel(b.day, b.endDay) else CivilDate.shortLabel(b.day)) +
                    (b.minute?.let { " " + LocalClock.formatMinute(it) } ?: "")
                out += listOfNotNull(
                    t.name, b.kind.label, whenText, b.title, b.ref?.let { "ref $it" }, TripZoneRules.ukTime(b, t, clocks)?.let { "($it)" },
                ).joinToString(" · ")
            }
            if (t.bookings.size > ASK_BOOKINGS) out += "${t.name} · ${t.bookings.size - ASK_BOOKINGS} more bookings"
        }
        return out.take(ASK_LINES)
    }

    /**
     * A model's `add_booking` as a card: [title] read as a line typed in Trips ([TripRules.read]); when it has no day,
     * the action's [day] (and [minute]) are added to it. Only a booking that falls on a trip makes a card; one with the
     * same title on the same day of that trip is already there (null).
     */
    fun proposal(trips: List<TripItem>, title: String?, day: Long?, minute: Int?, today: Long, nowMinute: Int): AskProposal.AddBooking? {
        val t = title?.replace('\n', ' ')?.split(' ', '\t')?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > 200) return null
        var r = TripRules.read(t, today, nowMinute, trips)
        if (r !is TripRules.Read.Booking && day != null) {
            val extra = CivilDate.shortLabel(day) + (minute?.let { " " + LocalClock.formatMinute(it) } ?: "")
            r = TripRules.read("$t $extra", today, nowMinute, trips)
        }
        val read = (r as? TripRules.Read.Booking)?.entry ?: return null
        val entry = if (read.minute == null && minute != null && (day == null || day == read.day)) read.copy(minute = minute) else read
        val trip = trips.firstOrNull { it.id == entry.tripId } ?: return null
        if (already(trip, entry)) return null
        return AskProposal.AddBooking(entry, trip.name)
    }

    /** The same title on the same day is already in the trip. */
    fun already(trip: TripItem, e: BookingEntry): Boolean = trip.bookings.any { it.day == e.day && it.title.equals(e.title, ignoreCase = true) }

    /** The card's words: "Add to Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123". */
    fun cardLine(p: AskProposal.AddBooking): String = "Add to " + TripRules.bookingLine(p.entry, p.tripName)

    /** The undo bar's line: "Added to Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123". */
    fun doneLine(p: AskProposal.AddBooking): String = "Added to " + TripRules.bookingLine(p.entry, p.tripName)
}

/** The trips and bookings on a replica. */
class Trips(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<TripItem> {
        val bookings = TripRules.bookings(replica.entities(EntityTypes.TRIP_BOOKING).associate { it.ref.entityId to it.fields })
        return TripRules.items(replica.entities(EntityTypes.TRIP).associate { it.ref.entityId to it.fields }, bookings)
    }

    fun view(): TripsView = TripRules.view(items(), calendar.epochDayOf(nowMs()))

    /** The days away on a trip ([TripRules.awayDays]), from a week back. */
    fun awayDays(): Map<Long, String> {
        val from = calendar.epochDayOf(nowMs()) - 7
        return TripRules.awayDays(items().filter { it.endDay >= from })
    }

    /** Adds a booking checked elsewhere (Ask's card); its id. */
    fun addBooking(e: BookingEntry): String {
        val id = newId()
        replica.commitLocal(EntityTypes.TRIP_BOOKING, id, TripRules.fields(e, nowMs()))
        return id
    }

    /**
     * Adds what [text] says ([TripRules.read]): a trip (one not yet over by the same name has its dates updated) or a
     * booking on the trip it falls on. Refused (nothing written) when MEKA couldn't read it.
     */
    fun add(text: String): TripAdded {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        val trips = items()
        return when (val r = TripRules.read(text, today, calendar.minuteOfDay(now), trips)) {
            is TripRules.Read.Refused -> TripAdded(r.line, refused = true)
            is TripRules.Read.Trip -> {
                val existing = TripRules.open(trips, today).firstOrNull { it.name.equals(r.entry.name, ignoreCase = true) }
                val fields = TripRules.fields(r.entry, now).let { f -> if (existing != null) f - TripFields.ADDED_AT else f }
                replica.commitLocal(EntityTypes.TRIP, existing?.id ?: newId(), fields)
                TripAdded(TripRules.addedLine(r.entry, today, replaced = existing != null), refused = false)
            }
            is TripRules.Read.Booking -> {
                val trip = trips.first { it.id == r.entry.tripId }
                replica.commitLocal(EntityTypes.TRIP_BOOKING, newId(), TripRules.fields(r.entry, now))
                TripAdded(TripRules.addedLine(r.entry, trip), refused = false)
            }
        }
    }

    /** Removes a trip and its bookings for good; false when it isn't there. */
    fun remove(id: String): Boolean = removeForUndo(id) != null

    /** Removes one booking for good; false when it isn't there. */
    fun removeBooking(id: String): Boolean = removeBookingForUndo(id) != null

    /**
     * Trip lists slice 2d: puts [goer] on or off the trip [tripId]'s who's going. A trip never chosen starts from nobody,
     * so the first tap says just that one. False when the trip has gone or nothing changed.
     */
    fun setGoing(tripId: String, goer: TripGoer, on: Boolean): Boolean {
        val trip = items().firstOrNull { it.id == tripId } ?: return false
        val before = trip.going.orEmpty()
        val after = if (on) before + goer else before - goer
        if (trip.going != null && after == before) return false
        replica.commitLocal(EntityTypes.TRIP, tripId, mapOf(TripFields.GOING to TripGoer.write(after).fv()))
        return true
    }

    /**
     * Trip lists slice 2d: the time he leaves home on the trip's first day, typed ("06:30", "6.30am", "leave at 7");
     * blank clears it. Refused (nothing written) when it isn't a time or the trip has gone.
     */
    /** Slice 3b: puts Obi's suggestions [keys] away on the trip [tripId] ("Not now"); false when nothing changed. */
    fun dismissSuggestions(tripId: String, keys: Collection<String>): Boolean {
        val trip = items().firstOrNull { it.id == tripId } ?: return false
        val clean = keys.map { it.replace(',', ' ').trim() }.filter { it.isNotEmpty() }
        if (clean.all { it in trip.suggestNo }) return false
        val after = TripSuggestRules.dismissed(trip.suggestNo, clean)
        replica.commitLocal(EntityTypes.TRIP, tripId, mapOf(TripFields.SUGGEST_NO to after.joinToString(",").fv()))
        return true
    }

    fun setLeave(tripId: String, typed: String): TripLeaveSaved {
        val trip = items().firstOrNull { it.id == tripId } ?: return TripLeaveSaved(TripListRules.GONE, refused = true)
        if (typed.isBlank()) {
            if (trip.leaveMinute != null) replica.commitLocal(EntityTypes.TRIP, tripId, mapOf(TripFields.LEAVE_MINUTE to FieldValue.Null))
            return TripLeaveSaved(TripListRules.LEAVE_CLEARED, refused = false)
        }
        val minute = TripListRules.readLeave(typed) ?: return TripLeaveSaved(TripListRules.LEAVE_NOT_READ, refused = true)
        if (trip.leaveMinute != minute) replica.commitLocal(EntityTypes.TRIP, tripId, mapOf(TripFields.LEAVE_MINUTE to minute.toLong().fv()))
        return TripLeaveSaved(TripListRules.leaveSavedLine(trip.startDay, minute), refused = false)
    }

    /** Removes a trip and its bookings, saying so with Undo's token; null when it isn't there. */
    fun removeForUndo(id: String): TripRemoved? {
        val trip = items().firstOrNull { it.id == id } ?: return null
        trip.bookings.forEach { replica.commitLocal(EntityTypes.TRIP_BOOKING, it.id, mapOf(TripBookingFields.DELETED to true.fv())) }
        replica.commitLocal(EntityTypes.TRIP, id, mapOf(TripFields.DELETED to true.fv()))
        return TripRemoved(TripRules.removedLine(trip), TripRules.undoToken(id, trip.bookings.map { it.id }))
    }

    /** Removes one booking, saying so with Undo's token; null when it isn't there. */
    fun removeBookingForUndo(id: String): TripRemoved? {
        replica.entity(EntityTypes.TRIP_BOOKING, id)?.takeIf { it.fields[TripBookingFields.DELETED]?.boolOrNull != true } ?: return null
        val trips = items()
        val trip = trips.firstOrNull { t -> t.bookings.any { it.id == id } }
        val booking = trip?.bookings?.firstOrNull { it.id == id }
        replica.commitLocal(EntityTypes.TRIP_BOOKING, id, mapOf(TripBookingFields.DELETED to true.fv()))
        val line = booking?.let { TripRules.removedLine(it, trip.name) } ?: "Removed the booking"
        return TripRemoved(line, TripRules.undoToken(null, listOf(id)))
    }

    /**
     * Undo: puts back exactly what [token] names (the trip and the bookings removed with it, or one booking); false
     * when nothing in it was removed.
     */
    fun undoRemove(token: String): Boolean {
        val (tripId, bookingIds) = TripRules.readToken(token) ?: return false
        var undone = false
        if (tripId != null && replica.entity(EntityTypes.TRIP, tripId)?.fields?.get(TripFields.DELETED)?.boolOrNull == true) {
            replica.commitLocal(EntityTypes.TRIP, tripId, mapOf(TripFields.DELETED to false.fv()))
            undone = true
        }
        bookingIds.forEach { b ->
            if (replica.entity(EntityTypes.TRIP_BOOKING, b)?.fields?.get(TripBookingFields.DELETED)?.boolOrNull == true) {
                replica.commitLocal(EntityTypes.TRIP_BOOKING, b, mapOf(TripBookingFields.DELETED to false.fv()))
                undone = true
            }
        }
        return undone
    }
}
