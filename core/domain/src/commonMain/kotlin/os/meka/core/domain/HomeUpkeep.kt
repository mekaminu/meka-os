package os.meka.core.domain

/**
 * Home upkeep calendar (build plan V1, Meka approved 2026-10-10): the jobs a house needs on a rhythm — the boiler
 * service, testing the smoke and CO alarms, clearing the gutters, cleaning the filters, home insurance and the TV
 * licence — each with its interval, one nudge at the right time, then gone until next time.
 *
 * Built on the renewals radar ([Renewals], `obligation` entities): a job MEKA suggests is added in one tap with its
 * interval, how far ahead it shows and a first date; "Done" rolls it on to the next time like any renewal, so it leaves
 * Needs doing until then. The nudge is the radar's own (one digest line on the day it starts showing, keyed by its due
 * day, so once per time round). Non-AI, pure: nothing here leaves the device.
 */

/** How a suggested job's first date is picked. */
sealed interface UpkeepFirstDue {
    /** The 1st of next month (monthly checks start on a clean rhythm). */
    data object NextMonth : UpkeepFirstDue
    /** The next 1st of any of these months (1–12) after today: gutters after the leaves fall and again in spring. */
    data class NextOf(val months: List<Int>) : UpkeepFirstDue
    /** A placeholder this many days ahead; Meka sets the real date (a renewal date only he knows). */
    data class InDays(val days: Int) : UpkeepFirstDue
}

data class UpkeepPreset(
    val id: String,
    val title: String,
    /** What it covers, under the title ("Press each alarm's test button"). */
    val note: String,
    val kind: ObligationKind,
    val repeat: RenewalRepeat,
    /** Days before its date it starts showing (0: on the day). */
    val leadDays: Int,
    val firstDue: UpkeepFirstDue,
    /** Lower-case words that mean an item already on the radar is this job ("smoke alarm"). */
    val aliases: List<String>,
) {
    /** False when MEKA can't know the date (a renewal): the row opens after adding so Meka sets it. */
    val dateKnown: Boolean get() = firstDue !is UpkeepFirstDue.InDays
}

data class UpkeepRow(
    val presetId: String,
    val title: String,
    val note: String,
    /** "Every month · a nudge on the day" or, once tracked, "On the radar · due Sun 1 Nov". */
    val line: String,
    /** The radar item that is this job, or null while it isn't tracked. */
    val trackedId: String?,
    /** The date it would start on if added now (local epoch day). */
    val firstDueDay: Long,
    val dateKnown: Boolean,
) {
    val tracked: Boolean get() = trackedId != null
}

data class HomeUpkeepView(
    val rows: List<UpkeepRow>,
    /** "2 of 6 on the radar", "6 jobs a home needs · add the ones that apply". */
    val summary: String,
) {
    val trackedCount: Int get() = rows.count { it.tracked }

    /** Index access for Swift. */
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): UpkeepRow = rows[index]
}

object HomeUpkeepRules {
    const val LABEL = "Home upkeep"

    val PRESETS: List<UpkeepPreset> = listOf(
        UpkeepPreset(
            "alarms", "Test the smoke and CO alarms", "Press each alarm's test button",
            ObligationKind.HOME, RenewalRepeat.MONTHLY, 0, UpkeepFirstDue.NextMonth,
            listOf("smoke alarm", "co alarm", "carbon monoxide"),
        ),
        UpkeepPreset(
            "boiler", "Boiler service", "A Gas Safe engineer, once a year",
            ObligationKind.BOILER, RenewalRepeat.YEARLY, 28, UpkeepFirstDue.InDays(30),
            listOf("boiler"),
        ),
        UpkeepPreset(
            "gutters", "Clear the gutters", "After the leaves fall, and again in spring",
            ObligationKind.HOME, RenewalRepeat.HALF_YEARLY, 7, UpkeepFirstDue.NextOf(listOf(4, 11)),
            listOf("gutter"),
        ),
        UpkeepPreset(
            "filters", "Clean the filters", "Cooker hood, vacuum and dryer",
            ObligationKind.HOME, RenewalRepeat.QUARTERLY, 3, UpkeepFirstDue.NextMonth,
            listOf("filter"),
        ),
        UpkeepPreset(
            "home-insurance", "Home insurance", "Buildings and contents · compare before it renews",
            ObligationKind.INSURANCE, RenewalRepeat.YEARLY, 21, UpkeepFirstDue.InDays(30),
            listOf("home insurance", "buildings insurance", "contents insurance", "house insurance"),
        ),
        UpkeepPreset(
            "tv-licence", "TV licence", "Renews once a year",
            ObligationKind.LICENCE, RenewalRepeat.YEARLY, 28, UpkeepFirstDue.InDays(30),
            listOf("tv licence", "tv license"),
        ),
    )

