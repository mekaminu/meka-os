package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Bin days (Meka approved 2026-10-10), slice 1: the manual pattern. Each bin is typed once — "Black bin Tue
 * fortnightly from 13 Oct", "Blue bin every other Tuesday 20 Oct", "Food caddy every Tuesday" — and MEKA works out
 * every collection from it, a day later in a week with a bank holiday on or before the collection day (the usual
 * English rule; never past Saturday). The evening before: Today's header and a heads-up at 19:00, "Black bin and food
 * caddy out tonight"; the week ahead shows each collection. Plain, non-AI rules; nothing leaves Meka's synced data.
 *
 * One synced `bin` entity per bin (ADR-008 addendum 2026-10-12). The council's own collection days (a later slice)
 * would fill the same entities.
 */
object BinFields {
    /** "Black bin", "Food caddy" (at most [BinRules.MAX_NAME]; one bin per name, ignoring case). */
    const val NAME = "name"
    /** ISO weekday 1..7 of the collection. */
    const val WEEKDAY = "weekday"
    /** 1 every week, 2 every other week. */
    const val EVERY_WEEKS = "everyWeeks"
    /** One collection day (local epoch day): fixes which week a fortnightly bin goes. */
    const val ANCHOR = "anchorDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/**
 * Slice 3 (2026-10-12): a one-off change to one collection, typed in Bins — "Black bin Sat 2 Jan instead of 29 Dec"
 * (moved) or "No black bin 29 Dec" (skipped). One synced `bin_move` entity per bin and week (ADR-008 addendum
 * 2026-10-12); [FROM] is the day the pattern would have collected (after any bank-holiday shift).
 */
object BinMoveFields {
    const val BIN = "binId"
    const val FROM = "fromDay"
    /** The day it goes instead; equal to [FROM] when [SKIPPED]. */
    const val TO = "toDay"
    const val SKIPPED = "skipped"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** One stored one-off: the bin's collection on [fromDay] goes on [toDay] instead, or not at all (null). */
data class BinMove(val id: String, val binId: String, val fromDay: Long, val toDay: Long?)

/** What one typed line says ([BinRules.read]). [dated] is false when a fortnightly bin's week was guessed (no date typed). */
data class BinEntry(val name: String, val weekday: Int, val everyWeeks: Int, val anchorDay: Long, val dated: Boolean = true)

/** One stored bin. */
data class BinItem(
    val id: String, val name: String, val weekday: Int, val everyWeeks: Int, val anchorDay: Long, val addedAtMs: Long,
    /** One-off moves (slice 3), any order. */
    val moves: List<BinMove> = emptyList(),
)

/** A row in the Bins pane: "Black bin" · "Every other Tuesday" · "Next: Tue 13 Oct" (or "Tomorrow · a day later for the bank holiday"). */
data class BinRow(val id: String, val name: String, val line: String, val note: String?, val spoken: String)

/** The evening before a collection: Today's header, "Black bin and food caddy out tonight". */
data class BinLine(val text: String, val spoken: String)

data class BinsView(
    val rows: List<BinRow>,
    /** "Next: Black bin and food caddy · Tue 13 Oct", or the empty line. */
    val summary: String,
    /** Slice 3: the one-offs still to come, soonest first: "Black bin" · "Sat 2 Jan instead of Wed 30 Dec" (Remove takes it back). */
    val oneOffs: List<BinRow> = emptyList(),
    /** Completeness: the evening reminder's minute ([BinRules.NOTICE_MIN] until he picks another). */
    val remindMinute: Int = BinRules.NOTICE_MIN,
    /** The reminder's chips, in order (his minute among them). */
    val remindChoices: List<Int> = BinRules.REMIND_CHOICES,
    /** "Reminder the evening before at 19:00 · Today says it from 16:00". */
    val remindLine: String = BinRules.remindLine(BinRules.NOTICE_MIN),
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = BinsView(emptyList(), BinRules.EMPTY_LINE) }
}

/**
 * Bin days completeness: tonight's bins on the Galaxy Watch, from [BinRules.EVE_FROM_MIN] the evening before a
 * collection until midnight — "OUT TONIGHT" · "Black bin and food caddy", lit from his reminder's time.
 */
data class WatchBins(
    /** "OUT TONIGHT", or "BINS" when the usual collection was moved or dropped. */
    val label: String,
    /** From his reminder's time on (bins still to go out): the label is lit. */
    val lit: Boolean,
    /** "Black bin and food caddy" · "No food caddy collection". */
    val line: String,
    /** "a day later for the bank holiday", "Nothing goes out tonight", or null. */
    val note: String?,
    /** The tile's one line: "Bins out tonight · Black bin". */
    val short: String,
    val spoken: String,
)

/** What adding a line did: the line under the field, and whether nothing was written. */
data class BinAdded(val line: String, val refused: Boolean)

object BinRules {
    const val MAX_NAME = 40
    /** Today's line from 16:00 the evening before until midnight. */
    const val EVE_FROM_MIN = 16 * 60
    /** The heads-up at 19:00 the evening before (his choice since completeness), standing until 07:00 on the day. */
    const val NOTICE_MIN = 19 * 60
    /** Completeness: the reminder's chips, 17:00 to 21:00. */
    val REMIND_CHOICES: List<Int> = listOf(17 * 60, 18 * 60, 19 * 60, 20 * 60, 21 * 60)
    /** The `context_mode` entity holding the reminder's minute (ADR-008 addendum 2026-10-19, additive). */
    const val SETTING_ID = "bins_reminder"
    const val REMIND_FIELD = "remindMinute"
    const val STALE_MIN = 7 * 60
    /** The watch's label while bins go out tonight. */
    const val OUT_LABEL = "OUT TONIGHT"

