package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Scheduling assistant, slice 2 (build plan; non-AI, pure): **book the time they pick**. When he has sent a reply
 * holding free times (Suggest times' list, "• Tuesday 20 October, 13:00–14:00", as he sent it — his own words, so what
 * he edited is what counts) and the other person writes back in the same thread naming one of them ("Tuesday at 1pm
 * works", "the second one", "20th is good"), their email's row in "From your mail" says "Sam picked Tue 20 Oct,
 * 13:00–14:00" with **Book it**. Book it adds the event to his calendar (calendar editing, the account he last added
 * to, the usual five-second Undo before it reaches Google/Outlook) and the row says "Booked · …"; nothing is booked
 * without his tap and nothing is sent to them (he writes back as usual).
 *
 * Their words are untrusted (ADR-006): they can only choose among the times **he** offered — a time, a day or a title
 * of theirs never reaches his calendar; the event's title is "Meeting with <their name>". A reply that names a time he
 * didn't offer, names two of his times, or says a time doesn't work ("Tuesday's no good, Wednesday?" keeps only
 * Wednesday) offers nothing ambiguous: either exactly one offered time is picked or none is.
 */

/** A time they picked from the ones he offered, answering his reply on [offerId]. */
data class MeetingPick(val offerId: String, val slot: FreeSlot, val title: String)

object MeetingPickRules {
    const val BOOK = "Book it"
    const val BOOKING = "Booking…"
    const val NO_PICK = "They didn't pick one of the times you offered"
    const val NO_ACCOUNT = "Allow editing on a calendar in Calendars first, then Book it"
    const val GONE = "That time has passed · write back with another"
    /**
     * A pick still counts for this long after his reply was sent; slice 3: after it, or once every time he offered
     * has passed, the offer has cleared itself (no Book it, no "can't make those times").
     */
    const val WINDOW_DAYS = 14
    const val MAX_TITLE = 80

