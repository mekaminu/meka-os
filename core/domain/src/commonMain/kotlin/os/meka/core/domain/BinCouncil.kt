package os.meka.core.domain

/**
 * Bin days: the council's own dates (Needs Meka #25, answered 2026-10-12; built 2026-11-02). Most councils' bin-day
 * pages offer the address's collections as a calendar file (iCalendar, .ics: "Add to calendar", "Download your bin
 * calendar"). Meka picks that file in Bins on the Fold or the Mac; it is read on the device (nothing is sent, and the
 * council's name and his address never reach this repository or the server) and becomes the same `bin` and `bin_move`
 * entities he would type: each bin's usual day and every-N-weeks from the council's dates, and a one-off wherever the
 * council's date differs from that pattern (Christmas weeks, a bank holiday the council doesn't move for). Only the
 * dates and the bins' names are kept, never the file's other text. The typed pattern is kept on the bin so "Use my
 * own days" puts it back. Plain, non-AI rules.
 */

/** One bin as the council's calendar has it: the dates (local epoch days, soonest first) and what they make. */
data class CouncilBin(
    val name: String,
    val days: List<Long>,
    val weekday: Int,
    val everyWeeks: Int,
    val anchorDay: Long,
    /** Where the council's date differs from the pattern: from the pattern's day to the council's (null: no collection). */
    val moves: List<Pair<Long, Long?>>,
)

/** What a calendar file said: the bins (in the file's order), or the plain line why nothing could be used. */
sealed interface CouncilRead {
    data class Bins(val bins: List<CouncilBin>) : CouncilRead
    data class Refused(val line: String) : CouncilRead
}

/** What using the council's dates did: the line under the field, whether nothing was written, and whether Undo applies. */
data class BinCouncilSaid(val line: String, val refused: Boolean, val canUndo: Boolean = false)

object BinCouncilRules {
    const val SOURCE = "council"
    const val TYPED = "typed"
    /** The biggest file read (a year of collections is a few KB; some councils pad with long descriptions). */
    const val MAX_CHARS = 512_000
    /** At most this many events are read. */
    const val MAX_EVENTS = 2_000
    /** Collections from a week back (so this week's one-offs hold) to about a year ahead. */
    const val BACK_DAYS = 7L
    const val AHEAD_DAYS = 400L
    /** A council's date this many days or fewer from the pattern's is that week's collection moved, else a skip. */
    const val MATCH_DAYS = 6L
    /** A recurring event (RRULE) is expanded at most this many times. */
    private const val MAX_REPEATS = 120

    const val NOT_A_CALENDAR = "That file isn't a calendar. On your council's bin-day page, download the bin calendar (.ics) and pick that."
    const val NO_COLLECTIONS = "No collections from this week on in that calendar. Is it last year's? Download the latest from the council's bin-day page."
    const val TOO_BIG = "That file is too big to be a bin calendar."
    const val NOTHING_TO_UNDO = "Your bins already use the days you typed."

