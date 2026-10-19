package os.meka.core.domain

/**
 * Money manager, nudges (2026-10-19): **a quiet heads-up when something new is worth a look in Money** — the watchdog's
 * payments ([MoneyWatch]: possibly charged twice, bigger than usual, a first payment somewhere new, fees) and the tips
 * from his own numbers ([MoneyTips]: a habit, a category over its average, a regular payment that went up) — so he
 * doesn't have to open Money to find them. Rules only, no AI.
 *
 * - **Only what's new because of recent payments**: an item counts when it holds with every line and doesn't without the
 *   lines from the last [RECENT_DAYS] days (a bank feed's new lines, or a statement that reaches this week). Importing an
 *   old statement never sets one off.
 * - **Once each**: an alert once ever (its id names its payments); a tip once a month.
 * - **No amounts**: the title names what and where ("Possibly charged twice · Amazon", "Netflix went up"); the figures
 *   stay behind the fingerprint. A tap opens Money.
 * - **His to quieten**: a heads-up from 09:00 (held in quiet hours, like every heads-up), its own row in Notifications
 *   ([NoticeSource.MONEY_LOOK]: Heads-up · Digest · Silent); a tip kind he hid and an alert he put away never come.
 * - Several at once share one notification ([POST_AS]), each saying how many there are.
 */
object MoneyNudges {
    const val RECENT_DAYS = 3
    const val NOTICE_MIN = 9 * 60
    const val KEY_PREFIX = "money-look:"
    const val POST_AS = "money-look"
    const val ALERT_TEXT = "Worth a look · in Money, behind your fingerprint"
    const val TIP_TEXT = "A tip from your own numbers · in Money, behind your fingerprint"
    /** At most this many at once (the rest wait for the screen). */
    const val MAX = 3

    private val AMOUNT = Regex("""\s*(·\s*)?[−-]?£[0-9][0-9,]*(\.[0-9]+)?""")

    /** [title] without its amounts: "Bigger than usual · Currys £480" → "Bigger than usual · Currys". */
    fun withoutAmounts(title: String): String = title.replace(AMOUNT, "").replace(Regex("\\s{2,}"), " ").trim().trimEnd('·').trim()

    /** One new item: its notice [key], the words without amounts and whether it's an alert (else a tip). */
    data class Item(val key: String, val title: String, val alert: Boolean)

    /**
     * The new items on [today]: watchdog alerts not put away ([fine]) and tips of kinds not [hidden], that hold with
     * [lines] but not without the last [RECENT_DAYS] days' lines. Alerts first, at most [MAX].
     */
    fun items(lines: List<MoneyWatchLine>, today: Long, names: Map<String, String>, hidden: Set<String>, fine: Set<String>): List<Item> {
        if (lines.isEmpty()) return emptyList()
        val from = today - RECENT_DAYS + 1
        if (lines.none { it.day in from..today }) return emptyList()
        val older = lines.filter { it.day < from }
        val alertsNow = MoneyWatch.alerts(lines, today, names, fine)
        val alertsBefore = if (older.isEmpty()) emptySet() else MoneyWatch.alerts(older, today, names, fine).map { it.id }.toSet()
        val newAlerts = alertsNow.filter { it.id !in alertsBefore }
            .map { Item(KEY_PREFIX + it.id, withoutAmounts(it.title), alert = true) }
        fun tipLines(ls: List<MoneyWatchLine>) = ls.map { MoneyLine(it.day, it.pence, it.merchant, it.category) }
        val tipsNow = MoneyTips.tips(tipLines(lines), today, hidden)
        val tipsBefore = if (older.isEmpty()) emptySet() else MoneyTips.tips(tipLines(older), today, hidden).map { it.id }.toSet()
        val month = MoneyRules.monthOf(today)
        val newTips = tipsNow.filter { it.id !in tipsBefore }
            .map { Item("$KEY_PREFIX${it.id}:${month / 12}-${month % 12 + 1}", withoutAmounts(it.title), alert = false) }
        return (newAlerts + newTips).take(MAX)
    }

    /** The heads-ups for [items]: from [NOTICE_MIN] today, gone at the end of tomorrow. */
    fun notices(items: List<Item>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        if (items.isEmpty()) return emptyList()
        val today = cal.epochDayOf(nowMs)
        val n = items.size
        return items.map { i ->
            val text = if (n == 1) (if (i.alert) ALERT_TEXT else TIP_TEXT)
            else "${if (i.alert) "Worth a look" else "A tip"} · and ${n - 1} more in Money, behind your fingerprint"
            Notice(
                key = i.key, source = NoticeSource.MONEY_LOOK, tier = NoticeTier.HEADS_UP,
                title = i.title, text = text,
                atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.MONEY,
                expiresAtMs = cal.toEpochMs(today + 2, 0), postAs = POST_AS,
            )
        }
    }
}
