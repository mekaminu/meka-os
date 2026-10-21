package os.meka.core.domain

/*
 * Call assistant, completeness (build plan; non-AI, pure): **Ask "any calls today?"**. A typed question about his calls
 * ("any calls today?", "who rang yesterday?", "did the dentist call?", "has anyone called this week?") is answered on
 * the device from Activity → Calls ([CallLogView]: the call assistant's messages, calls missed at work and, on the
 * Fold, the calls the phone screened), never sent to the AI: a caller's words stay on his devices. Talk's "who called?"
 * still plays the voice messages ([VoicePlaylistRules]); a longer spoken question lands here too.
 */

/** The days a call question covers. */
enum class CallAskScope { TODAY, YESTERDAY, WEEK }

object CallAskRules {
    /** Calls said at most; the rest are counted. */
    const val MAX_SAID = 5
    const val BUTTON = "Open Calls"

    private const val VERB = """(?:call|calls|called|ring|rang|rung|phone|phoned|rings)"""
    private val PLURAL = Regex("""\b(calls|voicemails)\b""")
    private val ASKING = Regex("""\b(any|how many|were there|what|which|who|did|have|has|had|today|yesterday|this week|missed|recent|latest|last)\b""")
    private val WHO = Regex("""\bwho(?:'s| has| have)?\s+(?:\w+\s+)?(called|rang|rung|phoned)\b""")
    private val ANYONE = Regex("""\b(anyone|anybody|someone|somebody)\b(?:\s+\w+){0,2}\s+$VERB\b""")
    /** "did the dentist call", "has Sam rung", "have the school phoned". */
    private val DID = Regex("""\b(did|has|have)\s+((?:[\w'’-]+\s+){1,4}?)$VERB\b""")
    private val FROM = Regex("""\b(?:calls?|anything|messages?)\s+from\s+([\w'’-]+(?:\s+[\w'’-]+)?)""")
    /** Questions about how Navilife works, asks to stop calls, or to make one: not about his call log. */
    private val NOT = Regex(
        """\b(how do i|how does|how can i|set ?up|turn (?:on|off)|switch (?:on|off)|remind|stop|block|filter|prevent|cost|price|tariff)\b""",
    )
    private val STARTS = Regex("""^(?:please\s+)?(?:call|ring|phone|add|text|message)\b""")
    private val NOT_NAMES = setOf(
        "i", "you", "we", "they", "he", "she", "it", "the", "a", "an", "my", "our", "anyone", "anybody", "someone",
        "somebody", "ever", "back", "today", "yesterday", "already", "yet", "just", "this", "week", "morning", "afternoon",
        "evening", "any", "of", "at", "work", "me", "us", "call", "assistant", "number", "unknown", "there", "been", "missed",
        "spam", "new", "and", "or", "last", "few", "days",
    )

