package os.meka.core.domain

/**
 * Money manager, slice 3 (2026-10-16): **the watchdog** (board item 9) on the Money screen's current month, behind his
 * fingerprint like the rest: payments from the last 30 days worth a second look. Rules over the lines he imported; no
 * AI. Navilife never acts on them: each says "Not you? Your bank's app can stop or dispute it", and "That's fine"
 * puts one away on both devices (synced `money_alert`, LWW, Undo).
 *
 * Kinds ([MoneyAlertKind]):
 * - **double**: the same shop, the same amount (£5+), on the same account within a day of each other — "Possibly charged
 *   twice · Amazon" · "£23.40 on Tue 13 Oct and again on Wed 14 Oct · Current account". Never coffee or travel fares
 *   (two flat whites or two bus fares the same day are normal).
 * - **large**: a payment of £100+ at least twice the shop's usual and £50 over its biggest before ("Bigger than usual ·
 *   Currys £480" · "Usually about £45 · Tue 13 Oct · Current account"), or £250+ to a shop never paid before once there
 *   are 60 days of statements to know that ("First payment to Acme Ltd · £320"). Any single payment of £2,000+ is
 *   "Check this · Rail Co · £2,400" instead (Money review 2026-10-26: one booking was over half a month), lit, with its
 *   share of the month and whether the same amount came back (a refund, or a move between his accounts).
 * - **fees**: foreign transaction fees added up per account ("Foreign transaction fees · £4.12" · "3 fees on Credit
 *   card in the last 30 days"), and each late-payment or over-limit fee or interest charged ("Late payment fee · £12").
 *
 * At most [MAX_PER_KIND] of each, newest first; doubles, then large, then fees.
 */
enum class MoneyAlertKind(val key: String) {
    DUPLICATE("double"),
    LARGE("large"),
    FEES("fees"),
    ;

    companion object {
        fun of(key: String?): MoneyAlertKind? = entries.firstOrNull { it.key == key }
    }
}

/** One payment worth a look: [title] what it is, [line] when and where; [lit] draws the title's line in the accent. */
data class MoneyAlert(val id: String, val kind: String, val title: String, val line: String, val lit: Boolean, val spoken: String)

/** A statement line as the watchdog sees it. */
data class MoneyWatchLine(
    val id: String, val account: String, val day: Long, val pence: Long, val merchant: String, val category: MoneyCategory, val text: String,
)

object MoneyWatch {
    const val WINDOW_DAYS = 30
    const val MAX_PER_KIND = 3
    const val DOUBLE_MIN_PENCE = 500L
    const val LARGE_MIN_PENCE = 10_000L
    const val NEW_MIN_PENCE = 25_000L
    const val NEW_HISTORY_DAYS = 60

    /** Money review (Meka 2026-10-26 22:12): one payment of £2,000+ is "Check this", whatever the shop's usual. */
    const val CHECK_MIN_PENCE = 200_000L
    const val CHECK = "Check this"
    const val CHECK_ASK = "Real, or a refund or a move?"
    const val BACK_DAYS = 30L
    const val FINE = "That's fine"
    const val HEAD = "WORTH A LOOK"
    const val FOOT = "Not you? Your bank's app can stop or dispute it · Navilife never acts on your accounts"
    const val FINE_SAID = "Put away · on both devices"

    private val NOT_DOUBLE = setOf(MoneyCategory.COFFEE, MoneyCategory.TRANSPORT)

    private val FX = Regex("non[- ]?sterling|foreign (transaction|exchange|currency)|\\bfx fee|currency conversion|cross[- ]border|exchange rate fee", RegexOption.IGNORE_CASE)
    private val LATE = Regex("late (payment )?fee|late payment|over ?limit|overlimit|returned payment fee|unpaid (item|transaction) fee", RegexOption.IGNORE_CASE)
    private val INTEREST = Regex("\\binterest\\b|overdraft (fee|charge|interest)", RegexOption.IGNORE_CASE)

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** A foreign transaction fee's line ("Non-sterling transaction fee", "FX fee"); also read by [MoneyTravel]. */
    fun isForeignFee(text: String): Boolean = FX.containsMatchIn(text)

