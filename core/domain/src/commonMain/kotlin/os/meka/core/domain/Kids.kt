package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Kids' admin (V1, Meka approved 2026-10-12), slice 1: each child's **sizes** — shoes, football boots, tops, trousers,
 * coat and school jumper — with a nudge to check them every three months, on Ask's **Kids** tile and in the Kids pane on
 * both apps. The children themselves come from About you (rule 13: names live in his data, never in code); a child taken
 * out of About you drops out of Kids too (what was kept for them stays in his data, unseen, and comes back with them).
 *
 * Stored as one synced `kid_admin` entity per child (ADR-008 addendum 2026-10-15, additive), id `c-<the child's name
 * folded>` ([KidsRules.idFor]) so both apps write the same entity: [KidFields], all LWW. Typing a size counts as having
 * checked them ([KidFields.CHECKED_AT]); "All still fit" stamps the same without changing a size.
 *
 * Non-AI, pure rules ([KidsRules]) over a thin store ([Kids]). Tests use invented children.
 */
object KidFields {
    /** The child's name as About you had it when last written (for export; the view always shows About you's). */
    const val NAME = "name"
    /** Int64: when the sizes were last typed or confirmed ("All still fit"). */
    const val CHECKED_AT = "checkedAtMs"
    /** Slice 5, Int64: pocket money a week in pence; Null when none is set. */
    const val POCKET = "pocketPence"
    /** Slice 5, Int64: the Monday (epoch day) of the week [PAID_PENCE] was noted for. */
    const val PAID_WEEK = "paidWeek"
    /** Slice 5, Int64: how much was marked paid for [PAID_WEEK], in pence. */
    const val PAID_PENCE = "paidPence"
}

/** The sizes Kids keeps per child, in the pane's order; [key] is the entity's field. */
enum class KidSize(val key: String, val label: String, val hint: String) {
    SHOES("shoes", "Shoes", "Shoe size… (13 or 1½)"),
    BOOTS("boots", "Football boots", "Boot size… (12)"),
    TOPS("tops", "Tops", "Tops… (7–8 years)"),
    TROUSERS("trousers", "Trousers", "Trousers… (7–8 years)"),
    COAT("coat", "Coat", "Coat… (8–9 years)"),
    JUMPER("jumper", "School jumper", "Jumper… (26\" or 7–8)"),
    ;

    companion object {
        fun of(key: String?): KidSize? = entries.firstOrNull { it.key == key }
    }
}

/** What Kids holds for one child, as stored. */
data class KidRecord(
    val id: String, val sizes: Map<KidSize, String>, val checkedAtMs: Long?,
    /** Slice 5: pocket money a week (pence), and what was marked paid for which week (its Monday). */
    val pocketPence: Long? = null, val paidWeek: Long? = null, val paidPence: Long? = null,
)

/** One size row of a child's card: the label, what's kept (null when empty) and the field's hint. */
data class KidSizeRow(val size: String, val label: String, val value: String?, val hint: String)

/**
 * A child's card: [name] and [line] from About you ("Under 7s · Oakfield School"); [sizes] every size row; [sizesLine]
 * the kept ones in one line ("Shoes 13 · Tops 7–8"); [checkedLine] when they were checked, lit ([due]) once three months
 * have gone by; [spoken] for screen readers.
 */
data class KidCard(
    val name: String,
    val line: String,
    val sizes: List<KidSizeRow>,
    val sizesLine: String?,
    val checkedLine: String,
    val due: Boolean,
    val spoken: String,
    /** Slice 2: the child's club fees (on the radar as bills), soonest first. */
    val fees: List<KidFeeRow> = emptyList(),
    /** Slice 5: the child's chores and pocket money this week ([KidMoney.NONE] until he sets any). */
    val money: KidMoney = KidMoney.NONE,
)

/**
 * Slice 2: one club fee on a child's card: [title] ("Football"), [line] ("£35 · every month · next Sun 1 Nov"), lit
 * ([due]) from three days before it's due (Bills due's reminder) and when overdue; [id] is the radar item's.
 */
data class KidFeeRow(val id: String, val title: String, val line: String, val due: Boolean, val dueDay: Long, val spoken: String)

/** A typed fee read ([KidsRules.readFee]): what it's for, the cost in pence (null: not said), its repeat and first due day. */
data class KidFeeDraft(val title: String, val costPence: Long?, val repeat: RenewalRepeat, val dueDay: Long)

/** The Kids pane: a [summary], one card per child (About you's order), and [empty] when there are no children yet. */
data class KidsView(
    val summary: String, val kids: List<KidCard>, val empty: String?, val tile: LifeTileLine?,
    /** Slice 3: what they need in the next 7 days (school, club sessions, fees); null with no children. */
    val week: KidWeek? = null,
) {
    val isEmpty: Boolean get() = kids.isEmpty()

    companion object {
        val EMPTY = KidsView(KidsRules.NO_CHILDREN, emptyList(), KidsRules.NO_CHILDREN, null)
    }
}

