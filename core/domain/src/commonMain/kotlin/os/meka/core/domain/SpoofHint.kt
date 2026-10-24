package os.meka.core.domain

/**
 * The spoofed-number hint (build plan "Fix first", Meka 2026-10-22 19:49: a scam caller pretending to be the police
 * rang through from a number that looked like a real switchboard), slice 4. Non-AI and pure.
 *
 * A call from a number in the same exchange as an official switchboard (the same digits but the last
 * [SpoofHintRules.TAIL]) gets "Could be spoofed" on the in-call warning: scammers fake those numbers, and the real
 * ones never need card details or money moved. The lines come from two places: a few national numbers scammers are
 * known to copy ([SpoofHintRules.NATIONAL]: public switchboards, nothing about him), and the ones he types in About
 * you → Official numbers ("police 01632 960000, bank 0345 …"), kept in his data (rule 13: his local force or bank is
 * never in the code).
 */
data class OfficialLine(
    /** The national form, digits only: "02072301212". */
    val digits: String,
    /** Whose it is, as said in the warning: "the Met Police's switchboard", "your bank's number". */
    val whose: String,
    val claim: OfficialClaim?,
    /** Whether a number in the same exchange counts too; false for a number whose exchange other bodies share. */
    val exchange: Boolean = true,
)

/** A call's number looked like [line]'s; [exact] when it is that very number. */
data class SpoofLookalike(val line: OfficialLine, val exact: Boolean) {
    /** "Could be spoofed · looks like HMRC's number". */
    val title: String get() = "${SpoofHintRules.COULD_BE} · looks like ${line.whose}"
    /** The warning's extra line: what it means and what to do. */
    val hint: String
        get() = "${SpoofHintRules.COULD_BE}: this ${if (exact) "is" else "looks like"} ${line.whose}, and scammers fake it. " +
            "Hang up and call back on a number you know."
    /** Obi's line in his earbuds. */
    val spoken: String
        get() = "Careful: this number looks like ${line.whose}, and it could be faked. Never give card details or move money."
}

object SpoofHintRules {
    const val COULD_BE = "Could be spoofed"

    /** The digits that may differ and still be the same exchange (a switchboard's own block of numbers). */
    const val TAIL = 4

    /** At most this many numbers in About you's Official numbers. */
    const val MAX_LINES = 8

    /**
     * National numbers scammers are known to copy. Public switchboards only, checked against the bodies' own pages:
     * New Scotland Yard's switchboard, Action Fraud (that number only: other public bodies share its 0300 123 exchange),
     * HMRC's helplines (0300 200 xxxx; one line covers the exchange).
     */
    val NATIONAL: List<OfficialLine> = listOf(
        OfficialLine("02072301212", "the Met Police's switchboard", OfficialClaim.POLICE),
        OfficialLine("03001232040", "Action Fraud's number", OfficialClaim.POLICE, exchange = false),
        OfficialLine("03002003300", "HMRC's number", OfficialClaim.GOVERNMENT),
    )

    /** A UK number in its national form, digits only ("+44 20 7230 1212" → "02072301212"); null when it isn't one. */
    fun national(number: String?): String? {
        val raw = number?.trim().orEmpty()
        if (raw.isEmpty() || raw.any { !it.isDigit() && it !in "+-() ." }) return null
        if (raw.startsWith("+") && !raw.startsWith("+44")) return null
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("0044")) d = "0" + d.drop(4)
        else if (raw.startsWith("+44") || (d.startsWith("44") && d.length == 12)) d = "0" + d.drop(2)
        if (d.startsWith("00")) return null
        if (!d.startsWith("0") || d.length !in 10..11) return null
        return d
    }

    /** A national number as people write it: "020 7230 1212", "0306 999 0000", "01632 960000". */
    fun shown(d: String): String = when {
        d.length == 11 && d.startsWith("02") -> "${d.take(3)} ${d.substring(3, 7)} ${d.drop(7)}"
        d.length == 11 && (d.startsWith("03") || d.startsWith("08") || d.startsWith("09")) -> "${d.take(4)} ${d.substring(4, 7)} ${d.drop(7)}"
        d.length == 11 -> "${d.take(5)} ${d.drop(5)}"
        else -> d
    }

    /** Whose a typed line is, from the word before it: "police", "bank", "NHS"/"surgery", "school", "council"/"HMRC". */
    fun whoseTyped(claim: OfficialClaim?): String = when (claim) {
        OfficialClaim.POLICE -> "your police force's number"
        OfficialClaim.BANK -> "your bank's number"
        OfficialClaim.NHS -> "your surgery's number"
        OfficialClaim.SCHOOL -> "the school's number"
        OfficialClaim.GOVERNMENT -> "a public body's number"
        null -> "a number you listed as official"
    }

    /**
     * About you's Official numbers as typed: up to [MAX_LINES] entries split by commas or semicolons, each an optional
     * word or two ("police", "my bank") then a UK number. Returns the cleaned text ("police 01632 960000, bank 0306 999
     * 0000"), or null when any entry isn't one (no number, a number that isn't a UK one, too many).
     */
    fun cleanTyped(t: String): String? {
        val parts = t.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_LINES) return null
        val out = parts.map { p ->
            val start = p.indexOfFirst { it.isDigit() || it == '+' }
            if (start < 0) return null
            val label = p.substring(0, start).trim().trimEnd(':', '-', '·').trim()
            val num = p.substring(start).trim()
            if (label.length > 30 || label.any { !it.isLetter() && it != ' ' && it != '\'' }) return null
            val shown = shown(national(num) ?: return null)
            if (label.isEmpty()) shown else "${label.lowercase()} $shown"
        }
        return out.joinToString(", ")
    }

    /** The lines in what [cleanTyped] kept; unreadable entries are skipped. */
    fun typedLines(kept: String?): List<OfficialLine> {
        if (kept.isNullOrBlank()) return emptyList()
        return kept.split(',', ';').mapNotNull { p ->
            val s = p.trim()
            val start = s.indexOfFirst { it.isDigit() || it == '+' }
            if (start < 0) return@mapNotNull null
            val digits = national(s.substring(start)) ?: return@mapNotNull null
            val claim = OfficialCallerRules.claim(s.substring(0, start))
            OfficialLine(digits, whoseTyped(claim), claim)
        }.take(MAX_LINES)
    }

    /**
     * The official line [number] looks like, or null: the same length and the same digits but the last [TAIL], an
     * exact match first, his own lines before the national ones. A withheld or non-UK number is never one.
     */
    fun lookalike(number: String?, typed: List<OfficialLine> = emptyList()): SpoofLookalike? {
        val d = national(number) ?: return null
        val lines = typed + NATIONAL
        lines.firstOrNull { it.digits == d }?.let { return SpoofLookalike(it, exact = true) }
        if (d.length <= TAIL + 3) return null
        val stem = d.dropLast(TAIL)
        return lines.firstOrNull { it.exchange && it.digits.length == d.length && it.digits.dropLast(TAIL) == stem }?.let { SpoofLookalike(it, exact = false) }
    }
}
