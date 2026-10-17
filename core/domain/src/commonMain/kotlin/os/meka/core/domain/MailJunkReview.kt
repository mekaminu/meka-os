package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Review on an approval card opens its own list (Meka, 2026-10-17 04:03: "Empty Junk · Personal" — ↑ Review opened the
 * general Mail page instead of the list of what would move), slice 1: **the Empty Junk review**. When the server counts
 * an account's Junk folder it also writes, on the account's `mail_backlog` entity, who the looked-at emails are from,
 * grouped by sender ([MailBacklogFields.JUNK_SENDERS], one Text field, ADR-008 addendum 2026-10-17): per sender a key
 * (a hash, never the address), the sender's name, the domain, how many, up to two subjects, where they'd go (Trash, back
 * to the inbox, or staying for him to check), why a rescue is one and whether it reads as a scam. The card's Review
 * opens a sheet of it ([MailJunkReviewRules.review]): the rescues first (who and why), then each sender going to Trash
 * with a tick he can take off to keep that sender's mail in Junk ([MailBacklogFields.JUNK_KEEP_SENDERS], written by
 * the apps, synced, Undo), and Approve inside the sheet. The server never trashes a kept sender's mail (the clean-out
 * and the daily Junk review). Every word in the list is the sender's (ADR-006): shown as plain text, never acted on.
 * Non-AI, pure, unit-tested.
 */

/** One sender's emails in the looked-at Junk, as the server wrote them. */
data class MailJunkSender(
    /** [MailJunkReviewRules.senderKey]: a hash of the domain (or, for webmail, the address). */
    val key: String,
    /** Where their mail would go: [MailTidy.JUNK_TRASH], [MailTidy.RESCUE] or [MailTidy.KEEP] (stays for him to check). */
    val tidy: MailTidy,
    val name: String,
    val domain: String?,
    val count: Int,
    /** Up to [MailJunkReviewRules.SUBJECTS] subjects, newest first. */
    val subjects: List<String>,
    /** The scam signal ("claims to be paypal", "you've won"), else null. */
    val scam: String? = null,
    /** For a rescue: why ("you wrote in this thread", "already writes to you", "your partner"). */
    val why: String? = null,
)

/** A sender row in the review sheet. */
data class JunkReviewRow(
    val key: String,
    /** "Prize Draws" */
    val title: String,
    /** "prizes.example · 42 emails" */
    val meta: String,
    val subjects: List<String>,
    /** "Looks like a scam" chip. */
    val scam: Boolean,
    /** "Claims to be PayPal" / "Says “you've won”", under the chip; null when not a scam. */
    val scamLine: String?,
    /** He took the tick off: their mail stays in Junk. */
    val kept: Boolean,
    val count: Int,
    /** "Going to Trash" / "Stays in Junk · you kept it". */
    val state: String,
)

/** Someone going back to the inbox. */
data class JunkRescueRow(val title: String, val meta: String, val why: String, val subjects: List<String>)

/** The Empty Junk card's review sheet. */
data class JunkReview(
    /** The card's id (Approve and the ticks go through it). */
    val cardId: String,
    /** "Empty Junk · Personal" */
    val title: String,
    /** "330 to Trash from 41 senders · 2 back to your inbox · 6 stay for you to check" */
    val summary: String,
    val rescues: List<JunkRescueRow>,
    val senders: List<JunkReviewRow>,
    /** "6 stay for you to check: banks, schools and public bodies" or null. */
    val staysLine: String?,
    /** "Take the tick off a sender to keep their mail in Junk" / the waiting line before the list has come. */
    val hint: String,
    /** When the server looked at only part of the folder: "Navilife looked at the newest 500 …"; else null. */
    val partLine: String?,
    /** "Approve · 330 to Trash" */
    val approveLabel: String,
    val canApprove: Boolean,
    /** "Nothing moves until you approve · Trash keeps them 30 days". */
    val note: String,
)

object MailJunkReviewRules {
    /** Fits the sync kernel's 20,000-character Text limit with room to spare. */
    const val MAX_CHARS = 18_000
    const val SUBJECTS = 2
    const val MAX_NAME = 60
    const val MAX_DOMAIN = 80
    const val MAX_SUBJECT = 100
    /** Kept-sender keys he can hold per account. */
    const val MAX_KEPT = 400