    fun preset(id: String): UpkeepPreset? = PRESETS.firstOrNull { it.id == id }

    /** The first date for [p] counted from [today] (local epoch days). */
    fun firstDueDay(p: UpkeepPreset, today: Long): Long {
        val d = CivilDate.fromEpochDay(today)
        return when (val f = p.firstDue) {
            UpkeepFirstDue.NextMonth -> firstOfMonthAfter(d.year, d.month)
            is UpkeepFirstDue.InDays -> today + f.days
            is UpkeepFirstDue.NextOf -> (0..12).asSequence()
                .map { ahead -> monthAhead(d.year, d.month, ahead) }
                .filter { (_, m) -> m in f.months }
                .map { (y, m) -> CivilDate.toEpochDay(y, m, 1) }
                .first { it > today }
        }
    }

    /** "a nudge on the day", "shown 3 days before", "shown a week before", "shown 4 weeks before". */
    fun nudgeLabel(leadDays: Int): String = when {
        leadDays <= 0 -> "a nudge on the day"
        leadDays == 1 -> "shown the day before"
        leadDays == 7 -> "shown a week before"
        leadDays % 7 == 0 -> "shown ${leadDays / 7} weeks before"
        else -> "shown $leadDays days before"
    }

    /** Whether a radar item is this job: the same title, one of its words, or (the boiler) the same kind. */
    fun matches(p: UpkeepPreset, item: RenewalItem): Boolean {
        val t = item.title.trim().lowercase()
        return t == p.title.lowercase() || p.aliases.any { t.contains(it) } ||
            (p.kind == ObligationKind.BOILER && item.kind == ObligationKind.BOILER)
    }

    /** The suggested jobs against what is on the radar ([items]: every live renewal), for [today]. */
    fun view(items: List<RenewalItem>, today: Long): HomeUpkeepView {
        val used = mutableSetOf<String>()
        val rows = PRESETS.map { p ->
            val item = items.sortedBy { it.dueDay }.firstOrNull { it.id !in used && matches(p, it) }
            item?.let { used += it.id }
            val line = if (item != null) "On the radar · ${item.meta}"
            else listOfNotNull(
                RenewalRules.repeatLabel(p.repeat), nudgeLabel(p.leadDays), "you set the date".takeIf { !p.dateKnown },
            ).joinToString(" · ")
            UpkeepRow(p.id, p.title, p.note, line, item?.id, firstDueDay(p, today), p.dateKnown)
        }
        val n = rows.count { it.tracked }
        val summary = when (n) {
            0 -> "${rows.size} jobs a home needs · add the ones that apply"
            rows.size -> "All ${rows.size} on the radar"
            else -> "$n of ${rows.size} on the radar"
        }
        return HomeUpkeepView(rows, summary)
    }

    /** What the undo bar says after adding: "Added “Clear the gutters” · Sun 1 Nov". */
    fun addedLine(title: String, dueDay: Long, today: Long): String = "Added “$title” · ${RenewalRules.dayWord(dueDay, today)}"

    private fun monthAhead(year: Int, month: Int, ahead: Int): Pair<Int, Int> {
        val i = (month - 1) + ahead
        return (year + i / 12) to (i % 12 + 1)
    }

    private fun firstOfMonthAfter(year: Int, month: Int): Long =
        monthAhead(year, month, 1).let { (y, m) -> CivilDate.toEpochDay(y, m, 1) }
}

/** Adds a suggested home upkeep job to the radar (refused when that job is already there). Returns the new id. */
fun Renewals.addHomeUpkeep(presetId: String, today: Long): String {
    val p = HomeUpkeepRules.preset(presetId) ?: throw ValidationException("That isn't one of the home jobs")
    if (items().any { HomeUpkeepRules.matches(p, it) }) throw ValidationException("“${p.title}” is already on the radar")
    return add(p.title, p.kind, HomeUpkeepRules.firstDueDay(p, today), p.repeat, leadDays = p.leadDays)
}
