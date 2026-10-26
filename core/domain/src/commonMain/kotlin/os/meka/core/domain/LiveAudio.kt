package os.meka.core.domain

/**
 * Live on the phone's speaker, the fix for "it breaks when speaking and skips too" (Meka 2026-10-25 18:55).
 *
 * "Skips": the microphone heard Obi's own voice from the speaker (the echo canceller doesn't take it all out), Google
 * took it for Meka talking over him and cut him off mid-sentence. [LiveEchoGate] decides, chunk by chunk while Obi
 * speaks out loud, whether the microphone goes up or goes as silence: only Meka's voice clearly louder than Obi's own
 * (as the microphone hears it) for a moment goes up. With headphones everything goes up (full barge-in).
 *
 * "Breaks": Obi's voice started playing with the first chunk and a 0.2 s buffer, so any network gap stuttered.
 * [LiveJitter] holds a turn's first audio until a little is queued, then plays it as one stream; if the network falls
 * behind it waits again (counted as an underrun) rather than stuttering, and never drops what it holds on a gap.
 *
 * Both are plain state, used by one conversation from one thread at a time (the Fold guards them with a lock).
 */
object LiveAudioRules {
    /** His voice must be this much louder than Obi's own echo (as the microphone hears it) to go up. */
    const val OVER_ECHO_DB = 12f
    /** ... for this long, so a burst of echo or a clatter doesn't cut Obi off. */
    const val HOLD_MS = 250L
    /** Never let the threshold fall below this (a quiet room while Obi pauses between words). */
    const val FLOOR_DBFS = -35f
    /** How far back the microphone may still be hearing what played (the speaker → room → microphone delay). */
    const val ECHO_LAG_MS = 200L
    /**
     * How the microphone hears Obi relative to what is played (dB, mic level − played level), learnt over the talk from
     * the chunks that stay muted. It starts at 0 (as loud as played: the strictest gate) and settles to the phone's
     * own echo once a few seconds of Obi have played.
     */
    const val START_ECHO_DB = 0f
    const val MIN_ECHO_DB = -40f
    const val MAX_ECHO_DB = 0f
    /** How quickly the echo estimate follows (per muted chunk). */
    const val ECHO_ALPHA = 0.05f
    /** Played chunks quieter than this teach nothing about the echo (pauses, breaths). */
    const val LEARN_ABOVE_DBFS = -50f

    /** Start playing a turn once this much of Obi's voice is queued (or the turn is complete). */
    const val START_AFTER_MS = 280L
    /** ... or once its first audio has waited this long (a short answer whose end is slow to be marked). */
    const val MAX_WAIT_MS = 450L
    /** The Fold's track buffer: about a second, so a write never waits on the speaker. */
    const val TRACK_BUFFER_MS = 1_000L

    /** The microphone level his voice must reach while Obi plays at [playedDbfs] and the echo is [echoDb]. */
    fun threshold(playedDbfs: Float, echoDb: Float): Float =
        maxOf(playedDbfs + echoDb.coerceIn(MIN_ECHO_DB, MAX_ECHO_DB) + OVER_ECHO_DB, FLOOR_DBFS)

    /** Milliseconds of 16-bit mono audio at [rate] in [bytes]. */
    fun ms(bytes: Int, rate: Int = LiveTalkRules.OUT_RATE): Long =
        if (bytes <= 0 || rate <= 0) 0 else bytes * 1_000L / (rate * 2)

    /** Whether a held turn (something is held) starts playing now. */
    fun start(heldMs: Long, waitedMs: Long, turnDone: Boolean): Boolean =
        turnDone || heldMs >= START_AFTER_MS || waitedMs >= MAX_WAIT_MS

    /** The Fold's AudioTrack buffer in bytes for [rate] (at least the platform's [minBytes]). */
    fun trackBytes(rate: Int, minBytes: Int): Int = maxOf(minBytes, (rate * 2L * TRACK_BUFFER_MS / 1_000).toInt())

}