/**
 * What a size save said: [line] under the field, [refused] when nothing was written, [undo] the value before a clear.
 * Slice 2, for a club fee: [feeUndo] the fee id Undo puts back (Paid or Remove, kept by the facade).
 */
data class KidSaved(
    val line: String, val refused: Boolean = false, val undo: String? = null, val feeUndo: String? = null,
    /** Slice 5: the token Undo hands back for a removed chore, a pocket money change or a Paid (the facade keeps what it undoes). */
    val moneyUndo: String? = null,
)

/** Non-AI, pure. */
object KidsRules {
    const val TITLE = "Kids"
    const val ROW_LINE = "Each child's sizes, club fees, chores and pocket money · a nudge to check sizes every 3 months"
    /** Three months, near enough: a size typed or confirmed more than this long ago asks for a check. */
    const val CHECK_DAYS = 91
    const val MAX_SIZE = 16
    const val NO_CHILDREN = "Add the children in Ask → Settings → About you first; each gets a card here for their sizes."
    const val NO_SIZES = "No sizes yet · add what they wear now"
    const val NOT_SIZE = "A size has a number in it (13, 1½, 7–8 years, 26\") or is XS, S, M, L or XL, up to 16 characters."
    const val NOT_CHILD = "That child isn't in About you any more."
    const val CLEARED = "Cleared · Undo puts it back"
    const val FOOT = "Kept in your own data (synced, encrypted). Typing a size counts as checking them; \"All still fit\" says so without changing one."

    private val LETTER_SIZES = setOf("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL")

    /** The entity id for a child: "c-alex" for "Alex", "c-mary-jane" for "Mary Jane"; null for a name with no letters. */
    fun idFor(name: String): String? {
        val folded = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
        return if (folded.isEmpty()) null else "c-$folded"
    }

