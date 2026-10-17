package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Money manager (16), slice 1 (2026-10-17): **Ask and Talk answer money questions from his own Money**, on the device,
 * never sent to the AI (no amounts, merchants or balances leave the device for an answer; ADR-006 addendum). Rules
 * only:
 *
 * - **Spending**: "how much did I spend on takeaways last month?", "what did I spend at Tesco this week?", "how much
 *   have I spent this month?" — a category (its name or a common word for it), else a shop in his statements, else
 *   everything; over today, yesterday, this or last week, this or last month, a named month, this or last year, or the
 *   last N days (this month so far when none is said).
 * - **What's coming out**: "what's coming out this week?", "what bills are due before payday?" — his regular payments'
 *   next days, the bills, subscriptions and club fees on Lists → Renewals and each card's statement, counted once.
 * - **Can I afford it**: "can I afford £300 on Saturday?" — from the payday view's safe to spend (the pay account's
 *   balance less what's still to go out before payday).
 * - **Payday** and **safe to spend**: the payday view's own lines.
 *
 * Money is behind his fingerprint, so the facade answers only while Money is open or within [ASK_WINDOW_MS] of an
 * unlock from Ask ([UNLOCK]); otherwise [LOCKED] with an unlock button. Insight on his own numbers, not advice.
 */
data class MoneyAskData(
    /** Every account's lines, a statement line and the same bank line counted once, with merchant and category. */
    val lines: List<MoneyLine>,
    val today: Long,
    /** What's due to go out: regular payments' next days, radar bills and card statements, each once. */
    val upcoming: List<MoneyBill>,
    val payday: MoneyPaydayView?,
    /** Money manager (17): paying abroad, for "which card should I use abroad?" ([MoneyTravel]). */
    val travel: MoneyTravelView? = null,
)

object MoneyAskRules {
    const val LOCKED = "That's in Money, behind your fingerprint. Unlock it and I'll answer from your statements, on this device only."
    const val UNLOCK = "Unlock Money to answer"
    const val UNLOCK_REASON = "Answer from your money"
    const val EMPTY = "Money has nothing to go on yet · import a statement or connect your banks in Ask → Life → Money."
    const val HOW = "I can answer from Money: what you spent on something (“takeaways last month”), what's coming out this week, when payday is, what's safe to spend, whether you can afford an amount (“can I afford £300 on Saturday?”), or which card to use abroad."
    const val NOT_ADVICE = " From your own statements · not financial advice."
    /** How long an unlock from Ask lets money questions be answered (Talk's follow-ups too). */
    const val ASK_WINDOW_MS = 5 * 60_000L
    const val SHOWN = 6

    private val HOW_TO = Regex("""^\s*(how (do|does|should)|how to|where (do|is|are|can)|what does|what is the|explain|help)\b""", RegexOption.IGNORE_CASE)
    private val SPEND = Regex("""\b(spen[dt]|spending)\b""", RegexOption.IGNORE_CASE)
    private val NOT_MONEY = Regex("""\b(time|hours?|minutes?|night|nights|weekend|day off|days off|holiday allowance)\b""", RegexOption.IGNORE_CASE)
    private val OUT = Regex("""\bwhat'?s? (is )?(coming|going) out\b|\b(coming|going) out (of my|from my|this|next|today|tomorrow|before)\b|\b(due to come out|leaving my account|direct debits?|standing orders?|bills? (are |is )?(due|coming))\b""", RegexOption.IGNORE_CASE)
    private val NOT_OUT = Regex("""\b(cinema|film|films|movie|movies|album|game|games|book|books|episode|season|series|trailer)\b""", RegexOption.IGNORE_CASE)
    private val AFFORD = Regex("""\bafford\b""", RegexOption.IGNORE_CASE)
    private val PAYDAY = Regex("""\b(pay ?day|when (do|will) i get paid|when am i paid|next pay)\b""", RegexOption.IGNORE_CASE)
    private val SAFE = Regex("""\b(safe to spend|left to spend|how much (money )?(have i got|do i have) left|how much can i spend)\b""", RegexOption.IGNORE_CASE)
    private val ABROAD = Regex("""\b(foreign (transaction )?fees?|non[- ]?sterling|fx fees?)\b|\bwhich (card|account)\b.*\b(abroad|overseas|holiday|travel(l?ing)?)\b|\b(card|pay|paying|use)\b.*\b(abroad|overseas)\b""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""£\s?(\d[\d,]*(?:\.\d{1,2})?)\s?(k\b)?|\b(\d[\d,]*(?:\.\d{1,2})?)\s?(k\s)?\s?(pounds|quid)\b""", RegexOption.IGNORE_CASE)

