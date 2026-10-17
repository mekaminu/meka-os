package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Money manager, slice 13 (Meka approved 2026-10-12 21:22), part 1: **his side hustle's money and the tax year**. Insight
 * on his own numbers only: Navilife never files anything, never pays HMRC, and nothing here is tax advice.
 *
 * What counts as the side hustle's (both his choice, both synced, ADR-008 addendum 2026-10-17, additive, no migration):
 * - a whole account he marks **Side hustle account** (`money_account.business` = true): every line on it, except
 *   transfers between his own accounts;
 * - a shop or payer he marks **Business** on a payment (`money_rule.business` true/false, on the same merchant rule
 *   entity his categories use): every line to or from them, on any account. False takes one out of a business account.
 *
 * For the UK tax year so far (6 April to today) it adds up money in, money out (by category) and the profit; a share
 * to put aside that he picks ([SET_ASIDE_CHOICES], 20% unless changed; one `context_mode` entity [ENTITY_ID] with
 * `setAsidePct`), stated as his choice and never as a tax bill; the £1,000 trading allowance as a fact; last tax year's
 * totals; and the next Self Assessment dates (5 October, 31 January, 31 July), lit within 30 days. Pure, no AI.
 */
object MoneyBusinessFields {
    /** On `money_account`: true when the whole account is the side hustle's. */
    const val ACCOUNT = "business"
    /** On `money_rule`: true when a shop or payer is the side hustle's, false to keep one out; null follows the account. */
    const val MERCHANT = "business"
    const val SET_ASIDE = "setAsidePct"
    const val CHANGED_AT = "changedAtMs"
}

/** One statement line as the side hustle sees it. */
data class MoneyBusinessLine(
    val account: String, val day: Long, val pence: Long, val merchantKey: String, val category: MoneyCategory,
)

/** A cost category's total this tax year. */
data class MoneyBusinessCost(val key: String, val label: String, val amount: String, val line: String)

/** One set-aside chip ("20%"); [on] is his choice. */
data class MoneyBusinessChip(val key: String, val label: String, val on: Boolean)

/** One Self Assessment date: "Sun 31 Jan 2027 · in 106 days", what it is, lit within 30 days. */
data class MoneyBusinessDate(val day: Long, val title: String, val line: String, val lit: Boolean)

/** An account with its Side hustle account switch. */
data class MoneyBusinessAccount(val id: String, val name: String, val on: Boolean)

/**
 * SIDE HUSTLE AND TAX on the Money screen. [intro] (and no figures) until an account or a payer is marked; [accounts]
 * always lists his accounts with the switch.
 */
data class MoneyBusinessView(
    val head: String,
    val intro: String?,
    /** "2026–27 so far · since Mon 6 Apr". */
    val yearLine: String?,
    val inLine: String?,
    val outLine: String?,
    /** "Profit £1,240" / "Loss £120". */
    val profitLine: String?,
    val profitUp: Boolean,
    /** "At 20%, put aside about £248 · your share, not a tax bill". */
    val setAsideLine: String?,
    val chips: List<MoneyBusinessChip>,
    val allowanceLine: String?,
    val costs: List<MoneyBusinessCost>,
    /** "2025–26 · in £… · out £… · profit £…". */
    val lastYearLine: String?,
    val dates: List<MoneyBusinessDate>,
    val accounts: List<MoneyBusinessAccount>,
    /** "Marked as business: Canva, Stripe" (payers he marked), or null. */
    val merchantsLine: String?,
    val foot: String,
    val spoken: String,
    /** Slice 13b: YEAR-END EXPORT, a chip per tax year (last year, this one so far); null until something is marked. */
    val export: TradingExportView? = null,
) {
    /** Index access for Swift. */
    fun costAt(i: Int): MoneyBusinessCost = costs[i]
    fun chipAt(i: Int): MoneyBusinessChip = chips[i]
    fun dateAt(i: Int): MoneyBusinessDate = dates[i]
    fun accountAt(i: Int): MoneyBusinessAccount = accounts[i]
}