    /**
     * A typed size made tidy, or null when it can't be one: spaces collapsed, "7-8" → "7–8", "s"/"xl" upper-cased; it
     * must hold a digit or be a letter size, and fit [MAX_SIZE].
     */
    fun tidy(typed: String): String? {
        var t = typed.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > MAX_SIZE) return null
        if (t.uppercase() in LETTER_SIZES) return t.uppercase()
        if (t.none { it.isDigit() || it == '½' }) return null
        t = Regex("([0-9½]) ?- ?([0-9])").replace(t) { m -> m.groupValues[1] + "–" + m.groupValues[2] }
        return t
    }

    /** The kept sizes and when they were checked, from stored fields. */
    fun record(id: String, fields: Map<String, FieldValue>): KidRecord = KidRecord(
        id = id,
        sizes = KidSize.entries.mapNotNull { s -> fields[s.key]?.textOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { s to it } }.toMap(),
        checkedAtMs = fields[KidFields.CHECKED_AT]?.longOrNull,
        pocketPence = fields[KidFields.POCKET]?.longOrNull,
        paidWeek = fields[KidFields.PAID_WEEK]?.longOrNull,
        paidPence = fields[KidFields.PAID_PENCE]?.longOrNull,
    )

    /** "Shoes 13 · Tops 7–8"; null with nothing kept. */
    fun sizesLine(sizes: Map<KidSize, String>): String? =
        KidSize.entries.mapNotNull { s -> sizes[s]?.let { "${s.label} $it" } }.joinToString(" · ").ifEmpty { null }

    /** Whether a child's sizes want a look: some kept, and checked [CHECK_DAYS] or more days ago (or never stamped). */
    fun due(r: KidRecord?, todayDay: Long, cal: LocalCalendar): Boolean {
        if (r == null || r.sizes.isEmpty()) return false
        val checked = r.checkedAtMs ?: return true
        return todayDay - cal.epochDayOf(checked) >= CHECK_DAYS
    }

    /** "Sizes checked today" · "Sizes checked Wed 14 Oct" · "Last checked Tue 14 Jul · do they still fit?" · [NO_SIZES]. */
    fun checkedLine(r: KidRecord?, todayDay: Long, cal: LocalCalendar): String {
        if (r == null || r.sizes.isEmpty()) return NO_SIZES
        val checked = r.checkedAtMs ?: return "Not checked yet · do they still fit?"
        val day = cal.epochDayOf(checked)
        val label = if (day == todayDay) "today" else CivilDate.shortLabel(day)
        return if (due(r, todayDay, cal)) "Last checked $label · do they still fit?" else "Sizes checked $label"
    }

    /** "Alex", "Alex and Sam", "Alex, Sam and Jo". */
    fun names(ns: List<String>): String = when (ns.size) {
        0 -> ""
        1 -> ns[0]
        else -> ns.dropLast(1).joinToString(", ") + " and " + ns.last()
    }

    /** "Alex's", or "Alex's and Sam's" for the tile and summary. */
    private fun possessive(ns: List<String>): String = names(ns.map { "$it's" })

    fun card(
        c: AboutChild, r: KidRecord?, todayDay: Long, cal: LocalCalendar, fees: List<KidFeeRow> = emptyList(),
        money: KidMoney = KidMoney.NONE,
    ): KidCard {
        val sizes = r?.sizes.orEmpty()
        val line = sizesLine(sizes)
        val checked = checkedLine(r, todayDay, cal)
        return KidCard(
            name = c.name,
            line = AboutYouRules.childLine(c),
            sizes = KidSize.entries.map { s -> KidSizeRow(s.key, s.label, sizes[s], s.hint) },
            sizesLine = line,
            checkedLine = checked,
            due = due(r, todayDay, cal),
            spoken = listOfNotNull(c.name, line, checked).joinToString(". "),
            fees = fees,
            money = money,
        )
    }

    /**
     * The pane and the tile for the children About you holds, in its order; a record whose child has left About you is
     * not shown. The tile asks for a check (lit) when one is due, else names the first child's shoe size (or first size).
     */
    fun view(
        children: List<AboutChild>, records: Map<String, KidRecord>, todayDay: Long, cal: LocalCalendar,
        /** Slice 2: the radar's items; the club fees are those with [RenewalItem.forChild]. */
        radar: List<RenewalItem> = emptyList(),
        /** Slice 5: each child's chores and pocket money this week, by their kid_admin id. */
        money: Map<String, KidMoney> = emptyMap(),
    ): KidsView {
        if (children.isEmpty()) return KidsView.EMPTY
        val cards = children.map { c ->
            val id = idFor(c.name)
            card(c, id?.let { records[it] }, todayDay, cal, feesFor(id, radar, todayDay), id?.let { money[it] } ?: KidMoney.NONE)
        }
        val dueNames = cards.filter { it.due }.map { it.name }
        val withSizes = cards.filter { it.sizesLine != null }
        val summary = when {
            dueNames.isNotEmpty() -> "Time to check ${possessive(dueNames)} sizes · tap All still fit or type the new ones"
            withSizes.isEmpty() -> "Add what ${if (cards.size == 1) cards[0].name + " wears" else "each child wears"} now; Navilife asks you to check every 3 months."
            withSizes.size < cards.size -> "Sizes for ${names(withSizes.map { it.name })} · add ${possessive(cards.filter { it.sizesLine == null }.map { it.name })}"
            else -> "Sizes for ${names(cards.map { it.name })} · checked every 3 months"
        }
        return KidsView(summary, cards, null, tile(cards, dueNames))
    }

    private fun tile(cards: List<KidCard>, dueNames: List<String>): LifeTileLine? {
        // Slice 2: a fee due within 3 days (or overdue) leads, lit: "Alex's Football · £35 due tomorrow".
        cards.flatMap { c -> c.fees.filter { it.due }.map { c.name to it } }.minByOrNull { it.second.dueDay }?.let { (name, f) ->
            val more = cards.sumOf { c -> c.fees.count { it.due } } - 1
            return LifeTileLine("$name's ${f.title} · " + f.line.substringAfterLast(" · ").let { w ->
                f.line.split(" · ").firstOrNull { it.startsWith("£") }?.let { "$it $w" } ?: w
            } + if (more > 0) " · +$more" else "", true)
        }
        if (dueNames.isNotEmpty()) return LifeTileLine("Check ${possessive(dueNames)} sizes", true)
        // Slice 5: on Sunday, pocket money still to pay: "Alex's pocket money · £8 to pay".
        cards.filter { it.money.lit }.let { owed ->
            if (owed.isNotEmpty()) return LifeTileLine(
                "${owed[0].name}'s pocket money · ${KidChoreRules.fmt(owed[0].money.toPayPence)} to pay" + if (owed.size > 1) " · +${owed.size - 1}" else "", true,
            )
        }
        val first = cards.firstOrNull { it.sizesLine != null } ?: return cards.flatMap { c -> c.fees.map { c.name to it } }
            .minByOrNull { it.second.dueDay }?.let { (name, f) -> LifeTileLine("$name's ${f.title} · ${f.line.substringAfterLast(" · ")}", false) }
        val shoes = first.sizes.firstOrNull { it.size == KidSize.SHOES.key }?.value
        val size = if (shoes != null) "shoes $shoes" else first.sizes.first { it.value != null }.let { "${it.label.lowercase()} ${it.value}" }
        val more = cards.count { it.sizesLine != null } - 1
        return LifeTileLine("${first.name} · $size" + if (more > 0) " · +$more" else "", false)
    }

    // ---- Slice 2: club fees ----

    const val FEE_HINT = "Add a fee… (Football £35 monthly from 1 Nov)"
    const val FEE_HEAD = "CLUB FEES"
    const val FEE_FOOT = "On the radar as a bill too: one reminder 3 days before, in Lists → Renewals. Navilife never pays."
    const val FEE_NO_DATE = "Say when it's due: “Football £35 monthly from 1 Nov” or “Swimming £60 on the 5th every month”."
    const val FEE_NO_TITLE = "Say what it's for: “Football £35 monthly from 1 Nov”."
    const val FEE_BAD_COST = "Type the amount in pounds after a £, like £35 or £12.50."
    const val FEE_GONE = "That fee has changed since · look in Lists → Renewals"
    const val FEE_NOTHING_TO_UNDO = "Nothing to undo"
    /** Lit, and Bills due's reminder comes, this many days before ([BillRules.REMIND_DAYS]). */
    val FEE_SOON_DAYS: Int get() = BillRules.REMIND_DAYS
    private const val FEE_MAX_TITLE = 40

    private val I = setOf(RegexOption.IGNORE_CASE)
    private val costRe = Regex("""£\s?(\d{1,5}(?:,\d{3})*(?:\.\d{1,2})?)|\b(\d{1,5}(?:\.\d{1,2})?)\s?(?:pounds|quid|gbp)\b""", I)
    private val repeatRes: List<Pair<Regex, RenewalRepeat>> = listOf(
        Regex("""\b(?:every|each|a|per)\s+(?:3|three)\s+months\b|\bquarterly\b""", I) to RenewalRepeat.QUARTERLY,
        Regex("""\b(?:every|each|a|per)\s+(?:6|six)\s+months\b|\bhalf[\s-]?yearly\b|\btwice\s+a\s+year\b""", I) to RenewalRepeat.HALF_YEARLY,
        Regex("""\b(?:every|each|a|per)\s+month\b|\bmonthly\b|\bpcm\b""", I) to RenewalRepeat.MONTHLY,
        Regex("""\b(?:every|each|a|per)\s+year\b|\byearly\b|\bannual(?:ly)?\b|\bfor\s+the\s+year\b""", I) to RenewalRepeat.YEARLY,
        Regex("""\b(?:just\s+)?once\b|\bone[\s-]?off\b""", I) to RenewalRepeat.NONE,
    )
    /** "on the 1st", "the 5th", "1st of the month", "on the 28th of each month". */
    private val dayOfMonthRe = Regex("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)(?:\s+of\s+(?:the|each|every)\s+month)?\b""", I)
    private val connectorsLead = Regex("""^(?:\s|,|;|:|-|–|—|&|·|\bfor\b|\bfrom\b|\bon\b|\bdue\b|\bstarting\b|\bnext\b|\bthe\b|\bis\b|\bof\b)+""", I)
    private val connectorsTail = Regex("""(?:\s|,|;|:|-|–|—|&|·|\bfor\b|\bfrom\b|\bon\b|\bdue\b|\bstarting\b|\bnext\b|\bthe\b|\bis\b|\bof\b|\bevery\b)+$""", I)

    /**
     * Reads one typed fee: "Football £35 monthly from 1 Nov", "Swimming £60 on the 5th every month", "Club subs £120
     * yearly 1 Sep", "Trip £15 20 Nov" (no repeat word: once). A date without a year is its next time from [today]; "the
     * 5th" alone is the next 5th. Returns the draft, or the line saying what's missing (nothing is written then).
     */
    fun readFee(typed: String, today: Long): Pair<KidFeeDraft?, String?> {
        var s = " " + typed.trim().replace(Regex("""\s+"""), " ") + " "
        if (s.isBlank()) return null to FEE_NO_TITLE
        var cost: Long? = null
        costRe.find(s)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "")
            cost = try { RenewalRules.parseCost(n) } catch (_: ValidationException) { return null to FEE_BAD_COST }
            s = s.replaceRange(m.range, " ")
        }
        if (cost == null && s.contains('£')) return null to FEE_BAD_COST
        var repeat = RenewalRepeat.NONE
        for ((re, r) in repeatRes) {
            val m = re.find(s) ?: continue
            repeat = r
            s = s.replaceRange(m.range, " ")
            break
        }
        var due: Long? = null
        when (val f = SchoolRules.findSpan(s, today)) {
            SpanFound.Bad -> return null to FEE_NO_DATE
            is SpanFound.Days -> { if (f.endDay != f.startDay) return null to FEE_NO_DATE; due = f.startDay; s = f.rest }
            SpanFound.None -> dayOfMonthRe.find(s)?.let { m ->
                val dom = m.groupValues[1].toInt()
                if (dom !in 1..31) return null to FEE_NO_DATE
                due = nextDayOfMonth(dom, today)
                s = s.replaceRange(m.range, " ")
                if (repeat == RenewalRepeat.NONE && Regex("""\bof\s+(?:each|every)\s+month\b""", I).containsMatchIn(m.value)) repeat = RenewalRepeat.MONTHLY
            }
        }
        val title = s.replace(connectorsLead, "").replace(connectorsTail, "").replace(Regex("""\s+"""), " ").trim()
            .replaceFirstChar { it.uppercase() }
        if (title.isEmpty()) return null to FEE_NO_TITLE
        val day = due ?: return null to FEE_NO_DATE
        return KidFeeDraft(title.take(FEE_MAX_TITLE).trim(), cost, repeat, day) to null
    }

    /** The next [dom] of a month on or after [today] (a short month takes its last day). */
    fun nextDayOfMonth(dom: Int, today: Long): Long {
        val t = CivilDate.fromEpochDay(today)
        var y = t.year
        var m = t.month
        repeat(2) {
            val d = minOf(dom, CivilDate.lengthOfMonth(y, m))
            val day = CivilDate.toEpochDay(y, m, d)
            if (day >= today) return day
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        return CivilDate.toEpochDay(y, m, minOf(dom, CivilDate.lengthOfMonth(y, m)))
    }

    /** "today", "tomorrow", "Sun 1 Nov". */
    private fun feeDay(day: Long, today: Long): String = when (day - today) {
        0L -> "today"
        1L -> "tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** A fee's row: "£35 · every month · next Sun 1 Nov" (lit soon: "due tomorrow", overdue: "was due Fri 30 Oct"). */
    fun feeRow(item: RenewalItem, today: Long): KidFeeRow {
        val left = item.dueDay - today
        val cost = item.costPence?.let(RenewalRules::formatPence)
        val repeat = item.repeats?.let { r -> if (r == RenewalRepeat.NONE) "once" else RenewalRules.repeatLabel(r).lowercase() } ?: item.repeatLabel.lowercase()
        val whenWord = when {
            left < 0 -> "was due ${CivilDate.shortLabel(item.dueDay)}"
            left <= FEE_SOON_DAYS -> "due ${feeDay(item.dueDay, today)}"
            item.repeats != null && item.repeats != RenewalRepeat.NONE -> "next ${CivilDate.shortLabel(item.dueDay)}"
            else -> "due ${CivilDate.shortLabel(item.dueDay)}"
        }
        val line = listOfNotNull(cost, repeat, whenWord).joinToString(" · ")
        return KidFeeRow(item.id, item.title, line, left <= FEE_SOON_DAYS, item.dueDay, "${item.title}. $line")
    }

    /** The line after a fee is added: "Added Football · £35 · every month · next Sun 1 Nov · reminds you 3 days before". */
    fun feeAddedLine(d: KidFeeDraft, today: Long): String {
        val cost = d.costPence?.let(RenewalRules::formatPence)
        val repeat = if (d.repeat == RenewalRepeat.NONE) "once" else RenewalRules.repeatLabel(d.repeat).lowercase()
        return "Added ${d.title} · " + listOfNotNull(cost, repeat, "due ${feeDay(d.dueDay, today)}").joinToString(" · ") +
            " · reminds you $FEE_SOON_DAYS days before"
    }

    /** After Paid: "Paid Football · next Tue 1 Dec" or "Paid Football · done". */
    fun feePaidLine(title: String, next: Long?): String =
        if (next == null) "Paid $title · done" else "Paid $title · next ${CivilDate.shortLabel(next)}"

    fun feeRemovedLine(title: String): String = "Removed $title · Undo puts it back"

    /** The fees of [items] for the child [id], soonest first. */
    fun feesFor(id: String?, items: List<RenewalItem>, today: Long): List<KidFeeRow> =
        if (id == null) emptyList()
        else items.filter { it.forChild == id }.sortedWith(compareBy<RenewalItem> { it.dueDay }.thenBy { it.title }).map { feeRow(it, today) }

    /** The line after a save: "Saved · shoes 13". */
    fun savedLine(size: KidSize, value: String): String = "Saved · ${size.label.lowercase()} $value"

    /** The line after All still fit. */
    fun fitLine(name: String): String = "Marked $name's sizes as still fitting · Navilife asks again in 3 months"
}

/** Slice 3: one thing in the week list: "Alex: PE kit", "Sam: Training 16:55", "Alex's Football · £35 due"; [lit] wants doing. */
data class KidWeekItem(
    val text: String, val lit: Boolean, val spoken: String,
    /** Slice 4: a club session from a calendar (its title is the club's words, so Ask treats it as untrusted). */
    val fromCalendar: Boolean = false,
)

/** Slice 3: one day of the week list: [label] "Today", "Tomorrow" or "Mon 19 Oct". */
data class KidWeekDay(val day: Long, val label: String, val items: List<KidWeekItem>)

/** Slice 3: THIS WEEK on the Kids pane: [line] how much there is (or that there's nothing), [days] only days with something. */
data class KidWeek(val line: String, val days: List<KidWeekDay>) {
    val isEmpty: Boolean get() = days.isEmpty()
    val count: Int get() = days.sumOf { it.items.size }
}

/**
 * Kids' admin, slice 3: **what the children need this week**, one list across them for today and the next six days,
 * from what Navilife already holds: School's days off, dates and weekly things (PE as "PE kit"), the children's club
 * sessions and matches in his calendar ([ClubChildRules]: the age group in the title or calendar, else whose series
 * he set), and their club fees due (overdue ones on today). A school line for a child who isn't in About you is left
 * out; a session whose child isn't known is listed without a name. Non-AI, pure; nothing new is stored.
 */
object KidsWeekRules {
    const val HEAD = "THIS WEEK"
    const val DAYS = 7
    const val EMPTY = "Nothing in the next 7 days from School, club sessions or fees."
    const val FOOT = "From School, the club sessions in your calendar and the fees above."

    private fun dayLabel(day: Long, today: Long): String = when (day - today) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** About you's child named [who] (any case), or null; "" (both/all) is handled by the caller. */
    private fun childNamed(who: String, children: List<AboutChild>): AboutChild? =
        children.firstOrNull { it.name.trim().equals(who.trim(), ignoreCase = true) }

    /** Whose club session [e] is, as About you names them: its age group first, else whose series he set; null if unknown. */
    fun childOf(e: CalendarEvent, children: List<AboutChild>, clubSet: Map<String, String>, cal: LocalCalendar): AboutChild? {
        val group = listOfNotNull(e.title, e.calendarTitle, e.calendarName).firstNotNullOfOrNull { FootballRules.ageGroup(it) }
        if (group != null) children.firstOrNull { it.ageGroup == group }?.let { return it }
        val named = clubSet[ClubChildRules.seriesId(e, cal)] ?: ClubChildRules.fromEvent(e)
        return named?.let { childNamed(it, children) }
    }

    /** A school thing in the list's words: "Alex: PE kit", "Alex off school · INSET day", "No school · Half term". */
    fun schoolText(t: SchoolThing, who: String?): String = when (t.kind) {
        SchoolKind.OFF -> {
            val head = if (who == null) SchoolRules.NO_SCHOOL else "$who off school"
            if (t.title == SchoolRules.NO_SCHOOL) head else "$head · ${t.title}"
        }
        else -> SchoolRules.thingText(t.copy(who = who.orEmpty()), kit = t.kind == SchoolKind.WEEKLY)
    }

    fun week(
        children: List<AboutChild>,
        school: List<SchoolItem>,
        events: List<CalendarEvent>,
        clubSet: Map<String, String>,
        radar: List<RenewalItem>,
        nowMs: Long,
        cal: LocalCalendar,
        holidays: HolidayCalendar,
    ): KidWeek {
        if (children.isEmpty()) return KidWeek(EMPTY, emptyList())
        val today = cal.epochDayOf(nowMs)
        val ids = children.mapNotNull { c -> KidsRules.idFor(c.name)?.let { it to c.name } }.toMap()
        val sessions = events.filter { !it.allDay && it.endAtMs > nowMs && ClubChildRules.asks(it) }.sortedBy { it.startAtMs }
        val days = (today until today + DAYS).mapNotNull { d ->
            val label = dayLabel(d, today)
            val soon = d - today <= 1
            val items = mutableListOf<KidWeekItem>()
            SchoolRules.onDay(school, d, holidays).forEach { t ->
                val who = if (t.who.isEmpty()) null else (childNamed(t.who, children)?.name ?: return@forEach)
                val text = schoolText(t, who)
                items += KidWeekItem(text, soon, "$label. $text")
            }
            sessions.filter { cal.epochDayOf(it.startAtMs) == d }.forEach { e ->
                val who = childOf(e, children, clubSet, cal)?.name
                val time = LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))
                val what = e.title.trim().ifEmpty { "Training" }.take(40)
                val text = (if (who != null) "$who: " else "") + "$what $time"
                items += KidWeekItem(text, false, "$label. $text", fromCalendar = true)
            }
            radar.filter { it.forChild != null && it.forChild in ids && (it.dueDay == d || (d == today && it.dueDay < today)) }
                .sortedWith(compareBy<RenewalItem> { it.dueDay }.thenBy { it.title })
                .forEach { f ->
                    val cost = f.costPence?.let(RenewalRules::formatPence)
                    val whenWord = if (f.dueDay < today) "was due ${CivilDate.shortLabel(f.dueDay)}" else "due"
                    val text = "${ids.getValue(f.forChild!!)}'s ${f.title} · " + listOfNotNull(cost, whenWord).joinToString(" ")
                    items += KidWeekItem(text, true, "$label. $text")
                }
            if (items.isEmpty()) null else KidWeekDay(d, label, items)
        }
        if (days.isEmpty()) return KidWeek(EMPTY, emptyList())
        val n = days.sumOf { it.items.size }
        val soon = days.filter { it.day - today <= 1 }.sumOf { it.items.size }
        val line = "$n ${if (n == 1) "thing" else "things"} in the next 7 days" + if (soon > 0) " · $soon today or tomorrow" else ""
        return KidWeek(line, days)
    }
}

