package os.meka.core.domain

import os.meka.core.sync.FieldValue

/*
 * Life audit, slice 2 (build plan; non-AI, pure): **the audit as one review, on demand**. Mail → Life audit (both apps)
 * opens one card that adds up what 60 days of his mail turned up, from what the apps already hold (nothing is read again,
 * nothing reaches the AI):
 *
 * - **What was read**: "60 days of your Personal and Business mail · sender, subject and the short preview, never the
 *   whole email · nothing in your mail was changed".
 * - **Subscriptions**: on Renewals plus the ones found and not yet answered, and what they cost a month together.
 * - **Emails waiting on you**: how many still need him, how many need a reply and from whom, the oldest's day.
 * - **Waiting on others**: Waiting for's open items, plus promises and emails with no reply yet still to add.
 * - **Warranties**: how many are on Renewals and the next to end.
 * - **Bills**: what's due in the next 30 days, what it adds up to, and the next.
 *
 * Every line says where it lives (Needs you, Lists → Renewals, Lists → Waiting for); the review itself only counts, so
 * it never acts on anything. Every name in it is someone else's word (ADR-006): shown, never followed.
 */

/** One line of the review: [key] one of [LifeAuditReviewRules.KEYS]. */
data class LifeAuditReviewLine(
    val key: String,
    val title: String,
    /** "2 on Renewals · 1 found to add · about £34.97 a month". */
    val line: String,
    /** Where it lives: "Lists → Renewals". */
    val place: String,
    /** Nothing to report (shown quieter). */
    val clear: Boolean,
    val spoken: String,
)

/** The review: its title, what was read, the lines, and the foot that says nothing happens on its own. */
data class LifeAuditReview(
    val title: String,
    /** The closed card's line: "3 subscriptions · 4 emails waiting on you · 2 bills due". */
    val summary: String,
    val read: String,
    val lines: List<LifeAuditReviewLine>,
    val foot: String,
    val spoken: String,
)

object LifeAuditReviewRules {
    const val TITLE = "Life audit"
    const val OPEN = "Review"
    const val CLOSE = "Hide"
    const val FOOT = "Each finding waits for your tap · Navilife never pays, cancels or replies on its own"
    /** Bills counted this far ahead. */
    const val BILL_DAYS = 30
    /** Names on the waiting-on-you line, at most. */
    const val NAMES = 2

    const val KEY_SUBSCRIPTIONS = "subscriptions"
    const val KEY_ON_YOU = "on-you"
    const val KEY_ON_OTHERS = "on-others"
    const val KEY_WARRANTIES = "warranties"
    const val KEY_BILLS = "bills"
    val KEYS = listOf(KEY_SUBSCRIPTIONS, KEY_ON_YOU, KEY_ON_OTHERS, KEY_WARRANTIES, KEY_BILLS)

    private fun plural(n: Int, one: String, many: String = one + "s"): String = "$n " + if (n == 1) one else many

    /** "Personal and Business", "Personal, Business and Outlook". */
    fun accountsLabel(accounts: List<String>): String = when (accounts.size) {
        0 -> ""
        1 -> accounts[0]
        else -> accounts.dropLast(1).joinToString(", ") + " and " + accounts.last()
    }

    /**
     * The accounts the review names: the connected ones' labels as the server last listed them ([facts], null before
     * the Mail pane asked) and any account the synced mail carries ([seen]), Personal, Business, Outlook first.
     */
    fun accounts(facts: List<MailAccountFacts>?, seen: List<String>): List<String> {
        val all = ((facts?.mapNotNull { MailLabel.of(it.label)?.label } ?: emptyList()) + seen).filter { it.isNotBlank() }.distinct()
        return all.sortedBy { l -> MailLabel.entries.indexOfFirst { it.label == l }.let { if (it < 0) MailLabel.entries.size else it } }
    }

    fun readLine(accounts: List<String>): String =
        "${LifeAuditRules.WINDOW_DAYS} days of your " + accountsLabel(accounts) + " mail" +
            " · sender, subject and the short preview, never the whole email · nothing in your mail was changed"

    /** "Alex Green", "Alex Green and Sam Taylor", "Alex Green, Sam Taylor and 2 more". */
    fun names(who: List<String>): String {
        val shown = who.take(NAMES)
        val more = who.size - shown.size
        return when {
            more > 0 -> shown.joinToString(", ") + " and $more more"
            shown.size == 2 -> shown[0] + " and " + shown[1]
            else -> shown.joinToString()
        }
    }