object MoneyBusiness {
    const val ENTITY_ID = "money_business"
    const val HEAD = "SIDE HUSTLE AND TAX"
    const val INTRO = "Mark the account your side hustle's money goes through (below), or tap a payment and choose Business: Navilife adds up what came in, what went out and the profit for the tax year."
    const val FOOT = "Your own numbers, not tax advice: the share to put aside is the one you pick, not a tax bill. Navilife never files or pays anything; the dates are HMRC's."
    const val SWITCH = "Side hustle account"
    const val MENU_ON = "Business ✓"
    const val MENU_OFF = "Business"
    const val DEFAULT_PCT = 20
    val SET_ASIDE_CHOICES = listOf(10, 20, 25, 30, 40)
    /** HMRC's trading allowance: £1,000 of income a tax year. */
    const val ALLOWANCE_PENCE = 100_000L
    const val SHOWN_COSTS = 4
    const val LIT_DAYS = 30

    fun setAside(fields: Map<String, FieldValue>?): Int =
        fields?.get(MoneyBusinessFields.SET_ASIDE)?.longOrNull?.toInt()?.takeIf { it in SET_ASIDE_CHOICES } ?: DEFAULT_PCT

    fun setAsideFields(pct: Int, nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneyBusinessFields.SET_ASIDE to pct.toLong().fv(), MoneyBusinessFields.CHANGED_AT to nowMs.fv())

    /** "20" → 20 when it's a choice; else null. */
    fun parseChoice(key: String): Int? = key.trim().removeSuffix("%").toIntOrNull()?.takeIf { it in SET_ASIDE_CHOICES }

    fun setAsideSaid(pct: Int): String = "Putting aside $pct% of the profit · on both devices · your choice, not a tax bill"
    fun accountSaid(name: String, on: Boolean): String =
        if (on) "$name is your side hustle's account · everything on it counts as business, except transfers"
        else "$name isn't counted as the side hustle's any more"
    fun merchantSaid(merchant: String, on: Boolean): String =
        if (on) "$merchant counts as business from now on · on every account"
        else "$merchant no longer counts as business"

    /** Whether a line is the side hustle's: the payer's own mark first, then the account's; transfers never. */
    fun counts(l: MoneyBusinessLine, accounts: Set<String>, merchants: Map<String, Boolean>): Boolean {
        if (l.category == MoneyCategory.TRANSFERS) return false
        merchants[l.merchantKey]?.let { return it }
        return l.account in accounts
    }

    /** The UK tax year (6 April – 5 April) [day] falls in, as its first year. */
    fun taxYearOf(day: Long): Int {
        val d = CivilDate.fromEpochDay(day)
        return if (d.month > 4 || (d.month == 4 && d.day >= 6)) d.year else d.year - 1
    }

    fun yearStart(startYear: Int): Long = CivilDate.toEpochDay(startYear, 4, 6)

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun withYear(day: Long) = "${CivilDate.shortLabel(day)} ${CivilDate.fromEpochDay(day).year}"

    private fun inDays(n: Long) = when (n) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $n days" }

    /** The next three Self Assessment dates from [today] (today's included). */
    fun dates(today: Long): List<MoneyBusinessDate> {
        val y = CivilDate.fromEpochDay(today).year
        val all = (y - 1..y + 2).flatMap { yr ->
            listOf(
                CivilDate.toEpochDay(yr, 1, 31) to "File your ${TradingTaxYear(yr - 2).label} return online and pay what's due (with any first payment on account for ${TradingTaxYear(yr - 1).label})",
                CivilDate.toEpochDay(yr, 7, 31) to "Second payment on account for ${TradingTaxYear(yr - 1).label}, if HMRC asked for one",
                CivilDate.toEpochDay(yr, 10, 5) to "Tell HMRC you need to file, if ${TradingTaxYear(yr - 1).label} was your side hustle's first year over the allowance",
            )
        }.filter { it.first >= today }.sortedBy { it.first }.take(3)
        return all.map { (d, what) ->
            val n = d - today
            MoneyBusinessDate(d, what, "${withYear(d)} · ${inDays(n)}", n <= LIT_DAYS)
        }
    }

