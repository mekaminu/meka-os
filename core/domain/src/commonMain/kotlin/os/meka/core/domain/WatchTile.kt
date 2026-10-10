package os.meka.core.domain

/**
 * The Galaxy Watch's tile and complication (build plan "Galaxy Watch", slice 3). Both read the watch's own copy of the
 * day ([WatchHomeView], the cover screen's "now" card), so they say what the watch's screen says, only shorter:
 *
 * - the **tile** (swipe from the watch face): the label, the title, its line and one button, the primary one (Done on a
 *   task, Went on a booked session), plus a running fast's clock; a tap anywhere else opens MEKA;
 * - the **complication** (on any watch face): MEKA's ring as a ranged value — a running fast's share of its goal with
 *   its clock, else how much of today's list is done ("2/5").
 *
 * A button press on the tile comes back as the tile's last clickable id ([pressId]); [pressed] only ever answers a
 * button the watch is offering now, so a stale tile can't tick off something that has moved on. Pure, no AI; nothing
 * is stored.
 */
data class WatchTileView(
    /** "UP NEXT" · "IN 12 MIN" · "LINK THIS WATCH". */
    val label: String,
    val lit: Boolean,
    /** Cut at a word to [WatchTileRules.TITLE_MAX] characters. */
    val title: String,
    val line: String?,
    /** The one button the tile offers (the screen's primary one); null when there's nothing to tap. */
    val button: WatchButton?,
    /** [WatchTileRules.pressId] of [button]. */
    val buttonId: String?,
    /** "Fasting · goal 16 h · 14:05" while a fast runs; null otherwise (the tile stays calm). */
    val fastLine: String?,
    /** The fast has reached its goal (the line in the success colour). */
    val fastReached: Boolean,
    /** What a screen reader says for the whole tile. */
    val spoken: String,
)

/** The complication's ring: [value] of 0…1 with a short [text] and [title] beside it. */
data class WatchComplicationView(
    /** At most 7 characters: "14:05" · "2/5" · "Clear". */
    val text: String,
    /** "Fast" · "Goal" · "Done" · "Today". */
    val title: String,
    val value: Float,
    val fasting: Boolean,
    val spoken: String,
)

object WatchTileRules {
    const val TITLE_MAX = 40
    const val UNLINKED_LABEL = "LINK THIS WATCH"
    const val UNLINKED_TITLE = "Open MEKA"
    const val UNLINKED_LINE = "It shows a code to type on your phone"
    const val PRESS_PREFIX = "press:"
    /** The tile's id for "open MEKA" (any tap that isn't the button). */
    const val OPEN_ID = "open"

    /** How often the tile and complication look again when nothing is counting: a quarter hour, like the sync. */
    const val CALM_REFRESH_MS = 15 * 60_000L

    /** "press:DONE:t1": what the tile hands back when its button is tapped. */
    fun pressId(b: WatchButton): String = "$PRESS_PREFIX${b.action.name}:${b.targetId}"

    /**
     * The button a tapped tile's [id] means, but only while the watch still offers it ([home]'s buttons now): a tile
     * drawn a while ago whose task was done on the phone since does nothing.
     */
    fun pressed(id: String?, home: WatchHomeView?): WatchButton? {
        if (id == null || home == null || !id.startsWith(PRESS_PREFIX)) return null
        return home.buttons.firstOrNull { pressId(it) == id }
    }

    /** [s] cut at a word to [max] characters with "…" (a word longer than that is cut where it is). */
    fun shorten(s: String, max: Int = TITLE_MAX): String {
        val t = s.trim()
        if (t.length <= max) return t
        val cut = t.take(max - 1)
        if (t[max - 1] == ' ') return cut.trimEnd() + "…" // the cut falls between words
        val space = cut.lastIndexOf(' ')
        return (if (space >= max / 2) cut.take(space) else cut).trimEnd() + "…"
    }

    fun tile(home: WatchHomeView?): WatchTileView {
        if (home == null) {
            return WatchTileView(
                label = UNLINKED_LABEL, lit = true, title = UNLINKED_TITLE, line = UNLINKED_LINE,
                button = null, buttonId = null, fastLine = null, fastReached = false,
                spoken = "This watch isn't linked to MEKA yet. Open MEKA to link it.",
            )
        }
        val button = home.buttons.firstOrNull { it.primary }
        val f = home.fast
        val fastLine = if (f.running) "${f.title} · ${f.clock}" else null
        val title = shorten(home.title)
        val spoken = buildList {
            add(home.label.lowercase().replaceFirstChar { it.uppercase() })
            add(home.title)
            home.line?.let(::add)
            if (f.running) add("${f.title}, ${spokenClock(f.clock)}")
            button?.let { add("Double tap ${it.label} to ${it.label.lowercase()}") }
        }.joinToString(". ") + "."
        return WatchTileView(
            label = home.label, lit = home.lit, title = title, line = home.line,
            button = button, buttonId = button?.let(::pressId), fastLine = fastLine, fastReached = f.running && f.reached,
            spoken = spoken,
        )
    }

    /**
     * When the tile and complication should be drawn again with nothing synced in between: the next minute while a
     * fast's clock runs or something is starting ("IN 12 MIN" counts down), else a quarter hour.
     */
    fun refreshAfterMs(home: WatchHomeView?, nowMs: Long): Long {
        val counting = home != null && (home.fast.running || home.lit)
        return if (counting) WatchHomeRules.nextTickMs(nowMs) - nowMs else CALM_REFRESH_MS
    }

    /**
     * The complication. A running fast: its share of the goal and its clock ("Fast", "Goal" once reached). Otherwise
     * today's list: [done] of [done] + [left] ("2/5"), "Clear" when there was nothing at all. Unlinked: an empty ring
     * reading "Link".
     */
    fun complication(home: WatchHomeView?, done: Int, left: Int): WatchComplicationView {
        if (home == null) return WatchComplicationView("Link", "MEKA", 0f, false, "MEKA isn't linked on this watch yet")
        val f = home.fast
        if (f.running) {
            return WatchComplicationView(
                text = f.clock.take(7),
                title = if (f.reached) "Goal" else "Fast",
                value = f.progress.coerceIn(0f, 1f),
                fasting = true,
                spoken = "${f.title}, ${spokenClock(f.clock)}" + if (f.reached) ", goal reached" else ", ${(f.progress.coerceIn(0f, 1f) * 100).toInt()} % of the goal",
            )
        }
        val d = done.coerceAtLeast(0)
        val total = d + left.coerceAtLeast(0)
        if (total == 0) return WatchComplicationView("Clear", "Today", 0f, false, "Nothing on today's list")
        val text = "$d/$total".let { if (it.length <= 7) it else "${(d * 100) / total}%" }
        return WatchComplicationView(
            text = text,
            title = "Done",
            value = d.toFloat() / total,
            fasting = false,
            spoken = if (left <= 0) "All $total done today" else "$d of $total done today",
        )
    }

    /** "14:05" → "14 hours 5 minutes" (for screen readers). */
    fun spokenClock(clock: String): String {
        val parts = clock.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return clock
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return clock
        val hours = if (h == 1) "1 hour" else "$h hours"
        val minutes = if (m == 1) "1 minute" else "$m minutes"
        return when {
            h == 0 -> minutes
            m == 0 -> hours
            else -> "$hours $minutes"
        }
    }
}
