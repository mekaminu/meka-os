package os.meka.core.domain

/**
 * One habit as a compact chip under Today's news ticker (Fold review 2026-10-09 07:26, item 3): the full-width
 * "0 of 1 habits today" tile became a row of chips, each the habit's name beside a ring to tick it with the drawn check.
 */
data class HabitChip(
    val id: String,
    /** The habit's name, shortened at a word past [HabitChipRules.MAX_TITLE_CHARS]. */
    val title: String,
    val done: Boolean,
    /** Behind for the week: the name is lit in the accent colour. */
    val behind: Boolean,
    /** "Tick Stretch for today" / "Untick Stretch for today" (the whole name, for a screen reader). */
    val tickLabel: String,
    /** "1/2" for an N-a-week habit (this week's goes); null for a daily one. */
    val count: String? = null,
)

/**
 * Today's habit chips (non-AI, pure, unit-tested). The habits on today's list (done today, due today or behind for
 * the week — the rule Needs you's "Habits today" uses) in the Goals order; none means no row at all. The live tiles
 * strip under the ticker drops its habits tile, since the chips say it (the 24-hour Day ring's sheet keeps it).
 */
object HabitChipRules {
    const val MAX_TITLE_CHARS = 18

    fun build(goals: GoalsView?): List<HabitChip> = build(goals?.let(NeedsYouMeanwhileRules::habitsToday).orEmpty())

    fun build(habits: List<HabitItem>): List<HabitChip> = habits.map { h ->
        HabitChip(
            id = h.id,
            title = shortTitle(h.title),
            done = h.doneToday,
            behind = !h.doneToday && h.pace == HabitPace.BEHIND,
            tickLabel = NeedsYouMeanwhileRules.tickLabel(h),
            count = h.countLabel,
        )
    }

    /** The tiles for the slim strip under the ticker: all but the habits tile, which the chips replace. */
    fun stripTiles(tiles: List<DayTile>): List<DayTile> = tiles.filter { it.kind != DayTileKind.HABITS }

    /** "Read 20 pages of a…" → shortened at the last word that fits, with an ellipsis; short names unchanged. */
    fun shortTitle(title: String): String {
        val t = title.trim().replace(Regex("\\s+"), " ")
        if (t.length <= MAX_TITLE_CHARS) return t
        val cut = t.substring(0, MAX_TITLE_CHARS)
        val space = cut.lastIndexOf(' ')
        val head = if (space >= MAX_TITLE_CHARS / 2) cut.substring(0, space) else cut
        return head.trimEnd(' ', ',', '·', '-', '–') + "…"
    }
}
