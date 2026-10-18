package os.meka.core.domain

/**
 * Numeric goals (Goals, properly, slice 3; non-AI, pure): "Save £2,000", "Read 12 books". The goal keeps a number to
 * reach and a unit; each update is a `goal_entry` (signed, in hundredths), and a habit's ticks can feed it (each tick
 * adds 1). The number decides the goal's progress, and a small chart shows how it has grown (with the pace line when
 * the goal has a due date). Nothing is guessed: the numbers are Meka's own.
 */
data class GoalMetric(
    /** The number to reach, in hundredths. */
    val targetX100: Long,
    /** "£" (written before) or a word written after ("books"). */
    val unit: String,
    /** Where it stands now, in hundredths (updates plus habit ticks). */
    val currentX100: Long,
    /** The day it started counting. */
    val sinceDay: Long,
    /** The goal's due day, for the pace line; null for none. */
    val dueDay: Long?,
    /** The cumulative number over time, oldest first; the last point is today. */
    val points: List<MetricPoint>,
    /** The habit whose ticks add 1 each, and its title; null for none. */
    val habitId: String? = null,
    val habitTitle: String? = null,
    /** The latest updates, newest first (at most [GoalMetricRules.RECENT]). */
    val recent: List<GoalEntryItem> = emptyList(),
    /** "On pace", "Behind pace · £200 to catch up", "Reached"; null without a due date. */
    val paceLine: String? = null,
    /** Money manager (6): the Money account whose balance the number follows, and its name; null for none. */
    val moneyAccountId: String? = null,
    val moneyAccount: String? = null,
) {
    /** "£800 of £2,000", "5 of 12 books". */
    val line: String get() = GoalMetricRules.line(currentX100, targetX100, unit)
    val pct: Int get() = GoalMetricRules.pct(currentX100, targetX100)
    /** What the settings field shows: "£2,000", "12 books". */
    val targetText: String get() = GoalMetricRules.format(targetX100, unit)
    val reached: Boolean get() = currentX100 >= targetX100
    /** Money manager (6): "Follows Savings's balance in Money · brought up to date each time you open Money"; null for none. */
    val moneyLine: String? get() = moneyAccount?.let { MoneyGoals.followsInGoals(it) }
}

/** One point of the chart: the number on a local day. */
data class MetricPoint(val day: Long, val valueX100: Long)

/** One update, for the list under the chart: "+£200 · Sat 10 Oct". */
data class GoalEntryItem(val id: String, val amountX100: Long, val day: Long, val label: String)

/** A point on the chart in 0–1 (x left to right, y bottom to top). */
data class PlotPoint(val x: Float, val y: Float)

/** What the chart draws: the number's line, the pace line (start to the target on the due day) and the target's height. */
data class MetricPlot(val line: List<PlotPoint>, val pace: List<PlotPoint>, val targetY: Float)

/** A typed update: add (or take away) [x100], or set the number to [x100] ("=1200"). */
data class MetricInput(val x100: Long, val set: Boolean)

object GoalMetricRules {
    const val RECENT = 5
    /** The chart keeps at most this many points (the oldest are folded into the first). */
    const val MAX_POINTS = 60
    const val MAX_UNIT = 20
    private const val MAX_X100 = 100_000_000_000L // a billion

    private val CURRENCIES = setOf("£", "$", "€")

    /**
     * A number at the start of [t] — an optional currency, digits ("2,000" with proper thousands or plain "2000"), up
     * to two decimals, an optional "k" — not followed by a letter, digit or dot. Written by hand, not as a regex, so it
     * reads the same on every platform (Kotlin/Native's regex engine didn't, 2026-10-11 nightly).
     */
    private class Num(val currency: String, val x100: Long, val end: Int)