    fun view(
        accounts: List<MoneyAccount>, lines: List<MoneyBusinessLine>, businessAccounts: Set<String>,
        merchants: Map<String, Boolean>, setAsidePct: Int, today: Long,
    ): MoneyBusinessView? {
        if (accounts.isEmpty()) return null
        val names = accounts.map { it.id }.toSet()
        val bizAccounts = businessAccounts.filter { it in names }.toSet()
        val accountRows = accounts.sortedBy { it.name.lowercase() }.map { MoneyBusinessAccount(it.id, it.name, it.id in bizAccounts) }
        val marked = merchants.filterValues { it }.keys
        val merchantsLine = marked.takeIf { it.isNotEmpty() }?.sorted()?.let { ks ->
            val shown = ks.take(4).joinToString(", ") { MoneyCategoryRules.merchantLabel(it) }
            "Marked as business: $shown" + (if (ks.size > 4) " and ${ks.size - 4} more" else "")
        }
        val chips = SET_ASIDE_CHOICES.map { MoneyBusinessChip(it.toString(), "$it%", it == setAsidePct) }
        val dates = dates(today)
        if (bizAccounts.isEmpty() && marked.isEmpty()) {
            return MoneyBusinessView(HEAD, INTRO, null, null, null, null, false, null, chips, null, emptyList(), null,
                emptyList(), accountRows, null, FOOT, "Side hustle and tax. $INTRO")
        }
        val biz = lines.filter { counts(it, bizAccounts, merchants) }
        val year = taxYearOf(today)
        val start = yearStart(year)
        val cur = biz.filter { it.day in start..today }
        val moneyIn = cur.filter { it.pence > 0 }.sumOf { it.pence }
        val out = cur.filter { it.pence < 0 }
        val moneyOut = -out.sumOf { it.pence }
        val profit = moneyIn - moneyOut
        val label = TradingTaxYear(year).label
        val yearLine = "$label so far · since ${CivilDate.shortLabel(start)}"
        val nIn = cur.count { it.pence > 0 }
        val inLine = "In ${fmt(moneyIn)} · $nIn payment${if (nIn == 1) "" else "s"}"
        val outLine = "Out ${fmt(moneyOut)} · ${out.size} cost${if (out.size == 1) "" else "s"}"
        val profitLine = if (profit >= 0) "Profit ${fmt(profit)}" else "Loss ${fmt(-profit)}"
        val setAsideLine = if (profit > 0) {
            val p = (profit * setAsidePct + 50) / 100
            "At $setAsidePct%, put aside about ${fmt(p)} · your share, not a tax bill"
        } else "Nothing to put aside while there's no profit"
        val allowanceLine = when {
            moneyIn == 0L -> null
            moneyIn <= ALLOWANCE_PENCE -> "Under HMRC's £1,000 trading allowance so far (${fmt(moneyIn)} in) · gov.uk explains when you needn't tell HMRC"
            else -> "Over HMRC's £1,000 trading allowance this tax year · that usually means a Self Assessment return"
        }
        val costs = out.groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }
            .entries.sortedByDescending { it.value }.take(SHOWN_COSTS).map { (c, p) ->
                val n = out.count { it.category == c }
                MoneyBusinessCost(c.key, c.label, fmt(p), "$n payment${if (n == 1) "" else "s"}")
            }
        val lastStart = yearStart(year - 1)
        val last = biz.filter { it.day in lastStart until start }
        val lastYearLine = if (last.isEmpty()) null else {
            val i = last.filter { it.pence > 0 }.sumOf { it.pence }
            val o = -last.filter { it.pence < 0 }.sumOf { it.pence }
            "${TradingTaxYear(year - 1).label} · in ${fmt(i)} · out ${fmt(o)} · " + (if (i >= o) "profit ${fmt(i - o)}" else "loss ${fmt(o - i)}")
        }
        val spoken = listOfNotNull("Side hustle, $yearLine", inLine, outLine, profitLine, setAsideLine,
            dates.firstOrNull()?.let { "Next: ${it.line}, ${it.title}" }).joinToString(". ")
        return MoneyBusinessView(
            HEAD, null, yearLine, inLine, outLine, profitLine, profit > 0, setAsideLine, chips, allowanceLine, costs,
            lastYearLine, dates, accountRows, merchantsLine, FOOT, spoken, MoneyBusinessExport.view(year),
        )
    }
}

