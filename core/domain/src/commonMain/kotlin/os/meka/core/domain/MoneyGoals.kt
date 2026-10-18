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
 */
object MoneyGoals {
    /** Only £ goals can follow an account. */
    const val UNIT = "£"
    const val HEAD = "MONEY GOALS"
    const val NOT_POUNDS = "Only a goal with a £ number can follow an account"
    const val EMPTY = "No goal with a £ number yet · in Goals, give one a number (like Save £2,000) and it can follow an account's balance here."
    const val NO_ACCOUNTS = "Add an account (a statement, or your bank) first · then a goal can follow its balance."
    const val FOOT = "A goal that follows an account shows its amount in Goals, outside Money's lock. It's brought up to date each time you open Money."
    const val NONE = "None"

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
    fun view(goals: List<GoalItem>, accounts: List<MoneyAccount>, cardIds: Set<String>): MoneyGoalsView {
        val gs = candidates(goals)
        val follow = followable(accounts, cardIds)
        if (gs.isEmpty()) return MoneyGoalsView(HEAD, emptyList(), EMPTY, FOOT)
        val rows = gs.map { g ->
            val m = g.metric!!
            val linked = m.moneyAccountId?.let { id -> follow.firstOrNull { it.id == id } }
            val line = "${m.line} · ${m.pct}%" + (m.paceLine?.let { " · $it" } ?: "")
            val follows = when {
                linked == null -> null
                linked.balance == null -> "Follows ${linked.name} · it has no balance yet: type one under Net worth"
                else -> "Follows ${linked.name} · its balance ${MoneyRules.signedPlain(linked.balance.pence)} on ${CivilDate.shortLabel(linked.balance.day)}"
            }
            val choices = follow.map { MoneyGoalChoice(it.id, it.name, it.id == linked?.id) }
            MoneyGoalRow(
                id = g.id, title = g.title, line = line, pct = m.pct, follows = follows, choices = choices,
                spoken = listOfNotNull(g.title, line, follows ?: "Doesn't follow an account").joinToString(", "),
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

/** One £ goal in MONEY GOALS: [line] "£800 of £2,000 · 40%", [follows] which account and its balance, or null for none. */
data class MoneyGoalRow(
    val id: String, val title: String, val line: String, val pct: Int, val follows: String?,
    val choices: List<MoneyGoalChoice>, val spoken: String,
)

/** MONEY GOALS: the £ goals, or [empty] (no £ goal, or no account to follow) and the [foot]. */
data class MoneyGoalsView(val head: String, val rows: List<MoneyGoalRow>, val empty: String?, val foot: String)
