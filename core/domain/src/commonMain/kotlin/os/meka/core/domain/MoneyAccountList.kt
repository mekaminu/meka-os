package os.meka.core.domain

/**
 * Money review (Meka 2026-10-26 22:12, Accounts: "one clean list"; mockup `docs/design/money/`, tab Accounts): his
 * accounts as one list of one-line rows, each a short name ("Amex Platinum", "HSBC card", "Revolut ••9180") over what it
 * is, with its balance (a card's as owed) or "Add balance", and a tap opens the account's sheet: its balance, It's a
 * card, Side hustle account, Import a statement into it, and Remove (asks first). The six "Side hustle account" switches,
 * the six "Remove" links, the import chips and "Is one of these a card?" leave the page. A Hyperliquid account he follows
 * is a row too (read-only; a tap goes to Trading).
 *
 * Built from the [MoneyView] both apps already have (net worth's rows, the cards, the side hustle's accounts), so the
 * Fold and the Mac draw the same list and sheet. Words only, no new data and nothing stored.
 */
data class MoneyAccountItem(
    val id: String,
    /** Two letters for the 40 dp tile ("HS", "AM"). */
    val initials: String,
    /** The short name ([MoneyAccountNames.shortName]). */
    val name: String,
    /** "Card · Due Fri 30 Oct", "Side hustle · updated 26 Oct", "Reconnect in Lunch Flow". */
    val sub: String,
    /** Signed ("£1,204", "−£4,200"); [MoneyAccountList.ADD_BALANCE] when it has none yet. */
    val amount: String,
    val addBalance: Boolean,
    val owes: Boolean,
    /** A lit sub line (reconnect, a card due soon). */
    val lit: Boolean,
    /** False for a trading row: a tap goes to the Trading tab, not a sheet. */
    val opens: Boolean,
    val spoken: String,
)

/** An account's sheet: what it is, its balance and the switches; every action is an existing Money command. */
data class MoneyAccountSheet(
    val id: String,
    val title: String,
    /** The bank's own name when the short one differs ("American Express Platinum Card"), else null. */
    val fullName: String?,
    /** "From your bank · 312 transactions · 1 Jul – 26 Oct · updated 26 Oct". */
    val line: String,
    val amount: String?,
    /** "Balance · from your statement · 26 Oct", "Card · its balance, owed", "No balance yet · type it to count it". */
    val balanceLine: String?,
    /** Whether a typed balance makes sense here (not a card, and not one his bank keeps up to date). */
    val canType: Boolean,
    /** Marked as a card (its due date and statement show on Bills). */
    val card: Boolean,
    /** Null while there's no side hustle block (nothing to mark). */
    val sideHustle: Boolean?,
    /** "Remove Amex Platinum and its 312 transactions?" */
    val removeAsk: String,
    val spoken: String,
    /** Accounts' rest: the name he gave it (the Name field starts with it), or null while it goes by its own. */
    val nick: String? = null,
)

object MoneyAccountList {
    const val HEAD = "ACCOUNTS"
    const val ADD_BALANCE = "Add balance"
    const val BALANCE = "BALANCE"
    const val CARD_SWITCH = "It's a card"
    const val CARD_SWITCH_LINE = "Its due date, statement and limit show on Bills"
    const val SIDE_HUSTLE_LINE = "Its money counts for your side hustle's tax year"
    const val IMPORT = "Import a statement into it…"
    const val REMOVE = "Remove account"
    const val REMOVE_YES = "Remove"
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val TRADING_SUB = "Hyperliquid · read-only"

    /** The page's Import row (a sheet holds the field for a new account). */
    const val IMPORT_ROW = "Import a statement"
    const val IMPORT_ROW_SUB = "A CSV file from any bank or card, for a new account"
    const val IMPORT_HEAD = "Import a statement"

