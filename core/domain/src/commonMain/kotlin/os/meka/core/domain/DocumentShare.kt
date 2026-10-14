package os.meka.core.domain

/*
 * Screenshots and photos that do something, slice 3 (build plan V1; non-AI, pure, on the device): **a picture of a
 * document becomes a Vault row**. A photo or screenshot of a passport's photo page, a driving licence, a GHIC, a
 * railcard, an insurance certificate or a warranty (or the same words shared as text) is read on the device as
 * before; when it says what the document is and when it runs out, the capture sheet (and the Mac's bar) offers
 * "Add to the Vault · Alex passport · expires Thu 20 May 2027". Nothing is written until he taps it, and then only the
 * Vault's own row: the document's name (the holder's first name, for a passport, licence, ID or health card) and the
 * day it runs out. Never a document number, never the picture.
 *
 * The expiry is read where the document says it: after "Date of expiry", "Expiry date", "Valid until", a licence's
 * "4b." and the like (the latest date on that line and the next, since a passport prints its issue and expiry dates
 * side by side), or from a passport's machine-readable lines, whose expiry carries its own check digit. Read by hand,
 * no regular expressions (Kotlin/Native's engine has read numbers differently before).
 */

/** What the capture sheet offers for a document. */
data class DocumentShareView(
    /** "Add to the Vault · Alex passport · expires Thu 20 May 2027", "Update the Vault · …", "Already in the Vault · …". */
    val line: String,
    val entry: DocumentEntry,
    /** The Vault already has it with this date: the line is shown, not offered. */
    val already: Boolean,
    /** The Vault has a document with this title and another date (a renewed passport): adding updates it. */
    val updates: Boolean,
)

object DocumentShareRules {
    const val ACTION = "Add to the Vault"
    const val ICON = "🗂"

