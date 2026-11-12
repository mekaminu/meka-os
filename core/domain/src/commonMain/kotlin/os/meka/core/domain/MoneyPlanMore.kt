package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * **Plan's lower half** (approved by Meka 2026-11-06 21:34, mockup `docs/design/money/Phone-PlanMore.dc.html`), slice 1:
 * Goals' empty state and the forecast as tiles and bars instead of sentences.
 *
 * - **Goals, empty**: a card ("No money goals yet · Start one from a plan") with up to three start tiles: Save £ a month
 *   (What if I save…), Clear my cards (only with a card to clear), and Add a £ goal (a sheet: what for, how much), so a
 *   first goal is one tap away.
 * - **Forecast at your pace**: In / Out / Left over tiles for the average month; a 6 · 12 months toggle; one bar a month
 *   growing to the total left over by then (the 6th and 12th labelled in gold); By <month> tiles for 6 and 12 months;
 *   and **What if I cut…**, a slider from £0 to £600 a month in £25 steps, worked out on the device as he slides (never
 *   stored or synced). What-ifs that are on (halve a category, trading, an extra) count in the bars and tiles too.
 *
 * Rules only, figures from his own statements, not financial advice (said once at the tab's foot).
 */
data class MoneyGoalStart(val key: String, val icon: String, val name: String, val spoken: String)

/** The empty goals card: [title], [line] and the start [tiles]. */
data class MoneyGoalsEmpty(val title: String, val line: String, val tiles: List<MoneyGoalStart>)

/** A forecast tile: [label] in small caps, [value], [lit] (gold for left over, the alarm tone when negative). */
data class MoneyPlanTile(val label: String, val value: String, val lit: Boolean = false, val alarm: Boolean = false, val spoken: String = "$label, $value")

/**
 * One month's bar: [month] "Nov", [share] of the tallest bar (0–1), [tag] "+£6.3k" on the 6th and 12th (gold), [key]
 * when tagged, [below] when the total by then is under zero.
 */
data class MoneyPlanBar(val month: String, val share: Float, val tag: String?, val key: Boolean, val below: Boolean)

/** The forecast card: the three tiles, the span chips, the bars, the By tiles and the slider's lines. */
data class MoneyForecastTiles(
    val tiles: List<MoneyPlanTile>, val span: Int, val spans: List<Int>, val bars: List<MoneyPlanBar>,
    val by: List<MoneyPlanTile>, val cutLabel: String, val cutNote: String, val cutPence: Long, val spoken: String,
)

/** One day of the no-spend week: its letter and [state] ([MoneyNoSpendView.WEEK_FREE] etc.). */
data class MoneyWeekDot(val letter: String, val state: Int, val spoken: String) {
    val noSpend: Boolean get() = state == MoneyNoSpendView.WEEK_FREE
    val spent: Boolean get() = state == MoneyNoSpendView.WEEK_SPENT
    val pending: Boolean get() = state == MoneyNoSpendView.WEEK_NOT_YET
}

data class MoneyNoSpendTile(val on: Boolean, val dots: List<MoneyWeekDot>, val count: String, val button: String, val spoken: String) {
    fun dotAt(i: Int): MoneyWeekDot = dots[i]
}

/** A fun money chip: £[pounds], [chosen] when it's the week's allowance. */
data class MoneyFunChip(val pounds: Int, val label: String, val chosen: Boolean)

/** [value] "£25" (left, or "£5 over"), [of] "of £60", [share] what's left of it (0–1) for the pot's bar. */
data class MoneyFunTile(val hasPot: Boolean, val value: String, val of: String, val share: Float, val over: Boolean, val chips: List<MoneyFunChip>, val spoken: String) {
    fun chipAt(i: Int): MoneyFunChip = chips[i]
}

data class MoneyChallengeTiles(val noSpend: MoneyNoSpendTile, val funMoney: MoneyFunTile, val capRow: String, val capSet: String)

/** One account's bar: [amount] its fees (or [NO_FEES]), [share] of the biggest, [sub] its rate and payments. */
data class MoneyAbroadBar(val id: String, val name: String, val amount: String, val share: Float, val good: Boolean, val sub: String, val spoken: String)

data class MoneyAbroadPayment(val what: String, val fee: String, val spoken: String)

data class MoneyAbroadTiles(
    val head: String, val feesLabel: String, val fees: String, val lit: Boolean, val line: String,
    val bars: List<MoneyAbroadBar>, val payments: List<MoneyAbroadPayment>, val more: String?, val spoken: String,
) {
    fun barAt(i: Int): MoneyAbroadBar = bars[i]
    fun paymentAt(i: Int): MoneyAbroadPayment = payments[i]
}

object MoneyPlanMore {
    const val GOALS_EMPTY_TITLE = "No money goals yet"
    const val GOALS_EMPTY_LINE = "Start one from a plan"
    const val START_SAVE = "save"
    const val START_CLEAR = "clear"
    const val START_ADD = "add"

    const val ADD_SHEET = "Add a £ goal"
    const val ADD_NAME_HINT = "What for? e.g. Holiday"
    const val ADD_AMOUNT_HINT = "How much? e.g. £2,000"
    const val ADD = "Add goal"
    const val ADD_BAD_AMOUNT = "Type how much, e.g. £2,000."
    const val ADD_NO_NAME = "Say what it's for, e.g. Holiday."
    const val DEFAULT_NAME = "Savings"
    /** A goal over £10m is a typo. */
    const val MAX_GOAL_PENCE = 1_000_000_000L
    const val MAX_NAME = 60

    const val IN = "IN / MONTH"
    const val OUT = "OUT / MONTH"
    const val LEFT = "LEFT OVER"
    val SPANS = listOf(6, 12)
    const val CUT_HEAD = "What if I cut…"
    /** The slider at nothing (walk-through 2026-11-08 A8: a bare "—" where a figure belongs). */
    const val CUT_NONE = "£0 a month"
    const val CUT_HINT = "Slide to see what a cut adds"
    const val CUT_MAX_POUNDS = 600
    const val CUT_STEP_POUNDS = 25

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun signed(p: Long) = if (p < 0) "−" + fmt(-p) else "+" + fmt(p)
    private fun monthYear(m: Int) = "${Recurrence.MONTH_SHORT[m % 12].uppercase()} ${m / 12}"

    /** The empty goals card, or null when he has a goal (or Goals can't show). */
    fun goalsEmpty(goals: MoneyGoalsView?, hasSave: Boolean, hasClear: Boolean): MoneyGoalsEmpty? {
        if (goals == null || goals.rows.isNotEmpty()) return null
        val tiles = buildList {
            if (hasSave) add(MoneyGoalStart(START_SAVE, "↗", "Save £ a month", "Save pounds a month: opens What if I save"))
            if (hasClear) add(MoneyGoalStart(START_CLEAR, "▭", "Clear my cards", "Clear my cards: opens Clear my cards"))
            add(MoneyGoalStart(START_ADD, "◎", "Add a £ goal", "Add a pound goal"))
        }
        return MoneyGoalsEmpty(GOALS_EMPTY_TITLE, GOALS_EMPTY_LINE, tiles)
    }

    /** A new goal from the sheet: its title and number ("£2,000"), or why not ([MoneySaved] refused). */
    data class NewGoal(val title: String, val target: String)

    fun newGoal(name: String, amount: String): Result<NewGoal> {
        val title = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        val t = amount.trim().lowercase()
        val thousands = t.endsWith("k")
        val pence = MoneyCsv.pence(t.removeSuffix("k").trim())?.let { if (thousands) it * 1000 else it }
        if (pence == null || pence <= 0 || pence > MAX_GOAL_PENCE) return Result.failure(IllegalArgumentException(ADD_BAD_AMOUNT))
        if (title.isEmpty()) return Result.failure(IllegalArgumentException(ADD_NO_NAME))
        return Result.success(NewGoal(title, fmt(pence)))
    }

    fun addedSaid(g: NewGoal) = "${g.title} · ${g.target} added to Goals"

    /** The slider's value in pence, kept to its steps and range. */
    fun cutPence(pounds: Int): Long = (pounds.coerceIn(0, CUT_MAX_POUNDS) / CUT_STEP_POUNDS * CUT_STEP_POUNDS) * 100L

    /** "£6.3k", "£950", "−£1.2k": short enough to sit over a narrow bar. */
    fun short(p: Long): String {
        val sign = if (p < 0) "−" else "+"
        val a = abs(p)
        if (a < 100_000L) return sign + fmt(a)
        // Tenths of a thousand (£100s); from £100k on, whole thousands.
        val t = (a / 10_000.0).roundToLong()
        val k = when {
            t >= 1_000 -> "${(a / 100_000.0).roundToLong()}k"
            t % 10 == 0L -> "${t / 10}k"
            else -> "${t / 10}.${t % 10}k"
        }
        return "$sign£$k"
    }

    /**
     * The forecast as tiles for [span] months (6 or 12) with the slider at [cutPounds]; null before it's ready or with
     * no money in on the statements (the sentences then stay, as they explain what's missing).
     */
    fun forecast(f: MoneyForecastView, span: Int, cutPounds: Int): MoneyForecastTiles? {
        if (f.notReady != null || f.leftPence == null || f.inRounded <= 0) return null
        val s = if (span in SPANS) span else 12
        val cut = cutPence(cutPounds)
        val month = f.leftRounded + f.whatIfPence + cut
        val leftAlarm = month < 0
        val tiles = listOf(
            MoneyPlanTile(IN, fmt(f.inRounded)),
            MoneyPlanTile(OUT, fmt(f.outRounded)),
            MoneyPlanTile(LEFT, if (month < 0) "−" + fmt(-month) else fmt(month), lit = !leftAlarm, alarm = leftAlarm,
                spoken = if (month < 0) "More out than in, ${fmt(-month)} a month" else "Left over, ${fmt(month)} a month"),
        )
        // Spending plans he counts come off in the months he'll buy their things.
        fun at(i: Int) = month * i - f.plannedAt(i)
        val tallest = (1..s).maxOf { abs(at(it)) }.coerceAtLeast(1)
        val bars = (1..s).map { i ->
            val total = at(i)
            val key = i == 6 || i == 12
            MoneyPlanBar(Recurrence.MONTH_SHORT[(f.thisMonth + i) % 12], (abs(total).toFloat() / tallest).coerceIn(0.04f, 1f),
                if (key) short(total) else null, key, total < 0)
        }
        val by = SPANS.map { h ->
            MoneyPlanTile("BY ${monthYear(f.thisMonth + h)}", signed(at(h)), alarm = at(h) < 0,
                spoken = "By ${Recurrence.MONTH_SHORT[(f.thisMonth + h) % 12]} ${(f.thisMonth + h) / 12}, ${signed(at(h))}")
        }
        val end = f.thisMonth + 12
        val cutLabel = if (cut == 0L) CUT_NONE else "${fmt(cut)} a month"
        val cutNote = if (cut == 0L) CUT_HINT
            else "That adds ${fmt(cut * 12)} by ${Recurrence.MONTH_SHORT[end % 12]} ${end / 12}"
        val spoken = tiles.joinToString(". ") { it.spoken } + ". " + by.joinToString(". ") { it.spoken }
        return MoneyForecastTiles(tiles, s, SPANS, bars, by, cutLabel, cutNote, cut, spoken)
    }

    // ---- Slice 2: CHALLENGES as two tiles, PAYING ABROAD as a fees tile and a bar per account ----

    const val NO_SPEND = "NO-SPEND DAYS"
    const val THIS_WEEK = "this week"
    const val START_COUNTING = "Start counting"
    const val STOP_COUNTING = "Stop counting"
    const val FUN = "FUN MONEY"
    const val FUN_NOT_SET = "Not set"
    const val FUN_PICK = "Pick a week's fun money"
    val FUN_CHIPS = listOf(40, 60, 80)
    const val CAP_ROW = "Cap a category for the month"
    const val CAP_SET = "Set ›"
    const val ABROAD_HEAD = "PAYING ABROAD · LAST 12 MONTHS"
    const val FEES_LABEL = "FOREIGN FEES"
    const val SHOW_PAYMENTS = "See the payments behind it"
    const val HIDE_PAYMENTS = "Hide payments"
    const val NO_FEES = "No fees"
    /** The payments list stops here; the rest are counted in a last line. */
    const val PAYMENTS_SHOWN = 12
    private val WEEK_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")






    fun challengeTiles(ch: MoneyChallengesView): MoneyChallengeTiles {
        val ns = ch.noSpend
        val week = ns?.week?.takeIf { it.size == 7 } ?: List(7) { MoneyNoSpendView.WEEK_NOT_YET }
        val names = CivilDate.DAY_LONG
        val dots = week.mapIndexed { i, st ->
            MoneyWeekDot(WEEK_LETTERS[i], if (ch.noSpendOn) st else MoneyNoSpendView.WEEK_NOT_YET,
                names[i] + when { !ch.noSpendOn || st == MoneyNoSpendView.WEEK_NOT_YET -> ", not counted yet"; st == MoneyNoSpendView.WEEK_FREE -> ", no spending"; else -> ", spent" })
        }
        val n = if (ch.noSpendOn) week.count { it == MoneyNoSpendView.WEEK_FREE } else 0
        val noSpend = MoneyNoSpendTile(ch.noSpendOn, dots, "$n",
            if (ch.noSpendOn) STOP_COUNTING else START_COUNTING,
            if (ch.noSpendOn) "No-spend days, $n $THIS_WEEK" else "No-spend days, not counting")
        val f = ch.funMoney
        val funTile = if (f == null || f.limitPence <= 0) {
            MoneyFunTile(false, FUN_NOT_SET, FUN_PICK, 0f, false, FUN_CHIPS.map { MoneyFunChip(it, "£$it", false) }, "Fun money, not set")
        } else {
            val over = f.leftPence < 0
            val value = if (over) "${fmt(-f.leftPence)} over" else fmt(f.leftPence)
            val share = (f.leftPence.toFloat() / f.limitPence).coerceIn(0f, 1f)
            MoneyFunTile(true, value, "of ${fmt(f.limitPence)}", share, over,
                FUN_CHIPS.map { MoneyFunChip(it, "£$it", it * 100L == f.limitPence) },
                "Fun money this week, " + (if (over) value else "$value left") + " of ${fmt(f.limitPence)}")
        }
        return MoneyChallengeTiles(noSpend, funTile, CAP_ROW, CAP_SET)
    }

    /** What a fun money chip sends to [MoneyChallenges.parseFun]; a tap on the chosen one takes it off. */
    fun funText(pounds: Int): String = "£$pounds"




    /** PAYING ABROAD as tiles, or null with no account to show (the trip banner still leads the screen). */
    fun abroadTiles(tv: MoneyTravelView): MoneyAbroadTiles? {
        if (tv.rows.isEmpty()) return null
        val total = tv.feesPence
        val best = tv.rows.firstOrNull { it.good }
        val line = when {
            total == 0L -> "No foreign fees on your payments abroad"
            best != null -> "Could be £0 paying with ${best.name}, as it charged no fees abroad"
            else -> "Could be £0 with a card that has no foreign fees"
        }
        val tallest = tv.rows.maxOf { it.feePence }.coerceAtLeast(1)
        val bars = tv.rows.map { r ->
            val amount = if (r.feePence == 0L) NO_FEES else fmt(r.feePence)
            val pays = "${r.payments} payment${if (r.payments == 1) "" else "s"} abroad"
            val sub = if (r.feePence == 0L) "$pays · ${fmt(r.spentPence)}"
                else listOfNotNull(MoneyTravel.rate(r.feePence, r.spentPence)?.let { "$it of ${fmt(r.spentPence)}" },
                    pays.takeIf { r.payments > 0 }).joinToString(" · ").ifEmpty { r.line }
            MoneyAbroadBar(r.id, r.name, amount, if (r.feePence == 0L) 1f else (r.feePence.toFloat() / tallest).coerceIn(0.04f, 1f),
                r.feePence == 0L, sub, "${r.name}, $amount, $sub")
        }
        val payments = tv.fees.take(PAYMENTS_SHOWN).map { f ->
            val what = "${f.what ?: "Foreign fee"} · ${CivilDate.shortLabel(f.day)}"
            MoneyAbroadPayment(what, "${fmt(f.pence)} fee", "$what, ${fmt(f.pence)} fee")
        }
        val rest = tv.fees.size - payments.size
        val more = if (rest > 0) "and $rest more" else null
        val fees = fmt(total)
        return MoneyAbroadTiles(ABROAD_HEAD, FEES_LABEL, fees, total > 0, line, bars, payments, more,
            "Foreign fees in the last 12 months, $fees. $line. " + bars.joinToString(". ") { it.spoken })
    }
}
