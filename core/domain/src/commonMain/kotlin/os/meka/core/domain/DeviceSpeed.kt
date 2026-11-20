package os.meka.core.domain

/**
 * Needs Meka #43 "Yes" (2026-11-11 20:47): each device sends Obi's response times to Navilife's own server once a day,
 * so the daily build report can carry a one-line speed summary per device. Numbers only: how many hellos and answers
 * in the last day, their average and slowest time to Obi's voice. Nothing he said, no times of day, no words.
 */
data class DeviceSpeedSummary(
    /** "phone" or "Mac" (the label the apps already give Speed). */
    val device: String,
    val sentAtMs: Long,
    val helloCount: Int,
    val helloAvgMs: Long?,
    val helloMaxMs: Long?,
    val answerCount: Int,
    val answerAvgMs: Long?,
    val answerMaxMs: Long?,
    /**
     * Taps feel instant (3, rest; 2026-11-20): the slowest core work over the last day, by the code's own names ("Money
     * view"), slowest first, at most [DeviceSpeedRules.SLOW_MAX], each over [SpeedRules.SLOW_MS]. Never what it was about.
     */
    val slow: List<DeviceSlowWork> = emptyList(),
    /** The same slice: cold start, tab switch and first content as the app timed them in the last day ([FeelLog.all]). */
    val feel: List<FeelEntry> = emptyList(),
)

/** One named piece of core work: its slowest time in the window, how many times it ran and how many were slow. */
data class DeviceSlowWork(val name: String, val worstMs: Long, val count: Int, val slow: Int)

object DeviceSpeedRules {
    /** The device-only store's key for when the last summary went ([MekaCore]'s local values: never synced). */
    const val SENT_KEY = "turn_speed.sent.v1"
    /** Once a day: a summary goes when the last one is at least this old (20 h, so a daily habit doesn't drift). */
    const val EVERY_MS = 20 * 60 * 60_000L
    /** The turns a summary covers: the last day. */
    const val WINDOW_MS = 24 * 60 * 60_000L
    const val DEVICE_MAX = 12
    /** The report names this many of the slowest pieces of work. */
    const val SLOW_MAX = 5
    /** A work name the report can print: the code's names are short ("Whole-app refresh"). */
    const val NAME_MAX = 32
    /** The slowest work worth a warning in the report: a second or more held the core's one thread. */
    const val SLOW_WARN_MS = 1_000L

    /** Due when nothing went yet, the last went [EVERY_MS] ago or more, or the clock moved back. */
    fun due(lastSentMs: Long?, nowMs: Long): Boolean = lastSentMs == null || nowMs - lastSentMs !in 0 until EVERY_MS

    /** A device label the report can print: letters and spaces only, at most [DEVICE_MAX]; "device" when nothing is left. */
    fun label(device: String): String =
        device.filter { it.isLetter() || it == ' ' }.trim().take(DEVICE_MAX).ifEmpty { "device" }

    /** A work name the report can print: letters, digits, spaces and · - ' only, at most [NAME_MAX]; null when nothing is left. */
    fun workName(name: String): String? =
        name.filter { it.isLetterOrDigit() || it == ' ' || it == '·' || it == '-' || it == '\'' }.trim().take(NAME_MAX).trim().ifEmpty { null }

    /** The day's work log ([SpeedLog.worst] over a day) as the report's list: slow ones only, slowest first, at most [SLOW_MAX]. */
    fun slowWork(entries: List<SpeedEntry>): List<DeviceSlowWork> =
        entries.filter { it.worstMs > SpeedRules.SLOW_MS }
            .sortedWith(compareByDescending<SpeedEntry> { it.worstMs }.thenBy { it.name })
            .mapNotNull { e -> workName(e.name)?.let { DeviceSlowWork(it, e.worstMs, e.count, e.slow) } }
            .take(SLOW_MAX)

