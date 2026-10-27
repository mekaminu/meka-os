package os.meka.core.domain

/**
 * Money manager (6), 2026-10-18: **money goals linked to Goals**. A goal with a £ number in Goals ("Save £2,000 for
 * the holiday") can follow one of his Money accounts: its number becomes that account's balance, so the chart, the pace
 * line and Goals' progress move as the savings do, with nothing typed.
 *
 * Linking happens inside Money (behind the fingerprint), in MONEY GOALS; the goal stores only the account's id
 * ([GoalFields.METRIC_MONEY_ACCOUNT], additive). Each time Money is opened (or a statement or balance changes while it
 * is open) the followed goals are brought up to the account's balance with one update a day with a fixed id
 * ([entryId]), so two devices doing it the same day agree. A followed goal's amount then shows in Goals, outside
 * Money's lock: he chose that by linking it, and MONEY GOALS says so. Cards can't be followed (a card's balance is owed,
 * not saved). No AI, nothing sent. Pure rules; the store is [Goals].
 *
 * Money redesign, Plan's goals card (mockup `docs/design/money/`, 2026-10-27): GOALS is one card of rows, each goal's
 * name, "£240 of £600", a bar in its own colour and one note on its pace ("£180 a month to 15 Dec · on track"). Which
 * account it follows and the chips to change it moved into the goal's sheet (layout rule (e): no choices open on the
 * page), opened by a tap on the row.
 */
object MoneyGoals {
    /** Only £ goals can follow an account. */
    const val UNIT = "£"
    const val HEAD = "GOALS"
    const val NOT_POUNDS = "Only a goal with a £ number can follow an account"
    const val EMPTY = "No money goals yet · Make it a goal under What if I save…, Use this plan under Clear my cards, or give a goal in Goals a £ number."
    const val NO_ACCOUNTS = "Add an account (a statement, or your bank) first · then a goal can follow its balance."
    const val FOOT = "A goal that follows an account shows its amount in Goals, outside Money's lock. It's brought up to date each time you open Money."
    const val NONE = "None"
    const val DONE = "Done"
    const val FOLLOW = "FOLLOWS AN ACCOUNT'S BALANCE"
    const val NOT_FOLLOWING = "Doesn't follow an account · pick one and its balance becomes the goal's number"
    const val REACHED = "Reached"
    /** The goals' colours: the money palette's tones, in turn. */
    const val TONES = 7

    /** "15 Dec" (the day and the month, no weekday). */
    fun dayMonth(day: Long): String {
        val d = CivilDate.fromEpochDay(day)
        return "${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]}"
    }

    /** Whole months from [today] to [due] (at least 1), counting a part month as one. */
    fun monthsLeft(today: Long, due: Long): Long = maxOf(1L, ((due - today) * 10_000L + 304_374L) / 304_375L)

    private fun upToPound(x100: Long): Long = (x100 + 99) / 100 * 100

    /**
     * The goal's one pace note and whether it is behind: "£180 a month to 15 Dec · on track", "£210 a month to 30 Jul ·
     * £60 behind", "£400 to go · was due 1 Oct", "£400 to go · no date yet", or "Reached". Behind is against a straight
     * line from its start to its due day, the same as Goals' pace line; the monthly figure is what's left over the months
     * left, rounded up to the pound.
     */
    fun note(m: GoalMetric, today: Long): Pair<String, Boolean> {
        if (m.reached) return REACHED to false
        val left = m.targetX100 - m.currentX100
        val due = m.dueDay ?: return "${GoalMetricRules.format(upToPound(left), UNIT)} to go · no date yet" to false
        if (due < today) return "${GoalMetricRules.format(upToPound(left), UNIT)} to go · was due ${dayMonth(due)}" to true
        val months = monthsLeft(today, due)
        val each = upToPound((left + months - 1) / months)
        val head = "${GoalMetricRules.format(each, UNIT)} a month to ${dayMonth(due)}"
        if (due <= m.sinceDay) return "$head · on track" to false
        val span = due - m.sinceDay
        val gone = (today - m.sinceDay).coerceIn(0, span)
        val expected = m.targetX100 * gone / span
        val short = expected - m.currentX100
        return if (short <= 0) "$head · on track" to false
        else "$head · ${GoalMetricRules.format(upToPound(short), UNIT)} behind" to true
    }

    fun entryId(goalId: String, day: Long): String = "$goalId.m$day"

    /** Goals' line under a followed goal's number. */
    fun followsInGoals(account: String): String = "Follows $account's balance in Money · brought up to date each time you open Money"

