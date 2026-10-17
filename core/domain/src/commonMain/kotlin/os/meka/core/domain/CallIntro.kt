package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * The call assistant talks to them (build plan "spam calls still ring", item (c); Meka 2026-10-13 20:56: "pick it up,
 * talk to them, and if it's fake drop and block"). Before taking a message the assistant asks "who's calling, and
 * what's it about?"; what the caller says ([judge], with the number's web reputation when the server has one) decides
 * whether the call goes on to a message or is ended politely as a likely scam ("Meka will contact the organisation
 * directly"). Non-AI and pure, so it answers inside the phone service's wait and the same words show on every device.
 *
 * Untrusted content (ADR-006): the caller's words only ever end a call or flag the number; they never put anyone
 * through, never add anything and never raise an autonomy level. A judged scam puts the number on Suspected spam
 * (Block or Not spam stays Meka's choice); the assistant never shares any of his details or agrees to anything.
 */
enum class IntroVerdict {
    /** Nothing said (or only a word or digits): the call goes on to a message as before. */
    NONE,
    /** Said who they are and nothing looks wrong. */
    OK,
    /** Claims to be the police, a bank, HMRC… without asking for anything: a message is taken, and he's told to check. */
    CLAIMS_OFFICIAL,
    /** A claim plus a scam's ask (payment, details, threats), a strong ask alone, or a number reported as a scam. */
    SCAM,
}

/** What [CallIntroRules.judge] made of the caller's words: [claim] "HMRC", [ask] "asked for payment". */
data class IntroJudgement(val verdict: IntroVerdict, val claim: String? = null, val ask: String? = null, val reported: Boolean = false)

object CallIntroRules {
    /** Asked straight after the greeting; the answer decides the rest of the call. */
    const val INTRO_QUESTION = "First, may I ask who's calling, and what it's about?"
    /** Then, for everyone not judged a scam. */
    const val RECORD_AFTER_INTRO = "Thank you. Please leave your message after the tone, then press the hash key or just hang up."
    /** A judged scam: polite, says nothing about Meka, agrees to nothing. */
    const val SCAM_CLOSE = "Thank you. Meka will contact the organisation directly if he needs to. Goodbye."
    /** How long the assistant waits for them to start answering. */
    const val INTRO_SECONDS = 5
    /** The longest answer kept (it is shown, display only). */
    const val MAX_INTRO = 300
    /** Hints for the phone service's recogniser. */
    const val HINTS = "police, bank, fraud team, HMRC, courier, dentist, school, doctor, surgery, delivery"

    /** [HeldMessageFields.ENDED] values. */
    const val ENDED_SCAM = "scam"
    const val ENDED_NO_MESSAGE = "noMessage"

    /** The after-work summary's line for an ended scam call, before its summary. */
    const val DISPLAY_SCAM = "Likely scam · your call assistant ended the call"
    const val HAPPENED_SCAM = "ended as a likely scam"
    const val HAPPENED_NO_MESSAGE = "said who they were · no message"
    const val SCAM_CHECK = "Calls like this can be spoofed · call the organisation on a number you trust (101 for the police, the one on your card for a bank)"

    /** The caller's words cleaned for keeping: one line, at most [MAX_INTRO]; null when nothing was said. */
    fun clean(speech: String?): String? {
        val t = speech?.replace(Regex("\\s+"), " ")?.replace(Regex("[<>]"), "")?.trim() ?: return null
        if (t.isEmpty()) return null
        return if (t.length > MAX_INTRO) t.take(MAX_INTRO - 1).trimEnd() + "…" else t
    }

    /** Whether the caller said something a person would (two or more words with letters, not just a key or a number). */
    fun real(intro: String?): Boolean {
        val t = intro ?: return false
        if (CallNoticeRules.digitsOrOneWord(t)) return false
        return Regex("[A-Za-z]{2,}").findAll(t).count() >= 2
    }

    private class Pattern(val label: String, val regex: Regex)

    private fun p(label: String, vararg words: String) =
        Pattern(label, Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE))

    /** Who they claim to be (first match wins; the label is how it's shown). */
    private val claims = listOf(
        p("the police", "police", "metropolitan police", "detective", "officer", "constable", "national crime agency", "action fraud"),
        p("HMRC", "hmrc", "h m r c", "tax office", "inland revenue", "revenue and customs", "tax department"),
        p("a bank's fraud team", "fraud team", "fraud department", "fraud prevention", "security team"),
        p("a bank", "bank", "barclays", "lloyds", "natwest", "hsbc", "santander", "halifax", "nationwide", "monzo", "starling"),
        p("Amazon", "amazon", "amazon prime"),
        p("BT", "bt", "openreach", "broadband provider", "internet provider"),
        p("Microsoft", "microsoft", "apple support", "tech support", "technical support"),
        p("a court", "court", "bailiff", "bailiffs", "solicitor", "magistrates"),
        p("the DWP", "dwp", "department for work and pensions", "national insurance"),
        p("a courier", "courier", "royal mail", "dpd", "evri", "hermes", "dhl", "parcelforce", "customs"),
    )

    /** The scam's ask. */
    private val asks = listOf(
        p("asked for payment", "pay", "payment", "transfer", "send money", "owe", "owed", "outstanding", "a fine", "unpaid", "refund"),
        p("asked for your details", "verify", "confirm your details", "confirm your identity", "security details", "card details",
            "card number", "account number", "sort code", "pin", "password", "one time code", "one-time code", "passcode",
            "date of birth", "remote access", "download"),
        p("threatened action", "arrest", "arrested", "warrant", "legal action", "prosecution", "prosecuted", "suspended", "court case", "lawsuit"),
        p("said your account was at risk", "suspicious", "compromised", "hacked", "unusual activity", "unauthorised", "unauthorized", "fraudulent"),
    )

    /** Asks only a scam makes, claim or not. */
    private val strong = listOf(
        p("asked for gift cards or crypto", "gift cards?", "itunes cards?", "google play cards?", "bitcoin", "crypto", "cryptocurrency"),
        p("threatened arrest", "warrant for your arrest", "you will be arrested", "you'll be arrested"),
        p("asked you to move money to a safe account", "safe account"),
        p("asked you to press 1", "press 1 to speak", "press one to speak"),
    )

    /**
     * The caller's answer ([intro], as heard) with the number's web reputation ([reputation], null when the server
     * has none): a claim plus an ask is a scam; a strong ask alone is; a number reported as a scam is when it also
     * claims or asks something (or the reports are sure). A claim alone is CLAIMS_OFFICIAL (a message is taken).
     */
    fun judge(intro: String?, reputation: NumberReputation?): IntroJudgement {
        val reportedScam = reputation?.verdict == NumberVerdict.SCAM
        val sureScam = NumberLookupRules.shouldBlock(reputation)
        val text = intro?.takeIf { real(it) }
        if (text == null) {
            return if (sureScam) IntroJudgement(IntroVerdict.SCAM, reported = true) else IntroJudgement(IntroVerdict.NONE)
        }
        val claim = claims.firstOrNull { it.regex.containsMatchIn(text) }?.label
        val ask = asks.firstOrNull { it.regex.containsMatchIn(text) }?.label
        val strongAsk = strong.firstOrNull { it.regex.containsMatchIn(text) }?.label
        return when {
            claim != null && ask != null -> IntroJudgement(IntroVerdict.SCAM, claim, ask, reportedScam)
            strongAsk != null -> IntroJudgement(IntroVerdict.SCAM, claim, strongAsk, reportedScam)
            reportedScam && (claim != null || ask != null || sureScam) -> IntroJudgement(IntroVerdict.SCAM, claim, ask, true)
            claim != null && claim in OFFICIAL -> IntroJudgement(IntroVerdict.CLAIMS_OFFICIAL, claim)
            else -> IntroJudgement(IntroVerdict.OK, claim)
        }
    }

    private val OFFICIAL = setOf("the police", "HMRC", "a bank's fraud team", "a bank", "a court", "the DWP")

    /**
     * The one line shown for an ended call (the message's summary): "Said they're from HMRC and asked for payment",
     * "Asked for your details", "Reported online as a scam".
     */
    fun summary(j: IntroJudgement): String {
        val from = j.claim?.let { "Said they're from $it" }
        val line = when {
            from != null && j.ask != null -> "$from and ${j.ask}"
            from != null -> from
            j.ask != null -> j.ask.replaceFirstChar { it.uppercaseChar() }
            j.reported -> "Reported online as a scam"
            else -> "Sounded like a scam"
        }
        return CallNoticeRules.cleanSummary(line) ?: line
    }

    /** Why the number is on Suspected spam: "Said they're from HMRC and asked for payment · the assistant ended the call". */
    fun why(j: IntroJudgement): String = summary(j)

    /** The Activity entry for an ended call: "Ended a likely scam call from 01632 960001" · what they said it was. */
    fun activity(number: String?, j: IntroJudgement, atMs: Long): Map<String, FieldValue> = linkedMapOf(
        ActivityFields.AT to FieldValue.Int64(atMs),
        ActivityFields.KIND to FieldValue.Text(ActivityKind.CALL.name),
        ActivityFields.SUMMARY to FieldValue.Text(
            ("Ended a likely scam call from " + (number?.let(BlockedCallerRules::display) ?: CallAssistantRules.WITHHELD_NAME)).take(ActivityRules.MAX_LINE),
        ),
        ActivityFields.DETAIL to FieldValue.Text(summary(j).take(ActivityRules.MAX_LINE)),
        ActivityFields.WHY to FieldValue.Text(
            "Call assistant · asked who was calling; it said Meka will contact the organisation directly and hung up. " +
                "The number is on Suspected spam: you choose Block or Not spam",
        ),
        ActivityFields.SOURCE to FieldValue.Text("calls"),
    )

    /** The Activity entry id for one ended call. */
    fun activityId(heldId: String): String = "v" + ActivityRules.fnv64("introscam:$heldId")
}