/**
 * Whether each microphone chunk goes up while Obi speaks ([send]), from what is playing right now ([played]) and what
 * the microphone hears of it. One per conversation.
 */
/**
 * Hotfix 2026-10-26 (Meka 20:27: Obi says "the same sentence 20 times" on the speaker): with [bargeInOnSpeaker] false,
 * the default, nothing from the microphone goes up while Obi is speaking on the phone's own speaker, so his echo can
 * never make Google cut him off and restart; Meka stops him by tapping the orb. Headphones keep full barge-in. The
 * echo-relative barge-in below stays for when it's proven on the Fold (Health → Obi's voice shows cut-offs per talk).
 */
class LiveEchoGate(private val bargeInOnSpeaker: Boolean = false) {
    /** What is queued to play, in order (Google sends faster than real time, so this can run many seconds ahead). */
    private class Span(val start: Long, val end: Long, val dbfs: Float)
    private val spans = ArrayDeque<Span>()
    private var overSince = -1L

    /** How the microphone hears Obi relative to what plays, as learnt so far ([LiveAudioRules.START_ECHO_DB] at first). */
    var echoDb: Float = LiveAudioRules.START_ECHO_DB
        private set

    /** The last decision's levels, for the log: what the microphone heard and what was playing. */
    var lastMicDbfs: Float = -160f
        private set
    var lastPlayedDbfs: Float = -160f
        private set
    /** Whether the last chunk went up as his voice (false: it went as silence). */
    var lastSent: Boolean = true
        private set

    /** A chunk of Obi's voice at [dbfs] plays from [startMs] to [endMs] (the device's elapsed clock). */
    fun played(startMs: Long, endMs: Long, dbfs: Float) {
        if (endMs <= startMs) return
        if (spans.size >= MAX_SPANS) spans.removeFirst()
        spans.addLast(Span(startMs, endMs, dbfs))
    }

    /** The loudest of Obi's voice the microphone may be hearing at [nowMs]; -160 when nothing plays. */
    fun playedAt(nowMs: Long): Float {
        val from = nowMs - LiveAudioRules.ECHO_LAG_MS
        while (spans.isNotEmpty() && spans.first().end <= from) spans.removeFirst()
        var loud = -160f
        for (sp in spans) {
            if (sp.start > nowMs) break
            if (sp.dbfs > loud) loud = sp.dbfs
        }
        return loud
    }

    /**
     * Whether a microphone chunk at [micDbfs] goes up at [nowMs]. Not while Obi speaks ([speaking] false) or with
     * headphones: always. On the speaker while he speaks: only once his voice has been [LiveAudioRules.OVER_ECHO_DB]
     * over Obi's echo for [LiveAudioRules.HOLD_MS]; everything else goes as silence and teaches the gate the echo.
     */
    fun send(output: BargeInOutput, micDbfs: Float, speaking: Boolean, nowMs: Long, chunkMs: Long = LiveTalkRules.CHUNK_MS.toLong()): Boolean {
        lastMicDbfs = micDbfs
        if (output == BargeInOutput.HEADPHONES || !speaking) {
            overSince = -1
            lastPlayedDbfs = -160f
            lastSent = true
            return true
        }
        val played = playedAt(nowMs)
        lastPlayedDbfs = played
        if (!bargeInOnSpeaker || micDbfs < LiveAudioRules.threshold(played, echoDb)) {
            overSince = -1
            if (played > LiveAudioRules.LEARN_ABOVE_DBFS) {
                echoDb = (echoDb + LiveAudioRules.ECHO_ALPHA * ((micDbfs - played) - echoDb))
                    .coerceIn(LiveAudioRules.MIN_ECHO_DB, LiveAudioRules.MAX_ECHO_DB)
            }
            lastSent = false
            return false
        }
        if (overSince < 0) overSince = nowMs
        lastSent = nowMs - overSince + chunkMs >= LiveAudioRules.HOLD_MS
        return lastSent
    }