/** One side hustle line for the year-end file: the statement's own words, its account's name and its category. */
data class MoneyBusinessExportLine(val day: Long, val account: String, val text: String, val category: MoneyCategory, val pence: Long)

/**
 * Money manager, slice 13b: **the year-end export for an accountant**. SIDE HUSTLE AND TAX → YEAR-END EXPORT saves a
 * spreadsheet (CSV) of a UK tax year's side hustle lines — the same ones the block counts ([MoneyBusiness.counts]):
 * date, account, the statement's words, category, money in, money out — then totals: money in, money out by category,
 * money out, and the profit or loss. Built on the device from his own statements and bank lines (nothing sent
 * anywhere, nothing synced or stored); pounds. Facts only, never tax advice; Navilife files nothing.
 */
object MoneyBusinessExport {
    const val HEAD = "YEAR-END EXPORT"
    const val LINE = "A spreadsheet (CSV) of a tax year's side hustle money for your accountant · every payment in and out, then totals by category · made on this device from your statements · not tax advice"
    val COLUMNS = listOf("Date", "Account", "Description", "Category", "Money in (GBP)", "Money out (GBP)")
    const val NOTE = "Exported by Navilife from your own statements and bank lines: the side hustle account's lines (except transfers) and the payers marked Business. Not tax advice; check it with your accountant."

    /** Last tax year and this one so far (the one before first: it's the one an accountant asks for). */
    fun view(currentYear: Int): TradingExportView = TradingExportView(HEAD, LINE, listOf(currentYear - 1, currentYear).map { y ->
        val t = TradingTaxYear(y)
        val label = if (y == currentYear) "${t.label} so far" else t.label
        TradingExportChip(t.key, label, "Export the $label tax year's side hustle money as a spreadsheet")
    })

    fun fileName(year: TradingTaxYear): String = "navilife-side-hustle-${year.key}.csv"

    /** The year's lines (6 April to 5 April), oldest first. */
    fun inYear(year: TradingTaxYear, lines: List<MoneyBusinessExportLine>): List<MoneyBusinessExportLine> {
        val from = MoneyBusiness.yearStart(year.startYear)
        val to = MoneyBusiness.yearStart(year.startYear + 1)
        return lines.filter { it.day in from until to }.sortedWith(compareBy({ it.day }, { it.account }, { it.text }))
    }

    private fun date(day: Long): String = CivilDate.fromEpochDay(day).let {
        "${it.year}-${it.month.toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}"
    }

    private fun row(cells: List<String>) = cells.joinToString(",") { TradingExportRules.field(it) }

    private fun p(pence: Long) = TradingExportRules.plain(pence)