    const val ADD_HINT = "Add… (Black bin Tue fortnightly from 13 Oct, Food caddy every Tue)"
    const val EMPTY_LINE = "Nothing entered yet. Type each bin once with its day, and for every-other-week bins the date of one collection."
    const val NOT_READ = "Navilife couldn't read that. Try \"Black bin Tue fortnightly from 13 Oct\" or \"Food caddy every Tuesday\"."
    const val SHIFT_NOTE = "a day later for the bank holiday"
    const val COUNCIL = "Typed by you. Christmas weeks: type a one-off, \"Black bin Sat 2 Jan instead of 29 Dec\" or \"No black bin 29 Dec\". Your council's own collection days can fill this later."
    /** A one-off moves a collection at most this many days either way. */
    const val MOVE_MAX_DAYS = 14
    private val SKIP_WORDS = setOf("no", "skip", "skipped", "cancelled", "canceled", "none", "not")

    private val DAY_WORDS = mapOf(
        "mon" to 1, "monday" to 1, "mondays" to 1, "tue" to 2, "tues" to 2, "tuesday" to 2, "tuesdays" to 2,
        "wed" to 3, "weds" to 3, "wednesday" to 3, "wednesdays" to 3, "thu" to 4, "thur" to 4, "thurs" to 4,
        "thursday" to 4, "thursdays" to 4, "fri" to 5, "friday" to 5, "fridays" to 5, "sat" to 6, "saturday" to 6,
        "saturdays" to 6, "sun" to 7, "sunday" to 7, "sundays" to 7,
    )
    private val FILLER = setOf(
        "on", "every", "each", "from", "next", "starting", "start", "starts", "collected", "collection", "collections",
        "goes", "go", "out", "is", "the", "a", "week", "weeks", "weekly", "fortnightly", "fortnight", "other", "alternate",
        "alternating", "2", "two", "1", "one", "day", "days", "and", "of", "by",
    )

    /** Words by hand (letters, digits, apostrophes), so Kotlin/Native's regexes can't read them differently. */
    private fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '\'' || c == '’') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** 2 for "fortnightly", "every other", "every 2 weeks", "alternate"; else 1. */
    private fun everyWeeks(lower: List<String>): Int {
        if (lower.any { it == "fortnightly" || it == "fortnight" || it == "alternate" || it == "alternating" }) return 2
        for (i in 0 until lower.size - 1) {
            if (lower[i] == "every" && lower[i + 1] == "other") return 2
            if ((lower[i] == "2" || lower[i] == "two") && lower[i + 1].startsWith("week")) return 2
        }
        return 1
    }

    /** "black" → "Black bin", "food waste" → "Food caddy", "garden" → "Garden waste"; else the words as typed. */
    fun tidyName(raw: String): String {
        val w = words(raw)
        val lower = w.map { it.lowercase() }
        val name = when {
            lower.isEmpty() -> ""
            "food" in lower || "caddy" in lower -> "Food caddy"
            "garden" in lower -> "Garden waste"
            lower.size == 1 && lower[0] in setOf("black", "blue", "green", "brown", "grey", "gray", "purple", "red", "orange") -> "${w[0]} bin"
            lower == listOf("recycling") -> "Recycling"
            else -> w.joinToString(" ")
        }
        return name.take(MAX_NAME).trim().replaceFirstChar { it.uppercase() }
    }

