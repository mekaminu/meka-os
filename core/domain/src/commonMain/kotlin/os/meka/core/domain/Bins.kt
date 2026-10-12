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
 * One synced `bin` entity per bin (ADR-008 addendum 2026-10-12). Central Bedfordshire Council's own collection days
 * (a later slice) would fill the same entities.
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

/** What one typed line says ([BinRules.read]). [dated] is false when a fortnightly bin's week was guessed (no date typed). */
data class BinEntry(val name: String, val weekday: Int, val everyWeeks: Int, val anchorDay: Long, val dated: Boolean = true)

/** One stored bin. */
data class BinItem(val id: String, val name: String, val weekday: Int, val everyWeeks: Int, val anchorDay: Long, val addedAtMs: Long)

/** A row in the Bins pane: "Black bin" · "Every other Tuesday" · "Next: Tue 13 Oct" (or "Tomorrow · a day later for the bank holiday"). */
data class BinRow(val id: String, val name: String, val line: String, val note: String?, val spoken: String)

/** The evening before a collection: Today's header, "Black bin and food caddy out tonight". */
data class BinLine(val text: String, val spoken: String)

data class BinsView(
    val rows: List<BinRow>,
    /** "Next: Black bin and food caddy · Tue 13 Oct", or the empty line. */
    val summary: String,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = BinsView(emptyList(), BinRules.EMPTY_LINE) }
}

/** What adding a line did: the line under the field, and whether nothing was written. */
data class BinAdded(val line: String, val refused: Boolean)

object BinRules {
    const val MAX_NAME = 40
    /** Today's line from 16:00 the evening before until midnight. */
    const val EVE_FROM_MIN = 16 * 60
    /** The heads-up at 19:00 the evening before, standing until 07:00 on the day (bins out by then). */
    const val NOTICE_MIN = 19 * 60
    const val STALE_MIN = 7 * 60

    const val ADD_HINT = "Add… (Black bin Tue fortnightly from 13 Oct, Food caddy every Tue)"
    const val EMPTY_LINE = "Nothing entered yet. Type each bin once with its day, and for every-other-week bins the date of one collection."
    const val NOT_READ = "MEKA couldn't read that. Try \"Black bin Tue fortnightly from 13 Oct\" or \"Food caddy every Tuesday\"."
    const val SHIFT_NOTE = "a day later for the bank holiday"
    const val COUNCIL = "Typed by you. Central Bedfordshire's own collection days can fill this later; Christmas weeks may differ, so check the council's dates then."

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

    /** Whether the bin's collection that week was moved by a bank holiday. */
    fun shifted(bin: BinItem, day: Long): Boolean = CivilDate.isoDayOfWeek(day) != bin.weekday

    /** The bin's next collection from [from] on (today counts). */
    fun next(bin: BinItem, from: Long, holidays: HolidayCalendar): Long? {
        var wk = monday(from)
        repeat(6) {
            collectionIn(bin, wk, holidays)?.let { if (it >= from) return it }
            wk += 7
        }
        return null
    }

    /** The bins collected on [day], in the order entered. */
    fun onDay(bins: List<BinItem>, day: Long, holidays: HolidayCalendar): List<BinItem> =
        bins.filter { collectionIn(it, monday(day), holidays) == day }

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
    fun view(bins: List<BinItem>, today: Long, holidays: HolidayCalendar): BinsView {
        if (bins.isEmpty()) return BinsView.EMPTY
        val rows = bins.map { b ->
            val next = next(b, today, holidays)
            val note = next?.let { "Next: ${dayWord(it, today)}" + if (shifted(b, it)) " · $SHIFT_NOTE" else "" }
            val line = patternLabel(b)
            BinRow(b.id, b.name, line, note, listOfNotNull(b.name, line, note).joinToString(". "))
        }
        val firstDay = bins.mapNotNull { next(it, today, holidays) }.minOrNull()
        val summary = firstDay?.let { "Next: ${names(onDay(bins, it, holidays))} · ${dayWord(it, today)}" } ?: EMPTY_LINE
        return BinsView(rows, summary)
    }

