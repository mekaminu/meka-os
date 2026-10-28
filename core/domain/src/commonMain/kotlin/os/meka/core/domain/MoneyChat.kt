package os.meka.core.domain

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Money redesign, **Ask Obi** (mockup `docs/design/money/`, v4, approved Meka 2026-10-25 09:30), slice 1: a bar on every
 * Money tab ("Ask Obi: “Can I afford…”, “What if I save…”") opens **Obi · money**, a short money chat answered on the
 * device from his own numbers, each answer with the buttons the mockup shows (Make it a goal, Use this plan, Show the
 * numbers). Rules only, nothing sent: the AI's own money chat is the next slice.
 *
 * What it answers, beyond [MoneyAskRules] (spending, what's coming out, payday, safe to spend, can I afford £N on a day,
 * paying abroad):
 * - **What if I save** "£300 a month for 6 months": the total and the month it's reached ([MoneySave.balances], no
 *   interest), whether it fits his average month ([MoneySave.obi]), and with "… and clear my cards" the cards' clear date
 *   on what's left over ([MoneyClear.run]). Make it a goal.
 * - **Can I afford £1,200 in July** (or "by July"): a month ahead turns into a saving pace, "about £150 a month from
 *   November", and whether that fits. Make it a goal.
 * - **Which card first / clear my cards** ("… at £500 a month"): the highest-rate card first, why, and when they clear.
 *   Use this plan.
 *
 * Plain arithmetic with the inputs said; never moves money; "not financial advice" once, in [FOOT].
 */
data class MoneyChatAction(
    /** [MoneyChat.ACT_GOAL], [MoneyChat.ACT_CLEAR] or [MoneyChat.ACT_PLAN]. */
    val kind: String,
    val label: String,
    /** ACT_GOAL: the monthly amount; ACT_CLEAR: the monthly payment. */
    val amountPence: Long = 0,
    /** ACT_GOAL: how many months. */
    val months: Int = 0,
    /** ACT_CLEAR: [MoneyClear.BY_RATE] or [MoneyClear.BY_SMALL]. */
    val order: String = "",
)

/** One turn: his [question], Obi's [text] and its [actions]; [refused] when Money was locked or had nothing to go on. */
data class MoneyChatTurn(
    val question: String,
    val text: String,
    val actions: List<MoneyChatAction> = emptyList(),
    val refused: Boolean = false,
)

/** What the chat reads: Ask's money data, and the Plan tab's bases (null when not worked out yet). */
data class MoneyChatData(
    val ask: MoneyAskData,
    val save: MoneySaveBase?,
    val clear: MoneyClearBase?,
)

object MoneyChat {
    const val BAR = "Ask Obi: “Can I afford…”, “What if I save…”"
    const val BAR_SPOKEN = "Ask Obi about your money"
    const val HEAD = "Obi · money"
    const val TRY = "Try"
    const val FIELD = "Ask about your money…"
    const val SEND = "Ask"
    const val DONE = "Done"
    const val WORKING = "Obi is working it out…"
    const val FOOT = "Worked out on this device from your own numbers; nothing is sent. Sums, not financial advice."
    const val HELLO = "Ask me about your money: what you've spent, what's coming out, whether you can afford something, what saving a little each month adds up to, or which card to clear first."

    const val ACT_GOAL = "goal"
    const val ACT_CLEAR = "clear"
    const val ACT_PLAN = "plan"
    const val MAKE_GOAL = MoneySave.MAKE_GOAL
    const val USE_PLAN = MoneyClear.USE
    const val SHOW = "Show the numbers"

    /** The chat keeps the last few turns while the sheet is open (never stored or synced). */
    const val KEEP = 6
    const val MAX_MONTHS = 60

    private val NOT_ADVICE = MoneyAskRules.NOT_ADVICE

