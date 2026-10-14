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
 *
 * Which address a rule is for stays on the server (the entity's id is a hash of the account and the address).
 */
enum class MailSenderRule(val line: String) {
    KEEP("Always kept in the inbox · Autopilot leaves its emails where they land"),
    TIDY("Always tidied · Autopilot archives its emails as they arrive");

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
    }
}
