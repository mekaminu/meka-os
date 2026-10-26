package os.meka.core.domain

/**
 * Money manager (7), slice 1 (2026-10-18): **the month in review in plain English** — a few sentences on how a finished
 * month went, from his own statements. Rules only, no AI: nothing financial leaves his devices, nothing stored (worked out
 * from the lines each time; no entity, no migration). Facts about his own numbers, never advice.
 *
 * - **Where it shows**: on a finished month he pages back to (‹), its own review; on this month, for the first
 *   [LEADS_DAYS] days, last month's review leads ([MoneyMonthReviewView.lastMonth]).
 * - **The sentences**, each only when it has something to say:
 *   1. what went out and what came in, and what was left over ("so about £660 was left over"; lit when more went out);
 *   2. against the 6 months before it (only months his statements cover);
 *   3. where most of it went (the three biggest categories);
 *   4. what moved: the category furthest over its usual and the one furthest under;
 *   5. a new place: the biggest merchant he hadn't paid in the 6 months before (statements reaching back);
 *   6. the biggest single payment;
 *   7. the days with no everyday spending (bills, subscriptions and fees don't count against a day);
 *   8. (slice 2, 2026-10-18) **the side hustle**: once he's marked a Side hustle account or a Business payer, what it
 *      brought in, what it cost and the profit or loss that month, against the month before when that had any (the
 *      lines [MoneyBusiness.counts] counts, so transfers never; from statements or the bank feed alike).
 * - **A heads-up** at 09:00 on the [NOTICE_DAY]nd when last month has statements to its end ([readyMonth], [notices]; no
 *   amounts, it's outside the fingerprint). Not in January: the year in review's heads-up says it then.
 */
data class MoneyMonthReviewView(
    /** The month reviewed, as [MoneyRules.monthOf]'s index. */
    val month: Int,
    /** "SEPTEMBER IN REVIEW". */
    val head: String,
    /** Shown on this month (last month's review leading) rather than on the month itself. */
    val lastMonth: Boolean,
    /** The plain-English sentences, in order. */
    val sentences: List<String>,
    /** The first sentence lit (brass) when more went out than came in. */
    val firstLit: Boolean,
    val foot: String,
    val spoken: String,
)

object MoneyMonthReview {
    /** Last month's review leads this month for this many days. */
    const val LEADS_DAYS = 10
    /** Fewer lines than this in the month: nothing worth saying. */
    const val MIN_LINES = 5
    /** A difference smaller than this reads "about". */
    const val SAME_PENCE = 2_000L
    /** A category "moved" when it's this far from its usual, and this share of it. */
    const val MOVE_PENCE = 2_000L
    const val MOVE_SHARE = 0.25
    /** A new place is worth a sentence from this much. */
    const val NEW_PENCE = 3_000L
    const val NOTICE_DAY = 2
    const val NOTICE_MIN = 9 * 60
    /** Statements reach "to the end" of a month when their last line is this close to it. */
    const val END_SLACK_DAYS = 4
    /** The footer's end, after where the lines came from ([MoneySource], Money bugs A4) and how they were worked out. */
    const val FOOT = "Transfers between your accounts left out. Not financial advice."

    private val NOT_EVERYDAY = setOf(MoneyCategory.BILLS, MoneyCategory.SUBSCRIPTIONS, MoneyCategory.FEES)

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun monthStart(month: Int) = CivilDate.toEpochDay(month / 12, month % 12 + 1, 1)
    private fun monthEnd(month: Int) = (month / 12).let { y -> CivilDate.toEpochDay(y, month % 12 + 1, CivilDate.lengthOfMonth(y, month % 12 + 1)) }
    private fun name(month: Int) = CivilDate.MONTH_LONG[month % 12]

    /** The month to review on the screen: [shown] when it's finished, else last month in the first [LEADS_DAYS] days. */
    fun monthFor(shown: Int, today: Long): Int? {
        val thisMonth = MoneyRules.monthOf(today)
        return when {
            shown < thisMonth -> shown
            CivilDate.fromEpochDay(today).day <= LEADS_DAYS -> thisMonth - 1
            else -> null
        }
    }

    /**
     * The review of [month] (finished before [today]) from every line (counted once). Null when the month has fewer
     * than [MIN_LINES] lines.
     */
    fun view(
        lines: List<MoneyYearLine>, month: Int, today: Long, business: List<MoneyBusinessLine>? = null,
        source: MoneySource = MoneySource.FILES,
    ): MoneyMonthReviewView? {
        val thisMonth = MoneyRules.monthOf(today)
        if (month >= thisMonth || lines.isEmpty()) return null
        val start = monthStart(month)
        val end = monthEnd(month)
        val mine = lines.filter { it.day in start..end }
        if (mine.size < MIN_LINES) return null
        val firstData = lines.minOf { it.day }
        val lastData = lines.maxOf { it.day }
        val firstMonth = MoneyRules.monthOf(firstData)
        val mo = name(month)

        fun spendOf(ls: List<MoneyYearLine>): Map<MoneyCategory, Long> = ls.filter { it.category.spend }
            .groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }.filterValues { it > 0 }
        val spend = spendOf(mine)
        val spent = spend.values.sum()
        val income = mine.filter { it.category == MoneyCategory.INCOME && it.pence > 0 }.sumOf { it.pence }
        val out = mutableListOf<String>()