    private val SAVE = Regex("""\b(save|saving|put (aside|away|by))\b""", RegexOption.IGNORE_CASE)
    private val A_MONTH = Regex("""\b(a|per|each|every) month\b|\bmonthly\b|/\s?m(onth)?\b|\bpcm\b""", RegexOption.IGNORE_CASE)
    private val CARDS = Regex("""\b(which|what) (credit )?card\b.*\b(pay|clear|first)\b|\b(clear|pay (off|down)|paying off|get rid of)\b.*\b(my |the |both |all )?(credit )?cards?\b|\b(cards?|debt) (plan|free)\b|\bdebt[- ]free\b""", RegexOption.IGNORE_CASE)
    private val AFFORD = Regex("""\bafford\b""", RegexOption.IGNORE_CASE)
    private val HOW_TO = Regex("""^\s*(how (do|does|should) i (use|change|set)|where (do|is|are|can)|explain|help)\b""", RegexOption.IGNORE_CASE)

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun monthShort(m: Int): String = "${Recurrence.MONTH_SHORT[m % 12]} ${m / 12}"
    private fun monthLong(m: Int): String = CivilDate.MONTH_LONG[m % 12]

    /** Whether [question] is one this chat answers (its own kinds, or Ask's money rules). */
    fun isAbout(question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty() || HOW_TO.containsMatchIn(q)) return false
        return isSave(q) || CARDS.containsMatchIn(q) || (AFFORD.containsMatchIn(q) && MoneyAskRules.amount(q) != null) || MoneyAskRules.isAbout(q)
    }

    private fun isSave(q: String): Boolean = SAVE.containsMatchIn(q) && MoneyAskRules.amount(q) != null

    /**
     * How many months [q] says ("for 6 months", "for a year", "18-month", "two years"), else null. Capped at
     * [MAX_MONTHS].
     */
    fun monthsIn(q: String): Int? {
        val s = q.lowercase()
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "eighteen" to 18, "a" to 1)
        Regex("""\b(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|eighteen)[- ]months?\b""").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1]] ?: return null
            return n.coerceIn(1, MAX_MONTHS)
        }
        Regex("""\b(a|one|two|three|four|five|\d)[- ]years?\b""").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1]] ?: return null
            return (n * 12).coerceIn(1, MAX_MONTHS)
        }
        if (Regex("""\bhalf a year\b""").containsMatchIn(s)) return 6
        return null
    }

    /** The month (year × 12 + month − 1) [q] names after "in", "by", "for" or "before", the next one after [thisMonth]. */
    fun monthAhead(q: String, thisMonth: Int): Int? {
        val s = q.lowercase()
        val i = CivilDate.MONTH_LONG.indexOfFirst { n ->
            val l = n.lowercase()
            Regex("""\b(in|by|for|before|until|till|this|next|come) (${l}|${l.take(3)})\b""").containsMatchIn(s)
        }
        if (i < 0) return null
        var m = (thisMonth / 12) * 12 + i
        if (m <= thisMonth) m += 12
        return m
    }

    /** The answer to [question] from [d]. */
    fun answer(question: String, d: MoneyChatData): MoneyChatTurn {
        val q = question.trim()
        val a = d.ask
        if (a.lines.isEmpty() && a.upcoming.isEmpty() && d.clear == null) return MoneyChatTurn(q, MoneyAskRules.EMPTY, refused = true)
        return when {
            CARDS.containsMatchIn(q) && !isSave(q) -> cards(q, d)
            isSave(q) -> save(q, d)
            AFFORD.containsMatchIn(q) && MoneyAskRules.amount(q) != null && monthAhead(q, MoneyRules.monthOf(a.today)) != null -> affordLater(q, d)
            MoneyAskRules.isAbout(q) -> MoneyChatTurn(q, MoneyAskRules.answer(q, a))
            else -> MoneyChatTurn(q, HELLO)
        }
    }

    private fun thisMonth(d: MoneyChatData): Int = d.save?.thisMonth ?: MoneyRules.monthOf(d.ask.today)

    /** "Saving £300 a month for 6 months puts by £1,800 by Apr 2027." + whether it fits, and the cards with what's left. */
    fun save(q: String, d: MoneyChatData): MoneyChatTurn {
        val amount = MoneyAskRules.amount(q) ?: return MoneyChatTurn(q, HELLO)
        val monthly = A_MONTH.containsMatchIn(q) || monthsIn(q) != null
        val now = thisMonth(d)
        val left = d.save?.leftPence
        if (!monthly) {
            // "Save £1,200 by July": a total by a month.
            val by = monthAhead(q, now) ?: return MoneyChatTurn(q, "How much a month, and for how long? Try “What if I save £200 a month for 6 months?”")
            return pace(q, amount, by, now, left, "save")
        }
        val n = monthsIn(q) ?: MoneySave.DEFAULT_MONTHS
        val total = MoneySave.balances(amount, n, 0).last()
        val parts = mutableListOf("Saving ${fmt(amount)} a month for $n month${if (n == 1) "" else "s"} puts by ${fmt(MoneySave.roundPound(total))} by ${monthShort(now + n)}.")
        parts += MoneySave.obi(amount, left).first
        val c = d.clear
        if (c != null && Regex("""\bcards?\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
            val room = left?.let { it - amount }
            if (room == null) parts += "Once two whole months are in, I can say what that leaves for the cards."
            else if (room < c.minPence) parts += "That leaves about ${fmt(MoneyForecast.roundTen(room.coerceAtLeast(0)))} for the cards, less than their minimums (${fmt(c.minPence)}), so they wouldn't clear."
            else {
                val pay = MoneyClear.snap(room, c)
                val run = MoneyClear.run(c.cards, pay, MoneyClear.BY_RATE, c.thisMonth)
                parts += run.months?.let { "With the other ${fmt(pay)} a month on the cards, they'd clear by ${monthShort(c.thisMonth + it)}." }
                    ?: "The other ${fmt(pay)} a month on the cards only keeps up with their interest."
            }
        }
        return MoneyChatTurn(q, parts.joinToString(" ") + NOT_ADVICE, listOf(
            MoneyChatAction(ACT_GOAL, MAKE_GOAL, amountPence = amount, months = n),
            MoneyChatAction(ACT_PLAN, SHOW),
        ))
    }

    /** "To have £1,200 by July, put aside about £150 a month from November (8 months)." */
    private fun pace(q: String, total: Long, by: Int, now: Int, left: Long?, kind: String): MoneyChatTurn {
        val n = (by - now).coerceIn(1, MAX_MONTHS)
        val each = (ceil(total.toDouble() / n / 500.0) * 500).roundToLong().coerceAtLeast(500)
        val from = monthLong(now + 1)
        val head = if (kind == "afford") "If you start now: put aside about ${fmt(each)} a month from $from and you'd have ${fmt(total)} by ${monthLong(by)} ($n month${if (n == 1) "" else "s"})."
        else "To have ${fmt(total)} by ${monthLong(by)}, put aside about ${fmt(each)} a month from $from ($n month${if (n == 1) "" else "s"})."
        val fit = MoneySave.obi(each, left).first
        return MoneyChatTurn(q, "$head $fit$NOT_ADVICE", listOf(
            MoneyChatAction(ACT_GOAL, MAKE_GOAL, amountPence = each, months = n),
            MoneyChatAction(ACT_PLAN, SHOW),
        ))
    }

    /** "Can I afford a £1,200 holiday in July?": a month ahead becomes a saving pace. */
    fun affordLater(q: String, d: MoneyChatData): MoneyChatTurn {
        val amount = MoneyAskRules.amount(q) ?: return MoneyChatTurn(q, HELLO)
        val now = thisMonth(d)
        val by = monthAhead(q, now) ?: return MoneyChatTurn(q, MoneyAskRules.answer(q, d.ask))
        return pace(q, amount, by, now, d.save?.leftPence, "afford")
    }

    /** "Card A first: it charges 24.9%, more than Card B's 22.9%. … At £600 a month they'd clear by Mar 2027." */
    fun cards(q: String, d: MoneyChatData): MoneyChatTurn {
        val c = d.clear ?: return MoneyChatTurn(q, "None of your cards owes anything that I can see. Mark a card on Accounts (tap it → It's a card) and add its statement on Bills.")
        val pay = MoneyClear.snap(MoneyAskRules.amount(q) ?: c.startPence, c)
        val run = MoneyClear.run(c.cards, pay, MoneyClear.BY_RATE, c.thisMonth)
        val parts = mutableListOf<String>()
        val known = c.cards.filter { it.aprBp != null }.sortedByDescending { it.aprBp }
        if (c.cards.size == 1) parts += "${c.cards[0].name} is the only card that owes anything (${fmt(c.cards[0].owedPence)})."
        else if (known.size >= 2) {
            val first = known[0]
            val next = known[1]
            if (first.aprBp == next.aprBp) parts += "${first.name} and ${next.name} charge the same, so clear the smaller one first: it's done sooner for the same interest."
            else parts += "${first.name} first: it charges ${MoneyCards.aprText(first.aprBp!!)}, more than ${next.name}'s ${MoneyCards.aprText(next.aprBp!!)}. Keep paying the minimum on the others and put the rest on ${first.name}."
        } else parts += "I don't know every card's rate yet: type each card's APR in its sheet on Bills and I can say which costs most."
        parts += run.months?.let { "At ${fmt(pay)} a month they'd clear by ${monthShort(c.thisMonth + it)}, about ${fmt(MoneyForecast.roundTen(run.interestPence).coerceAtLeast(0))} in interest on the way." }
            ?: "At ${fmt(pay)} a month the interest keeps up with the payments: it needs more a month to clear."
        val acts = buildList {
            if (run.months != null) add(MoneyChatAction(ACT_CLEAR, USE_PLAN, amountPence = pay, order = MoneyClear.BY_RATE))
            add(MoneyChatAction(ACT_PLAN, SHOW))
        }
        return MoneyChatTurn(q, parts.joinToString(" ") + " Sums only, not advice.", acts)
    }

    /**
     * The "Try" chips: a what-if at the slider's start, can I afford on Saturday, which card first (when a card owes
     * anything), and his biggest category this year.
     */
    fun suggestions(d: MoneyChatData): List<String> {
        val start = d.save?.startPence ?: MoneySave.DEFAULT_PENCE
        val out = mutableListOf("What if I save ${fmt(start)} a month for 6 months?")
        out += "Can I afford £300 on Saturday?"
        if (d.clear != null) out += if (d.clear.cards.size > 1) "Which card should I pay off first?" else "How soon can I clear my card?"
        val year = CivilDate.fromEpochDay(d.ask.today).year
        val from = CivilDate.toEpochDay(year, 1, 1)
        val top = d.ask.lines.filter { it.day >= from && it.pence < 0 && it.category.spend && it.category != MoneyCategory.OTHER }
            .groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }
            .entries.sortedByDescending { it.value }.map { it.key }
            .firstOrNull { MoneyAskRules.category(it.label.lowercase()) == it }
        out += top?.let { "How much have I spent on ${it.label.lowercase()} this year?" } ?: "How much have I spent this month?"
        return out
    }

    /** The goal Make it a goal makes: "Save £1,800 by Apr 2027", its target the total, due the end of that month. */
    data class Goal(val title: String, val target: String, val dueDay: Long, val horizon: GoalHorizon)

    fun goal(amountPence: Long, months: Int, thisMonth: Int): Goal {
        val n = months.coerceIn(1, MAX_MONTHS)
        val total = fmt(MoneySave.roundPound(amountPence * n))
        val m = thisMonth + n
        val y = m / 12
        val mo = m % 12 + 1
        val due = CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo))
        val h = when {
            n <= 3 -> GoalHorizon.QUARTER
            n <= 12 -> GoalHorizon.YEAR
            else -> GoalHorizon.SOMEDAY
        }
        return Goal("Save $total by ${monthShort(m)}", total, due, h)
    }

    /** The chat's turns after [turn] is added: the newest last, at most [KEEP]. */
    fun add(turns: List<MoneyChatTurn>, turn: MoneyChatTurn): List<MoneyChatTurn> = (turns + turn).takeLast(KEEP)
}
