package os.meka.core.domain

/**
 * Bills due (build plan V1, slice 1: from the radar). A bill is an ordinary radar item ([Renewals], an `obligation`)
 * of kind BILL, or any other item Meka has given a pay link (a subscription he pays by hand), except the car's dates and
 * the money dates, which have their own reminders. A few days before each payment, at 09:00, one heads-up with the
 * amount and, when Meka saved where he pays it, a **Pay** button that opens that page:
 * "Council tax due Thu 15 Oct · £180" · "In 3 days · Pay opens oakfield.gov.uk".
 *
 * **MEKA never pays anything.** It only reminds and opens the page Meka typed; marking it Paid stays his tap in Lists.
 * The radar's digest line leaves bills to this reminder until one is overdue. Non-AI, pure; nothing leaves the device.
 * Slice 2 ([MailBillRules]): the server puts bills read from bill emails on the radar (never a link from the email).
 *
 * Completeness (2026-10-19): **a changed amount is flagged** — when a bill costs £1 or more than last time, more or less,
 * its reminder and its row in Lists say so ("£7.90 more than last time (£84.20)"): for a bill he types, the cost before
 * he changed it this period ([ObligationFields.PREV_COST]); for a bill read from email, the same payee's last paid bill
 * ([previousCost]). And **one reminder per bill, not two**: a radar bill that is one of Money's cards' payments (it names
 * the card and falls within [CARD_DAYS] of the card's due day, or of the one he marked Paid) is left to the card's
 * reminder ([cardFor]), which takes over its Pay link; Until payday counts it once ([withoutCards]).
 */
/** A bill he has paid (a closed one-off of kind BILL with a cost), for [BillRules.previousCost]. */
data class PaidBill(val title: String, val dueDay: Long, val pence: Long)

object BillRules {
    /** The reminder comes this many days before the due day. */
    const val REMIND_DAYS = 3
    /** At 09:00. */
    const val NOTICE_MIN = 9 * 60

    /** A change smaller than this (£1) isn't flagged: an energy bill moves by pence every month. */
    const val CHANGE_PENCE = 100L
    /** A card's payment and a radar bill this many days apart are the same payment. */
    const val CARD_DAYS = 3L
    /** "Last time" is a paid bill due between [LAST_MIN_DAYS] and [LAST_MAX_DAYS] before (a month or a quarter back). */
    const val LAST_MIN_DAYS = 14L
    const val LAST_MAX_DAYS = 100L

    /** Whether [before] → [now] is worth saying. */
    fun changed(before: Long, now: Long): Boolean = kotlin.math.abs(now - before) >= CHANGE_PENCE

    /**
     * The same payee's last paid bill's cost: the newest [paid] bill with the same title (a bill email's reminder is
     * named after its payee, "Octopus Energy bill"), due 14–100 days before [dueDay]. Null when there's none.
     */
    fun previousCost(title: String, dueDay: Long, paid: List<PaidBill>): Long? {
        val key = title.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        return paid.filter { it.title.trim().lowercase() == key && dueDay - it.dueDay in LAST_MIN_DAYS..LAST_MAX_DAYS }
            .maxByOrNull { it.dueDay }?.pence
    }

    /** The row's words: "up £7.90 on last time", "down £5 on last time". */
    fun changeShort(before: Long, now: Long): String =
        (if (now > before) "up " else "down ") + RenewalRules.formatPence(kotlin.math.abs(now - before)) + " on last time"

    /** The reminder's words: "£7.90 more than last time (£84.20)", "£5 less than last time (£45)". */
    fun changeLine(before: Long, now: Long): String =
        RenewalRules.formatPence(kotlin.math.abs(now - before)) + (if (now > before) " more" else " less") +
            " than last time (${RenewalRules.formatPence(before)})"

    private val NOT_A_NAME = setOf(
        "the", "and", "your", "bill", "bills", "card", "cards", "credit", "charge", "payment", "payments", "statement",
        "due", "minimum", "monthly", "account", "direct", "debit", "from", "mail", "visa", "mastercard", "plc", "bank",
    )

    /** The words that name a card or a payee: "American Express" reads as "amex" too. */
    private fun names(text: String): Set<String> {
        val t = text.lowercase().replace("american express", "amex")
        return t.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in NOT_A_NAME }.toSet()
    }

    /** Whether a bill called [title] due on [dueDay] is [card]'s payment: it names the card, within [CARD_DAYS] of its due or Paid day. */
    fun isCardsBill(title: String, dueDay: Long, card: MoneyCard, today: Long): Boolean {
        if (names(title).intersect(names(card.name)).isEmpty()) return false
        val days = listOfNotNull(MoneyCards.nextDue(card, today), card.paidFor)
        return days.any { kotlin.math.abs(it - dueDay) <= CARD_DAYS }
    }

    /** The card whose own reminder covers this radar bill, or null. */
    fun cardFor(item: RenewalItem, cards: List<MoneyCard>, today: Long): MoneyCard? =
        if (!isBill(item)) null else cards.firstOrNull { isCardsBill(item.title, item.dueDay, it, today) }

    /** Until payday's radar bills without the ones a card's own statement already counts. */
    fun withoutCards(bills: List<MoneyBill>, cards: List<MoneyCard>, today: Long): List<MoneyBill> =
        if (cards.isEmpty()) bills else bills.filter { b -> cards.none { isCardsBill(b.title, b.dueDay, it, today) } }

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
        item.payLink != null -> "The reminder ${REMIND_DAYS} days before opens ${site(item.payLink)} · Navilife never pays"
        else -> "Where you pay it (optional) · the reminder can open it"
    }

    /**
     * The reminders: for each bill due within [REMIND_DAYS], one heads-up at 09:00 [REMIND_DAYS] before (at once when it
     * was added later), standing until its day ends. Pay opens the saved link; without one the words say where to mark
     * it Paid. Each due day posts once. A bill that is one of [cards]' payments is left to the card's reminder; a
     * changed amount is said ("£7.90 more than last time (£84.20)").
     */
    fun notices(items: List<RenewalItem>, nowMs: Long, cal: LocalCalendar, cards: List<MoneyCard> = emptyList()): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.filter(::isBill).filter { cardFor(it, cards, today) == null }.mapNotNull { item ->
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
                // Kids' admin, slice 2: a child's club fee says whose it is ("Alex's Football due tomorrow · £35").
                title = (if (item.forChild != null && !item.subject.isNullOrBlank()) "${item.subject!!.trim()}'s ${item.title}" else item.title) +
                    " due $dayWord" + (item.costPence?.let { " · ${RenewalRules.formatPence(it)}" } ?: ""),
                text = "$inWords · " + (item.prevCostPence?.let { p -> item.costPence?.let { changeLine(p, it) + " · " } } ?: "") + when {
                    link != null -> "Pay opens ${site(link)}"
                    // Mail slice 3h: a Direct Debit read from a bill email has nothing to pay by hand.
                    MailBillRules.isDirectDebit(item.title) -> "taken by Direct Debit · nothing to pay"
                    else -> "mark it Paid in Lists once it's done"
                },
                atMs = cal.toEpochMs(item.dueDay - REMIND_DAYS, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(item.dueDay + 1, 0),
                actions = if (link != null) listOf(NoticeAction.PAY) else emptyList(),
                link = link,
            )
        }
    }
}
