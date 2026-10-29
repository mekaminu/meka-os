package os.meka.core.domain

import os.meka.core.sync.FieldValue

/*
 * Renewals keep their email (Meka, 2026-10-28 20:14: "I got this but can't even find the email to get the details"),
 * slice 1 (non-AI, pure): every Renewals item that came from an email (a renewal or subscription find he added, a bill,
 * a return or a warranty the server put there) knows its email again.
 *
 * - **Keep the source.** The server keeps which message it was on its own mirror row ([remoteId]: the provider's
 *   message and thread ids, never synced: ADR-008 addendum 2026-10-16 still holds, the apps never see a provider id),
 *   and from now on writes the sender's name ([MailFindFields.FROM_NAME]) on a find. Never the body.
 * - **Open the email.** The apps ask for it by Navilife's own handle ([MailReaderSource.FIND]: the find's id, or a
 *   server-written item's own id), and the server reads that exact message whole, as the "From your mail" reader does
 *   (with Open in Gmail / Outlook at its foot). An item made before this has no kept message: the server finds it again
 *   among the account's kept headers ([sameEmail]: the same sender and subject on the day it came, or for a bill,
 *   return or warranty the email whose id makes the item's own). Gone: the reader says so.
 * - **From the email.** The card's box ([box]): who sent it, the subject, the day it came and what Navilife read from
 *   it (the amount, how often, the renewal or due day, a cancel-by day), as tiles, so he rarely needs to open it.
 *
 * Untrusted (ADR-006): the box is plain text, nothing is acted on, and opening it is only ever his tap.
 */

/** One tile of what Navilife read from the email: "Amount" · "£10.99 a month". */
data class EmailReadTile(val label: String, val value: String)

/** "From the email" on a Renewals card. */
data class RenewalEmail(
    /** The handle the reader asks for ([MailReaderSource.FIND]). */
    val handle: String,
    /** "Netflix" (the sender's name, else the company, else the account). */
    val from: String,
    /** "“Your membership renews soon”", or null when none was kept. */
    val subject: String?,
    /** "Sun 25 Oct · Personal mail"; the account alone when the day isn't known. */
    val received: String,
    val tiles: List<EmailReadTile>,
    /** "Open the email". */
    val openLabel: String,
    val spoken: String,
    /** For the reader's fallback while it loads or can't. */
    val account: String,
    val receivedDay: Long?,
)

object MailSourceRules {
    const val BOX_TITLE = "From the email"
    const val OPEN = "Open the email"
    const val AMOUNT = "Amount"
    const val PLAN = "Plan"
    const val RENEWS = "Renews"
    const val DUE = "Due"
    const val CANCEL_BY = "Cancel by"

    /** The mirror row's note of the message: "src:" + the message id, then the thread id after a tab (server only). */
    private const val PREFIX = "src:"

    fun remoteId(messageId: String, threadId: String?): String = PREFIX + messageId + (threadId?.let { "\t$it" } ?: "")

    /** The message id kept by [remoteId], or null for a row that kept none (or kept something else). */
    fun messageOf(remoteId: String?): String? =
        remoteId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('\t')?.takeIf { it.isNotBlank() }

    /** The thread id kept by [remoteId], or null. */
    fun threadOf(remoteId: String?): String? =
        remoteId?.takeIf { it.startsWith(PREFIX) && '\t' in it }?.substringAfter('\t')?.takeIf { it.isNotBlank() }

