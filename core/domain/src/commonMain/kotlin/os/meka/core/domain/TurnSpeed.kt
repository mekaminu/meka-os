package os.meka.core.domain

/**
 * The orb speed pass, slice 2 (Meka, 2026-10-20 19:57: "a lot of wait"; measure first): a timing trace of each Hello
 * and Talk turn, kept on the device only, holding times and nothing of what was said.
 *
 * A Hello turn starts at the tap; a Talk turn when his words ended (the endpoint). The facade marks when the
 * question went to the server, when the answer's first piece came back, when Navilife's voice was ready to start (its
 * first clip back, from memory or the server, or the device's own voice taking over) and how long each clip took.
 * Settings → Navilife's voice → Speed shows the last [KEPT].
 */
enum class TurnKind(val label: String, val code: String) {
    HELLO("Hello", "h"),
    TALK("Talk", "t"),
}

/**
 * One turn: [atMs] its start (wall clock, for the row's time), then each wait in ms (null: not part of this turn).
 * [toVoiceMs] is the whole wait he hears: tap → hello, or he stopped → the answer starts. [deviceVoice]: the
 * device's own voice spoke first because Navilife's wasn't there in time.
 */
data class TurnTrace(
    val kind: TurnKind,
    val atMs: Long,
    val toVoiceMs: Long?,
    val toSentMs: Long? = null,
    val toFirstPieceMs: Long? = null,
    val toClipMs: Long? = null,
    val clipMs: List<Long> = emptyList(),
    val deviceVoice: Boolean = false,
)

/**
 * The open turn's marks (elapsed ms on one clock). Each mark counts once (the first); [clip] keeps every clip's
 * fetch time (0 for one already on the device), at most [TurnSpeedRules.MAX_CLIPS].
 */
class TurnStopwatch(val kind: TurnKind, val startMs: Long) {
    private var sentAt: Long? = null
    private var firstPieceAt: Long? = null
    private var voiceAt: Long? = null
    private var deviceVoice = false
    private val clips = ArrayList<Long>()

    /** The question left for the server (Talk only). */
    fun sent(now: Long) { if (sentAt == null) sentAt = now }

    /** The answer's first piece came back (the early sentence, or the whole answer). */
    fun firstPiece(now: Long) { if (firstPieceAt == null) firstPieceAt = now }

    /** A clip came back after [tookMs] ([audio] false: the device's own voice says it); the first one starts the voice. */
    fun clip(now: Long, tookMs: Long, audio: Boolean) {
        if (voiceAt == null) {
            voiceAt = now
            deviceVoice = !audio
        }
        if (clips.size < TurnSpeedRules.MAX_CLIPS) clips += tookMs.coerceAtLeast(0)
    }

    /** Still taking marks: under [TurnSpeedRules.OPEN_MS] old, so a later reading isn't counted as this turn's. */
    fun open(now: Long): Boolean = now - startMs in 0..TurnSpeedRules.OPEN_MS

    /** The trace, or null when nothing was measured (stopped before a word, or a clock jump). */
    fun finish(wallStartMs: Long): TurnTrace? {
        val voice = voiceAt ?: return null
        fun since(t: Long?, from: Long): Long? = t?.let { it - from }?.takeIf { TurnSpeedRules.sane(it) }
        val toVoice = since(voice, startMs) ?: return null
        val sent = since(sentAt, startMs)
        val first = sentAt?.let { s -> since(firstPieceAt, s) }
        val clip = if (kind == TurnKind.HELLO) null else (firstPieceAt ?: sentAt ?: startMs).let { from -> since(voice, from) }
        return TurnTrace(kind, wallStartMs, toVoice, sent, first, clip, clips.toList(), deviceVoice)
    }
}

/** The pane the traces make, ready to show. */
data class TurnSpeedView(
    val title: String,
    val intro: String,
    val summary: String?,
    val rows: List<String>,
    val empty: String?,
    val clear: String?,
)

object TurnSpeedRules {
    const val KEPT = 20
    const val MAX_CLIPS = 12
    /** Marks after this long aren't this turn's. */
    const val OPEN_MS = 60_000L
    /** Tap → hello audible (the speed pass's target). */
    const val HELLO_TARGET_MS = 600L
    /** He stops talking → Navilife starts answering. */
    const val ANSWER_TARGET_MS = 1_500L
    /** The device-only store's key ([MekaCore]'s local values: never synced or sent). */
    const val KEY = "turn_speed.v1"

    const val TITLE = "Speed"
    const val INTRO = "How long each hello and answer took to start, on this device only. Nothing you said is kept here."
    const val EMPTY = "Nothing yet. Say hello or ask something in Talk and it shows here."
    const val CLEAR = "Clear"

    fun sane(ms: Long): Boolean = ms in 0..OPEN_MS