    /**
     * What one typed line says, or null when it names no day MEKA can read or no bin. A date ("13 Oct") fixes the
     * weekday and, for an every-other-week bin, which week; with only a weekday, a fortnightly bin starts at its next
     * one ([BinEntry.dated] false: the row asks to type a date if that's the other week).
     */
    fun read(text: String, today: Long): BinEntry? {
        var s = SchoolRules.tidy(text)
        if (s.isEmpty()) return null
        var anchor: Long? = null
        when (val found = SchoolRules.findSpan(s, today)) {
            SpanFound.Bad -> return null
            is SpanFound.Days -> { if (found.startDay != found.endDay) return null; anchor = found.startDay; s = found.rest }
            SpanFound.None -> Unit
        }
        val w = words(s)
        val lower = w.map { it.lowercase() }
        val every = everyWeeks(lower)
        val typedDay = lower.firstNotNullOfOrNull { DAY_WORDS[it] }
        val weekday = anchor?.let { CivilDate.isoDayOfWeek(it) } ?: typedDay ?: return null
        val nameWords = w.filterIndexed { i, _ -> lower[i] !in FILLER && lower[i] !in DAY_WORDS }
        val name = tidyName(nameWords.joinToString(" "))
        if (name.isEmpty()) return null
        val first = anchor ?: (today + (weekday - CivilDate.isoDayOfWeek(today)).mod(7))
        return BinEntry(name, weekday, every, first, dated = anchor != null || every == 1)
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** What a one-off line (slice 3) says: which bins, the collection it changes, and where it goes (null: no collection). */
    sealed interface MoveRead {
        /** Not a one-off: read it as a bin's pattern. */
        data object NotAMove : MoveRead
        data class Refused(val line: String) : MoveRead
        data class Move(val bins: List<BinItem>, val fromDays: List<Long>, val toDay: Long?) : MoveRead
    }

    const val MOVE_NOT_READ = "Navilife couldn't read that one-off. Try \"Black bin Sat 2 Jan instead of 29 Dec\" or \"No black bin 29 Dec\"."

    private val MOVE_FILLER = FILLER + setOf("instead", "moved", "move", "moves", "to", "collection", "bin's", "bins", "this", "time", "only", "once", "one", "off", "oneoff")

    /** The bins a one-off names ("Black bin and food caddy", "black"), or null for a name no bin has; empty when it names none. */
    private fun namedBins(words: List<String>, bins: List<BinItem>): List<BinItem>? {
        val groups = mutableListOf(mutableListOf<String>())
        for (w in words) if (w.lowercase() == "and" || w == "&") groups += mutableListOf<String>() else groups.last() += w
        val out = mutableListOf<BinItem>()
        for (g in groups) {
            val kept = g.filter { it.lowercase() !in MOVE_FILLER && it.lowercase() !in SKIP_WORDS && it.lowercase() !in DAY_WORDS }
            if (kept.isEmpty()) continue
            val name = tidyName(kept.joinToString(" "))
            out += bins.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        }
        return out.distinct()
    }

    /**
     * A one-off typed in Bins (slice 3): "Black bin Sat 2 Jan instead of 29 Dec" moves one collection, "No black bin
     * 29 Dec" (or "skip", "cancelled") drops one; with no bin named, every bin collected that day. The old day must be
     * one of the bin's collections (the pattern's day or its bank-holiday day); a move goes at most [MOVE_MAX_DAYS]
     * either way. Anything else is [MoveRead.NotAMove] and read as a pattern.
     */
    fun readMove(text: String, today: Long, bins: List<BinItem>, holidays: HolidayCalendar): MoveRead {
        val s = SchoolRules.tidy(text)
        val lowerAll = words(s).map { it.lowercase() }
        val at = s.lowercase().indexOf("instead of")
        val skip = at < 0 && (lowerAll.any { it in SKIP_WORDS } || "no collection" in s.lowercase())
        if (at < 0 && !skip) return MoveRead.NotAMove
        val toDay: Long?
        val fromDay: Long
        val nameText: String
        if (at >= 0) {
            val left = SchoolRules.findSpan(s.substring(0, at), today)
            val right = SchoolRules.findSpan(s.substring(at + "instead of".length), today)
            if (left !is SpanFound.Days || right !is SpanFound.Days || left.startDay != left.endDay || right.startDay != right.endDay) {
                return MoveRead.Refused(MOVE_NOT_READ)
            }
            toDay = left.startDay; fromDay = right.startDay
            nameText = left.rest + " " + right.rest
        } else {
            val found = SchoolRules.findSpan(s, today)
            if (found !is SpanFound.Days || found.startDay != found.endDay) return MoveRead.Refused(MOVE_NOT_READ)
            toDay = null; fromDay = found.startDay; nameText = found.rest
        }
        if (toDay != null && (toDay == fromDay || kotlin.math.abs(toDay - fromDay) > MOVE_MAX_DAYS)) {
            return MoveRead.Refused("A one-off moves a collection to another day up to two weeks either way.")
        }
        val named = namedBins(words(nameText), bins)
            ?: return MoveRead.Refused("No bin by that name yet. Your bins: ${bins.joinToString(", ") { it.name }.ifEmpty { "none" }}.")
        val wk = monday(fromDay)
        fun scheduled(b: BinItem): Long? = collectionIn(b, wk, holidays)?.takeIf { it == fromDay || wk + b.weekday - 1 == fromDay }
        val chosen = named.ifEmpty { bins.filter { scheduled(it) != null } }
        if (chosen.isEmpty()) return MoveRead.Refused("No bin is collected on ${CivilDate.shortLabel(fromDay)}.")
        for (b in chosen) {
            if (scheduled(b) == null) {
                val near = next(b.copy(moves = emptyList()), wk, holidays)
                return MoveRead.Refused("${b.name} isn't collected on ${CivilDate.shortLabel(fromDay)}" +
                    (near?.let { " · that week's is ${CivilDate.shortLabel(it)}" } ?: "") + ".")
            }
        }
        return MoveRead.Move(chosen, chosen.map { scheduled(it)!! }, toDay)
    }

    /** "Moved Black bin to Sat 2 Jan instead of Wed 30 Dec" / "No collection for black bin on Tue 29 Dec". */
    fun movedLine(bins: List<BinItem>, fromDay: Long, toDay: Long?): String =
        toDay?.let { "Moved ${names(bins)} to ${CivilDate.shortLabel(it)} instead of ${CivilDate.shortLabel(fromDay)}" }
            ?: "No collection for ${names(bins).replaceFirstChar { it.lowercase() }} on ${CivilDate.shortLabel(fromDay)}"

    /** The one-offs from each `bin_move` entity's fields, by bin; removed and unreadable ones are skipped. */
    fun moves(entities: Map<String, Map<String, FieldValue>>): Map<String, List<BinMove>> = entities.mapNotNull { (id, f) ->
        if (f.f(BinMoveFields.DELETED).boolOrNull == true) return@mapNotNull null
        val bin = f.f(BinMoveFields.BIN).textOrNull ?: return@mapNotNull null
        val from = f.f(BinMoveFields.FROM).longOrNull ?: return@mapNotNull null
        val skipped = f.f(BinMoveFields.SKIPPED).boolOrNull == true
        val to = if (skipped) null else f.f(BinMoveFields.TO).longOrNull ?: return@mapNotNull null
        BinMove(id, bin, from, to)
    }.sortedBy { it.id }.groupBy { it.binId }

    /** The live bins from each entity's fields; unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<BinItem> = entities.mapNotNull { (id, s) ->
        if (s.f(BinFields.DELETED).boolOrNull == true) return@mapNotNull null
        val name = s.f(BinFields.NAME).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val weekday = (s.f(BinFields.WEEKDAY).longOrNull ?: return@mapNotNull null).toInt()
        if (weekday !in 1..7) return@mapNotNull null
        val every = (s.f(BinFields.EVERY_WEEKS).longOrNull ?: 1L).toInt().coerceIn(1, 4)
        val anchor = s.f(BinFields.ANCHOR).longOrNull ?: return@mapNotNull null
        BinItem(id, name, weekday, every, anchor, s.f(BinFields.ADDED_AT).longOrNull ?: 0L)
    }.sortedWith(compareBy<BinItem> { it.addedAtMs }.thenBy { it.name.lowercase() }.thenBy { it.id })

    fun fields(e: BinEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        BinFields.NAME to e.name.fv(),
        BinFields.WEEKDAY to e.weekday.toLong().fv(),
        BinFields.EVERY_WEEKS to e.everyWeeks.toLong().fv(),
        BinFields.ANCHOR to e.anchorDay.fv(),
        BinFields.ADDED_AT to nowMs.fv(),
        BinFields.DELETED to false.fv(),
    )

    private fun monday(day: Long): Long = day - (CivilDate.isoDayOfWeek(day) - 1)

    /**
     * The bin's collection in the week starting [weekMonday], or null when it doesn't go that week. A bank holiday on
     * a weekday on or before the collection day moves it a day later for each (never past Saturday); a weekend
     * collection doesn't move.
     */
    fun collectionIn(bin: BinItem, weekMonday: Long, holidays: HolidayCalendar): Long? {
        val weeks = (weekMonday - monday(bin.anchorDay)) / 7
        if (weeks.mod(bin.everyWeeks.toLong()) != 0L) return null
        val scheduled = weekMonday + bin.weekday - 1
        if (bin.weekday > 5) return scheduled
        val shift = (weekMonday..scheduled).count { holidays.isHoliday(it) }
        return minOf(scheduled + shift, weekMonday + 5)
    }

    /** The collection the pattern makes in [weekMonday]'s week (as [collectionIn]) and the one-off that changes it, if any. */
    private fun moveFor(bin: BinItem, weekMonday: Long, scheduled: Long): BinMove? =
        bin.moves.firstOrNull { it.fromDay == scheduled || it.fromDay == weekMonday + bin.weekday - 1 }

    /**
     * Every collection of [bin] from [from] to [to] (both counted), soonest first: the pattern with its bank-holiday
     * shifts, then the one-offs (slice 3) moving or dropping single collections.
     */
    fun collections(bin: BinItem, from: Long, to: Long, holidays: HolidayCalendar): List<Long> {
        val out = mutableSetOf<Long>()
        var wk = monday(from - MOVE_MAX_DAYS)
        val last = to + MOVE_MAX_DAYS
        while (wk <= last) {
            val s = collectionIn(bin, wk, holidays)
            if (s != null) {
                val move = moveFor(bin, wk, s)
                val day = if (move != null) move.toDay else s
                if (day != null && day in from..to) out += day
            }
            wk += 7
        }
        return out.sorted()
    }

    /** What's unusual about [bin]'s collection on [day]: "moved from Tue 29 Dec", the bank-holiday note, or null. */
    fun note(bin: BinItem, day: Long): String? {
        bin.moves.firstOrNull { it.toDay == day && it.fromDay != day }?.let { return "moved from ${CivilDate.shortLabel(it.fromDay)}" }
        return if (shifted(bin, day)) SHIFT_NOTE else null
    }

    /** The notes of the bins going on [day], " · " first; empty when none. */
    private fun notes(going: List<BinItem>, day: Long): String =
        going.mapNotNull { note(it, day) }.distinct().joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }

