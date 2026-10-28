package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Money redesign, Plan (mockup v3/v4, approved 2026-10-25): **Obi's money check**. The tips from his numbers become
 * ideas to save, each built only from his own payments and showing the payments behind it, with **Remind me** (put
 * away for [REMIND_DAYS] days, then it comes back) and **Not for me** (put away for good), both on both devices with
 * Undo. Rules find every idea and every number (this slice); Obi's wording of them (the AI, approved 2026-10-24 21:13)
 * ([MoneyCheckWords], 2026-10-28) may only reword and rank what is here, never add a payment.
 *
 * Ideas ([MoneyIdeaKind]), biggest yearly saving first:
 * - **interest**: interest and fees charged in the last [INTEREST_DAYS] days, at least [INTEREST_MIN_PENCE] — what
 *   that costs a year; "Clear my cards" on the same tab shows how to stop it.
 * - **rise**: a regular payment that went up — the rise over a year; asking for the old price may get it back.
 * - **habit**: coffee, takeaways or eating out 8+ times and £30+ in 30 days — what halving it saves a year.
 * - **subs**: two or more subscriptions — what they cost a month; never a claim he doesn't use one (no data says so),
 *   so it adds nothing to the yearly total.
 * - **over**: a category well over its 6-month average this month — how far over; nothing to the yearly total.
 * - His own categories ([MoneyOwnPlan], 2026-10-28) are ideas too: a want of his ("Vape") bought often is a **habit**,
 *   and one well over its usual this month is **over**, each with Set a cap.
 *
 * Never moves money, cancels or pays anything; "not financial advice" sits in the tab's foot ⓘ ([MoneyTabs.info]).
 */
enum class MoneyIdeaKind(val key: String) {
    INTEREST("interest"), RISE("rise"), HABIT("habit"), SUBS("subs"), OVER("over");
}

/** One payment behind an idea: who, when ("Tue 6 Oct") and how much ("£12.49"). */
data class MoneyIdeaPay(val label: String, val date: String, val amount: String)

/**
 * One idea: [title] the fact, [figure] its number for the row's right side ("~£470/yr", "£84/mo", "£120 over"),
 * [savePence] what it adds to the yearly total (0 when no saving can be claimed), [why] two short sentences, [behind]
 * the payments it came from, newest first, and [act] an optional step in the app ([actKey] [MoneyCheck.ACT_CLEAR] or
 * [MoneyCheck.ACT_CAP]). [lit] wears the accent (interest, a rise, a category over).
 */
data class MoneyIdea(
    val id: String, val kind: String, val title: String, val figure: String, val savePence: Long, val why: String,
    val behind: List<MoneyIdeaPay>, val act: String?, val actKey: String?, val lit: Boolean, val spoken: String,
    /** The rules' own title and why (what goes to Obi, and what [MoneyCheckWords.check] reads); [byObi] when he worded it. */
    val ruleTitle: String = title, val ruleWhy: String = why, val byObi: Boolean = false,
)

/** What he put away: ideas he said Not for me to, and ideas he asked to be reminded of, with the day they come back. */
data class MoneyIdeaState(val notFor: Set<String> = emptySet(), val until: Map<String, Long> = emptyMap())

/**
 * OBI'S MONEY CHECK: [total] "£1,960 a year" (null when no idea saves a sum) over [from] "from 4 ideas", the [ideas]
 * (empty with [empty] said instead), and [away] "2 put away" with Show them again when any are put away.
 */
data class MoneyCheckView(
    val total: String?, val from: String, val ideas: List<MoneyIdea>, val empty: String?, val away: String?,
    /** "Written by Obi · Sun 25 Oct" when any idea shows in his words ([MoneyCheckWords]); null otherwise. */
    val by: String? = null,
)

object MoneyCheck {
    const val HEAD = "OBI'S MONEY CHECK"
    const val SHEET = "Idea"
    const val BEHIND = "THE PAYMENTS BEHIND IT"
    const val REMIND = "Remind me"
    const val NOT_FOR_ME = "Not for me"
    const val SHOW_AGAIN = "Show them again"
    const val SHOWN_AGAIN = "Ideas shown again"
    const val EMPTY = "Nothing to look at just now · Obi checks again as payments come in"
    const val SHOWN = 3
    const val BEHIND_MAX = 6
    const val REMIND_DAYS = 14L
    const val INTEREST_DAYS = 90L
    const val INTEREST_MIN_PENCE = 1_000L
    const val SUBS_MIN = 2
    const val ACT_CLEAR = "clear"
    const val ACT_CAP = "cap"
    const val ACT_CLEAR_LABEL = "See Clear my cards"
    const val ACT_CAP_LABEL = "Set a cap"
    const val NOT_FOR_SAID = "Put away · it won't come back"
    const val FROM_LINE = "Obi's money check finds ideas in your own payments (the last 6 months): card interest and fees, prices that went up, habits, subscriptions and categories over usual. Each shows the payments behind it. Remind me brings one back in 2 weeks."

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Pence rounded to the nearest £10 (£1 under £10). */
    private fun roundTen(p: Long): Long = if (p < 1_000) (p / 100.0).roundToLong() * 100 else (p / 1_000.0).roundToLong() * 1_000