    fun target(kind: TurnKind): Long = if (kind == TurnKind.HELLO) HELLO_TARGET_MS else ANSWER_TARGET_MS

    /** Adds [trace], newest last, keeping the last [KEPT]. */
    fun add(traces: List<TurnTrace>, trace: TurnTrace): List<TurnTrace> = (traces + trace).takeLast(KEPT)

    /**
     * One row, newest first in the pane: "20:41 · Talk · 1.9 s, slow · sent 0.1 s · answer 1.2 s · voice 0.6 s ·
     * clips 0.5 s, 0.4 s" (the steps that apply; "phone's voice" when the device spoke first; "clip ready" for 0 ms).
     */
    fun row(t: TurnTrace, cal: LocalCalendar, device: String = "phone"): String {
        val parts = mutableListOf(LocalClock.formatMinute(cal.minuteOfDay(t.atMs)), t.kind.label)
        val total = t.toVoiceMs ?: 0
        parts += SpeechRules.seconds(total) + if (total > target(t.kind)) ", slow" else ""
        t.toSentMs?.let { parts += "sent " + SpeechRules.seconds(it) }
        t.toFirstPieceMs?.let { parts += "answer " + SpeechRules.seconds(it) }
        t.toClipMs?.let { parts += "voice " + SpeechRules.seconds(it) }
        if (t.deviceVoice) parts += "$device's voice"
        if (t.clipMs.isNotEmpty()) parts += "clips " + t.clipMs.joinToString(", ") { if (it == 0L) "ready" else SpeechRules.seconds(it) }
        return parts.joinToString(" · ")
    }

    /**
     * The summary over the kept turns: "Hello middle 0.5 s (aim 0.6 s) · answers middle 1.7 s (aim 1.5 s) · most of
     * the wait: the answer" (each kind once it has a turn; the step with the largest middle over the Talk turns).
     */
    fun summary(traces: List<TurnTrace>): String? {
        if (traces.isEmpty()) return null
        val parts = mutableListOf<String>()
        fun middle(xs: List<Long>): Long? = xs.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        middle(traces.filter { it.kind == TurnKind.HELLO }.mapNotNull { it.toVoiceMs })?.let {
            parts += "Hello middle ${SpeechRules.seconds(it)} (aim ${SpeechRules.seconds(HELLO_TARGET_MS)})"
        }
        val talks = traces.filter { it.kind == TurnKind.TALK }
        middle(talks.mapNotNull { it.toVoiceMs })?.let {
            parts += "answers middle ${SpeechRules.seconds(it)} (aim ${SpeechRules.seconds(ANSWER_TARGET_MS)})"
        }
        val steps = listOf(
            "sending" to middle(talks.mapNotNull { it.toSentMs }),
            "the answer" to middle(talks.mapNotNull { it.toFirstPieceMs }),
            "the voice" to middle(talks.mapNotNull { it.toClipMs }),
        ).filter { it.second != null }
        steps.maxByOrNull { it.second!! }?.takeIf { it.second!! > 0 }?.let { parts += "most of the wait: ${it.first}" }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    fun view(traces: List<TurnTrace>, cal: LocalCalendar, device: String = "phone"): TurnSpeedView = TurnSpeedView(
        title = TITLE,
        intro = INTRO,
        summary = summary(traces),
        rows = traces.asReversed().map { row(it, cal, device) },
        empty = if (traces.isEmpty()) EMPTY else null,
        clear = if (traces.isEmpty()) null else CLEAR,
    )

    // ---- The device-only store: one line per turn, numbers only ----

    fun encode(traces: List<TurnTrace>): String = traces.takeLast(KEPT).joinToString("\n") { t ->
        listOf(
            t.kind.code, t.atMs.toString(), t.toVoiceMs.str(), t.toSentMs.str(), t.toFirstPieceMs.str(), t.toClipMs.str(),
            t.clipMs.joinToString(","), if (t.deviceVoice) "d" else "",
        ).joinToString("|")
    }

    /** Reads [encode]'s text; a line it can't read is skipped. */
    fun decode(text: String?): List<TurnTrace> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split("|")
        if (f.size < 8) return@mapNotNull null
        val kind = TurnKind.entries.firstOrNull { it.code == f[0] } ?: return@mapNotNull null
        val at = f[1].toLongOrNull() ?: return@mapNotNull null
        val clips = if (f[6].isEmpty()) emptyList() else f[6].split(",").mapNotNull { it.toLongOrNull() }
        TurnTrace(kind, at, f[2].toLongOrNull(), f[3].toLongOrNull(), f[4].toLongOrNull(), f[5].toLongOrNull(), clips, f[7] == "d")
    }.takeLast(KEPT)

    private fun Long?.str() = this?.toString() ?: ""
}