        // 1. Out, in, and what was left.
        val left = income - spent
        out += when {
            income <= 0 -> "In $mo you spent ${fmt(spent)}."
            left >= SAME_PENCE -> "In $mo you spent ${fmt(spent)} and ${fmt(income)} came in, so about ${fmt(left)} was left over."
            -left >= SAME_PENCE -> "In $mo you spent ${fmt(spent)} and ${fmt(income)} came in, so ${fmt(-left)} more went out than came in."
            else -> "In $mo you spent ${fmt(spent)} and ${fmt(income)} came in, about as much as went out."
        }
        val firstLit = income > 0 && -left >= SAME_PENCE

        // 2. Against the 6 months before it that his statements cover.
        val byMonth = lines.groupBy { MoneyRules.monthOf(it.day) }
        val prior = (1..6).map { month - it }.filter { it >= firstMonth && byMonth[it].orEmpty().isNotEmpty() }
        val priorSpend = prior.map { spendOf(byMonth[it].orEmpty()) }
        val avgTotal = if (prior.isEmpty()) null else priorSpend.sumOf { it.values.sum() } / prior.size
        val span = if (prior.size == 6) "6-month" else "${prior.size}-month"
        avgTotal?.let { a ->
            out += when {
                spent - a >= SAME_PENCE -> "That's ${fmt(spent - a)} more than your $span average."
                a - spent >= SAME_PENCE -> "That's ${fmt(a - spent)} less than your $span average."
                else -> "That's about your $span average."
            }
        }

        // 3. Where most of it went.
        val top = spend.entries.sortedByDescending { it.value }.take(3)
        if (top.isNotEmpty()) {
            val first = "Most went on ${top[0].key.label} (${fmt(top[0].value)})"
            val rest = top.drop(1).map { "${it.key.label} (${fmt(it.value)})" }
            out += when (rest.size) {
                0 -> "$first."
                1 -> "$first, then ${rest[0]}."
                else -> "$first, then ${rest[0]} and ${rest[1]}."
            }
        }

        // 4. What moved against its usual.
        if (prior.isNotEmpty()) {
            val usual = MoneyCategory.entries.filter { it.spend }.associateWith { c -> priorSpend.sumOf { it[c] ?: 0L } / prior.size }
            val diffs = MoneyCategory.entries.filter { it.spend }.map { c -> c to ((spend[c] ?: 0L) - (usual[c] ?: 0L)) }
            fun moved(c: MoneyCategory, d: Long) = kotlin.math.abs(d) >= MOVE_PENCE && (usual[c] ?: 0L).let { u -> u == 0L || kotlin.math.abs(d) >= u * MOVE_SHARE }
            val up = diffs.filter { (c, d) -> d > 0 && (usual[c] ?: 0L) > 0 && moved(c, d) }.maxByOrNull { it.second }
            val down = diffs.filter { (c, d) -> d < 0 && moved(c, d) }.minByOrNull { it.second }
            val upText = up?.let { "${it.first.label} was ${fmt(it.second)} over its usual" }
            val downText = down?.let { "${it.first.label} ${fmt(-it.second)} under" }
            when {
                upText != null && downText != null -> out += "$upText; $downText."
                upText != null -> out += "$upText."
                down != null -> out += "${down.first.label} was ${fmt(-down.second)} under its usual."
            }
        }

        // 5. A new place: paid this month, not in the 6 months before (only when his statements reach back that far).
        val sixBefore = monthStart(month - 6)
        if (firstData <= sixBefore) {
            val seen = lines.filter { it.day in sixBefore until start && it.pence < 0 }.map { it.merchantKey }.toSet()
            val fresh = mine.filter { it.category.spend && it.pence < 0 && it.merchantKey !in seen }
                .groupBy { it.merchantKey }.mapValues { (_, l) -> -l.sumOf { it.pence } }
                .filterValues { it >= NEW_PENCE }.maxByOrNull { it.value }
            fresh?.let { out += "New this month: ${fmt(it.value)} at ${MoneyCategoryRules.merchantLabel(it.key)}." }
        }

        // 6. The biggest single payment.
        mine.filter { it.category.spend && it.pence < 0 }.minByOrNull { it.pence }?.let {
            out += "The biggest single payment was ${fmt(-it.pence)} to ${MoneyCategoryRules.merchantLabel(it.merchantKey)} on ${CivilDate.shortLabel(it.day)}."
        }

        // 7. Days with no everyday spending, over the days his statements cover.
        val from = maxOf(start, firstData)
        val to = minOf(end, lastData)
        if (to >= from) {
            val busy = mine.filter { it.category.spend && it.pence < 0 && it.category !in NOT_EVERYDAY }.map { it.day }.toSet()
            val days = (to - from + 1).toInt()
            val quiet = (from..to).count { it !in busy }
            if (quiet > 0) out += if (quiet == 1) "One day with no everyday spending." else "$quiet days of $days with no everyday spending."
        }

