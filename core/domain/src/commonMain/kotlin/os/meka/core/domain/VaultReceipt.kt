package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Receipts → Vault (build plan: "Promises spotted in email → Waiting for; receipts → Vault"; "Returns and warranties:
 * … the receipt is kept in the Vault"), on his tap. A receipt or order email Inbox autopilot tidied shows, in its
 * preview under Archived for you (and All tidied mail), **Keep in the Vault**: one tap makes a Vault document,
 * "Currys receipt" · "Receipt · £499.00 · bought Sat 10 Oct 2026 · Personal mail", with the warranty's end as its date
 * when the email says how long (or when its price put a warranty on Renewals): "Warranty until Fri 8 Oct 2027". The
 * document's row offers **Open the email** (Gmail's search or Outlook's folder, made at the tap, as Archived for you
 * does) and, like any document, Add a copy for a PDF or photo of the receipt itself. **Take it out of the Vault** on
 * the same preview undoes it.
 *
 * What is kept is what the tidy already holds — the sender's name, the subject, when it came, the price read from the
 * subject and preview — never the email's body, address or the provider's ids (ADR-008 addendum 2026-10-18: one
 * additive Text field `vault_doc.receipt`). Every word is the shop's (ADR-006): shown, never acted on. A warranty that
 * Renewals already reminds about gets no second heads-up from the Vault. Non-AI, pure, unit-tested.
 */

/** What a kept receipt remembers of its email. */
data class VaultReceipt(
    /** [MailTidiedEmail.key]: one Vault document per email. */
    val emailKey: String,
    /** The tidy it came from, for Open the email while the tidy is kept. */
    val batchId: String,
    val pence: Long?,
    /** The local day the email came. */
    val boughtDay: Long,
    /** "Personal", "Business", "Outlook". */
    val account: String,
    val merchant: String,
    val subject: String,
    /** The warranty's end is also on Renewals (its price was over his threshold), so the Vault adds no heads-ups. */
    val onRenewals: Boolean,
)

/** The preview's Vault line and button for a receipt email. */
data class VaultReceiptOffer(
    /** "Keep in the Vault" or "Take it out of the Vault". */
    val action: String,
    /** Under it: what keeping does, or "In the Vault as “Currys receipt” · …". */
    val line: String,
    val kept: Boolean,
)

object VaultReceiptRules {
    const val KEEP = "Keep in the Vault"
    const val TAKE_OUT = "Take it out of the Vault"
    const val OPEN = "Open the email"
    const val GONE = "Couldn't find that email any more"
    /** After the tidy's record has gone (Delete mail data), Open the email can only say what to look for. */
    const val NO_TIDY = "The tidy's record has gone · search your mail for it"
    private const val SEP = '\u001f'
    private const val VERSION = "r1"

    fun encode(r: VaultReceipt): String = listOf(
        VERSION, r.emailKey, r.batchId, r.pence?.toString().orEmpty(), r.boughtDay.toString(), clean(r.account),
        clean(r.merchant), if (r.onRenewals) "1" else "0", clean(r.subject),
    ).joinToString(SEP.toString())

    fun decode(text: String?): VaultReceipt? {
        val p = text?.split(SEP) ?: return null
        if (p.size < 9 || p[0] != VERSION || p[1].isBlank()) return null
        return VaultReceipt(
            emailKey = p[1], batchId = p[2], pence = p[3].toLongOrNull(), boughtDay = p[4].toLongOrNull() ?: return null,
            account = p[5], merchant = p[6].ifBlank { "Shop" }, subject = p[8], onRenewals = p[7] == "1",
        )
    }

    private fun clean(s: String) = s.map { if (it == SEP || it.code < 0x20) ' ' else it }.joinToString("").trim()

    /** "Currys receipt"; the sender's name as the tidy read it. */
    fun title(merchant: String): String = "${merchant.take(DocumentRules.MAX_TITLE - 8).trim()} receipt"

