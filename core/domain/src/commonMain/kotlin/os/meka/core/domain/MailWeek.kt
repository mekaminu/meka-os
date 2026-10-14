package os.meka.core.domain

/*
 * Inbox autopilot, slice 9b (build plan; non-AI, pure, no regexes): **the week ahead reads the sorted mail**.
 *
 * - **Mail line** under the week's summary: "Mail · 3 emails need you, from Alex Green, Sam Taylor and 1 more · 2 to
 *   chase this week" ([line]); the same in the spoken week and Sunday's heads-up says only the counts.
 * - **On its day**: an email that still needs him (or is snoozed) and names a day of the week shown in its subject or
 *   preview ("Trip on Friday", "Parents' evening Thu 15 Oct", "15th October", "tomorrow") shows on that day as an
 *   Email line: "Oakfield School · “Trip on Friday”" ([dayOf]). An explicit date wins over a weekday name; a weekday
 *   name is the first such day on or after the day the email came; "tomorrow"/"tonight" count from that day too.
 * - **Chases**: a Waiting for item made from an email whose chase day falls in the week shows on that day ("Chase Sam
 *   Taylor · Invoice for October"); one already due shows on the first day shown.
 *
 * Every word of an email is someone else's (ADR-006). The week ahead never reaches the AI, and these lines only show
 * what the From your mail card already shows; nothing here acts on anything.
 */

/** A mail line on a day of the week ahead ([MailWeekRules.lines]). */
data class MailWeekLine(val id: String, val day: Long, val text: String, val spoken: String)

object MailWeekRules {
    /** Mail lines a day may carry (after everything else, so the diary keeps its place under the day's cap). */
    const val PER_DAY = 2
    /** A sender's name on a line, at most. */
    private const val NAME = 40
    /** A subject on a line, at most. */
    private const val SUBJECT = 80

    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val MONTHS = listOf(
        listOf("january", "jan"), listOf("february", "feb"), listOf("march", "mar"), listOf("april", "apr"), listOf("may"),
        listOf("june", "jun"), listOf("july", "jul"), listOf("august", "aug"), listOf("september", "sept", "sep"),
        listOf("october", "oct"), listOf("november", "nov"), listOf("december", "dec"),
    )

    private fun month(word: String): Int? = MONTHS.indexOfFirst { word in it }.takeIf { it >= 0 }?.plus(1)