    /** Whether [question] is a money question these rules answer (a how-to about Money stays with the help). */
    fun isAbout(question: String): Boolean {
        val q = question.trim()
        if (HOW_TO.containsMatchIn(q)) return false
        if (SAFE.containsMatchIn(q) || PAYDAY.containsMatchIn(q)) return true
        if (ABROAD.containsMatchIn(q)) return true
        if (OUT.containsMatchIn(q)) return !NOT_OUT.containsMatchIn(q)
        if (AFFORD.containsMatchIn(q)) return amount(q) != null
        return SPEND.containsMatchIn(q) && !NOT_MONEY.containsMatchIn(q)
    }

    /** The first amount in [q]: "£300", "£1,250.50", "300 pounds", "£1.5k"; null when none. */
    fun amount(q: String): Long? {
        val m = AMOUNT.find(q) ?: return null
        val raw = (m.groupValues[1].ifEmpty { m.groupValues[3] }).replace(",", "")
        val k = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotBlank()
        val pounds = raw.toDoubleOrNull() ?: return null
        val p = ((if (k) pounds * 1000 else pounds) * 100).roundToLong()
        return p.takeIf { it in 1..1_000_000_00L }
    }

    // ---- periods ----

    /** A span of days, both ends included, and how to say it ("last month (September)"). */
    data class Period(val from: Long, val to: Long, val said: String, val month: Int? = null)

    private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private fun monday(day: Long): Long = day - (CivilDate.isoDayOfWeek(day) - 1)

