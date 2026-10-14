package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * School dates filled in (build plan, Meka 2026-10-12): a council's published school holidays, offered in the School
 * pane and written into his own synced school items only after he picks the school's council there (rule 13: the table
 * is keyed by council, generic, and nothing about him or the school is in it). Each item keeps where it came from
 * ([SchoolFields.SOURCE]) and when the table was checked against the council's own page ([SchoolFields.CHECKED]), so
 * its row says "Council dates · checked 14 Oct 2026 · check with the school".
 *
 * A council's dates never include a school's own INSET days (schools choose most of their five training days); the
 * training days a council names itself are kept, titled "Training day". His typed days off always win: a council
 * holiday overlapping a day off he typed for both boys is not added, and removing council dates is his to do.
 */
data class TermPeriod(val title: String, val startDay: Long, val endDay: Long)

/** One council's school holidays as published, with the page they were checked against. */
data class TermCalendar(
    /** Stable, generic key ("central-bedfordshire"); stored on each item as "council:<id>". */
    val id: String,
    /** "Central Bedfordshire". */
    val name: String,
    val sourceUrl: String,
    /** The day a build run checked the table against [sourceUrl] (epoch day). */
    val checkedDay: Long,
    /** "2026–27 and 2027–28". */
    val years: String,
    val periods: List<TermPeriod>,
)

/** A council the School pane offers: "Central Bedfordshire" · "2026–27 and 2027–28 · 13 dates · checked 14 Oct 2026". */
data class TermChoice(val id: String, val name: String, val line: String)

/**
 * The School pane's council card. While no council's dates are in: [offer] with [choices] (pick one, then Fill in).
 * Once they are: [filled] names it, [line] says what is in and what isn't, and Remove all takes them out (Undo fills
 * them again).
 */
data class SchoolTermCard(
    val title: String,
    val line: String,
    val choices: List<TermChoice>,
    val filled: TermChoice?,
)

object SchoolTerms {
    private fun d(y: Int, m: Int, day: Int) = CivilDate.toEpochDay(y, m, day)

    /**
     * Central Bedfordshire Council's school term and holiday dates, checked 2026-10-14 against the council's page
     * (centralbedfordshire.gov.uk, "School term and holiday dates in Central Bedfordshire"). Bank holidays inside a
     * holiday are part of it; a bank holiday on its own (early May) is left to the bank-holiday calendar. The council
     * names two training days in 2026–27 (12 Apr, 7 Jun 2027); every other INSET day is the school's own.
     */
    val CENTRAL_BEDFORDSHIRE = TermCalendar(
        id = "central-bedfordshire",
        name = "Central Bedfordshire",
        sourceUrl = "https://www.centralbedfordshire.gov.uk/info/3/schools_and_education/529/school_term_and_holiday_dates_in_central_bedfordshire",
        checkedDay = d(2026, 10, 14),
        years = "2026–27 and 2027–28",
        periods = listOf(
            TermPeriod("October half term", d(2026, 10, 26), d(2026, 10, 30)),
            TermPeriod("Christmas holidays", d(2026, 12, 21), d(2027, 1, 4)),
            TermPeriod("February half term", d(2027, 2, 15), d(2027, 2, 19)),
            TermPeriod("Easter holidays", d(2027, 3, 26), d(2027, 4, 9)),
            TermPeriod("Training day", d(2027, 4, 12), d(2027, 4, 12)),
            TermPeriod("May half term", d(2027, 5, 31), d(2027, 6, 4)),
            TermPeriod("Training day", d(2027, 6, 7), d(2027, 6, 7)),
            TermPeriod("Summer holidays", d(2027, 7, 22), d(2027, 8, 31)),
            TermPeriod("October half term", d(2027, 10, 25), d(2027, 10, 29)),
            TermPeriod("Christmas holidays", d(2027, 12, 20), d(2028, 1, 3)),
            TermPeriod("February half term", d(2028, 2, 14), d(2028, 2, 18)),
            TermPeriod("Easter holidays", d(2028, 4, 3), d(2028, 4, 17)),
            TermPeriod("May half term", d(2028, 5, 29), d(2028, 6, 2)),
            TermPeriod("Summer holidays", d(2028, 7, 22), d(2028, 8, 31)),
        ),
    )

    /** Every council the pane offers, by name. A council is added only once its dates are checked on its own page. */
    val COUNCILS: List<TermCalendar> = listOf(CENTRAL_BEDFORDSHIRE)