/**
 * Kids' admin, slice 4: **Ask and Talk know the children** — "what size shoes is Alex?", "what do the boys need this
 * week?", "when's the football fee due?". Only for a question about them ([isAbout]: a child's name or a word about
 * sizes, kit, fees or the children), the Kids pane as lines for the AI ([askLines]), and the device's own answer when
 * the AI can't be reached ([localAnswer]). Non-AI, pure; nothing is stored.
 */
object KidAskRules {
    /** At most this many lines go with a question (the head, up to 4 children, 6 fees, 4 chore lines, the week's head and 10 things). */
    const val ASK_LINES = 26
    private const val ASK_CHILDREN = 4
    private const val ASK_FEES = 6
    private const val ASK_WEEK = 10

    private val WORDS = Regex(
        """\b(kids?|child(ren)?|boys?|sons?|daughters?|girls?|sizes?|shoes?|trainers|boots?|tops?|trousers|coats?|jumpers?|uniform|pe|kit|club fees?|fees?|subs|training|matches|need(s)? this week|chores?|pocket money)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [question] is about the children: one of their names (About you's), or a word about sizes, kit or fees. */
    fun isAbout(question: String, names: List<String>): Boolean {
        val q = question.lowercase()
        if (names.any { n -> n.isNotBlank() && Regex("""\b${Regex.escape(n.trim().lowercase())}('s)?\b""").containsMatchIn(q) }) return true
        return WORDS.containsMatchIn(q)
    }

    /**
     * The Kids pane as Ask's kids lines: "Kids · 2 children: Alex and Sam", then per child "Alex · Under 7s · Oakfield
     * School · sizes: Shoes 13 · Tops 7–8 · Sizes checked Wed 14 Oct", each fee "Alex's club fee · Football · £35 ·
     * every month · next Sun 1 Nov", then "This week · 3 things in the next 7 days" and "Tomorrow (Fri 16 Oct) · Alex:
     * PE kit". Empty with no children.
     */
    fun askLines(v: KidsView): List<String> {
        if (v.kids.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val n = v.kids.size
        out += "Kids · $n ${if (n == 1) "child" else "children"}: ${KidsRules.names(v.kids.map { it.name })}"
        v.kids.take(ASK_CHILDREN).forEach { c ->
            val sizes = c.sizesLine?.let { "sizes: $it" } ?: "no sizes kept"
            out += listOf(c.name, c.line.takeIf { it.isNotBlank() }, sizes, c.checkedLine.takeIf { c.sizesLine != null }).filterNotNull().joinToString(" · ")
        }
        v.kids.flatMap { c -> c.fees.map { c.name to it } }.sortedBy { it.second.dueDay }.take(ASK_FEES).forEach { (name, f) ->
            out += "$name's club fee · ${f.title} · ${f.line}"
        }
        // Slice 5: chores and pocket money this week, one line per child who has any.
        v.kids.filter { it.money.shown }.take(ASK_CHILDREN).forEach { c ->
            val m = c.money
            out += listOfNotNull(
                "${c.name}'s chores and pocket money",
                m.pocketValue?.let { "pocket money $it a week" },
                m.chores.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.title} ${it.line.substringAfterLast(" · ").lowercase()}" },
                m.weekLine?.removePrefix("This week · ")?.let { "this week $it" },
                m.paidLine,
                m.payLabel?.let { "${KidChoreRules.fmt(m.toPayPence)} still to pay" },
            ).joinToString(" · ")
        }
        val week = v.week
        if (week != null) {
            out += "This week · ${week.line}"
            week.days.flatMap { d -> d.items.map { d to it } }.take(ASK_WEEK).forEach { (d, i) ->
                val label = if (d.label == "Today" || d.label == "Tomorrow") "${d.label} (${CivilDate.shortLabel(d.day)})" else d.label
                out += "$label · ${i.text}"
            }
        }
        return out.take(ASK_LINES)
    }

    /** Whether the week's lines carry a club calendar's words. */
    fun fromOthers(v: KidsView): Boolean = v.week?.days?.any { d -> d.items.any { it.fromCalendar } } == true

    private val SIZE_WORDS: List<Pair<KidSize, Regex>> = listOf(
        KidSize.BOOTS to Regex("""\b(football )?boots?\b""", RegexOption.IGNORE_CASE),
        KidSize.SHOES to Regex("""\b(shoes?|trainers|feet|foot)\b""", RegexOption.IGNORE_CASE),
        KidSize.TOPS to Regex("""\b(tops?|t-?shirts?|shirts?)\b""", RegexOption.IGNORE_CASE),
        KidSize.TROUSERS to Regex("""\b(trousers|jeans|joggers)\b""", RegexOption.IGNORE_CASE),
        KidSize.COAT to Regex("""\b(coats?|jackets?)\b""", RegexOption.IGNORE_CASE),
        KidSize.JUMPER to Regex("""\b(jumpers?|sweatshirts?)\b""", RegexOption.IGNORE_CASE),
    )
    private val WEEK_Q = Regex("""\b(this week|need|needs|coming up|kit)\b""", RegexOption.IGNORE_CASE)

    /**
     * The device's own answer when the AI can't be reached: a size ("Alex's shoes: 13 · checked Wed 14 Oct"; "Alex's
     * coat size isn't kept yet · add it in Ask → Life → Kids"), or what they need this week (the first few things); null
     * when [question] is neither.
     */
    fun localAnswer(question: String, v: KidsView): String? {
        if (v.kids.isEmpty() || !isAbout(question, v.kids.map { it.name })) return null
        val q = question.lowercase()
        val named = v.kids.filter { c -> Regex("""\b${Regex.escape(c.name.trim().lowercase())}('s)?\b""").containsMatchIn(q) }
        val size = SIZE_WORDS.firstOrNull { it.second.containsMatchIn(q) }?.first
        if (size != null && (q.contains("size") || q.contains("what") || q.contains("wear") || q.contains("take"))) {
            val who = named.ifEmpty { v.kids }
            return who.joinToString(" ") { c ->
                val value = c.sizes.firstOrNull { it.size == size.key }?.value
                if (value != null) "${c.name}'s ${size.label.lowercase()}: $value · ${c.checkedLine.replaceFirstChar { it.lowercase() }}."
                else "${c.name}'s ${size.label.lowercase()} size isn't kept yet · add it in Ask → Life → Kids."
            }
        }
        val week = v.week ?: return null
        if (!WEEK_Q.containsMatchIn(q)) return null
        if (week.isEmpty) return week.line
        val things = week.days.flatMap { d -> d.items.map { "${d.label}: ${it.text}" } }
        val shown = things.take(4)
        return "${week.line}. " + shown.joinToString("; ") + (if (things.size > shown.size) "; and ${things.size - shown.size} more in Ask → Life → Kids." else ".")
    }
}

/** Kids' admin on a replica (both apps). The child is named as About you holds them; the facade checks they're there. */
class Kids(private val replica: Replica, private val nowMs: () -> Long) {
    fun records(): Map<String, KidRecord> =
        replica.entities(EntityTypes.KID_ADMIN).associate { it.ref.entityId to KidsRules.record(it.ref.entityId, it.fields) }