    private fun yearly(p: Long) = "~${fmt(p)}/yr"

    fun remindSaid(day: Long): String = "Obi brings it back on ${CivilDate.shortLabel(day)}"

    /** "2 put away" for the card's foot; null with none. */
    fun awayLine(n: Int): String? = if (n <= 0) null else "$n put away"

    private fun pay(l: MoneyLine) = MoneyIdeaPay(MoneyCategoryRules.merchantLabel(l.merchant), CivilDate.shortLabel(l.day), fmt(-l.pence))

    private fun behind(ls: List<MoneyLine>) = ls.sortedByDescending { it.day }.take(BEHIND_MAX).map(::pay)

    /** Interest and fees charged in the last [INTEREST_DAYS] days, a year of it at that pace. */
    fun interest(lines: List<MoneyLine>, today: Long): MoneyIdea? {
        val from = today - INTEREST_DAYS + 1
        val ls = lines.filter { it.category == MoneyCategory.FEES && it.pence < 0 && it.day in from..today }
        val total = -ls.sumOf { it.pence }
        if (total < INTEREST_MIN_PENCE) return null
        val save = roundTen(total * 365 / INTEREST_DAYS)
        val title = "Card interest and fees"
        val n = ls.size
        val why = "${fmt(total)} in interest and fees in the last 3 months, $n charge${if (n == 1) "" else "s"}. Clear my cards on this tab shows how to clear what the cards owe."
        return MoneyIdea("interest", MoneyIdeaKind.INTEREST.key, title, yearly(save), save, why, behind(ls), ACT_CLEAR_LABEL, ACT_CLEAR,
            lit = true, spoken = "$title, about ${fmt(save)} a year. $why")
    }

    /** A regular payment that went up: the rise over a year. */
    fun rises(lines: List<MoneyLine>, regular: List<MoneyTips.Regular>): List<MoneyIdea> =
        regular.filter { it.rise > 0 }.sortedByDescending { it.rise }.take(MoneyTips.MAX_PER_KIND).map { r ->
            val save = r.rise * 12
            val title = "${r.label} went up · ask for a better price"
            val why = "${fmt(r.monthly)} a month from ${CivilDate.shortLabel(r.lastDay)}, up from ${fmt(r.monthly - r.rise)}. Asking for the old price may bring it back down."
            MoneyIdea("rise-${r.key}", MoneyIdeaKind.RISE.key, title, yearly(save), save, why,
                behind(lines.filter { it.merchant == r.key && it.pence < 0 }), null, null, lit = true,
                spoken = "$title, ${fmt(save)} a year. $why")
        }

    /** Coffee, takeaways or eating out often: what halving it saves a year. */
    fun habits(lines: List<MoneyLine>, today: Long): List<MoneyIdea> {
        val from = today - 29
        return listOf(MoneyCategory.COFFEE, MoneyCategory.TAKEAWAY, MoneyCategory.EATING_OUT).mapNotNull { c ->
            val ls = lines.filter { it.category == c && it.pence < 0 && it.day in from..today }
            val total = -ls.sumOf { it.pence }
            if (ls.size < MoneyTips.HABIT_MIN_COUNT || total < MoneyTips.HABIT_MIN_PENCE) return@mapNotNull null
            val save = roundTen(total * 12 / 2)
            val title = "${c.label} ${ls.size}× in 30 days"
            val why = "${fmt(total)} in the last 30 days. Halving it saves about ${fmt(save)} a year."
            MoneyIdea("habit-${c.key}", MoneyIdeaKind.HABIT.key, title, yearly(save), save, why, behind(ls), ACT_CAP_LABEL, ACT_CAP,
                lit = false, spoken = "$title, about ${fmt(save)} a year. $why")
        }.sortedByDescending { it.savePence }.take(MoneyTips.MAX_PER_KIND)
    }