    /** "15", "15th", "1st", "2nd", "3rd" → the day of the month; null otherwise. */
    private fun dayNumber(word: String): Int? {
        val digits = word.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 2) return null
        val rest = word.drop(digits.length)
        if (rest.isNotEmpty() && rest !in setOf("st", "nd", "rd", "th")) return null
        return digits.toInt().takeIf { it in 1..31 }
    }

    /**
     * The local day [text] names, read from an email that came on [receivedDay]: an explicit date ("15 Oct", "Thursday
     * 15th October", "October 15") in the year that keeps it closest after the email, else a weekday name (the first
     * such day on or after [receivedDay]), else "tomorrow"/"tonight". Null when it names no day.
     */
    fun dayOf(text: String, receivedDay: Long): Long? {
        val w = words(text.lowercase())
        val rd = CivilDate.fromEpochDay(receivedDay)
        for (i in w.indices) {
            val m = month(w[i]) ?: continue
            // "May" is a month only beside a day number; so are the short forms (a "mar" or "dec" in a word list).
            val d = w.getOrNull(i - 1)?.let(::dayNumber) ?: w.getOrNull(i + 1)?.let(::dayNumber) ?: continue
            for (year in listOf(rd.year, rd.year + 1)) {
                if (d > CivilDate.lengthOfMonth(year, m)) continue
                val day = CivilDate.toEpochDay(year, m, d)
                // A date more than a month before the email is next year's (a December email about "4 Jan").
                if (day >= receivedDay - 31) return day
            }
        }
        for (word in w) {
            val iso = WEEKDAYS.indexOf(word).takeIf { it >= 0 }?.plus(1) ?: continue
            val from = CivilDate.isoDayOfWeek(receivedDay)
            return receivedDay + (iso - from).mod(7)
        }
        // "tomorrow's", "tonight's" read as one word (the apostrophe dropped).
        if ("tomorrow" in w || "tomorrows" in w) return receivedDay + 1
        if ("tonight" in w || "tonights" in w) return receivedDay
        return null
    }

    private fun short(s: String, max: Int): String {
        val t = s.trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** Emails still open for him: needing him now or snoozed, in the last 14 days, newest first. */
    private fun open(items: List<MailItem>, nowMs: Long): List<MailItem> =
        items.filter { i ->
            i.goneAtMs == null && !MailNeedsRules.done(i) && nowMs - i.receivedAtMs <= MailNeedsRules.WINDOW_MS && i.receivedAtMs <= nowMs + 60_000L
        }.sortedWith(compareByDescending<MailItem> { it.receivedAtMs }.thenBy { it.id })

    /**
     * The mail lines for [days] (the week shown, today first): open emails naming one of the days, then chases of
     * Waiting for items made from emails (one already due on the first day). At most [PER_DAY] a day, chases first.
     */
    fun lines(items: List<MailItem>, waiting: List<WaitingItem>, days: List<Long>, nowMs: Long, cal: LocalCalendar): List<MailWeekLine> {
        if (days.isEmpty()) return emptyList()
        val shown = days.toSet()
        val first = days.first()
        val out = mutableListOf<MailWeekLine>()
        val open = waiting.associateBy { it.id }
        items.mapNotNull { i -> i.waitingId?.let(open::get)?.let { i to it } }
            .filter { (_, w) -> w.chaseDay != null }
            .sortedWith(compareBy<Pair<MailItem, WaitingItem>> { it.second.chaseDay }.thenBy { it.first.id })
            .forEach { (i, w) ->
                val day = maxOf(w.chaseDay!!, first)
                if (day !in shown) return@forEach
                val who = short(w.who?.takeIf { it.isNotBlank() } ?: i.from, NAME)
                val title = short(w.title, SUBJECT)
                val late = if (w.chaseDay < first) " · was due ${CivilDate.shortLabel(w.chaseDay)}" else ""
                out += MailWeekLine("mc-${i.id}", day, "Chase $who · $title$late", "Chase $who about $title$late")
            }
        for (i in open(items, nowMs)) {
            // One made into a Waiting for item shows as its chase instead.
            if (i.waitingId != null) continue
            val received = cal.epochDayOf(i.receivedAtMs)
            val day = dayOf(i.subject, received) ?: i.preview?.let { dayOf(it, received) } ?: continue
            if (day !in shown) continue
            val from = short(i.from, NAME)
            val subject = short(i.subject, SUBJECT)
            out += MailWeekLine("mw-${i.id}", day, "$from · “$subject”", "Email from $from: $subject")
        }
        return out.groupBy { it.day }.values.flatMap { it.take(PER_DAY) }
    }

    /** Chases due in [days] (the week shown), already-due ones included. */
    fun chaseCount(items: List<MailItem>, waiting: List<WaitingItem>, days: List<Long>): Int {
        if (days.isEmpty()) return 0
        val ids = items.mapNotNull { it.waitingId }.toSet()
        return waiting.count { it.id in ids && it.chaseDay != null && it.chaseDay <= days.last() }
    }

    /**
     * The week's mail line: "Mail · 3 emails need you, from Alex Green, Sam Taylor and 1 more · 2 to chase this week";
     * null when nothing needs him and nothing is to chase. Senders as the brief says them ([MailAskRules.plainName]).
     */
    fun line(items: List<MailItem>, waiting: List<WaitingItem>, days: List<Long>, nowMs: Long, week: String): String? {
        val brief = MailAskRules.brief(items, nowMs)
        val chases = chaseCount(items, waiting, days)
        if (brief == null && chases == 0) return null
        val needs = brief?.let { MailAskRules.briefSentence(it).removeSuffix(".").replace("One email needs you", "1 email needs you") }
        val chase = chases.takeIf { it > 0 }?.let { "$it to chase ${week.lowercase()}" }
        return (listOf("Mail") + listOfNotNull(needs, chase)).joinToString(" · ")
    }

    /** The week's mail line read out: "In your email, 3 emails need you, from Alex Green and Sam Taylor; 2 to chase this week." */
    fun spokenLine(line: String): String = "In your email, " + line.removePrefix("Mail · ").replace(" · ", "; ") + "."

    /** The heads-up's words: "3 emails need you · 2 to chase" (counts only, no names); null with neither. */
    fun noticeBit(items: List<MailItem>, waiting: List<WaitingItem>, days: List<Long>, nowMs: Long): String? {
        val n = MailNeedsRules.showingSorted(items, nowMs).size
        val chases = chaseCount(items, waiting, days)
        return listOfNotNull(
            n.takeIf { it > 0 }?.let { MailNeedsRules.headline(it) },
            chases.takeIf { it > 0 }?.let { "$it to chase" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Letters and digits runs, lower case (an apostrophe inside a word is dropped). By hand, no regex. */
    private fun words(s: String): List<String> {
        val out = mutableListOf<String>()
        val w = StringBuilder()
        for (c in s) {
            when {
                c.isLetterOrDigit() -> w.append(c)
                (c == '\'' || c == '’') && w.isNotEmpty() -> {}
                else -> if (w.isNotEmpty()) { out += w.toString(); w.clear() }
            }
        }
        if (w.isNotEmpty()) out += w.toString()
        return out
    }
}
