package os.meka.core.domain

/**
 * Spam-call lookup (build plan "Take first: spam-call lookup is on", Meka stored the Brave key 2026-10-17; Needs Meka
 * #32). When a caller the Fold doesn't know rings, Navilife's server searches the web for the number (Brave Search API)
 * and reads what the report sites (who-called-style pages) say about it into a [NumberReputation]: a reported scam, a
 * nuisance or sales caller, a business that lists the number, or nothing known. Non-AI and pure, so the server, both
 * apps and the tests read the same verdict. A failed lookup (no key, the month's credit used, a timeout) is "no
 * lookup", never spam.
 *
 * What it does with the verdict ([NumberLookupRules.shouldBlock], [NumberLookupRules.line]): a number reported as a
 * scam by several reports is blocked (before it rings when the answer comes in time, otherwise straight after the
 * call), with Undo, exactly as the other automatic blocks and never for contacts, anyone he called in 90 days, the
 * household or a caller claiming an official body; a nuisance or sales caller goes to the call assistant when its
 * switch is on; every notification about the caller says "reported 23× as a scam". Numbers are never logged (rule 13).
 */
enum class NumberVerdict { SCAM, NUISANCE, LEGIT, UNKNOWN }

/**
 * What the web says about one number: [reports] the most reports any report site counted (0 when none says), [sure]
 * whether the evidence is strong enough to block on, [why] the kind of scam in a few words ("a police-impersonation
 * scam") or, for [NumberVerdict.LEGIT], the site that lists it.
 */
data class NumberReputation(
    val verdict: NumberVerdict,
    val reports: Int = 0,
    val sure: Boolean = false,
    val why: String? = null,
) {
    override fun toString() = "NumberReputation(${verdict.name}, reports=$reports, sure=$sure)"

    companion object { val UNKNOWN = NumberReputation(NumberVerdict.UNKNOWN) }
}

/** One web search result: its address, title and the snippet under it. */
data class WebHit(val url: String, val title: String, val text: String)

object NumberLookupRules {
    /** How long a verdict is kept per number: a definite one 30 days, "nothing known" a week, a failed lookup a day. */
    const val DEFINITE_DAYS = 30
    const val UNKNOWN_DAYS = 7
    const val FAILED_DAYS = 1

    /** The Fold waits this long for the server before a call rings (Android gives the screening service about 5 s). */
    const val BEFORE_RING_WAIT_MS = 1_500L
    /** After the call there's no hurry. */
    const val AFTER_CALL_WAIT_MS = 6_000L

    /**
     * Sites whose pages are people's reports about phone numbers. A hit counts only when it is one of these and it
     * names the number itself ([mentions]), so a generic "how to spot scam calls" page never counts.
     */
    val REPORT_SITES = listOf(
        "who-called", "whocalled", "tellows", "shouldianswer", "should-i-answer", "whocallsme", "who-calls-me",
        "unknownphone", "800notes", "callercenter", "ukphoneinfo", "whoseno", "numberbase", "scamcall", "telguarder",
        "spamcalls", "whatsthisnumber", "who-is-calling", "whoisthisnumber", "sync.me", "hiya.com", "truecaller.com",
        "callerid", "phonenumberinfo", "ukcallerid", "thisnumber", "whocallsuk",
    )

    private val SCAM_WORDS = Regex(
        "\\b(scam|scams|scammer|scammers|fraud|fraudulent|fraudster|phishing|spoof|spoofed|spoofing|impersonat\\w*|" +
            "pretend\\w*|fake|dangerous|arrest|warrant|gift cards?|crypto|bitcoin)\\b",
    )
    private val NUISANCE_WORDS = Regex(
        "\\b(nuisance|sales|salesman|marketing|telemarket\\w*|cold call\\w*|survey|ppi|accident claim\\w*|silent call\\w*|" +
            "robocall\\w*|automated|recorded message|spam|unwanted|annoying|harass\\w*|negative|keeps calling|calls? every day)\\b",
    )
    private val LEGIT_WORDS = Regex("\\b(contact us|customer services?|helpline|head office|opening hours|call us|switchboard|enquiries)\\b")
    private val REPORT_COUNT = Regex("\\b(\\d{1,5})\\s+(?:user\\s+)?(?:reports?|comments?|ratings?|reviews?|complaints?)\\b")

    /** Which scam it is, by the claim most reports make. */
    private val CLAIMS = listOf(
        Regex("\\b(police|officer|detective|constabulary|met police|action fraud)\\b") to "a police-impersonation scam",
        Regex("\\b(hmrc|tax refund|tax rebate|tax office)\\b") to "an HMRC scam",
        Regex("\\b(bank|card|account (?:has been |was )?(?:suspended|compromised|frozen)|fraud team|fraud department)\\b") to "a bank scam",
        Regex("\\b(amazon|prime membership)\\b") to "an Amazon scam",
        Regex("\\b(courier|parcel|delivery|royal mail|dpd|evri|hermes)\\b") to "a delivery scam",
        Regex("\\b(investment|crypto|bitcoin|trading)\\b") to "an investment scam",
        Regex("\\b(broadband|bt openreach|internet will be|phone line will be|microsoft|computer|virus)\\b") to "a tech-support scam",
    )