    private fun wordOrDot(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_' || c == '.')

    private fun number(t: String): Num? {
        var i = 0
        val cur = if (t.isNotEmpty() && t[0].toString() in CURRENCIES) { i = 1; t[0].toString() } else ""
        while (i < t.length && t[i].isWhitespace()) i++
        val start = i
        while (i < t.length && (t[i].isDigit() || t[i] == ',')) i++
        var run = t.substring(start, i)
        if (run.isEmpty() || !run[0].isDigit()) return null
        if (',' in run) {
            val groups = run.split(',')
            val proper = groups[0].length in 1..3 && groups.drop(1).all { it.length == 3 && it.all(Char::isDigit) } && groups.all { it.isNotEmpty() }
            if (!proper) { run = run.substringBefore(','); i = start + run.length }
        }
        val whole = run.replace(",", "").toLongOrNull() ?: return null
        var frac = 0L
        if (i < t.length && t[i] == '.') {
            var j = i + 1
            while (j < t.length && t[j].isDigit()) j++
            val d = t.substring(i + 1, j)
            if (d.length !in 1..2) return null
            frac = d.padEnd(2, '0').toLong()
            i = j
        }
        var v = whole * 100 + frac
        val afterNumber = i
        var q = i
        while (q < t.length && t[q].isWhitespace()) q++
        val end = when {
            q < t.length && (t[q] == 'k' || t[q] == 'K') && !wordOrDot(t.getOrNull(q + 1)) -> { v *= 1000; q + 1 }
            !wordOrDot(t.getOrNull(afterNumber)) -> afterNumber
            else -> return null
        }
        return if (v <= MAX_X100) Num(cur, v, end) else null
    }

    /**
     * The number to reach as typed: "£2,000", "£2k", "12 books", "100 km", "5000" (no unit). Null when it isn't a
     * positive number.
     */
    fun parseTarget(text: String?): Pair<Long, String>? {
        val t = text?.trim().orEmpty()
        val m = number(t) ?: return null
        val v = m.x100
        if (v <= 0) return null
        val cur = m.currency
        val rest = t.substring(m.end).trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ")
        val unit = when {
            cur.isNotEmpty() -> cur
            rest.length in 1..MAX_UNIT && rest.none { it.isDigit() } -> rest
            rest.isEmpty() -> ""
            else -> return null
        }
        return v to unit
    }

    /** A number to reach suggested by the title ("Save £2,000" → "£2,000", "Read 12 books" → "12 books"), else null. */
    fun suggestFromTitle(title: String): String? {
        // A currency amount anywhere: "£2,000", "£ 2k", "€12.50".
        for (i in title.indices) {
            if (title[i].toString() !in CURRENCIES) continue
            var j = i + 1
            while (j < title.length && title[j].isWhitespace()) j++
            if (j >= title.length || !title[j].isDigit()) continue
            while (j < title.length && (title[j].isDigit() || title[j] == ',')) j++
            if (j + 1 < title.length && title[j] == '.' && title[j + 1].isDigit()) {
                j += 2
                if (j < title.length && title[j].isDigit()) j++
            }
            if (j < title.length && (title[j] == 'k' || title[j] == 'K')) j++
            return title.substring(i, j).replace(" ", "")
        }
        // A count and its word: "12 books", "100 km" (the number starts a word; the word is 2–20 plain letters).
        val words = title.split(' ', '\t', '\n').filter { it.isNotEmpty() }
        for (k in 0 until words.size - 1) {
            val n = words[k]
            if (!n[0].isDigit() || !n.all { it.isDigit() || it == ',' }) continue
            val w = words[k + 1].takeWhile { it in 'A'..'Z' || it in 'a'..'z' }
            val tail = words[k + 1].drop(w.length)
            if (w.length !in 2..MAX_UNIT || (tail.isNotEmpty() && (tail[0].isLetterOrDigit() || tail[0] == '_'))) continue
            return "$n ${w.lowercase()}"
        }
        return null
    }

    /** An update: "200", "+200", "+£200", "-1", "−50", "=1200", "2 books". Null when it isn't a number. */
    fun parseInput(text: String?): MetricInput? {
        var t = text?.trim().orEmpty().replace('−', '-')
        var set = false
        var sign = 1
        when {
            t.startsWith("=") -> { set = true; t = t.drop(1).trim() }
            t.startsWith("+") -> t = t.drop(1).trim()
            t.startsWith("-") -> { sign = -1; t = t.drop(1).trim() }
        }
        val v = number(t)?.x100 ?: return null
        if (set && sign < 0) return null
        return MetricInput(sign * v, set)
    }

    /** "£2,000", "£12.50", "12 books", "1 book", "5,000". */
    fun format(x100: Long, unit: String): String {
        val neg = x100 < 0
        val a = if (neg) -x100 else x100
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = a % 100
        val num = if (frac == 0L) whole else "$whole.${frac.toString().padStart(2, '0')}"
        val sign = if (neg) "−" else ""
        return when {
            unit in CURRENCIES -> "$sign$unit$num"
            unit.isEmpty() -> "$sign$num"
            else -> "$sign$num ${if (a == 100L) singular(unit) else unit}"
        }
    }

    /** "+£200", "−1 book". */
    fun signed(x100: Long, unit: String): String = if (x100 >= 0) "+${format(x100, unit)}" else format(x100, unit)

    private fun singular(unit: String): String =
        if (unit.length > 3 && unit.endsWith("s") && !unit.endsWith("ss")) unit.dropLast(1) else unit

    /** "£800 of £2,000", "5 of 12 books" (the word once, after the target). */
    fun line(current: Long, target: Long, unit: String): String =
        if (unit in CURRENCIES || unit.isEmpty()) "${format(current, unit)} of ${format(target, unit)}"
        else "${format(current, "")} of ${format(target, unit)}"

    fun pct(current: Long, target: Long): Int =
        if (target <= 0) 0 else (current.coerceAtLeast(0) * 100 / target).coerceIn(0, 100).toInt()

    /**
     * The chart: the cumulative number on each day something changed, from [sinceDay] (what came before counts there)
     * to [today]; at most [MAX_POINTS] (the oldest folded into the first).
     */
    fun points(sinceDay: Long, today: Long, changes: List<Pair<Long, Long>>): List<MetricPoint> {
        val byDay = changes.groupBy({ it.first.coerceIn(sinceDay, maxOf(sinceDay, today)) }, { it.second })
            .mapValues { it.value.sum() }.entries.sortedBy { it.key }
        var run = 0L
        val out = mutableListOf(MetricPoint(sinceDay, 0L))
        for ((d, x) in byDay) {
            run += x
            if (out.last().day == d) out[out.size - 1] = MetricPoint(d, run) else out += MetricPoint(d, run)
        }
        if (out.last().day < today) out += MetricPoint(today, run)
        if (out.size <= MAX_POINTS) return out
        val keep = out.takeLast(MAX_POINTS - 1)
        return listOf(MetricPoint(sinceDay, out[out.size - MAX_POINTS].valueX100)) + keep
    }

    /**
     * Where it stands against a straight line from 0 on [sinceDay] to the target on [dueDay]: "Reached", "On pace",
     * "Behind pace · £200 to catch up"; null without a due date (or one on or before the start).
     */
    fun paceLine(current: Long, target: Long, unit: String, sinceDay: Long, dueDay: Long?, today: Long): String? {
        if (current >= target) return "Reached"
        if (dueDay == null || dueDay <= sinceDay) return null
        val span = dueDay - sinceDay
        val gone = (today - sinceDay).coerceIn(0, span)
        val expected = target * gone / span
        return if (current >= expected) "On pace" else "Behind pace · ${format(expected - current, unit)} to catch up"
    }

    /** The chart in 0–1: x from the start to today (or the due day, if later); y from 0 to the target (or the top). */
    fun plot(m: GoalMetric, today: Long): MetricPlot {
        val x0 = m.sinceDay
        val x1 = maxOf(today, m.dueDay ?: today, x0 + 1)
        val top = maxOf(m.targetX100, m.points.maxOfOrNull { it.valueX100 } ?: 0L, 1L).toFloat()
        fun x(d: Long) = ((d - x0).toFloat() / (x1 - x0)).coerceIn(0f, 1f)
        fun y(v: Long) = (v.toFloat() / top).coerceIn(0f, 1f)
        val line = m.points.map { PlotPoint(x(it.day), y(it.valueX100)) }
        val pace = m.dueDay?.takeIf { it > x0 }?.let { listOf(PlotPoint(0f, 0f), PlotPoint(x(it), y(m.targetX100))) }.orEmpty()
        return MetricPlot(line, pace, y(m.targetX100))
    }

    /** "+£200 · Sat 10 Oct", "+1 book · today". */
    fun entryLabel(x100: Long, unit: String, day: Long, today: Long): String =
        "${signed(x100, unit)} · ${if (day == today) "today" else CivilDate.shortLabel(day)}"
}
