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
class LiveEchoGate {
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
            return true
        }
        val played = playedAt(nowMs)
        lastPlayedDbfs = played
        if (micDbfs < LiveAudioRules.threshold(played, echoDb)) {
            overSince = -1
            if (played > LiveAudioRules.LEARN_ABOVE_DBFS) {
                echoDb = (echoDb + LiveAudioRules.ECHO_ALPHA * ((micDbfs - played) - echoDb))
                    .coerceIn(LiveAudioRules.MIN_ECHO_DB, LiveAudioRules.MAX_ECHO_DB)
            }
            return false
        }
        if (overSince < 0) overSince = nowMs
        return nowMs - overSince + chunkMs >= LiveAudioRules.HOLD_MS
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
    }

    private fun release(nowMs: Long): List<T> {
        if (held.isEmpty() || !LiveAudioRules.start(heldMs, if (firstAt < 0) 0 else nowMs - firstAt, turnDone)) return emptyList()
        val out = held.toList()
        lastFrom = maxOf(until, nowMs)
        until = lastFrom + heldMs
        held.clear()
        heldMs = 0
        firstAt = -1
        playing = true
        return out
    }
}
