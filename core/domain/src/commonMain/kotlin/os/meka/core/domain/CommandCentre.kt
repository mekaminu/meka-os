package os.meka.core.domain

/**
 * The unfolded command centre (build plan M1, Fold modes slice 2). Non-AI, pure: the same rules on the open Fold and
 * the Mac's wide window. Today stays on the left; beside it, instead of an empty detail pane, what needs you (the
 * decision stack) and what's coming up in the calendar after today. Opening a task puts its detail where Needs you was.
 *
 * Widths are of the content area (after the rail or sidebar), in dp on the Fold and points on the Mac:
 * - under [TWO_DP]: [CommandLayout.SINGLE], Today alone (the closed Fold);
 * - from [TWO_DP]: [CommandLayout.TWO], Today | Needs you over Coming up (the open Fold);
 * - from [THREE_DP]: [CommandLayout.THREE], Today | Needs you | Coming up (the open Fold on its side, a wide Mac window).
 */
enum class CommandLayout { SINGLE, TWO, THREE }

/** One column of the command centre, left to right. */
enum class CommandColumn {
    TODAY,

    /** The open task's detail (it takes Needs you's place). */
    DETAIL,

    NEEDS_YOU,

    COMING_UP,

    /** Two-column layout with nothing open: Needs you above Coming up in one column. */
    NEEDS_YOU_AND_COMING_UP,
}

/** One line of Coming up: an event, an all-day event or a planned task. */
data class ComingUpLine(
    /** Stable within its day: "a-<event>" for all-day, otherwise the timeline row's id. */
    val id: String,
    /** "09:30" · "All day" · "From 22:00" */
    val time: String,
    val title: String,
    /** The event, to open its detail; null for a planned task. */
    val event: CalendarEvent?,
    val isTask: Boolean,
)

/** One day of Coming up. */
data class ComingUpDay(
    /** The agenda section's id ("d-<epochDay>"). */
    val id: String,
    val epochDay: Long,
    /** "Tomorrow" · "Fri 9 Oct" */
    val title: String,
    /** "2 events · 1 fixture" / "Thursday 8 October"; null when the agenda has none. */
    val subtitle: String?,
    val lines: List<ComingUpLine>,
    /** "+2 more" when the day holds more than fits; null otherwise. */
    val moreLine: String?,
)

data class ComingUp(
    val days: List<ComingUpDay>,
    /** "Nothing planned in the next 30 days" when there is nothing after today; null otherwise. */
    val emptyLine: String?,
)

/**
 * News in the command centre (news ticker, slice 2): under Coming up, today's fixture if Barça plays, then a few
 * headlines taken lane by lane (Barça's top story, AI's, then the next of each). Opens the News place.
 */
data class NewsGlance(val matchday: NewsMatchday?, val items: List<NewsItem>)

object CommandCentreRules {
    /** The same breakpoint the shell and Today use for two panes. */
    const val TWO_DP = 600f

    /** Room for three columns of at least ~270 dp beside a usable Today. */
    const val THREE_DP = 900f

    /** Share of the width the columns beside Today take, two-column and three-column. */
    const val SIDE_SHARE_TWO = 0.45f
    const val SIDE_SHARE_THREE = 0.6f

    /** Days and lines per day Coming up shows: fewer when it shares its column with Needs you. */
    const val DAYS_SHARED = 3
    const val LINES_SHARED = 3
    const val DAYS_ALONE = 7
    const val LINES_ALONE = 5

    fun layout(contentWidthDp: Float): CommandLayout = when {
        contentWidthDp >= THREE_DP -> CommandLayout.THREE
        contentWidthDp >= TWO_DP -> CommandLayout.TWO
        else -> CommandLayout.SINGLE
    }

