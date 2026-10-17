package os.meka.core.domain

/**
 * Money manager (18), slice 1 (2026-10-17): **the year in review** — what he spent and what came in over a year, by
 * category, against the same dates the year before, from his own statements. Rules only, no AI, nothing sent, nothing
 * stored (worked out from the lines each time; no entity, no migration). Insight on his own numbers, not advice.
 *
 * - **In January** the year just finished leads ("2026 IN REVIEW", [MoneyYearView.unfolded] and lit), and one heads-up at
 *   09:00 on 2 January says it's ready ([notice], no amounts).
 * - **The rest of the year** the same block shows this year so far ("2026 SO FAR"), folded until he opens it.
 * - Spending is every spend category (transfers between his accounts and income left out); "in" is income.
 * - **Against the year before**: only over the same dates, and only when his statements reach back that far — "£1,200
 *   less than the same dates in 2025"; each category says "up £340" or "down £120" on them.
 * - The busiest and quietest **whole** months, and the biggest single payment.
 */
data class MoneyYearLine(val day: Long, val pence: Long, val merchantKey: String, val category: MoneyCategory)

/** One category's year: its total, share of the year's spending, and a line ("42 payments · about £120 a month · up £340 on 2025"). */
data class MoneyYearCategory(
    val key: String, val label: String, val amount: String, val share: Float, val line: String, val lit: Boolean, val spoken: String,
)

/**
 * The YEAR IN REVIEW block (this month only): [unfolded] open by default (January's finished year), [title] what was spent
 * and what came in, [leftLine] what was left over ([leftLit] when more went out), [vsLine] against the year before,
 * [categories] the biggest first, [monthsLine] the busiest and quietest whole months, [biggest] the largest payment.
 */
data class MoneyYearView(
    val year: Int,
    val head: String,
    val unfolded: Boolean,
    val folded: String,
    val title: String,
    val leftLine: String?,
    val leftLit: Boolean,
    val vsLine: String?,
    val vsLit: Boolean,
    val categories: List<MoneyYearCategory>,
    val monthsLine: String?,
    val biggest: String?,
    val foot: String,
    val spoken: String,
) {
    fun categoryAt(i: Int): MoneyYearCategory = categories[i]
}