    /** What [r] costs a month, in pence (a yearly one's twelfth, a quarterly one's third); null when its price isn't known. */
    fun monthlyPence(pence: Long?, repeat: RenewalRepeat?): Long? = pence?.let {
        when (repeat) {
            RenewalRepeat.YEARLY -> (it + 6) / 12
            RenewalRepeat.HALF_YEARLY -> (it + 3) / 6
            RenewalRepeat.QUARTERLY -> (it + 1) / 3
            RenewalRepeat.MONTHLY -> it
            else -> null
        }
    }

    fun subscriptionsLine(onRadar: List<RenewalItem>, found: List<MailFind>): LifeAuditReviewLine {
        val title = "Subscriptions"
        val place = if (found.isEmpty()) "Lists → Renewals" else "Needs you · Lists → Renewals"
        if (onRadar.isEmpty() && found.isEmpty()) {
            val line = "None found in your mail"
            return LifeAuditReviewLine(KEY_SUBSCRIPTIONS, title, line, place, true, "$title. $line.")
        }
        val monthly = onRadar.sumOf { monthlyPence(it.costPence, it.repeats) ?: 0L } +
            found.sumOf { LifeAuditRules.monthlyPence(it.pence ?: 0L, it.repeat) }
        val parts = listOfNotNull(
            onRadar.takeIf { it.isNotEmpty() }?.let { "${it.size} on Renewals" },
            found.takeIf { it.isNotEmpty() }?.let { "${it.size} found to add" },
            if (monthly > 0) "about ${RenewalRules.formatPence(monthly)} a month" else null,
        )
        val line = parts.joinToString(" · ")
        return LifeAuditReviewLine(KEY_SUBSCRIPTIONS, title, line, place, false, "$title. $line.")
    }

