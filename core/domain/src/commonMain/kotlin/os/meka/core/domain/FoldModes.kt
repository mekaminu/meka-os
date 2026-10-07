package os.meka.core.domain

/**
 * Fold modes (build plan M1), non-AI and pure. The Fold reports how it is held; these rules say which mode MEKA shows
 * and, for the half-folded "Flex mode" on a table, what the bedside clock says. The Mac has no hinge, so it only ever
 * gets [FoldMode.UNFOLDED] or [FoldMode.COVER] from its window width (it doesn't use them yet).
 */
enum class FoldPosture {
    /** Fully open (or closed: the cover screen is flat too). */
    FLAT,

    /** Half open with the hinge across the screen, standing on a table like a little laptop ("Flex mode"). */
    TABLETOP,

    /** Half open with the hinge down the middle, held like a book. */
    BOOK,
}

enum class FoldMode {
    /** Closed: the cover screen. */
    COVER,

    /** Open: the big screen. */
    UNFOLDED,

    /** Half folded on a table: the bedside clock on the top half, the alarm and the day on the bottom. */
    BEDSIDE,
}

/** Which part of the day the bedside clock's lower half is about. */
enum class BedsideSection {
    /** After midnight, before the morning brief starts: what today holds. */
    EARLY,

    /** The morning brief is offered and unread. */
    MORNING,

    /** The day: what's next. */
    DAY,

    /** The evening has started: tomorrow at a glance. */
    EVENING,
}

data class BedsideView(
    /** "06:42" */
    val time: String,
    /** "Wednesday 7 October" */
    val dateLabel: String,
    /** "Alarm 06:30 · in 7 h 48", "Alarm Thu 06:30", or "No alarm set". */
    val alarmLine: String,
    val alarmSet: Boolean,
    /** Quiet hours: the clock dims (lower brightness, quieter colours). */
    val dim: Boolean,
    val section: BedsideSection,
    /** "Morning brief" · "Up next" · "Tomorrow · Thu 8 Oct" · "Today · Wed 7 Oct" */
    val heading: String,
    /** At most [FoldModeRules.MAX_LINES] lines, never empty. */
    val lines: List<String>,
    /** Tapping the section opens the brief (morning) or the shutdown (evening); null when there is nothing to open. */
    val opens: BedsideOpens?,
)

enum class BedsideOpens { BRIEF, SHUTDOWN }

object FoldModeRules {
    /** The same breakpoint the shell and Today use for two panes. */
    const val WIDE_DP = 600f
    const val MAX_LINES = 3

    /**
     * Half folded with the hinge across is the bedside clock, whatever the width; otherwise the width decides
     * (a book-held Fold is still the big screen).
     */
    fun mode(widthDp: Float, posture: FoldPosture): FoldMode = when {
        posture == FoldPosture.TABLETOP -> FoldMode.BEDSIDE
        widthDp >= WIDE_DP -> FoldMode.UNFOLDED
        else -> FoldMode.COVER
    }

    /** The bedside clock keeps the screen on only while the phone is charging (on the nightstand), never on battery. */
    fun keepScreenOn(mode: FoldMode, charging: Boolean): Boolean = mode == FoldMode.BEDSIDE && charging

    /**
     * "Alarm 06:30 · in 7 h 48" within a day, "Alarm Thu 06:30" further off, "No alarm set" without one (or one that
     * has already gone off). [nextAlarmMs] is the phone's own next alarm; MEKA's alarms join it with the Alarms item.
     */
    fun alarmLine(nowMs: Long, nextAlarmMs: Long?, cal: LocalCalendar): String {
        if (nextAlarmMs == null || nextAlarmMs <= nowMs) return NO_ALARM
        val at = LocalClock.formatMinute(cal.minuteOfDay(nextAlarmMs))
        val minutes = ((nextAlarmMs - nowMs + 59_999) / 60_000).toInt() // round up: 30 s away is "in 1 min"
        if (minutes > 24 * 60) {
            val day = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(cal.epochDayOf(nextAlarmMs)) - 1]
            return "Alarm $day $at"
        }
        return "Alarm $at · in ${inWords(minutes)}"
    }

    /** "45 min", "7 h", "7 h 48". */
    fun inWords(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60}"
    }

    /**
     * The bedside clock: the time and date, the alarm, and one short section for this part of the day, read from the
     * views MEKA already has (nothing new is stored). Morning: the brief while it is unread. Evening (from the end of
     * work, else 18:00, until midnight): tomorrow at a glance. After midnight until the brief starts: what today
     * holds. Otherwise: what's next.
     */
    fun bedside(
        nowMs: Long,
        cal: LocalCalendar,
        nextAlarmMs: Long?,
        quiet: QuietHours,
        today: Today,
        brief: MorningBriefView,
        shutdown: ShutdownView,
    ): BedsideView {
        val minute = cal.minuteOfDay(nowMs)
        val day = cal.epochDayOf(nowMs)
        val alarm = alarmLine(nowMs, nextAlarmMs, cal)
        val section = when {
            brief.offered -> BedsideSection.MORNING
            shutdown.evening -> BedsideSection.EVENING
            minute < brief.startMinute -> BedsideSection.EARLY
            else -> BedsideSection.DAY
        }
        val shortDay = CivilDate.shortLabel(day)
        val (heading, lines, opens) = when (section) {
            BedsideSection.MORNING -> Triple(
                "Morning brief",
                listOfNotNull(brief.daySummary, brief.workLine, brief.waitingLine, brief.fastingLine),
                BedsideOpens.BRIEF,
            )
            BedsideSection.EVENING -> Triple(
                shutdown.tomorrow.label,
                listOfNotNull(shutdown.tomorrow.glance.removePrefix(GLANCE_PREFIX).replaceFirstChar { it.uppercase() }, shutdown.tomorrow.workLine),
                BedsideOpens.SHUTDOWN,
            )
            BedsideSection.EARLY -> Triple(
                "Today · $shortDay",
                listOfNotNull(brief.daySummary, brief.workLine),
                null,
            )
            BedsideSection.DAY -> {
                val next = buildList {
                    today.timeline.nextEvent?.let { add(it.line) }
                    today.upNext?.let { add("Next: ${it.title}") }
                }
                Triple("Up next", next.ifEmpty { listOf(NOTHING_NEXT) }, null)
            }
        }
        return BedsideView(
            time = LocalClock.formatMinute(minute),
            dateLabel = CivilDate.longLabel(day),
            alarmLine = alarm,
            alarmSet = alarm != NO_ALARM,
            dim = quiet.isQuiet(minute),
            section = section,
            heading = heading,
            lines = lines.filter { it.isNotBlank() }.distinct().take(MAX_LINES).ifEmpty { listOf(NOTHING_NEXT) },
            opens = opens,
        )
    }

    const val NO_ALARM = "No alarm set"
    const val NOTHING_NEXT = "Nothing else planned today"
    private const val GLANCE_PREFIX = "Tomorrow: "
}