    /**
     * The national digits a UK number is listed under ("+44 7700 900123" → "07700900123"), or the digits as they came
     * for another country's number; null when it isn't a number worth looking up (withheld, a short code, an emergency
     * or public-service number).
     */
    fun national(number: String?): String? {
        val t = number?.trim().orEmpty()
        if (BlockedCallerRules.keyOf(t) == null || OfficialCallerRules.isEmergency(t)) return null
        var d = t.filter { it.isDigit() }
        if (t.startsWith("+44") || d.startsWith("0044")) d = "0" + d.removePrefix("00").removePrefix("44")
        else if (t.startsWith("+") || d.startsWith("00")) d = d.removePrefix("00")
        return d.takeIf { it.length in 10..15 }
    }

    /** Whether the Fold should ask about [number] at all. */
    fun lookUpable(number: String?): Boolean = national(number) != null

    /** What is searched: the number in quotes, as report sites list it. */
    fun query(number: String): String? = national(number)?.let { "\"$it\"" }

    private fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")

    fun isReportSite(url: String): Boolean = host(url).let { h -> REPORT_SITES.any { h.contains(it) } }

    /** Whether the hit is about this number: its digits appear (however spaced) in the address, title or snippet. */
    fun mentions(hit: WebHit, national: String): Boolean {
        val squeezed = squeeze(hit.url + " " + hit.title + " " + hit.text)
        val bare = national.removePrefix("0")
        return squeezed.contains(national) || (national.startsWith("0") && squeezed.contains("44$bare"))
    }

    /** "07700 900-123" → "07700900123": spaces, dashes, brackets and dots between digits dropped. */
    private fun squeeze(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in " -(). " && out.isNotEmpty() && out.last().isDigit()) {
                var j = i
                while (j < s.length && s[j] in " -(). ") j++
                if (j < s.length && s[j].isDigit()) { i = j; continue }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** What the search results say about [number]. A result that doesn't name the number is ignored. */
    fun classify(number: String, hits: List<WebHit>): NumberReputation {
        val nat = national(number) ?: return NumberReputation.UNKNOWN
        val about = hits.filter { mentions(it, nat) }
        val reportHits = about.filter { isReportSite(it.url) }
        val texts = reportHits.map { (it.title + " " + it.text).lowercase() }
        val scam = texts.count { SCAM_WORDS.containsMatchIn(it) }
        val nuisance = texts.count { NUISANCE_WORDS.containsMatchIn(it) }
        val reports = texts.maxOfOrNull { t -> REPORT_COUNT.findAll(t).maxOfOrNull { it.groupValues[1].toInt() } ?: 0 } ?: 0
        val legit = about.filter { !isReportSite(it.url) && LEGIT_WORDS.containsMatchIn((it.title + " " + it.text).lowercase()) }
        return when {
            scam >= 2 || (scam >= 1 && reports >= 3) -> {
                // A business that lists the number itself and only a stray mention of "scam": not sure enough to block.
                val sure = (scam >= 3 || reports >= 10 || (scam >= 2 && reports >= 3)) && !(legit.size >= 2 && scam < 3)
                NumberReputation(NumberVerdict.SCAM, reports, sure, claimOf(texts.filter { SCAM_WORDS.containsMatchIn(it) }))
            }
            scam + nuisance >= 2 || (nuisance >= 1 && reports >= 3) ->
                NumberReputation(NumberVerdict.NUISANCE, reports, sure = nuisance + scam >= 3 || reports >= 5)
            legit.isNotEmpty() -> NumberReputation(NumberVerdict.LEGIT, 0, sure = false, why = host(legit.first().url).take(60))
            else -> NumberReputation(NumberVerdict.UNKNOWN, reports)
        }
    }

    private fun claimOf(texts: List<String>): String? =
        CLAIMS.map { (re, label) -> label to texts.count { re.containsMatchIn(it) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first

    /** Whether a caller the phone doesn't know is blocked on this reputation: a scam, sure. */
    fun shouldBlock(r: NumberReputation?): Boolean = r != null && r.verdict == NumberVerdict.SCAM && r.sure

    /**
     * Whether, with the call assistant on, the caller goes to it instead of ringing: a nuisance or sales caller, or a
     * scam that isn't blocked (not sure enough, or spam protection off).
     */
    fun toAssistant(r: NumberReputation?): Boolean =
        r != null && (r.verdict == NumberVerdict.NUISANCE || r.verdict == NumberVerdict.SCAM)

    /** The line beside the caller on every notification: "reported 23× as a police-impersonation scam"; null when nothing's known. */
    fun line(r: NumberReputation?): String? {
        r ?: return null
        val times = if (r.reports > 0) "${r.reports}×" else "online"
        return when (r.verdict) {
            NumberVerdict.SCAM -> "reported $times as ${r.why ?: "a scam"}"
            NumberVerdict.NUISANCE -> "reported $times as nuisance or sales calls"
            NumberVerdict.LEGIT, NumberVerdict.UNKNOWN -> null
        }
    }

    /** How long a looked-up answer is kept, by what it was (null: the lookup failed). */
    fun keepDays(r: NumberReputation?): Int = when (r?.verdict) {
        null -> FAILED_DAYS
        NumberVerdict.UNKNOWN -> UNKNOWN_DAYS
        else -> DEFINITE_DAYS
    }

    /** Settings' line (Work mode → Spam protection). */
    const val HINT = "Unknown numbers are looked up on the web (Brave Search): one reported as a scam by several people " +
        "is blocked, a nuisance or sales caller goes to your call assistant, and the caller's notification says what " +
        "people reported. Only the number is searched, at most once a day; contacts and anyone you called lately never are."
}
