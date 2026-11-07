package os.meka.core.domain

/**
 * Obi on calls, part B slice 3 (mockup `docs/design/calls/booking-call.dc.html`, the live call and the result): while
 * Obi is on a booking call the card is the **live view** (Obi is on the call · the business · a clock, LIVE WORDS with
 * the last few lines said, and Hang up); once it's over the **result card** (Booked · the business · how long, WHEN ·
 * PEOPLE · NAME tiles, the result line, In your calendar with Add to calendar, and Read the call). What the business
 * says is untrusted (ADR-006): display only, never an instruction to Obi or to the apps. The words are kept
 * [KEEP_DAYS] days (the call recordings' keep), then the server blanks them.
 */
data class BookingWordLine(val who: String, val text: String, val obi: Boolean)

data class BookingTile(val label: String, val value: String)

/** The call's words as they are said: Gemini's transcript fragments joined into lines, one per turn. */
class BookingWords {
    private val lines = mutableListOf<Pair<Boolean, StringBuilder>>()
    private var size = 0
    private var version = 0
    private var published = -1

    /** A fragment Obi said ([obi]) or the business said; a new line when the speaker changes. Beyond the cap it's dropped. */
    fun add(obi: Boolean, fragment: String) {
        val t = fragment.replace(Regex("[\\t\\r\\n]+"), " ")
        if (t.isBlank() || size >= BookingLiveRules.MAX_TRANSCRIPT) return
        val room = BookingLiveRules.MAX_TRANSCRIPT - size
        val last = lines.lastOrNull()
        if (last == null || last.first != obi) {
            if (lines.size >= BookingLiveRules.MAX_LINES) return
            lines += obi to StringBuilder(t.trimStart().take(room))
        } else last.second.append(t.take(room))
        size = lines.sumOf { it.second.length }
        version++
    }

    /** The last few lines for the live view, encoded, when they changed since the last call; null otherwise. */
    fun liveIfChanged(): String? {
        if (version == published) return null
        published = version
        return encode(lines.takeLast(BookingLiveRules.LIVE_LINES), BookingLiveRules.MAX_LIVE)
    }

    /** The whole call, encoded. */
    fun all(): String = encode(lines, BookingLiveRules.MAX_TRANSCRIPT)

    companion object {
        private fun encode(ls: List<Pair<Boolean, StringBuilder>>, max: Int): String {
            val out = ls.map { (obi, b) -> (if (obi) "O" else "T") + "\t" + b.toString().replace(Regex("\\s+"), " ").trim() }
                .filter { it.length > 2 }
            // The newest lines are the ones kept when the live view's cap bites.
            var total = 0
            val kept = out.asReversed().takeWhile { total += it.length + 1; total <= max + 1 }.asReversed()
            return kept.joinToString("\n")
        }

        /** The lines as the apps draw them: Obi's as "Obi", the business's under its name. Anything malformed is skipped. */
        fun decode(s: String?, business: String): List<BookingWordLine> = s.orEmpty().split('\n').mapNotNull { l ->
            val tab = l.indexOf('\t')
            if (tab != 1) return@mapNotNull null
            val text = l.substring(2).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (l[0]) {
                'O' -> BookingWordLine(BookingLiveRules.OBI, text, true)
                'T' -> BookingWordLine(business.trim().take(BookingLiveRules.MAX_WHO).ifEmpty { BookingLiveRules.THEM }, text, false)
                else -> null
            }
        }
    }
}