        // 8. The side hustle's month (only once something is marked, and only when it had lines this month).
        sideHustle(business, month)?.let { out += it }

        val covered = if (from <= start && lastData >= end - END_SLACK_DAYS) "all of $mo" else "${CivilDate.shortLabel(from)} – ${CivilDate.shortLabel(to)}"
        val head = "${mo.uppercase()} IN REVIEW"
        return MoneyMonthReviewView(
            month = month, head = head, lastMonth = false, sentences = out, firstLit = firstLit,
            foot = "${source.from}, $covered. ${source.worked} $FOOT",
            spoken = "$mo in review. " + out.joinToString(" "),
        )
    }

    /**
     * Sentence 8: "Your side hustle brought in £420 and cost £85, a profit of £335, up £120 on August." from the lines
     * the side hustle counts ([business], already filtered); null when nothing is marked or the month had none.
     */
    fun sideHustle(business: List<MoneyBusinessLine>?, month: Int): String? {
        if (business.isNullOrEmpty()) return null
        fun inMonth(m: Int) = business.filter { it.day in monthStart(m)..monthEnd(m) }
        val mine = inMonth(month)
        if (mine.isEmpty()) return null
        fun result(ls: List<MoneyBusinessLine>) = ls.sumOf { it.pence }
        val moneyIn = mine.filter { it.pence > 0 }.sumOf { it.pence }
        val moneyOut = -mine.filter { it.pence < 0 }.sumOf { it.pence }
        val profit = moneyIn - moneyOut
        val head = when {
            moneyIn == 0L -> "Your side hustle had nothing in and cost ${fmt(moneyOut)}"
            moneyOut == 0L -> "Your side hustle brought in ${fmt(moneyIn)} with no costs"
            profit >= 0 -> "Your side hustle brought in ${fmt(moneyIn)} and cost ${fmt(moneyOut)}, a profit of ${fmt(profit)}"
            else -> "Your side hustle brought in ${fmt(moneyIn)} and cost ${fmt(moneyOut)}, a loss of ${fmt(-profit)}"
        }
        val before = inMonth(month - 1)
        val against = if (before.isEmpty()) "" else {
            val d = profit - result(before)
            when {
                d >= SAME_PENCE -> ", up ${fmt(d)} on ${name(month - 1)}"
                -d >= SAME_PENCE -> ", down ${fmt(-d)} on ${name(month - 1)}"
                else -> ", about the same as ${name(month - 1)}"
            }
        }
        return "$head$against."
    }

    /** The view for the Money screen showing [shown] on [today] ([monthFor]), marked when it's last month leading this one. */
    fun forScreen(
        lines: List<MoneyYearLine>, shown: Int, today: Long, business: List<MoneyBusinessLine>? = null,
        source: MoneySource = MoneySource.FILES,
    ): MoneyMonthReviewView? {
        val m = monthFor(shown, today) ?: return null
        return view(lines, m, today, business, source)?.copy(lastMonth = shown == MoneyRules.monthOf(today))
    }

    /**
     * The month whose review is ready on [today]: last month, when his statements reach its end (a line within
     * [END_SLACK_DAYS] of it, or later) and it has [MIN_LINES] lines; never in January (the year's heads-up says it).
     */
    fun readyMonth(days: Iterable<Long>, today: Long): Int? {
        val d = CivilDate.fromEpochDay(today)
        if (d.month == 1 || d.day > LEADS_DAYS) return null
        val m = MoneyRules.monthOf(today) - 1
        val start = monthStart(m)
        val end = monthEnd(m)
        var count = 0
        var reaches = false
        for (day in days) {
            if (day in start..end) count++
            if (day >= end - END_SLACK_DAYS) reaches = true
        }
        return m.takeIf { count >= MIN_LINES && reaches }
    }

    /** The heads-up at 09:00 on the [NOTICE_DAY]nd that last month's review is ready (no amounts); gone after [LEADS_DAYS] days. */
    fun notices(readyMonth: Int?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val m = readyMonth ?: return emptyList()
        val next = m + 1
        val at = CivilDate.toEpochDay(next / 12, next % 12 + 1, NOTICE_DAY)
        val end = CivilDate.toEpochDay(next / 12, next % 12 + 1, LEADS_DAYS + 1)
        val today = cal.epochDayOf(nowMs)
        if (today >= end || today < at - NOTICE_DAY + 1) return emptyList()
        return listOf(
            Notice(
                key = "money-month:${m / 12}-${m % 12 + 1}", source = NoticeSource.MONEY, tier = NoticeTier.HEADS_UP,
                title = "Your ${name(m)} money review is ready",
                text = "In Money, behind your fingerprint · how the month went, in a few sentences",
                atMs = cal.toEpochMs(at, NOTICE_MIN), target = NoticeTarget.MONEY, expiresAtMs = cal.toEpochMs(end, 0),
            ),
        )
    }
}
