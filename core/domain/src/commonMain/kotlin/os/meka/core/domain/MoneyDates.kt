package os.meka.core.domain

/**
 * UK money deadlines (build plan V1, Meka approved 2026-10-10): **dates only, never advice, never touching accounts.**
 * Five dates on the renewals radar ([Renewals], `obligation` entities), each added in one tap from Lists → Renewals →
 * Money dates:
 *  - **Self Assessment** — the online return and payment, 31 January (only if Meka files one);
 *  - **ISA allowance** — the tax year ends 5 April and the year's allowance doesn't carry over;
 *  - **Energy contract** and **Broadband contract** — the end dates Meka types, with a switch nudge six weeks before;
 *  - **Pension check-in** — once a year, on a day Meka picks.
 *
 * Each gets its own heads-up at 09:00 ([MoneyDateRules.notices]): Self Assessment and the ISA four weeks and a week
 * before, the contracts six weeks and a week before, the pension check-in on its day. The radar's digest line leaves
 * them to these until one is overdue. The week ahead and Ask already know the radar, so the dates show there too.
 * No new entity: these are ordinary obligations, recognised by title ([MoneyDateRules.matches]). Non-AI, pure:
 * nothing here leaves the device, and MEKA never reads or touches an account.
 */
data class MoneyDatePreset(
    val id: String,
    val title: String,
    /** What it is, under the title. */
    val note: String,
    val kind: ObligationKind,
    val repeat: RenewalRepeat,
    /** Lower-case words that mean an item already on the radar is this one ("tax return"). */
    val aliases: List<String>,
    /** The fixed (month, day) it falls on every year, or null when only Meka knows the date. */
    val fixed: Pair<Int, Int>?,
    /** Days before the date each heads-up comes (0: on the day), furthest first. */
    val remindDaysBefore: List<Int>,
    /** The word between the title and the day in a reminder: "Self Assessment due Sun 31 Jan". Blank: "Pension check-in · …". */
    val verb: String,
    /** The fact the reminders add ("file and pay online by 31 January"); never advice. */
    val hint: String,
) {
    val dateKnown: Boolean get() = fixed != null
}

data class MoneyDateRow(
    val presetId: String,
    val title: String,
    val note: String,
    /** "Every year · 31 January · reminders 4 weeks and a week before", or "On the radar · due Sun 31 Jan". */
    val line: String,
    /** The radar item that is this one, or null while it isn't tracked. */
    val trackedId: String?,
    /** False when the row opens after adding so Meka sets the date (contracts, the pension check-in). */
    val dateKnown: Boolean,
) {
    val tracked: Boolean get() = trackedId != null
}

data class MoneyDatesView(
    val rows: List<MoneyDateRow>,
    /** "2 of 5 on the radar", "Tax, ISA, contracts and pension · dates only". */
    val summary: String,
) {
    val trackedCount: Int get() = rows.count { it.tracked }

    /** Index access for Swift. */
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): MoneyDateRow = rows[index]
}

object MoneyDateRules {
    const val LABEL = "Money dates"
    /** Each reminder at 09:00 on its day. */
    const val NOTICE_MIN = 9 * 60
    /** A contract's or the pension check-in's placeholder date, days ahead (Meka sets the real one). */
    const val PLACEHOLDER_DAYS = 60
    /** The contracts' switch nudge: six weeks before the end. */
    const val SWITCH_DAYS = 42

    val PRESETS: List<MoneyDatePreset> = listOf(
        MoneyDatePreset(
            "self_assessment", "Self Assessment", "Only if you file a tax return · online return and payment",
            ObligationKind.OTHER, RenewalRepeat.YEARLY, listOf("self assessment", "tax return"),
            fixed = 1 to 31, remindDaysBefore = listOf(28, 7), verb = "due",
            hint = "file and pay online by 31 January",
        ),
        MoneyDatePreset(
            "isa", "ISA allowance", "The tax year ends 5 April · the allowance doesn't carry over",
            ObligationKind.OTHER, RenewalRepeat.YEARLY, listOf("isa allowance", "isa deadline"),
            fixed = 4 to 5, remindDaysBefore = listOf(28, 7), verb = "ends",
            hint = "this tax year's allowance doesn't carry over",
        ),
        MoneyDatePreset(
            "energy", "Energy contract", "Your fixed deal's end date · a switch nudge 6 weeks before",
            ObligationKind.OTHER, RenewalRepeat.NONE, listOf("energy contract", "energy deal", "energy tariff", "energy fix"),
            fixed = null, remindDaysBefore = listOf(SWITCH_DAYS, 7), verb = "ends",
            hint = "time to compare deals before it ends",
        ),
        MoneyDatePreset(
            "broadband", "Broadband contract", "Your contract's end date · a switch nudge 6 weeks before",
            ObligationKind.OTHER, RenewalRepeat.NONE, listOf("broadband contract", "broadband deal"),
            fixed = null, remindDaysBefore = listOf(SWITCH_DAYS, 7), verb = "ends",
            hint = "time to compare deals before it ends",
        ),
        MoneyDatePreset(
            "pension", "Pension check-in", "Once a year · look over your pension statements",
            ObligationKind.OTHER, RenewalRepeat.YEARLY, listOf("pension check"),
            fixed = null, remindDaysBefore = listOf(0), verb = "",
            hint = "once a year · look over your pension statements",
        ),
    )