    /** The goals MONEY GOALS can show: open goals with a £ number. */
    fun candidates(goals: List<GoalItem>): List<GoalItem> = goals.filter { !it.done && it.metric?.unit == UNIT }

    /** Accounts a goal can follow: not cards, by name. */
    fun followable(accounts: List<MoneyAccount>, cardIds: Set<String>): List<MoneyAccount> =
        accounts.filter { it.id !in cardIds }.sortedBy { it.name.lowercase() }

    /**
     * The goals to bring up to date: each followed goal whose account still exists and has a balance, with that balance
     * in hundredths. A card or an account with no balance leaves the goal where it is.
     */
    fun toSync(goals: List<GoalItem>, accounts: List<MoneyAccount>, cardIds: Set<String>): List<Pair<String, Long>> {
        val byId = followable(accounts, cardIds).associateBy { it.id }
        return candidates(goals).mapNotNull { g ->
            val a = g.metric?.moneyAccountId?.let { byId[it] } ?: return@mapNotNull null
            val b = a.balance ?: return@mapNotNull null
            g.id to b.pence
        }
    }

    /** MONEY GOALS in Money: each £ goal, where it stands, which account it follows and the accounts to pick from. */
    fun view(goals: List<GoalItem>, accounts: List<MoneyAccount>, cardIds: Set<String>, today: Long): MoneyGoalsView {
        val gs = candidates(goals)
        val follow = followable(accounts, cardIds)
        if (gs.isEmpty()) return MoneyGoalsView(HEAD, emptyList(), EMPTY, FOOT)
        val rows = gs.mapIndexed { i, g ->
            val m = g.metric!!
            val linked = m.moneyAccountId?.let { id -> follow.firstOrNull { it.id == id } }
            val line = "${m.line} · ${m.pct}%" + (m.paceLine?.let { " · $it" } ?: "")
            val follows = when {
                linked == null -> null
                linked.balance == null -> "Follows ${linked.name} · it has no balance yet: type one under Net worth"
                else -> "Follows ${linked.name} · its balance ${MoneyRules.signedPlain(linked.balance.pence)} on ${CivilDate.shortLabel(linked.balance.day)}"
            }
            val choices = follow.map { MoneyGoalChoice(it.id, it.name, it.id == linked?.id) }
            val (note, behind) = note(m, today)
            MoneyGoalRow(
                id = g.id, title = g.title, line = line, pct = m.pct, follows = follows, choices = choices,
                spoken = listOfNotNull(g.title, m.line, note, linked?.let { "follows ${it.name}" }, "opens its sheet").joinToString(", "),
                amount = m.line, share = (m.currentX100.coerceAtLeast(0).toDouble() / m.targetX100.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat(),
                note = note, behind = behind, tone = i % TONES, followsName = linked?.name,
            )
        }
        return MoneyGoalsView(HEAD, rows, if (follow.isEmpty()) NO_ACCOUNTS else null, FOOT)
    }

    /** What linking said: "“Holiday” follows Savings · £800 of £2,000 now". */
    fun linkedSaid(goal: String, account: String, balancePence: Long?, target: GoalMetric?): String =
        "“$goal” follows $account" + when {
            balancePence == null -> " · it moves once the account has a balance"
            target != null -> " · ${GoalMetricRules.line(balancePence, target.targetX100, UNIT)} now"
            else -> ""
        }

    fun unlinkedSaid(goal: String): String = "“$goal” no longer follows an account · its number stays where it got to"
}

/** One account a goal can follow; [picked] when it does. */
data class MoneyGoalChoice(val accountId: String, val name: String, val picked: Boolean)

/**
 * One £ goal in GOALS: [line] "£800 of £2,000 · 40%", [follows] which account and its balance, or null for none. The card's
 * row (Plan's goals card, 2026-10-27): [amount] "£800 of £2,000", [share] of the bar (0–1), [note] its pace, [behind] when
 * it's off pace (the note in amber), [tone] its colour (0–6, the money palette), [followsName] the account it follows.
 */
data class MoneyGoalRow(
    val id: String, val title: String, val line: String, val pct: Int, val follows: String?,
    val choices: List<MoneyGoalChoice>, val spoken: String,
    val amount: String = line, val share: Float = 0f, val note: String = "", val behind: Boolean = false,
    val tone: Int = 0, val followsName: String? = null,
)

/** MONEY GOALS: the £ goals, or [empty] (no £ goal, or no account to follow) and the [foot]. */
data class MoneyGoalsView(val head: String, val rows: List<MoneyGoalRow>, val empty: String?, val foot: String)