    const val SOURCE_PREFIX = "council:"
    const val CARD_TITLE = "Holidays from the council"
    const val OFFER_LINE = "Most schools follow their council's holidays. Pick the boys' school's council to fill them in; " +
        "the school's own INSET days aren't in them. Not listed? Type the dates below."
    const val NOT_LISTED = "Not listed? Type the dates below, one line each."
    const val NOT_FILLED = "Nothing new to add: those holidays are in already."

    fun calendar(id: String): TermCalendar? = COUNCILS.firstOrNull { it.id == id }

    /** The council an item came from, or null for one he typed. */
    fun councilOf(source: String?): TermCalendar? =
        source?.takeIf { it.startsWith(SOURCE_PREFIX) }?.let { calendar(it.removePrefix(SOURCE_PREFIX)) }

    /** "14 Oct 2026". */
    fun checkedLabel(day: Long): String {
        val d = CivilDate.fromEpochDay(day)
        return "${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]} ${d.year}"
    }

    /** The row's source line: "Council dates · checked 14 Oct 2026 · check with the school". */
    fun sourceLine(item: SchoolItem): String? {
        councilOf(item.source) ?: return null
        val checked = item.checkedDay?.let { " · checked ${checkedLabel(it)}" }.orEmpty()
        return "Council dates$checked · check with the school"
    }

    /** The holidays still to come or under way from [today]. */
    fun ahead(cal: TermCalendar, today: Long): List<TermPeriod> = cal.periods.filter { it.endDay >= today }

    private fun choice(cal: TermCalendar, today: Long): TermChoice {
        val n = ahead(cal, today).size
        return TermChoice(cal.id, cal.name, "${cal.years} · $n ${if (n == 1) "date" else "dates"} · checked ${checkedLabel(cal.checkedDay)}")
    }

    /** The card for [items] (live ones): the offer while no council's dates are in, else what's in. */
    fun card(items: List<SchoolItem>, today: Long): SchoolTermCard {
        val filled = items.mapNotNull { councilOf(it.source) }.firstOrNull()
        if (filled == null) {
            return SchoolTermCard(CARD_TITLE, OFFER_LINE, COUNCILS.filter { ahead(it, today).isNotEmpty() }.map { choice(it, today) }, null)
        }
        val n = items.count { councilOf(it.source)?.id == filled.id && it.endDay >= today }
        val line = "${filled.name} · $n ${if (n == 1) "holiday" else "holidays"} to come · ${filled.years} · " +
            "checked ${checkedLabel(filled.checkedDay)}. The school's own INSET days aren't in them: add those below."
        return SchoolTermCard(CARD_TITLE, line, emptyList(), choice(filled, today))
    }

    /**
     * What filling in [cal] adds from [today]: each holiday still to come, except any that overlaps a day off for both
     * boys already in (typed by him, or this council's from before).
     */
    fun toAdd(cal: TermCalendar, items: List<SchoolItem>, today: Long): List<TermPeriod> {
        val off = items.filter { it.kind == SchoolKind.OFF && it.who.isEmpty() }
        return ahead(cal, today).filter { p -> off.none { it.startDay <= p.endDay && p.startDay <= it.endDay } }
    }

    /** What one holiday writes: a day off for both boys, marked with the council and the day it was checked. */
    fun fields(cal: TermCalendar, p: TermPeriod, nowMs: Long): Map<String, FieldValue> = mapOf(
        SchoolFields.KIND to SchoolKind.OFF.wire.fv(),
        SchoolFields.TITLE to p.title.fv(),
        SchoolFields.WHO to "".fv(),
        SchoolFields.START to p.startDay.fv(),
        SchoolFields.END to p.endDay.fv(),
        SchoolFields.WEEKDAY to 0L.fv(),
        SchoolFields.ADDED_AT to nowMs.fv(),
        SchoolFields.SOURCE to "$SOURCE_PREFIX${cal.id}".fv(),
        SchoolFields.CHECKED to cal.checkedDay.fv(),
    )

    /** The same id on every device for one council holiday, so the Fold and the Mac filling it in at once make one row. */
    fun idFor(cal: TermCalendar, p: TermPeriod): String = "council-${cal.id}-${p.startDay}"

    /** "Filled in 8 holidays from Central Bedfordshire · check the school's INSET days". */
    fun filledLine(cal: TermCalendar, n: Int): String =
        "Filled in $n ${if (n == 1) "holiday" else "holidays"} from ${cal.name} · add the school's INSET days below"

