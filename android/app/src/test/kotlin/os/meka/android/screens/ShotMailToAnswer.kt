package os.meka.android.screens

import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.FieldValue

/**
 * Found and fixing week 5, A8 (Life audit): the one morning of mail to answer every mail shot shares. Needs you's Mail to
 * answer box draws these seven as its rows ("1–5 of 7"), and the Mail shots' phone holds the same seven, synced from
 * the stand-in server, so Mail's Life audit reads "7 emails waiting on you" instead of "Nothing needs you from 60 days of
 * mail ✓" beside them. Invented names only (rule 13). Wed 21 Oct 2026, the morning before 17:15 in London.
 */
object ShotMailToAnswer {
    data class Email(val from: String, val subject: String, val time: String, val scam: Boolean = false) {
        /** Minutes before 17:15 the email arrived. */
        val minutesBefore: Int get() = 17 * 60 + 15 - (time.take(2).toInt() * 60 + time.takeLast(2).toInt())
    }

    const val SCAM_WARNING = "⚠ Possible scam · sent from mail-x.com, not paypal.com"

    // Real-looking subjects and a morning's times, newest first (walk-through A22: "Subject 0…4" at 00:12…04:12).
    val emails = listOf(
        Email("Sam Taylor", "Are you free for lunch on Friday?", "09:41"),
        Email("PayPal", "Your account has been limited", "09:12", scam = true),
        Email("Oakfield School", "Year 3 trip to the museum: consent form", "08:55"),
        Email("Oakfield School", "Parents' evening booking is open", "08:30"),
        Email("Council", "Your council tax bill for 2026/27", "08:02"),
        Email("Dentist", "Time for your check-up", "07:46"),
        Email("Energy Co", "Your new tariff starts next month", "07:15"),
    )

    val size: Int get() = emails.size

    /** Needs you's rows for the box shot, as the core would build them. */
    fun rows(): List<MailNeedsRow> = emails.mapIndexed { i, e ->
        MailNeedsRow(
            id = "m$i", from = e.from, line = "Personal · ${e.time}", subject = e.subject, preview = "Hi Alex, the card ending 1234",
            business = false, spoken = "${e.from}, ${e.subject}", replyLine = "To ${e.from}", scam = e.scam,
            warning = if (e.scam) SCAM_WARNING else null, time = e.time,
        )
    }

    /** The same seven as the server writes them for a phone at [nowMs]: (item id, its fields). */
    fun serverItems(nowMs: Long, account: String = "Personal"): List<Pair<String, Map<String, FieldValue>>> =
        emails.mapIndexed { i, e ->
            MailNeedsRules.itemId("acc1", "answer$i") to MailNeedsRules.serverFields(
                account, e.from, if (e.scam) "alerts@mail-x.com" else null, e.subject, "Hi Alex, a line or two",
                nowMs - e.minutesBefore * 60_000L, 1, warning = if (e.scam) SCAM_WARNING else "",
            )
        }
}
