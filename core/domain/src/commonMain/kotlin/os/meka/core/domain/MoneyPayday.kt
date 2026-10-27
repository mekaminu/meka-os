package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money manager, slice 6 (2026-10-16): **the payday view** (board item 6) with **the low-balance warning** (item 10) on
 * the Money screen's current month, behind his fingerprint like the rest. Rules only, no AI; insight on his own numbers,
 * never advice, and Navilife never moves money.
 *
 * - **Payday** comes from his pay in his statements: the biggest money in (£200+, Income) that lands about monthly in the
 *   same account from the same payer, twice or more in the last 100 days. Its day of the month is the latest of the last
 *   three (a weekend or bank holiday moves pay earlier, never later), and the next one moves back to the working day
 *   before when it falls on a weekend or bank holiday.
 * - **Since payday**: what he's spent (every account's spending) since the last pay, and a day.
 * - **Still to go out before payday**: the pay account's regular payments ([MoneyTips.regular]) and the bills, subscriptions
 *   and club fees with a cost on Lists → Renewals, due before payday (a radar item that is one of those regular payments
 *   counts once).
 * - **Safe to spend**: the pay account's balance (from a statement file's Balance column, or typed) plus anything in its
 *   statements since, less what's still to go out, and a day until payday; lit as the low-balance warning when it's under
 *   [LOW_PENCE] ("After the bills before payday you'd have about £86") or below nothing.
 *
 * The balance is two additive fields on `money_account` (ADR-008 addendum); everything else is worked out on the device.
 */
data class MoneyBill(val title: String, val dueDay: Long, val pence: Long)

/** One payment still to go out before payday: "Netflix" · "Fri 23 Oct" · "£10.99". */
data class MoneyPaydayRow(val key: String, val title: String, val day: String, val amount: String, val spoken: String)

/**
 * The payday block: [head], then either [notReady] or [payLine] ("Payday Fri 30 Oct · in 14 days"), [fromLine] (who
 * pays it into which account), [sinceLine], [goingOut] with its [rows] ([more] past [MoneyPayday.SHOWN]), the pay
 * account ([account], [accountName]) and its [balanceLine], [safe] ([safeLit] for the warning) and [foot].
 */
data class MoneyPaydayView(
    val head: String, val notReady: String?, val payLine: String?, val fromLine: String?, val sinceLine: String?,
    val goingOut: String?, val rows: List<MoneyPaydayRow>, val more: Int,
    val account: String?, val accountName: String?, val balanceLine: String?, val balanceKnown: Boolean,
    val safe: String?, val safeLit: Boolean, val foot: String,
    /** Money manager (16), Ask: the next payday and, with a balance, what's left after the bills before it (pence). */
    val paydayDay: Long? = null, val leftPence: Long? = null,
    /** Money redesign (B2): what's still to go out before payday (pence), for Spending's "Bills to come" tile. */
    val toGoPence: Long? = null,
    /** Money redesign (B3): the Bills tab's card and list, from the same bills ([MoneyBills]). */
    val bills: MoneyBillsView? = null,
)

/**
 * Money review (Meka 2026-10-26 22:12, Bills): Until payday as one line on Bills, "Payday Fri 30 Oct · in 3 days" over
 * "Safe to spend … about £410"; a tap opens its sheet (since payday, the pay account's balance with its field, the
 * foot), so the balance field never sits open on the page. [lit] when the safe-to-spend line is the low-balance warning.
 */
data class MoneyPaydayBrief(val title: String, val line: String?, val lit: Boolean, val spoken: String)

/** A line of the statements for the payday view: its account, day, signed pence, merchant key and category. */
data class MoneyPayLine(val account: String, val day: Long, val pence: Long, val merchant: String, val category: MoneyCategory)

/** An account's balance: [pence] (signed: overdrawn is negative) as of [day], [typed] or from a statement. */
data class MoneyBalance(val pence: Long, val day: Long, val typed: Boolean)

object MoneyPayday {
    const val HEAD = "UNTIL PAYDAY"
    const val NOT_READY = "Navilife finds your payday from your pay coming in · import two months or more of the account it goes into."
    const val FOOT = "From your statements, Lists → Renewals and Cards (a card due before payday counts its statement) · not financial advice"

    /** [FOOT] naming where the lines really came from (Money bugs A4): "From your banks through Lunch Flow, Lists → …". */
    fun foot(source: MoneySource): String =
        if (source == MoneySource.FILES) FOOT else FOOT.replaceFirst("From your statements", source.from)
    const val BALANCE_HINT = "Balance now: £1,240 (−£50 if overdrawn)"
    /** Until payday's row before payday is found. */
    const val NOT_FOUND = "Payday not found yet"
    const val HOW_FOUND = "tap to see how it's found"
    /** Until payday's row while the pay account's balance isn't in. */
    const val ADD_BALANCE = "Add the balance to see what's safe to spend"
    const val DONE = "Done"
    const val BALANCE_BAD = "Type the balance, e.g. £1,240.50, or −£50 if it's overdrawn."
    const val BALANCE_CLEARED = "Balance cleared · type it again any time"
    const val PAY_MIN_PENCE = 20_000L
    const val LOOK_BACK_DAYS = 100L
    const val LOW_PENCE = 10_000L
    const val SHOWN = 6
    const val MAX_BALANCE_PENCE = 100_000_000_00L
    /** A balance older than this (days) says payments since aren't in it. */
    const val STALE_DAYS = 3L

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Pence rounded to whole pounds. */
    private fun pounds(p: Long): Long = (p / 100.0).roundToLong() * 100

