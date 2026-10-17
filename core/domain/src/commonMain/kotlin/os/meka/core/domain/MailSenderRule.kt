package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, completeness (Meka, 2026-10-12: "per-sender always/never rules editable"): his own rule for one
 * sender, kept on its `mail_sender` entity ([MailSenderFields.RULE], [MailSenderFields.RULE_AT]; an app's, LWW;
 * absent or "" = no rule). Non-AI, pure, unit-tested.
 *
 * - [KEEP] — "never tidy": Autopilot, its dry run and the backlog clean-up leave this sender's mail in the inbox, and a
 *   later email after an unsubscribe isn't sent to Trash either. Offered on each newsletter and promo sender in Mail.
 * - [TIDY] — "always tidy": the sender's mail is archived as it arrives even when the rules weren't sure or sorted it as
 *   needing him (a receipt still as a receipt, a newsletter as a newsletter). The never-touch rules still win: never
 *   public bodies, schools, banks, his partner or a thread he wrote in; Junk stays for the daily review.
 * - [BLOCK] — "block sender" (2026-10-17, Meka: "I thought it would ask me if I wanted to block an email address"):
 *   the sender's mail goes to Trash as it arrives (recoverable, never deleted for good), with or without Autopilot on.
 *   The same never-touch rules win: never people he writes to, his partner, banks, schools, public bodies or
 *   account-security mail.
 *
 * Which address a rule is for stays on the server (the entity's id is a hash of the account and the address).
 */
enum class MailSenderRule(val line: String) {
    KEEP("Always kept in the inbox · Autopilot leaves its emails where they land"),
    TIDY("Always tidied · Autopilot archives its emails as they arrive"),
    BLOCK("Blocked · its emails go straight to Trash");

    companion object {
        fun of(stored: String?): MailSenderRule? = entries.firstOrNull { it.name == stored }
    }
}

object MailSenderRuleRules {
    /** On a sender's row in Mail. */
    const val KEEP_ACTION = "Keep in inbox"
    /** On a sender with a rule: takes it off (Autopilot goes back to its own rules). */
    const val CLEAR_ACTION = "Let Autopilot decide"

    /** The rule on a `mail_sender` entity's fields, whether or not the server has written its own fields yet. */
    fun read(fields: Map<String, FieldValue>?): MailSenderRule? =
        MailSenderRule.of((fields?.get(MailSenderFields.RULE) as? FieldValue.Text)?.value)

    /** What an app writes: the rule, or "" to take it off. */
    fun fields(rule: MailSenderRule?, nowMs: Long): Map<String, FieldValue> = mapOf(
        MailSenderFields.RULE to (rule?.name ?: "").fv(),
        MailSenderFields.RULE_AT to nowMs.fv(),
    )

    /** The line Mail's bar says after the tap. */
    fun setLine(name: String, rule: MailSenderRule?): String = when (rule) {
        MailSenderRule.KEEP -> "$name stays in the inbox · Autopilot won't tidy its emails"
        MailSenderRule.TIDY -> "Autopilot will tidy $name's emails as they arrive"
        MailSenderRule.BLOCK -> "$name is blocked · its emails go straight to Trash"
        null -> "Autopilot decides about $name's emails again"
    }

    /**
     * The plan for one email from a sender with [rule], after the never-touch checks ([MailTidyRules.plan] makes them
     * first): null means no rule, so the usual plan stands.
     */
    fun plan(rule: MailSenderRule?, sort: MailSort): MailTidy? = when (rule) {
        null -> null
        MailSenderRule.KEEP -> MailTidy.KEEP
        MailSenderRule.TIDY -> when (sort) {
            MailSort.NEWSLETTER -> MailTidy.NEWSLETTER
            MailSort.RECEIPT -> MailTidy.RECEIPT
            MailSort.JUNK -> MailTidy.KEEP
            MailSort.NEEDS_YOU, MailSort.FYI, MailSort.NOTIFICATION, MailSort.PROMO -> MailTidy.ARCHIVE
        }
        // Blocked: whatever it is, to Trash (recoverable for 30 days; Undo like any tidy).
        MailSenderRule.BLOCK -> MailTidy.TRASH
    }

    // ---- Slice 10b: Always tidy from a "From your mail" card, and "Your rules" in Mail ----

    /**
     * On a card in From your mail: the email is Done and its sender gets [MailSenderRule.TIDY]. Junk fix slice 2
     * (Meka, 2026-10-17: "what does Always tidy do?"): the label says what it does.
     */
    const val ALWAYS_TIDY = "Always tidy · archive their future mail"
    const val RULES_TITLE = "Your rules"

    /** What the bar says after Always tidy on a card from [from]: what happens from now on, and where to change it. */
    fun cardLine(from: String): String =
        "Done · $from's emails will skip your inbox and go to Archive · change it in Mail → Your rules"

    /**
     * Junk fix slice 2: **Block sender** on a card — this email to Trash and the sender's later mail too
     * ([MailSenderRule.BLOCK]). Not offered on account-security mail or a real company's notice (the server also
     * never blocks people he writes to, his partner, banks, schools or public bodies; those are only archived).
     */
    const val BLOCK = "Block sender"
    /** On a possible scam: the same block, said for what it is. */
    const val BIN = "Bin & block"

    fun blockLine(from: String): String =
        "Blocked $from · this email and their later ones go to Trash · Undo, or change it in Mail → Your rules"

    fun binLine(from: String): String =
        "Binned · $from is blocked, their later emails go straight to Trash · Undo brings it back"

    /** Can Block (or Bin & block on a scam) be offered on [row]? Not on security mail or a real company's own notice. */
    fun canBlock(row: MailNeedsRow): Boolean = row.security == null && (row.warning == null || row.scam)

    /**
     * What an app writes on the card's `mail_item`: a Done that also asks for the sender's rule ([rule]: TIDY or
     * BLOCK; Always tidy before slice 2 wrote no [MailItemFields.RULE_ASKED], which reads as TIDY).
     */
    fun cardFields(nowMs: Long, rule: MailSenderRule = MailSenderRule.TIDY): Map<String, FieldValue> =
        MailNeedsRules.doneFields(nowMs) + (MailItemFields.TIDY_ASKED_AT to nowMs.fv()) + (MailItemFields.RULE_ASKED to rule.name.fv())

    /** The rule a card asked for (TIDY when the card predates Block). */
    fun asked(i: MailItem): MailSenderRule = MailSenderRule.of(i.ruleAsked)?.takeIf { it != MailSenderRule.KEEP } ?: MailSenderRule.TIDY

    /** Where the server moves the card's own email once it's Done: Trash for a block of a sender it may block. */
    fun cardTidy(i: MailItem, protected: Boolean): MailTidy =
        if (i.tidyAskedAtMs != null && MailNeedsRules.done(i) && asked(i) == MailSenderRule.BLOCK && !protected && i.security == null) MailTidy.TRASH
        else MailTidy.ARCHIVE

    /**
     * Junk fix slice 2: the inbox emails from blocked senders to Trash this check — arrived after the block ([ruleAt]
     * by lower-cased address) and that [MailTidyRules.plan] would move with the BLOCK rule (so never the never-touch
     * senders or security mail). [planOf] is the tidy plan of one email with its sender's rule.
     */
    fun <H> blockedDue(inbox: List<H>, address: (H) -> String?, receivedAt: (H) -> Long, ruleAt: Map<String, Long>, planOf: (H) -> MailTidy): List<H> =
        inbox.filter { h ->
            val at = address(h)?.lowercase()?.let { ruleAt[it] } ?: return@filter false
            receivedAt(h) >= at && planOf(h) == MailTidy.TRASH
        }

    /** The server should write the sender's always-tidy rule now: asked on the card, still Done, not written for this ask. */
    fun ruleDue(i: MailItem): Boolean {
        val asked = i.tidyAskedAtMs ?: return false
        return MailNeedsRules.done(i) && (i.doneAskedAtMs ?: 0L) >= asked && (i.tidyRuleAtMs == null || i.tidyRuleAtMs < asked)
    }

    /**
     * The server should take the rule off again: it wrote it for this ask, then Undo brought the card back (an Undo
     * before the server acted needs nothing), and it hasn't taken it off since.
     */
    fun clearDue(i: MailItem): Boolean {
        val asked = i.tidyAskedAtMs ?: return false
        val wrote = i.tidyRuleAtMs ?: return false
        val undo = i.undoAskedAtMs ?: return false
        return wrote >= asked && undo > asked && !MailNeedsRules.done(i) && (i.tidyClearedAtMs == null || i.tidyClearedAtMs < wrote)
    }

    /**
     * What the server writes on the sender for an Always tidy asked at [askedAtMs]: the rule, and — when the sender has
     * no entity yet (a person, not a newsletter) — the entity itself (its account label, name and domain, never the
     * address). Null when he set a rule on that sender after the tap ([current] is the sender's fields), which stands.
     */
    fun senderFieldsForCard(
        current: Map<String, FieldValue>?, account: String, fromName: String?, fromAddress: String?, lastAtMs: Long, askedAtMs: Long,
        rule: MailSenderRule = MailSenderRule.TIDY,
    ): Map<String, FieldValue>? {
        val ruleAt = (current?.get(MailSenderFields.RULE_AT) as? FieldValue.Int64)?.value ?: 0L
        if (ruleAt > askedAtMs) return null
        val base = if (current?.get(MailSenderFields.NAME) == null) {
            MailUnsubscribeRules.senderFields(
                account, MailUnsubscribeRules.displayName(fromName, fromAddress), MailSortRules.domain(fromAddress),
                UnsubscribeMethod.NONE, lastAtMs, 1,
            )
        } else emptyMap()
        return base + fields(rule, askedAtMs)
    }

    /**
     * After an Undo on the card: the rule comes off only while it's still the one the card set (same time); a rule he
     * set since stands. Null to leave it.
     */
    fun clearFieldsForCard(
        current: Map<String, FieldValue>?, askedAtMs: Long, nowMs: Long, rule: MailSenderRule = MailSenderRule.TIDY,
    ): Map<String, FieldValue>? {
        if (read(current) != rule) return null
        val ruleAt = (current?.get(MailSenderFields.RULE_AT) as? FieldValue.Int64)?.value ?: return null
        if (ruleAt != askedAtMs) return null
        return fields(null, nowMs)
    }

    /** What the server writes on the card once it wrote the rule, or took it off. */
    fun ruleWrittenFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.TIDY_RULE_AT to nowMs.fv())
    fun ruleClearedFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.TIDY_CLEARED_AT to nowMs.fv())

    /** One account's senders that carry his rule, by name: each with [CLEAR_ACTION]. */
    fun rows(senders: List<MailSender>, account: String): List<MailRuleRow> =
        senders.filter { it.account == account && it.rule != null }
            .sortedWith(compareBy<MailSender> { it.name.lowercase() }.thenBy { it.id })
            .map { s ->
                val rule = s.rule!!
                MailRuleRow(
                    id = s.id, name = s.name, line = rule.line, rule = rule.name,
                    spoken = "${s.name}. ${rule.line}",
                )
            }

    /** "Your rules · 2 senders" over the list; null with none. */
    fun header(rows: List<MailRuleRow>): String? = when (rows.size) {
        0 -> null
        1 -> "$RULES_TITLE · 1 sender"
        else -> "$RULES_TITLE · ${rows.size} senders"
    }
}

