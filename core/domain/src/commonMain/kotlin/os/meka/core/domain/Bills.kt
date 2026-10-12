package os.meka.core.domain

/**
 * Bills due (build plan V1, slice 1: from the radar). A bill is an ordinary radar item ([Renewals], an `obligation`)
 * of kind BILL, or any other item Meka has given a pay link (a subscription he pays by hand), except the car's dates and
 * the money dates, which have their own reminders. A few days before each payment, at 09:00, one heads-up with the
 * amount and, when Meka saved where he pays it, a **Pay** button that opens that page:
 * "Council tax due Thu 15 Oct · £180" · "In 3 days · Pay opens centralbedfordshire.gov.uk".
 *
 * **MEKA never pays anything.** It only reminds and opens the page Meka typed; marking it Paid stays his tap in Lists.
 * The radar's digest line leaves bills to this reminder until one is overdue. Non-AI, pure; nothing leaves the device.
 * Bill emails (the amount and the link read from the email) wait on mail access (Needs Meka #4).
 */
object BillRules {
    /** The reminder comes this many days before the due day. */
    const val REMIND_DAYS = 3
    /** At 09:00. */
    const val NOTICE_MIN = 9 * 60

    /** Whether a radar item gets the bill reminder (a bill, or anything with a pay link, but not the car or money dates). */
    fun isBill(item: RenewalItem): Boolean =
        (item.kind == ObligationKind.BILL || item.payLink != null) && !CarRules.isCar(item) && !MoneyDateRules.isMoney(item)

    /** The site a pay link opens, for the words: "https://www.octopus.energy/dashboard" → "octopus.energy". */
    fun site(link: String): String =
        link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .lowercase().removePrefix("www.")

    /** The row's button: "Pay on octopus.energy ↗". */
    fun payLabel(link: String): String = "Pay on ${site(link)} ↗"

    /** The line under the pay-link field. */
    fun linkLine(item: RenewalItem, bad: Boolean): String = when {
        bad -> "That isn't a web address · try octopus.energy"
        item.payLink != null -> "The reminder ${REMIND_DAYS} days before opens ${site(item.payLink)} · MEKA never pays"
        else -> "Where you pay it (optional) · the reminder can open it"
    }

    /**
     * The reminders: for each bill due within [REMIND_DAYS], one heads-up at 09:00 [REMIND_DAYS] before (at once when it
     * was added later), standing until its day ends. Pay opens the saved link; without one the words say where to mark
     * it Paid. Each due day posts once.
     */
    fun notices(items: List<RenewalItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.filter(::isBill).mapNotNull { item ->
            val left = (item.dueDay - today).toInt()
            if (left !in 0..REMIND_DAYS) return@mapNotNull null
            val inWords = when (left) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> "In $left days"
            }
            val dayWord = when (left) {
                0 -> "today"
                1 -> "tomorrow"
                else -> CivilDate.shortLabel(item.dueDay)
            }
            val link = item.payLink
            Notice(
                key = "bill:${item.id}:${item.dueDay}", source = NoticeSource.BILLS, tier = NoticeTier.HEADS_UP,
                title = "${item.title} due $dayWord" + (item.costPence?.let { " · ${RenewalRules.formatPence(it)}" } ?: ""),
                text = "$inWords · " + (link?.let { "Pay opens ${site(it)}" } ?: "mark it Paid in Lists once it's done"),
                atMs = cal.toEpochMs(item.dueDay - REMIND_DAYS, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(item.dueDay + 1, 0),
                actions = if (link != null) listOf(NoticeAction.PAY) else emptyList(),
                link = link,
            )
        }
    }
}