    fun preset(id: String): MoneyDatePreset? = PRESETS.firstOrNull { it.id == id }

    /** Whether a radar item is this one: the same title or one of its words as whole words ("isa", never "visa"). */
    fun matches(p: MoneyDatePreset, item: RenewalItem): Boolean {
        val t = item.title.trim().lowercase()
        return t == p.title.lowercase() || p.aliases.any { containsWord(t, it) }
    }

    /** Whether a radar item is one of the money dates (it gets their reminders instead of the radar's digest line). */
    fun isMoney(item: RenewalItem): Boolean = PRESETS.any { matches(it, item) }

    /** The next (month, day) on or after [today]. */
    fun nextFixed(fixed: Pair<Int, Int>, today: Long): Long {
        val y = CivilDate.fromEpochDay(today).year
        val thisYear = CivilDate.toEpochDay(y, fixed.first, fixed.second)
        return if (thisYear >= today) thisYear else CivilDate.toEpochDay(y + 1, fixed.first, fixed.second)
    }

    /** "4 weeks and a week before", "6 weeks and a week before", "on the day". */
    fun remindLabel(days: List<Int>): String = days.joinToString(" and ") { d ->
        when {
            d == 0 -> "on the day"
            d == 7 -> "a week"
            d % 7 == 0 -> "${d / 7} weeks"
            else -> "$d days"
        }
    }.let { if (days == listOf(0)) "a reminder on the day" else "reminders $it before" }

    /** The money dates against what is on the radar ([items]: every live renewal). */
    fun view(items: List<RenewalItem>, today: Long): MoneyDatesView {
        val used = mutableSetOf<String>()
        val rows = PRESETS.map { p ->
            val item = items.sortedBy { it.dueDay }.firstOrNull { it.id !in used && matches(p, it) }
            item?.let { used += it.id }
            val line = when {
                item != null -> "On the radar · ${item.meta}"
                p.fixed != null -> "${RenewalRules.repeatLabel(p.repeat)} · next ${CivilDate.shortLabel(nextFixed(p.fixed, today))} · ${remindLabel(p.remindDaysBefore)}"
                p.repeat == RenewalRepeat.NONE -> "You set the end date · ${remindLabel(p.remindDaysBefore)}"
                else -> "${RenewalRules.repeatLabel(p.repeat)} · you set the date · ${remindLabel(p.remindDaysBefore)}"
            }
            MoneyDateRow(p.id, p.title, p.note, line, item?.id, p.dateKnown)
        }
        val n = rows.count { it.tracked }
        val summary = when (n) {
            0 -> "Tax, ISA, contracts and pension · dates only"
            rows.size -> "All ${rows.size} on the radar"
            else -> "$n of ${rows.size} on the radar"
        }
        return MoneyDatesView(rows, summary)
    }

    /**
     * The reminders: for each money date on the radar, a heads-up at 09:00 on each of its [MoneyDatePreset.remindDaysBefore]
     * ("Self Assessment due Sun 31 Jan" · "In 4 weeks · file and pay online by 31 January"), each only on its own day.
     * Opens Lists (the radar).
     */
    fun notices(items: List<RenewalItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.mapNotNull { item ->
            val p = PRESETS.firstOrNull { matches(it, item) } ?: return@mapNotNull null
            val left = (item.dueDay - today).toInt()
            if (left !in p.remindDaysBefore) return@mapNotNull null
            val inWords = when {
                left == 0 -> "Today"
                left == 7 -> "In a week"
                left % 7 == 0 -> "In ${left / 7} weeks"
                else -> "In $left days"
            }
            val day = CivilDate.shortLabel(item.dueDay)
            Notice(
                key = "money:${item.id}:${item.dueDay}:$left", source = NoticeSource.MONEY, tier = NoticeTier.HEADS_UP,
                title = if (p.verb.isBlank()) "${item.title} · $day" else "${item.title} ${p.verb} $day",
                text = if (left == 0 && p.verb.isBlank()) p.hint.replaceFirstChar { it.uppercase() } else "$inWords · ${p.hint}",
                atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
    }

    /** [word] in [text] with no letter either side ("energy deal" in "octopus energy deal", never in "energy dealer"). Read by hand, no regex. */
    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + word.length)
            if ((before == null || before !in 'a'..'z') && (after == null || after !in 'a'..'z')) return true
            from = i + 1
        }
    }
}

/**
 * Adds one of the money dates to the radar (refused when it is already there): Self Assessment and the ISA on their
 * next fixed day, the contracts and the pension check-in on a placeholder [MoneyDateRules.PLACEHOLDER_DAYS] ahead for
 * Meka to set. Shown on the radar from the first reminder.
 */
fun Renewals.addMoneyDate(presetId: String, today: Long): String {
    val p = MoneyDateRules.preset(presetId) ?: throw ValidationException("That isn't one of the money dates")
    if (items().any { MoneyDateRules.matches(p, it) }) throw ValidationException("“${p.title}” is already on the radar")
    val due = p.fixed?.let { MoneyDateRules.nextFixed(it, today) } ?: (today + MoneyDateRules.PLACEHOLDER_DAYS)
    return add(p.title, p.kind, due, p.repeat, leadDays = maxOf(p.remindDaysBefore.max(), 7))
}
