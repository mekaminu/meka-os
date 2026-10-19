package os.meka.core.domain

/*
 * Money at a glance, the email half (build plan: "Money manager (Open Banking) now covers spend by category: make this
 * the email half of it (receipt detail matched to transactions) so it isn't built twice"; Money manager item 2:
 * "receipts from email matched for item detail"). A payment in Money whose receipt or order email Inbox autopilot
 * tidied (the same amount, from a day before the email to [MoneyReceipts.AFTER_DAYS] after it, the shop's name in the
 * statement's words, or the only payment and the only receipt of that amount in that window) shows what it was for
 * under it: "Receipt in your email · “Your order of Kindle Paperwhite”", with the provider's short preview when it adds
 * something. The month says how many of its payments have one.
 *
 * Read only from what the tidy already holds on the device (sender's name, subject, preview, when it came, the price
 * read from the subject and preview), never the body; nothing new is stored or synced, nothing is sent (no migration).
 * Every word is the shop's (ADR-006): shown as a quote, never acted on. Pure, non-AI, unit-tested.
 */

/** A priced receipt or order email, as Money matches it. */
data class MoneyReceiptMail(
    val key: String,
    /** The local day the email came. */
    val day: Long,
    val pence: Long,
    /** "Amazon", "Currys"; the sender's name. */
    val merchant: String,
    val subject: String,
    val preview: String?,
)

/** One statement line as the matcher sees it: [pence] signed (money out negative), [merchantKey] its words' key. */
data class MoneyReceiptLine(val id: String, val day: Long, val pence: Long, val merchantKey: String)

object MoneyReceipts {
    /** A statement can date a payment the day before the email (a late-evening order). */
    const val BEFORE_DAYS = 1
    /** Card payments post a few days after the order. */
    const val AFTER_DAYS = 5
    const val MAX_QUOTE = 70
    const val MAX_DETAIL = 90
    const val LINE = "Receipt in your email"

    /** The priced receipts and orders in his tidies (a tidy he undid still had them), one per email, newest kept. */
    fun fromTidies(batches: List<MailTidyBatch>, cal: LocalCalendar): List<MoneyReceiptMail> =
        batches.asSequence().flatMap { it.emails.asSequence() }
            .filter { it.tidy == MailTidy.RECEIPT && (it.pence ?: 0L) > 0L }
            .distinctBy { it.key }
            .map { e ->
                MoneyReceiptMail(e.key, cal.epochDayOf(e.receivedAtMs), e.pence!!, e.from, e.subject, e.preview?.takeIf { it.isNotBlank() })
            }.toList()

    /** Letters-only words of a name, upper case, three letters or more ("Amazon.co.uk" → AMAZON). */
    private fun words(s: String): List<String> =
        s.uppercase().replace(Regex("""\.(CO\.UK|COM|CO|UK|NET|ORG)\b"""), " ").split(Regex("[^A-Z]+")).filter { it.length >= 3 }
            .filter { it !in GENERIC }

    private val GENERIC = setOf("THE", "LTD", "LIMITED", "PLC", "SHOP", "STORE", "ONLINE", "ORDERS", "ORDER", "UK", "GROUP", "SERVICES", "PAYMENTS", "RECEIPTS")

    /** The shop's name is in the statement's words: a word of three letters or more the same, or one starting the other ("TESCO STORES" and "Tesco"). */
    fun sameShop(receiptMerchant: String, merchantKey: String): Boolean {
        val r = words(receiptMerchant)
        val k = words(merchantKey)
        if (r.isEmpty() || k.isEmpty()) return false
        return r.any { w -> k.any { it == w || (w.length >= 4 && it.startsWith(w)) || (it.length >= 4 && w.startsWith(it)) } }
    }

    /**
     * Each receipt matched to at most one payment and each payment to at most one receipt: oldest receipts first, the
     * same shop first (then the closest day), else only when it is the one payment of that amount in the window and the
     * one receipt of that amount near it. Returns payment id → receipt.
     */
    fun match(lines: List<MoneyReceiptLine>, receipts: List<MoneyReceiptMail>): Map<String, MoneyReceiptMail> {
        if (lines.isEmpty() || receipts.isEmpty()) return emptyMap()
        val out = HashMap<String, MoneyReceiptMail>()
        val used = HashSet<String>()
        val byAmount = lines.filter { it.pence < 0 }.groupBy { -it.pence }
        val sorted = receipts.sortedWith(compareBy<MoneyReceiptMail> { it.day }.thenBy { it.key })
        fun window(r: MoneyReceiptMail) = (r.day - BEFORE_DAYS)..(r.day + AFTER_DAYS)
        // Same-shop matches first, so a nameless match can't take a payment a named one should have.
        val pending = mutableListOf<MoneyReceiptMail>()
        for (r in sorted) {
            val cands = byAmount[r.pence].orEmpty().filter { it.id !in used && it.day in window(r) }
            val named = cands.filter { sameShop(r.merchant, it.merchantKey) }
                .minWithOrNull(compareBy<MoneyReceiptLine> { kotlin.math.abs(it.day - r.day) }.thenBy { it.id })
            if (named != null) { used += named.id; out[named.id] = r } else pending += r
        }
        for (r in pending) {
            val all = byAmount[r.pence].orEmpty().filter { it.day in window(r) }
            val only = all.singleOrNull()?.takeIf { it.id !in used } ?: continue
            val rivals = receipts.count { it.pence == r.pence && only.day in window(it) }
            if (rivals == 1) { used += only.id; out[only.id] = r }
        }
        return out
    }

    private fun cut(s: String, max: Int): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** "Receipt in your email · “Your order of Kindle Paperwhite”". */
    fun line(r: MoneyReceiptMail): String {
        val q = r.subject.takeIf { it.isNotBlank() && it != MailTidiedRules.NO_SUBJECT }?.let { "“${cut(it, MAX_QUOTE)}”" }
        return listOfNotNull(LINE, q ?: r.merchant).joinToString(" · ")
    }

    /** The preview when it says more than the subject (the item, the size), cut; else null. */
    fun detail(r: MoneyReceiptMail): String? {
        val p = r.preview?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val s = r.subject.trim().lowercase()
        if (s.isNotEmpty() && (p.lowercase().startsWith(s) && p.length - s.length < 12)) return null
        return cut(p, MAX_DETAIL)
    }

    /** "4 of October's payments have a receipt in your email"; null with none. */
    fun monthLine(matched: Int, month: String): String? = when {
        matched <= 0 -> null
        matched == 1 -> "1 of $month's payments has a receipt in your email"
        else -> "$matched of $month's payments have a receipt in your email"
    }

    /** What a screen reader adds to the payment. */
    fun spoken(r: MoneyReceiptMail): String = ", receipt in your email: ${cut(r.subject, MAX_QUOTE)}"
}