    /** "Removed 8 council holidays". */
    fun removedLine(n: Int): String = "Removed $n council ${if (n == 1) "holiday" else "holidays"}"

    // ---- INSET days pasted from the school's website or letter ----

    const val INSET_HINT = "Paste the INSET days (dates, or the school's letter)"
    const val INSET_NONE = "Navilife couldn't find a date in that. Paste the dates, e.g. \"2 Nov, 4 Jan and 12 Apr\"."
    /** At most this many INSET days from one paste (a school has five a year; two years' worth with room). */
    const val INSET_MAX = 12

    /**
     * Every date or range in a pasted text, in order ("INSET days: Monday 2 November, 4 January and 12–13 April"), each
     * a day off for both boys titled "INSET day(s)". Dates with no year are the next time they come round from [today];
     * a range that can't be right is skipped, not the whole paste. At most [INSET_MAX].
     */
    fun readInset(text: String, today: Long): List<SchoolEntry> {
        var rest = SchoolRules.tidy(text)
        val out = mutableListOf<SchoolEntry>()
        while (out.size < INSET_MAX) {
            val (span, after) = SchoolRules.findSpanAt(rest, today) ?: break
            if (span != null && out.none { it.startDay == span.first && it.endDay == span.second }) {
                val title = if (span.second > span.first) "INSET days" else "INSET day"
                out += SchoolEntry(SchoolKind.OFF, title, "", span.first, span.second)
            }
            rest = after
        }
        return out.sortedBy { it.startDay }
    }

    /** "Added 3 INSET days · Mon 2 Nov, Mon 4 Jan, Mon 12 Apr". */
    fun insetLine(added: List<SchoolEntry>, skipped: Int): String {
        val days = added.joinToString(", ") { SchoolRules.spanLabel(it.startDay, it.endDay) }
        val n = added.size
        val head = if (n == 0) "Nothing new to add" else "Added $n INSET ${if (n == 1) "day" else "days"} · $days"
        return if (skipped > 0) "$head · $skipped already in" else head
    }
}

/** The council holidays and pasted INSET days on a replica (alongside [School]). */
class SchoolTermsStore(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun items() = SchoolRules.items(replica.entities(EntityTypes.SCHOOL_ITEM).associate { it.ref.entityId to it.fields })

    /**
     * Fills in [councilId]'s holidays still to come; the line to show, or null for a council not in the table. Idempotent:
     * a holiday already in (or overlapping a day off he typed) isn't added again.
     */
    fun fill(councilId: String): Pair<String, Int>? {
        val cal = SchoolTerms.calendar(councilId) ?: return null
        val today = calendar.epochDayOf(nowMs())
        val add = SchoolTerms.toAdd(cal, items(), today)
        if (add.isEmpty()) return SchoolTerms.NOT_FILLED to 0
        for (p in add) {
            val fixed = SchoolTerms.idFor(cal, p)
            // A holiday removed before keeps its id deleted for good (the removal wins on every device), so filling in
            // again after Remove all writes it under a new id.
            val id = if (replica.entity(EntityTypes.SCHOOL_ITEM, fixed) == null) fixed else newId()
            replica.commitLocal(EntityTypes.SCHOOL_ITEM, id, SchoolTerms.fields(cal, p, nowMs()))
        }
        return SchoolTerms.filledLine(cal, add.size) to add.size
    }

    /** Removes every council holiday from [councilId] (past ones too); how many went. */
    fun removeAll(councilId: String): Int {
        val source = "${SchoolTerms.SOURCE_PREFIX}$councilId"
        val gone = items().filter { it.source == source }
        gone.forEach { replica.commitLocal(EntityTypes.SCHOOL_ITEM, it.id, mapOf(SchoolFields.DELETED to true.fv())) }
        return gone.size
    }

    /** Adds the INSET days in a pasted text; null when it has no date in it. Days already off for both boys are skipped. */
    fun addInset(text: String): String? {
        val today = calendar.epochDayOf(nowMs())
        val found = SchoolTerms.readInset(text, today)
        if (found.isEmpty()) return null
        val off = items().filter { it.kind == SchoolKind.OFF && it.who.isEmpty() }
        val (skip, add) = found.partition { e -> off.any { it.startDay <= e.startDay && e.endDay <= it.endDay } }
        add.forEach { replica.commitLocal(EntityTypes.SCHOOL_ITEM, newId(), SchoolRules.fields(it, nowMs())) }
        return SchoolTerms.insetLine(add, skip.size)
    }
}