    /** The file for [year]: a header, each line by date, a blank row, then the totals and a note. */
    fun csv(year: TradingTaxYear, lines: List<MoneyBusinessExportLine>): String {
        val ls = inYear(year, lines)
        val out = StringBuilder()
        fun add(cells: List<String>) { out.append(row(cells)).append("\r\n") }
        add(COLUMNS)
        ls.forEach { l ->
            add(listOf(date(l.day), l.account, l.text, l.category.label, if (l.pence > 0) p(l.pence) else "", if (l.pence < 0) p(-l.pence) else ""))
        }
        add(List(COLUMNS.size) { "" })
        val tag = "Tax year ${year.label}"
        val moneyIn = ls.filter { it.pence > 0 }.sumOf { it.pence }
        val moneyOut = -ls.filter { it.pence < 0 }.sumOf { it.pence }
        add(listOf(tag, "", "Total money in", "", p(moneyIn), ""))
        ls.filter { it.pence < 0 }.groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }
            .entries.sortedWith(compareByDescending<Map.Entry<MoneyCategory, Long>> { it.value }.thenBy { it.key.label })
            .forEach { (c, v) -> add(listOf(tag, "", "Money out · ${c.label}", c.label, "", p(v))) }
        add(listOf(tag, "", "Total money out", "", "", p(moneyOut)))
        val profit = moneyIn - moneyOut
        add(listOf(tag, "", if (profit >= 0) "Profit" else "Loss", "", if (profit >= 0) p(profit) else "", if (profit < 0) p(-profit) else ""))
        add(listOf("", "", "Note", "", NOTE, ""))
        return out.toString()
    }

    /** "2025–26: 84 payments · in £4,200.00 · out £1,310.00 · profit £2,890.00 · choose where to save it". */
    fun saidReady(year: TradingTaxYear, lines: List<MoneyBusinessExportLine>): MoneySaved {
        val ls = inYear(year, lines)
        val i = ls.filter { it.pence > 0 }.sumOf { it.pence }
        val o = -ls.filter { it.pence < 0 }.sumOf { it.pence }
        val result = if (i >= o) "profit ${MoneyRules.fmt(i - o)}" else "loss ${MoneyRules.fmt(o - i)}"
        return MoneySaved("${year.label}: ${ls.size} payment${if (ls.size == 1) "" else "s"} · in ${MoneyRules.fmt(i)} · out ${MoneyRules.fmt(o)} · $result · choose where to save it")
    }

    fun saidEmpty(year: TradingTaxYear): MoneySaved = MoneySaved("No side hustle money in ${year.label} · nothing to save")

    const val NOTHING_MARKED = "Mark your side hustle's account or a payer as Business first"

    /**
     * What the export comes to: the lines the side hustle counts ([accounts] his accounts, [txns] his lines with a
     * bank account and its statement counted once, [rules] his categories) for the tax year [yearKey].
     */
    fun result(
        yearKey: String, accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>,
        businessAccounts: Set<String>, merchants: Map<String, Boolean>,
    ): TradingExportResult {
        val year = TradingTaxYear.parse(yearKey) ?: return TradingExportResult(MoneySaved("That isn't a tax year", refused = true))
        val names = accounts.associate { it.id to it.name }
        val biz = businessAccounts.filter { it in names }.toSet()
        if (biz.isEmpty() && merchants.none { it.value }) return TradingExportResult(MoneySaved(NOTHING_MARKED, refused = true))
        val merged = MoneyBank.merge(accounts, txns.filter { it.account in names }).txns
        val lines = merged.mapNotNull { t ->
            val key = MoneyCategoryRules.merchantKey(t.text)
            val cat = rules[key] ?: MoneyCategoryRules.categoryOf(t.text, t.pence)
            val l = MoneyBusinessLine(t.account, t.day, t.pence, key, cat)
            if (!MoneyBusiness.counts(l, biz, merchants)) null
            else MoneyBusinessExportLine(t.day, names[t.account].orEmpty(), t.text, cat, t.pence)
        }
        if (inYear(year, lines).isEmpty()) return TradingExportResult(saidEmpty(year))
        return TradingExportResult(saidReady(year, lines), csv(year, lines), fileName(year))
    }
}