    /**
     * The last day's turns as numbers, with the day's [slow] work and [feel] times riding along, or null when there were
     * no turns (nothing worth sending: the summary goes as a turn ends).
     */
    fun summary(
        traces: List<TurnTrace>,
        device: String,
        nowMs: Long,
        slow: List<SpeedEntry> = emptyList(),
        feel: List<FeelEntry> = emptyList(),
    ): DeviceSpeedSummary? {
        val recent = traces.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs && it.toVoiceMs != null }
        if (recent.isEmpty()) return null
        val hellos = recent.filter { it.kind == TurnKind.HELLO }.mapNotNull { it.toVoiceMs }
        val answers = recent.filter { it.kind == TurnKind.TALK }.mapNotNull { it.toVoiceMs }
        fun avg(xs: List<Long>): Long? = if (xs.isEmpty()) null else (xs.sum() + xs.size / 2) / xs.size
        return DeviceSpeedSummary(
            label(device), nowMs,
            hellos.size, avg(hellos), hellos.maxOrNull(),
            answers.size, avg(answers), answers.maxOrNull(),
            slowWork(slow), feel,
        )
    }

    /**
     * The report's line: "Phone · 6 hellos avg 0.5 s, slowest 1.2 s (aim 0.6 s) · 14 answers avg 1.7 s, slowest 3.1 s
     * (aim 1.5 s), over aim" (each kind once it has a turn).
     */
    fun line(s: DeviceSpeedSummary): String {
        val parts = mutableListOf(s.device.replaceFirstChar { it.uppercaseChar() })
        fun part(n: Int, noun: String, avg: Long?, max: Long?, aim: Long) {
            if (n <= 0 || avg == null || max == null) return
            parts += "$n ${if (n == 1) noun else noun + "s"} avg ${SpeechRules.seconds(avg)}, slowest ${SpeechRules.seconds(max)} " +
                "(aim ${SpeechRules.seconds(aim)})" + if (avg > aim) ", over aim" else ""
        }
        part(s.helloCount, "hello", s.helloAvgMs, s.helloMaxMs, TurnSpeedRules.HELLO_TARGET_MS)
        part(s.answerCount, "answer", s.answerAvgMs, s.answerMaxMs, TurnSpeedRules.ANSWER_TARGET_MS)
        if (parts.size == 1) parts += "no turns"
        return parts.joinToString(" · ")
    }

    /**
     * The report's work line, or null with nothing slow: "Phone · Money view 1.8 s (12 times, 3 slow) · Whole-app refresh
     * 640 ms (40 times, 6 slow)".
     */
    fun workLine(s: DeviceSpeedSummary): String? {
        if (s.slow.isEmpty()) return null
        return (listOf(s.device.replaceFirstChar { it.uppercaseChar() }) + s.slow.map { w ->
            "${w.name} ${SpeedRules.said(w.worstMs)}" + when {
                w.count <= 1 -> ""
                w.slow == 0 -> " (${w.count} times)"
                else -> " (${w.count} times, ${w.slow} slow)"
            }
        }).joinToString(" · ")
    }

    /** A second or more of work held the core: the report shows the work line as a warning. */
    fun workOver(s: DeviceSpeedSummary): Boolean = s.slow.any { it.worstMs >= SLOW_WARN_MS }

    /**
     * The report's feel line, or null when nothing was timed: "Phone: Cold start to Today · 2.1 s · aim 1.5 s · 3 times,
     * 1 over; Tab switch · 90 ms (Calendar) · aim 150 ms · 40 times".
     */
    fun feelLine(s: DeviceSpeedSummary): String? {
        if (s.feel.isEmpty()) return null
        return s.device.replaceFirstChar { it.uppercaseChar() } + ": " + s.feel.joinToString("; ") { FeelRules.line(it) }
    }

    /** A moment's slowest time over its aim: the report shows the feel line as a warning. */
    fun feelOver(s: DeviceSpeedSummary): Boolean = FeelRules.worstOver(s.feel) != null

    /** Either kind's average over its aim: the report shows it as a warning, not a notice. */
    fun overAim(s: DeviceSpeedSummary): Boolean =
        (s.helloAvgMs ?: 0) > TurnSpeedRules.HELLO_TARGET_MS || (s.answerAvgMs ?: 0) > TurnSpeedRules.ANSWER_TARGET_MS
}