    /** iCalendar line unfolding: a line starting with a space or tab continues the one before (RFC 5545 3.1). */
    internal fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1)
            else out += raw
        }
        return out
    }

    /** "SUMMARY;LANGUAGE=en:Black bin" → ("SUMMARY", ["LANGUAGE=en"], "Black bin"). */
    private fun split(line: String): Triple<String, List<String>, String>? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        return Triple(head[0].uppercase(), head.drop(1), line.substring(colon + 1))
    }

    /** "20261103", "20261103T070000Z" → that local day (the date as written; a collection is a whole day). */
    internal fun day(value: String): Long? {
        val v = value.trim()
        if (v.length < 8 || !v.take(8).all { it.isDigit() }) return null
        val y = v.substring(0, 4).toInt(); val m = v.substring(4, 6).toInt(); val d = v.substring(6, 8).toInt()
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
        return CivilDate.toEpochDay(y, m, d)
    }

    private fun unescape(s: String): String = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** A weekly RRULE's days from [start] ("FREQ=WEEKLY;INTERVAL=2;UNTIL=20270331"), within [to]; null when it isn't weekly. */
    private fun repeats(rule: String, start: Long, to: Long): List<Long>? {
        val parts = rule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
        if (parts["FREQ"]?.uppercase() != "WEEKLY") return null
        if (parts["BYDAY"]?.contains(',') == true) return null
        val step = 7L * (parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 8) ?: 1)
        val until = parts["UNTIL"]?.let { day(it) } ?: Long.MAX_VALUE
        val count = parts["COUNT"]?.toIntOrNull()?.coerceIn(1, MAX_REPEATS) ?: MAX_REPEATS
        return (0 until count).map { start + it * step }.takeWhile { it <= until && it <= to }
    }

    /**
     * The bins' names in one event's summary: "Black bin and food caddy" → [Black bin, Food caddy]; "Refuse
     * collection" → [Refuse]; "Recycling (blue bin)" → [Recycling]. Words like "collection" and "day" go.
     */
    fun names(summary: String): List<String> {
        val noBrackets = buildString {
            var depth = 0
            for (c in summary) { if (c == '(' || c == '[') depth++ else if (c == ')' || c == ']') depth = maxOf(0, depth - 1) else if (depth == 0) append(c) }
        }
        var parts = listOf(noBrackets)
        for (sep in listOf(" and ", " And ", " AND ", "&", ",", "+", "/")) parts = parts.flatMap { it.split(sep) }
        val drop = setOf("collection", "collections", "collected", "day", "due", "your", "next", "scheduled", "today", "tomorrow", "service", "bins")
        return parts.mapNotNull { p ->
            val kept = p.split(' ', '\t', '-', ':').filter { it.isNotBlank() && it.lowercase().trim('.') !in drop }
            BinRules.tidyName(kept.joinToString(" ")).takeIf { it.isNotEmpty() }
        }.distinct()
    }

    /**
     * Reads an iCalendar file into each bin's collection days from [today] − [BACK_DAYS] to [today] + [AHEAD_DAYS]:
     * DTSTART (a date or a date-time, the date as written), SUMMARY for the bins, weekly RRULEs expanded with their
     * EXDATEs; everything else in the file is ignored and nothing of it kept.
     */
    fun collect(text: String, today: Long): Map<String, List<Long>>? {
        if (text.length > MAX_CHARS) return null
        val lines = unfold(text)
        if (lines.none { it.trim().equals("BEGIN:VCALENDAR", ignoreCase = true) }) return null
        val from = today - BACK_DAYS
        val to = today + AHEAD_DAYS
        val out = LinkedHashMap<String, MutableSet<Long>>()
        var inEvent = false
        var start: Long? = null; var summary: String? = null; var rule: String? = null
        val except = mutableSetOf<Long>()
        var events = 0
        for (line in lines) {
            val t = line.trim()
            when {
                t.equals("BEGIN:VEVENT", ignoreCase = true) -> { inEvent = true; start = null; summary = null; rule = null; except.clear() }
                t.equals("END:VEVENT", ignoreCase = true) && inEvent -> {
                    inEvent = false
                    if (++events > MAX_EVENTS) break
                    val s = start ?: continue
                    val bins = names(summary ?: continue)
                    if (bins.isEmpty()) continue
                    val days = (rule?.let { repeats(it, s, to) } ?: listOf(s)).filter { it !in except && it in from..to }
                    for (b in bins) out.getOrPut(b) { mutableSetOf() } += days
                }
                inEvent -> {
                    val (key, _, value) = split(t) ?: continue
                    when (key) {
                        "DTSTART" -> start = day(value)
                        "SUMMARY" -> summary = unescape(value).trim()
                        "RRULE" -> rule = value
                        "EXDATE" -> value.split(',').mapNotNull { day(it) }.forEach { except += it }
                    }
                }
            }
        }
        return out.mapValues { it.value.sorted() }.filterValues { it.isNotEmpty() }
    }

    /**
     * One bin's pattern from the council's [days] (soonest first): the commonest weekday, every N weeks from the
     * commonest gap (1 to 4), anchored on its first day on that weekday; then a one-off wherever the council's date
     * isn't the pattern's (with the bank-holiday shift [holidays] would make): moved to the council's date when one
     * is within [MATCH_DAYS], else no collection that week.
     */
    fun pattern(name: String, days: List<Long>, holidays: HolidayCalendar): CouncilBin {
        val weekday = days.groupingBy { CivilDate.isoDayOfWeek(it) }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key
        val usual = days.filter { CivilDate.isoDayOfWeek(it) == weekday }
        val gaps = usual.zipWithNext { a, b -> ((b - a + 3) / 7).toInt() }.filter { it in 1..4 }
        val every = gaps.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: 1
        val anchor = usual.first()
        val bin = BinItem("council", name, weekday, every, anchor, 0)
        val planned = BinRules.collections(bin, days.first(), days.last(), holidays)
        val council = days.toSet()
        val spare = days.filter { it !in planned.toSet() }.toMutableList()
        val moves = planned.filter { it !in council }.map { p ->
            val near = spare.filter { kotlin.math.abs(it - p) <= MATCH_DAYS }.minByOrNull { kotlin.math.abs(it - p) }
            if (near != null) spare.remove(near)
            p to near
        }
        return CouncilBin(name, days, weekday, every, anchor, moves)
    }

    /** The whole file read: each bin's pattern and one-offs, or the plain line why not. */
    fun read(text: String, today: Long, holidays: HolidayCalendar): CouncilRead {
        if (text.length > MAX_CHARS) return CouncilRead.Refused(TOO_BIG)
        val found = collect(text, today) ?: return CouncilRead.Refused(NOT_A_CALENDAR)
        val ahead = found.filterValues { days -> days.any { it >= today } }
        if (ahead.isEmpty()) return CouncilRead.Refused(NO_COLLECTIONS)
        return CouncilRead.Bins(ahead.map { (name, days) -> pattern(name, days, holidays) })
    }

    /** "Council dates for Black bin and food caddy · 52 collections to Fri 5 Feb · 3 changes from the usual day". */
    fun usedLine(bins: List<CouncilBin>, today: Long): String {
        val n = bins.sumOf { b -> b.days.count { it >= today } }
        val last = bins.maxOf { it.days.last() }
        val changes = bins.sumOf { b -> b.moves.count { (from, _) -> from >= today } }
        val named = BinRules.names(bins.map { BinItem(it.name, it.name, it.weekday, it.everyWeeks, it.anchorDay, 0) })
        return "Council dates for $named · " +
            "$n ${if (n == 1) "collection" else "collections"} to ${CivilDate.shortLabel(last)}" +
            (if (changes > 0) " · $changes ${if (changes == 1) "change" else "changes"} from the usual day" else "")
    }

    /** "Council dates · read Mon 2 Nov" for the pane while any bin follows the council; null otherwise. */
    fun inUseLine(bins: List<BinItem>, calendar: LocalCalendar = LocalCalendar.UTC): String? {
        val at = bins.mapNotNull { it.councilAtMs }.maxOrNull() ?: return null
        return "Council dates · read ${CivilDate.shortLabel(calendar.epochDayOf(at))}"
    }

    /** After "Use my own days". */
    fun typedLine(kept: Int, removed: Int): String = listOfNotNull(
        "Back to the days you typed".takeIf { kept > 0 },
        "Removed ${removed} ${if (removed == 1) "bin" else "bins"} only the council's calendar had".takeIf { removed > 0 },
    ).joinToString(" · ").ifEmpty { NOTHING_TO_UNDO }
}
