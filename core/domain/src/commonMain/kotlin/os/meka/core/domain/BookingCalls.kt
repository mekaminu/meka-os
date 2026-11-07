package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Obi on calls, part B slice 2a (Meka approved 2026-11-07: the costs with a £20 monthly cap, businesses' voices to
 * Google's Live API, the rules as written, and the mockups `docs/design/calls/booking-call.dc.html`): **the call card**.
 * He asks Obi to book something ("book a table for 4 at Rosa's Kitchen on Saturday between 7 and 8"); Ask (typed or on
 * the orb) offers a card, and his tap keeps a call card under Obi is working on → CALLS FOR YOU: the business, its
 * number, WHEN · WINDOW · PEOPLE, what Obi may say and what he won't, whether his calendar is free then, and the
 * call's likely length and cost. Nothing rings until he taps **Call now**, which needs a number that passes
 * [numberCheck] (a UK business number: never an emergency, premium-rate or personal one, never his own or his family's).
 * The server dials an approved card (slice 2b); the result comes back on the same card.
 *
 * Hard rules (the build plan's): businesses only, never personal contacts; never card details, deposits or fees; at
 * most [MAX_MINUTES] minutes; calls made between [CALL_FROM_MIN] and [CALL_TO_MIN] local time only.
 */
object BookingCallFields {
    const val BUSINESS = "business"
    const val NUMBER = "number"
    const val WHAT = "what"
    const val DAY = "day"
    const val FROM = "fromMin"
    const val TO = "toMin"
    const val PEOPLE = "people"
    const val NOTE = "note"
    const val STATE = "state"
    const val ASKED_AT = "askedAt"
    const val APPROVED_AT = "approvedAt"
    /** Written by the server (slice 2b): what came of the call, in its own words, display only. */
    const val RESULT = "result"
    /** Written by the server (slice 2b) when it starts ringing; a card on the call long after is closed as failed. */
    const val CALLED_AT = "calledAt"
    /** Written by the server (slice 2b) when the call is over. */
    const val ENDED_AT = "endedAt"
    /** Set on Cancel or the Ask card's Undo; a cancelled card never rings. */
    const val CANCELLED = "cancelled"
}

enum class BookingCallState(val code: String) {
    DRAFT("draft"), APPROVED("approved"), CALLING("calling"), BOOKED("booked"), NOT_BOOKED("not_booked"), FAILED("failed");

    val finished: Boolean get() = this == BOOKED || this == NOT_BOOKED || this == FAILED

    companion object {
        fun of(code: String?): BookingCallState = entries.firstOrNull { it.code == code } ?: DRAFT
    }
}

/** One call card as kept. Days are local epoch days, times minutes of the day. */
data class BookingCall(
    val id: String,
    val business: String,
    val number: String?,
    val what: String,
    val day: Long,
    val fromMin: Int?,
    val toMin: Int?,
    val people: Int?,
    val note: String?,
    val state: BookingCallState,
    val askedAtMs: Long,
    val approvedAtMs: Long?,
    val result: String?,
    val cancelled: Boolean,
    /** When the server started ringing (slice 2b), or null. */
    val calledAtMs: Long? = null,
) {
    val open: Boolean get() = !cancelled && !state.finished
}

/** Why a number can't be rung, or the number as it will be dialled (+44…). */
sealed interface NumberCheck {
    data class Ok(val e164: String) : NumberCheck
    data class Refused(val line: String) : NumberCheck
}

/** A call card as both apps draw it (the words are all here, the same on the Fold and the Mac). */
data class BookingCallRow(
    val id: String,
    val business: String,
    /** "01234 567890 · you added it", or [NO_NUMBER]. */
    val numberLine: String,
    val numberOk: Boolean,
    /** "Needs your tap", "Waiting to ring · …", "Booked", … */
    val status: String,
    val needsYou: Boolean,
    val whenTile: String,
    val windowTile: String,
    val peopleTile: String,
    val what: String,
    val note: String?,
    val maySay: List<String>,
    val wont: List<String>,
    /** "Your Saturday is free then · about 3 min · about 20p" (null once the call is over). */
    val foot: String?,
    /** Why Call now is greyed out (no number, a day gone), or null when it can be tapped. */
    val blocked: String?,
    val result: String?,
    val canCall: Boolean,
    val canEdit: Boolean,
    val canChangePeople: Boolean,
    val canCancel: Boolean,
    val spoken: String,
)

object BookingCallRules {
    const val TITLE = "Calls for you"
    const val INTRO = "Ask Obi to book a table or an appointment and he makes a call card here. Nothing rings until you tap Call now; " +
        "he rings businesses only, between 09:00 and 20:00, says he's your AI assistant and never pays or gives card details."
    const val EMPTY = "No calls waiting · say \"book a table for 4 at … on Saturday at 7\" to Obi"
    const val CARD_BUTTON = "Make the card"
    const val CALL_NOW = "Call now"
    const val EDIT = "Edit"
    const val CANCEL = "Cancel"
    const val MAY_SAY = "Obi may say"
    const val WONT = "Obi won't"
    const val WHEN = "When"
    const val WINDOW = "Window"
    const val PEOPLE = "People"
    const val PARTY = "Party size"
    const val NO_NUMBER = "No number yet · tap Edit to add theirs"
    const val NEEDS_TAP = "Call card · needs your tap"
    const val WAITING = "Waiting to ring · Obi rings between 09:00 and 20:00; this card changes to On the call"
    const val CALLING = "Obi is on the call"
    const val BOOKED = "Booked"
    const val NOT_BOOKED = "Not booked"
    const val FAILED = "The call didn't go through"
    const val CANCELLED_LINE = "Call card cancelled · nothing rang"
    const val APPROVED_LINE = "Approved · Obi will ring them"
    const val GONE = "That call card has changed on another device"
    const val TOO_MANY = "Three call cards are open already · cancel one first"
    const val DAY_GONE = "That day has gone · ask Obi again for a new day"
    const val EDIT_TITLE = "Edit the call card"
    const val EDIT_BUSINESS = "Who to ring"
    const val EDIT_NUMBER = "Their number"
    const val EDIT_NOTE = "Anything Obi should ask for"
    const val EDIT_NOTE_HINT = "A window seat, a high chair, a quiet table…"
    const val SAVE = "Save"
    const val SAVED = "Call card saved"

    const val MAX_OPEN = 3
    const val MAX_BUSINESS = 60
    const val MAX_WHAT = 80
    const val MAX_NOTE = 160
    const val MAX_PEOPLE = 12
    /** A booking call ends at this length (the live calls' longest). */
    const val MAX_MINUTES = LiveCallRules.MAX_SECONDS / 60
    /** Calls are made between these local times. */
    const val CALL_FROM_MIN = 9 * 60
    const val CALL_TO_MIN = 20 * 60
    /** The card's estimate: a booking takes about this long, Obi speaking about this share of it. */
    const val TYPICAL_SECONDS = 180L
    const val TYPICAL_OBI_SHARE_PCT = 40L
    /** Twilio UK outbound per minute (micro-dollars, the build plan's figures) plus the audio stream's. */
    const val LANDLINE_MICRO_USD_PER_MIN = 16_000L
    const val MOBILE_MICRO_USD_PER_MIN = 31_000L
    const val STREAM_MICRO_USD_PER_MIN = 4_000L
    /** The window when he gives a time but no end: an hour. */
    const val DEFAULT_WINDOW_MIN = 60

    /** What Obi may say on every booking call (his first name only when About you has one). */
    fun maySay(people: Int?): List<String> = listOf(
        "Your first name",
        if (people != null) "Party size and time" else "The day and time",
        "The assistant number, for confirmation",
    )

    /** What Obi never does on a booking call, whatever they ask. */
    val WONT_LIST = listOf("Card or bank details", "Deposits or fees", "Your address or mobile")

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    private fun clean(s: String?, max: Int): String? = s?.replace(Regex("\\s+"), " ")?.trim()?.trim('.', ',', ';')?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The Ask card from the model's `book_call` ("Table for 4 at Rosa's Kitchen", a date, the window's start as
     * time and its length as minutes): the business after " at ", the party from "for 4" (or "for four people"), the
     * rest as what it is. Null (no card) without a business or a day, for a day gone, or a window outside the day.
     */
    fun proposal(title: String?, day: Long?, minute: Int?, minutes: Int?, today: Long, nowMinute: Int): AskProposal.BookCall? {
        val t = clean(title, MAX_BUSINESS + MAX_WHAT + 8) ?: return null
        val d = day ?: return null
        if (d < today) return null
        val at = Regex("""\s+(?:at|with)\s+(.+)$""", RegexOption.IGNORE_CASE).find(t) ?: return null
        val business = clean(at.groupValues[1], MAX_BUSINESS) ?: return null
        val head = t.substring(0, at.range.first)
        val people = Regex("""\bfor\s+(\d{1,2}|[a-z]+)(?:\s+(?:people|of us|adults|guests))?\b""", RegexOption.IGNORE_CASE).find(head)
            ?.groupValues?.get(1)?.lowercase()?.let { it.toIntOrNull() ?: NUMBER_WORDS[it] }?.takeIf { it in 1..MAX_PEOPLE }
        val what = clean(head.replace(Regex("""^(?:book|ring|call)\s+(?:me\s+)?(?:a|an)?\s*""", RegexOption.IGNORE_CASE), "").ifBlank { "A booking" }, MAX_WHAT)
            ?.replaceFirstChar { it.uppercase() } ?: "A booking"
        val from = minute?.takeIf { it in 0 until 24 * 60 }
        val to = from?.let { (it + (minutes?.takeIf { m -> m in 15..12 * 60 } ?: DEFAULT_WINDOW_MIN)).coerceAtMost(24 * 60 - 1) }
        if (d == today && to != null && to <= nowMinute) return null
        return AskProposal.BookCall(business, what, people, d, from, to)
    }

    fun windowLabel(from: Int?, to: Int?): String =
        if (from == null) "Any time" else LocalClock.formatMinute(from) + (to?.let { "–" + LocalClock.formatMinute(it) } ?: "")

    fun cardLine(p: AskProposal.BookCall): String =
        listOfNotNull("Call card · ${p.business}", p.what, CivilDate.shortLabel(p.day) + if (p.fromMin != null) " " + windowLabel(p.fromMin, p.toMin) else "")
            .joinToString(" · ")

    fun doneLine(p: AskProposal.BookCall): String = "Call card ready in Obi is working on · ${p.business} · add their number, then Call now"

    /** The fields a new card writes. */
    fun draftFields(p: AskProposal.BookCall, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(BookingCallFields.BUSINESS, p.business.fv())
        put(BookingCallFields.WHAT, p.what.fv())
        put(BookingCallFields.DAY, p.day.fv())
        p.fromMin?.let { put(BookingCallFields.FROM, it.toLong().fv()) }
        p.toMin?.let { put(BookingCallFields.TO, it.toLong().fv()) }
        p.people?.let { put(BookingCallFields.PEOPLE, it.toLong().fv()) }
        put(BookingCallFields.STATE, BookingCallState.DRAFT.code.fv())
        put(BookingCallFields.ASKED_AT, nowMs.fv())
    }

    fun read(id: String, f: Map<String, FieldValue>): BookingCall? {
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        fun num(k: String) = (f[k] as? FieldValue.Int64)?.value
        val business = text(BookingCallFields.BUSINESS) ?: return null
        val day = num(BookingCallFields.DAY) ?: return null
        return BookingCall(
            id = id, business = business, number = text(BookingCallFields.NUMBER), what = text(BookingCallFields.WHAT) ?: "A booking",
            day = day, fromMin = num(BookingCallFields.FROM)?.toInt(), toMin = num(BookingCallFields.TO)?.toInt(),
            people = num(BookingCallFields.PEOPLE)?.toInt()?.takeIf { it in 1..MAX_PEOPLE }, note = text(BookingCallFields.NOTE),
            state = BookingCallState.of(text(BookingCallFields.STATE)), askedAtMs = num(BookingCallFields.ASKED_AT) ?: 0,
            approvedAtMs = num(BookingCallFields.APPROVED_AT), result = text(BookingCallFields.RESULT),
            cancelled = (f[BookingCallFields.CANCELLED] as? FieldValue.Bool)?.value == true,
            calledAtMs = num(BookingCallFields.CALLED_AT),
        )
    }

    /** Short and service codes (999, 112, 111, 101, 105, 116 xxx, 118 xxx) are never rung. */
    private val SHORT_CODES = listOf("999", "112", "111", "101", "105", "116", "118")

    /**
     * Whether [raw] may be rung for a booking: a UK number of 10 or 11 digits (+44, 0044 or 0 first); never an
     * emergency or service code, premium rate (09…, 0871–0873, 118…), personal numbering (070) or a pager (076);
     * never one of [personal] (his own mobile, the assistant's number, his partner's, his voicemail: anyone in his life).
     */
    fun numberCheck(raw: String?, personal: Collection<String?>): NumberCheck {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return NumberCheck.Refused(NO_NUMBER)
        if (t.any { it.isLetter() } || !t.all { it.isDigit() || it in "+ ()-." }) return NumberCheck.Refused("That doesn't look like a phone number")
        val digits = t.filter { it.isDigit() }
        if (digits in SHORT_CODES || digits.length <= 6 && SHORT_CODES.any { digits.startsWith(it) }) return NumberCheck.Refused("Obi never rings emergency or service numbers")
        val national = when {
            t.startsWith("+") && digits.startsWith("44") -> "0" + digits.drop(2)
            t.startsWith("+") -> return NumberCheck.Refused("UK numbers only for now")
            digits.startsWith("0044") -> "0" + digits.drop(4)
            digits.startsWith("00") -> return NumberCheck.Refused("UK numbers only for now")
            digits.startsWith("0") -> digits
            else -> return NumberCheck.Refused("Add the full number with its area code")
        }
        if (national.length !in 10..11) return NumberCheck.Refused("Add the full number with its area code")
        if (national.startsWith("09") || national.startsWith("0871") || national.startsWith("0872") || national.startsWith("0873") || national.startsWith("0118"))
            return NumberCheck.Refused("Obi never rings premium-rate numbers")
        if (national.startsWith("070") || national.startsWith("076")) return NumberCheck.Refused("Obi rings businesses only")
        if (!(national.startsWith("01") || national.startsWith("02") || national.startsWith("03") || national.startsWith("07") || national.startsWith("08")))
            return NumberCheck.Refused("Obi rings businesses only")
        val e164 = "+44" + national.drop(1)
        if (personal.any { ContactNumbers.e164(it) == e164 }) return NumberCheck.Refused("That's a personal number · Obi rings businesses only")
        return NumberCheck.Ok(e164)
    }

    /** "01234 567890" for a +44 number (landlines 5 + 6, mobiles 5 + 6, 02x as 3 + 4 + 4). */
    fun pretty(e164: String): String {
        val n = if (e164.startsWith("+44")) "0" + e164.drop(3) else e164
        return when {
            n.startsWith("02") && n.length == 11 -> "${n.take(3)} ${n.substring(3, 7)} ${n.drop(7)}"
            n.length == 11 -> "${n.take(5)} ${n.drop(5)}"
            else -> n
        }
    }

    /** The likely cost of a typical call, in pence: the phone service by the minute plus Gemini Live. */
    fun estimatePence(e164: String?, gbpPerUsdMicros: Long = LiveModeRules.FALLBACK_GBP_PER_USD_MICROS): Long {
        val perMin = (if (e164?.startsWith("+447") == true) MOBILE_MICRO_USD_PER_MIN else LANDLINE_MICRO_USD_PER_MIN) + STREAM_MICRO_USD_PER_MIN
        val minutes = (TYPICAL_SECONDS + 59) / 60
        val micro = minutes * perMin + LiveModeRules.costMicroUsd(TYPICAL_SECONDS, TYPICAL_SECONDS * TYPICAL_OBI_SHARE_PCT / 100)
        return LiveModeRules.pence(micro, gbpPerUsdMicros)
    }

    /**
     * Whether his calendar is free in the card's window on its day: "Your Saturday is free then", "Clashes with
     * “Football” at 19:30", or for a card with no time, the day's first and last timed events' span.
     */
    fun calendarLine(c: BookingCall, events: List<CalendarEvent>, today: Long, cal: LocalCalendar): String {
        val dayName = when (c.day - today) { 0L -> "today"; 1L -> "tomorrow"; else -> CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(c.day) - 1] }
        val timed = events.filter { !it.allDay && cal.epochDayOf(it.startAtMs) <= c.day && cal.epochDayOf(it.endAtMs - 1) >= c.day }
        if (c.fromMin == null) {
            return if (timed.isEmpty()) "Nothing else in your calendar ${if (dayName == "today" || dayName == "tomorrow") dayName else "on $dayName"}"
            else "${timed.size} ${if (timed.size == 1) "thing" else "things"} in your calendar ${if (dayName == "today" || dayName == "tomorrow") dayName else "on $dayName"}"
        }
        val from = cal.toEpochMs(c.day, c.fromMin)
        val to = cal.toEpochMs(c.day, c.toMin ?: (c.fromMin + DEFAULT_WINDOW_MIN))
        val clash = timed.filter { it.startAtMs < to && it.endAtMs > from }.minByOrNull { it.startAtMs }
        val whose = if (dayName == "today" || dayName == "tomorrow") dayName.replaceFirstChar { it.uppercase() } else "Your $dayName"
        return if (clash == null) "$whose is free then"
        else "Clashes with “${clash.title.trim().take(40)}” at ${LocalClock.formatMinute(cal.minuteOfDay(clash.startAtMs))}"
    }

    /** Why Call now can't be tapped yet, or null when it can. */
    fun blocked(c: BookingCall, personal: Collection<String?>, nowMs: Long, cal: LocalCalendar): String? {
        val today = cal.epochDayOf(nowMs)
        if (c.day < today) return DAY_GONE
        if (c.day == today && c.toMin != null && c.toMin <= cal.minuteOfDay(nowMs)) return DAY_GONE
        return (numberCheck(c.number, personal) as? NumberCheck.Refused)?.line
    }

    /** The line for a card waiting to ring outside calling hours: "Waiting to ring · Obi rings at 09:00". */
    fun waitingLine(nowMs: Long, cal: LocalCalendar): String {
        val m = cal.minuteOfDay(nowMs)
        return if (m in CALL_FROM_MIN until CALL_TO_MIN) WAITING else "Waiting to ring · Obi rings at 09:00, when calls start"
    }

    fun rows(calls: List<BookingCall>, personal: Collection<String?>, events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): List<BookingCallRow> {
        val today = cal.epochDayOf(nowMs)
        return calls.filter { !it.cancelled }
            .sortedWith(compareBy<BookingCall> { it.state.finished }.thenByDescending { it.askedAtMs })
            .map { c ->
                val check = numberCheck(c.number, personal)
                val ok = check is NumberCheck.Ok
                val draft = c.state == BookingCallState.DRAFT
                val block = if (draft) blocked(c, personal, nowMs, cal) else null
                val status = when (c.state) {
                    BookingCallState.DRAFT -> NEEDS_TAP
                    BookingCallState.APPROVED -> waitingLine(nowMs, cal)
                    BookingCallState.CALLING -> CALLING
                    BookingCallState.BOOKED -> BOOKED
                    BookingCallState.NOT_BOOKED -> NOT_BOOKED
                    BookingCallState.FAILED -> FAILED
                }
                val numberLine = when {
                    c.number == null -> NO_NUMBER
                    check is NumberCheck.Ok -> "${pretty(check.e164)} · you added it"
                    else -> "${c.number} · ${(check as NumberCheck.Refused).line}"
                }
                val foot = if (c.state.finished) null else listOf(
                    calendarLine(c, events, today, cal),
                    "about ${TYPICAL_SECONDS / 60} min",
                    "about ${LiveModeRules.money(estimatePence((check as? NumberCheck.Ok)?.e164))}",
                ).joinToString(" · ")
                val whenTile = CivilDate.shortLabel(c.day)
                val window = windowLabel(c.fromMin, c.toMin)
                BookingCallRow(
                    id = c.id, business = c.business, numberLine = numberLine, numberOk = ok, status = status,
                    needsYou = draft && block == null, whenTile = whenTile, windowTile = window,
                    peopleTile = c.people?.toString() ?: "—", what = c.what, note = c.note,
                    maySay = maySay(c.people), wont = WONT_LIST, foot = foot, blocked = block, result = c.result,
                    canCall = draft && block == null, canEdit = draft, canChangePeople = draft && c.people != null,
                    canCancel = draft || c.state == BookingCallState.APPROVED,
                    spoken = listOfNotNull("Call card for ${c.business}", c.what, "$whenTile, $window", c.people?.let { "$it people" }, status, block).joinToString(". "),
                )
            }
    }

    /** Ask's lines for open call cards (what Obi sees): never the number. */
    fun askLines(calls: List<BookingCall>): List<String> = calls.filter { it.open }.map { c ->
        listOfNotNull("Call card", c.business, c.what, CivilDate.shortLabel(c.day) + " " + windowLabel(c.fromMin, c.toMin),
            c.people?.let { "$it people" }, if (c.state == BookingCallState.DRAFT) "waiting for his tap on Call now" else "waiting to ring").joinToString(" · ")
    }
}

/** The device side: the cards he makes, his edits, Call now and Cancel (the server dials and writes the result). */
class BookingCalls(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    fun all(): List<BookingCall> = replica.entities(EntityTypes.BOOKING_CALL).filter { !it.deleted }
        .mapNotNull { BookingCallRules.read(it.ref.entityId, it.fields) }

    private fun one(id: String): BookingCall? = all().firstOrNull { it.id == id }

    /** A new card from Ask's tap; null when [BookingCallRules.MAX_OPEN] are open already. */
    fun draft(p: AskProposal.BookCall): String? {
        if (all().count { it.open } >= BookingCallRules.MAX_OPEN) return null
        val id = newId()
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, BookingCallRules.draftFields(p, nowMs()))
        return id
    }

    /** Party size − / +, while the card waits for him; false otherwise. */
    fun setPeople(id: String, people: Int): Boolean {
        val c = one(id) ?: return false
        if (c.cancelled || c.state != BookingCallState.DRAFT || people !in 1..BookingCallRules.MAX_PEOPLE) return false
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.PEOPLE to people.toLong().fv()))
        return true
    }

    /** Edit: who, their number (kept as typed; checked on Call now) and a note for Obi. Blank business keeps the old one. */
    fun edit(id: String, business: String?, number: String?, note: String?): Boolean {
        val c = one(id) ?: return false
        if (c.cancelled || c.state != BookingCallState.DRAFT) return false
        val fields = buildMap {
            business?.replace(Regex("\\s+"), " ")?.trim()?.take(BookingCallRules.MAX_BUSINESS)?.takeIf { it.isNotEmpty() }?.let { put(BookingCallFields.BUSINESS, it.fv()) }
            number?.trim()?.take(24)?.let { put(BookingCallFields.NUMBER, it.fv()) }
            note?.replace(Regex("\\s+"), " ")?.trim()?.take(BookingCallRules.MAX_NOTE)?.let { put(BookingCallFields.NOTE, it.fv()) }
        }
        if (fields.isEmpty()) return false
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, fields)
        return true
    }

    /**
     * Call now: the card moves to approved for the server to dial, only while it still waits for him and passes
     * [BookingCallRules.blocked] now; the number is kept as it will be dialled. Returns the line to show.
     */
    fun approve(id: String, personal: Collection<String?>, cal: LocalCalendar): String {
        val c = one(id) ?: return BookingCallRules.GONE
        if (c.cancelled || c.state != BookingCallState.DRAFT) return BookingCallRules.GONE
        BookingCallRules.blocked(c, personal, nowMs(), cal)?.let { return it }
        val e164 = (BookingCallRules.numberCheck(c.number, personal) as NumberCheck.Ok).e164
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.NUMBER to e164.fv(),
            BookingCallFields.STATE to BookingCallState.APPROVED.code.fv(),
            BookingCallFields.APPROVED_AT to nowMs().fv(),
        ))
        return BookingCallRules.APPROVED_LINE
    }

    /** Cancel (or the Ask card's Undo): only before Obi is on the call. */
    fun cancel(id: String): Boolean {
        val c = one(id) ?: return false
        if (c.cancelled || (c.state != BookingCallState.DRAFT && c.state != BookingCallState.APPROVED)) return false
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.CANCELLED to true.fv()))
        return true
    }
}
