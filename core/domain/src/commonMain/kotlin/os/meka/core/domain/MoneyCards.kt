package os.meka.core.domain

import kotlin.math.roundToInt

/**
 * Money manager, slice 7 (2026-10-17): **Cards** (board item 8) on the Money screen's current month, behind his
 * fingerprint like the rest. He says which of his accounts is a card (a credit card, or a charge card paid in full each
 * month) and types what its statement says: the statement balance, the minimum, the day of the month it's due, the
 * limit and when a 0% period ends. Navilife then shows, per card:
 *  - when the payment is due ("Due Fri 23 Oct · in 6 days", lit three days out) with **Paid** (Undo) once he's paid it;
 *  - the statement and minimum with "pay in full to avoid interest" (a charge card: "a charge card is paid in full");
 *  - how much of the limit the statement uses ("32% of your £2,000 limit", lit from 75%);
 *  - the 0% period ending ("0% ends Mon 1 Feb 2027 · in 3 months", lit within 30 days, and once it has ended);
 *  - interest or a late fee charged on the card in its statements over the last two months, lit.
 * A heads-up at 09:00 three days before each payment (until Paid) and 30 and 7 days before a 0% period ends, with no
 * amounts in them (they show on the lock screen; the figures stay in Money). Rules only, no AI; facts on his own
 * numbers, never advice; Navilife never pays anything.
 *
 * Stored as additive LWW fields on `money_account` ([MoneyCardFields], ADR-008 addendum); nothing new is sent anywhere.
 */
object MoneyCardFields {
    /** "credit" or "charge" while the account is a card; Null when it isn't (or after Not a card). */
    const val KIND = "cardKind"
    /** The credit limit in pence. */
    const val LIMIT = "cardLimitPence"
    /** The last statement's balance in pence (what he owes on it). */
    const val STATEMENT = "cardStatementPence"
    /** The statement's minimum payment in pence. */
    const val MINIMUM = "cardMinPence"
    /** The day of the month the payment is due, 1–31 (the month's last day when it has fewer). */
    const val DUE = "cardDueDay"
    /** The epoch day a 0% (promotional) period ends. */
    const val PROMO_END = "cardPromoEndDay"
    /** The epoch day of the due date he marked Paid. */
    const val PAID_FOR = "cardPaidFor"

    val ALL = listOf(KIND, LIMIT, STATEMENT, MINIMUM, DUE, PROMO_END, PAID_FOR)
}

/** One card as stored: its account [id] and [name], [kind] and what he typed (null where he hasn't). */
data class MoneyCard(
    val id: String, val name: String, val kind: String,
    val limitPence: Long? = null, val statementPence: Long? = null, val minPence: Long? = null,
    val dueDayOfMonth: Int? = null, val promoEndDay: Long? = null, val paidFor: Long? = null,
) {
    val charge: Boolean get() = kind == MoneyCards.CHARGE
}

/** A typed field on a card ([MoneyCards.FIELDS]): its key, label, hint and what's kept now ([value], blank for nothing). */
data class MoneyCardField(val key: String, val label: String, val hint: String, val value: String)

/**
 * One card on the Money screen: [dueLine] (lit when due within three days and not paid), [statementLine],
 * [useLine] (lit from 75%), [promoLine] (lit within 30 days or ended), [feesLine] (lit), [canPay] while a due date is
 * waiting for Paid, [paidLine] once it's paid, and the [fields] to type.
 */
data class MoneyCardRow(
    val id: String, val name: String, val kindLine: String,
    val dueLine: String, val dueLit: Boolean, val canPay: Boolean, val paidLine: String?,
    val statementLine: String?, val useLine: String?, val useLit: Boolean,
    val promoLine: String?, val promoLit: Boolean, val feesLine: String?,
    val charge: Boolean, val fields: List<MoneyCardField>, val spoken: String,
)

/** An account he could mark as a card. */
data class MoneyCardOffer(val id: String, val name: String)

/** The CARDS block: [head], each card, the accounts he could mark as one ([offers] under [offerLine]) and [foot]. */
data class MoneyCardsView(
    val head: String, val rows: List<MoneyCardRow>, val offerLine: String?, val offers: List<MoneyCardOffer>, val foot: String,
)

/** A card's statement line, for its fees: its day, signed pence and words. */
data class MoneyCardLine(val account: String, val day: Long, val pence: Long, val text: String)