    /** Two or more subscriptions: what they cost; a check, never a claim he doesn't use one. */
    fun subs(regular: List<MoneyTips.Regular>): MoneyIdea? {
        val s = regular.filter { it.category == MoneyCategory.SUBSCRIPTIONS }.sortedByDescending { it.monthly }
        if (s.size < SUBS_MIN) return null
        val monthly = s.sumOf { it.monthly }
        val title = "${s.size} subscriptions · still using them all?"
        val named = s.take(2).joinToString(" and ") { "${it.label} ${fmt(it.monthly)}" }
        val why = "$named${if (s.size > 2) " and ${s.size - 2} more" else ""}, every month: ${fmt(monthly * 12)} a year. One you no longer use saves its whole year."
        val rows = s.take(BEHIND_MAX).map { MoneyIdeaPay(it.label, "monthly · last ${CivilDate.shortLabel(it.lastDay)}", fmt(it.monthly)) }
        return MoneyIdea("subs", MoneyIdeaKind.SUBS.key, title, "${fmt(monthly)}/mo", 0, why, rows, null, null, lit = false,
            spoken = "$title, ${fmt(monthly)} a month. $why")
    }

    /** This month's categories well over their 6-month average ([MoneyTips.over]): how far, with a cap to set. */
    fun over(lines: List<MoneyLine>, today: Long): List<MoneyIdea> = MoneyTips.over(lines, today).filter { it.id.startsWith("over-") }.mapNotNull { t ->
        val key = t.id.removePrefix("over-")
        val c = MoneyCategory.of(key) ?: return@mapNotNull null
        val by = Regex("""£[\d,]+(\.\d\d)?""").find(t.title)?.value ?: return@mapNotNull null
        val month = MoneyRules.monthOf(today)
        val ls = lines.filter { it.category == c && it.pence < 0 && MoneyRules.monthOf(it.day) == month }
        MoneyIdea("over-$key", MoneyIdeaKind.OVER.key, "${c.label} over usual this month", "$by over", 0, "${t.title}. ${t.line}.",
            behind(ls), ACT_CAP_LABEL, ACT_CAP, lit = true, spoken = "${c.label} $by over usual this month. ${t.line}")
    }

    /** His own categories bought often (wants only, as [habits]): "Vape 12× in 30 days", what halving saves a year. */
    fun ownHabits(lines: List<MoneyLine>, today: Long, plan: MoneyOwnPlan): List<MoneyIdea> {
        val from = today - 29
        return plan.spendBy(lines.filter { it.day in from..today }).mapNotNull { (c, all) ->
            val ls = all.filter { !plan.need(it) }
            val total = -ls.sumOf { it.pence }
            if (ls.size < MoneyTips.HABIT_MIN_COUNT || total < MoneyTips.HABIT_MIN_PENCE) return@mapNotNull null
            val save = roundTen(total * 12 / 2)
            val title = "${c.name} ${ls.size}× in 30 days"
            val why = "${fmt(total)} in the last 30 days. Halving it saves about ${fmt(save)} a year."
            MoneyIdea("habit-" + MoneyOwnPlan.ideaKey(c), MoneyIdeaKind.HABIT.key, title, yearly(save), save, why, behind(ls),
                ACT_CAP_LABEL, ACT_CAP, lit = false, spoken = "$title, about ${fmt(save)} a year. $why")
        }.sortedByDescending { it.savePence }.take(MoneyTips.MAX_PER_KIND)
    }

    /**
     * His own categories well over their average this month (the same test as Spending's: at least
     * [MoneyRules.OVER_MIN_PENCE] and [MoneyRules.OVER_SHARE] over, against the up to 6 whole months before with statements).
     */
    fun ownOver(lines: List<MoneyLine>, today: Long, plan: MoneyOwnPlan): List<MoneyIdea> {
        if (!plan.any || lines.isEmpty()) return emptyList()
        val firstMonth = MoneyRules.monthOf(lines.minOf { it.day })
        val month = MoneyRules.monthOf(today)
        val prior = (1..6).map { month - it }.filter { it >= firstMonth }
        if (prior.size < 2) return emptyList()
        val d = CivilDate.fromEpochDay(today)
        val left = CivilDate.lengthOfMonth(d.year, d.month) - d.day
        val monthName = CivilDate.MONTH_LONG[month % 12]
        val avgName = MoneyRules.averageName(prior)
        return plan.spendBy(lines).mapNotNull { (c, ls) ->
            val byMonth = ls.groupBy { MoneyRules.monthOf(it.day) }.mapValues { (_, m) -> -m.sumOf { it.pence } }
            val p = byMonth[month] ?: 0L
            val avg = prior.sumOf { byMonth[it] ?: 0L } / prior.size
            if (p <= 0 || p - avg < MoneyRules.OVER_MIN_PENCE || p <= avg * (1 + MoneyRules.OVER_SHARE)) return@mapNotNull null
            val by = fmt(p - avg)
            val line = "${fmt(p)} so far in $monthName · " + when (left) { 0 -> "last day of the month"; 1 -> "1 day to go"; else -> "$left days to go" }
            val cur = ls.filter { MoneyRules.monthOf(it.day) == month }
            Triple(p - avg, c, MoneyIdea("over-" + MoneyOwnPlan.ideaKey(c), MoneyIdeaKind.OVER.key, "${c.name} over usual this month", "$by over", 0,
                "${c.name} $by over $avgName. $line.", behind(cur), ACT_CAP_LABEL, ACT_CAP, lit = true,
                spoken = "${c.name} $by over usual this month. $line"))
        }.sortedByDescending { it.first }.take(MoneyTips.MAX_PER_KIND).map { it.third }
    }