    private val WEEKDAYS = listOf(
        listOf("monday", "mon"), listOf("tuesday", "tues", "tue"), listOf("wednesday", "weds", "wed"),
        listOf("thursday", "thurs", "thur", "thu"), listOf("friday", "fri"), listOf("saturday", "sat"), listOf("sunday", "sun"),
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** One line of his list: "• Tuesday 20 October, 13:00–14:00" (the bullet optional). */
    private val OFFER = Regex(
        """^\s*(?:[•*\-–]\s*)?(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\s+(\d{1,2})\s+""" +
            """(January|February|March|April|May|June|July|August|September|October|November|December),?\s+(\d{1,2}):(\d{2})\s*[–\-]\s*(\d{1,2}):(\d{2})""" +
            """(?:\s*\((?:[A-Za-z]{3}\s+)?(\d{1,2}):(\d{2})\s*[–\-]\s*\d{1,2}:\d{2}\s+[^()]{1,40}\btime\))?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    /** Where the quoted earlier email starts in their reply. */
    private val QUOTE = Regex("""\bOn\s[^\n]{0,160}?\bwrote:|-{2,}\s*Original Message|\n\s*>|\bFrom:\s|\bSent from my\b""", RegexOption.IGNORE_CASE)
    private val CLAUSES = Regex("""[.;!?\n,()]+|\bbut\b|\bhowever\b""", RegexOption.IGNORE_CASE)
    /** A clause that turns a time down. ("No problem" is fine, so a bare "no" isn't a no here.) */
    private val NEGATIVE = Regex(
        """\b(?:not|cannot|can't|cant|won't|wont|doesn't|doesnt|don't|dont|isn't|isnt|unfortunately|unable|busy|tied\s+up|clash(?:es)?|none)\b|n't\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ORDINAL = Regex("""\b(first|1st|second|2nd|third|3rd|last)\s+(?:one|option|time|slot|choice)\b""", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("""\b(monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\b""", RegexOption.IGNORE_CASE)
    private val DAY_ORD = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?\b""", RegexOption.IGNORE_CASE)
    private val SLASH = Regex("""\b(\d{1,2})/(\d{1,2})\b""")
    private val AMPM = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b(\d{1,2})[:.](\d{2})\b(?!\s*(?:am|pm|a\.m\.|p\.m\.))""", RegexOption.IGNORE_CASE)
    private val NOON = Regex("""\b(?:noon|midday)\b""", RegexOption.IGNORE_CASE)
    private val RE = Regex("""^\s*(?:(?:re|fw|fwd|aw)\s*:\s*)+""", RegexOption.IGNORE_CASE)

    /**
     * The times he offered in [reply], as he sent it on [sentAtMs]: each list line read back to its day (the first
     * date on or after the day before he sent it whose weekday matches), in the order written.
     */
    fun offered(reply: String?, sentAtMs: Long, cal: LocalCalendar): List<FreeSlot> {
        if (reply.isNullOrBlank()) return emptyList()
        val sentDay = cal.epochDayOf(sentAtMs)
        val year = CivilDate.fromEpochDay(sentDay).year
        return reply.lines().mapNotNull { line ->
            val m = OFFER.matchEntire(line) ?: return@mapNotNull null
            val g = m.groupValues
            val weekday = CivilDate.DAY_LONG.indexOfFirst { it.equals(g[1], ignoreCase = true) } + 1
            val month = CivilDate.MONTH_LONG.indexOfFirst { it.equals(g[3], ignoreCase = true) } + 1
            val dom = g[2].toInt()
            val start = g[4].toInt() * 60 + g[5].toInt()
            val end = g[6].toInt() * 60 + g[7].toInt()
            if (end <= start || start >= LocalClock.MINUTES_PER_DAY || end > LocalClock.MINUTES_PER_DAY) return@mapNotNull null
            val day = (year..year + 1).asSequence()
                .filter { dom in 1..CivilDate.lengthOfMonth(it, month) }
                .map { CivilDate.toEpochDay(it, month, dom) }
                .firstOrNull { it >= sentDay - 1 && CivilDate.isoDayOfWeek(it) == weekday } ?: return@mapNotNull null
            val theirs = g[8].takeIf { it.isNotEmpty() }?.let { h -> h.toInt() * 60 + g[9].toInt() }?.takeIf { it < LocalClock.MINUTES_PER_DAY }
            FreeSlot(cal.toEpochMs(day, start), cal.toEpochMs(day, end), theirs)
        }.distinctBy { it.startMs to it.endMs }
    }

    /** "re: Catch up?" and "Catch up?" are one conversation. */
    fun sameSubject(a: String, b: String): Boolean = RE.replace(a, "").trim().equals(RE.replace(b, "").trim(), ignoreCase = true)

    /**
     * His sent reply that [answer] answers: same thread (or, without one, the same sender and subject), his reply sent
     * before [answer] came and within [WINDOW_DAYS], and holding offered times. The newest such reply.
     */
    fun offerFor(answer: MailItem, items: List<MailItem>, cal: LocalCalendar): Pair<MailItem, List<FreeSlot>>? =
        items.asSequence()
            .filter { it.id != answer.id && MailNeedsRules.replySent(it) }
            .filter { o ->
                val sent = o.replySentAtMs!!
                sent < answer.receivedAtMs && answer.receivedAtMs - sent <= WINDOW_DAYS * CivilDate.DAY_MS &&
                    (if (o.threadKey != null && answer.threadKey != null) o.threadKey == answer.threadKey
                    else o.from.equals(answer.from, ignoreCase = true) && sameSubject(o.subject, answer.subject))
            }
            .sortedByDescending { it.replySentAtMs }
            .map { it to offered(it.replyText, it.replySentAtMs!!, cal) }
            .firstOrNull { it.second.isNotEmpty() }

    /** Slice 3: an answer that turns all his times down or asks for others. */
    private val DECLINE = Regex(
        """\bnone\s+of\s+(?:those|these|them|the\s+(?:times|dates|days|slots|options))\b|""" +
            """\b(?:can't|cant|cannot|can\s+not|couldn't|won't\s+be\s+able\s+to|unable\s+to|not\s+able\s+to)\s+(?:do|make|manage)\s+(?:any|either|those|these|them)\b|""" +
            """\bneither\s+(?:of\s+(?:those|these|them)|works|suits|is\s+good)\b|""" +
            """\b(?:those|these)\s+(?:times\s+|dates\s+|days\s+)?(?:don't|do\s+not|won't|wont|doesn't)\s+(?:work|suit)\b|""" +
            """\b(?:any|some)\s+other\s+(?:times?|days?|dates?|slots?|options?)\b|""" +
            """\b(?:other|different|alternative|later|earlier)\s+(?:times|days|dates|slots|options)\b|""" +
            """\b(?:what|how)\s+about\s+(?:next|the\s+following)\s+week\b""",
        RegexOption.IGNORE_CASE,
    )
    const val DECLINED_SUFFIX = "can't make the times you offered · Suggest times finds others"

    /** Their words before the quoted earlier email. */
    fun fresh(text: String): String = QUOTE.find(text)?.let { text.substring(0, it.range.first) } ?: text

    /** Whether [text] (their words) turns his offered times down or asks for others. */
    fun declines(text: String?): Boolean = !text.isNullOrBlank() && DECLINE.containsMatchIn(fresh(text))

    /**
     * Slice 3: [answer] answers his offer (inside [WINDOW_DAYS], some offered time still ahead) without picking one and
     * turns them down or asks for other times. The row then says so and offers Suggest times again, away from those days.
     */
    fun declinedFor(answer: MailItem, items: List<MailItem>, nowMs: Long, cal: LocalCalendar): Boolean {
        val (offer, slots) = offerFor(answer, items, cal) ?: return false
        if (slots.none { it.endMs > nowMs }) return false
        return declines(answerText(answer, offer))
    }

    /** "Sam can't make the times you offered · Suggest times finds others". */
    fun declinedLine(from: String): String = "${from.trim().substringBefore(' ').ifEmpty { "They" }} $DECLINED_SUFFIX"

    private fun answerText(answer: MailItem, offer: MailItem): String =
        listOfNotNull(answer.subject.takeIf { !sameSubject(it, offer.subject) }, answer.preview).joinToString(". ")

    /** Their words before the quoted earlier email, with the clauses that turn a time down left out. */
    fun kept(text: String): String {
        return fresh(text).split(CLAUSES).filter { it.isNotBlank() && !NEGATIVE.containsMatchIn(it) }.joinToString(" ")
    }

    /**
     * The one offered time [text] picks, or null: every kind of thing it names (a weekday, a date, a time, "the second
     * one") has to fit the time, and exactly one offered time may fit.
     */
    fun pick(text: String?, slots: List<FreeSlot>, cal: LocalCalendar): FreeSlot? {
        if (text.isNullOrBlank() || slots.isEmpty()) return null
        var t = kept(text)
        val ordinals = ORDINAL.findAll(t).map {
            when (it.groupValues[1].lowercase()) {
                "first", "1st" -> 0
                "second", "2nd" -> 1
                "third", "3rd" -> 2
                else -> slots.size - 1
            }
        }.toSet()
        t = ORDINAL.replace(t, " ")
        val weekdays = WEEKDAY.findAll(t).map { m -> WEEKDAYS.indexOfFirst { m.value.lowercase() in it } + 1 }.toSet()
        val dates = mutableSetOf<Pair<Int?, Int>>() // (month or null, day of month)
        DAY_MONTH.findAll(t).forEach { dates += (MONTHS.indexOf(it.groupValues[2].lowercase()) + 1) to it.groupValues[1].toInt() }
        MONTH_DAY.findAll(t).forEach { dates += (MONTHS.indexOf(it.groupValues[1].lowercase()) + 1) to it.groupValues[2].toInt() }
        SLASH.findAll(t).forEach { dates += it.groupValues[2].toInt() to it.groupValues[1].toInt() }
        if (dates.isEmpty()) DAY_ORD.findAll(t).forEach { dates += null to it.groupValues[1].toInt() }
        val times = mutableSetOf<Int>()
        AMPM.findAll(t).forEach {
            val h = it.groupValues[1].toInt()
            val min = it.groupValues[2].takeIf { s -> s.isNotEmpty() }?.toInt() ?: 0
            if (h in 1..12 && min < 60) times += (h % 12 + if (it.groupValues[3].lowercase().startsWith("p")) 12 else 0) * 60 + min
        }
        CLOCK.findAll(t).forEach {
            val h = it.groupValues[1].toInt()
            val min = it.groupValues[2].toInt()
            if (h in 0..23 && min < 60) {
                times += h * 60 + min
                if (h in 1..11) times += (h + 12) * 60 + min
            }
        }
        if (NOON.containsMatchIn(t)) times += 12 * 60
        if (ordinals.isEmpty() && weekdays.isEmpty() && dates.isEmpty() && times.isEmpty()) return null
        val fits = slots.withIndex().filter { (i, s) ->
            val day = cal.epochDayOf(s.startMs)
            val ymd = CivilDate.fromEpochDay(day)
            (ordinals.isEmpty() || i in ordinals) &&
                (weekdays.isEmpty() || CivilDate.isoDayOfWeek(day) in weekdays) &&
                (dates.isEmpty() || dates.any { (m, d) -> d == ymd.day && (m == null || m == ymd.month) }) &&
                (times.isEmpty() || cal.minuteOfDay(s.startMs) in times || s.theirStartMin in times)
        }
        return fits.singleOrNull()?.value
    }

    /** The time [answer] picks from his earlier offer, or null (none, ambiguous, or already past [nowMs]). */
    fun pickFor(answer: MailItem, items: List<MailItem>, nowMs: Long, cal: LocalCalendar): MeetingPick? {
        val (offer, slots) = offerFor(answer, items, cal) ?: return null
        val slot = pick(answerText(answer, offer), slots, cal)
            ?: return null
        if (slot.endMs <= nowMs) return null
        return MeetingPick(offer.id, slot, title(answer.from))
    }

    /** "Meeting with Sam Taylor": their name, never their words. */
    fun title(from: String): String = "Meeting with ${from.trim().ifEmpty { "them" }}".take(MAX_TITLE)

    /** "Tue 20 Oct, 13:00–14:00". */
    fun slotShort(s: FreeSlot, cal: LocalCalendar): String =
        "${CivilDate.shortLabel(cal.epochDayOf(s.startMs))}, ${LocalClock.formatMinute(cal.minuteOfDay(s.startMs))}–${LocalClock.formatMinute(cal.minuteOfDay(s.endMs))}"

    /** The row's line: "Sam picked Tue 20 Oct, 13:00–14:00 · one of the times you offered". */
    fun pickedLine(from: String, s: FreeSlot, cal: LocalCalendar): String {
        val who = from.trim().substringBefore(' ').ifEmpty { "They" }
        return "$who picked ${slotShort(s, cal)} · one of the times you offered"
    }

    /** Once booked: "Booked · Tue 20 Oct, 13:00–14:00 · in your calendar". */
    fun bookedLine(s: FreeSlot, cal: LocalCalendar): String = "Booked · ${slotShort(s, cal)} · in your calendar"

    /** The bar's line after Book it: "Adding “Meeting with Sam” to Google · Tue 20 Oct, 13:00 · write back to confirm". */
    fun bookingLine(p: MeetingPick, provider: String, cal: LocalCalendar): String =
        "Adding “${p.title}” to ${CalendarEditRules.providerName(provider)} · ${CivilDate.shortLabel(cal.epochDayOf(p.slot.startMs))}, " +
            "${LocalClock.formatMinute(cal.minuteOfDay(p.slot.startMs))} · write back to confirm"

    /** The event Book it adds: their name as the title, the email it came from in the notes. */
    fun draft(p: MeetingPick, account: String, subject: String): EventDraft =
        EventDraft(p.title, p.slot.startMs, p.slot.endMs, allDay = false, notes = "Booked by Navilife from your $account email “${subject.take(120)}”")

    /** Booked: the edit Book it made, and the start it booked (so the row says so on every device). */
    fun bookedFields(editId: String, startMs: Long): Map<String, FieldValue> =
        mapOf(MailItemFields.MEETING_EDIT_ID to editId.fv(), MailItemFields.MEETING_AT to startMs.fv())

    /** Undo inside the five seconds: not booked after all. */
    fun unbookedFields(): Map<String, FieldValue> =
        mapOf(MailItemFields.MEETING_EDIT_ID to "".fv(), MailItemFields.MEETING_AT to 0L.fv())

    /** Booked for [p]'s time from this email. */
    fun booked(i: MailItem, p: MeetingPick): Boolean = !i.meetingEditId.isNullOrEmpty() && i.meetingAtMs == p.slot.startMs
}