    /** Labels after which a document prints the day it runs out (lower case). */
    private val ANCHORS = listOf(
        "date of expiry", "expiry date", "date d'expiration", "expiration date", "expiry of insurance", "expiry",
        "expires on", "expires", "valid until", "valid till", "valid to", "valid thru", "valid through", "end date",
        "cover ends", "policy ends", "renewal date", "warranty ends",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    /** French months a passport prints beside the English ones ("20 MAY /MAI 27"). */
    private val OTHER_MONTHS = setOf("janv", "fév", "fev", "mars", "avr", "mai", "juin", "juil", "août", "aout", "sept", "déc")

    // ---- What it is ----

    private fun has(s: String, vararg words: String): Boolean = words.any { s.contains(it) }

    /**
     * The kind of document [text] is, from words only a document carries (not a letter that mentions insurance in
     * passing); null for anything else. A parking confirmation is never a document.
     */
    fun kindOf(text: String): DocKind? {
        val s = text.lowercase()
        if (ParkingConfirmationRules.looksLikeOne(text)) return null
        // Whole words for the short ones ("ehic" is inside "vehicle").
        val words = String(CharArray(s.length) { if (s[it].isLetter()) s[it] else ' ' }).split(' ').toSet()
        return when {
            has(s, "passport", "passeport") || mrzLines(text).any { it.startsWith("P<") } -> DocKind.PASSPORT
            has(s, "global health insurance card", "european health insurance card") || words.any { it == "ghic" || it == "ehic" } -> DocKind.HEALTH_CARD
            has(s, "driving licence", "driving license", "driving licences") -> DocKind.DRIVING_LICENCE
            has(s, "railcard") -> DocKind.RAILCARD
            has(s, "identity card", "national id card", "residence card", "residence permit") -> DocKind.ID_CARD
            has(s, "certificate of insurance", "certificate of motor insurance", "insurance certificate", "policy schedule") ||
                (s.contains("insurance") && s.contains("policy")) -> DocKind.INSURANCE
            has(s, "warranty", "guarantee") -> DocKind.WARRANTY
            else -> null
        }
    }

    // ---- When it runs out ----

    /** The year a two-digit year means: this century unless that's over 20 years ahead (a birth year, "80"). */
    private fun fullYear(yy: Int, thisYear: Int): Int = if (2000 + yy <= thisYear + 20) 2000 + yy else 1900 + yy

    private fun digits(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' }

    private fun monthOf(w: String): Int? {
        val l = w.lowercase().trim('.', ',')
        if (l.length < 3) return null
        val i = MONTHS.indexOf(l.take(3))
        return if (i >= 0 && l.all { it.isLetter() }) i + 1 else null
    }

    private fun day(y: Int, m: Int, d: Int): Long? =
        if (m in 1..12 && y in 1900..2199 && d in 1..CivilDate.lengthOfMonth(y, m)) CivilDate.toEpochDay(y, m, d) else null

    /** "20.05.2027", "20/05/27", "2027-05-20", "05/2027" (its last day): one token. */
    private fun numericDate(t: String, thisYear: Int): Long? {
        for (sep in listOf('.', '/', '-')) {
            val p = t.split(sep)
            if (p.size == 3 && p.all(::digits)) {
                return if (p[0].length == 4) day(p[0].toInt(), p[1].toInt(), p[2].toInt())
                else when (p[2].length) {
                    4 -> day(p[2].toInt(), p[1].toInt(), p[0].toInt())
                    2 -> day(fullYear(p[2].toInt(), thisYear), p[1].toInt(), p[0].toInt())
                    else -> null
                }
            }
            if (p.size == 2 && sep == '/' && p.all(::digits)) {
                val m = p[0].toInt()
                val y = when (p[1].length) {
                    4 -> p[1].toInt()
                    2 -> p[1].toInt().takeIf { it > 12 }?.let { fullYear(it, thisYear) }
                    else -> null
                } ?: return null
                if (m in 1..12) return CivilDate.toEpochDay(y, m, CivilDate.lengthOfMonth(y, m))
            }
        }
        return null
    }

    /**
     * Every date in [snippet], in order: the numeric forms, "20 May 2027", "20 MAY /MAI 27", "May 20, 2027" and a
     * month alone, "May 2027" (its last day).
     */
    fun dates(snippet: String, thisYear: Int): List<Long> {
        val ts = snippet.replace('\n', ' ').split(' ', '\t').map { it.trim(',', ';', ':', '(', ')') }.filter { it.isNotEmpty() }
        val out = mutableListOf<Long>()
        var i = 0
        fun year(t: String?): Int? = t?.trim('.', ',')?.takeIf(::digits)?.let {
            when (it.length) { 4 -> it.toInt(); 2 -> fullYear(it.toInt(), thisYear); else -> null }
        }
        while (i < ts.size) {
            val t = ts[i]
            val n = numericDate(t.trimEnd('.'), thisYear)
            if (n != null) { out += n; i++; continue }
            val d = t.trimEnd('.', 's', 't', 'n', 'd', 'r', 'h').takeIf { digits(it) && it.length <= 2 }?.toInt()
            val m = ts.getOrNull(i + 1)?.let(::monthOf)
            if (d != null && m != null) {
                var j = i + 2
                // "/MAI" or "MAI": a passport's second language.
                while (j < ts.size && (ts[j].startsWith("/") || ts[j].lowercase().trim('/', '.') in OTHER_MONTHS) && j <= i + 3) j++
                val y = year(ts.getOrNull(j))
                val got = if (y != null) day(y, m, d) else null
                if (got != null) { out += got; i = j + 1; continue }
            }
            val m2 = monthOf(t)
            if (m2 != null) {
                val next = ts.getOrNull(i + 1)?.trim(',')
                val y4 = next?.takeIf { digits(it) && it.length == 4 }?.toInt()
                if (y4 != null) { out += CivilDate.toEpochDay(y4, m2, CivilDate.lengthOfMonth(y4, m2)); i += 2; continue }
                val d2 = next?.trimEnd('s', 't', 'n', 'd', 'r', 'h')?.takeIf { digits(it) && it.length <= 2 }?.toInt()
                val y2 = ts.getOrNull(i + 2)?.trim(',')?.takeIf { digits(it) && it.length == 4 }?.toInt()
                val got = if (d2 != null && y2 != null) day(y2, m2, d2) else null
                if (got != null) { out += got; i += 3; continue }
            }
            i++
        }
        return out
    }

    /** The rest of the line after position [at] in [text], plus the next line. */
    private fun window(text: String, at: Int): String {
        val end1 = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val end2 = if (end1 >= text.length) text.length else text.indexOf('\n', end1 + 1).let { if (it < 0) text.length else it }
        return text.substring(at, end2)
    }

    /** Where a licence's field "4b" (its expiry) starts its value, or -1. */
    private fun licenceExpiry(lower: String): Int {
        var from = 0
        while (true) {
            val i = lower.indexOf("4b", from)
            if (i < 0) return -1
            val before = lower.getOrNull(i - 1)
            val after = lower.getOrNull(i + 2)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || after == '.' || after == ' ' || after == ':')) return i + 2
            from = i + 2
        }
    }