object BookingLiveRules {
    const val OBI = "Obi"
    const val THEM = "Them"
    const val MAX_WHO = 24
    const val LIVE_WORDS = "Live words"
    const val HANG_UP = "Hang up"
    const val HANGING_UP = "Hanging up · Obi is saying goodbye"
    const val HUNG_UP_LINE = "Hanging up · Obi says goodbye and ends the call"
    const val NOT_ON_CALL = "That call has already ended"
    const val ADD_TO_CALENDAR = "Add to calendar"
    const val ADDED = "Added"
    const val ADDED_LINE = "Added to your calendar"
    const val READ = "Read the call"
    const val READ_TITLE = "The call, in words"
    const val READ_FOOT = "Kept for 7 days, then deleted · what they said is shown as heard, Obi never acts on it"
    const val CLOSE = "Close"
    const val NAME = "Name"
    const val WAITING_WORDS = "Ringing · the words show here once they answer"

    /** The live view shows this many lines, at most this many characters. */
    const val LIVE_LINES = 4
    const val MAX_LIVE = 600
    /** The whole call is kept to this many characters and lines. */
    const val MAX_TRANSCRIPT = 6_000
    const val MAX_LINES = 120
    /** The words are kept this long after the call, then blanked (the recordings' keep). */
    const val KEEP_DAYS = 7
    const val KEEP_MS = KEEP_DAYS * 24 * 60 * 60_000L
    /** The live words are sent to the apps at most this often. */
    const val WORDS_EVERY_MS = 3_000L
    /** The server looks for Hang up this often. */
    const val HANG_UP_POLL_MS = 2_000L
    /** After Hang up Obi has this long to say goodbye before the line closes. */
    const val HANG_UP_GRACE_MS = 6_000L
    /** While a card is on the call the apps read again this often. */
    const val LIVE_REFRESH_MS = 2_000L
    /** How long the calendar event is: a table an hour and a half, anything else an hour. */
    const val TABLE_MIN = 90
    const val OTHER_MIN = 60

    /** Obi's turn when he taps Hang up: a goodbye, nothing booked unless it already was. */
    const val HUNG_UP_TURN = "The person you assist has asked you to end this call now. Don't book anything more. " +
        "Say sorry, you have to go, thank them and say goodbye in one short sentence, then call ${LiveCallRules.END_CALL}."
    const val HUNG_UP_RESULT = "Not booked · you hung up · nothing was agreed"

    /** "01:12" from [sinceMs] to [nowMs] (minutes can pass 59: "12:05"). */
    fun clock(sinceMs: Long, nowMs: Long): String {
        val s = ((nowMs - sinceMs) / 1_000).coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return (if (m < 10) "0$m" else "$m") + ":" + (if (r < 10) "0$r" else "$r")
    }

    /** "2 min 40 s", "45 s", "3 min". */
    fun length(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return when {
            m == 0L -> "$r s"
            r == 0L -> "$m min"
            else -> "$m min $r s"
        }
    }

    /** The live view's "Rosa's Kitchen · 01:12" (the apps tick it each second). */
    fun liveLine(business: String, sinceMs: Long, nowMs: Long): String = "$business · ${clock(sinceMs, nowMs)}"