    /** He talked over Obi (or the turn ended): nothing is playing any more; the echo learnt so far is kept. */
    fun clear() {
        spans.clear()
        overSince = -1
    }

    private companion object {
        /** About ten minutes of 40 ms chunks: far more than one answer ever queues. */
        const val MAX_SPANS = 16_384
    }
}

/**
 * Obi's voice between Google and the speaker: a turn's audio is held until [LiveAudioRules.start] says go, then played
 * as one stream. [until] is when what was let through will have played (the speaking clock). One per conversation.
 */
class LiveJitter<T>(private val rate: Int = LiveTalkRules.OUT_RATE) {
    private val held = ArrayList<T>()
    private var heldMs = 0L
    private var firstAt = -1L
    private var playing = false
    private var turnDone = false

    /** When what was let through will have finished playing (elapsed ms); 0 when nothing has. */
    var until: Long = 0L
        private set
    /** Times the network fell behind mid-turn and Obi waited for more rather than stuttering. */
    var underruns: Int = 0
        private set
    /** The longest Obi went quiet mid-turn this talk, from the speaker running dry to playing again (ms). */
    var longestGapMs: Long = 0L
        private set
    /** When the speaker ran dry mid-turn (elapsed ms); -1 while it hasn't. */
    private var dryAt = -1L
    /** What [arrive] or [tick] let through last, for the gate: from, to (elapsed ms). */
    var lastFrom: Long = 0L
        private set

    /** A chunk of [bytes] arrived at [nowMs]: what to play now, in order (empty while held). */
    fun arrive(item: T, bytes: Int, nowMs: Long): List<T> {
        val ms = LiveAudioRules.ms(bytes, rate)
        if (playing && nowMs > until) {
            // Mid-turn, the speaker ran dry before this arrived: wait for a little again instead of stuttering.
            playing = false
            underruns++
            dryAt = until
        }
        if (playing) {
            lastFrom = maxOf(until, nowMs)
            until = lastFrom + ms
            return listOf(item)
        }
        if (held.isEmpty()) firstAt = nowMs
        held += item
        heldMs += ms
        return release(nowMs)
    }

    /** Google marked the turn complete at [nowMs]: whatever is held plays now; the next turn is held afresh. */
    fun turnComplete(nowMs: Long): List<T> {
        turnDone = true
        val out = release(nowMs)
        playing = false
        turnDone = false
        return out
    }

    /** The clock moved on ([nowMs]): a turn held too long starts. */
    fun tick(nowMs: Long): List<T> = if (playing) emptyList() else release(nowMs)

    /** He talked over Obi: nothing held plays, the clock stops. */
    fun interrupted() {
        held.clear()
        heldMs = 0
        firstAt = -1
        playing = false
        turnDone = false
        until = 0
        dryAt = -1
    }

    private fun release(nowMs: Long): List<T> {
        if (held.isEmpty() || !LiveAudioRules.start(heldMs, if (firstAt < 0) 0 else nowMs - firstAt, turnDone)) return emptyList()
        val out = held.toList()
        if (dryAt >= 0) {
            longestGapMs = maxOf(longestGapMs, nowMs - dryAt)
            dryAt = -1
        }
        lastFrom = maxOf(until, nowMs)
        until = lastFrom + heldMs
        held.clear()
        heldMs = 0
        firstAt = -1
        playing = true
        return out
    }
}

/**
 * How a Live talk sounded on this device (2026-10-25, the Live item's (3)): whether the speaker or headphones played
 * Obi, how often he stopped because Google heard Meka talk over him ([cutOffs]) with the levels at the last one, and how
 * often the network fell behind and Obi waited rather than stuttered ([waits], [longestWaitMs]). Numbers only, never
 * words; kept on this device for Health → Obi's voice and the Speed list.
 */
