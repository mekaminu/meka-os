package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Money redesign (B), slice 2 (mockup v4, approved by Meka 2026-10-25 09:30): the Spending tab's new look. The month's
 * spending as a ring (one slice per category, biggest first, in the money palette), the total in the middle with how it
 * compares to usual (tap a category and the middle shows that category and its share), the three tiles (came in, bills
 * still to come before payday, left after them), the category rows (an icon, the share, a thin bar against the biggest,
 * the note against usual; the top [FOLDED_ROWS] until "All"), and the latest [LATEST] payments grouped by day with the
 * day's total out ("See all" opens the month's full list). Both apps draw it from here, so the Fold and the Mac match.
 */
enum class MoneyIcon {
    CART, FORK, CUP, TAKEAWAY, TRAIN, FUEL, BILL, REPEAT, BAG, SHIRT, HOME, HEART, TICKET, PLANE, KID, GIFT, CASH, PERCENT,
    DOTS, IN, MOVE,
}

/** How a line reads: [QUIET] plain, [GOOD] under usual (green), [OVER] over usual (lit). */
enum class MoneyTone { QUIET, GOOD, OVER }

/** One slice of the ring: [start] and [sweep] as fractions of the circle (0–1); [tone] 0–6 a colour, [MoneySpending.REST] quiet. */
data class MoneyRingSlice(val key: String, val label: String, val start: Float, val sweep: Float, val tone: Int)

/** The ring's middle: [top] ("Spent so far"), [big] ("£1,840"), [sub] ("£220 under usual by the 24th") and its tone. */
data class MoneyRingCentre(val top: String, val big: String, val sub: String?, val tone: MoneyTone, val spoken: String)

/** A category row: [pct] ("23%"), [bar] its length against the biggest category (0–1), [note] against usual. */
data class MoneySpendRow(
    val key: String, val label: String, val amount: String, val pct: String, val bar: Float, val note: String,
    val noteTone: MoneyTone, val tone: Int, val icon: MoneyIcon, val spoken: String,
)

/** One of the three tiles under the ring: "Came in £3,200" (lit), "Bills to come £410", "Left £950". */
data class MoneyTile(val label: String, val value: String, val lit: Boolean)

/** A day of the latest payments: [label] ("Today", "Yesterday", "Fri 23 Oct"), [total] out that day ("−£48.20") or null. */
data class MoneyPayDay(val label: String, val total: String?, val rows: List<MoneyTxnRow>)

/** The Spending tab's new look ([MoneySpending.view]). [seeAll] ("See all 42 ›") when the month has more than the latest. */
data class MoneySpendingView(
    val centre: MoneyRingCentre, val slices: List<MoneyRingSlice>, val rows: List<MoneySpendRow>, val tiles: List<MoneyTile>,
    val latest: List<MoneyPayDay>, val seeAll: String?,
)

/** A spend category's month for [MoneySpending.view]: its pence out, the note and whether it's over or under usual. */
data class MoneySpendCat(val category: MoneyCategory, val pence: Long, val line: String, val over: Boolean, val under: Boolean)

/** A payment for [MoneySpending.view]: its row, day and signed pence (newest first). */
data class MoneySpendTxn(val row: MoneyTxnRow, val day: Long, val pence: Long)

object MoneySpending {
    /** Colours in the ring before the rest share one quiet slice. */
    const val TONES = 7
    /** The quiet tone: the rest past [TONES], and Other always. */
    const val REST = 7
    const val REST_KEY = "rest"
    const val REST_LABEL = "Everything else"
    /** Category rows shown before "All". */
    const val FOLDED_ROWS = 5
    /** Payments under "Latest payments". */
    const val LATEST = 5
    const val LATEST_HEAD = "LATEST PAYMENTS"
    const val FEWER = "Fewer"
    const val SEE_ALL_CLOSE = "Latest only"

