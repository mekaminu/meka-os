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
    /** Slice 3 (server): when the business answered and Obi started talking. */
    const val ANSWERED_AT = "answeredAt"
    /** Slice 3 (server): the last few lines said, while Obi is on the call ([BookingWords.encode]); cleared after. */
    const val WORDS = "words"
    /** Slice 3 (server): the whole call in words once it's over, kept [BookingLiveRules.KEEP_DAYS] days, then blanked. */
    const val TRANSCRIPT = "transcript"
    /** Slice 3 (server): how long Obi was on the line, in seconds. */
    const val SECONDS = "seconds"
    /** Slice 3 (server): the time agreed, minutes of the day, only when it falls in the card's window. */
    const val BOOKED_MIN = "bookedMin"
    /** Slice 3 (server): the name the booking is under, as Obi gave it. */
    const val BOOKED_NAME = "bookedName"
    /** Slice 3 (device): Hang up was tapped; the server ends the call within seconds. */
    const val HANG_UP = "hangUp"
    /** Slice 3 (device): the calendar edit Add to calendar made. */
    const val CALENDAR_EDIT = "calendarEdit"
    /** Slice 3b (device): Take over was tapped; Obi tells them he's putting them through and the server rings his mobile. */
    const val TAKE_OVER = "takeOver"
    /** Slice 3b (server): when he pressed 1 on his mobile and the business was put through to him. */
    const val TAKEN_AT = "takenAt"
    /** Slice 3b (server): how long he was on the call himself, in seconds. */
    const val YOUR_SECONDS = "yourSeconds"
    /** Slice 3c (device): Listen was tapped; the server rings his mobile so he can hear the call, muted. */
    const val LISTEN = "listen"
    /** Slice 3c (server): when he pressed 1 on his mobile and started listening in; -1 once his listening leg is over. */
    const val LISTENING_AT = "listeningAt"
    /** Try again (device): the new card made from this one; the old card keeps its result and loses Try again. */
    const val RETRIED_AS = "retriedAs"
    /** The number looked up (server, [BookingNumberRules]): found · none · failed, for which name, the number and its site. */
    const val LOOKUP = "lookup"
    const val LOOKED_UP_FOR = "lookedUpFor"
    const val FOUND_NUMBER = "foundNumber"
    const val FOUND_FROM = "foundFrom"
    /** The business rang back (server, [BookingCallbackRules]): when, and the call assistant message it left. */
    const val CALLED_BACK_AT = "calledBackAt"
    const val CALLED_BACK_HELD = "calledBackHeld"
}

enum class BookingCallState(val code: String) {
    DRAFT("draft"), APPROVED("approved"), CALLING("calling"), BOOKED("booked"), NOT_BOOKED("not_booked"), FAILED("failed"),
    /** Slice 3b: he took the call from Obi (his mobile rang; he finishes it himself). */
    TAKEN("taken");