object MoneyCards {
    const val CREDIT = "credit"
    const val CHARGE = "charge"
    const val HEAD = "CARDS"
    const val FOOT = "From what you type off each statement · a reminder 3 days before each payment and before 0% ends · Navilife never pays anything · not financial advice"
    const val OFFER_FIRST = "Is one of these a card? Mark it to keep its due date, minimum and limit here:"
    const val OFFER_MORE = "Another card:"
    const val MARK = "It's a card"
    const val PAID = "Paid"
    const val NOT_CARD = "Not a card"
    const val CHARGE_SWITCH = "Charge card (paid in full)"
    const val BAD_AMOUNT = "Type an amount, e.g. £640 or £25.50."
    const val BAD_DUE = "Type the day of the month it's due, e.g. 23 or 23rd."
    const val BAD_PROMO = "Type the day 0% ends, e.g. 1 Feb 2027 or 01/02/2027."
    const val NOT_A_CARD = "That account isn't marked as a card."
    const val NO_DUE = "Type the day of the month the payment is due"
    /** A reminder this many days before the due date. */
    const val REMIND_DAYS = 3
    const val NOTICE_MIN = 9 * 60
    const val USE_LIT_PERCENT = 75
    const val PROMO_LIT_DAYS = 30L
    val PROMO_REMIND_DAYS = listOf(30L, 7L)
    const val FEE_LOOK_BACK_DAYS = 62L
    const val MAX_PENCE = 10_000_000_00L