    private fun id(kind: MoneyAlertKind, vararg parts: String): String = "w" + ActivityRules.fnv64("moneyalert:${kind.key}:" + parts.joinToString(":"))

    private fun median(xs: List<Long>): Long = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** "Head · 3" with the count of what's showing. */
    fun head(n: Int): String = "$HEAD · $n"

    /** Every alert for [today] not put away ([fine]), in order: doubles, large, fees. */
    fun alerts(lines: List<MoneyWatchLine>, today: Long, names: Map<String, String>, fine: Set<String> = emptySet()): List<MoneyAlert> {
        if (lines.isEmpty()) return emptyList()
        return (doubles(lines, today, names) + large(lines, today, names) + fees(lines, today, names)).filter { it.id !in fine }
    }

    private fun inWindow(l: MoneyWatchLine, today: Long) = l.day in (today - WINDOW_DAYS + 1)..today

    fun doubles(lines: List<MoneyWatchLine>, today: Long, names: Map<String, String>): List<MoneyAlert> {
        val out = mutableListOf<Pair<Long, MoneyAlert>>()
        lines.filter { inWindow(it, today) && it.pence <= -DOUBLE_MIN_PENCE && it.category.spend && it.category !in NOT_DOUBLE }
            .groupBy { Triple(it.account, it.merchant, it.pence) }
            .forEach { (k, ls) ->
                val sorted = ls.sortedWith(compareBy<MoneyWatchLine> { it.day }.thenBy { it.id })
                var i = 0
                while (i < sorted.size - 1) {
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    if (b.day - a.day <= 1) {
                        val label = MoneyCategoryRules.merchantLabel(k.second)
                        val account = names[k.first].orEmpty()
                        val title = "Possibly charged twice · $label"
                        val whenLine = if (a.day == b.day) "${fmt(-k.third)} twice on ${CivilDate.shortLabel(a.day)}"
                            else "${fmt(-k.third)} on ${CivilDate.shortLabel(a.day)} and again on ${CivilDate.shortLabel(b.day)}"
                        val line = listOf(whenLine, account).filter { it.isNotEmpty() }.joinToString(" · ")
                        out += b.day to MoneyAlert(id(MoneyAlertKind.DUPLICATE, a.id, b.id), MoneyAlertKind.DUPLICATE.key, title, line, lit = true, spoken = "$title. $line")
                        i += 2
                    } else i++
                }
            }
        return out.sortedByDescending { it.first }.take(MAX_PER_KIND).map { it.second }
    }

