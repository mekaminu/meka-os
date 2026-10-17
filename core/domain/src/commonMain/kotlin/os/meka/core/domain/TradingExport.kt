package os.meka.core.domain

import kotlin.math.abs

/**
 * Trading tracker, slice 8 (the CSV export for tax): Money → TRADING → EXPORT FOR TAX saves a spreadsheet (CSV) of a UK
 * tax year's Hyperliquid perpetuals for an accountant — every order (its fills together) with its size, average price,
 * value, closed P&L and fee, each day's funding per coin, and each account's totals. Navilife's server reads them from
 * Hyperliquid's public data with the public address only (no key, nothing that can trade) when he asks, keeps them in
 * memory for a few minutes, and the device builds the file; nothing new is synced or stored.
 *
 * UK tax years run 6 April to 5 April ([TradingTaxYear]); amounts are US dollars, Hyperliquid's own unit (the file says
 * so: HMRC wants pounds, which his accountant converts). Facts only, never tax advice.
 */
data class TradingTaxYear(val startYear: Int) {
    /** "2025–26". */
    val label: String get() = "$startYear–" + ((startYear + 1) % 100).toString().padStart(2, '0')

    /** "2025-26" for the file name. */
    val key: String get() = "$startYear-" + ((startYear + 1) % 100).toString().padStart(2, '0')

    fun fromMs(cal: LocalCalendar): Long = cal.toEpochMs(CivilDate.toEpochDay(startYear, 4, 6), 0)
    fun toMs(cal: LocalCalendar): Long = cal.toEpochMs(CivilDate.toEpochDay(startYear + 1, 4, 6), 0)

    companion object {
        /** The tax year [nowMs] falls in. */
        fun of(nowMs: Long, cal: LocalCalendar): TradingTaxYear {
            val d = CivilDate.fromEpochDay(cal.epochDayOf(nowMs))
            val started = d.month > 4 || (d.month == 4 && d.day >= 6)
            return TradingTaxYear(if (started) d.year else d.year - 1)
        }

        /** "2025-26" → the year; anything else null. */
        fun parse(key: String?): TradingTaxYear? {
            val m = Regex("""(\d{4})-(\d{2})""").matchEntire(key?.trim().orEmpty()) ?: return null
            val y = m.groupValues[1].toInt()
            return TradingTaxYear(y).takeIf { y in 2019..2100 && it.key == key!!.trim() }
        }
    }
}

/** One perpetuals order (its fills together): [size] and [averagePrice] as Hyperliquid's decimal text; money in US cents. */
data class TradingExportOrder(
    val atMs: Long, val coin: String, val direction: String, val size: String, val averagePrice: String,
    val valueCents: Long, val closedPnlCents: Long, val feeCents: Long,
)

/** One UTC day's funding on one coin: received +, paid −, US cents. */
data class TradingExportFunding(val day: Long, val coin: String, val cents: Long)

/** What the server read for one followed account; [partial] when Hyperliquid had more than it could give. */
data class TradingExportAccount(
    val walletId: String, val orders: List<TradingExportOrder>, val funding: List<TradingExportFunding>, val partial: Boolean = false,
    /** True when this account couldn't be read at all. */
    val failed: Boolean = false,
) {
    override fun toString() = "TradingExportAccount($walletId, ${orders.size} orders)"
}

/** EXPORT FOR TAX: the line and a chip per tax year (this one so far, the one before). */
data class TradingExportChip(val key: String, val label: String, val spoken: String)
data class TradingExportView(val head: String, val line: String, val chips: List<TradingExportChip>)

/** What the export came to: the [csv] and its [fileName] to save, or only [said] when there's nothing to save. */
data class TradingExportResult(val said: MoneySaved, val csv: String? = null, val fileName: String? = null) {
    override fun toString() = "TradingExportResult(${said.line.take(40)}, ${csv?.length ?: 0} chars)"
}

object TradingExportRules {
    const val HEAD = "EXPORT FOR TAX"
    const val LINE = "A spreadsheet (CSV) of a UK tax year's trades, fees and funding for your accountant · read from Hyperliquid's public data when you ask · US dollars · not tax advice"
    const val WORKING = "Reading the year's trades from Hyperliquid · this can take a minute"
    const val OFFLINE = "Couldn't reach Navilife's server · try again with signal"
    const val OFF = "Navilife's server can't export trades yet · it needs the latest update"
    const val FAILED = "Couldn't read the trades from Hyperliquid · try again in a few minutes"
    const val SLOW = "Hyperliquid is still sending the year's trades · try again in a minute"
    const val NONE = "No accounts to export · add your public address first"
    const val MIME = "text/csv"
    const val MAX_ORDERS = 20_000
    const val MAX_FUNDING = 5_000
    /** The longest window the server reads (a tax year, with a day to spare for a clock change). */
    const val MAX_SPAN_MS = 367L * 86_400_000L

    val COLUMNS = listOf(
        "Date (UTC)", "Account", "Type", "Coin", "Direction", "Size", "Average price (USD)", "Value (USD)",
        "Closed P&L (USD)", "Fee (USD)", "Funding (USD)", "Net (USD)",
    )

    /** This tax year so far and the one before (the one before first: it's the one an accountant asks for). */
    fun years(nowMs: Long, cal: LocalCalendar): List<TradingTaxYear> = TradingTaxYear.of(nowMs, cal).let { now -> listOf(TradingTaxYear(now.startYear - 1), now) }