    /** The lines that look like a passport's or ID card's machine-readable zone (spaces dropped). */
    fun mrzLines(text: String): List<String> = text.lines().map { l -> l.filterNot { it == ' ' }.uppercase() }
        .filter { l -> l.length >= 30 && l.count { it == '<' } >= 2 && l.all { it in 'A'..'Z' || it in '0'..'9' || it == '<' } }

    private fun mrzValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        else -> 0
    }

    /** ICAO 9303's check digit (weights 7, 3, 1). */
    fun checkDigit(s: String): Int = s.withIndex().sumOf { (i, c) -> mrzValue(c) * intArrayOf(7, 3, 1)[i % 3] } % 10

    /** A passport's expiry from its second machine-readable line (YYMMDD at 21–26, checked by 27); null unless it checks. */
    fun mrzExpiry(text: String, thisYear: Int): Long? {
        for (l in mrzLines(text)) {
            if (l.length != 44 || l.startsWith("P<")) continue
            val e = l.substring(21, 27)
            val check = l[27]
            if (!digits(e) || !check.isDigit() || checkDigit(e) != check - '0') continue
            if (!digits(l.substring(13, 19)) || l[20] !in "MF<") continue
            return day(fullYear(e.substring(0, 2).toInt(), thisYear), e.substring(2, 4).toInt(), e.substring(4, 6).toInt())
        }
        return null
    }

    /**
     * When the document runs out: a passport's checked machine-readable expiry, else the latest date (within what the
     * Vault takes) on the line and the line after the first expiry label; null when it doesn't say.
     */
    fun expiry(text: String, today: Long): Long? {
        val ok = { d: Long -> d >= today - DocumentRules.MAX_DAYS_BACK && d <= today + DocumentRules.MAX_DAYS_AHEAD }
        return expiryWhere(text, CivilDate.fromEpochDay(today).year, ok)
    }

    private fun expiryWhere(text: String, thisYear: Int, ok: (Long) -> Boolean): Long? {
        mrzExpiry(text, thisYear)?.takeIf(ok)?.let { return it }
        val lower = text.lowercase()
        val starts = ANCHORS.mapNotNull { a -> lower.indexOf(a).takeIf { it >= 0 }?.let { it + a.length } } +
            listOfNotNull(licenceExpiry(lower).takeIf { it >= 0 })
        for (at in starts.sorted()) {
            val found = dates(window(lower, at), thisYear).filter(ok).maxOrNull()
            if (found != null) return found
        }
        return null
    }

    // ---- Whose it is ----

    private fun firstName(w: String?): String? {
        val n = w?.trim { !it.isLetter() }?.takeIf { it.length in 2..20 && it.all { c -> c.isLetter() || c == '-' || c == '\'' } } ?: return null
        if (n.lowercase() in LABEL_WORDS) return null
        return n.lowercase().replaceFirstChar { it.uppercase() }
    }

    private val LABEL_WORDS = setOf("given", "names", "name", "prénoms", "prenoms", "surname", "nom", "date", "of", "birth", "sex", "the")

    /**
     * The holder's first given name, for a document that names its holder: a passport's machine-readable first line
     * ("P<GBRSMITH<<ALEX<JOHN"), else the line after "Given names", else a licence's field "2.". Null when unsure.
     */
    fun holder(text: String, kind: DocKind): String? {
        if (kind !in setOf(DocKind.PASSPORT, DocKind.DRIVING_LICENCE, DocKind.ID_CARD, DocKind.HEALTH_CARD)) return null
        mrzLines(text).firstOrNull { it.startsWith("P<") && it.length >= 10 }?.let { l ->
            val names = l.substring(5)
            val cut = names.indexOf("<<")
            if (cut > 0) firstName(names.substring(cut + 2).split('<').firstOrNull())?.let { return it }
        }
        val lines = text.lines().map { it.trim() }
        lines.forEachIndexed { i, l ->
            val low = l.lowercase()
            val at = low.indexOf("given name")
            if (at >= 0) {
                // "Given names / Prénoms (2)" with the names on the next line, or "4. Given names: ALEX" on the same.
                val after = l.substring(at + "given name".length).trimStart('s', 'S')
                after.split(' ', '/', ':').firstNotNullOfOrNull { w -> w.takeIf { it.any(Char::isLetter) }?.let(::firstName) }?.let { return it }
                firstName(lines.getOrNull(i + 1)?.split(' ')?.firstOrNull())?.let { return it }
            }
        }
        if (kind == DocKind.DRIVING_LICENCE) {
            lines.firstOrNull { it.startsWith("2.") || it.startsWith("2 ") }?.let { l ->
                firstName(l.drop(2).trim().split(' ').firstOrNull())?.let { return it }
            }
        }
        return null
    }

    // ---- The offer ----

    /** What insurance covers, when it says: "Car insurance", "Home insurance"… */
    private fun insuranceTitle(lower: String): String = when {
        has(lower, "motor", "vehicle", "car insurance", "registration mark") -> "Car insurance"
        has(lower, "home insurance", "buildings", "contents") -> "Home insurance"
        has(lower, "travel insurance", "travel") -> "Travel insurance"
        has(lower, "pet insurance", " pet ") -> "Pet insurance"
        has(lower, "life insurance", "life cover") -> "Life insurance"
        else -> "Insurance"
    }

    /** The Vault row a document's words make, or null when it isn't one or doesn't say when it runs out. */
    fun read(text: String, subject: String?, today: Long): DocumentEntry? {
        val all = listOfNotNull(subject?.takeIf { it.isNotBlank() }, text).joinToString("\n")
        val kind = kindOf(all) ?: return null
        val expires = expiry(all, today) ?: return null
        val name = holder(all, kind)
        val base = when (kind) {
            DocKind.INSURANCE -> insuranceTitle(all.lowercase())
            DocKind.HEALTH_CARD -> "GHIC"
            else -> kind.label
        }
        val tail = if (base.any { it.isLowerCase() }) base.replaceFirstChar { it.lowercase() } else base
        val title = (if (name != null) "$name $tail" else base)
            .split(' ').filterNot(DocumentRules::looksLikeNumber).joinToString(" ").take(DocumentRules.MAX_TITLE).trim()
        return DocumentEntry(title.ifEmpty { kind.label }, kind, expires)
    }

    /** Whether [text] looks like a document with an expiry (for a screenshot's notice); no date bounds. */
    fun looksLikeOne(text: String): Boolean =
        kindOf(text) != null && expiryWhere(text, THIS_CENTURY, { true }) != null

    /** For [looksLikeOne] only: any year will do to see that a date is there. */
    private const val THIS_CENTURY = 2026

    /** The capture sheet's offer for [text], against the Vault's [existing] documents; null when it isn't a document. */
    fun view(text: String, subject: String?, today: Long, existing: List<DocumentItem>): DocumentShareView? {
        val e = read(text, subject, today) ?: return null
        val same = existing.firstOrNull { it.title.equals(e.title, ignoreCase = true) }
        val date = DocumentRules.dateLabel(e.expiresDay!!)
        return when {
            same != null && same.expiresDay == e.expiresDay ->
                DocumentShareView("Already in the Vault · ${e.title} · expires $date", e, already = true, updates = false)
            same != null -> DocumentShareView("Update the Vault · ${e.title} · now expires $date", e, already = false, updates = true)
            else -> DocumentShareView("$ACTION · ${e.title} · expires $date", e, already = false, updates = false)
        }
    }
}