    fun large(lines: List<MoneyWatchLine>, today: Long, names: Map<String, String>): List<MoneyAlert> {
        val first = lines.minOf { it.day }
        val byMerchant = lines.filter { it.pence < 0 }.groupBy { it.merchant }
        val monthSpend = lines.filter { it.pence < 0 && it.category.spend }.groupBy { MoneyRules.monthOf(it.day) }.mapValues { (_, ls) -> -ls.sumOf { it.pence } }
        val out = mutableListOf<Pair<Long, MoneyAlert>>()
        lines.filter { inWindow(it, today) && it.pence <= -LARGE_MIN_PENCE && it.category.spend && it.category != MoneyCategory.FEES }.forEach { l ->
            val amount = -l.pence
            val before = byMerchant[l.merchant].orEmpty().filter { it.day < l.day }.map { -it.pence }
            val label = MoneyCategoryRules.merchantLabel(l.merchant)
            val account = names[l.account].orEmpty()
            val title: String
            val line: String
            when {
                amount >= CHECK_MIN_PENCE -> {
                    title = "$CHECK · $label · ${fmt(amount)}"
                    line = checkLine(l, amount, lines, monthSpend[MoneyRules.monthOf(l.day)] ?: 0L, account)
                    out += l.day to MoneyAlert(id(MoneyAlertKind.LARGE, l.id), MoneyAlertKind.LARGE.key, title, line, lit = true, spoken = "$title. $line")
                    return@forEach
                }
                before.isEmpty() -> {
                    if (amount < NEW_MIN_PENCE || l.day - first < NEW_HISTORY_DAYS) return@forEach
                    title = "First payment to $label · ${fmt(amount)}"
                    line = listOf(CivilDate.shortLabel(l.day), account, "Is this you?").filter { it.isNotEmpty() }.joinToString(" · ")
                }
                before.size >= 2 -> {
                    val usual = median(before)
                    if (amount < usual * 2 || amount < before.max() + 5_000) return@forEach
                    title = "Bigger than usual · $label ${fmt(amount)}"
                    line = listOf("Usually about ${fmt(usual)}", CivilDate.shortLabel(l.day), account).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                else -> return@forEach
            }
            out += l.day to MoneyAlert(id(MoneyAlertKind.LARGE, l.id), MoneyAlertKind.LARGE.key, title, line, lit = false, spoken = "$title. $line")
        }
        return out.sortedByDescending { it.first }.take(MAX_PER_KIND).map { it.second }
    }

    /**
     * A "Check this" payment's line: its share of that month's spending, the day and account, then whether the same
     * amount came back within [BACK_DAYS] either side (a refund, or a move between his accounts that landed as spending:
     * "£2,400 came back on Tue 15 Sep · a refund?"), else "Real, or a refund or a move?".
     */
    internal fun checkLine(l: MoneyWatchLine, amount: Long, lines: List<MoneyWatchLine>, monthSpend: Long, account: String): String {
        val share = if (monthSpend > 0) (amount * 100 / monthSpend).toInt() else 0
        val month = CivilDate.MONTH_LONG[MoneyRules.monthOf(l.day) % 12]
        val shareText = if (share in 25..100) "$share% of $month's spending" else null
        val back = lines.filter { it.pence == amount && kotlin.math.abs(it.day - l.day) <= BACK_DAYS }
            .minByOrNull { kotlin.math.abs(it.day - l.day) }
        val ask = if (back != null) "${fmt(amount)} came back on ${CivilDate.shortLabel(back.day)} · a refund?" else CHECK_ASK
        return listOfNotNull(shareText, CivilDate.shortLabel(l.day), account.ifEmpty { null }, ask).joinToString(" · ")
    }

    fun fees(lines: List<MoneyWatchLine>, today: Long, names: Map<String, String>): List<MoneyAlert> {
        val recent = lines.filter { inWindow(it, today) && it.pence < 0 }
        val out = mutableListOf<Pair<Long, MoneyAlert>>()
        recent.filter { FX.containsMatchIn(it.text) }.groupBy { it.account }.forEach { (acc, ls) ->
            val total = -ls.sumOf { it.pence }
            val latest = ls.maxWith(compareBy<MoneyWatchLine> { it.day }.thenBy { it.id })
            val title = "Foreign transaction fees · ${fmt(total)}"
            val n = ls.size
            val line = "$n fee${if (n == 1) "" else "s"}" + (names[acc]?.let { " on $it" } ?: "") + " in the last 30 days"
            out += latest.day to MoneyAlert(id(MoneyAlertKind.FEES, "fx", acc, latest.id), MoneyAlertKind.FEES.key, title, line, lit = false, spoken = "$title. $line")
        }
        recent.filter { !FX.containsMatchIn(it.text) && (LATE.containsMatchIn(it.text) || INTEREST.containsMatchIn(it.text)) }.forEach { l ->
            val what = if (LATE.containsMatchIn(l.text)) {
                if (Regex("over ?limit|overlimit", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) "Over-limit fee" else "Late payment fee"
            } else "Interest charged"
            val title = "$what · ${fmt(-l.pence)}"
            val line = listOf(names[l.account].orEmpty(), CivilDate.shortLabel(l.day)).filter { it.isNotEmpty() }.joinToString(" · ")
            out += l.day to MoneyAlert(id(MoneyAlertKind.FEES, l.id), MoneyAlertKind.FEES.key, title, line, lit = true, spoken = "$title. $line")
        }
        return out.sortedByDescending { it.first }.take(MAX_PER_KIND).map { it.second }
    }
}