    /**
     * Keeps [typed] as [childName]'s [sizeKey]; blank clears it (with Undo). Refused, with nothing written, when it can't
     * be a size or the size or name is unknown. Any save stamps the check.
     */
    fun setSize(childName: String, sizeKey: String, typed: String): KidSaved {
        val size = KidSize.of(sizeKey) ?: return KidSaved(KidsRules.NOT_SIZE, refused = true)
        val id = KidsRules.idFor(childName) ?: return KidSaved(KidsRules.NOT_CHILD, refused = true)
        val before = records()[id]?.sizes?.get(size)
        if (typed.isBlank()) {
            if (before == null) return KidSaved(KidsRules.CLEARED.substringBefore(" ·"))
            replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(KidFields.NAME to childName.trim().fv(), size.key to FieldValue.Null))
            return KidSaved(KidsRules.CLEARED, undo = before)
        }
        val clean = KidsRules.tidy(typed) ?: return KidSaved(KidsRules.NOT_SIZE, refused = true)
        replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(
            KidFields.NAME to childName.trim().fv(),
            size.key to clean.fv(),
            KidFields.CHECKED_AT to nowMs().fv(),
        ))
        return KidSaved(KidsRules.savedLine(size, clean))
    }

    /** "All still fit": stamps the check for [childName] without changing a size; false when nothing is kept for them. */
    fun allFit(childName: String): Boolean {
        val id = KidsRules.idFor(childName) ?: return false
        if (records()[id]?.sizes.isNullOrEmpty()) return false
        replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(KidFields.CHECKED_AT to nowMs().fv()))
        return true
    }
}
