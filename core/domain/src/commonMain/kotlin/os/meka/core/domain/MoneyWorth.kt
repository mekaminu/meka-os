package os.meka.core.domain

import kotlin.math.abs

/**
 * Money manager, slice 8 (2026-10-17): **Net worth** (board item 12) on the Money screen, behind his fingerprint like the
 * rest. One total from what Navilife already knows plus what he types:
 *  - each account's balance (a statement's Balance column, or typed; a card counts what its statement says he owes);
 *  - things outside these accounts he types once and updates when he likes: "Pension £42,000", "Savings elsewhere 12k",
 *    "Car £8,500", "Car loan £3,200" (a loan, mortgage, debt, "owe…" or a minus sign makes it something owed);
 *  - a month-by-month line over up to the last 12 months: each account's balance worked back through its statements
 *    (balance then = balance now less what came in and went out since), with cards and what he typed held at today's
 *    figures, so the line shows how his accounts moved.
 * Accounts with no balance yet aren't counted and say so, with a field to type it. Rules only, no AI; facts on his own
 * numbers, never advice.
 *
 * Trading tracker, slice 5 (2026-10-17): each Hyperliquid account he follows in Money counts too, its account value in
 * pounds at the European Central Bank's reference rate the server reads once a day ([TradingWalletFields.GBP_PER_USD]);
 * held at today's figure on the line, like cards and what he types. Until the rate is in, the row says so and isn't
 * counted.
 *
 * Stored as a new synced entity `money_worth` ([MoneyWorthFields], LWW, ADR-008 addendum, no migration); the history
 * is worked out on the device and never stored.
 */
object MoneyWorthFields {
    const val NAME = "name"
    /** "have" or "owe". */
    const val KIND = "kind"
    /** The value in pence, always positive ([KIND] says which way it counts). */
    const val PENCE = "pence"
    /** The epoch day he last typed it. */
    const val DAY = "day"
}

/** One thing he typed: its [id], [name], whether he [owes] it, its value in [pence] (positive) and the [day] typed. */
data class MoneyWorthItem(val id: String, val name: String, val owes: Boolean, val pence: Long, val day: Long) {
    val signed: Long get() = if (owes) -pence else pence
}

/** What a typed line reads as: the thing's name, whether it's owed and its value. */
data class MoneyWorthParse(val name: String, val owes: Boolean, val pence: Long)

/**
 * One line under NET WORTH. [kind] is [MoneyWorth.ACCOUNT], [MoneyWorth.CARD], [MoneyWorth.TYPED] or [MoneyWorth.TRADING]; [amount] is signed
 * ("£1,840", "−£640"), blank with [needsBalance] for an account with no balance yet; [removable] for a typed one.
 */
data class MoneyWorthRow(
    val id: String, val name: String, val kind: String, val amount: String, val line: String,
    val owes: Boolean, val needsBalance: Boolean, val removable: Boolean, val spoken: String,
)

/** One month on the line: its short [label] ("Oct"), its [pence] and what a tap says ([said]: "End of Sep 2026 · £51,880"). */
data class MoneyWorthPoint(val label: String, val pence: Long, val said: String)

/**
 * The NET WORTH block: [total] (null with nothing counted yet, then [empty] says what to do), [splitLine] ("You have
 * £55,500 · you owe £3,200"), [changeLine] against the month before, the [rows], [missingLine] for accounts without a
 * balance, the [points] to draw (two or more) with [chartLine] under them, the typing [hint] and [foot].
 */
data class MoneyWorthView(
    val head: String, val total: String?, val totalNegative: Boolean, val splitLine: String?, val changeLine: String?,
    val rows: List<MoneyWorthRow>, val missingLine: String?, val points: List<MoneyWorthPoint>, val chartLine: String?,
    val empty: String?, val hint: String, val foot: String, val spoken: String,
)

/** An account's figures for the history: its id, current balance (signed) and day, and its statement lines (day, pence). */
data class MoneyWorthAccount(val id: String, val balance: Long, val day: Long, val lines: List<Pair<Long, Long>>)

object MoneyWorth {
    const val HEAD = "NET WORTH"
    const val ACCOUNT = "account"
    const val CARD = "card"
    const val TYPED = "typed"
    const val TRADING = "trading"
    const val HINT = "Add what's outside these accounts: Pension £42,000 · Car £8,500 · Car loan £3,200"
    const val EMPTY = "Type what you have and owe outside these accounts (a pension, savings elsewhere, the car, a loan), and each account's balance, to see one total."
    const val FOOT = "Past months work each account's balance back through its statements; cards and what you type stay at today's figures · not financial advice"
    const val FOOT_TRADING = "Past months work each account's balance back through its statements; cards, trading and what you type stay at today's figures · trading in pounds at the European Central Bank's rate · not financial advice"
    const val NO_RATE = "Hyperliquid · counted once today's dollar rate is in"
    const val CHART_ONE = "The line starts once a second month is in your statements."
    const val BAD = "Type a name and an amount, e.g. Pension £42,000, Car 8.5k or Car loan £3,200."
    const val NO_NAME = "Say what it is first, e.g. Pension £42,000."
    const val BALANCE_HINT = "Balance now, e.g. £1,240"
    const val MAX_ITEMS = 20
    const val MAX_PENCE = 100_000_000_00L
    const val MONTHS = 12

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** "£1,840", "−£640". */
    fun signed(p: Long): String = MoneyRules.signedPlain(p)

