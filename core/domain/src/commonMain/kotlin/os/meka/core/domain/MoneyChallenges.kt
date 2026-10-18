package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Money manager, slice 15 (Meka approved 2026-10-12 21:22): **challenges** he sets himself, from his own statements.
 * No AI, nothing sent, nothing judged: just where he is against the goal he picked.
 *
 * - **No-spend days**: a day this month with no everyday spending (bills, subscriptions, fees, transfers and money in
 *   don't break one), counted up to the last day his statements reach; the streak ending there and the month's best.
 * - **Fun money**: a weekly allowance (Monday to Sunday) for eating out, coffee, takeaways, going out, shopping and
 *   clothes, with what's left and the weeks in a row he stayed within it.
 * - **Caps**: a monthly cap on any spend category ("Eating out £150"), with a progress bar (lit from 90%) and the months
 *   in a row he stayed within it.
 *
 * All three live on one synced `context_mode` entity [ENTITY_ID] ([MoneyChallengeFields]; ADR-008 addendum 2026-10-17,
 * additive, no migration): `noSpend`, `funPence`, `cap_<category key>` (pence a month), `changedAtMs`.
 */
object MoneyChallengeFields {
    const val NO_SPEND = "noSpend"
    const val FUN = "funPence"
    const val CAP_PREFIX = "cap_"
    const val CHANGED_AT = "changedAtMs"
    fun cap(c: MoneyCategory): String = CAP_PREFIX + c.key
}

/** One statement line as the challenges see it. */
data class MoneyChallengeLine(val day: Long, val pence: Long, val category: MoneyCategory)

/** What he set: no-spend days on, fun money a week, and a monthly cap per category (pence). */
data class MoneyChallengeSetting(
    val noSpend: Boolean = false,
    val funPence: Long? = null,
    val caps: Map<MoneyCategory, Long> = emptyMap(),
) {
    val any: Boolean get() = noSpend || funPence != null || caps.isNotEmpty()
}

/** No-spend days this month: [days] one per day from the 1st to the last day his statements reach, true = no spending. */
data class MoneyNoSpendView(
    val title: String, val line: String, val streakLine: String?, val lit: Boolean, val days: List<Boolean>, val spoken: String,
    /** The run ending on the last day his statements reach, and the month's no-spend days (Goals' streak row). */
    val streak: Int = 0, val count: Int = 0, val best: Int = 0,
) {
    fun dayAt(i: Int): Boolean = days[i]
}

/** One allowance or cap: [amount] "£32 of £50", [share] (0–1+, drawn up to 1) for the bar, [line] what's left and the run, [lit] near or over. */
data class MoneyChallengeBar(
    val key: String, val title: String, val amount: String, val share: Float, val line: String, val lit: Boolean, val spoken: String,
    /** Whole weeks (fun money) or months (a cap) in a row within it, before this one (Goals' streak row). */
    val run: Int = 0,
)

/** One of Goals' MONEY CHALLENGES rows: [line] the streak and how this week or month is going, never an amount. */
data class MoneyStreak(val key: String, val title: String, val line: String, val lit: Boolean, val spoken: String)

/**
 * Money manager (15), the streaks in Goals: the challenges he set, as streaks beside his habits. No amounts, so it sits
 * outside Money's lock; a tap opens Money (behind the fingerprint). [note] when his statements stop before today.
 */
data class MoneyStreaksView(val head: String, val rows: List<MoneyStreak>, val note: String?, val foot: String) {
    fun rowAt(i: Int): MoneyStreak = rows[i]
}

/**
 * CHALLENGES on the Money screen (this month only). [intro] until he sets one; [noSpend] when that's on; [funMoney] his
 * week; [caps] his categories, biggest share first; [throughLine] when the statements stop before today.
 */
data class MoneyChallengesView(
    val head: String,
    val intro: String?,
    val noSpendOn: Boolean,
    val noSpend: MoneyNoSpendView?,
    val funMoney: MoneyChallengeBar?,
    val caps: List<MoneyChallengeBar>,
    val throughLine: String?,
    val funHint: String,
    val capHint: String,
    val foot: String,
) {
    fun capAt(i: Int): MoneyChallengeBar = caps[i]
}

object MoneyChallenges {
    const val ENTITY_ID = "money_challenges"
    const val HEAD = "CHALLENGES"
    const val SWITCH = "No-spend days"
    const val FUN_KEY = "fun"
    const val INTRO = "Set yourself a challenge from your own numbers: count no-spend days, give yourself fun money for the week, or cap a category for the month."
    const val FUN_HINT = "Fun money a week, e.g. £50"
    const val CAP_HINT = "Cap a category a month, e.g. Eating out £150"
    const val FOOT = "From your own statements, up to the last day they reach. Bills, subscriptions and fees don't break a no-spend day."
    const val BAD_FUN = "Type an amount for the week, e.g. £50."
    const val BAD_CAP = "Type a category and an amount, e.g. Eating out £150 or Takeaways £60."
    const val LIT_SHARE = 0.9
    const val MAX_PENCE = 10_000_000L
    /** How far back a run of good weeks or months is counted. */
    const val RUN_LOOKBACK = 12

    /** What fun money covers. */
    val FUN_CATEGORIES = setOf(
        MoneyCategory.EATING_OUT, MoneyCategory.COFFEE, MoneyCategory.TAKEAWAY, MoneyCategory.FUN, MoneyCategory.SHOPPING, MoneyCategory.CLOTHES,
    )

    /** Spending that never breaks a no-spend day (it would happen anyway). */
    private val NOT_EVERYDAY = setOf(MoneyCategory.BILLS, MoneyCategory.SUBSCRIPTIONS, MoneyCategory.FEES)

    /** Categories a cap can be on (every spend category). */
    val CAPPABLE: List<MoneyCategory> = MoneyCategory.entries.filter { it.spend }

    fun setting(fields: Map<String, FieldValue>?): MoneyChallengeSetting {
        if (fields == null) return MoneyChallengeSetting()
        val caps = CAPPABLE.mapNotNull { c -> fields[MoneyChallengeFields.cap(c)]?.longOrNull?.takeIf { it > 0 }?.let { c to it } }.toMap()
        return MoneyChallengeSetting(
            noSpend = fields[MoneyChallengeFields.NO_SPEND]?.boolOrNull == true,
            funPence = fields[MoneyChallengeFields.FUN]?.longOrNull?.takeIf { it > 0 },
            caps = caps,
        )
    }

    fun noSpendFields(on: Boolean, nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneyChallengeFields.NO_SPEND to (if (on) true.fv() else FieldValue.Null), MoneyChallengeFields.CHANGED_AT to nowMs.fv())

    fun funFields(pence: Long?, nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneyChallengeFields.FUN to (pence?.fv() ?: FieldValue.Null), MoneyChallengeFields.CHANGED_AT to nowMs.fv())

    fun capFields(c: MoneyCategory, pence: Long?, nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneyChallengeFields.cap(c) to (pence?.fv() ?: FieldValue.Null), MoneyChallengeFields.CHANGED_AT to nowMs.fv())

    /** Every challenge off (Delete all money data). */
    fun clearFields(nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneyChallengeFields.NO_SPEND to FieldValue.Null, MoneyChallengeFields.FUN to FieldValue.Null) +
            CAPPABLE.associate { MoneyChallengeFields.cap(it) to FieldValue.Null } + (MoneyChallengeFields.CHANGED_AT to nowMs.fv())

    private val AMOUNT = Regex("""£?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*(k)?\b""", RegexOption.IGNORE_CASE)
    private val MONTHLY = Regex("""\b(a|per|each|every)\s+month\b|\bmonthly\b|/\s*m(onth)?\b""", RegexOption.IGNORE_CASE)

    private fun amountOf(text: String): Long? {
        val m = AMOUNT.findAll(text).lastOrNull() ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val p = kotlin.math.round(n * (if (m.groupValues[2].isNotEmpty()) 1_000.0 else 1.0) * 100).toLong()
        return p.takeIf { it in 1..MAX_PENCE }
    }

    /** "£50", "50 a week", "£200 a month" (made weekly): the week's allowance in pence; null without an amount. */
    fun parseFun(text: String): Long? {
        val p = amountOf(text.trim()) ?: return null
        return if (MONTHLY.containsMatchIn(text)) (p * 12 + 26) / 52 else p
    }

    /** "Eating out £150", "takeaways 60", "£40 coffee": the category and its cap a month; null when either is missing. */
    fun parseCap(text: String): Pair<MoneyCategory, Long>? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val m = AMOUNT.findAll(t).lastOrNull() ?: return null
        val p = amountOf(t) ?: return null
        val words = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
            .replace(MONTHLY, " ").replace(Regex("""\b(cap|on|for|at|a|per|max|limit)\b""", RegexOption.IGNORE_CASE), " ")
            .trim().trim(':', '-', '·', ',').trim().lowercase().replace(Regex("\\s+"), " ")
        if (words.isEmpty()) return null
        return categoryNamed(words)?.let { it to p }
    }

    /** A category from what he typed: its label or key, the start of its label, or a few words people say. */
    fun categoryNamed(words: String): MoneyCategory? {
        val w = words.lowercase().trim()
        SAID[w]?.let { return it }
        CAPPABLE.firstOrNull { it.label.lowercase() == w || it.key.replace('_', ' ') == w }?.let { return it }
        CAPPABLE.firstOrNull { it.label.lowercase().startsWith(w) || w.startsWith(it.label.lowercase()) }?.let { return it }
        if (w.length >= 4) CAPPABLE.firstOrNull { it.label.lowercase().contains(w) }?.let { return it }
        if (w.endsWith("s")) return categoryNamed(w.removeSuffix("s")).takeIf { w.length > 3 }
        return null
    }

    private val SAID = mapOf(
        "takeaway" to MoneyCategory.TAKEAWAY, "takeaways" to MoneyCategory.TAKEAWAY, "food" to MoneyCategory.GROCERIES,
        "food shopping" to MoneyCategory.GROCERIES, "the shop" to MoneyCategory.GROCERIES, "restaurants" to MoneyCategory.EATING_OUT,
        "going out" to MoneyCategory.FUN, "fun" to MoneyCategory.FUN, "petrol" to MoneyCategory.FUEL, "diesel" to MoneyCategory.FUEL,
        "gym" to MoneyCategory.HEALTH, "gifts" to MoneyCategory.GIVING, "presents" to MoneyCategory.GIVING,
        "coffees" to MoneyCategory.COFFEE, "trains" to MoneyCategory.TRANSPORT, "travel" to MoneyCategory.TRAVEL,
    )

    fun noSpendSaid(on: Boolean): String =
        if (on) "Counting no-spend days · on both devices" else "Stopped counting no-spend days"
    fun funSaid(pence: Long?): String =
        if (pence != null) "Fun money · ${MoneyRules.fmt(pence)} a week, Monday to Sunday · on both devices" else "Fun money stopped"
    fun capSaid(c: MoneyCategory, pence: Long?): String =
        if (pence != null) "${c.label} capped at ${MoneyRules.fmt(pence)} a month · on both devices" else "${c.label} cap removed"

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun n(k: Int, one: String, many: String = one + "s") = "$k ${if (k == 1) one else many}"

    private fun out(l: MoneyChallengeLine) = l.pence < 0 && l.category.spend

    /** Whether [day] had everyday spending. */
    private fun spentOn(byDay: Map<Long, List<MoneyChallengeLine>>, day: Long) =
        byDay[day].orEmpty().any { out(it) && it.category !in NOT_EVERYDAY }

    /** Spending in [cats] (null = every spend category) between [from] and [to] inclusive, refunds netted. */
    private fun spent(lines: List<MoneyChallengeLine>, from: Long, to: Long, cats: Set<MoneyCategory>?): Long =
        (-lines.filter { it.day in from..to && it.category.spend && (cats == null || it.category in cats) }.sumOf { it.pence }).coerceAtLeast(0)

    fun view(lines: List<MoneyChallengeLine>, setting: MoneyChallengeSetting, today: Long): MoneyChallengesView? {
        if (lines.isEmpty()) return null
        val first = lines.minOf { it.day }
        val through = minOf(today, lines.maxOf { it.day })
        val throughLine = if (through < today) "Your statements reach ${CivilDate.shortLabel(through)}, so that's as far as these count" else null
        if (!setting.any) return MoneyChallengesView(HEAD, INTRO, false, null, null, emptyList(), null, FUN_HINT, CAP_HINT, FOOT)

        val noSpend = if (!setting.noSpend) null else noSpendView(lines, today, through, first)
        val funMoney = setting.funPence?.let { funView(lines, it, today, through, first) }
        val caps = setting.caps.map { (c, cap) -> capView(lines, c, cap, today, through, first) }
            .sortedByDescending { it.share }
        return MoneyChallengesView(HEAD, null, setting.noSpend, noSpend, funMoney, caps, throughLine, FUN_HINT, CAP_HINT, FOOT)
    }

    private fun noSpendView(lines: List<MoneyChallengeLine>, today: Long, through: Long, first: Long): MoneyNoSpendView {
        val d = CivilDate.fromEpochDay(today)
        val monthStart = CivilDate.toEpochDay(d.year, d.month, 1)
        val byDay = lines.groupBy { it.day }
        val start = maxOf(monthStart, first)
        val days = if (through < monthStart) emptyList() else (monthStart..through).map { day -> day >= start && !spentOn(byDay, day) }
        val count = days.count { it }
        var streak = 0
        var day = through
        while (day >= first && !spentOn(byDay, day)) { streak++; day-- }
        var best = 0; var run = 0
        days.forEach { if (it) { run++; best = maxOf(best, run) } else run = 0 }
        val month = CivilDate.MONTH_LONG[d.month - 1]
        val line = if (days.isEmpty()) "Nothing from $month in your statements yet"
            else "${n(count, "no-spend day")} in $month · of ${n(days.size, "day")} so far"
        val streakLine = when {
            days.isEmpty() -> null
            streak >= 2 -> "${streak} days in a row" + (if (best > streak) " · best this month $best" else " · your best this month")
            streak == 1 -> "1 day so far" + (if (best > 1) " · best this month $best in a row" else "")
            best >= 2 -> "Best this month $best in a row"
            else -> null
        }
        return MoneyNoSpendView(SWITCH, line, streakLine, streak >= 3, days,
            listOfNotNull("No-spend days", line, streakLine).joinToString(". "), streak, count, best)
    }

    private fun mondayOf(day: Long) = day - (CivilDate.isoDayOfWeek(day) - 1)

    private fun funView(lines: List<MoneyChallengeLine>, allowance: Long, today: Long, through: Long, first: Long): MoneyChallengeBar {
        val monday = mondayOf(today)
        val spentNow = spent(lines, monday, through, FUN_CATEGORIES)
        val left = allowance - spentNow
        val daysLeft = (monday + 6 - today).toInt()
        // Whole weeks before this one, newest first, while his statements cover them.
        var run = 0
        var w = monday - 7
        while (run < RUN_LOOKBACK && w >= mondayOf(first) && w + 6 <= through) {
            if (spent(lines, w, w + 6, FUN_CATEGORIES) <= allowance) { run++; w -= 7 } else break
        }
        val share = if (allowance > 0) spentNow.toFloat() / allowance else 0f
        val head = if (left >= 0) "${fmt(left)} left" else "${fmt(-left)} over"
        val togo = when (daysLeft) { 0 -> "last day of the week"; 1 -> "1 day to go"; else -> "$daysLeft days to go" }
        val runLine = when {
            run >= 2 -> "within it $run weeks in a row"
            run == 1 -> "within it last week"
            else -> null
        }
        val line = listOfNotNull(head, togo, runLine).joinToString(" · ")
        val amount = "${fmt(spentNow)} of ${fmt(allowance)}"
        return MoneyChallengeBar(FUN_KEY, "Fun money this week", amount, share, line, share >= LIT_SHARE,
            "Fun money this week, $amount, $line", run)
    }

    private fun capView(lines: List<MoneyChallengeLine>, c: MoneyCategory, cap: Long, today: Long, through: Long, first: Long): MoneyChallengeBar {
        val d = CivilDate.fromEpochDay(today)
        val monthStart = CivilDate.toEpochDay(d.year, d.month, 1)
        val spentNow = spent(lines, monthStart, through, setOf(c))
        val left = cap - spentNow
        var run = 0
        var y = d.year; var m = d.month
        while (run < RUN_LOOKBACK) {
            m -= 1; if (m == 0) { m = 12; y -= 1 }
            val s = CivilDate.toEpochDay(y, m, 1)
            val e = CivilDate.toEpochDay(y, m, CivilDate.lengthOfMonth(y, m))
            if (s < CivilDate.toEpochDay(CivilDate.fromEpochDay(first).year, CivilDate.fromEpochDay(first).month, 1) || e > through) break
            if (spent(lines, s, e, setOf(c)) <= cap) run++ else break
        }
        val share = if (cap > 0) spentNow.toFloat() / cap else 0f
        val head = if (left >= 0) "${fmt(left)} left" else "${fmt(-left)} over"
        val runLine = when {
            run >= 2 -> "within it $run months in a row"
            run == 1 -> "within it last month"
            else -> null
        }
        val line = listOfNotNull(head, runLine).joinToString(" · ")
        val amount = "${fmt(spentNow)} of ${fmt(cap)}"
        return MoneyChallengeBar(c.key, "${c.label} this month", amount, share, line, share >= LIT_SHARE,
            "${c.label} this month, $amount, $line", run)
    }

    const val STREAKS_HEAD = "MONEY CHALLENGES"
    const val STREAKS_FOOT = "From your challenges in Money · no amounts here · tap one to open Money"
    /** A run worth lighting: 3 no-spend days, 2 weeks or 2 months in a row. */
    const val LIT_DAYS = 3
    const val LIT_RUN = 2

    /**
     * Goals' MONEY CHALLENGES (the streaks in Goals): one row per challenge he set, the streak first ("4 days in a row ·
     * 9 this month", "Within it 3 weeks in a row · on track this week"), lit while a run is going. Null with none set or no
     * statements; never an amount.
     */
    fun streaks(lines: List<MoneyChallengeLine>, setting: MoneyChallengeSetting, today: Long): MoneyStreaksView? {
        if (!setting.any) return null
        val v = view(lines, setting, today) ?: return null
        val rows = buildList {
            v.noSpend?.takeIf { it.days.isNotEmpty() }?.let { ns ->
                val month = CivilDate.MONTH_LONG[CivilDate.fromEpochDay(today).month - 1]
                val line = when {
                    ns.streak >= 1 -> "${n(ns.streak, "day")} in a row · ${ns.count} this month"
                    ns.best >= 2 -> "${n(ns.count, "no-spend day")} in $month · best ${ns.best} in a row"
                    else -> "${n(ns.count, "no-spend day")} in $month"
                }
                add(MoneyStreak("nospend", SWITCH, line, ns.streak >= LIT_DAYS, "No-spend days, $line"))
            }
            v.funMoney?.let { add(barStreak(it, "Fun money", "week")) }
            v.caps.forEach { b ->
                val c = CAPPABLE.first { it.key == b.key }
                add(barStreak(b, "${c.label} cap", "month"))
            }
        }
        if (rows.isEmpty()) return null
        val through = minOf(today, lines.maxOf { it.day })
        val note = if (through < today) "Counted to ${CivilDate.shortLabel(through)}, where your statements reach" else null
        return MoneyStreaksView(STREAKS_HEAD, rows, note, STREAKS_FOOT)
    }

    private fun barStreak(b: MoneyChallengeBar, title: String, unit: String): MoneyStreak {
        val now = if (b.share <= 1f) "on track this $unit" else "over this $unit"
        val line = when {
            b.run >= 2 -> "Within it ${b.run} ${unit}s in a row · $now"
            b.run == 1 -> "Within it last $unit · $now"
            else -> now.replaceFirstChar { it.uppercase() }
        }
        return MoneyStreak(b.key, title, line, b.run >= LIT_RUN && b.share <= 1f, "$title, $line")
    }
}