data class LiveAudioStats(
    val output: BargeInOutput,
    val cutOffs: Int = 0,
    val waits: Int = 0,
    val longestWaitMs: Long = 0,
    /** At the last cut-off: what the microphone heard and what Obi was playing (dBFS); null without a cut-off. */
    val lastMicDbfs: Float? = null,
    val lastPlayedDbfs: Float? = null,
    /** At the last cut-off: whether his voice was going up (false: only silence was, so Google stopped on its own). */
    val lastLetThrough: Boolean? = null,
)

/** The cut-offs of one Live talk ([LiveEchoGate]'s levels at each). One per conversation, beside the gate. */
class LiveCutOffs {
    var count = 0
        private set
    private var mic: Float? = null
    private var played: Float? = null
    private var letThrough: Boolean? = null

    /** Google said Obi was talked over: what the gate last saw. */
    fun record(gate: LiveEchoGate) {
        count++
        mic = gate.lastMicDbfs
        played = gate.lastPlayedDbfs
        letThrough = gate.lastSent
    }

    /** The talk's numbers with [output] and the jitter's waits. */
    fun stats(output: BargeInOutput, jitter: LiveJitter<*>): LiveAudioStats =
        LiveAudioStats(output, count, jitter.underruns, jitter.longestGapMs, mic, played, letThrough)
}

/** Health's words for [LiveAudioStats] (pure). */
object LiveAudioLines {
    /** The Speed list's name for a wait for Obi's voice. */
    const val SPEED_WAIT = "Live · Obi waited for the network"

    /** "Speaker: the mic waits while Obi talks · stopped twice when he heard you (mic -22 dB, Obi -30 dB) · waited once for the network (0.4 s)". */
    fun line(a: LiveAudioStats): String {
        val mode = if (a.output == BargeInOutput.HEADPHONES) "Headphones: you can talk over Obi" else "Speaker: the mic waits while Obi talks"
        val parts = mutableListOf(mode)
        if (a.cutOffs > 0) {
            val levels = if (a.lastMicDbfs != null && a.lastPlayedDbfs != null) {
                val obi = if (a.lastPlayedDbfs <= -159f) "quiet" else "${db(a.lastPlayedDbfs)} dB"
                " (last: mic ${db(a.lastMicDbfs)} dB, Obi $obi" + (if (a.lastLetThrough == false) ", only silence was going up" else "") + ")"
            } else ""
            parts += "stopped ${times(a.cutOffs)} when he heard you$levels"
        }
        if (a.waits > 0) parts += "waited ${times(a.waits)} for the network (longest ${SpeedRules.said(a.longestWaitMs)})"
        if (a.cutOffs == 0 && a.waits == 0) parts += "no cut-offs or waits"
        return parts.joinToString(" · ")
    }

    private fun db(v: Float): Int = kotlin.math.round(v).toInt()

    private fun times(n: Int): String = when (n) {
        1 -> "once"
        2 -> "twice"
        else -> "$n times"
    }

    /** As stored with the talk: output, cut-offs, waits, longest, mic, played, let through (tab-free numbers). */
    fun encode(a: LiveAudioStats): List<String> = listOf(
        a.output.name, a.cutOffs.toString(), a.waits.toString(), a.longestWaitMs.toString(),
        a.lastMicDbfs?.toString().orEmpty(), a.lastPlayedDbfs?.toString().orEmpty(), a.lastLetThrough?.toString().orEmpty(),
    )

    fun decode(parts: List<String>): LiveAudioStats? {
        if (parts.size < 4) return null
        val output = BargeInOutput.entries.firstOrNull { it.name == parts[0] } ?: return null
        return LiveAudioStats(
            output = output,
            cutOffs = parts[1].toIntOrNull() ?: 0,
            waits = parts[2].toIntOrNull() ?: 0,
            longestWaitMs = parts[3].toLongOrNull() ?: 0,
            lastMicDbfs = parts.getOrNull(4)?.toFloatOrNull(),
            lastPlayedDbfs = parts.getOrNull(5)?.toFloatOrNull(),
            lastLetThrough = parts.getOrNull(6)?.toBooleanStrictOrNull(),
        )
    }
}