    private fun monthSpan(month: Int, today: Long): Period {
        val y = month / 12
        val mo = month % 12 + 1
        val from = CivilDate.toEpochDay(y, mo, 1)
        val end = CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo))
        return Period(from, minOf(end, today), "", month)
    }

    /** The spending question's span; this month so far when none is said. */
    fun period(question: String, today: Long): Period {
        val q = question.lowercase()
        val thisMonth = MoneyRules.monthOf(today)
        fun monthName(m: Int) = CivilDate.MONTH_LONG[m % 12]
        Regex("""\b(?:last|past) (\d{1,3}) days\b""").find(q)?.let { m ->
            val n = m.groupValues[1].toLong().coerceIn(1, 366)
            return Period(today - n + 1, today, "in the last $n days")
        }
        return when {
            Regex("""\byesterday\b""").containsMatchIn(q) -> Period(today - 1, today - 1, "yesterday")
            Regex("""\btoday\b""").containsMatchIn(q) -> Period(today, today, "today")
            Regex("""\blast week\b""").containsMatchIn(q) -> {
                val from = monday(today) - 7
                Period(from, from + 6, "last week (${CivilDate.shortLabel(from)} – ${CivilDate.shortLabel(from + 6)})")
            }
            Regex("""\bthis week\b""").containsMatchIn(q) -> Period(monday(today), today, "this week")
            Regex("""\blast month\b""").containsMatchIn(q) -> monthSpan(thisMonth - 1, today).copy(said = "last month (${monthName(thisMonth - 1)})")
            Regex("""\blast year\b""").containsMatchIn(q) -> {
                val y = CivilDate.fromEpochDay(today).year - 1
                Period(CivilDate.toEpochDay(y, 1, 1), CivilDate.toEpochDay(y, 12, 31), "last year ($y)")
            }
            Regex("""\bthis year\b""").containsMatchIn(q) -> {
                val y = CivilDate.fromEpochDay(today).year
                Period(CivilDate.toEpochDay(y, 1, 1), today, "this year so far")
            }
            else -> {
                val named = CivilDate.MONTH_LONG.indexOfFirst { n ->
                    Regex("""\b(${n.lowercase()}|${n.lowercase().take(3)})\b""").containsMatchIn(q) && !(n == "May" && !Regex("""\bin may\b|\bmay (20\d\d|so far)\b|\bfor may\b|\bduring may\b""").containsMatchIn(q))
                }
                if (named >= 0) {
                    // The latest such month that has begun.
                    var m = (thisMonth / 12) * 12 + named
                    if (m > thisMonth) m -= 12
                    val p = monthSpan(m, today)
                    p.copy(said = if (m == thisMonth) "this month so far" else "in ${monthName(m)}" + (if (m / 12 != thisMonth / 12) " ${m / 12}" else ""))
                } else monthSpan(thisMonth, today).copy(said = "this month so far")
            }
        }
    }

    // ---- what it's about ----

    private val CATEGORY_WORDS: List<Pair<MoneyCategory, Regex>> = listOf(
        MoneyCategory.TAKEAWAY to """takeaways?|take-?aways?|deliveroo|just eat|uber eats""",
        MoneyCategory.COFFEE to """coffees?|caf[eé]s?""",
        MoneyCategory.EATING_OUT to """eating out|restaurants?|meals out|dining( out)?|lunch(es)? out""",
        MoneyCategory.GROCERIES to """groceries|grocery|food shop(ping)?|supermarkets?|the shopping""",
        MoneyCategory.FUEL to """fuel|petrol|diesel""",
        MoneyCategory.TRANSPORT to """transport|trains?|travel cards?|tfl|tube|buses|parking|taxis?|ubers?""",
        MoneyCategory.SUBSCRIPTIONS to """subscriptions?|streaming""",
        MoneyCategory.BILLS to """bills|utilities|energy|council tax""",
        MoneyCategory.CLOTHES to """clothes|clothing""",
        MoneyCategory.SHOPPING to """shopping|online shopping""",
        MoneyCategory.HOME to """home|house|diy""",
        MoneyCategory.HEALTH to """health|fitness|the gym|gym""",
        MoneyCategory.FUN to """going out|fun|nights out|drinks|pubs?|bars?""",
        MoneyCategory.TRAVEL to """travel|holidays?|flights?|hotels?""",
        MoneyCategory.KIDS to """kids|children|the boys""",
        MoneyCategory.GIVING to """gifts|presents|giving|charity""",
        MoneyCategory.CASH to """cash""",
        MoneyCategory.FEES to """fees|interest|charges""",
    ).map { (c, w) -> c to Regex("""\b(on|for|at|in) ($w)\b|\b($w)\b""", RegexOption.IGNORE_CASE) }

    private val STOP = setOf(
        "how", "much", "did", "do", "does", "spend", "spent", "spending", "on", "at", "in", "for", "last", "this", "past",
        "month", "week", "year", "the", "what", "have", "has", "my", "and", "money", "total", "today", "yesterday", "days",
        "so", "far", "with", "was", "were", "all", "out", "about", "since", "tell", "me", "can", "you", "navilife", "meka",
    )

    /** The category [q] names, if any (the shops' words are tried first by [answerSpend] when no category matches). */
    fun category(q: String): MoneyCategory? {
        // "going out" and "eating out" before the bare words they contain; the list is in that order.
        val hits = CATEGORY_WORDS.filter { it.second.containsMatchIn(q) }.map { it.first }
        if (MoneyCategory.EATING_OUT in hits) return MoneyCategory.EATING_OUT
        if (MoneyCategory.FUN in hits && Regex("""\bgoing out\b|\bnights? out\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) return MoneyCategory.FUN
        if (MoneyCategory.GROCERIES in hits) return MoneyCategory.GROCERIES
        return hits.firstOrNull()
    }

    /** The shops in [lines] named by a word of [q] (3+ letters): their merchant keys. */
    fun merchants(q: String, lines: List<MoneyLine>): Set<String> {
        val words = q.lowercase().split(Regex("""[^a-z0-9&']+""")).filter { it.length >= 3 && it !in STOP && CivilDate.MONTH_LONG.none { m -> m.lowercase().startsWith(it) } }
        if (words.isEmpty()) return emptySet()
        val keys = lines.asSequence().filter { it.category.spend }.map { it.merchant }.toSet()
        return keys.filter { k -> val kw = k.lowercase().split(' '); words.any { it in kw } }.toSet()
    }

    // ---- answers ----

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun pounds(p: Long): Long = (p / 100.0).roundToLong() * 100

    private fun names(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }

    /** The answer to a money question ([isAbout]) from [d]. */
    fun answer(question: String, d: MoneyAskData): String {
        val q = question.trim()
        return when {
            ABROAD.containsMatchIn(q) && !AFFORD.containsMatchIn(q) -> MoneyTravel.answer(d.travel)
            AFFORD.containsMatchIn(q) -> answerAfford(q, d)
            SAFE.containsMatchIn(q) -> answerSafe(d)
            OUT.containsMatchIn(q) -> answerOut(q, d)
            PAYDAY.containsMatchIn(q) && !SPEND.containsMatchIn(q) -> answerPayday(d)
            SPEND.containsMatchIn(q) -> answerSpend(q, d)
            else -> HOW
        }
    }

    /** "You spent £71.40 on takeaways last month (September), 9 payments. Your 6-month average is £60 a month." */
    fun answerSpend(q: String, d: MoneyAskData): String {
        if (d.lines.isEmpty()) return EMPTY
        val p = period(q, d.today)
        val first = d.lines.minOf { it.day }
        val last = d.lines.maxOf { it.day }
        val cat = category(q)
        val shops = if (cat == null) merchants(q, d.lines) else emptySet()
        fun inPeriod(l: MoneyLine) = l.day in p.from..p.to
        val chosen: (MoneyLine) -> Boolean = when {
            cat != null -> { l -> l.category == cat }
            shops.isNotEmpty() -> { l -> l.merchant in shops && l.category.spend }
            else -> { l -> l.category.spend }
        }
        val these = d.lines.filter { inPeriod(it) && chosen(it) }
        val spent = -these.sumOf { it.pence }
        val n = these.count { it.pence < 0 }
        val what = when {
            cat != null -> " on ${cat.label.lowercase()}"
            shops.isNotEmpty() -> " at " + names(shops.map { MoneyCategoryRules.merchantLabel(it) }.sorted())
            else -> ""
        }
        val reach = buildList {
            if (p.to < first) add("Your statements start ${MoneyRules.dayMonth(first)}, so there's nothing that far back.")
            else if (p.from < first) add("Your statements start ${MoneyRules.dayMonth(first)}, so that's from then.")
            if (p.to > last && p.to >= first) add("Your statements reach ${CivilDate.shortLabel(last)}.")
        }
        if (p.to < first) return reach.joinToString(" ")
        val head = if (n == 0 || spent <= 0) "Nothing${what.ifEmpty { " spent" }} ${p.said}." else
            "You spent ${fmt(spent)}$what ${p.said}, ${n} payment${if (n == 1) "" else "s"}."
        val more = mutableListOf<String>()
        // A month: against the 6 whole months before it that the statements cover.
        val month = p.month
        if (month != null) {
            val prior = (1..6).map { month - it }.filter { m -> MoneyRules.monthOf(first) < m || (MoneyRules.monthOf(first) == m && CivilDate.fromEpochDay(first).day == 1) }
            if (prior.isNotEmpty()) {
                val avg = prior.sumOf { m -> -d.lines.filter { MoneyRules.monthOf(it.day) == m && chosen(it) }.sumOf { it.pence } } / prior.size
                if (avg > 0) more += if (prior.size == 1) "In ${CivilDate.MONTH_LONG[prior[0] % 12]} it was ${fmt(pounds(avg))}."
                    else "Your ${prior.size}-month average before that is ${fmt(pounds(avg))} a month."
            }
        }
        // Everything: where most of it went.
        if (cat == null && shops.isEmpty() && spent > 0) {
            val top = these.groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } }.filter { it.value > 0 }
                .entries.sortedByDescending { it.value }.take(3)
            if (top.isNotEmpty()) more += "Most went on " + names(top.map { "${it.key.label.lowercase()} ${fmt(pounds(it.value))}" }) + "."
        }
        return (listOf(head) + more + reach).joinToString(" ")
    }

    /** The window "what's coming out …" asks about: this week (to Sunday), next week, today, tomorrow, before payday, else 7 days. */
    fun outWindow(q: String, today: Long, payday: Long?): Period {
        val s = q.lowercase()
        return when {
            Regex("""\b(before|until|till|to) pay ?day\b""").containsMatchIn(s) && payday != null ->
                Period(today, payday - 1, "before payday on ${CivilDate.shortLabel(payday)}")
            Regex("""\btomorrow\b""").containsMatchIn(s) -> Period(today + 1, today + 1, "tomorrow")
            Regex("""\btoday\b""").containsMatchIn(s) -> Period(today, today, "today")
            Regex("""\bnext week\b""").containsMatchIn(s) -> {
                val from = monday(today) + 7
                Period(from, from + 6, "next week (${CivilDate.shortLabel(from)} – ${CivilDate.shortLabel(from + 6)})")
            }
            Regex("""\bthis month\b""").containsMatchIn(s) -> {
                val c = CivilDate.fromEpochDay(today)
                val end = CivilDate.toEpochDay(c.year, c.month, CivilDate.lengthOfMonth(c.year, c.month))
                Period(today, end, "by the end of the month")
            }
            Regex("""\bthis week\b""").containsMatchIn(s) && CivilDate.isoDayOfWeek(today) < 7 -> {
                val end = monday(today) + 6
                Period(today, end, "this week (to ${CivilDate.shortLabel(end)})")
            }
            else -> Period(today, today + 6, "in the next 7 days")
        }
    }

    /** "About £64 coming out this week (to Sun 25 Oct), 3 payments: Netflix £10.99 on Fri 23 Oct, …" */
    fun answerOut(q: String, d: MoneyAskData): String {
        if (d.lines.isEmpty() && d.upcoming.isEmpty()) return EMPTY
        val w = outWindow(q, d.today, d.payday?.paydayDay)
        val due = d.upcoming.filter { it.dueDay in w.from..w.to && it.pence > 0 }.sortedWith(compareBy({ it.dueDay }, { it.title }))
        if (due.isEmpty()) return "Nothing regular is due to come out ${w.said}, as far as your statements, Lists → Renewals and your cards show."
        val total = due.sumOf { it.pence }
        val shown = due.take(SHOWN).map { "${it.title} ${fmt(it.pence)} on ${CivilDate.shortLabel(it.dueDay)}" }
        val rest = due.size - shown.size
        return "About ${fmt(pounds(total))} coming out ${w.said}, ${due.size} payment${if (due.size == 1) "" else "s"}: " +
            names(shown + (if (rest > 0) listOf("$rest more") else emptyList())) + "." +
            " Regular payments' days are as they've been, so allow a day or two."
    }

    fun answerPayday(d: MoneyAskData): String {
        val p = d.payday ?: return EMPTY
        if (p.payLine == null) return p.notReady ?: MoneyPayday.NOT_READY
        return listOfNotNull(p.payLine, p.fromLine).joinToString(" · ") + "."
    }

    fun answerSafe(d: MoneyAskData): String {
        val p = d.payday ?: return EMPTY
        if (p.payLine == null) return p.notReady ?: MoneyPayday.NOT_READY
        val safe = p.safe ?: return "${p.balanceLine ?: "Type your pay account's balance under Until payday in Money"}, then ask again."
        return "$safe. ${p.payLine}." + NOT_ADVICE
    }

    /** The day [q] names ("on Saturday", "tomorrow", "today"), on or after [today]; null when none. */
    fun dayIn(q: String, today: Long): Long? {
        val s = q.lowercase()
        if (Regex("""\btomorrow\b""").containsMatchIn(s)) return today + 1
        if (Regex("""\b(today|tonight)\b""").containsMatchIn(s)) return today
        val i = DAYS.indexOfFirst { Regex("""\b${it}s?\b""").containsMatchIn(s) }
        if (i < 0) return null
        val dow = CivilDate.isoDayOfWeek(today) - 1
        var ahead = (i - dow).mod(7).toLong()
        if (Regex("""\bnext ${DAYS[i]}\b""").containsMatchIn(s) && ahead == 0L) ahead = 7
        return today + ahead
    }

    /** "Yes, by your numbers: …", "Only just: …" or "Not without going short: …", from safe to spend. */
    fun answerAfford(q: String, d: MoneyAskData): String {
        val amount = amount(q) ?: return HOW
        val p = d.payday ?: return EMPTY
        val payday = p.paydayDay
        if (p.payLine == null || payday == null) return (p.notReady ?: MoneyPayday.NOT_READY)
        val left = p.leftPence
            ?: return "I'd need ${p.accountName ?: "your pay account"}'s balance to say: type it under Until payday in Money, then ask again."
        val day = dayIn(q, d.today)
        val on = day?.let { if (it == d.today) " today" else " on ${CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(it) - 1]}" } ?: ""
        val a = fmt(amount)
        if (day != null && day >= payday) {
            return "${CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(day) - 1]} is after payday on ${CivilDate.shortLabel(payday)}, so $a would come out of the next pay. " +
                "Until then you have about ${fmt(pounds(left.coerceAtLeast(0)))} after the bills." + NOT_ADVICE
        }
        val after = left - amount
        val daysLeft = (payday - (day ?: d.today)).coerceAtLeast(1)
        val before = "after the bills before payday you'd have about ${fmt(pounds(left.coerceAtLeast(0)))}"
        val text = when {
            left < 0 -> "Not without going short: what's still to go out before payday is already about ${fmt(pounds(-left))} more than ${p.accountName ?: "your pay account"} holds."
            after < 0 -> "Not without going short: $before, so $a$on would be about ${fmt(pounds(-after))} more than that, before payday on ${CivilDate.shortLabel(payday)}."
            after < MoneyPayday.LOW_PENCE -> "Only just: $before; $a$on leaves about ${fmt(pounds(after))}, about ${fmt(pounds(after / daysLeft))} a day until payday on ${CivilDate.shortLabel(payday)}."
            else -> "Yes, by your numbers: $before; $a$on leaves about ${fmt(pounds(after))}, about ${fmt(pounds(after / daysLeft))} a day until payday on ${CivilDate.shortLabel(payday)}."
        }
        return text + NOT_ADVICE
    }

    /** What's due to go out: [regular] payments' next days (from [today]) and [bills], a bill that is a regular payment counted once. */
    fun upcoming(regular: List<MoneyTips.Regular>, bills: List<MoneyBill>, today: Long): List<MoneyBill> {
        val reg = regular.filter { it.nextDay >= today }
        return reg.map { MoneyBill(it.label, it.nextDay, it.monthly) } +
            bills.filter { b -> b.dueDay >= today && b.pence > 0 && reg.none { r -> MoneyPayday.sameAs(b, r) } }
    }

    /** Whether an unlock from Ask still holds at [nowMs]. */
    fun unlocked(open: Boolean, untilMs: Long, nowMs: Long): Boolean = open || nowMs < untilMs
}
