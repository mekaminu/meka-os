package os.meka.core.domain

/**
 * Live trains, slice 5: the wake alarm anchored on the train. On an office day, before this morning's wake alarm has
 * been dismissed, a fresh morning live board ([RouteRules.liveBoard]) gives the train to leave for
 * ([RouteRules.leaveBy]). The ringing alarm says it ("Leave by 07:32 for the 07:42"), and when his usual train is
 * cancelled or late so he has to leave earlier than the alarm allows, one heads-up offers "Wake at 06:20 instead of
 * 06:40?". Only Meka's tap moves the alarm.
 */
data class TrainWake(
    val epochDay: Long,
    /** This morning's wake alarm ([AlarmRules.wakeId]). */
    val alarmId: String,
    val leave: LeaveBy,
    /** The bad trains between the one to leave for and his usual one: "07:42 cancelled" · "07:42 cancelled · 07:48 delayed". */
    val trouble: String?,
    /** The earlier wake time to offer (the leave-by less the time to get ready, in fives); null when the alarm already allows it. */
    val wakeMinute: Int?,
    /** What the ringing alarm says: "Leave by 07:32 for the 07:42" · "Leave by 07:20 for the 07:30 · 07:42 cancelled". */
    val ringLine: String,
)

/** Non-AI, pure. */
object TrainWakeRules {
    const val KEY_PREFIX = "wake-train:"
    private const val MIN_MS = 60_000L
    /** At most two troubled trains named. */
    private const val TROUBLE_MAX = 2

    /** One heads-up per morning per train to leave for: a further cancellation (an even earlier train) asks again. */
    fun key(epochDay: Long, depMin: Int): String = "$KEY_PREFIX$epochDay:$depMin"
    fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /**
     * This morning's train-anchored wake, or null: [alarm] is today's wake alarm, on and not dismissed, it's an office
     * day ([office], for today), and a fresh morning board gives a leave-by ([RouteRules.leaveBy]: needs About you's
     * route and time to the train, and a board reaching his usual train).
     */
    fun view(s: LineStatusSnapshot, alarm: Alarm?, office: OfficeWindow?, bufferMin: Int, nowMs: Long, cal: LocalCalendar): TrainWake? {
        alarm ?: return null
        office ?: return null
        if (alarm.kind != AlarmKind.WAKE || alarm.off || alarm.dismissedAtMs != null) return null
        val today = cal.epochDayOf(nowMs)
        if (alarm.epochDay != today || cal.epochDayOf(office.startMs) != today || nowMs >= office.startMs) return null
        // The alarm has rung out (no answer for ten minutes): nothing to say on it any more.
        if (nowMs >= alarm.ringAtMs + AlarmRules.RING_FOR_MS) return null
        val board = RouteRules.liveBoard(s, RouteRules.Commute.MORNING, nowMs) ?: return null
        val leave = RouteRules.leaveBy(board, RouteRules.Commute.MORNING, office, nowMs, cal) ?: return null
        val nowMin = cal.minuteOfDay(nowMs)
        val pickAhead = ahead(leave.train.depMin, nowMin)
        val usualAhead = office.toWorkMin?.let { ((office.startMs - it * MIN_MS + leave.walkMin * MIN_MS - nowMs) / MIN_MS).toInt() }
        val trouble = usualAhead?.let { u ->
            board.departures.filter { d -> RouteRules.bad(d) && ahead(d.depMin, nowMin).let { it in (pickAhead + 1)..u && it < 12 * 60 } }
                .take(TROUBLE_MAX).joinToString(" · ") { RouteRules.words(it) }.ifEmpty { null }
        }
        val wake = (leave.leaveMin - bufferMin).floorDiv(AlarmRules.STEP_MIN) * AlarmRules.STEP_MIN
        val offer = wake.takeIf {
            trouble != null && !leave.later && it >= AlarmRules.EARLIEST_WAKE_MIN && it < alarm.minute &&
                alarm.snoozedUntilMs == null && cal.toEpochMs(today, it) > nowMs
        }
        val words = leave.words.replaceFirstChar { it.uppercaseChar() }
        return TrainWake(
            epochDay = today,
            alarmId = alarm.id,
            leave = leave,
            trouble = trouble,
            wakeMinute = offer,
            ringLine = (words + (trouble?.let { " · $it" }.orEmpty())).take(Alarms.NOTE_MAX),
        )
    }

    /** [r] as the ringing screen shows it: this morning's wake alarm says the train to leave for. */
    fun ring(r: AlarmRing?, tw: TrainWake?): AlarmRing? =
        if (r != null && tw != null && r.id == tw.alarmId) r.copy(line = tw.ringLine) else r

    /**
     * The one heads-up ([NoticeSource.TRAINS]) while the earlier wake stands: "07:42 cancelled · leave by 07:20 for
     * the 07:30" · "Wake at 06:20 instead of 06:40?", with [NoticeAction.WAKE_FOR_TRAIN]. Due when the board was read,
     * held to the end of quiet hours, never after the earlier time (so if quiet hours run past it nothing is posted;
     * the ringing alarm still says the train).
     */
    fun notices(tw: TrainWake?, s: LineStatusSnapshot, alarmMinute: Int?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> {
        tw ?: return emptyList()
        val m = tw.wakeMinute ?: return emptyList()
        val base = alarmMinute ?: return emptyList()
        val read = s.board.checkedMs ?: return emptyList()
        val atMs = quiet.endAfter(read, cal)
        val until = cal.toEpochMs(tw.epochDay, m)
        if (atMs >= until) return emptyList()
        return listOf(
            Notice(
                key = key(tw.epochDay, tw.leave.train.depMin), source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP,
                title = "${tw.trouble} · ${tw.leave.words}",
                text = "Wake at ${LocalClock.formatMinute(m)} instead of ${LocalClock.formatMinute(base)}?",
                atMs = atMs, target = NoticeTarget.TODAY, expiresAtMs = until,
                actions = listOf(NoticeAction.WAKE_FOR_TRAIN),
            ),
        )
    }

    /** "Wake alarm 06:20 · for the 07:30". */
    fun movedLine(tw: TrainWake): String? = tw.wakeMinute?.let {
        "Wake alarm ${LocalClock.formatMinute(it)} · for the ${LocalClock.formatMinute(tw.leave.train.depMin)}"
    }

    /** The note the moved alarm keeps: "Leave by 07:20 for the 07:30". */
    fun note(tw: TrainWake): String = tw.leave.words.replaceFirstChar { it.uppercaseChar() }

    private fun ahead(min: Int, nowMin: Int): Int = ((min - nowMin) % 1440 + 1440) % 1440
}
