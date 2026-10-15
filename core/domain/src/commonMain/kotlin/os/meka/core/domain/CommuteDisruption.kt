package os.meka.core.domain

/**
 * The commute counts everywhere, slice 7: trouble on tomorrow morning's trains never moves the wake alarm by itself.
 * The evening before an office day, when TfL's planned status for the morning commute ([LineStatusSnapshot.planned],
 * read by the server from 17:00) has a leg of the route not running well ([LineLevel.MAJOR]), Shut down's wake alarm
 * shows the trouble with "Wake 10 min earlier · 06:30", and one heads-up offers the same button. Only Meka's tap moves
 * the alarm.
 */
data class WakeTrains(
    val epochDay: Long,
    /** "South Rail planned closure tomorrow morning" · "South Rail part closure · Metro severe delays tomorrow morning". */
    val title: String,
    /** "North Rail to Riverside is running" · "Check before you leave". */
    val advice: String,
    /** The time to offer (the alarm, or the suggestion when none is set, less [CommuteDisruptionRules.EARLIER_MIN]); null once it's already early enough. */
    val earlierMinute: Int?,
    /** "Wake 10 min earlier · 06:30", or null when [earlierMinute] is. */
    val actionLabel: String?,
    /** Shut down's line under the alarm: "Trains · … · North Rail to Riverside is running" or "… · alarm already 10 min early". */
    val line: String,
)

/** Non-AI, pure. */
object CommuteDisruptionRules {
    const val EARLIER_MIN = 10
    /** The offer shows from 17:00 the evening before (when the server starts reading the morning's plans). */
    const val EVENING_FROM_MIN = 17 * 60
    /** A planned status read longer ago than this is too old to act on. */
    const val FRESH_MS = 6 * 60 * 60_000L
    const val KEY_PREFIX = "wake-trains:"

    fun key(epochDay: Long): String = "$KEY_PREFIX$epochDay"
    fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /**
     * Tomorrow's offer, or null: [wake] is for an office day (its first commitment is work with a commute), the planned
     * status is for that day and fresh, it's after 17:00 the evening before (or the small hours of the day) and before
     * the alarm, and a leg the morning travels (About you's Train route: its first line, then the onward one) isn't
     * running well. Null without a route.
     */
    fun offer(s: LineStatusSnapshot, wake: WakeView, nowMs: Long, cal: LocalCalendar): WakeTrains? {
        val r = RouteRules.route() ?: return null
        val suggestion = wake.suggestion ?: return null
        val c = suggestion.commitment
        if (!c.isWork || c.commuteMin <= 0) return null
        val day = wake.epochDay
        if (s.plannedDay != day) return null
        val read = s.plannedCheckedMs ?: return null
        if (nowMs - read > FRESH_MS) return null
        if (nowMs < cal.toEpochMs(day - 1, EVENING_FROM_MIN)) return null
        val base = wake.setMinute ?: suggestion.minute
        if (nowMs >= cal.toEpochMs(day, base)) return null
        val legs = r.legs(morning = true).mapNotNull { id ->
            s.plannedSeverityOf(id)?.takeIf { RouteRules.level(it) == LineLevel.MAJOR }?.let { id to it }
        }
        if (legs.isEmpty()) return null
        val title = legs.joinToString(" · ") { (id, sev) -> "${RouteRules.name(id, r)} ${RouteRules.words(sev)}" } + " tomorrow morning"
        val advice = if (legs.any { it.first == r.firstId }) {
            RouteRules.fallbackAdvice(r, r.fallbackId?.let { s.plannedSeverityOf(it) }, morning = true) ?: "Check before you leave"
        } else "Check before you leave"
        // Already moved (by this offer or by hand): the alarm sits at least ten minutes before the suggestion.
        val already = wake.setMinute != null && wake.setMinute <= suggestion.minute - EARLIER_MIN
        val earlier = (base - EARLIER_MIN).takeIf { !already && it >= AlarmRules.EARLIEST_WAKE_MIN && cal.toEpochMs(day, it) > nowMs }
        return WakeTrains(
            epochDay = day,
            title = title.replaceFirstChar { it.uppercaseChar() },
            advice = advice,
            earlierMinute = earlier,
            actionLabel = earlier?.let { "Wake $EARLIER_MIN min earlier · ${LocalClock.formatMinute(it)}" },
            line = "Trains · ${title.replaceFirstChar { it.uppercaseChar() }} · " +
                if (already) "alarm already ${suggestion.minute - wake.setMinute!!} min early" else advice,
        )
    }

    /**
     * The one heads-up ([NoticeSource.TRAINS]) while the offer stands: "South Rail planned closure tomorrow morning" ·
     * "Wake at 06:30 instead of 06:40? · North Rail to Riverside is running", with [NoticeAction.WAKE_EARLIER].
     * Due when the plans were read (not before 17:00), held to the end of quiet hours, never after the earlier time:
     * a problem first seen at 23:30 doesn't wake Meka, and if quiet hours run past the alarm nothing is posted.
     */
    fun notices(s: LineStatusSnapshot, wake: WakeView?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> {
        wake ?: return emptyList()
        val t = offer(s, wake, nowMs, cal) ?: return emptyList()
        val earlier = t.earlierMinute ?: return emptyList()
        val from = cal.toEpochMs(t.epochDay - 1, EVENING_FROM_MIN)
        val atMs = quiet.endAfter(maxOf(from, s.plannedCheckedMs!!), cal)
        val until = cal.toEpochMs(t.epochDay, earlier)
        if (atMs >= until) return emptyList()
        val base = wake.setMinute ?: wake.suggestion!!.minute
        return listOf(
            Notice(
                key = key(t.epochDay), source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP,
                title = t.title,
                text = "Wake at ${LocalClock.formatMinute(earlier)} instead of ${LocalClock.formatMinute(base)}? · ${t.advice}",
                atMs = atMs, target = NoticeTarget.TODAY, expiresAtMs = until,
                actions = listOf(NoticeAction.WAKE_EARLIER),
            ),
        )
    }
}