object MoneyYear {
    const val SHOWN = 8
    /** A change on the year before smaller than this reads "about the same". */
    const val SAME_PENCE = 2_000L
    const val NOTICE_DAY = 2
    const val NOTICE_MIN = 9 * 60
    const val FOOT = "From your own statements: everything you spent, bills included; income and transfers between your accounts left out. Not financial advice."

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun n(k: Int, one: String, many: String = one + "s") = "$k ${if (k == 1) one else many}"
    private fun yearOf(day: Long) = CivilDate.fromEpochDay(day).year
    private fun dayMonth(day: Long) = CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }

    /** The same calendar date a year earlier (29 Feb → 28 Feb). */
    fun yearBefore(day: Long): Long {
        val d = CivilDate.fromEpochDay(day)
        val y = d.year - 1
        return CivilDate.toEpochDay(y, d.month, minOf(d.day, CivilDate.lengthOfMonth(y, d.month)))
    }

    /** The year to review on [today]: last year in January, else this year so far. */
    fun yearFor(today: Long): Int = CivilDate.fromEpochDay(today).let { if (it.month == 1) it.year - 1 else it.year }

    /**
     * The block for [today] from every line (counted once). Null when the year has no statements (a finished year), or
     * fewer than two months of them (this year so far).
     */
    fun view(lines: List<MoneyYearLine>, today: Long): MoneyYearView? {
        if (lines.isEmpty()) return null
        val year = yearFor(today)
        val finished = year < CivilDate.fromEpochDay(today).year
        val yearStart = CivilDate.toEpochDay(year, 1, 1)
        val yearEnd = CivilDate.toEpochDay(year, 12, 31)
        val firstData = lines.minOf { it.day }
        val lastData = lines.maxOf { it.day }
        val mine = lines.filter { it.day in yearStart..yearEnd }
        if (mine.isEmpty()) return null
        val months = mine.map { MoneyRules.monthOf(it.day) }.toSet()
        if (!finished && months.size < 2) return null
        val from = maxOf(yearStart, firstData)
        val to = minOf(yearEnd, lastData, today)

        fun spendOf(ls: List<MoneyYearLine>): Map<MoneyCategory, Long> = ls.filter { it.category.spend }
            .groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }.filterValues { it > 0 }
        val spend = spendOf(mine)
        val spent = spend.values.sum()
        val income = mine.filter { it.category == MoneyCategory.INCOME && it.pence > 0 }.sumOf { it.pence }

        // The year before, over the same dates, only when his statements reach back to its start.
        val prevFrom = yearBefore(from)
        val prevTo = yearBefore(to)
        val prev = if (firstData <= prevFrom) lines.filter { it.day in prevFrom..prevTo } else emptyList()
        val prevSpend = if (prev.isEmpty()) null else spendOf(prev)
        val wholeYear = from == yearStart && to == yearEnd
        val versus = if (wholeYear) "${year - 1}" else "the same dates in ${year - 1}"
        val vsLine = prevSpend?.let { ps ->
            val d = spent - ps.values.sum()
            when {
                d >= SAME_PENCE -> "${fmt(d)} more than $versus"
                -d >= SAME_PENCE -> "${fmt(-d)} less than $versus"
                else -> "About the same as $versus"
            }
        }
        val vsLit = prevSpend != null && spent - prevSpend.values.sum() >= SAME_PENCE

        val spanMonths = ((MoneyRules.monthOf(to) - MoneyRules.monthOf(from)) + 1).coerceAtLeast(1)
        val categories = spend.entries.sortedByDescending { it.value }.take(SHOWN).map { (c, p) ->
            val count = mine.count { it.category == c && it.pence < 0 }
            val change = prevSpend?.let { ps ->
                val d = p - (ps[c] ?: 0L)
                when {
                    (ps[c] ?: 0L) == 0L -> "none the year before"
                    d >= SAME_PENCE -> "up ${fmt(d)} on ${year - 1}"
                    -d >= SAME_PENCE -> "down ${fmt(-d)} on ${year - 1}"
                    else -> "about the same as ${year - 1}"
                }
            }
            val lit = prevSpend != null && (prevSpend[c] ?: 0L) > 0L && p - (prevSpend[c] ?: 0L) >= SAME_PENCE * 5
            val line = listOfNotNull(n(count, "payment"), "about ${fmt(p / spanMonths)} a month", change).joinToString(" · ")
            MoneyYearCategory(c.key, c.label, fmt(p), if (spent > 0) (p.toFloat() / spent).coerceIn(0f, 1f) else 0f, line, lit,
                "${c.label}, ${fmt(p)}, $line")
        }

        // The busiest and quietest whole months his statements cover.
        val whole = months.filter { m ->
            val y = m / 12; val mo = m % 12 + 1
            CivilDate.toEpochDay(y, mo, 1) >= firstData && CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo)) <= minOf(lastData, today - 1)
        }
        val byMonth = whole.associateWith { m -> spendOf(mine.filter { MoneyRules.monthOf(it.day) == m }).values.sum() }
        val monthsLine = if (byMonth.size < 2) null else {
            val most = byMonth.maxBy { it.value }
            val least = byMonth.minBy { it.value }
            "Busiest month ${CivilDate.MONTH_LONG[most.key % 12]} · ${fmt(most.value)} · quietest ${CivilDate.MONTH_LONG[least.key % 12]} · ${fmt(least.value)}"
        }
        val big = mine.filter { it.category.spend && it.pence < 0 }.minByOrNull { it.pence }
        val biggest = big?.let { "Biggest payment · ${fmt(-it.pence)} · ${MoneyCategoryRules.merchantLabel(it.merchantKey)} on ${CivilDate.shortLabel(it.day)}" }

        val head = if (finished) "$year IN REVIEW" else "$year SO FAR"
        val title = "Spent ${fmt(spent)}" + (if (income > 0) " · in ${fmt(income)}" else "")
        val leftLine = if (income <= 0) null else (income - spent).let { d ->
            when {
                d >= SAME_PENCE -> "${fmt(d)} more came in than went out"
                -d >= SAME_PENCE -> "${fmt(-d)} more went out than came in"
                else -> "About as much went out as came in"
            }
        }
        val leftLit = income > 0 && spent - income >= SAME_PENCE
        val covered = if (wholeYear) "all of $year" else "${dayMonth(from)} – ${dayMonth(to)} $year · ${n(spanMonths, "month")}"
        val folded = if (finished) "Your $year in review · $title" else "Your year so far · $title"
        val foot = "From your statements, $covered. $FOOT"
        val spoken = listOfNotNull(head.lowercase().replaceFirstChar { it.uppercase() }, title, leftLine, vsLine,
            categories.take(3).joinToString(", ") { "${it.label} ${it.amount}" }.ifEmpty { null }, monthsLine, biggest).joinToString(". ")
        return MoneyYearView(year, head, finished, folded, title, leftLine, leftLit, vsLine, vsLit, categories, monthsLine, biggest, foot, spoken)
    }

    /** The year whose review is ready on [today]: in January, last year when it has statements; else null. */
    fun readyYear(days: Iterable<Long>, today: Long): Int? {
        val d = CivilDate.fromEpochDay(today)
        if (d.month != 1) return null
        val y = d.year - 1
        return y.takeIf { days.any { yearOf(it) == y } }
    }

    /** The heads-up at 09:00 on 2 January that last year's review is ready (no amounts; it's outside the fingerprint). */
    fun notices(readyYear: Int?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val y = readyYear ?: return emptyList()
        val at = CivilDate.toEpochDay(y + 1, 1, NOTICE_DAY)
        val end = CivilDate.toEpochDay(y + 1, 2, 1)
        val today = cal.epochDayOf(nowMs)
        if (today < at || today >= end) return emptyList()
        return listOf(
            Notice(
                key = "money-year:$y", source = NoticeSource.MONEY, tier = NoticeTier.HEADS_UP,
                title = "Your $y money year in review is ready",
                text = "In Money, behind your fingerprint · what you spent and what came in, by category, against the year before",
                atMs = cal.toEpochMs(at, NOTICE_MIN), target = NoticeTarget.MONEY, expiresAtMs = cal.toEpochMs(end, 0),
            ),
        )
    }
}