    /** Today's header the evening before (from [EVE_FROM_MIN]): "Black bin and food caddy out tonight"; null otherwise. */
    fun tonightLine(bins: List<BinItem>, today: Long, minuteOfDay: Int, holidays: HolidayCalendar): BinLine? {
        if (minuteOfDay < EVE_FROM_MIN) return null
        val going = onDay(bins, today + 1, holidays).takeIf { it.isNotEmpty() } ?: return null
        val late = going.any { shifted(it, today + 1) }
        val text = "${names(going)} out tonight" + if (late) " · $SHIFT_NOTE" else ""
        return BinLine(text, "${names(going)} ${if (going.size == 1) "goes" else "go"} out tonight" + if (late) ", $SHIFT_NOTE." else ".")
    }

    /** The week ahead's line on a collection day: "Black bin and food caddy" (with the bank-holiday note). */
    fun weekLine(bins: List<BinItem>, day: Long, holidays: HolidayCalendar): String? {
        val going = onDay(bins, day, holidays).takeIf { it.isNotEmpty() } ?: return null
        return names(going) + if (going.any { shifted(it, day) }) " · $SHIFT_NOTE" else ""
    }

    /**
     * The heads-up the evening before each collection in the coming week (source [NoticeSource.BINS]) at
     * [NOTICE_MIN], through the governor so quiet hours and digests apply, standing until [STALE_MIN] on the day:
     * "Black bin and food caddy out tonight" · "Collected tomorrow, Tue 13 Oct".
     */
    fun notices(bins: List<BinItem>, nowMs: Long, cal: LocalCalendar, holidays: HolidayCalendar): List<Notice> {
        if (bins.isEmpty()) return emptyList()
        val today = cal.epochDayOf(nowMs)
        return (today..today + 7).mapNotNull { day ->
            val going = onDay(bins, day, holidays).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val late = going.any { shifted(it, day) }
            Notice(
                key = "bins:$day", source = NoticeSource.BINS, tier = NoticeTier.HEADS_UP,
                title = "${names(going)} out tonight",
                text = "Collected tomorrow, ${CivilDate.shortLabel(day)}" + if (late) " · $SHIFT_NOTE" else "",
                atMs = cal.toEpochMs(day - 1, NOTICE_MIN), target = NoticeTarget.TODAY,
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
                next?.let { "next ${dated(it, today)}" + if (shifted(b, it)) " · $SHIFT_NOTE" else "" },
                then?.let { "then ${CivilDate.shortLabel(it)}" },
            ).joinToString(" · ")
        }
        val days = bins.mapNotNull { b -> next(b, today, holidays)?.let { listOf(it) + listOfNotNull(next(b, it + 1, holidays)) } }
            .flatten().distinct().sorted().take(ASK_DAYS)
        val collections = days.map { day ->
            val going = onDay(bins, day, holidays)
            "Collection · ${dated(day, today)} · ${names(going)} · out the evening before" +
                if (going.any { shifted(it, day) }) " · $SHIFT_NOTE" else ""
        }
        return listOf(head) + each + collections
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
    fun items(): List<BinItem> = BinRules.items(replica.entities(EntityTypes.BIN).associate { it.ref.entityId to it.fields })

    fun view(holidays: HolidayCalendar): BinsView = BinRules.view(items(), calendar.epochDayOf(nowMs()), holidays)

    /** Adds (or, for a bin already there by name, updates) what [text] says; refused when MEKA couldn't read it. */
    fun add(text: String): BinAdded {
        val today = calendar.epochDayOf(nowMs())
        val e = BinRules.read(text, today) ?: return BinAdded(BinRules.NOT_READ, refused = true)
        val existing = items().firstOrNull { it.name.equals(e.name, ignoreCase = true) }
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
}