    private fun norm(s: String?): String? = s?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Whether a kept header is the email an older find came from: it came on the find's day ([headerDay] ==
     * [receivedDay]) and has the same subject (as the find kept it, cut to [MailRenewalRules.MAX_SUBJECT]); a find that
     * kept no subject needs the same sender ([MailReceiptRules.merchant]) instead.
     */
    fun sameEmail(
        merchant: String?, receivedDay: Long, subject: String?,
        fromName: String?, fromAddress: String?, headerSubject: String?, headerDay: Long,
    ): Boolean {
        if (headerDay != receivedDay) return false
        val s = norm(subject)
        if (s != null) return s == norm(headerSubject)
        val m = merchant?.lowercase() ?: return false
        return MailReceiptRules.merchant(fromName, fromAddress)?.lowercase() == m
    }

    /** The finds whose Add makes a Renewals item: renewals and Life audit's subscriptions, by that item's id. */
    fun findsByItem(finds: Collection<MailFind>): Map<String, MailFind> =
        finds.filter { it.kind == MailRenewalRules.KIND_RENEWAL || it.kind == LifeAuditRules.KIND_SUBSCRIPTION }
            .associateBy { MailRenewalRules.obligationId(it.id) }

    /** Whether a Renewals item was put there by the server from an email (a bill, a return or a warranty). */
    fun fromServerMail(fields: Map<String, FieldValue>): Boolean =
        fields[ActionableFields.PROVENANCE_SOURCE]?.textOrNull == MailBillRules.SOURCE &&
            fields[ActionableFields.PROVENANCE_TRUST]?.textOrNull == Trust.UNTRUSTED_EXTERNAL.name

    /** "Sun 25 Oct · Personal mail". */
    private fun received(day: Long?, account: String): String =
        listOfNotNull(day?.let { CivilDate.shortLabel(it) }, "$account mail").joinToString(" · ")

    /** The account an item's subject line names ("Personal mail" → "Personal"), else "your". */
    fun accountOf(subjectLabel: String?): String =
        subjectLabel?.trim()?.removeSuffix(" mail")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 20 } ?: "your"

    /** The box for a Renewals item made from the find [f]: what the find read, and who it was from. */
    fun box(f: MailFind, today: Long): RenewalEmail {
        val from = f.fromName ?: f.merchant ?: "${f.account} mail"
        val tiles = listOfNotNull(
            MailRenewalRules.priceLabel(f.pence, f.repeat, f.renewDay)?.let { EmailReadTile(AMOUNT, it) },
            RenewalRules.repeatLabel(f.repeat).takeIf { f.repeat != RenewalRepeat.NONE }?.let { EmailReadTile(PLAN, it) },
            EmailReadTile(RENEWS, RenewalRules.dayWord(f.renewDay, today)),
            f.cancelByDay?.let { EmailReadTile(CANCEL_BY, RenewalRules.dayWord(it, today)) },
        )
        return made(f.id, from, f.subject, f.receivedDay, f.account, tiles)
    }

    /**
     * The box for an item the server wrote from an email (a bill, return or warranty): what the item holds (its title
     * as the sender, the amount and the due day); the day it came is only in its note, so it isn't shown.
     */
    fun box(item: RenewalItem, today: Long): RenewalEmail {
        val account = accountOf(item.subject)
        val tiles = listOfNotNull(
            item.costPence?.let { EmailReadTile(AMOUNT, RenewalRules.formatPence(it)) },
            EmailReadTile(if (item.kind == ObligationKind.BILL) DUE else RENEWS, RenewalRules.dayWord(item.dueDay, today)),
        )
        return made(item.id, item.title, null, null, account, tiles)
    }

    private fun made(handle: String, from: String, subject: String?, day: Long?, account: String, tiles: List<EmailReadTile>): RenewalEmail {
        val quoted = subject?.trim()?.takeIf { it.isNotEmpty() }?.let { "“$it”" }
        val rec = received(day, account)
        return RenewalEmail(
            handle = handle, from = from, subject = quoted, received = rec, tiles = tiles, openLabel = OPEN,
            spoken = listOfNotNull("$BOX_TITLE: $from", quoted, rec, tiles.joinToString(", ") { "${it.label} ${it.value}" }.ifEmpty { null })
                .joinToString(". ") + ".",
            account = account, receivedDay = day,
        )
    }

    /** The reader's stand-in while the email loads, or when it can't be read. */
    fun fallback(e: RenewalEmail, cal: LocalCalendar, nowMs: Long): MailReaderFallback = MailReaderFallback(
        from = e.from, subject = e.subject?.removePrefix("“")?.removeSuffix("”") ?: "",
        preview = e.tiles.joinToString(" · ") { "${it.label} ${it.value}" }.ifEmpty { null },
        atMs = e.receivedDay?.let { cal.toEpochMs(it, 12 * 60) } ?: nowMs,
        account = e.account,
    )
}
