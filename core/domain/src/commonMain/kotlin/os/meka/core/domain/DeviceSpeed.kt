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
)

object DeviceSpeedRules {
    /** The device-only store's key for when the last summary went ([MekaCore]'s local values: never synced). */
    const val SENT_KEY = "turn_speed.sent.v1"
    /** Once a day: a summary goes when the last one is at least this old (20 h, so a daily habit doesn't drift). */
    const val EVERY_MS = 20 * 60 * 60_000L
    /** The turns a summary covers: the last day. */
    const val WINDOW_MS = 24 * 60 * 60_000L
    const val DEVICE_MAX = 12

    /** Due when nothing went yet, the last went [EVERY_MS] ago or more, or the clock moved back. */
    fun due(lastSentMs: Long?, nowMs: Long): Boolean = lastSentMs == null || nowMs - lastSentMs !in 0 until EVERY_MS

    /** A device label the report can print: letters and spaces only, at most [DEVICE_MAX]; "device" when nothing is left. */
    fun label(device: String): String =
        device.filter { it.isLetter() || it == ' ' }.trim().take(DEVICE_MAX).ifEmpty { "device" }

    /** The last day's turns as numbers, or null when there were none (nothing worth sending). */
    fun summary(traces: List<TurnTrace>, device: String, nowMs: Long): DeviceSpeedSummary? {
        val recent = traces.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs && it.toVoiceMs != null }
        if (recent.isEmpty()) return null
        val hellos = recent.filter { it.kind == TurnKind.HELLO }.mapNotNull { it.toVoiceMs }
        val answers = recent.filter { it.kind == TurnKind.TALK }.mapNotNull { it.toVoiceMs }
        fun avg(xs: List<Long>): Long? = if (xs.isEmpty()) null else (xs.sum() + xs.size / 2) / xs.size
        return DeviceSpeedSummary(
            label(device), nowMs,
            hellos.size, avg(hellos), hellos.maxOrNull(),
            answers.size, avg(answers), answers.maxOrNull(),
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

    /** Either kind's average over its aim: the report shows it as a warning, not a notice. */
    fun overAim(s: DeviceSpeedSummary): Boolean =
        (s.helloAvgMs ?: 0) > TurnSpeedRules.HELLO_TARGET_MS || (s.answerAvgMs ?: 0) > TurnSpeedRules.ANSWER_TARGET_MS
}
