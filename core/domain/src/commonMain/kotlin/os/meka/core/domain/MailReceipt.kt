package os.meka.core.domain

/**
 * Inbox autopilot, slice 3d: what a receipt or order email says it cost, and who from (Meka, 2026-10-12: receipts
 * auto-archived **but shown to him**; the "Archived for you" card reads "3 receipts and orders £84.20"). Read from what
 * the server already keeps — the sender's name and address, the subject and the provider's short preview (Gmail's
 * snippet, Outlook's bodyPreview) — never the body. Non-AI, pure, unit-tested. Pounds only: an amount in another
 * currency, a refund, or a preview naming several prices with no "total" leaves the receipt unpriced (counted, no sum).
 */
data class MailReceipt(
    /** What it cost in pence, or null when the words don't say for sure. */
    val pence: Long?,
    /** "Amazon", "Tesco", "Trainline"; null when neither the name nor the address gives one. */
    val merchant: String?,
)

object MailReceiptRules {
    /** Above this an amount is taken as a misread (an order number with a £ in front), not a receipt. */
    const val MAX_PENCE = 10_000_00L
    /** Merchants a tidy's line names before "+ N more". */
    const val MERCHANTS_SHOWN = 3

    private val AMOUNT = Regex("""(?:£|GBP\s?)\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""", RegexOption.IGNORE_CASE)
    private val TOTAL_WORDS = Regex(
        """\b(order total|grand total|total paid|total charged|total|amount paid|amount charged|amount due|you paid|you've paid|paid|charged|payment of|amount)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REFUND = Regex("""\b(refund|refunded|refunding|credited|credit note)\b""", RegexOption.IGNORE_CASE)
    private val GENERIC_NAMES = setOf(
        "no-reply", "noreply", "no reply", "donotreply", "do not reply", "orders", "order", "receipts", "receipt",
        "notifications", "notification", "info", "hello", "support", "customer service", "customer services", "team",
        "accounts", "billing", "payments", "shop", "store", "mail", "email", "news",
    )
    private val SLD = setOf("co", "com", "org", "net", "ac", "gov", "ltd", "plc", "me")

    /** The receipt as the server reads it at tidy time. */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?): MailReceipt =
        MailReceipt(pence = amount(subject, preview), merchant = merchant(fromName, fromAddress))

    /**
     * The amount in pence: a refund → null; the first £ amount right after a total word ("Order total: £84.20",
     * "You paid £12.99") in the subject, then the preview; else the one £ amount the words name (all the same); else null.
     */
    fun amount(subject: String?, preview: String?): Long? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { REFUND.containsMatchIn(it) }) return null
        for (t in texts) {
            for (w in TOTAL_WORDS.findAll(t)) {
                // The amount must follow the word closely ("Total: £84.20", "Total (inc VAT) £84.20"), not a later line.
                val after = t.substring(w.range.last + 1).take(28)
                val m = AMOUNT.find(after) ?: continue
                if (after.substring(0, m.range.first).any { it.isDigit() }) continue
                pence(m)?.let { return it }
            }
        }
        val all = texts.flatMap { t -> AMOUNT.findAll(t).mapNotNull { pence(it) }.toList() }.distinct()
        return all.singleOrNull()
    }

    private fun pence(m: MatchResult): Long? {
        val pounds = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val p = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        val total = pounds * 100 + p
        return total.takeIf { it in 1..MAX_PENCE }
    }

    /**
     * Who it's from: the sender's display name without "no-reply"/"Orders"-style words and domain endings
     * ("Amazon.co.uk" → "Amazon", "Tesco Groceries" stays); else the address's registrable label ("orders@email.trainline.com"
     * → "Trainline", "orders@shopco.co.uk" → "Shopco"); null when neither gives a name.
     */
    fun merchant(fromName: String?, fromAddress: String?): String? {
        val name = fromName?.trim()?.trim('"', '\'')?.let { cleanName(it) }
        if (!name.isNullOrBlank()) return name
        val domain = MailSortRules.domain(fromAddress) ?: return null
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        var i = parts.size - 2
        if (parts[i] in SLD && i > 0) i--
        val label = parts[i]
        if (label.length < 2) return null
        return label.replaceFirstChar { it.uppercase() }
    }

    private fun cleanName(raw: String): String? {
        var s = raw
        // "Amazon.co.uk" → "Amazon"; "orders@shop.com" as a name → nothing (the address path decides).
        if ('@' in s) return null
        s = s.replace(Regex("""\.(co\.uk|com|co|uk|org|net|io)\b""", RegexOption.IGNORE_CASE), "")
        val words = s.split(Regex("""\s*[|·•\-–—:]\s*|\s+""")).filter { it.isNotBlank() }
        val kept = words.filterNot { w -> w.lowercase() in GENERIC_NAMES || w.lowercase() in setOf("no", "reply", "do", "not") }
        val out = kept.joinToString(" ").trim()
        return out.takeIf { it.length >= 2 && out.any { it.isLetter() } }?.take(40)
    }

    /** "£84.20", "£1,204.00". */
    fun format(pence: Long): String {
        val pounds = pence / 100
        val p = (pence % 100).toString().padStart(2, '0')
        val grouped = pounds.toString().reversed().chunked(3).joinToString(",").reversed()
        return "£$grouped.$p"
    }

    /** The total of the priced receipts, or null when none is priced. */
    fun total(receipts: List<MailReceipt>): Long? = receipts.mapNotNull { it.pence }.takeIf { it.isNotEmpty() }?.sum()

    /** The distinct merchants, most receipts first, then by name. */
    fun merchants(receipts: List<MailReceipt>): List<String> =
        receipts.mapNotNull { it.merchant }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /**
     * What a tidy's receipts line adds after "→ Navilife/Receipts": " · £84.20 · Amazon, Tesco + 1 more" (all priced),
     * " · £84.20 on 2 · Amazon" (some priced), " · Amazon" (none priced); "" when there's nothing to add.
     */
    fun suffix(count: Int, pricedCount: Int, pence: Long?, merchants: List<String>): String {
        val money = when {
            pence == null || pricedCount <= 0 -> null
            pricedCount >= count -> format(pence)
            else -> "${format(pence)} on $pricedCount"
        }
        val who = merchants.takeIf { it.isNotEmpty() }?.let { m ->
            val shown = m.take(MERCHANTS_SHOWN).joinToString(", ")
            if (m.size > MERCHANTS_SHOWN) "$shown + ${m.size - MERCHANTS_SHOWN} more" else shown
        }
        return listOfNotNull(money, who).joinToString("") { " · $it" }
    }
}