    /** Two letters for the tile: the first two of the short name's first word ("HSBC card" → "HS"). */
    fun initials(name: String): String {
        val word = name.split(' ').firstOrNull { w -> w.any { it.isLetter() } } ?: return "··"
        return word.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "··" }
    }

    /** The list: his accounts (A–Z by short name) then the trading accounts net worth counts. */
    fun items(v: MoneyView): List<MoneyAccountItem> {
        val worth = v.worth?.rows.orEmpty().associateBy { it.id }
        val marked = v.cards?.rows.orEmpty().associateBy { it.id }
        val briefs = v.cards?.let { MoneyCards.briefs(it) }.orEmpty().associateBy { it.id }
        val biz = v.business?.accounts.orEmpty().filter { it.on }.map { it.id }.toSet()
        val accounts = v.accounts.map { a ->
            val w = worth[a.id]
            val short = a.nick ?: MoneyAccountNames.shortName(a.name)
            val isCard = a.id in marked || w?.kind == MoneyWorth.CARD
            val brief = briefs[a.id]
            val parts = mutableListOf<String>()
            if (isCard) parts += "Card"
            if (a.id in biz) parts += "Side hustle"
            val lit: Boolean
            when {
                a.reconnect -> { parts += "Reconnect in Lunch Flow"; lit = true }
                brief != null -> { parts += brief.line; lit = brief.lit }
                else -> {
                    a.updated?.let { parts += (if (a.bank) "Updated " else "Imported ") + it }
                    lit = false
                }
            }
            val sub = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { if (a.txns == 0) "No transactions yet" else "${a.txns} transactions" }
            val amount = w?.amount?.takeIf { it.isNotEmpty() }
            MoneyAccountItem(
                a.id, initials(short), short, sub, amount ?: ADD_BALANCE, addBalance = amount == null, owes = w?.owes == true,
                lit = lit, opens = true,
                spoken = "$short, $sub, " + (amount?.let { if (w?.owes == true) "owes ${it.removePrefix("−")}" else "balance $it" } ?: "no balance yet") + ". Opens its sheet",
            )
        }.sortedBy { it.name.lowercase() }
        val trading = v.worth?.rows.orEmpty().filter { it.kind == MoneyWorth.TRADING }.map { r ->
            MoneyAccountItem(
                r.id, "HL", r.name, TRADING_SUB, r.amount.ifEmpty { "—" }, addBalance = false, owes = r.owes, lit = false, opens = false,
                spoken = "${r.name}, Hyperliquid, read-only, ${r.amount.ifEmpty { "not counted yet" }}. Opens Trading",
            )
        }
        return accounts + trading
    }

    /** Net worth's own rows once the accounts list carries the accounts, cards and trading: what he typed. */
    fun typedRows(w: MoneyWorthView): List<MoneyWorthRow> = w.rows.filter { it.kind == MoneyWorth.TYPED }

    /** [id]'s sheet, or null once it's gone (the sheet then closes). */
    fun sheet(v: MoneyView, id: String): MoneyAccountSheet? {
        val a = v.accounts.firstOrNull { it.id == id } ?: return null
        val w = v.worth?.rows?.firstOrNull { it.id == id }
        val short = a.nick ?: MoneyAccountNames.shortName(a.name)
        val card = v.cards?.rows?.any { it.id == id } == true
        val looksCard = card || w?.kind == MoneyWorth.CARD
        val side = v.business?.accounts?.firstOrNull { it.id == id }?.on ?: v.business?.let { false }
        val amount = w?.amount?.takeIf { it.isNotEmpty() }
        val txns = if (a.txns == 1) "its 1 transaction" else "its ${a.txns} transactions"
        return MoneyAccountSheet(
            id, short, a.name.takeIf { !it.equals(short, ignoreCase = true) }, a.line, amount, w?.line,
            canType = !looksCard && (!a.bank || amount == null), card = card, sideHustle = side,
            removeAsk = "Remove $short and $txns? Navilife forgets them on both devices.",
            spoken = "$short. " + (amount ?: "No balance yet") + ". ${a.line}",
            nick = a.nick,
        )
    }
}