    val finished: Boolean get() = this == BOOKED || this == NOT_BOOKED || this == FAILED || this == TAKEN

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
    /** Slice 3: when the call was over, when Obi started talking, how long he was on, what was said and agreed. */
    val endedAtMs: Long? = null,
    val answeredAtMs: Long? = null,
    val seconds: Long? = null,
    val words: String? = null,
    val transcript: String? = null,
    val bookedMin: Int? = null,
    val bookedName: String? = null,
    val hangUp: Boolean = false,
    val calendarEditId: String? = null,
    /** Slice 3b: Take over tapped; when he took it on his mobile; how long he talked himself. */
    val takeOver: Boolean = false,
    val takenAtMs: Long? = null,
    val yourSeconds: Long? = null,
    /** Slice 3c: Listen tapped; when his mobile started listening in. */
    val listen: Boolean = false,
    val listeningAtMs: Long? = null,
    /** Try again: the id of the new card made from this one, or null. */
    val retriedAs: String? = null,
    /** The number looked up (server): found · none · failed, the name it was looked up for, the number and its site. */
    val lookup: String? = null,
    val lookedUpFor: String? = null,
    val foundNumber: String? = null,
    val foundFrom: String? = null,
    /** The business rang back on the assistant's number (server): when. */
    val calledBackAtMs: Long? = null,
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
    /** Slice 3: Obi is on the call now: the live view (the clock from [liveSinceMs], the last lines, Hang up). */
    val live: Boolean = false,
    /** When the clock starts (answered, else ringing); -1 when not on the call. */
    val liveSinceMs: Long = -1,
    val liveWords: List<BookingWordLine> = emptyList(),
    val canHangUp: Boolean = false,
    /** Slice 3b: Take over (his mobile is in About you, nothing tapped yet). */
    val canTakeOver: Boolean = false,
    /** Slice 3c: Listen (his mobile is in About you, nothing tapped yet). */
    val canListen: Boolean = false,
    /** Slice 3c: his mobile is listening in now. */
    val listening: Boolean = false,
    /** The brief (WHEN · WINDOW · PEOPLE, may say, won't, the foot) shows until the call starts. */
    val showBrief: Boolean = true,
    /** Slice 3: the result card's tiles (WHEN · PEOPLE · NAME) on a booked card. */
    val resultTiles: List<BookingTile> = emptyList(),
    /** "Rosa's Kitchen · 2 min 40 s" once a call Obi talked on is over. */
    val lengthLine: String? = null,
    /** "In your calendar · Sat 14 Nov 19:30" on a booked card with a time. */
    val calendarLine: String? = null,
    val canAddToCalendar: Boolean = false,
    val addedToCalendar: Boolean = false,
    /** Read the call: the whole call in words, kept [BookingLiveRules.KEEP_DAYS] days. */
    val transcript: List<BookingWordLine> = emptyList(),
    /** Try again on a call that wasn't booked or didn't go through, while its day hasn't gone: a new card to approve. */
    val canTryAgain: Boolean = false,
    /** "Tried again · the new card is above" once Try again made a new card. */
    val triedAgainLine: String? = null,
    /** The number looked up: Use this number puts Obi's found number on the card (he still taps Call now after). */
    val canUseFound: Boolean = false,
    /** "They rang back · Sun 8 Nov, 14:05 · their message is in While you were at work" ([BookingCallbackRules.cardLine]). */
    val calledBackLine: String? = null,
) {
    val canRead: Boolean get() = transcript.isNotEmpty()
}

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
    /** A draft whose day went by before he tapped Call now: nothing more he can do but cancel it or ask again. */
    const val NOT_RUNG = "Call card · not rung"
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
    const val TRY_AGAIN = "Try again"
    const val TRIED_AGAIN = "New call card ready · check it, then Call now"
    const val TRIED_AGAIN_LINE = "Tried again · the new card is above"
    const val NOT_RETRYABLE = "Only a call that wasn't booked can be tried again"

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
            endedAtMs = num(BookingCallFields.ENDED_AT), answeredAtMs = num(BookingCallFields.ANSWERED_AT),
            seconds = num(BookingCallFields.SECONDS), words = text(BookingCallFields.WORDS), transcript = text(BookingCallFields.TRANSCRIPT),
            bookedMin = num(BookingCallFields.BOOKED_MIN)?.toInt()?.takeIf { it in 0 until 24 * 60 },
            bookedName = text(BookingCallFields.BOOKED_NAME),
            hangUp = (f[BookingCallFields.HANG_UP] as? FieldValue.Bool)?.value == true,
            calendarEditId = text(BookingCallFields.CALENDAR_EDIT),
            takeOver = (f[BookingCallFields.TAKE_OVER] as? FieldValue.Bool)?.value == true,
            takenAtMs = num(BookingCallFields.TAKEN_AT), yourSeconds = num(BookingCallFields.YOUR_SECONDS),
            listen = (f[BookingCallFields.LISTEN] as? FieldValue.Bool)?.value == true,
            listeningAtMs = num(BookingCallFields.LISTENING_AT),
            retriedAs = text(BookingCallFields.RETRIED_AS),
            lookup = text(BookingCallFields.LOOKUP), lookedUpFor = text(BookingCallFields.LOOKED_UP_FOR),
            foundNumber = text(BookingCallFields.FOUND_NUMBER), foundFrom = text(BookingCallFields.FOUND_FROM),
            calledBackAtMs = num(BookingCallFields.CALLED_BACK_AT),
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
        if (c.number == null && BookingNumberRules.usable(c, personal) != null) return BookingNumberRules.PICK
        return (numberCheck(c.number, personal) as? NumberCheck.Refused)?.line
    }

    /**
     * Try again: a call that wasn't booked (they were full, no answer, busy, the line failed) can become a new card
     * for the same brief, while its day hasn't gone and it hasn't been tried again already. Booked and taken calls
     * never; a cancelled one has nothing to retry. The new card waits for his Call now like any other.
     */
    fun canTryAgain(c: BookingCall, nowMs: Long, cal: LocalCalendar): Boolean {
        if (c.cancelled || c.retriedAs != null) return false
        if (c.state != BookingCallState.NOT_BOOKED && c.state != BookingCallState.FAILED) return false
        val today = cal.epochDayOf(nowMs)
        if (c.day < today) return false
        return !(c.day == today && c.toMin != null && c.toMin <= cal.minuteOfDay(nowMs))
    }

    /** The fields Try again's new card writes: the old brief (who, number, what, day, window, people, note), as a draft. */
    fun retryFields(c: BookingCall, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(BookingCallFields.BUSINESS, c.business.fv())
        c.number?.let { put(BookingCallFields.NUMBER, it.fv()) }
        put(BookingCallFields.WHAT, c.what.fv())
        put(BookingCallFields.DAY, c.day.fv())
        c.fromMin?.let { put(BookingCallFields.FROM, it.toLong().fv()) }
        c.toMin?.let { put(BookingCallFields.TO, it.toLong().fv()) }
        c.people?.let { put(BookingCallFields.PEOPLE, it.toLong().fv()) }
        c.note?.let { put(BookingCallFields.NOTE, it.fv()) }
        put(BookingCallFields.STATE, BookingCallState.DRAFT.code.fv())
        put(BookingCallFields.ASKED_AT, nowMs.fv())
    }

    /** The line for a card waiting to ring outside calling hours: "Waiting to ring · Obi rings at 09:00". */
    fun waitingLine(nowMs: Long, cal: LocalCalendar): String {
        val m = cal.minuteOfDay(nowMs)
        return if (m in CALL_FROM_MIN until CALL_TO_MIN) WAITING else "Waiting to ring · Obi rings at 09:00, when calls start"
    }

    /** [mobile]: his own mobile from About you, for Take over (slice 3b); none hides it. */
    fun rows(
        calls: List<BookingCall>, personal: Collection<String?>, events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar,
        mobile: String? = null,
    ): List<BookingCallRow> {
        val today = cal.epochDayOf(nowMs)
        return calls.filter { !it.cancelled }
            .sortedWith(compareBy<BookingCall> { it.state.finished }.thenByDescending { it.askedAtMs })
            .map { c ->
                val check = numberCheck(c.number, personal)
                val ok = check is NumberCheck.Ok
                val draft = c.state == BookingCallState.DRAFT
                val block = if (draft) blocked(c, personal, nowMs, cal) else null
                // Week 3's walk-through (A5): a draft waits on him whatever it still lacks (Call now, a number to add, or
                // Use this number for the one Obi found), so it counts in "needs you" exactly when it says it needs his
                // tap; only a draft whose day has gone doesn't.
                val waits = draft && block != DAY_GONE
                val status = when (c.state) {
                    BookingCallState.DRAFT -> if (waits) NEEDS_TAP else NOT_RUNG
                    BookingCallState.APPROVED -> waitingLine(nowMs, cal)
                    BookingCallState.CALLING -> when {
                        c.hangUp -> BookingLiveRules.HANGING_UP
                        c.takeOver -> BookingLiveRules.TAKING_OVER
                        c.listen && (c.listeningAtMs ?: 0) > 0 -> BookingLiveRules.LISTENING
                        c.listen && c.listeningAtMs == null -> BookingLiveRules.RINGING_TO_LISTEN
                        else -> CALLING
                    }
                    BookingCallState.TAKEN -> BookingLiveRules.TAKEN
                    BookingCallState.BOOKED -> BOOKED
                    BookingCallState.NOT_BOOKED -> NOT_BOOKED
                    BookingCallState.FAILED -> FAILED
                }
                val numberLine = when {
                    c.number == null -> if (draft) BookingNumberRules.line(c, personal, nowMs) else NO_NUMBER
                    check is NumberCheck.Ok && check.e164 == BookingNumberRules.usable(c, personal) -> "${pretty(check.e164)} · Obi found it"
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
                    needsYou = waits, whenTile = whenTile, windowTile = window,
                    peopleTile = c.people?.toString() ?: "—", what = c.what, note = c.note,
                    maySay = maySay(c.people), wont = WONT_LIST, foot = foot, blocked = block, result = c.result,
                    canCall = draft && block == null, canEdit = draft, canChangePeople = draft && c.people != null,
                    canCancel = draft || c.state == BookingCallState.APPROVED,
                    spoken = listOfNotNull("Call card for ${c.business}", c.what, "$whenTile, $window", c.people?.let { "$it people" }, status, block, c.result).joinToString(". "),
                ).let { BookingLiveRules.withLive(it, c, nowMs, cal, PutThroughRules.dialable(mobile) != null) }
                    .copy(canTryAgain = canTryAgain(c, nowMs, cal), triedAgainLine = if (c.retriedAs != null) TRIED_AGAIN_LINE else null,
                        canUseFound = draft && c.number == null && BookingNumberRules.usable(c, personal) != null,
                        calledBackLine = BookingCallbackRules.cardLine(c.calledBackAtMs, cal))
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

    /** Slice 3: Hang up while Obi is on the call; the server ends it within seconds. Returns the line to show. */
    fun hangUp(id: String): String {
        val c = one(id) ?: return BookingLiveRules.NOT_ON_CALL
        if (c.cancelled || c.state != BookingCallState.CALLING) return BookingLiveRules.NOT_ON_CALL
        if (!c.hangUp) replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.HANG_UP to true.fv()))
        return BookingLiveRules.HUNG_UP_LINE
    }

    /**
     * Slice 3b: Take over while Obi is on the call: Obi tells them he's putting them through, then the server rings
     * [mobile] (About you) and he presses 1 to take it. Returns the line to show.
     */
    fun takeOver(id: String, mobile: String?): String {
        val c = one(id) ?: return BookingLiveRules.NOT_ON_CALL
        if (c.cancelled || c.state != BookingCallState.CALLING || c.hangUp) return BookingLiveRules.NOT_ON_CALL
        if (PutThroughRules.dialable(mobile) == null) return BookingLiveRules.NO_MOBILE
        if (!c.takeOver) replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.TAKE_OVER to true.fv()))
        return BookingLiveRules.TAKE_OVER_LINE
    }

    /**
     * Slice 3c: Listen while Obi is on the call: the server rings [mobile] (About you) from the assistant's number;
     * press 1 and he hears both sides, muted (the business never hears him). Returns the line to show.
     */
    fun listen(id: String, mobile: String?): String {
        val c = one(id) ?: return BookingLiveRules.NOT_ON_CALL
        if (c.cancelled || c.state != BookingCallState.CALLING || c.hangUp || c.takeOver) return BookingLiveRules.NOT_ON_CALL
        if (PutThroughRules.dialable(mobile) == null) return BookingLiveRules.NO_MOBILE_LISTEN
        if (!c.listen) replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.LISTEN to true.fv()))
        return BookingLiveRules.LISTEN_LINE
    }

    /** Slice 3: the event a booked card's Add to calendar would make, or null (not booked, no time, added already). */
    fun calendarDraft(id: String, cal: LocalCalendar): EventDraft? {
        val c = one(id) ?: return null
        if (c.calendarEditId != null) return null
        return BookingLiveRules.calendarDraft(c, cal)
    }

    /** Slice 3: Add to calendar made edit [editId] for card [id]; the card says Added on both apps. */
    fun markAdded(id: String, editId: String) {
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.CALENDAR_EDIT to editId.fv()))
    }

    /**
     * Try again on a call that wasn't booked: a new draft card with the same brief (it waits for his Call now; nothing
     * rings by itself), and the old card remembers it so Try again goes. Returns the line to show.
     */
    fun tryAgain(id: String, cal: LocalCalendar): String {
        val c = one(id) ?: return BookingCallRules.GONE
        if (!BookingCallRules.canTryAgain(c, nowMs(), cal)) {
            return if (c.state == BookingCallState.NOT_BOOKED || c.state == BookingCallState.FAILED) {
                if (c.retriedAs != null) BookingCallRules.TRIED_AGAIN_LINE else BookingCallRules.DAY_GONE
            } else BookingCallRules.NOT_RETRYABLE
        }
        if (all().count { it.open } >= BookingCallRules.MAX_OPEN) return BookingCallRules.TOO_MANY
        val newId = newId()
        replica.commitLocal(EntityTypes.BOOKING_CALL, newId, BookingCallRules.retryFields(c, nowMs()))
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.RETRIED_AS to newId.fv()))
        return BookingCallRules.TRIED_AGAIN
    }

    /**
     * Use this number: the number Obi looked up becomes the card's own (as it will be dialled), only while the card
     * waits for him and has none; nothing rings until his Call now. Returns the line to show.
     */
    fun useFound(id: String, personal: Collection<String?>): String {
        val c = one(id) ?: return BookingCallRules.GONE
        if (c.cancelled || c.state != BookingCallState.DRAFT) return BookingCallRules.GONE
        val e164 = if (c.number == null) BookingNumberRules.usable(c, personal) else null
        if (e164 == null) return BookingNumberRules.NOTHING_TO_USE
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.NUMBER to e164.fv()))
        return BookingNumberRules.USED
    }

    /** Cancel (or the Ask card's Undo): only before Obi is on the call. */
    fun cancel(id: String): Boolean {
        val c = one(id) ?: return false
        if (c.cancelled || (c.state != BookingCallState.DRAFT && c.state != BookingCallState.APPROVED)) return false
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.CANCELLED to true.fv()))
        return true
    }
}