    /** Whether the bin's collection that week was moved by a bank holiday. */
    fun shifted(bin: BinItem, day: Long): Boolean = CivilDate.isoDayOfWeek(day) != bin.weekday

    /** The bin's next collection from [from] on (today counts). */
    fun next(bin: BinItem, from: Long, holidays: HolidayCalendar): Long? = collections(bin, from, from + 42, holidays).firstOrNull()

    /** The bins collected on [day], in the order entered. */
    fun onDay(bins: List<BinItem>, day: Long, holidays: HolidayCalendar): List<BinItem> =
        bins.filter { day in collections(it, day, day, holidays) }

    /**
     * Bins whose usual collection on [day] a one-off moved or dropped (slice 3), with what happened:
     * "Black bin moved to Sat 2 Jan", "No black bin collection".
     */
    fun heldBack(bins: List<BinItem>, day: Long, holidays: HolidayCalendar): List<Pair<BinItem, String>> = bins.mapNotNull { b ->
        val wk = monday(day)
        val s = collectionIn(b, wk, holidays)?.takeIf { it == day } ?: return@mapNotNull null
        val m = moveFor(b, wk, s) ?: return@mapNotNull null
        if (m.toDay == day) return@mapNotNull null
        b to (m.toDay?.let { "${b.name} moved to ${CivilDate.shortLabel(it)}" } ?: "No ${b.name.replaceFirstChar { it.lowercase() }} collection")
    }