    const val LIMIT = "limit"
    const val STATEMENT = "statement"
    const val MINIMUM = "minimum"
    const val DUE = "due"
    const val PROMO = "promo"
    val FIELDS = listOf(STATEMENT, MINIMUM, DUE, LIMIT, PROMO)

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Whether [name] reads like a card ("Credit card", "Amex", "Visa"), so it's offered first. */
    fun looksLikeCard(name: String): Boolean =
        Regex("""\b(card|credit|amex|american express|visa|mastercard|barclaycard|charge)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** An amount typed for a card ("£640", "640.20"; minus signs dropped): pence, or null. Blank → 0 means cleared. */
    fun parseAmount(text: String): Long? {
        val p = MoneyCsv.pence(text.trim().replace("−", "-")) ?: return null
        val a = kotlin.math.abs(p)
        return if (a > MAX_PENCE) null else a
    }

    /** "23", "23rd", "the 23rd", "23 each month": 1–31, or null. */
    fun parseDue(text: String): Int? {
        val m = Regex("""^(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\b""", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        return m.groupValues[1].toInt().takeIf { it in 1..31 }
    }

    /** "1 Feb 2027", "01/02/2027", "2027-02-01" or "Feb 2027" (its 1st): an epoch day after [today] less a year, or null. */
    fun parsePromo(text: String, today: Long): Long? {
        val t = text.trim()
        val day = MoneyCsv.day(t) ?: Regex("""^([A-Za-z]{3})[A-Za-z]*\.?\s+(\d{4})$""").find(t)?.let { m ->
            val mo = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.groupValues[1].lowercase()) + 1
            val y = m.groupValues[2].toInt()
            if (mo > 0 && y in 2000..2100) CivilDate.toEpochDay(y, mo, 1) else null
        } ?: return null
        return day.takeIf { it >= today - 366 && it <= today + 366 * 5 }
    }

    /** The due date for [dayOfMonth] in [year]/[month] (the month's last day when it's shorter). */
    private fun dueIn(year: Int, month: Int, dayOfMonth: Int): Long =
        CivilDate.toEpochDay(year, month, minOf(dayOfMonth, CivilDate.lengthOfMonth(year, month)))

    /** The due date on or after [today] (the one after when that's already marked Paid), or null with no due day. */
    fun nextDue(card: MoneyCard, today: Long): Long? {
        val d = card.dueDayOfMonth ?: return null
        val t = CivilDate.fromEpochDay(today)
        var y = t.year
        var m = t.month
        repeat(3) {
            val c = dueIn(y, m, d)
            if (c >= today && (card.paidFor == null || card.paidFor < c)) return c
            if (m == 12) { y++; m = 1 } else m++
        }
        return null
    }

    /** The due date he last marked Paid, if it's this month's (or still ahead), for "Paid for …". */
    private fun paidRecently(card: MoneyCard, today: Long): Long? = card.paidFor?.takeIf { today - it <= 20 }

    private fun inDays(n: Long): String = when (n) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $n days"
    }

    /** "in 3 months", "in 5 weeks", "in 12 days". */
    private fun inWhile(n: Long): String = when {
        n < 0 -> "ended"
        n <= 1 -> inDays(n)
        n < 35 -> if (n % 7 == 0L) "in ${n / 7} week${if (n == 7L) "" else "s"}" else "in $n days"
        else -> (n / 30.4).toInt().let { m -> if (m <= 1) "in a month" else "in $m months" }
    }

    private val FEE = Regex("""\b(interest|late (?:payment )?fee|late payment|over ?limit fee|default fee|returned payment fee)\b""", RegexOption.IGNORE_CASE)

    private fun fieldsOf(c: MoneyCard): List<MoneyCardField> = listOf(
        MoneyCardField(STATEMENT, "Statement balance", "Statement balance, e.g. £640", c.statementPence?.let(::fmt).orEmpty()),
        MoneyCardField(MINIMUM, "Minimum payment", "Minimum payment, e.g. £25", c.minPence?.let(::fmt).orEmpty()),
        MoneyCardField(DUE, "Due on the", "Due on the… e.g. 23rd", c.dueDayOfMonth?.let { ordinal(it) }.orEmpty()),
        MoneyCardField(LIMIT, "Credit limit", if (c.charge) "Spending limit, if it has one" else "Credit limit, e.g. £2,000", c.limitPence?.let(::fmt).orEmpty()),
        MoneyCardField(PROMO, "0% ends", "0% ends, e.g. 1 Feb 2027 (if it has one)", c.promoEndDay?.let { MoneyRules.dayMonth(it) }.orEmpty()),
    )

    fun ordinal(n: Int): String = "$n" + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    fun row(c: MoneyCard, today: Long, lines: List<MoneyCardLine>): MoneyCardRow {
        val due = nextDue(c, today)
        val paid = paidRecently(c, today)
        val nothingOwed = c.statementPence == 0L
        val dueLine = when {
            c.dueDayOfMonth == null -> NO_DUE
            due == null -> "Due on the ${ordinal(c.dueDayOfMonth)} of each month"
            else -> "Due ${CivilDate.shortLabel(due)} · ${inDays(due - today)}"
        }
        val dueLit = due != null && !nothingOwed && due - today <= REMIND_DAYS
        val paidLine = paid?.let { "Paid for ${CivilDate.shortLabel(it)}" }
        val st = c.statementPence
        val statementLine = when {
            st == null -> null
            st == 0L -> "Statement £0 · nothing to pay"
            else -> "Statement ${fmt(st)}" + (c.minPence?.takeIf { !c.charge && it > 0 }?.let { " · minimum ${fmt(it)}" } ?: "") +
                if (c.charge) " · a charge card is paid in full" else " · pay in full to avoid interest"
        }
        val lim = c.limitPence?.takeIf { it > 0 }
        val pct = if (lim != null && st != null) ((st * 100.0) / lim).roundToInt() else null
        val useLine = when {
            lim == null -> null
            pct == null -> "Limit ${fmt(lim)}"
            else -> "$pct% of your ${fmt(lim)} limit"
        }
        val useLit = pct != null && pct >= USE_LIT_PERCENT
        val pe = c.promoEndDay
        val promoLine = pe?.let { if (it < today) "0% ended ${MoneyRules.dayMonth(it)} · anything still owed now costs interest"
            else "0% ends ${CivilDate.shortLabel(it)}${if (CivilDate.fromEpochDay(it).year != CivilDate.fromEpochDay(today).year) " ${CivilDate.fromEpochDay(it).year}" else ""} · ${inWhile(it - today)}" }
        val promoLit = pe != null && pe - today <= PROMO_LIT_DAYS
        val fees = lines.filter { it.account == c.id && it.pence < 0 && today - it.day in 0..FEE_LOOK_BACK_DAYS && FEE.containsMatchIn(it.text) }
            .sortedByDescending { it.day }
        val feesLine = if (fees.isEmpty()) null else fees.take(3).joinToString(" · ") { f ->
            val what = if (Regex("interest", RegexOption.IGNORE_CASE).containsMatchIn(f.text)) "Interest" else "Late fee"
            "$what ${fmt(-f.pence)} charged ${CivilDate.shortLabel(f.day)}"
        } + (if (fees.size > 3) " · and ${fees.size - 3} more" else "")
        val kindLine = if (c.charge) "Charge card" else "Credit card"
        val spoken = listOfNotNull(c.name, kindLine, dueLine, paidLine, statementLine, useLine, promoLine, feesLine).joinToString(", ")
        return MoneyCardRow(
            c.id, c.name, kindLine, dueLine, dueLit, canPay = due != null && !nothingOwed, paidLine = paidLine,
            statementLine = statementLine, useLine = useLine, useLit = useLit, promoLine = promoLine, promoLit = promoLit,
            feesLine = feesLine, charge = c.charge, fields = fieldsOf(c), spoken = spoken,
        )
    }

    /** The CARDS block: each card (soonest due first), then the other accounts he could mark (card-like names first). */
    fun view(cards: List<MoneyCard>, accounts: List<MoneyAccount>, lines: List<MoneyCardLine>, today: Long): MoneyCardsView {
        val ids = cards.map { it.id }.toSet()
        val rows = cards.sortedWith(compareBy({ nextDue(it, today) ?: Long.MAX_VALUE }, { it.name.lowercase() })).map { row(it, today, lines) }
        val offers = accounts.filter { it.id !in ids }
            .sortedWith(compareBy({ !looksLikeCard(it.name) }, { it.name.lowercase() }))
            .map { MoneyCardOffer(it.id, it.name) }
        val offerLine = if (offers.isEmpty()) null else if (rows.isEmpty()) OFFER_FIRST else OFFER_MORE
        return MoneyCardsView(HEAD, rows, offerLine, offers, FOOT)
    }

    fun markedSaid(name: String): String = "$name is a card · type what its statement says"
    fun unmarkedSaid(name: String): String = "$name is no longer a card here · its transactions stay"
    fun paidSaid(name: String, due: Long): String = "$name marked paid for ${CivilDate.shortLabel(due)} · no reminder until the next one"
    fun kindSaid(name: String, charge: Boolean): String = if (charge) "$name is a charge card · paid in full each month" else "$name is a credit card"

    fun fieldSaid(name: String, field: String, value: String?): String {
        val label = when (field) {
            STATEMENT -> "statement balance"
            MINIMUM -> "minimum payment"
            DUE -> "due day"
            LIMIT -> "limit"
            else -> "0% end"
        }
        return if (value == null) "$name's $label cleared" else "$name's $label is $value"
    }

    /**
     * The heads-ups: three days before each payment until Paid, and 30 and 7 days before a 0% period ends. No amounts.
     * Bills due, completeness: a radar bill that is this card's payment ([BillRules.cardFor], among [bills]) has no
     * reminder of its own; its Pay link, when he saved one, comes on this one.
     */
    fun notices(cards: List<MoneyCard>, nowMs: Long, cal: LocalCalendar, bills: List<RenewalItem> = emptyList()): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val out = mutableListOf<Notice>()
        for (c in cards) {
            val due = nextDue(c, today)
            if (due != null && c.statementPence != 0L && due - today in 0..REMIND_DAYS.toLong()) {
                val left = due - today
                val inWords = when (left) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "In $left days" }
                val dayWord = when (left) { 0L -> "today"; 1L -> "tomorrow"; else -> CivilDate.shortLabel(due) }
                val link = bills.firstOrNull { it.payLink != null && BillRules.cardFor(it, listOf(c), today) != null }?.payLink
                out += Notice(
                    key = "card:${c.id}:due:$due", source = NoticeSource.CARDS, tier = NoticeTier.HEADS_UP,
                    title = "${c.name} payment due $dayWord",
                    text = "$inWords · " + (if (c.charge) "a charge card is paid in full" else "pay in full to avoid interest") +
                        (if (link != null) " · Pay opens ${BillRules.site(link)}" else "") +
                        " · the amounts are in Money · mark it Paid there",
                    atMs = cal.toEpochMs(due - REMIND_DAYS, NOTICE_MIN), target = NoticeTarget.MONEY,
                    expiresAtMs = cal.toEpochMs(due + 1, 0),
                    actions = if (link != null) listOf(NoticeAction.PAY) else emptyList(),
                    link = link,
                )
            }
            val pe = c.promoEndDay
            if (pe != null && pe >= today) {
                for ((i, n) in PROMO_REMIND_DAYS.withIndex()) {
                    val at = pe - n
                    val next = PROMO_REMIND_DAYS.getOrNull(i + 1)?.let { pe - it } ?: (pe + 1)
                    if (today < at || today >= next) continue
                    out += Notice(
                        key = "card:${c.id}:promo:$pe:$n", source = NoticeSource.CARDS, tier = NoticeTier.HEADS_UP,
                        title = "0% on ${c.name} ends ${CivilDate.shortLabel(pe)}",
                        text = "${if (n == 7L) "In a week" else "In a month"} · anything still owed then starts costing interest",
                        atMs = cal.toEpochMs(at, NOTICE_MIN), target = NoticeTarget.MONEY, expiresAtMs = cal.toEpochMs(next, 0),
                    )
                }
            }
        }
        return out
    }

    /** Ask's Money tile while a card's payment is due within three days and not paid (no amounts): "Amex due Fri 23 Oct". */
    fun tileLine(cards: List<MoneyCard>, today: Long): LifeTileLine? = cards
        .mapNotNull { c -> nextDue(c, today)?.takeIf { c.statementPence != 0L && it - today in 0..REMIND_DAYS.toLong() }?.let { c to it } }
        .minByOrNull { it.second }
        ?.let { (c, d) -> LifeTileLine("${c.name} due ${if (d == today) "today" else CivilDate.shortLabel(d)}", true) }

    /** Payday's "still to go out": each card due before payday with its statement (not yet Paid). */
    fun bills(cards: List<MoneyCard>, today: Long): List<MoneyBill> = cards.mapNotNull { c ->
        val d = nextDue(c, today) ?: return@mapNotNull null
        val p = c.statementPence?.takeIf { it > 0 } ?: return@mapNotNull null
        MoneyBill(c.name, d, p)
    }
}