    /** Until payday's one row on Bills ([MoneyPaydayBrief]). */
    fun brief(p: MoneyPaydayView): MoneyPaydayBrief {
        val title = p.payLine ?: NOT_FOUND
        val line = when {
            p.payLine == null -> HOW_FOUND
            p.safe != null -> p.safe
            p.account != null && !p.balanceKnown -> ADD_BALANCE
            else -> p.sinceLine
        }
        return MoneyPaydayBrief(title, line, p.safe != null && p.safeLit, listOfNotNull(title, line).joinToString(", ") + ", opens Until payday")
    }

    /** "£1,240", "−£50"; "£1,240.50" stays exact; blank → 0 means cleared. Null when it isn't an amount. */
    fun parseBalance(text: String): Long? {
        var s = text.trim().lowercase().replace("−", "-").replace("–", "-")
        val overdrawn = s.contains("overdrawn") || s.endsWith(" od") || s.endsWith("od")
        s = s.replace("overdrawn", "").removeSuffix("od").replace("by", "").trim()
        val p = MoneyCsv.pence(s) ?: return null
        if (abs(p) > MAX_BALANCE_PENCE) return null
        return if (overdrawn && p > 0) -p else p
    }

    fun balanceSaid(name: String, pence: Long): String = "$name's balance is ${MoneyRules.signedPlain(pence)} · safe to spend follows it"

    /** The pay: the account, the payer's key and the pay days (oldest first), or null. */
    data class Pay(val account: String, val merchant: String, val days: List<Long>, val pence: Long)