    /** "Black bin", "Black bin and food caddy", "Black bin, blue bin and food caddy" (later names lower-cased). */
    fun names(bins: List<BinItem>): String {
        val n = bins.mapIndexed { i, b -> if (i == 0) b.name else b.name.replaceFirstChar { it.lowercase() } }
        return if (n.size <= 1) n.joinToString() else n.dropLast(1).joinToString(", ") + " and " + n.last()
    }

    /** "Every Tuesday", "Every other Tuesday". */
    fun patternLabel(bin: BinItem): String =
        (if (bin.everyWeeks == 2) "Every other " else if (bin.everyWeeks > 2) "Every ${bin.everyWeeks} weeks on " else "Every ") +
            CivilDate.DAY_LONG[bin.weekday - 1]

    private fun dayWord(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today + 1 -> "Tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** The Bins pane. */
    fun view(bins: List<BinItem>, today: Long, holidays: HolidayCalendar, remindMinute: Int = NOTICE_MIN): BinsView {
        val choices = (REMIND_CHOICES + remindMinute).distinct().sorted()
        if (bins.isEmpty()) return BinsView.EMPTY.copy(remindMinute = remindMinute, remindChoices = choices, remindLine = remindLine(remindMinute))
        val rows = bins.map { b ->
            val next = next(b, today, holidays)
            val note = next?.let { "Next: ${dayWord(it, today)}" + (note(b, it)?.let { n -> " · $n" } ?: "") }
            val line = patternLabel(b)
            BinRow(b.id, b.name, line, note, listOfNotNull(b.name, line, note).joinToString(". "))
        }
        val firstDay = bins.mapNotNull { next(it, today, holidays) }.minOrNull()
        val summary = firstDay?.let { "Next: ${names(onDay(bins, it, holidays))} · ${dayWord(it, today)}" } ?: EMPTY_LINE
        return BinsView(rows, summary, oneOffRows(bins, today), remindMinute, choices, remindLine(remindMinute))
    }

    /** A reminder minute he may pick: a quarter hour from 16:00 (Today's line) to 22:00. */
    fun validRemind(minute: Int): Boolean = minute in EVE_FROM_MIN..22 * 60 && minute % 15 == 0

    /** The stored minute, or [NOTICE_MIN] when none (or not a valid one) is stored. */
    fun readRemind(fields: Map<String, FieldValue>?): Int =
        fields?.get(REMIND_FIELD)?.longOrNull?.toInt()?.takeIf { validRemind(it) } ?: NOTICE_MIN

    /** "Reminder the evening before at 19:00 · Today says it from 16:00". */
    fun remindLine(minute: Int): String =
        "Reminder the evening before at ${LocalClock.formatMinute(minute)} · Today says it from ${LocalClock.formatMinute(EVE_FROM_MIN)}"

    /** "Reminder at 20:00 · on both devices" after a chip. */
    fun remindSaid(minute: Int): String = "Reminder at ${LocalClock.formatMinute(minute)} · on both devices"

    /**
     * Completeness: tonight's bins on the watch, from [EVE_FROM_MIN] the evening before a collection until midnight
     * (bins go out the evening before), lit from [remindMinute]; a one-off that moved or dropped tomorrow's usual
     * collection says so. Null on other evenings and during the day.
     */
    fun watch(bins: List<BinItem>, today: Long, minuteOfDay: Int, holidays: HolidayCalendar, remindMinute: Int = NOTICE_MIN): WatchBins? {
        val tonight = tonightLine(bins, today, minuteOfDay, holidays) ?: return null
        val going = onDay(bins, today + 1, holidays)
        val held = heldBack(bins, today + 1, holidays).map { it.second }
        if (going.isEmpty()) {
            return WatchBins("BINS", false, held.first(), (held.drop(1) + "Nothing goes out tonight").joinToString(" · "),
                "Bins · not out tonight", tonight.spoken)
        }
        val note = (listOfNotNull(notes(going, today + 1).removePrefix(" · ").takeIf { it.isNotEmpty() }) + held)
            .joinToString(" · ").takeIf { it.isNotEmpty() }
        return WatchBins(OUT_LABEL, minuteOfDay >= remindMinute, names(going), note, "Bins out tonight · ${names(going)}", tonight.spoken)
    }

    /**
     * Bin days completeness 2: minutes from [minuteOfDay] until the watch's bins row next changes — his reminder's time
     * while bins still to go out aren't lit yet, else midnight (when the row goes) — so the tile is redrawn on time even
     * in quiet hours' hourly rest. Null while there's no row ([w] null).
     */
    fun watchChangeInMin(w: WatchBins?, minuteOfDay: Int, remindMinute: Int = NOTICE_MIN): Int? {
        if (w == null) return null
        val at = if (!w.lit && w.label == OUT_LABEL && minuteOfDay < remindMinute) remindMinute else 24 * 60
        return (at - minuteOfDay).coerceAtLeast(1)
    }

    /** "Sat 2 Jan instead of Wed 30 Dec" / "No collection on Tue 29 Dec". */
    fun moveLabel(m: BinMove): String =
        m.toDay?.let { "${CivilDate.shortLabel(it)} instead of ${CivilDate.shortLabel(m.fromDay)}" } ?: "No collection on ${CivilDate.shortLabel(m.fromDay)}"

    /** The one-offs not yet past, soonest first (by the day they change). */
    fun oneOffRows(bins: List<BinItem>, today: Long): List<BinRow> = bins.flatMap { b -> b.moves.map { b to it } }
        .filter { (_, m) -> maxOf(m.fromDay, m.toDay ?: m.fromDay) >= today }
        .sortedWith(compareBy<Pair<BinItem, BinMove>> { it.second.fromDay }.thenBy { it.first.name.lowercase() })
        .map { (b, m) -> BinRow(m.id, b.name, moveLabel(m), null, "${b.name}. One-off. ${moveLabel(m)}") }

    /** Today's header the evening before (from [EVE_FROM_MIN]): "Black bin and food caddy out tonight"; null otherwise. */
    fun tonightLine(bins: List<BinItem>, today: Long, minuteOfDay: Int, holidays: HolidayCalendar): BinLine? {
        if (minuteOfDay < EVE_FROM_MIN) return null
        val going = onDay(bins, today + 1, holidays)
        val held = heldBack(bins, today + 1, holidays).map { it.second }
        if (going.isEmpty()) {
            // Slice 3: the usual collection tomorrow was moved or dropped, so nothing goes out by habit.
            if (held.isEmpty()) return null
            val text = held.joinToString(" · ") + " · not out tonight"
            return BinLine(text, held.joinToString(". ") + ". Nothing goes out tonight.")
        }
        val extra = notes(going, today + 1)
        val heldText = held.joinToString("") { " · ${it.replaceFirstChar { c -> c.lowercase() }}" }
        val text = "${names(going)} out tonight$extra$heldText"
        val spokenExtra = (if (extra.isEmpty()) "" else ", ${extra.removePrefix(" · ").replace(" · ", ", ")}") +
            held.joinToString("") { ". $it" }
        return BinLine(text, "${names(going)} ${if (going.size == 1) "goes" else "go"} out tonight$spokenExtra.")
    }

    /** The week ahead's line on a collection day: "Black bin and food caddy" (with the bank-holiday note). */
    fun weekLine(bins: List<BinItem>, day: Long, holidays: HolidayCalendar): String? {
        val going = onDay(bins, day, holidays).takeIf { it.isNotEmpty() } ?: return null
        return names(going) + notes(going, day)
    }

    /**
     * The heads-up the evening before each collection in the coming week (source [NoticeSource.BINS]) at
     * [NOTICE_MIN], through the governor so quiet hours and digests apply, standing until [STALE_MIN] on the day:
     * "Black bin and food caddy out tonight" · "Collected tomorrow, Tue 13 Oct".
     */
    fun notices(bins: List<BinItem>, nowMs: Long, cal: LocalCalendar, holidays: HolidayCalendar, remindMinute: Int = NOTICE_MIN): List<Notice> {
        if (bins.isEmpty()) return emptyList()
        val today = cal.epochDayOf(nowMs)
        return (today..today + 7).mapNotNull { day ->
            val going = onDay(bins, day, holidays).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Notice(
                key = "bins:$day", source = NoticeSource.BINS, tier = NoticeTier.HEADS_UP,
                title = "${names(going)} out tonight",
                text = "Collected tomorrow, ${CivilDate.shortLabel(day)}" + notes(going, day),
                atMs = cal.toEpochMs(day - 1, remindMinute.takeIf { validRemind(it) } ?: NOTICE_MIN), target = NoticeTarget.TODAY,
                expiresAtMs = cal.toEpochMs(day, STALE_MIN),
            )
        }
    }

    /** At most this many bins go with a question (slice 2), in the order entered. */
    const val ASK_BINS = 6

    /** The coming collection days that go with a question. */
    const val ASK_DAYS = 4

    private fun relation(day: Long, today: Long): String? = when {
        day == today -> "today"
        day == today + 1 -> "tomorrow"
        monday(day) == monday(today) -> "this week"
        monday(day) == monday(today) + 7 -> "next week"
        else -> null
    }

    private fun dated(day: Long, today: Long): String = CivilDate.shortLabel(day) + (relation(day, today)?.let { " ($it)" } ?: "")

    /**
     * The bins as MEKA describes them to the model (slice 2, Ask's `bins` lines), so "which bin is it this week?" and
     * "when are the bins?" have an answer: "Bins · 2 bins · typed by Meka", then each bin ("Black bin · every other
     * Tuesday · next Tue 13 Oct (tomorrow) · then Tue 27 Oct"), then the coming collection days ("Collection · Tue
     * 13 Oct (tomorrow) · Black bin and food caddy · out the evening before", with the bank-holiday note). Meka's own
     * typed lines in MEKA's words, never untrusted. Nothing while no bins are entered.
     */
    fun askLines(bins: List<BinItem>, today: Long, holidays: HolidayCalendar): List<String> {
        if (bins.isEmpty()) return emptyList()
        val more = if (bins.size > ASK_BINS) " · the first $ASK_BINS below" else ""
        val head = "Bins · ${bins.size} ${if (bins.size == 1) "bin" else "bins"} · typed by Meka$more"
        val each = bins.take(ASK_BINS).map { b ->
            val next = next(b, today, holidays)
            val then = next?.let { next(b, it + 1, holidays) }
            listOfNotNull(
                b.name, patternLabel(b).replaceFirstChar { it.lowercase() },
                next?.let { "next ${dated(it, today)}" + (note(b, it)?.let { n -> " · $n" } ?: "") },
                then?.let { "then ${CivilDate.shortLabel(it)}" },
            ).joinToString(" · ")
        }
        val days = bins.mapNotNull { b -> next(b, today, holidays)?.let { listOf(it) + listOfNotNull(next(b, it + 1, holidays)) } }
            .flatten().distinct().sorted().take(ASK_DAYS)
        val collections = days.map { day ->
            val going = onDay(bins, day, holidays)
            "Collection · ${dated(day, today)} · ${names(going)} · out the evening before" + notes(going, day)
        }
        val oneOffs = bins.flatMap { b -> b.moves.map { b to it } }
            .filter { (_, m) -> maxOf(m.fromDay, m.toDay ?: m.fromDay) >= today }
            .sortedBy { it.second.fromDay }.take(ASK_DAYS)
            .map { (b, m) -> "One-off · ${b.name} · ${moveLabel(m)}" }
        return listOf(head) + each + collections + oneOffs
    }

    /** The line after adding:"Added Black bin · every other Tuesday · next Tue 13 Oct" (and the week check when guessed). */
    fun addedLine(e: BinEntry, today: Long, replaced: Boolean): String {
        val pattern = (if (e.everyWeeks == 2) "every other " else "every ") + CivilDate.DAY_LONG[e.weekday - 1]
        val next = dayWord(e.anchorDay, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }
        val base = "${if (replaced) "Updated" else "Added"} ${e.name} · $pattern · next $next"
        return if (e.dated) base else "$base · wrong week? Type it again with a collection date"
    }
}

/** The bins on a replica. */
class Bins(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<BinItem> {
        val moves = BinRules.moves(replica.entities(EntityTypes.BIN_MOVE).associate { it.ref.entityId to it.fields })
        return BinRules.items(replica.entities(EntityTypes.BIN).associate { it.ref.entityId to it.fields })
            .map { b -> moves[b.id]?.let { b.copy(moves = it) } ?: b }
    }

    fun view(holidays: HolidayCalendar): BinsView = BinRules.view(items(), calendar.epochDayOf(nowMs()), holidays, remindMinute())

    /** Completeness: the evening reminder's minute (synced, both devices). */
    fun remindMinute(): Int = BinRules.readRemind(replica.entity(EntityTypes.CONTEXT_MODE, BinRules.SETTING_ID)?.fields)

    /** Sets the reminder's minute; false (nothing written) when it isn't one he may pick or nothing changes. */
    fun setRemind(minute: Int): Boolean {
        if (!BinRules.validRemind(minute)) return false
        if (replica.entity(EntityTypes.CONTEXT_MODE, BinRules.SETTING_ID) != null && remindMinute() == minute) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, BinRules.SETTING_ID, mapOf(BinRules.REMIND_FIELD to minute.toLong().fv()))
        return true
    }