/**
 * Junk fix slice 2 (Meka, 2026-10-17): **Unsubscribe** on an email in From your mail, offered when the email carries a
 * usable List-Unsubscribe ([MailItemFields.CAN_UNSUB]). The tap is a Done too (the email is archived); the server
 * unsubscribes the sender its own way (one-click link or the sender's unsubscribe address, as Mail's Unsubscribe does)
 * at its next check and writes what came of it. An Undo before then stops it; once done it can't be taken back.
 */
object MailCardUnsubRules {
    const val UNSUBSCRIBE = "Unsubscribe"

    fun askFields(nowMs: Long): Map<String, FieldValue> = MailNeedsRules.doneFields(nowMs) + (MailItemFields.UNSUB_ASKED_AT to nowMs.fv())

    /** Undo: the card comes back; an unsubscribe the server hasn't tried yet is called off. */
    fun undoFields(nowMs: Long): Map<String, FieldValue> = MailNeedsRules.undoDoneFields(nowMs) + (MailItemFields.UNSUB_ASKED_AT to 0L.fv())

    /** The server should unsubscribe now: asked and not tried since. */
    fun due(i: MailItem): Boolean {
        val asked = i.unsubAskedAtMs ?: return false
        return i.unsubTriedAtMs == null || i.unsubTriedAtMs < asked
    }

    fun triedFields(note: String, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailItemFields.UNSUB_TRIED_AT to nowMs.fv(), MailItemFields.UNSUB_NOTE to note.fv())

    fun line(from: String): String = "Unsubscribing from $from at Navilife's next check · this email archived · Undo stops it"
}

/** Slice 10b: one sender under "Your rules" in Mail. */
data class MailRuleRow(
    val id: String,
    /** "Sam Taylor" or "The Weekly Digest". */
    val name: String,
    /** "Always tidied · Autopilot archives its emails as they arrive". */
    val line: String,
    /** The rule's name ("KEEP" / "TIDY"). */
    val rule: String,
    val spoken: String,
    /** Takes it off: "Let Autopilot decide", which sets "" (a plain string, so both apps pass it as is). */
    val action: String = MailSenderRuleRules.CLEAR_ACTION,
    val sets: String = "",
)