    fun pay(lines: List<MoneyPayLine>, today: Long): Pay? = lines
        .filter { it.category == MoneyCategory.INCOME && it.pence >= PAY_MIN_PENCE && it.day <= today && today - it.day <= LOOK_BACK_DAYS }
        .groupBy { it.account to it.merchant }
        .mapNotNull { (k, ls) ->
            // One pay a day at most (a split payment the same day counts once).
            val byDay = ls.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.pence } }
            val days = byDay.keys.sorted()
            if (days.size < 2 || today - days.last() > 40) return@mapNotNull null
            val recent = days.takeLast(3)
            if (recent.zipWithNext().any { (a, b) -> b - a !in 24..38 }) return@mapNotNull null
            val amounts = recent.map { byDay.getValue(it) }.sorted()
            Pay(k.first, k.second, recent, amounts[amounts.size / 2])
        }
        .maxByOrNull { it.pence }

    /** The nominal day of the month he's paid: the latest of the recent pay days (earlier ones were moved for weekends). */
    fun nominalDay(days: List<Long>): Int = days.maxOf { CivilDate.fromEpochDay(it).day }

    /** Moves [day] back to the working day before while it's a weekend or a bank holiday. */
    fun workingDayOnOrBefore(day: Long, holidays: HolidayCalendar): Long {
        var d = day
        while (CivilDate.isoDayOfWeek(d) >= 6 || holidays.isHoliday(d)) d--
        return d
    }

    /**
     * The next payday after [today], after the last pay. Money's wrong numbers (1): on payday itself it's already the
     * next month's, so "until payday" never counts down 0 days ("£5,266 a day") and the bills run to the next pay.
     */
    fun nextPayday(pay: Pay, today: Long, holidays: HolidayCalendar): Long {
        val nominal = nominalDay(pay.days)
        val last = pay.days.last()
        val t = CivilDate.fromEpochDay(today)
        var y = t.year
        var m = t.month
        repeat(4) {
            val c = workingDayOnOrBefore(CivilDate.toEpochDay(y, m, minOf(nominal, CivilDate.lengthOfMonth(y, m))), holidays)
            if (c > today && c > last + 20) return c
            if (m == 12) { y++; m = 1 } else m++
        }
        return today + 30
    }

    private fun inDays(n: Long): String = when (n) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $n days"
    }

    /** Whether a radar bill is one of the regular payments already counted (its name's word, or the same amount nearby). */
    fun sameAs(b: MoneyBill, r: MoneyTips.Regular): Boolean {
        val words = { s: String -> s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet() }
        val bw = words(b.title)
        val rw = words(r.label)
        if (bw.intersect(rw).any { it !in setOf("the", "and", "bill", "payment", "monthly") }) return true
        return abs(b.dueDay - r.nextDay) <= 4 && abs(b.pence - r.monthly) * 10 <= r.monthly
    }

    fun view(
        lines: List<MoneyPayLine>, today: Long, names: Map<String, String>, balances: Map<String, MoneyBalance>,
        bills: List<MoneyBill>, holidays: HolidayCalendar, source: MoneySource = MoneySource.FILES,
        cardTitles: Set<String> = emptySet(),
    ): MoneyPaydayView {
        val pay = pay(lines, today) ?: return MoneyPaydayView(HEAD, NOT_READY, null, null, null, null, emptyList(), 0,
            null, null, null, false, null, false, foot(source))
        val payday = nextPayday(pay, today, holidays)
        val lastPay = pay.days.last()
        val accountName = names[pay.account] ?: "your account"
        val payLine = "Payday ${CivilDate.shortLabel(payday)} · ${inDays(payday - today)}"
        val fromLine = "${MoneyCategoryRules.merchantLabel(pay.merchant)} into $accountName · about ${fmt(pounds(pay.pence))}"
        val spent = -lines.filter { it.category.spend && it.day in lastPay..today }.sumOf { it.pence }
        val sinceDays = (today - lastPay + 1).coerceAtLeast(1)
        val sinceLine = "Since payday on ${CivilDate.shortLabel(lastPay)} · spent ${fmt(pounds(spent.coerceAtLeast(0)))}" +
            (if (spent > 0) " · about ${fmt(pounds(spent / sinceDays))} a day" else "")

        val payLines = lines.filter { it.account == pay.account }
        val bal = balances[pay.account]
        val latestLine = payLines.maxOfOrNull { it.day }
        // What the balance already holds: the balance's day, or the account's latest line when that's later.
        val asOf = bal?.let { maxOf(it.day, latestLine ?: it.day) } ?: (today - 1)
        val allRegular = MoneyTips.regular(payLines.map { MoneyLine(it.day, it.pence, it.merchant, it.category) }, today)
        val regular = allRegular.filter { it.nextDay > asOf && it.nextDay < payday }
        val radar = bills.filter { it.pence > 0 && it.dueDay > asOf && it.dueDay < payday && regular.none { r -> sameAs(it, r) } }
        val out = regular.map { r -> Triple("r-${r.key}", r.label, r.nextDay) to r.monthly } +
            radar.map { b -> Triple("b-${b.title.lowercase()}-${b.dueDay}", b.title, b.dueDay) to b.pence }
        val sorted = out.sortedWith(compareBy({ it.first.third }, { it.first.second }))
        val total = sorted.sumOf { it.second }
        val rows = sorted.take(SHOWN).map { (k, p) ->
            MoneyPaydayRow(k.first, k.second, CivilDate.shortLabel(k.third), fmt(p), "${k.second}, ${CivilDate.shortLabel(k.third)}, ${fmt(p)}")
        }
        // Money redesign (B3): the same bills, each with its kind, for the Bills tab's card and list.
        val due = regular.map { r -> MoneyBills.Due("r-${r.key}", r.label, r.nextDay, r.monthly, MoneyBillKind.REGULAR, r.rise) } +
            radar.map { b ->
                MoneyBills.Due("b-${b.title.lowercase()}-${b.dueDay}", b.title, b.dueDay, b.pence,
                    if (b.title in cardTitles) MoneyBillKind.CARD else MoneyBillKind.RENEWAL)
            }
        val billsView = MoneyBills.view(due, MoneyBills.paidThisMonth(allRegular, today), CivilDate.shortLabel(payday), today)
        val goingOut = if (sorted.isEmpty()) "Nothing regular left to go out before payday" else
            "Still to go out before payday · about ${fmt(pounds(total))} · ${sorted.size} payment${if (sorted.size == 1) "" else "s"}"

        var balanceLine: String
        var safe: String? = null
        var lit = false
        var leftPence: Long? = null
        if (bal == null) {
            balanceLine = "Type $accountName's balance now to see what's safe to spend until payday"
        } else {
            val since = payLines.filter { it.day > bal.day }.sumOf { it.pence }
            val now = bal.pence + since
            val said = if (bal.typed) (if (bal.day == today) "you typed it today" else "you typed it ${CivilDate.shortLabel(bal.day)}")
                else "from your statement, ${CivilDate.shortLabel(bal.day)}"
            balanceLine = "$accountName · ${MoneyRules.signedPlain(now)} · $said" +
                (if (since != 0L) " · with the payments since" else "") +
                (if (asOf < today - STALE_DAYS) " · newer payments aren't in" else "")
            val left = now - total
            leftPence = left
            val daysLeft = (payday - today).coerceAtLeast(1)
            when {
                left < 0 -> {
                    safe = "What's still to go out is ${fmt(pounds(-left))} more than $accountName holds · move money in or check the dates"
                    lit = true
                }
                left < LOW_PENCE -> {
                    safe = "After the bills before payday you'd have about ${fmt(pounds(left))} · about ${fmt(pounds(left / daysLeft))} a day"
                    lit = true
                }
                else -> safe = "Safe to spend until payday · about ${fmt(pounds(left))} · about ${fmt(pounds(left / daysLeft))} a day"
            }
        }
        return MoneyPaydayView(
            HEAD, null, payLine, fromLine, sinceLine, goingOut, rows, (sorted.size - rows.size).coerceAtLeast(0),
            pay.account, accountName, balanceLine, bal != null, safe, lit, foot(source),
            paydayDay = payday, leftPence = leftPence, toGoPence = total, bills = billsView,
        )
    }
}
