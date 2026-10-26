package os.meka.core.domain

/**
 * Money bugs (A2), Meka 2026-10-24: an account's name showed its whole account number (a bank's IBAN came through in the
 * name). Navilife never shows a whole account number, sort code or IBAN: [shown] keeps the words and turns every
 * number-like part into "••" plus its last 4 digits ("Revolut GB33 REVO 0099 6912 3491 80" → "Revolut ••9180"). Used
 * wherever an account's name is read (Money's accounts and cards, payment rows, goals, payday, Ask), and by the server
 * when it names a bank's account, so names already stored are masked on read too. Pure; no I/O.
 */
object MoneyAccountNames {
    /** An IBAN (two letters, two check digits, then 10–30 letters or digits, spaces allowed between groups). */
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){10,30}\\b")

    /**
     * An account or card number or sort code: six or more digits, spaces, dashes or *s together (at least six digits),
     * or an already-masked "****1234" / "xxxx1234" / "••1234".
     */
    private val NUMBER = Regex("(?<![A-Za-z0-9])[0-9*xX•\\- ]*\\d[0-9*xX•\\- ]*(?![A-Za-z0-9])")

    /** [raw] as it may be shown: the words kept, each number-like part as "••" + its last 4 digits. */
    fun shown(raw: String): String {
        var s = IBAN.replace(raw.trim()) { m -> if (m.value.count { it.isDigit() } >= 8) mask(m.value) else m.value }
        s = NUMBER.replace(s) { m ->
            val v = m.value
            val t = v.trim()
            val digits = t.count { it.isDigit() }
            val hidden = t.any { it == '*' || it == '•' || it == 'x' || it == 'X' }
            when {
                digits >= 6 -> pad(v, mask(t))
                hidden && t.first().let { it == '*' || it == '•' || it == 'x' || it == 'X' } -> pad(v, mask(t))
                else -> v
            }
        }
        s = s.replace(Regex("(••\\d{1,4})(?:\\s*[·\\-–]?\\s*••\\d{1,4})+")) { m -> m.value.substringAfterLast("••").let { "••$it" } }
        s = s.replace(Regex("\\s+"), " ").trim().trimEnd('-', '·', ',').trim()
        return s.ifEmpty { raw.trim() }
    }

    private fun mask(v: String): String = "••" + v.filter { it.isDigit() }.takeLast(4)

    /** Keeps the spaces either side of a masked part, so words stay apart. */
    private fun pad(v: String, m: String): String =
        (if (v.firstOrNull() == ' ') " " else "") + m + (if (v.lastOrNull() == ' ') " " else "")

    /** True when [text] would show something [shown] hides (a test helper and a check for anything about to be shown). */
    fun revealsNumber(text: String): Boolean = shown(text) != text.trim().replace(Regex("\\s+"), " ")
}