    /** A category's icon (the mockup's set; the Mac uses the nearest system symbol). */
    fun icon(key: String): MoneyIcon = when (MoneyCategory.of(key)) {
        MoneyCategory.GROCERIES -> MoneyIcon.CART
        MoneyCategory.EATING_OUT -> MoneyIcon.FORK
        MoneyCategory.COFFEE -> MoneyIcon.CUP
        MoneyCategory.TAKEAWAY -> MoneyIcon.TAKEAWAY
        MoneyCategory.TRANSPORT -> MoneyIcon.TRAIN
        MoneyCategory.FUEL -> MoneyIcon.FUEL
        MoneyCategory.BILLS -> MoneyIcon.BILL
        MoneyCategory.SUBSCRIPTIONS -> MoneyIcon.REPEAT
        MoneyCategory.SHOPPING -> MoneyIcon.BAG
        MoneyCategory.CLOTHES -> MoneyIcon.SHIRT
        MoneyCategory.HOME -> MoneyIcon.HOME
        MoneyCategory.HEALTH -> MoneyIcon.HEART
        MoneyCategory.FUN -> MoneyIcon.TICKET
        MoneyCategory.TRAVEL -> MoneyIcon.PLANE
        MoneyCategory.KIDS -> MoneyIcon.KID
        MoneyCategory.GIVING -> MoneyIcon.GIFT
        MoneyCategory.CASH -> MoneyIcon.CASH
        MoneyCategory.FEES -> MoneyIcon.PERCENT
        MoneyCategory.INCOME -> MoneyIcon.IN
        MoneyCategory.TRANSFERS -> MoneyIcon.MOVE
        MoneyCategory.OTHER, null -> MoneyIcon.DOTS
    }

    /** "All 9 ›" folded, [FEWER] open; null when every row already shows. */
    fun allLabel(rows: Int, open: Boolean): String? = when {
        rows <= FOLDED_ROWS -> null
        open -> FEWER
        else -> "All $rows ›"
    }

    /** The rows shown: the top [FOLDED_ROWS] until "All" opens them. */
    fun shownRows(v: MoneySpendingView, open: Boolean): List<MoneySpendRow> = if (open) v.rows else v.rows.take(FOLDED_ROWS)

    /** The middle with category [key] picked: its name, amount and share of the month; the month's total when none or gone. */
    fun focus(v: MoneySpendingView, key: String?): MoneyRingCentre {
        val r = v.rows.firstOrNull { it.key == key } ?: return v.centre
        val share = "${r.pct} of spending"
        return MoneyRingCentre(r.label, r.amount, share, MoneyTone.QUIET, "${r.label}, ${r.amount}, $share")
    }

    /** Whether slice [s] is drawn full (nothing picked, or it's the picked one) or faded. */
    fun lit(s: MoneyRingSlice, focus: String?, v: MoneySpendingView): Boolean {
        if (focus == null || v.rows.none { it.key == focus }) return true
        if (s.key == focus) return true
        // A category folded into the rest lights the rest's slice.
        return s.key == REST_KEY && v.slices.none { it.key == focus }
    }

    /** The tone a payment's chip wears: its category's colour this month, else quiet. */
    fun txnTone(v: MoneySpendingView, category: String): Int = v.rows.firstOrNull { it.key == category }?.tone ?: REST