    fun view(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar): TradingExportView? {
        if (wallets.isEmpty()) return null
        val now = TradingTaxYear.of(nowMs, cal)
        return TradingExportView(HEAD, LINE, years(nowMs, cal).map { y ->
            val label = if (y == now) "${y.label} so far" else y.label
            TradingExportChip(y.key, label, "Export the $label tax year's trades as a spreadsheet")
        })
    }

    fun fileName(year: TradingTaxYear): String = "navilife-hyperliquid-${year.key}.csv"

    /** "2026-04-06 14:02" in UTC. */
    fun utc(ms: Long): String {
        val d = CivilDate.fromEpochDay(ms.floorDiv(CivilDate.DAY_MS))
        val m = (ms.mod(CivilDate.DAY_MS) / 60_000L).toInt()
        return day(d) + " " + (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }

    private fun day(d: CivilDate.Ymd) = "${d.year}-${d.month.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}"

    /** "-12.34", "0.00": plain dollars for a spreadsheet (no symbol, no separators). */
    fun plain(cents: Long): String = (if (cents < 0) "-" else "") + (abs(cents) / 100) + "." + (abs(cents) % 100).toString().padStart(2, '0')

    /** A CSV field: quoted when it holds a comma, quote or line break; a leading = + - @ is kept as text (no formulas). */
    fun field(s: String): String {
        val t = s.replace("\r", " ").replace("\n", " ")
        val safe = if (t.isNotEmpty() && t[0] in "=+@\t") "'$t" else t
        return if (safe.any { it == ',' || it == '"' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun row(cells: List<String>) = cells.joinToString(",") { field(it) }

    /**
     * The file: a header, then each account's orders and funding days by time, then a Total row per account (closed
     * P&L, fees, funding and what's left after them) and, with several accounts, one for all of them. A partial account
     * gets a Note row. [labels] are the accounts' names by wallet id.
     */
    fun csv(year: TradingTaxYear, accounts: List<TradingExportAccount>, labels: Map<String, String>): String {
        val out = StringBuilder()
        out.append(row(COLUMNS)).append("\r\n")
        data class Line(val at: Long, val cells: List<String>)
        var allClosed = 0L; var allFees = 0L; var allFunding = 0L
        val totals = mutableListOf<List<String>>()
        val read = accounts.filter { !it.failed }
        for (a in read) {
            val name = labels[a.walletId]?.takeIf { it.isNotBlank() } ?: TradingTracker.DEFAULT_LABEL
            val lines = a.orders.take(MAX_ORDERS).map { o ->
                Line(o.atMs, listOf(utc(o.atMs), name, "Trade", o.coin, o.direction, o.size, o.averagePrice, plain(o.valueCents),
                    plain(o.closedPnlCents), plain(-o.feeCents), "", plain(o.closedPnlCents - o.feeCents)))
            } + a.funding.take(MAX_FUNDING).map { f ->
                val at = f.day * CivilDate.DAY_MS
                Line(at, listOf(day(CivilDate.fromEpochDay(f.day)), name, "Funding", f.coin, "", "", "", "", "", "", plain(f.cents), plain(f.cents)))
            }
            lines.sortedBy { it.at }.forEach { out.append(row(it.cells)).append("\r\n") }
            val closed = a.orders.sumOf { it.closedPnlCents }
            val fees = a.orders.sumOf { it.feeCents }
            val funding = a.funding.sumOf { it.cents }
            allClosed += closed; allFees += fees; allFunding += funding
            totals += listOf("Tax year ${year.label}", name, "Total", "", "${a.orders.size} orders", "", "", "",
                plain(closed), plain(-fees), plain(funding), plain(closed - fees + funding))
            if (a.partial) totals += listOf("", name, "Note", "", "Hyperliquid gave only its most recent trades for this account; the earliest in the year may be missing", "", "", "", "", "", "", "")
        }
        if (read.size > 1) totals += listOf("Tax year ${year.label}", "All accounts", "Total", "", "${read.sumOf { it.orders.size }} orders", "", "", "",
            plain(allClosed), plain(-allFees), plain(allFunding), plain(allClosed - allFees + allFunding))
        totals.forEach { out.append(row(it)).append("\r\n") }
        return out.toString()
    }

    /** What he's told once the file is ready: "2025–26: 212 orders and 180 days of funding across 2 accounts · net +$1,240.00 · …". */
    fun saidReady(year: TradingTaxYear, accounts: List<TradingExportAccount>): MoneySaved {
        val read = accounts.filter { !it.failed }
        val orders = read.sumOf { it.orders.size }
        val days = read.flatMap { a -> a.funding.map { it.day } }.distinct().size
        val net = read.sumOf { a -> a.orders.sumOf { it.closedPnlCents - it.feeCents } + a.funding.sumOf { it.cents } }
        val parts = listOfNotNull(
            "${year.label}: $orders order${if (orders == 1) "" else "s"}" + (if (days > 0) " and $days day${if (days == 1) "" else "s"} of funding" else "") +
                (if (read.size > 1) " across ${read.size} accounts" else ""),
            "net ${TradingTracker.signedUsd(net)} after fees and funding",
            if (accounts.any { it.failed }) "${accounts.count { it.failed }} account couldn't be read" else null,
            if (read.any { it.partial }) "Hyperliquid gave only its most recent trades for one account" else null,
            "choose where to save it",
        )
        return MoneySaved(parts.joinToString(" · "))
    }

    fun saidEmpty(year: TradingTaxYear): MoneySaved = MoneySaved("No trades or funding in ${year.label} · nothing to save")
}