    /** The id for a typed thing: "n-pension" for "Pension"; null for a name with no letters or digits. */
    fun idFor(name: String): String? = KidsRules.idFor(name)?.let { "n" + it.removePrefix("c") }

    private val OWE = Regex("""\b(loan|loans|mortgage|debt|debts|owe|owed|owing|finance|overdraft|borrowed|credit card|pcp|hp)\b""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""([−\-–]?)\s*£?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*([km])?\b""", RegexOption.IGNORE_CASE)

    /**
     * A typed line: "Pension £42,000", "Savings elsewhere 12k", "Car £8,500", "Car loan £3,200", "Owe Dad £500",
     * "Mortgage −£180,000", "ISA: £5.2k". The last amount is the value; the words without it are the name ("owe"
     * dropped from its start); a minus sign or owing words make it owed. Null when there's no name or amount.
     */
    fun parse(text: String): MoneyWorthParse? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val m = AMOUNT.findAll(t).lastOrNull() ?: return null
        val number = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[3].lowercase()) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
        val pence = kotlin.math.round(number * mult * 100).toLong()
        if (pence <= 0 || pence > MAX_PENCE) return null
        val minus = m.groupValues[1].isNotEmpty()
        val name = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
            .replace(Regex("""\b(i\s+)?(owe|owed)\b\s*""", RegexOption.IGNORE_CASE)) { if (it.range.first == 0) "" else it.value }
            .replace(Regex("""\b(worth|is|of|about|at)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim().trim(':', '-', '–', '·', '=', ',').trim().replace(Regex("\\s+"), " ").take(40)
        if (name.none { it.isLetterOrDigit() }) return null
        return MoneyWorthParse(name.replaceFirstChar { it.titlecase() }, minus || OWE.containsMatchIn(t), pence)
    }

    fun savedSaid(p: MoneyWorthParse, updated: Boolean): String =
        (if (updated) "${p.name} is now " else "Added ${p.name} · ") + (if (p.owes) "${fmt(p.pence)} owed" else fmt(p.pence)) + " · counted in your net worth"

    fun removedSaid(name: String): String = "Removed $name from your net worth"

    /** The balance on [end]: [a]'s balance less what came in and went out after [end] up to its balance's day. */
    fun balanceOn(a: MoneyWorthAccount, end: Long): Long =
        if (end >= a.day) a.balance else a.balance - a.lines.filter { it.first > end && it.first <= a.day }.sumOf { it.second }

    /**
     * The month-by-month points: from the latest first statement month among [accounts] (so every account is known on
     * every point), at most [MONTHS] months, to [today]'s month; each month ends on its last day (today for this month).
     * [flat] (cards and typed things) is added to every point. Empty with no accounts to work back.
     */
    fun points(accounts: List<MoneyWorthAccount>, flat: Long, today: Long): List<MoneyWorthPoint> {
        val known = accounts.filter { it.lines.isNotEmpty() }
        if (known.isEmpty()) return emptyList()
        val thisMonth = MoneyRules.monthOf(today)
        val start = maxOf(thisMonth - (MONTHS - 1), known.maxOf { a -> MoneyRules.monthOf(a.lines.minOf { it.first }) })
        return (start..thisMonth).map { m ->
            val y = m / 12
            val mo = m % 12 + 1
            val end = minOf(CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo)), today)
            val total = flat + known.sumOf { balanceOn(it, end) } + accounts.filter { it.lines.isEmpty() }.sumOf { it.balance }
            val short = Recurrence.MONTH_SHORT[mo - 1]
            MoneyWorthPoint(short, total, (if (m == thisMonth) "Now" else "End of $short $y") + " · ${signed(total)}")
        }
    }

    /**
     * The NET WORTH block from his [accounts] (balances), [cards] (what each statement says he owes), [items] he typed
     * and every account's statement [lines] (account, day, pence), on [today].
     */
    fun view(
        accounts: List<MoneyAccount>, cards: List<MoneyCard>, items: List<MoneyWorthItem>,
        lines: List<Triple<String, Long, Long>>, today: Long, trading: List<TradingWallet> = emptyList(),
    ): MoneyWorthView {
        val cardById = cards.associateBy { it.id }
        val byAccount = lines.groupBy({ it.first }, { it.second to it.third })
        val rows = mutableListOf<MoneyWorthRow>()
        val history = mutableListOf<MoneyWorthAccount>()
        var flat = 0L
        var missing = 0
        for (a in accounts.sortedBy { it.name.lowercase() }) {
            val card = cardById[a.id]
            if (card != null) {
                val st = card.statementPence
                if (st == null) {
                    missing++
                    rows += MoneyWorthRow(a.id, a.name, CARD, "", "Card · type its statement balance under Cards", owes = true,
                        needsBalance = false, removable = false, spoken = "${a.name}, card, no statement balance yet")
                } else {
                    flat -= st
                    rows += MoneyWorthRow(a.id, a.name, CARD, signed(-st), if (st == 0L) "Card · nothing owed" else "Card · what its statement says you owe",
                        owes = st > 0, needsBalance = false, removable = false, spoken = "${a.name}, card, owes ${fmt(st)}")
                }
                continue
            }
            val b = a.balance
            if (b == null) {
                missing++
                rows += MoneyWorthRow(a.id, a.name, ACCOUNT, "", "No balance yet · type it to count it", owes = false,
                    needsBalance = true, removable = false, spoken = "${a.name}, no balance yet")
            } else {
                history += MoneyWorthAccount(a.id, b.pence, b.day, byAccount[a.id].orEmpty())
                val asOf = if (b.day == today) "today" else CivilDate.shortLabel(b.day)
                rows += MoneyWorthRow(a.id, a.name, ACCOUNT, signed(b.pence), "Balance · ${if (b.typed) "typed" else "from your statement"} · $asOf",
                    owes = b.pence < 0, needsBalance = false, removable = false, spoken = "${a.name}, balance ${signed(b.pence)}")
            }
        }
        for (i in items.sortedWith(compareBy({ it.owes }, { -it.pence }))) {
            flat += i.signed
            rows += MoneyWorthRow(i.id, i.name, TYPED, signed(i.signed),
                (if (i.owes) "Owed" else "You have") + " · typed ${if (i.day == today) "today" else MoneyRules.dayMonth(i.day)}",
                owes = i.owes, needsBalance = false, removable = true,
                spoken = "${i.name}, ${if (i.owes) "owed ${fmt(i.pence)}" else fmt(i.pence)}")
        }
        // Slice 5 of the trading tracker: each Hyperliquid account read so far, in pounds at the ECB's rate.
        var tradingPence = 0L
        for (w in trading.filter { it.equityCents != null }) {
            val p = w.equityPence
            val usd = TradingTracker.usd(w.equityCents!!)
            if (p == null) {
                rows += MoneyWorthRow(w.id, w.label, TRADING, "", "$usd · " + NO_RATE.removePrefix("Hyperliquid · "), owes = false,
                    needsBalance = false, removable = false, spoken = "${w.label}, Hyperliquid, $usd, not counted until the dollar rate is in")
                continue
            }
            tradingPence += p
            flat += p
            val rateDay = w.fxDay?.let { if (it == today) "today's rate" else "the rate of ${CivilDate.shortLabel(it)}" } ?: "the latest rate"
            rows += MoneyWorthRow(w.id, w.label, TRADING, signed(p),
                "Hyperliquid · $usd at ${TradingTracker.rateLine(w.gbpPerUsdMicros!!)} · $rateDay",
                owes = p < 0, needsBalance = false, removable = false, spoken = "${w.label}, Hyperliquid, ${signed(p)}")
        }
        val counted = history.isNotEmpty() || rows.any { it.amount.isNotEmpty() }
        val have = history.filter { it.balance > 0 }.sumOf { it.balance } + items.filter { !it.owes }.sumOf { it.pence } + tradingPence.coerceAtLeast(0)
        val owe = -history.filter { it.balance < 0 }.sumOf { it.balance } + items.filter { it.owes }.sumOf { it.pence } - tradingPence.coerceAtMost(0) +
            cards.filter { c -> accounts.any { it.id == c.id } }.sumOf { it.statementPence ?: 0L }
        val total = have - owe
        val points = points(history, flat, today)
        val change = if (points.size >= 2) {
            val d = points.last().pence - points[points.size - 2].pence
            val before = points[points.size - 2].label
            when {
                abs(d) < 100 -> "About the same as the end of $before"
                d > 0 -> "Up ${fmt(d)} since the end of $before"
                else -> "Down ${fmt(-d)} since the end of $before"
            }
        } else null
        val missingLine = when (missing) {
            0 -> null
            1 -> "1 account isn't counted yet: it has no balance"
            else -> "$missing accounts aren't counted yet: they have no balance"
        }
        val split = if (counted) "You have ${fmt(have)} · you owe ${fmt(owe)}" else null
        val chartLine = when {
            !counted -> null
            points.size >= 2 -> "${points.first().label} – ${points.last().label} · tap a month for its total"
            history.isNotEmpty() -> CHART_ONE
            else -> null
        }
        val spoken = if (counted) "Net worth ${signed(total)}. ${split.orEmpty()}. ${change.orEmpty()}" else EMPTY
        return MoneyWorthView(
            HEAD, if (counted) signed(total) else null, total < 0, split, change, rows, missingLine,
            if (points.size >= 2) points else emptyList(), chartLine, if (counted) null else EMPTY, HINT,
            if (tradingPence != 0L) FOOT_TRADING else FOOT, spoken,
        )
    }
}
