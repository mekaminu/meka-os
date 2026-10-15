package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Promises, receipts and renewals from email, slice 2 (build plan; non-AI, pure): **promises → Waiting for, on his
 * tap**. When someone writes to a connected account that they will send or do something — "I'll send you the signed
 * contract by Friday", "We will get back to you within 5 working days", "You will receive your refund by 30 Oct" — the
 * server reads who, what and by when from the subject and the kept preview and publishes one *find* (`mail_find`, kind
 * PROMISE). Both apps show it in Needs you with the renewals ("Promises from your mail", or "Renewals and promises…"):
 * who, "Waiting for: Signed contract · by Fri 16 Oct", the account and the subject, with **Add** (a Waiting for item
 * from them, chased the day after the day they gave, else in 3 days; Undo takes it off) and **Not needed** (Undo).
 * Nothing reaches Waiting for without that tap.
 *
 * Every word of an email is someone else's (ADR-006): a find only proposes a reminder he can clear; nothing is sent,
 * paid or opened because of it. Read from the subject and the provider's preview only, never the body. Not from
 * newsletters, promos, junk or no-reply senders (a refund promise excepted), nor from his own accounts; "won't",
 * "can't" or "unable" in the same sentence, codes and passwords, and "we'll send you an email when…" propose nothing.
 */

/** A promise read from an email: who made it, what Waiting for will say, and the day they gave (or null). */
data class MailPromise(
    val who: String?,
    /** Waiting for's title: "Signed contract", "A reply", "Refund". */
    val what: String,
    val byDay: Long?,
)

object MailPromiseRules {
    const val KIND_PROMISE = "PROMISE"
    /** Emails this many days back are read each check (a promise older than this has had its chance). */
    const val WINDOW_DAYS = 14
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A day further than this after the email is a misread. */
    const val MAX_DAYS = 60
    /** A promise stays offered this many days after its day (or default chase day) has passed. */
    const val STALE_DAYS = 7
    const val TITLE = "Promises from your mail"
    const val NO_NAME = "Someone in your mail"

    private const val MAX_WHAT = 60
    private const val MODAL = """(?:i|we)(?:'ll|’ll|\s+will|\s+shall|\s+am\s+going\s+to|\s+are\s+going\s+to)"""

