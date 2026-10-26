package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Money redesign (B), slice B3 (mockup v4, approved by Meka 2026-10-25 09:30): **the Bills tab's own card and list**.
 *
 * The card says what's still to go out before payday (or before the month ends while no payday is found) as one big
 * number with how many bills, a thin bar of how much of the month's bills are already paid, and "£672 of £1,082 this
 * month already paid". Under it every bill of the month by date: those still to come first ("OCT 28 · Sky · Monthly ·
 * £153.48"), then the ones already paid this month, greyed ("Paid · Fri 2 Oct").
 *
 * The bills are what [MoneyPayday] already counts (so the card's number is the Spending tab's "Bills to come" tile):
 * the regular payments found from his payments ([MoneyTips.regular]), the bills with a cost on Lists → Renewals and the
 * cards' statements due. Rules only, worked out on the device; Navilife never pays or moves anything.
 */
enum class MoneyBillKind { REGULAR, RENEWAL, CARD }

/** One bill of the month: [month] "OCT" and [day] "28" for the date block; [subLit] lights a bill due today or tomorrow. */
data class MoneyBillRow(
    val key: String, val month: String, val day: String, val title: String, val sub: String, val subLit: Boolean,
    val amount: String, val paid: Boolean, val spoken: String,
)

/**
 * The Bills tab's card and list: [head] "Still to go out before payday · Fri 30 Oct", [amount] "£410", [count] "3 bills",
 * [paidShare] (0–1) of the month's bills already paid for the bar, [paidLine] "£672 of £1,082 this month already paid"
 * (null before anything is paid), the [rows] and the [foot].
 */
data class MoneyBillsView(
    val head: String, val amount: String, val count: String, val paidShare: Float, val paidLine: String?,
    val rows: List<MoneyBillRow>, val foot: String, val spoken: String,
)

object MoneyBills {
    const val FOOT = "Found from payments that repeat each month, Lists → Renewals and your cards' due dates · not financial advice"
    const val NOTHING = "nothing left"
    /** At most this many rows (to come first); the rest are rare. */
    const val SHOWN = 12

    /** One bill: what it is, when, how much, and whether it's already paid this month. */
    data class Due(val key: String, val title: String, val day: Long, val pence: Long, val kind: MoneyBillKind, val rise: Long = 0L, val paid: Boolean = false)

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Pence rounded to whole pounds. */
    private fun pounds(p: Long): Long = (p / 100.0).roundToLong() * 100

    /** The first day of [today]'s month. */
    fun monthStart(today: Long): Long = CivilDate.fromEpochDay(today).let { CivilDate.toEpochDay(it.year, it.month, 1) }

    /** The last day of [today]'s month. */
    fun monthEnd(today: Long): Long = CivilDate.fromEpochDay(today).let { CivilDate.toEpochDay(it.year, it.month, CivilDate.lengthOfMonth(it.year, it.month)) }

    /** The regular payments already taken this month, as paid bills. */
    fun paidThisMonth(regular: List<MoneyTips.Regular>, today: Long): List<Due> {
        val start = monthStart(today)
        return regular.filter { it.lastDay in start..today }
            .map { Due("p-${it.key}", it.label, it.lastDay, it.monthly, MoneyBillKind.REGULAR, paid = true) }
    }

    /** "Monthly", "Monthly · went up £1.50", "Card statement", "From Lists → Renewals", with "Due tomorrow · " leading. */
    fun sub(d: Due, today: Long): String {
        if (d.paid) return "Paid · ${CivilDate.shortLabel(d.day)}"
        val what = when (d.kind) {
            MoneyBillKind.REGULAR -> if (d.rise > 0) "Monthly · went up ${fmt(d.rise)}" else "Monthly"
            MoneyBillKind.RENEWAL -> "From Lists → Renewals"
            MoneyBillKind.CARD -> "Card statement"
        }
        return when (d.day - today) {
            0L -> "Due today · $what"
            1L -> "Due tomorrow · $what"
            else -> what
        }
    }

    /**
     * The card and list from the bills still [toCome] (before [until]) and those [paid] this month. [untilLabel] names
     * payday ("Fri 30 Oct"), or null when no payday is found (then it's "before the month ends").
     */
    fun view(toCome: List<Due>, paid: List<Due>, untilLabel: String?, today: Long): MoneyBillsView? {
        if (toCome.isEmpty() && paid.isEmpty()) return null
        val coming = toCome.sortedWith(compareBy({ it.day }, { it.title }))
        val done = paid.sortedWith(compareByDescending<Due> { it.day }.thenBy { it.title })
        val toGo = coming.sumOf { it.pence }
        val paidPence = done.sumOf { it.pence }
        val all = toGo + paidPence
        val head = if (untilLabel != null) "Still to go out before payday · $untilLabel" else "Still to go out before the month ends"
        val n = coming.size
        val count = if (n == 0) NOTHING else "$n bill${if (n == 1) "" else "s"}"
        val paidLine = if (paidPence > 0) "${fmt(pounds(paidPence))} of ${fmt(pounds(all))} this month already paid" else null
        val rows = (coming + done).take(SHOWN).map { d ->
            val date = CivilDate.fromEpochDay(d.day)
            val sub = sub(d, today)
            MoneyBillRow(
                key = d.key, month = Recurrence.MONTH_SHORT[date.month - 1].uppercase(), day = date.day.toString(),
                title = d.title, sub = sub, subLit = !d.paid && d.day - today in 0L..1L, amount = fmt(d.pence), paid = d.paid,
                spoken = "${d.title}, ${CivilDate.shortLabel(d.day)}, ${fmt(d.pence)}, $sub",
            )
        }
        val spoken = "$head, ${fmt(pounds(toGo))}, $count" + (paidLine?.let { ", $it" } ?: "")
        return MoneyBillsView(
            head, fmt(pounds(toGo)), count, if (all > 0) (paidPence.toFloat() / all).coerceIn(0f, 1f) else 0f, paidLine,
            rows, FOOT, spoken,
        )
    }

    /**
     * No payday found yet: every account's regular payments and the radar's and cards' bills still to come before the
     * month ends (from tomorrow, or today when nothing came in today yet), and the regular payments already taken.
     */
    fun forMonth(regular: List<MoneyTips.Regular>, bills: List<MoneyBill>, cardTitles: Set<String>, today: Long): MoneyBillsView? {
        val end = monthEnd(today)
        val reg = regular.filter { it.nextDay in today..end }
        val radar = bills.filter { it.pence > 0 && it.dueDay in today..end && reg.none { r -> MoneyPayday.sameAs(it, r) } }
        val toCome = reg.map { Due("r-${it.key}", it.label, it.nextDay, it.monthly, MoneyBillKind.REGULAR, it.rise) } +
            radar.map { b -> Due("b-${b.title.lowercase()}-${b.dueDay}", b.title, b.dueDay, b.pence, if (b.title in cardTitles) MoneyBillKind.CARD else MoneyBillKind.RENEWAL) }
        return view(toCome, paidThisMonth(regular, today), null, today)
    }
}