    private fun norm(q: String) = q.lowercase().replace('’', '\'').replace(Regex("""[?!.,;:]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Whether [question] asks about the calls he's had (not how the call assistant works, nor to make a call). */
    fun isAbout(question: String): Boolean {
        val q = norm(question)
        if (q.isEmpty() || NOT.containsMatchIn(q) || STARTS.containsMatchIn(q)) return false
        if (PLURAL.containsMatchIn(q) && ASKING.containsMatchIn(q)) return true
        if (WHO.containsMatchIn(q) || ANYONE.containsMatchIn(q) || Regex("""\bcalls? from\b""").containsMatchIn(q)) return true
        val m = DID.find(q) ?: return false
        // "did I call mum?" / "have you called" ask about calls he made or Navilife made: not in the log.
        val subject = m.groupValues[2].trim().split(' ').first()
        return subject != "i" && subject != "you" && subject != "we"
    }

    fun scope(question: String): CallAskScope {
        val q = norm(question)
        return when {
            "yesterday" in q -> CallAskScope.YESTERDAY
            Regex("""\b(today|this morning|this afternoon|this evening|tonight|so far)\b""").containsMatchIn(q) -> CallAskScope.TODAY
            else -> CallAskScope.WEEK
        }
    }

    /** The caller the question names ("did the dentist call?" → "dentist"), as words; empty when it names nobody. */
    fun named(question: String): List<String> {
        val q = norm(question)
        val raw = FROM.find(q)?.groupValues?.get(1) ?: DID.find(q)?.groupValues?.get(2) ?: return emptyList()
        return raw.split(' ').map { it.trim('\'', '-') }.filter { it.length >= 2 && it !in NOT_NAMES }
    }

    private fun scopeLabel(s: CallAskScope) = when (s) {
        CallAskScope.TODAY -> "today"
        CallAskScope.YESTERDAY -> "yesterday"
        CallAskScope.WEEK -> "in the last 7 days"
    }

    private fun matches(row: CallLogRow, words: List<String>): Boolean {
        val who = " " + row.who.lowercase().replace(Regex("""[^\p{L}\p{N}']+"""), " ") + " "
        val digits = row.who.filter { it.isDigit() }
        return words.all { w ->
            " $w " in who || (w.all { it.isDigit() } && w.length >= 4 && w in digits) ||
                (w.endsWith("s") && " ${w.dropLast(1)} " in who)
        }
    }

    /** The caller as said: the name or number, without "· not in your contacts". */
    fun whoOf(row: CallLogRow): String = row.who.substringBefore(" · ").trim()

    /** "Sam Taylor at 14:29: Dentist confirming Thursday 3pm, said it's urgent." */
    fun said(row: CallLogRow, withDay: String?): String {
        val what = row.line.replace(" · ", ", ").replaceFirstChar { it.lowercase() }
            .replace(", " + CallNoticeRules.SAID_URGENT, ", " + CallNoticeRules.SAID_URGENT.replaceFirstChar { it.lowercase() })
        val day = withDay?.let { "$it, " } ?: ""
        return "$day${whoOf(row)} at ${row.time}: $what" + (if (row.blocked) " (blocked)" else "") + "."
    }

    /**
     * The answer from [view], or null when [question] isn't about his calls. [scope] picks the days; a caller named in
     * it narrows the calls to theirs. Urgent ones first, then newest first; at most [MAX_SAID], the rest counted.
     */
    fun answer(question: String, view: CallLogView): String? {
        if (!isAbout(question)) return null
        val s = scope(question)
        val days = view.days.filter { d ->
            when (s) {
                CallAskScope.TODAY -> d.label == "Today"
                CallAskScope.YESTERDAY -> d.label == "Yesterday"
                CallAskScope.WEEK -> true
            }
        }
        val who = named(question)
        val rows = days.flatMap { d -> d.rows.map { d.label to it } }.filter { who.isEmpty() || matches(it.second, who) }
        val label = scopeLabel(s)
        if (rows.isEmpty()) {
            if (who.isNotEmpty()) {
                val name = who.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                return "No calls from $name $label in your call log. Calls your call assistant took, calls missed at work " +
                    "and calls Navilife screened are kept for 7 days."
            }
            return "No calls $label in your call log."
        }
        val urgent = rows.count { it.second.urgent }
        val blocked = rows.count { it.second.blocked }
        val head = (if (who.isNotEmpty()) "${plural(rows.size, "call")} from ${whoOf(rows.first().second)} $label"
        else "${plural(rows.size, "call")} $label").replaceFirstChar { it.uppercase() } +
            (if (urgent > 0) ", $urgent urgent" else "") + (if (blocked > 0) ", $blocked blocked" else "") + "."
        val ordered = rows.sortedBy { !it.second.urgent }
        val showDay = s == CallAskScope.WEEK && rows.map { it.first }.distinct().size > 1
        val lines = ordered.take(MAX_SAID).map { (day, r) -> said(r, if (showDay) day else null) }
        val more = if (rows.size > MAX_SAID) " And ${rows.size - MAX_SAID} more in Activity → Calls." else ""
        return (listOf(head) + lines).joinToString(" ") + more
    }

    val link: HelpLink get() = HelpLink("activity", HelpPlace.ACTIVITY, BUTTON)
}