    fun onYouLine(showing: List<MailItem>, today: Long, cal: LocalCalendar): LifeAuditReviewLine {
        val title = "Emails waiting on you"
        val place = "Needs you"
        if (showing.isEmpty()) {
            val line = "None · you're up to date"
            return LifeAuditReviewLine(KEY_ON_YOU, title, line, place, true, "$title. $line.")
        }
        val reply = showing.filter { it.replyNeeded }
        val who = reply.sortedBy { it.receivedAtMs }.map { it.from.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val oldest = cal.epochDayOf(showing.minOf { it.receivedAtMs })
        val line = listOfNotNull(
            plural(showing.size, "email"),
            reply.takeIf { it.isNotEmpty() }?.let {
                (if (it.size == 1) "1 needs a reply" else "${it.size} need a reply") + (if (who.isNotEmpty()) ", from " + names(who) else "")
            },
            "oldest " + RenewalRules.dayWord(oldest, today),
        ).joinToString(" · ")
        return LifeAuditReviewLine(KEY_ON_YOU, title, line, place, false, "$title. $line.")
    }

    fun onOthersLine(waiting: List<WaitingItem>, promises: List<MailFind>, followUps: List<MailFind>): LifeAuditReviewLine {
        val title = "Waiting on others"
        val toAdd = promises.size + followUps.size
        val place = if (toAdd == 0) "Lists → Waiting for" else "Needs you · Lists → Waiting for"
        if (waiting.isEmpty() && toAdd == 0) {
            val line = "Nobody owes you anything from your mail"
            return LifeAuditReviewLine(KEY_ON_OTHERS, title, line, place, true, "$title. $line.")
        }
        val line = listOfNotNull(
            waiting.takeIf { it.isNotEmpty() }?.let { "${it.size} on Waiting for" },
            promises.takeIf { it.isNotEmpty() }?.let { plural(it.size, "promise") + " to add" },
            followUps.takeIf { it.isNotEmpty() }?.let { (if (it.size == 1) "1 email" else "${it.size} emails") + " with no reply yet" },
        ).joinToString(" · ")
        return LifeAuditReviewLine(KEY_ON_OTHERS, title, line, place, false, "$title. $line.")
    }

    fun warrantiesLine(warranties: List<RenewalItem>, today: Long): LifeAuditReviewLine {
        val title = "Warranties"
        val place = "Lists → Renewals"
        val live = warranties.filter { it.dueDay >= today }.sortedBy { it.dueDay }
        if (live.isEmpty()) {
            val line = "None on Renewals · orders over the Mail setting's price add theirs"
            return LifeAuditReviewLine(KEY_WARRANTIES, title, line, place, true, "$title. None on Renewals.")
        }
        val next = live.first()
        val name = next.title.removePrefix("Warranty ends · ").trim().ifEmpty { next.title }
        val line = plural(live.size, "warranty", "warranties") + " · the next ends " + RenewalRules.dayWord(next.dueDay, today) + " · " + name
        return LifeAuditReviewLine(KEY_WARRANTIES, title, line, place, false, "$title. $line.")
    }

    fun billsLine(bills: List<RenewalItem>, today: Long): LifeAuditReviewLine {
        val title = "Bills"
        val place = "Lists → Renewals"
        val due = bills.filter { it.dueDay <= today + BILL_DAYS }.sortedBy { it.dueDay }
        if (due.isEmpty()) {
            val line = "Nothing due in the next $BILL_DAYS days"
            return LifeAuditReviewLine(KEY_BILLS, title, line, place, true, "$title. $line.")
        }
        val total = due.mapNotNull { it.costPence }.sum()
        val next = due.first()
        val nextWord = if (next.dueDay < today) "the first was due " else "the next "
        val line = listOfNotNull(
            plural(due.size, "bill") + " due in the next $BILL_DAYS days",
            if (total > 0) RenewalRules.formatPence(total) + (if (due.any { it.costPence == null }) " known" else "") else null,
            nextWord + RenewalRules.dayWord(next.dueDay, today) + " · " + next.title,
        ).joinToString(" · ")
        return LifeAuditReviewLine(KEY_BILLS, title, line, place, false, "$title. $line.")
    }

    /** "3 subscriptions · 4 emails waiting on you · 2 bills due", or "Nothing needs you from 60 days of mail ✓". */
    fun summary(subs: Int, onYou: Int, bills: Int, toAdd: Int): String {
        val parts = listOfNotNull(
            subs.takeIf { it > 0 }?.let { plural(it, "subscription") },
            onYou.takeIf { it > 0 }?.let { (if (it == 1) "1 email" else "$it emails") + " waiting on you" },
            toAdd.takeIf { it > 0 }?.let { "$it to add" },
            bills.takeIf { it > 0 }?.let { plural(it, "bill") + " due" },
        )
        return if (parts.isEmpty()) "Nothing needs you from ${LifeAuditRules.WINDOW_DAYS} days of mail ✓" else parts.joinToString(" · ")
    }

    /**
     * The review, or null when no mail is connected ([accounts] empty): [accounts] the connected accounts' labels in
     * order, [items] the emails the server sorted, [finds] every mail find, [renewals] the radar's items, [waiting]
     * Waiting for's open items, [obligations] the radar's raw fields (for the finds' own showable rules).
     */
    fun review(
        accounts: List<String>,
        items: List<MailItem>,
        finds: List<MailFind>,
        renewals: List<RenewalItem>,
        waiting: List<WaitingItem>,
        obligations: Collection<Map<String, FieldValue>>,
        waits: List<FollowUpWait>,
        nowMs: Long,
        cal: LocalCalendar,
    ): LifeAuditReview? {
        if (accounts.isEmpty()) return null
        val today = cal.epochDayOf(nowMs)
        val subsFound = finds.filter { LifeAuditRules.showable(it, finds, obligations, today, cal) }
        val subsOn = renewals.filter { it.kind == ObligationKind.SUBSCRIPTION }
        val showing = items.filter { MailNeedsRules.showing(it, nowMs) }
        val promises = finds.filter { MailPromiseRules.showable(it, waiting, today) }
        val followUps = finds.filter { MailFollowUpRules.showable(it, waiting, today, waits) }
        val warranties = renewals.filter { it.kind == ObligationKind.WARRANTY }
        val bills = renewals.filter { BillRules.isBill(it) }
        val lines = listOf(
            subscriptionsLine(subsOn, subsFound),
            onYouLine(showing, today, cal),
            onOthersLine(waiting, promises, followUps),
            warrantiesLine(warranties, today),
            billsLine(bills, today),
        )
        val billsDue = bills.count { it.dueDay <= today + BILL_DAYS }
        val summary = summary(subsOn.size + subsFound.size, showing.size, billsDue, promises.size + followUps.size)
        val read = readLine(accounts)
        val spoken = "$TITLE. $summary. Read: $read. " + lines.joinToString(" ") { it.spoken } + " $FOOT."
        return LifeAuditReview(TITLE, summary, read, lines, FOOT, spoken)
    }
}