    /**
     * The warranty's last day for a receipt bought on [boughtDay]: the one [MailWarrantyRules.read] would put on
     * Renewals at [level] (onRenewals), else the length the email states, else none.
     */
    fun warranty(e: MailTidiedEmail, boughtDay: Long, level: MailWarrantyLevel): Pair<Long, Boolean>? {
        val receipt = MailReceipt(e.pence, e.from)
        MailWarrantyRules.read(receipt, e.subject, e.preview, boughtDay, level)?.let { return it.endDay to true }
        val months = MailWarrantyRules.statedMonths(e.subject, e.preview) ?: return null
        return (MailWarrantyRules.plusMonths(boughtDay, months) - 1) to false
    }

    /** The document [e] (a receipt in [b]) becomes, or null when it isn't a receipt. */
    fun entry(b: MailTidyBatch, e: MailTidiedEmail, cal: LocalCalendar, level: MailWarrantyLevel): Pair<DocumentEntry, VaultReceipt>? {
        if (e.tidy != MailTidy.RECEIPT) return null
        val bought = cal.epochDayOf(e.receivedAtMs)
        val w = warranty(e, bought, level)
        val merchant = e.from.ifBlank { "Shop" }
        val r = VaultReceipt(e.key, b.id, e.pence, bought, b.account, merchant, e.subject, onRenewals = w?.second == true)
        return DocumentEntry(title(merchant), DocKind.RECEIPT, w?.first) to r
    }

    fun fields(entry: DocumentEntry, r: VaultReceipt, nowMs: Long): Map<String, FieldValue> =
        DocumentRules.fields(entry, nowMs) + (VaultDocFields.RECEIPT to encode(r).fv())

    /** "Receipt · £499.00 · bought Sat 10 Oct 2026 · Personal mail". */
    fun rowLine(r: VaultReceipt): String = listOfNotNull(
        "Receipt", r.pence?.let(MailReceiptRules::format), "bought " + DocumentRules.dateLabel(r.boughtDay), "${r.account} mail",
    ).joinToString(" · ")

    /** "Warranty until Fri 8 Oct 2027", lit in its last month; "Warranty ended …"; "Kept from your mail · no warranty date". */
    fun status(d: DocumentItem, today: Long): String {
        val e = d.expiresDay ?: return "Kept from your mail · no warranty date"
        val left = e - today
        return when {
            left < 0 -> "Warranty ended ${DocumentRules.dateLabel(e)}"
            left == 0L -> "Warranty ends today · anything to claim, claim now"
            DocumentRules.due(d, today) -> "Warranty ends in ${DocumentRules.spanWords(left)} · anything to claim, claim before then"
            else -> "Warranty until ${DocumentRules.dateLabel(e)}"
        }
    }

    /** The preview's offer for [e]: none for anything that isn't a receipt. */
    fun offer(e: MailTidiedEmail, docs: List<DocumentItem>): VaultReceiptOffer? {
        if (e.tidy != MailTidy.RECEIPT) return null
        val kept = docs.firstOrNull { it.receipt?.emailKey == e.key }
        return if (kept != null) VaultReceiptOffer(TAKE_OUT, "In the Vault as “${kept.title}” · Ask → Settings → Vault", kept = true)
        else VaultReceiptOffer(KEEP, "Keeps the receipt with the Vault's documents · add a PDF or photo of it there", kept = false)
    }

    /** The line after keeping it. */
    fun keptLine(entry: DocumentEntry): String =
        "Kept “${entry.title}” in the Vault" + (entry.expiresDay?.let { " · warranty until ${DocumentRules.dateLabel(it)}" } ?: "")

    fun takenOutLine(title: String): String = "Took “$title” out of the Vault"

    /** What to look for once the tidy has gone: "Search your Personal mail for “Your Currys order” from Sat 10 Oct". */
    fun searchLine(r: VaultReceipt): String =
        "$NO_TIDY: “${r.subject}” from ${r.merchant}, ${CivilDate.shortLabel(r.boughtDay)}"
}
