package os.meka.core.domain

/**
 * Taps feel instant (Meka 2026-10-25), slice 20: the answers on the mail cards in Needs you — From your mail's Done,
 * Task, To a day, Waiting for, Snooze, Always tidy, Block and Unsubscribe; Time to chase's Got it and Not yet; the
 * finds' Add and Not needed; Got it on Archived for you — take the row (or the card) away in the same frame on both
 * apps, while the core's write follows. The ids decided here are held in a set the cards are drawn through, forgotten
 * once the core's card no longer holds them ([settle]), and given back on a failure or an Undo.
 *
 * A refused answer (the core says it couldn't find the email, chase or find) is a failure too: the row comes back with
 * the core's own line.
 */
object InstantMailRules {
    /** Archived for you's card is hidden by a key made from what it shows, so a newer digest still shows. */
    fun archivedKey(card: ArchivedForYouCard): String =
        ARCHIVED_PREFIX + card.slot + ":" + card.lines.joinToString(",") { it.id }

    /** From your mail as drawn: the rows not answered here yet; null once every row it showed is answered. */
    fun needs(card: MailNeedsCard?, hidden: Set<String>): MailNeedsCard? {
        if (card == null || hidden.isEmpty()) return card
        val rows = card.rows.filter { it.id !in hidden }
        if (rows.size == card.rows.size) return card
        return if (rows.isEmpty()) null else card.copy(rows = rows)
    }

    /** Time to chase as drawn. */
    fun chases(card: MailChaseCard?, hidden: Set<String>): MailChaseCard? {
        if (card == null || hidden.isEmpty()) return card
        val rows = card.rows.filter { it.id !in hidden }
        if (rows.size == card.rows.size) return card
        return if (rows.isEmpty()) null else card.copy(rows = rows)
    }

    /** The finds from his mail (renewals, promises, follow-ups, school, refunds) as drawn. */
    fun finds(card: MailFindCard?, hidden: Set<String>): MailFindCard? {
        if (card == null || hidden.isEmpty()) return card
        val rows = card.rows.filter { it.id !in hidden }
        if (rows.size == card.rows.size) return card
        return if (rows.isEmpty()) null else card.copy(rows = rows)
    }

    /** Archived for you as drawn: gone once its Got it was tapped here. */
    fun archived(card: ArchivedForYouCard?, hidden: Set<String>): ArchivedForYouCard? =
        if (card == null || hidden.isEmpty() || archivedKey(card) !in hidden) card else null

    /** Every id the core's cards still hold: what the decided set keeps (the rest landed). */
    fun present(
        needs: MailNeedsCard?, chases: MailChaseCard?, finds: MailFindCard?, archived: ArchivedForYouCard?,
    ): List<String> = buildList {
        needs?.rows?.forEach { add(it.id) }
        chases?.rows?.forEach { add(it.id) }
        finds?.rows?.forEach { add(it.id) }
        archived?.let { add(archivedKey(it)) }
    }

    /** Whether the core's line is a refusal (the email, chase or find wasn't found): the row comes back. */
    fun refused(line: String?): Boolean =
        line == null || line == MailNeedsRules.UNKNOWN || line == MailChaseRules.UNKNOWN || line == MailRenewalRules.UNKNOWN

    const val ARCHIVED_PREFIX = "archived-for-you:"
}