    /**
     * The time agreed, from Obi's [raw] ("19:30", "7:30pm", "7pm", "19.30"), only when it falls in the card's window
     * (or anywhere in the day for a card with no window). Null otherwise: the result line still says what was agreed.
     */
    fun agreedMinute(raw: String?, fromMin: Int?, toMin: Int?): Int? {
        val t = raw?.trim()?.lowercase()?.replace(" ", "") ?: return null
        val m = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(am|pm)?$""").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (min > 59) return null
        if (ap.isNotEmpty()) {
            if (h !in 1..12) return null
            if (ap == "pm" && h != 12) h += 12
            if (ap == "am" && h == 12) h = 0
        } else if (m.groupValues[2].isEmpty()) return null
        if (h > 23) return null
        val at = h * 60 + min
        if (fromMin != null) {
            val end = toMin ?: (fromMin + BookingCallRules.DEFAULT_WINDOW_MIN)
            if (at < fromMin || at > end) return null
        }
        return at
    }

    /** The name the booking is under, as Obi gave it: letters, spaces, hyphens and apostrophes only. */
    fun name(raw: String?): String? = raw?.replace(Regex("\\s+"), " ")?.trim()?.take(30)?.trim()
        ?.takeIf { n -> n.isNotEmpty() && n.all { it.isLetter() || it in " -'." } }

    /** The booked card's time: the one agreed, else the window's start (null: no time, no calendar line). */
    fun bookedAt(c: BookingCall): Int? = c.bookedMin ?: c.fromMin

    /** The calendar event for a booked card: "Table for 4 at Rosa's Kitchen", on its day at its time. Null without a time. */
    fun calendarDraft(c: BookingCall, cal: LocalCalendar): EventDraft? {
        if (c.state != BookingCallState.BOOKED) return null
        val at = bookedAt(c) ?: return null
        val start = cal.toEpochMs(c.day, at)
        val len = if (c.what.contains("table", ignoreCase = true)) TABLE_MIN else OTHER_MIN
        val what = c.what.trim()
        val title = if (what.contains(c.business, ignoreCase = true)) what else "$what at ${c.business}"
        val ref = c.result?.substringAfter("ref ", "")?.substringBefore(" ·")?.trim()?.takeIf { it.isNotEmpty() }
        return EventDraft(title.take(120), start, start + len * 60_000L, false, null,
            listOfNotNull("Booked by Obi on the phone", ref?.let { "Ref $it" }).joinToString(" · "))
    }

    /** Whether the call's words are still kept at [nowMs]. */
    fun kept(c: BookingCall, nowMs: Long): Boolean = c.transcript != null && (c.endedAtMs == null || nowMs - c.endedAtMs <= KEEP_MS)

    /** Whether the server should blank card [c]'s words now (over [KEEP_DAYS] days since the call ended). */
    fun shouldForget(c: BookingCall, nowMs: Long): Boolean =
        (c.transcript != null || c.words != null) && c.state.finished && c.endedAtMs != null && nowMs - c.endedAtMs > KEEP_MS

    /** [row] with slice 3's live view or result card for [c]. */
    fun withLive(row: BookingCallRow, c: BookingCall, nowMs: Long, cal: LocalCalendar): BookingCallRow {
        val live = !c.cancelled && c.state == BookingCallState.CALLING
        val finished = c.state.finished
        val transcript = if (finished && kept(c, nowMs)) BookingWords.decode(c.transcript, c.business) else emptyList()
        val booked = c.state == BookingCallState.BOOKED
        val at = if (booked) bookedAt(c) else null
        val tiles = if (!booked) emptyList() else listOfNotNull(
            BookingTile(BookingCallRules.WHEN, LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(c.day) - 1] + (at?.let { " " + LocalClock.formatMinute(it) } ?: "")),
            BookingTile(BookingCallRules.PEOPLE, c.people?.toString() ?: "—"),
            c.bookedName?.let { BookingTile(NAME, it) },
        )
        val draftable = booked && at != null && c.day >= cal.epochDayOf(nowMs)
        return row.copy(
            live = live,
            liveSinceMs = if (live) (c.answeredAtMs ?: c.calledAtMs ?: nowMs) else -1,
            liveWords = if (live) BookingWords.decode(c.words, c.business) else emptyList(),
            canHangUp = live && !c.hangUp,
            showBrief = !live && !finished,
            resultTiles = tiles,
            lengthLine = if (finished && c.seconds != null && c.seconds > 0) "${c.business} · ${length(c.seconds)}" else null,
            calendarLine = if (booked && at != null) "In your calendar · ${CivilDate.shortLabel(c.day)} ${LocalClock.formatMinute(at)}" else null,
            canAddToCalendar = draftable && c.calendarEditId == null,
            addedToCalendar = booked && c.calendarEditId != null,
            transcript = transcript,
            spoken = if (live) listOfNotNull(row.spoken, BookingWords.decode(c.words, c.business).lastOrNull()?.let { "${it.who}: ${it.text}" }).joinToString(". ")
                else row.spoken,
        )
    }
}