    /**
     * The view. [partial] while the month is this month ([dayOfMonth] of [daysInMonth] gone); [avgTotal] the usual
     * month's spending (null with nothing to compare) named [usual] ("usual", or "September" with one month before);
     * [toGoPence] from Until payday (this month only), [leftPence] the month's in less out; [txns] newest first, [allTxns] the month's count.
     */
    fun view(
        partial: Boolean, monthName: String, spentPence: Long, incomePence: Long, cats: List<MoneySpendCat>,
        avgTotal: Long?, usual: String, dayOfMonth: Int, daysInMonth: Int, toGoPence: Long?, leftPence: Long?,
        txns: List<MoneySpendTxn>, allTxns: Int, today: Long,
    ): MoneySpendingView {
        val spent = spentPence.coerceAtLeast(0)
        val sorted = cats.filter { it.pence > 0 }.sortedWith(compareByDescending<MoneySpendCat> { it.pence }.thenBy { it.category.ordinal })
        // Colours go to the biggest categories in turn; Other is always quiet, as is everything past the palette.
        val tones = HashMap<MoneyCategory, Int>()
        var next = 0
        sorted.forEach { c -> tones[c.category] = if (c.category == MoneyCategory.OTHER || next >= TONES) REST else next++ }
        val slices = ArrayList<MoneyRingSlice>()
        var at = 0f
        var restSweep = 0f
        sorted.forEach { c ->
            val sweep = if (spent > 0) (c.pence.toFloat() / spent).coerceIn(0f, 1f) else 0f
            val tone = tones.getValue(c.category)
            if (tone == REST && c.category != MoneyCategory.OTHER) restSweep += sweep
            else { slices += MoneyRingSlice(c.category.key, c.category.label, at, sweep, tone); at += sweep }
        }
        if (restSweep > 0f) slices += MoneyRingSlice(REST_KEY, REST_LABEL, at, restSweep, REST)
        val biggest = sorted.firstOrNull()?.pence ?: 0L
        val rows = sorted.map { c ->
            val share = if (spent > 0) c.pence.toDouble() / spent else 0.0
            val pct = pct(share)
            val tone = when { c.over -> MoneyTone.OVER; c.under -> MoneyTone.GOOD; else -> MoneyTone.QUIET }
            MoneySpendRow(
                c.category.key, c.category.label, MoneyRules.fmt(c.pence), pct,
                if (biggest > 0) (c.pence.toFloat() / biggest).coerceIn(0.02f, 1f) else 0f, c.line, tone,
                tones.getValue(c.category), icon(c.category.key), "${c.category.label}, ${MoneyRules.fmt(c.pence)}, $pct of spending, ${c.line}",
            )
        }
        val centre = centre(partial, monthName, spent, avgTotal, usual, dayOfMonth, daysInMonth)
        val tiles = buildList {
            add(MoneyTile("Came in", whole(incomePence.coerceAtLeast(0)), lit = incomePence > 0))
            toGoPence?.let { add(MoneyTile("Bills to come", whole(it.coerceAtLeast(0)), lit = false)) }
            leftPence?.let { add(MoneyTile("Left", if (it < 0) "−" + whole(-it) else whole(it), lit = false)) }
        }
        val latest = days(txns.take(LATEST), today)
        val seeAll = if (allTxns > LATEST) "See all $allTxns ›" else null
        return MoneySpendingView(centre, slices, rows, tiles, latest, seeAll)
    }

    /** The latest payments by day, newest first: "Today", "Yesterday", else "Fri 23 Oct", with the day's money out. */
    fun days(txns: List<MoneySpendTxn>, today: Long): List<MoneyPayDay> {
        val out = ArrayList<MoneyPayDay>()
        txns.groupBy { it.day }.entries.sortedByDescending { it.key }.forEach { (day, ts) ->
            val label = when (day) {
                today -> "Today"
                today - 1 -> "Yesterday"
                else -> CivilDate.shortLabel(day)
            }
            val outP = ts.filter { it.pence < 0 }.sumOf { -it.pence }
            out += MoneyPayDay(label, if (outP > 0) "−" + MoneyRules.fmt(outP) else null, ts.map { it.row })
        }
        return out
    }

    /** "23%", "<1%" for a sliver. */
    fun pct(share: Double): String {
        val p = (share * 100).roundToLong()
        return if (p == 0L && share > 0) "<1%" else "$p%"
    }

    /** Whole pounds for the headline figures ("£1,840"). */
    fun whole(pence: Long): String = MoneyRules.fmt((pence / 100.0).roundToLong() * 100)

    /**
     * The middle with nothing picked. This month compares with the usual month's pace by today ("£220 under usual by the
     * 24th"); a finished month with the usual month ("£140 under usual"). Within [MoneyRules.OVER_MIN_PENCE] is "about usual".
     */
    fun centre(partial: Boolean, monthName: String, spent: Long, avgTotal: Long?, usual: String, dayOfMonth: Int, daysInMonth: Int): MoneyRingCentre {
        val top = if (partial) "Spent so far" else "Spent in $monthName"
        val big = whole(spent)
        if (avgTotal == null || avgTotal <= 0) return MoneyRingCentre(top, big, null, MoneyTone.QUIET, "$top, $big")
        val against = if (partial) avgTotal * dayOfMonth.coerceIn(1, daysInMonth) / daysInMonth.coerceAtLeast(1) else avgTotal
        val by = if (partial) " by the ${ordinal(dayOfMonth)}" else ""
        val diff = spent - against
        val (sub, tone) = when {
            diff >= MoneyRules.OVER_MIN_PENCE -> "${whole(diff)} over $usual$by" to MoneyTone.OVER
            -diff >= MoneyRules.OVER_MIN_PENCE -> "${whole(-diff)} under $usual$by" to MoneyTone.GOOD
            else -> "about $usual$by" to MoneyTone.QUIET
        }
        return MoneyRingCentre(top, big, sub, tone, "$top, $big, $sub")
    }

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st, 22nd, 23rd, 24th … 31st. */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