    /** Every idea for [today], biggest yearly saving first (the ones without a sum after), none of a [hiddenKinds] tip kind. */
    fun ideas(
        lines: List<MoneyLine>, today: Long, regular: List<MoneyTips.Regular>, hiddenKinds: Set<String> = emptySet(),
        plan: MoneyOwnPlan = MoneyOwnPlan(),
    ): List<MoneyIdea> {
        if (lines.isEmpty()) return emptyList()
        val all = listOfNotNull(interest(lines, today)) +
            (if (MoneyTipKind.RISE.key in hiddenKinds) emptyList() else rises(lines, regular)) +
            (if (MoneyTipKind.HABIT.key in hiddenKinds) emptyList() else habits(lines, today) + ownHabits(lines, today, plan)) +
            listOfNotNull(subs(regular)) +
            (if (MoneyTipKind.OVER.key in hiddenKinds) emptyList() else over(lines, today) + ownOver(lines, today, plan))
        // A category that is already a habit idea isn't said twice as "over usual".
        val habitCats = all.filter { it.kind == MoneyIdeaKind.HABIT.key }.map { it.id.removePrefix("habit-") }.toSet()
        return all.filter { !(it.kind == MoneyIdeaKind.OVER.key && it.id.removePrefix("over-") in habitCats) }.sortedWith(compareByDescending<MoneyIdea> { it.savePence }.thenBy { it.kind })
    }

    /** Whether [id] is put away on [today]: Not for me, or Remind me until a day still to come. */
    fun away(id: String, state: MoneyIdeaState, today: Long): Boolean = id in state.notFor || (state.until[id]?.let { it > today } == true)

    /** The money check for [today]; null without this month's lines. */
    fun view(
        lines: List<MoneyLine>, today: Long, regular: List<MoneyTips.Regular>, state: MoneyIdeaState, hiddenKinds: Set<String> = emptySet(),
        words: MoneyCheckWordsState = MoneyCheckWordsState(), plan: MoneyOwnPlan = MoneyOwnPlan(),
    ): MoneyCheckView? {
        if (lines.isEmpty()) return null
        // A kind of tip hidden before the money check (habits, over, rises) counts as put away, so Show them again brings it back.
        val found = ideas(lines, today, regular, plan = plan)
        val shown = found.filter { !away(it.id, state, today) && it.kind !in hiddenKinds }
        val awayN = found.size - shown.size
        val save = shown.sumOf { it.savePence }
        val n = shown.size
        return MoneyCheckWords.apply(MoneyCheckView(
            total = if (save > 0) "${fmt(save)} a year" else null,
            from = if (n == 0) "no ideas just now" else "from $n idea${if (n == 1) "" else "s"}",
            ideas = shown, empty = if (n == 0) EMPTY else null, away = awayLine(awayN),
        ), words)
    }

    /** The first [SHOWN] ideas until "more" opens them. */
    fun shown(ideas: List<MoneyIdea>, open: Boolean): List<MoneyIdea> = if (open) ideas else ideas.take(SHOWN)

    /** "2 more ›" folded, Fewer open; null when every idea already shows. */
    fun more(n: Int, open: Boolean): String? = when {
        n <= SHOWN -> null
        open -> MoneyRefunds.FEWER
        else -> "${n - SHOWN} more ›"
    }

    /** The idea whose sheet is open, found again in each view (Remind me and Not for me take it away, closing the sheet). */
    fun ideaOf(v: MoneyCheckView?, id: String?): MoneyIdea? = id?.let { k -> v?.ideas?.firstOrNull { it.id == k } }

    /** An idea id a device may store: what [ideas] makes (letters, digits, "-", "_", spaces from a merchant key), short. */
    fun validId(id: String): Boolean = id.isNotEmpty() && id.length <= 60 && id.all { it.isLetterOrDigit() || it in "-_ .&'" }
}
