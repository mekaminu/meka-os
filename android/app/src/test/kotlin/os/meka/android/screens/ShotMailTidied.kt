package os.meka.android.screens

import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailTidiedEmail
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailTidy
import os.meka.core.sync.FieldValue

/**
 * Found and fixing week 5, A9 (the Mail tile's "71 promos" vs the sheet it opens, "8 promos tidied today" in one shot
 * and "3 promos" in another): the one day of tidied mail every Needs you mail shot shares. The tiles count these
 * (14 tidied = 3 newsletters + 8 promos + 2 junk & scams + 1 receipt), and All tidied mail lists the same eight promos,
 * so a tile's tap opens a list of the number it showed. Invented shops only (rule 13).
 */
object ShotMailTidied {
    const val NEWSLETTERS = 3
    const val PROMOS = 8
    const val JUNK = 2
    const val RECEIPTS = 1
    const val TOTAL = NEWSLETTERS + PROMOS + JUNK + RECEIPTS

    /** Today's one tidy, as the server writes it. */
    val kinds: Map<MailTidy, Int> = mapOf(
        MailTidy.NEWSLETTER to NEWSLETTERS, MailTidy.ARCHIVE to PROMOS, MailTidy.JUNK_TRASH to JUNK, MailTidy.RECEIPT to RECEIPTS,
    )

    /** The eight promos' senders and subjects, oldest first. */
    val shops: List<Pair<String, String>> = listOf(
        "Oakfield Shoes" to "Boots from £40 this weekend", "Sam's Deli" to "Christmas hampers are back",
        "Alex Books" to "Three for two on paperbacks", "Riverside Garden Centre" to "Bulbs half price",
        "Corner Cycles" to "Winter service, book now", "Hilltop Coffee" to "Your loyalty stamps",
        "Northgate Toys" to "Early Christmas deals", "Bramble Homeware" to "New candles in",
    )

    /** The sender's domain as an address gives it: "Sam's Deli" → "samsdeli.example", never a display name. */
    fun domain(from: String) = from.lowercase().filter { it.isLetterOrDigit() } + ".example"

    /**
     * The eight promos, oldest first, five minutes apart and the newest [newestMinsAgo] before [nowMs]. [names] replaces
     * the shops' (sender, subject) pairs where a test still reads placeholders.
     */
    fun promos(nowMs: Long, newestMinsAgo: Int = 60, names: List<Pair<String, String>> = shops): List<MailTidiedEmail> {
        require(names.size == PROMOS) { "the shots share $PROMOS promos" }
        return names.mapIndexed { i, (from, subject) ->
            MailTidiedEmail(
                MailTidiedRules.key("acc", "p${i + 1}"), MailTidy.ARCHIVE, MailSort.PROMO,
                nowMs - (newestMinsAgo + (PROMOS - 1 - i) * 5) * 60_000L, from, domain(from), subject, null,
            )
        }
    }

    /** Today's tidy at [atMs]: the counts above, with [emails] (the promos) listed. */
    fun fields(atMs: Long, emails: List<MailTidiedEmail>): Map<String, FieldValue> =
        MailAutopilotRules.batchFields(atMs, "Personal", kinds) + MailTidiedRules.fields(emails)
}
