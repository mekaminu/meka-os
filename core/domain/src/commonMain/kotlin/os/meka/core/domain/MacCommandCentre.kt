package os.meka.core.domain

/**
 * The Mac's Today columns (Mac presentation pass, slice 1; Meka 2026-10-15 13:48 from his first look). The Fold keeps
 * [CommandCentreRules]; the Mac reads at Mac text sizes and has a pointer, so it gets its own breakpoints and shares:
 * - under [CommandCentreRules.TWO_DP] (600 pt): Today alone, listing Needs you itself;
 * - under [THREE_PT] (1100 pt): two columns, Today (with Coming up and News under its day) | Needs you (or the open
 *   task's detail);
 * - from [THREE_PT]: Today ≈ 32% | Needs you ≈ 42% (the column that holds the most) | Coming up and News ≈ 26%.
 * The widths are points of the content area (after the sidebar); each column keeps a usable minimum and the last one
 * takes what rounding leaves, so they always add up to the width.
 *
 * Scrolling columns show no scroll bars; a soft fade at an edge says there's more ([edgeFades]).
 */
object MacCommandCentreRules {
    const val THREE_PT = 1100f

    const val TODAY_SHARE_THREE = 0.32f
    const val NEEDS_YOU_SHARE_THREE = 0.42f
    const val COMING_UP_SHARE_THREE = 0.26f
    const val TODAY_SHARE_TWO = 0.52f

    const val TODAY_MIN = 300f
    const val SIDE_MIN = 280f
    const val COMING_UP_MIN = 240f

    /** The fades at a scrolling column's edges, in points (the 8/16 rhythm). */
    const val TOP_FADE_PT = 8f
    const val BOTTOM_FADE_PT = 16f

    /** Scrolled less than this from an edge counts as at it (trackpad bounce, rounding). */
    const val EDGE_SLOP_PT = 1f

    fun layout(contentWidthPt: Float): CommandLayout = when {
        contentWidthPt >= THREE_PT -> CommandLayout.THREE
        contentWidthPt >= CommandCentreRules.TWO_DP -> CommandLayout.TWO
        else -> CommandLayout.SINGLE
    }

    /**
     * Left to right. Two columns: Today | the open task's detail or Needs you (Coming up sits under Today, see
     * [comingUpUnderToday]). Three: as the Fold's three, the detail taking Needs you's place.
     */
    fun columns(layout: CommandLayout, taskOpen: Boolean): List<CommandColumn> = when (layout) {
        CommandLayout.SINGLE -> listOf(CommandColumn.TODAY)
        CommandLayout.TWO -> listOf(CommandColumn.TODAY, if (taskOpen) CommandColumn.DETAIL else CommandColumn.NEEDS_YOU)
        CommandLayout.THREE -> CommandCentreRules.columns(CommandLayout.THREE, taskOpen)
    }

    /** Two columns: Coming up and News follow Today's own day in its column instead of a column of their own. */
    fun comingUpUnderToday(layout: CommandLayout): Boolean = layout == CommandLayout.TWO

    /** Today lists what needs you itself only when no Needs you column is beside it (never shown twice). */
    fun todayListsNeedsYou(layout: CommandLayout): Boolean = layout == CommandLayout.SINGLE

    /** Each column's width in points, left to right, adding up to [contentWidthPt]. */
    fun widths(contentWidthPt: Float, layout: CommandLayout): List<Float> {
        val w = maxOf(0f, contentWidthPt)
        return when (layout) {
            CommandLayout.SINGLE -> listOf(w)
            CommandLayout.TWO -> {
                val today = clamp(floor(w * TODAY_SHARE_TWO), TODAY_MIN, w - SIDE_MIN)
                listOf(today, w - today)
            }
            CommandLayout.THREE -> {
                val today = clamp(floor(w * TODAY_SHARE_THREE), TODAY_MIN, w - SIDE_MIN - COMING_UP_MIN)
                val coming = clamp(floor(w * COMING_UP_SHARE_THREE), COMING_UP_MIN, w - today - SIDE_MIN)
                listOf(today, w - today - coming, coming)
            }
        }
    }

