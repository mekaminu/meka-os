package os.meka.core.domain

/**
 * Money review (Meka 2026-10-26 22:12), Accounts' rest; mockup `docs/design/money/` (Accounts, the card under the
 * accounts list): Side hustle and tax, Shared with his partner, and Import, export and delete are three one-line rows in
 * one card, each opening a sheet that holds what used to sit open on the page (the side hustle's year, the shared costs,
 * the import field, the export and Delete all money data). Words only; built from the [MoneyView] both apps have.
 */
enum class MoneyMoreKind { SIDE_HUSTLE, SHARED_COSTS, DATA }

/** One row: "Side hustle and tax" over "Tax year so far · Profit £1,240 · put aside about £248", lit when a date is near. */
data class MoneyMoreRow(val kind: MoneyMoreKind, val title: String, val line: String, val lit: Boolean, val spoken: String)

object MoneyMore {
    const val HEAD = "MORE"
    const val SIDE_HUSTLE = "Side hustle and tax"
    const val SIDE_HUSTLE_START = "Mark an account or a payment as side hustle to start"
    const val SHARED_START = "Mark a payment shared to start"
    const val DATA = "Import, export and delete"
    const val DATA_LINE = "Statement files, CSV, delete all money data"
    const val EXPORT = "Export all payments (CSV)"
    const val EXPORT_LINE = "A spreadsheet of every payment Navilife has, with its account and category · made on this device"
    const val DONE = "Done"

    fun sharedTitle(partner: String): String = if (partner == "your partner") "Shared costs" else "Shared with $partner"

    /** "At 20%, put aside about £248 · your share, not a tax bill" → "put aside about £248"; null for anything else. */
    fun setAsideShort(line: String?): String? =
        line?.takeIf { it.startsWith("At ") && it.contains("put aside") }?.substringAfter(", ")?.substringBefore(" · ")

    /** The card's rows: the side hustle and shared costs when Money has them, then Import, export and delete (always). */
    fun rows(v: MoneyView): List<MoneyMoreRow> {
        val out = mutableListOf<MoneyMoreRow>()
        v.business?.let { b ->
            val line = if (b.intro != null) SIDE_HUSTLE_START
                else listOfNotNull("Tax year so far", b.profitLine, setAsideShort(b.setAsideLine)).joinToString(" · ")
            out += MoneyMoreRow(MoneyMoreKind.SIDE_HUSTLE, SIDE_HUSTLE, line, lit = b.dates.any { it.lit }, spoken = "$SIDE_HUSTLE. $line. Opens its sheet")
        }
        v.shared?.let { sh ->
            val title = sharedTitle(sh.partner)
            val line = if (sh.intro != null) SHARED_START else sh.balanceLine ?: sh.monthLine ?: SHARED_START
            out += MoneyMoreRow(MoneyMoreKind.SHARED_COSTS, title, line, lit = sh.intro == null && sh.balanceLit, spoken = "$title. $line. Opens its sheet")
        }
        out += MoneyMoreRow(MoneyMoreKind.DATA, DATA, DATA_LINE, lit = false, spoken = "$DATA. $DATA_LINE. Opens its sheet")
        return out
    }

    /** The sheet's title for [kind] (the shared one names his partner). */
    fun title(kind: MoneyMoreKind, v: MoneyView): String = when (kind) {
        MoneyMoreKind.SIDE_HUSTLE -> SIDE_HUSTLE
        MoneyMoreKind.SHARED_COSTS -> sharedTitle(v.shared?.partner ?: "your partner")
        MoneyMoreKind.DATA -> DATA
    }
}

/**
 * Accounts' rest: **Export all payments** — every line Navilife has (a statement and its bank account counted once), oldest
 * first, with the account's name, the words, the category and the amount in or out, as a CSV to save. Made on the device
 * from his own data; nothing is sent or stored. Pure.
 */
object MoneyExport {
    val COLUMNS = listOf("Date", "Account", "Description", "Category", "Money in (GBP)", "Money out (GBP)")
    const val NOTHING = "No payments to export yet · connect your banks or import a statement first"

    fun fileName(today: Long): String = CivilDate.fromEpochDay(today).let {
        "navilife-payments-${it.year}-${it.month.toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}.csv"
    }

    private fun date(day: Long): String = CivilDate.fromEpochDay(day).let {
        "${it.year}-${it.month.toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}"
    }

    fun csv(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>): String {
        val names = accounts.associate { it.id to (it.nick ?: it.name) }
        val mine = MoneyBank.merge(accounts, txns.filter { it.account in names }).txns
        val moves = MoneyTransfers.ids(accounts, mine, rules)
        val out = StringBuilder()
        fun add(cells: List<String>) { out.append(cells.joinToString(",") { TradingExportRules.field(it) }).append("\r\n") }
        add(COLUMNS)
        mine.sortedWith(compareBy({ it.day }, { names[it.account].orEmpty() }, { it.text })).forEach { t ->
            val cat = MoneyTransfers.categoryOf(t, MoneyCategoryRules.merchantKey(t.text), rules, moves)
            add(listOf(date(t.day), names[t.account].orEmpty(), t.text, cat.label,
                if (t.pence > 0) TradingExportRules.plain(t.pence) else "", if (t.pence < 0) TradingExportRules.plain(-t.pence) else ""))
        }
        return out.toString()
    }

    /** The file and what to say ("312 payments from 4 accounts · choose where to save it"), or why there's nothing. */
    fun result(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>, today: Long): TradingExportResult {
        val ids = accounts.map { it.id }.toSet()
        val lines = MoneyBank.merge(accounts, txns.filter { it.account in ids }).txns
        val count = lines.size
        if (count == 0) return TradingExportResult(MoneySaved(NOTHING, refused = true))
        val used = lines.map { it.account }.toSet().size
        val said = "$count payment${if (count == 1) "" else "s"} from $used account${if (used == 1) "" else "s"} · choose where to save it"
        return TradingExportResult(MoneySaved(said), csv(accounts, txns, rules), fileName(today))
    }
}