    /** Adds (or, for a bin already there by name, updates) what [text] says; refused when MEKA couldn't read it. */
    fun add(text: String, holidays: HolidayCalendar = HolidayCalendar.NONE): BinAdded {
        val today = calendar.epochDayOf(nowMs())
        val bins = items()
        when (val m = BinRules.readMove(text, today, bins, holidays)) {
            BinRules.MoveRead.NotAMove -> Unit
            is BinRules.MoveRead.Refused -> return BinAdded(m.line, refused = true)
            is BinRules.MoveRead.Move -> {
                m.bins.forEachIndexed { i, b ->
                    val from = m.fromDays[i]
                    // The same bin and day again changes that one-off rather than adding a second.
                    val id = b.moves.firstOrNull { it.fromDay == from }?.id ?: newId()
                    replica.commitLocal(EntityTypes.BIN_MOVE, id, mapOf(
                        BinMoveFields.BIN to b.id.fv(),
                        BinMoveFields.FROM to from.fv(),
                        BinMoveFields.TO to (m.toDay ?: from).fv(),
                        BinMoveFields.SKIPPED to (m.toDay == null).fv(),
                        BinMoveFields.ADDED_AT to nowMs().fv(),
                        BinMoveFields.DELETED to false.fv(),
                    ))
                }
                return BinAdded(BinRules.movedLine(m.bins, m.fromDays.first(), m.toDay), refused = false)
            }
        }
        val e = BinRules.read(text, today) ?: return BinAdded(BinRules.NOT_READ, refused = true)
        val existing = bins.firstOrNull { it.name.equals(e.name, ignoreCase = true) }
        val id = existing?.id ?: newId()
        val fields = BinRules.fields(e, nowMs()).let { f -> if (existing != null) f - BinFields.ADDED_AT else f }
        replica.commitLocal(EntityTypes.BIN, id, fields)
        return BinAdded(BinRules.addedLine(e, today, replaced = existing != null), refused = false)
    }

    /** Removes [id] for good; false when it isn't there. */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.BIN, id)?.takeIf { it[BinFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.BIN, id, mapOf(BinFields.DELETED to true.fv()))
        return true
    }

    /** Takes back a one-off (slice 3): the collection goes on its usual day again. False when it isn't there. */
    fun removeMove(id: String): Boolean {
        replica.entity(EntityTypes.BIN_MOVE, id)?.takeIf { it[BinMoveFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.BIN_MOVE, id, mapOf(BinMoveFields.DELETED to true.fv()))
        return true
    }
}