    /**
     * The columns left to right. Narrow: Today alone (a task's detail springs up over it). Two columns: the detail
     * when a task is open, else Needs you over Coming up. Three: the detail takes Needs you's place; Coming up stays.
     */
    fun columns(layout: CommandLayout, taskOpen: Boolean): List<CommandColumn> = when (layout) {
        CommandLayout.SINGLE -> listOf(CommandColumn.TODAY)
        CommandLayout.TWO -> listOf(CommandColumn.TODAY, if (taskOpen) CommandColumn.DETAIL else CommandColumn.NEEDS_YOU_AND_COMING_UP)
        CommandLayout.THREE -> listOf(CommandColumn.TODAY, if (taskOpen) CommandColumn.DETAIL else CommandColumn.NEEDS_YOU, CommandColumn.COMING_UP)
    }

    /** Share of the width beside Today (0 when Today is alone). */
    fun sideShare(layout: CommandLayout): Float = when (layout) {
        CommandLayout.SINGLE -> 0f
        CommandLayout.TWO -> SIDE_SHARE_TWO
        CommandLayout.THREE -> SIDE_SHARE_THREE
    }

    /** Today lists what needs you itself only when no Needs you column is beside it (never shown twice). */
    fun todayListsNeedsYou(layout: CommandLayout): Boolean = layout == CommandLayout.SINGLE

    /** "Needs you" · "Needs you · 3" · "Needs you · 9+" */
    fun needsYouHeading(count: Int): String = when {
        count <= 0 -> "Needs you"
        count > 9 -> "Needs you · 9+"
        else -> "Needs you · $count"
    }

    /**
     * Coming up: the days after today with something on them (Today's own timeline covers today), at most [maxDays],
     * each with its all-day events then events and planned tasks in time order, at most [maxLines] ("+2 more" for the
     * rest). Free stretches, gaps and the now line are left out. Hidden events stay hidden.
     */
    fun comingUp(view: CalendarView, maxDays: Int, maxLines: Int): ComingUp {
        val days = view.sections.asSequence()
            .filter { it.kind == AgendaKind.DAY && it.firstDay > view.todayEpochDay }
            .map { s ->
                val all = s.allDay.map { e -> ComingUpLine("a-" + e.id, ALL_DAY, e.title, e, isTask = false) } +
                    s.rows.filter { it.kind == TimelineKind.EVENT || it.kind == TimelineKind.TASK }
                        .map { r -> ComingUpLine(r.id, r.time, r.title, r.event, isTask = r.kind == TimelineKind.TASK) }
                Pair(s, all)
            }
            .filter { (_, all) -> all.isNotEmpty() }
            .take(maxDays.coerceAtLeast(0))
            .map { (s, all) ->
                val shown = all.take(maxLines.coerceAtLeast(1))
                ComingUpDay(
                    id = s.id,
                    epochDay = s.firstDay,
                    title = s.title,
                    subtitle = s.subtitle,
                    lines = shown,
                    moreLine = (all.size - shown.size).takeIf { it > 0 }?.let { "+$it more" },
                )
            }
            .toList()
        return ComingUp(days, if (days.isEmpty()) NOTHING_AHEAD else null)
    }

    /** Headlines News shows under Coming up: fewer when it shares its column with Needs you. */
    const val NEWS_SHARED = 2
    const val NEWS_ALONE = 4

    /**
     * News under Coming up: the matchday line and at most [maxItems] stories, the first of each lane in lane order,
     * then the second of each, and so on. Null when there is nothing to show (no topics, no headlines, no match), so
     * the column stays calm.
     */
    fun newsGlance(place: NewsPlace, maxItems: Int): NewsGlance? {
        val picked = ArrayList<NewsItem>()
        var round = 0
        while (picked.size < maxItems && place.lanes.any { it.items.size > round }) {
            for (lane in place.lanes) {
                if (picked.size >= maxItems) break
                lane.items.getOrNull(round)?.let(picked::add)
            }
            round++
        }
        if (picked.isEmpty() && place.matchday == null) return null
        return NewsGlance(place.matchday, picked)
    }

    const val ALL_DAY = "All day"
    const val NOTHING_AHEAD = "Nothing planned in the next 30 days"
}