    /** "I'll send…", "we will get back to you…": the verb they commit to (adverbs between allowed). */
    private val PROMISE = Regex(
        """\b$MODAL\s+(?:also\s+|then\s+|definitely\s+|certainly\s+|aim\s+to\s+|try\s+to\s+)?""" +
            """(send|forward|share|post|email\s+you|e-?mail\s+over|get\s+back\s+to\s+you|come\s+back\s+to\s+you|reply|respond|""" +
            """let\s+you\s+know|confirm|call\s+you|ring\s+you|phone\s+you|update\s+you|follow\s+up|pay|refund|deliver|""" +
            """sort|look\s+into|chase|arrange|book|fix|return)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "You will receive your refund…", "you should hear back…". */
    private val RECEIVE = Regex(
        """\byou(?:'ll|’ll|\s+will|\s+should)\s+(?:receive|get|hear\s+back|hear\s+from\s+(?:us|me))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** In the same sentence: not a promise after all. */
    private val NEGATED = Regex("""\b(won'?t|will\s+not|unable|can'?t|cannot|couldn'?t|not\s+be\s+able|if\s+you)\b""", RegexOption.IGNORE_CASE)
    /** Anywhere: account mail, not a promise. */
    private val NOISE = Regex(
        """\b(verification|one-time|passcode|password|sign-?in\s+code|login\s+code|security\s+code|unsubscribe|newsletter)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "We'll send you an email when it ships": automatic notices, not someone's word. */
    private val NOTICE_OBJECT = Regex(
        """^(?:you\s+)?(?:an?\s+)?(?:e-?mail|text|sms|notification|reminder|code|link|updates?|offers?|news|confirmation\s+e-?mail)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REFUND = Regex("""\brefund""", RegexOption.IGNORE_CASE)
    /** Where the object stops and the "when" begins. */
    private val TIME_START = Regex(
        """\s+(?:by|on|before|until|no\s+later\s+than|within|in\s+the\s+next|tomorrow|today|tonight|this|next|later|asap|as\s+soon|shortly|soon|when|once|if|after|first\s+thing|end\s+of|in\s+\d|over\s+the)\b|[,;:(]""",
        RegexOption.IGNORE_CASE,
    )
    private val WITHIN = Regex(
        """\b(?:within|in(?:\s+the\s+next)?)\s+(\d{1,2}|one|two|three|four|five|six|seven|ten|fourteen)\s+(working\s+|business\s+)?days?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val WITHIN_WEEK = Regex("""\bwithin\s+(?:a|one|the)\s+week\b|\bin\s+a\s+week\b""", RegexOption.IGNORE_CASE)
    private val SAME_DAY = Regex("""\b(?:today|tonight|this\s+(?:afternoon|evening)|later\s+today|end\s+of\s+(?:the\s+)?day|eod|cob)\b""", RegexOption.IGNORE_CASE)
    private val END_OF_WEEK = Regex("""\b(?:(?:by\s+)?(?:the\s+)?end\s+of\s+(?:the|this)\s+week|this\s+week)\b""", RegexOption.IGNORE_CASE)
    private val NEXT_WEEK = Regex("""\bnext\s+week\b""", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("""\b(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2}|\d{4}))?(?!\d)""")
    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10, "fourteen" to 14)

    /**
     * The promise an email received on [receivedDay] makes to him, or null when its words don't say one for sure (the
     * subject first, then the preview). [automated]: a no-reply style sender, whose only promise taken is a refund.
     */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?, receivedDay: Long, automated: Boolean = false): MailPromise? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { NOISE.containsMatchIn(it) }) return null
        for (t in texts) {
            for (m in PROMISE.findAll(t) + RECEIVE.findAll(t)) {
                val sentence = sentenceFrom(t, m.range.first)
                if (NEGATED.containsMatchIn(sentence)) continue
                val verb = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.lowercase()?.replace(Regex("""\s+"""), " ")
                val rest = sentence.substring((m.range.last + 1 - m.range.first).coerceAtMost(sentence.length)).trim()
                if (verb != null && verb in SEND_VERBS && NOTICE_OBJECT.containsMatchIn(rest)) continue
                val what = what(verb, rest, subject) ?: continue
                if (automated && !REFUND.containsMatchIn(sentence)) continue
                return MailPromise(
                    who = MailReceiptRules.merchant(fromName, fromAddress),
                    what = what,
                    byDay = byDay(rest, receivedDay),
                )
            }
        }
        return null
    }

    private val SEND_VERBS = setOf("send", "forward", "share", "post", "email you", "e-mail over", "email over", "deliver", "return")

    /** From [start] to the sentence's end (at most 160 characters). */
    private fun sentenceFrom(t: String, start: Int): String {
        val tail = t.substring(start)
        val end = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
            .let { i -> if (i < 0) tail.length else i }
            // "5.30pm" and "12.12.2026" don't end a sentence.
            .let { i -> if (i in 1 until tail.length - 1 && tail[i] == '.' && tail[i - 1].isDigit() && tail[i + 1].isDigit()) nextEnd(tail, i + 1) else i }
        return tail.take(minOf(end, 160))
    }

    private fun nextEnd(tail: String, from: Int): Int {
        var i = from
        while (i < tail.length) {
            val c = tail[i]
            if ((c == '.' && !(i + 1 < tail.length && tail[i + 1].isDigit() && tail[i - 1].isDigit())) || c == '!' || c == '?' || c == '\n') return i
            i++
        }
        return tail.length
    }

    /** Waiting for's title from the verb and what follows it; null when there's nothing to wait for. */
    private fun what(verb: String?, rest: String, subject: String?): String? {
        var obj = TIME_START.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
        obj = obj.trim().removePrefix("it to you").trim()
        obj = obj.replace(Regex("""^(?:you|to\s+you|over|across|through|back)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:over|across|through|to\s+you|back|for\s+you)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:the|a|an|your)\s+""", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd('.', ',', '!', ' ')
        val generic = obj.isEmpty() || obj.lowercase() in setOf("it", "this", "that", "them", "these", "those", "you", "one")
        val title = when (verb) {
            null -> if (generic) null else obj // "you will receive your refund"
            "get back to you", "come back to you", "reply", "respond" -> "A reply"
            "let you know" -> if (generic) "An answer" else "Let you know $obj"
            "call you", "ring you", "phone you" -> "A call"
            "update you" -> "An update"
            "pay" -> if (generic) "Payment" else "Payment: $obj"
            "refund" -> "Refund"
            "follow up" -> "A follow-up"
            else -> if (verb in SEND_VERBS) obj.takeUnless { generic } ?: subjectTopic(subject) ?: "What they're sending"
            else if (generic) verb.replaceFirstChar { it.uppercase() } + " it" else "${verb.replaceFirstChar { it.uppercase() }} $obj"
        } ?: return null
        val t = title.trim().replaceFirstChar { it.uppercase() }
        return (if (t.length <= MAX_WHAT) t else t.take(MAX_WHAT - 1).trimEnd() + "…").takeIf { it.length >= 2 }
    }

    /** "Re: Plans for Saturday" → "Plans for Saturday"; null when nothing is left. */
    private fun subjectTopic(subject: String?): String? =
        subject?.replace(Regex("""^\s*(?:(?:re|fw|fwd)\s*:\s*)+""", RegexOption.IGNORE_CASE), "")?.trim()?.takeIf { it.length >= 2 }

    /** The day [rest] names after the promise, on or after [receivedDay] and within [MAX_DAYS]; null when none. */
    internal fun byDay(rest: String, receivedDay: Long): Long? {
        val day = WITHIN.find(rest)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1].lowercase()] ?: return@let null
            if (m.groupValues[2].isNotBlank()) addWorkingDays(receivedDay, n) else receivedDay + n
        }
            ?: WITHIN_WEEK.find(rest)?.let { receivedDay + 7 }
            ?: numeric(rest, receivedDay)
            ?: MailWeekRules.dayOf(rest, receivedDay)
            ?: SAME_DAY.find(rest)?.let { receivedDay }
            ?: END_OF_WEEK.find(rest)?.let { receivedDay + (5 - CivilDate.isoDayOfWeek(receivedDay)).mod(7) }
            ?: NEXT_WEEK.find(rest)?.let { receivedDay + (8 - CivilDate.isoDayOfWeek(receivedDay)) + 4 }
        return day?.takeIf { it >= receivedDay && it - receivedDay <= MAX_DAYS }
    }

    private fun numeric(text: String, receivedDay: Long): Long? {
        val m = NUMERIC.find(text) ?: return null
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val rd = CivilDate.fromEpochDay(receivedDay)
        val years = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { listOf(if (it < 100) 2000 + it else it) } ?: listOf(rd.year, rd.year + 1)
        for (y in years) {
            if (mo !in 1..12 || d < 1 || d > CivilDate.lengthOfMonth(y, mo)) return null
            val day = CivilDate.toEpochDay(y, mo, d)
            if (day >= receivedDay) return day
        }
        return null
    }

    /** [n] working days after [from] (Saturdays and Sundays skipped). */
    internal fun addWorkingDays(from: Long, n: Int): Long {
        var day = from
        var left = n
        while (left > 0) {
            day++
            if (CivilDate.isoDayOfWeek(day) <= 5) left--
        }
        return day
    }

    /** One find per account and email (a second promise in the same thread is its own find). */
    fun findId(accountId: String, messageId: String): String = "mf" + ActivityRules.fnv64("mailpromise:$accountId:$messageId")

    /** The Waiting for item Add writes: one per find, so Add on both apps lands on the same item. */
    fun waitingId(findId: String): String = "mw" + ActivityRules.fnv64("mailfind:$findId")

    /** The find's fields, written once by the server; with no day given, its day is the usual chase from the email. */
    fun serverFields(p: MailPromise, account: String, subject: String?, receivedDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_PROMISE.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to (p.who?.fv() ?: FieldValue.Null),
        MailFindFields.DAY to (p.byDay ?: (receivedDay + ListRules.DEFAULT_CHASE_DAYS)).fv(),
        MailFindFields.WHAT to p.what.fv(),
        MailFindFields.BY_STATED to (p.byDay != null).fv(),
        MailFindFields.RECEIVED_DAY to receivedDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    fun title(f: MailFind): String = f.merchant ?: NO_NAME

    /** "Waiting for: Signed contract · by Fri 16 Oct" or "Waiting for: A reply · no day given · chase Mon 19 Oct". */
    fun line(f: MailFind, today: Long): String {
        val what = "Waiting for: ${f.what ?: "what they promised"}"
        return if (f.byStated) "$what · by ${RenewalRules.dayWord(f.renewDay, today)}"
        else "$what · no day given · chase ${RenewalRules.dayWord(maxOf(f.renewDay, today), today)}"
    }

    fun row(f: MailFind, today: Long): MailFindRow {
        val title = title(f)
        val line = line(f, today)
        val owner = if (f.merchant != null) "$title's" else "this"
        return MailFindRow(
            id = f.id, title = title, line = line, from = MailRenewalRules.fromLine(f),
            business = f.account == MailLabel.BUSINESS.label,
            spoken = "$title. $line. From your ${f.account} mail.",
            addSpoken = "Add $owner promise to Waiting for",
            notNeededSpoken = "$owner promise isn't needed on Waiting for".replaceFirstChar { it.uppercase() },
        )
    }

    /**
     * Whether [f] is an open promise to show: not answered, its day no more than [STALE_DAYS] gone, and not already on
     * Waiting for (Add's own item, or one of his from the same person with the same title).
     */
    fun showable(f: MailFind, waiting: List<WaitingItem>, today: Long): Boolean {
        if (f.kind != KIND_PROMISE || f.state != MailFindState.OPEN || f.renewDay + STALE_DAYS < today) return false
        val wid = waitingId(f.id)
        return waiting.none { w ->
            w.id == wid || (f.merchant != null && w.who?.equals(f.merchant, ignoreCase = true) == true && w.title.equals(f.what, ignoreCase = true))
        }
    }

    /** Days from [today] to chase: the day after the day they gave, else the find's default day; at least tomorrow. */
    fun chaseInDays(f: MailFind, today: Long): Int = ((if (f.byStated) f.renewDay + 1 else f.renewDay) - today).coerceAtLeast(1L).toInt()

    /** Waiting for's note: where it came from. */
    fun note(f: MailFind): String =
        "From an email in your ${f.account} mail on ${CivilDate.shortLabel(f.receivedDay)}" +
            (f.subject?.let { " (“$it”)" } ?: "") + ", added on your tap."

    /** The bar's line after Add: "On Waiting for: Signed contract from Sam Taylor · chase Sat 17 Oct". */
    fun addedLine(f: MailFind, today: Long): String =
        "On Waiting for: ${f.what ?: "their promise"}" + (f.merchant?.let { " from $it" } ?: "") +
            " · chase ${RenewalRules.dayWord(today + chaseInDays(f, today), today)}"
}
