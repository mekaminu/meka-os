package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money manager, slice 2 (2026-10-16): **tips from his own numbers, and his regular payments**, on the Money screen's
 * current month (behind his fingerprint, like the rest). Rules over the lines he imported; no AI, never generic advice,
 * never a recommendation to buy, sell or invest. Each kind of tip can be hidden (synced `money_tips`, LWW) and shown
 * again.
 *
 * Tips ([MoneyTipKind]):
 * - **habit**: coffee, takeaways or eating out 8+ times and £30+ in the last 30 days — "Coffee 19× · £71 in the last 30
 *   days" · "Halving it would save about £430 a year".
 * - **over**: a category already £20 and a fifth over its average for the 6 months before ("Clothes £180 over your
 *   6-month average" · "£260 so far in October · 15 days to go"); in a month's first week, last month's too.
 * - **rise**: a regular payment that went up ("Netflix went up £1.50" · "£12.49 a month from Tue 6 Oct · £18 more a
 *   year").
 *
 * Regular payments ([MoneyRegular]): the same shop taken 3+ times about a month apart (26–35 days) for about the same
 * amount, the last within 40 days: "Netflix · £12.49 a month" · "Subscriptions · last Tue 6 Oct · next about Fri 6 Nov".
 */
enum class MoneyTipKind(val key: String, val label: String) {
    HABIT("habit", "habits"),
    OVER("over", "over your average"),
    RISE("rise", "price rises"),
    ;

    companion object {
        fun of(key: String?): MoneyTipKind? = entries.firstOrNull { it.key == key }
    }
}

/** One tip: [title] the fact, [line] what it means; [lit] draws it in the accent. [kind] is what Hide hides. */
data class MoneyTip(val id: String, val kind: String, val title: String, val line: String, val lit: Boolean, val spoken: String)

/** One regular payment: [amount] "£12.49 a month"; [lit] when it went up. */
data class MoneyRegularRow(val key: String, val merchant: String, val amount: String, val line: String, val lit: Boolean, val spoken: String)

/** The regular payments: [head] "6 regular payments · about £84 a month · £1,008 a year". */
data class MoneyRegular(val head: String, val rows: List<MoneyRegularRow>)

/** A statement line as the tips see it: its merchant key and category already worked out. */
data class MoneyLine(val day: Long, val pence: Long, val merchant: String, val category: MoneyCategory)

object MoneyTips {
    const val HABIT_MIN_COUNT = 8
    const val HABIT_MIN_PENCE = 3_000L
    const val MAX_PER_KIND = 2
    const val GAP_MIN = 26
    const val GAP_MAX = 35
    const val ACTIVE_DAYS = 40
    const val MAX_REGULAR = 20
    const val HIDE = "Hide tips like this"
    const val SHOW_AGAIN = "Show them again"
    const val SHOWN_AGAIN = "Tips shown again"

    private val HABITS = listOf(MoneyCategory.COFFEE, MoneyCategory.TAKEAWAY, MoneyCategory.EATING_OUT)

    /** Day-to-day spending a regular payment is never made of (the weekly shop can look monthly by chance). */
    private val NOT_REGULAR = setOf(
        MoneyCategory.GROCERIES, MoneyCategory.EATING_OUT, MoneyCategory.COFFEE, MoneyCategory.TAKEAWAY, MoneyCategory.FUEL, MoneyCategory.CASH,
    )

    /** Categories too broad for "over your average" to say anything. */
    private val NOT_OVER = setOf(MoneyCategory.OTHER, MoneyCategory.CASH)

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Pence rounded to the nearest £10 (£1 under £10). */
    private fun roundTen(p: Long): Long = if (p < 1_000) (p / 100.0).roundToLong() * 100 else (p / 1_000.0).roundToLong() * 1_000

    fun hiddenLine(hidden: Set<String>): String? {
        val kinds = MoneyTipKind.entries.filter { it.key in hidden }
        if (kinds.isEmpty()) return null
        return "Hidden: tips on " + kinds.joinToString(", ") { it.label }
    }

    fun hiddenSaid(kind: MoneyTipKind): String = "Tips on ${kind.label} hidden · on both devices"

    /** Every tip for [today], in order: over, rise, habit; at most [MAX_PER_KIND] of each, none of a [hidden] kind. */
    fun tips(lines: List<MoneyLine>, today: Long, hidden: Set<String>, regular: List<Regular> = regular(lines, today)): List<MoneyTip> {
        if (lines.isEmpty()) return emptyList()
        val out = mutableListOf<MoneyTip>()
        if (MoneyTipKind.OVER.key !in hidden) out += over(lines, today)
        if (MoneyTipKind.RISE.key !in hidden) out += rises(regular)
        if (MoneyTipKind.HABIT.key !in hidden) out += habits(lines, today)
        return out
    }

    fun habits(lines: List<MoneyLine>, today: Long): List<MoneyTip> {
        val from = today - 29
        val recent = lines.filter { it.day in from..today && it.pence < 0 }
        return HABITS.mapNotNull { c ->
            val ls = recent.filter { it.category == c }
            val total = -ls.sumOf { it.pence }
            if (ls.size < HABIT_MIN_COUNT || total < HABIT_MIN_PENCE) return@mapNotNull null
            val saves = roundTen(total * 12 / 2)
            val title = "${c.label} ${ls.size}× · ${fmt(total)} in the last 30 days"
            val line = "Halving it would save about ${fmt(saves)} a year"
            total to MoneyTip("habit-${c.key}", MoneyTipKind.HABIT.key, title, line, lit = false, spoken = "${c.label}, ${ls.size} times, ${fmt(total)} in the last 30 days. $line")
        }.sortedByDescending { it.first }.take(MAX_PER_KIND).map { it.second }
    }

    /** Spend per category in [month] (pence out, refunds netted). */
    private fun spend(lines: List<MoneyLine>, month: Int): Map<MoneyCategory, Long> = lines
        .filter { it.category.spend && MoneyRules.monthOf(it.day) == month }
        .groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }

    /** The categories well over their 6-month average in [month] (so far, when it's this month). */
    private fun overIn(lines: List<MoneyLine>, month: Int, firstMonth: Int): List<Pair<MoneyCategory, Pair<Long, Long>>> {
        val prior = (1..6).map { month - it }.filter { it >= firstMonth }
        if (prior.size < 2) return emptyList()
        val sums = prior.map { spend(lines, it) }
        val cur = spend(lines, month)
        return cur.mapNotNull { (c, p) ->
            if (c in NOT_OVER || p <= 0) return@mapNotNull null
            val avg = sums.sumOf { it[c] ?: 0L } / prior.size
            if (p - avg >= MoneyRules.OVER_MIN_PENCE && p > avg * (1 + MoneyRules.OVER_SHARE)) c to (p to avg) else null
        }.sortedByDescending { (_, v) -> v.first - v.second }
    }

    fun over(lines: List<MoneyLine>, today: Long): List<MoneyTip> {
        val firstMonth = MoneyRules.monthOf(lines.minOf { it.day })
        val thisMonth = MoneyRules.monthOf(today)
        val d = CivilDate.fromEpochDay(today)
        val left = CivilDate.lengthOfMonth(d.year, d.month) - d.day
        val monthName = CivilDate.MONTH_LONG[thisMonth % 12]
        val now = overIn(lines, thisMonth, firstMonth).map { (c, v) ->
            val (p, avg) = v
            val title = "${c.label} ${fmt(p - avg)} over your 6-month average"
            val line = "${fmt(p)} so far in $monthName · " + when (left) { 0 -> "last day of the month"; 1 -> "1 day to go"; else -> "$left days to go" }
            MoneyTip("over-${c.key}", MoneyTipKind.OVER.key, title, line, lit = true, spoken = "$title. $line")
        }
        val last = if (d.day > 7) emptyList() else overIn(lines, thisMonth - 1, firstMonth)
            .filter { (c, _) -> now.none { it.id == "over-${c.key}" } }
            .map { (c, v) ->
                val (p, avg) = v
                val title = "${c.label} was ${fmt(p - avg)} over your 6-month average in ${CivilDate.MONTH_LONG[(thisMonth - 1) % 12]}"
                val line = "${fmt(p)} against about ${fmt(avg)} a month"
                MoneyTip("overlast-${c.key}", MoneyTipKind.OVER.key, title, line, lit = false, spoken = "$title. $line")
            }
        return (now + last).take(MAX_PER_KIND)
    }

    fun rises(regular: List<Regular>): List<MoneyTip> = regular.filter { it.rise > 0 }.sortedByDescending { it.rise }.take(MAX_PER_KIND).map { r ->
        val title = "${r.label} went up ${fmt(r.rise)}"
        val line = "${fmt(r.monthly)} a month from ${CivilDate.shortLabel(r.lastDay)} · ${fmt(r.rise * 12)} more a year"
        MoneyTip("rise-${r.key}", MoneyTipKind.RISE.key, title, line, lit = true, spoken = "$title. $line")
    }

    /** A regular payment found in the lines: its latest [monthly] amount, and [rise] over the steady price before it. */
    data class Regular(val key: String, val label: String, val category: MoneyCategory, val monthly: Long, val lastDay: Long, val nextDay: Long, val rise: Long)

    private fun median(xs: List<Long>): Long = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** The regular payments still going on [today], biggest first. */
    fun regular(lines: List<MoneyLine>, today: Long): List<Regular> = lines
        .filter { it.pence < 0 && it.category.spend && it.category !in NOT_REGULAR && it.day <= today }
        .groupBy { it.merchant }
        .mapNotNull { (key, ls) -> regularOf(key, ls, today) }
        .sortedWith(compareByDescending<Regular> { it.monthly }.thenBy { it.label })
        .take(MAX_REGULAR)

    private fun regularOf(key: String, ls: List<MoneyLine>, today: Long): Regular? {
        val sorted = ls.sortedBy { it.day }
        val last = sorted.last()
        if (today - last.day > ACTIVE_DAYS) return null
        // The run of payments about a month apart, back from the latest.
        val run = mutableListOf(last)
        for (i in sorted.size - 2 downTo 0) {
            val gap = run.last().day - sorted[i].day
            if (gap < GAP_MIN) continue // a one-off between (a top-up, a second plan from the same shop)
            if (gap > GAP_MAX) break
            run += sorted[i]
        }
        if (run.size < 3) return null
        val before = run.drop(1).map { -it.pence }
        val base = median(before)
        if (base <= 0) return null
        if (before.any { abs(it - base) * 100 > base * 15 }) return null
        val now = -last.pence
        if (now * 100 < base * 85 || now * 100 > base * 150) return null
        val steady = before.all { abs(it - base) * 100 <= base * 2 }
        val rise = if (steady && now - base >= 50 && (now - base) * 100 >= base * 3) now - base else 0L
        // Next: the same day next month (the 31st becomes the month's last day).
        val ld = CivilDate.fromEpochDay(last.day)
        val (ny, nm) = if (ld.month == 12) ld.year + 1 to 1 else ld.year to ld.month + 1
        val next = CivilDate.toEpochDay(ny, nm, minOf(ld.day, CivilDate.lengthOfMonth(ny, nm)))
        return Regular(key, MoneyCategoryRules.merchantLabel(key), last.category, now, last.day, next, rise)
    }

    fun regularView(regular: List<Regular>): MoneyRegular? {
        if (regular.isEmpty()) return null
        val total = regular.sumOf { it.monthly }
        val n = regular.size
        val head = "$n regular payment${if (n == 1) "" else "s"} · about ${fmt(total)} a month · ${fmt(total * 12)} a year"
        val rows = regular.map { r ->
            val line = listOfNotNull(
                r.category.label, "last ${CivilDate.shortLabel(r.lastDay)}", "next about ${CivilDate.shortLabel(r.nextDay)}",
                if (r.rise > 0) "up ${fmt(r.rise)}" else null,
            ).joinToString(" · ")
            MoneyRegularRow(r.key, r.label, "${fmt(r.monthly)} a month", line, r.rise > 0, "${r.label}, ${fmt(r.monthly)} a month, $line")
        }
        return MoneyRegular(head, rows)
    }
}