    /** Which edges of a scrolling column fade: the top once it has scrolled, the foot while more lies below. */
    fun edgeFades(offsetPt: Float, contentHeightPt: Float, viewportHeightPt: Float): EdgeFades {
        val below = contentHeightPt - viewportHeightPt - offsetPt
        return EdgeFades(top = offsetPt > EDGE_SLOP_PT, bottom = below > EDGE_SLOP_PT)
    }

    private fun floor(v: Float): Float = kotlin.math.floor(v)

    /** [v] kept within [lo]..[hi]; when the window is too narrow for both minimums, [lo] wins. */
    private fun clamp(v: Float, lo: Float, hi: Float): Float = maxOf(lo, minOf(v, hi))
}

/** The fades a scrolling column shows at its edges. */
data class EdgeFades(val top: Boolean, val bottom: Boolean)

/** What the Mac's one quiet setup line opens. */
enum class MacHeaderOpens { SETUP, HEALTH }

/**
 * The Mac's one line under the greeting (Mac presentation pass, slice 2, item 4): "Finish setting up · 4 steps ·
 * notifications are off" with Open Setup, in place of a red Health line plus a Setup card. [critical] only when there
 * is nothing to set up and Health has something broken (then it is Health's own line, as before).
 */
data class MacSetupLine(
    val text: String,
    val action: String,
    val opens: MacHeaderOpens,
    val critical: Boolean,
    val spoken: String,
)

/**
 * The Mac's Today header (Mac presentation pass, slice 2; Meka 2026-10-15 13:48): greeting, date and weather on the
 * left and the clock on the right, with the setup card and Health's line merged into one quiet line under them; no
 * news ticker where the News list already shows (the Fold keeps its ticker); and the sidebar shown when the window
 * opens wide.
 */
object MacHeaderRules {
    /** A window at least this wide opens with the sidebar showing (still collapsible). */
    const val SIDEBAR_PT = 1200f

    fun sidebarOnOpen(windowWidthPt: Float): Boolean = windowWidthPt >= SIDEBAR_PT

    /** The drifting ticker only when Today stands alone; otherwise the News list beside or under it has the stories. */
    fun tickerInToday(layout: CommandLayout): Boolean = layout == CommandLayout.SINGLE

    /**
     * The one line, or null when nothing is left to set up and nothing needs a look. [setupShown] is the Setup card's
     * own rule (something left, not hidden with Not today); [stepsLeft] its count.
     */
    fun setupLine(setupShown: Boolean, stepsLeft: Int, health: HealthView?): MacSetupLine? {
        val issues = health?.rows.orEmpty().filter { it.needsLook && !ownLine(it.key) }
        if (setupShown && stepsLeft > 0) {
            val steps = if (stepsLeft == 1) "1 step" else "$stepsLeft steps"
            val issue = when (issues.size) {
                0 -> null
                1 -> issuePhrase(issues[0])
                else -> "${issues.size} things need a look"
            }
            val text = listOfNotNull("Finish setting up", steps, issue).joinToString(" · ")
            val spoken = "Finish setting up: $steps left" + (issue?.let { ", and $it" } ?: "") + "."
            return MacSetupLine(text, "Open Setup", MacHeaderOpens.SETUP, critical = false, spoken = spoken)
        }
        val line = health?.todayLine ?: return null
        return MacSetupLine(line, "Open Health", MacHeaderOpens.HEALTH, critical = health.critical, spoken = "$line.")
    }

    /** "notifications are off" for the notifications row being off; else "<title> needs a look". */
    fun issuePhrase(row: HealthRow): String =
        if (row.key == "notifications" && row.line.startsWith("Off")) "notifications are off"
        else row.title + " needs a look"

    /** As Health's own Today line: battery and sign-ins have their own lines, About you is Setup's to ask. */
    private fun ownLine(key: String) = key == "battery" || key == AboutYouRules.HEALTH_KEY || key.startsWith("calendar:")
}