    const val HINT = "Take the tick off a sender to keep their mail in Junk. Nothing moves until you approve."
    const val WAITING = "Navilife lists who these are from at its next count of the folder (within a few hours). Nothing moves until you approve."
    const val NOTE = "Nothing moves until you approve · Trash keeps them for 30 days · Undo puts them back in Junk"
    const val KEPT_BAR = "Kept in Junk · their mail won't go to Trash"
    const val UNKEPT_BAR = "Going to Trash with the rest"

    /** Webmail domains: a sender there is one person, so they're grouped by address, never with everyone on the domain. */
    val WEBMAIL = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "hotmail.co.uk", "live.com", "live.co.uk", "msn.com",
        "yahoo.com", "yahoo.co.uk", "ymail.com", "icloud.com", "me.com", "mac.com", "aol.com", "aol.co.uk", "proton.me",
        "protonmail.com", "gmx.com", "gmx.co.uk", "mail.com", "btinternet.com", "sky.com", "virginmedia.com", "talktalk.net",
    )

    /** The sender's group: the domain, or for webmail the address (lower case); null with no address. */
    fun groupOf(fromAddress: String?): String? {
        val address = fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase()?.takeIf { '@' in it } ?: return null
        val domain = MailSortRules.domain(address) ?: return null
        return if (domain in WEBMAIL) address else domain
    }

    /** The sender's key as synced (a hash, so no address is ever written): "js" + 16 hex. */
    fun senderKey(fromAddress: String?, fromName: String?): String =
        "js" + ActivityRules.fnv64("junksender:" + (groupOf(fromAddress) ?: "name:" + MailUnsubscribeRules.displayName(fromName, null).lowercase()))

    /** Why a rescue goes back to the inbox, in the order [MailJunkRules.verdict] checks. */
    fun rescueWhy(f: MailJunkFacts): String? {
        val address = f.fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase()
        return when {
            address != null && address in f.keepAddresses -> "From your partner (About you)"
            f.replied -> "You wrote in this thread"
            f.knownSender -> "Already writes to you · their mail is in your inbox"
            else -> null
        }
    }

    private fun clean(s: String?, max: Int): String =
        s.orEmpty().map { if (it == '\t' || it == '\n' || it == '\r' || it.code < 0x20) ' ' else it }.joinToString("")
            .replace(Regex("""\s+"""), " ").trim().let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }

    /** What the server knows of one looked-at email: its header, where it would go and the facts behind it. */
    data class Looked(val fromAddress: String?, val fromName: String?, val subject: String?, val receivedAtMs: Long, val tidy: MailTidy, val why: String?)

    /** Groups looked-at emails by sender: the most first, ties by name. */
    fun group(looked: List<Looked>): List<MailJunkSender> =
        looked.groupBy { senderKey(it.fromAddress, it.fromName) + ":" + it.tidy.name }.values.map { list ->
            val newest = list.sortedByDescending { it.receivedAtMs }
            val head = newest.first()
            MailJunkSender(
                key = senderKey(head.fromAddress, head.fromName), tidy = head.tidy,
                name = clean(MailUnsubscribeRules.displayName(head.fromName, head.fromAddress), MAX_NAME),
                domain = MailSortRules.domain(head.fromAddress)?.let { clean(it, MAX_DOMAIN) },
                count = list.size,
                subjects = newest.mapNotNull { e -> e.subject?.let { clean(it, MAX_SUBJECT) }?.takeIf { it.isNotEmpty() } }.distinct().take(SUBJECTS),
                scam = newest.firstNotNullOfOrNull { e -> MailJunkRules.scamSignal(e.fromAddress, e.fromName, e.subject)?.let { asWritten(it, e.fromName) } },
                why = newest.firstNotNullOfOrNull { it.why },
            )
        }.sortedWith(compareByDescending<MailJunkSender> { it.count }.thenBy { it.name.lowercase() })

    /** "claims to be paypal" → "claims to be PayPal" when the sender's name writes it so. */
    private fun asWritten(signal: String, fromName: String?): String {
        if (!signal.startsWith("claims to be ")) return signal
        val claim = signal.removePrefix("claims to be ")
        val at = fromName?.indexOf(claim, ignoreCase = true) ?: -1
        return if (at >= 0) "claims to be " + fromName!!.substring(at, at + claim.length) else signal
    }

    private fun line(s: MailJunkSender): String = listOf(
        "1", s.key, s.tidy.name, s.count.toString(), clean(s.name, MAX_NAME), clean(s.domain, MAX_DOMAIN),
        clean(s.scam, 60), clean(s.why, 80), s.subjects.getOrNull(0)?.let { clean(it, MAX_SUBJECT) }.orEmpty(),
        s.subjects.getOrNull(1)?.let { clean(it, MAX_SUBJECT) }.orEmpty(),
    ).joinToString("\t")

    /**
     * The field's value: a "v1" first line (so an empty list still says the server looked), then as many senders as fit,
     * rescues and those going to Trash first. A sender left out for room is counted on the "v1" line.
     */
    fun encode(senders: List<MailJunkSender>): String {
        val ordered = senders.sortedBy { if (it.tidy == MailTidy.KEEP) 1 else 0 }
        val out = StringBuilder()
        var left = 0
        for (s in ordered) {
            val l = line(s)
            if (out.length + l.length + 12 > MAX_CHARS) { left++; continue }
            out.append('\n').append(l)
        }
        return "v1\t$left$out"
    }

    fun fields(senders: List<MailJunkSender>): Map<String, FieldValue> = mapOf(MailBacklogFields.JUNK_SENDERS to encode(senders).fv())

    /** The list back; null when the server hasn't written one yet. Lines it can't read are skipped. */
    fun decode(text: String?): List<MailJunkSender>? {
        val lines = text?.takeIf { it.startsWith("v1") }?.split('\n') ?: return null
        return lines.drop(1).mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 8 || p[0] != "1") return@mapNotNull null
            val tidy = MailTidy.of(p[2])?.takeIf { it == MailTidy.JUNK_TRASH || it == MailTidy.RESCUE || it == MailTidy.KEEP } ?: return@mapNotNull null
            MailJunkSender(
                key = p[1], tidy = tidy, count = p[3].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
                name = p[4].ifBlank { "Unknown sender" }, domain = p[5].ifBlank { null }, scam = p[6].ifBlank { null },
                why = p[7].ifBlank { null }, subjects = listOfNotNull(p.getOrNull(8)?.ifBlank { null }, p.getOrNull(9)?.ifBlank { null }),
            )
        }
    }

    /** Senders the server left off the list for room (the "v1" line's count). */
    fun leftOut(text: String?): Int = text?.takeIf { it.startsWith("v1") }?.substringBefore('\n')?.substringAfter('\t', "")?.toIntOrNull() ?: 0

    // ---- Kept senders (the apps write; the server reads) ----

    fun decodeKept(text: String?): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { it.startsWith("js") && it.length in 3..40 }.toSet()

    fun encodeKept(keys: Set<String>): String = keys.sorted().take(MAX_KEPT).joinToString(",")

    /** The field after he takes the tick off ([keep] true) or puts it back. */
    fun keptFields(current: Set<String>, key: String, keep: Boolean): Map<String, FieldValue> =
        mapOf(MailBacklogFields.JUNK_KEEP_SENDERS to encodeKept(if (keep) current + key else current - key).fv())

    /** Whether the server must leave this email in Junk: its sender is kept and it was going to Trash. */
    fun keptInJunk(kept: Set<String>, fromAddress: String?, fromName: String?, tidy: MailTidy): Boolean =
        tidy == MailTidy.JUNK_TRASH && kept.isNotEmpty() && senderKey(fromAddress, fromName) in kept

    /** Emails going to Trash once kept senders are left out; null before the list has come. */
    fun toTrash(b: MailBacklog): Int? {
        val senders = decode(b.junkSenders) ?: return null
        return senders.filter { it.tidy == MailTidy.JUNK_TRASH && it.key !in b.junkKeepSenders }.sumOf { it.count }
    }

    /** Emails kept in Junk by his untick. */
    fun keptCount(b: MailBacklog): Int =
        (decode(b.junkSenders) ?: emptyList()).filter { it.tidy == MailTidy.JUNK_TRASH && it.key in b.junkKeepSenders }.sumOf { it.count }

    private fun g(v: Int) = SpeechRules.grouped(v.toLong())
    private fun emails(v: Int) = "${g(v)} ${if (v == 1) "email" else "emails"}"

    private fun scamLine(signal: String): String {
        val s = signal.trim()
        return if (s.startsWith("claims to be ")) {
            "Claims to be " + s.removePrefix("claims to be ").split(' ').joinToString(" ") { w ->
                when {
                    w.any { it.isUpperCase() } -> w
                    w.length <= 4 -> w.uppercase()
                    else -> w.replaceFirstChar { it.uppercase() }
                }
            } + " · not their address"
        } else "Says “$s”"
    }

    /** The sheet for one account's Empty Junk card ([b]); null when there's nothing to approve. */
    fun review(b: MailBacklog): JunkReview? {
        val card = MailJunkClearRules.card(b) ?: return null
        val senders = decode(b.junkSenders)
        val kept = b.junkKeepSenders
        if (senders == null) {
            return JunkReview(
                cardId = card.id, title = card.title, summary = card.why.substringBefore(" · nothing moves"), rescues = emptyList(),
                senders = emptyList(), staysLine = null, hint = WAITING, partLine = null,
                approveLabel = MailJunkClearRules.CARD_YES, canApprove = true, note = NOTE,
            )
        }
        val trash = senders.filter { it.tidy == MailTidy.JUNK_TRASH }
        val rescues = senders.filter { it.tidy == MailTidy.RESCUE }
        val stays = senders.filter { it.tidy == MailTidy.KEEP }.sumOf { it.count }
        val going = trash.filter { it.key !in kept }.sumOf { it.count }
        val keptN = trash.filter { it.key in kept }.sumOf { it.count }
        val rescued = rescues.sumOf { it.count }
        val goingSenders = trash.count { it.key !in kept }
        val summary = listOfNotNull(
            "${emails(going)} to Trash" + if (goingSenders > 0) " from ${g(goingSenders)} ${if (goingSenders == 1) "sender" else "senders"}" else "",
            keptN.takeIf { it > 0 }?.let { "${g(it)} kept in Junk" },
            rescued.takeIf { it > 0 }?.let { "${g(it)} back to your inbox" },
            stays.takeIf { it > 0 }?.let { "${g(it)} ${if (it == 1) "stays" else "stay"} for you to check" },
        ).joinToString(" · ")
        val left = leftOut(b.junkSenders)
        val part = when {
            !b.junkLookFull && b.junkLooked != null && b.junkLooked > 0 ->
                "Navilife looked at the newest ${g(b.junkLooked)} of the folder's older emails; the rest are sorted by the same rules when they move."
            left > 0 -> "${g(left)} more senders weren't listed for room; they're sorted by the same rules."
            else -> null
        }
        return JunkReview(
            cardId = card.id, title = card.title, summary = summary,
            rescues = rescues.map { r ->
                JunkRescueRow(r.name, listOfNotNull(r.domain, emails(r.count)).joinToString(" · "), r.why ?: "Looks like someone you know", r.subjects)
            },
            senders = trash.map { s ->
                val isKept = s.key in kept
                JunkReviewRow(
                    key = s.key, title = s.name, meta = listOfNotNull(s.domain, emails(s.count)).joinToString(" · "),
                    subjects = s.subjects, scam = s.scam != null, scamLine = s.scam?.let { scamLine(it) }, kept = isKept, count = s.count,
                    state = if (isKept) "Stays in Junk · you kept it" else "Going to Trash",
                )
            },
            staysLine = stays.takeIf { it > 0 }?.let { "${emails(it)} ${if (it == 1) "stays" else "stay"} for you to check: banks, schools and public bodies" },
            hint = HINT, partLine = part,
            approveLabel = if (going > 0) "Approve · ${g(going)} to Trash" else MailJunkClearRules.CARD_YES,
            canApprove = going > 0 || rescued > 0,
            note = NOTE,
        )
    }
}
