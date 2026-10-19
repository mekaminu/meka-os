package os.meka.core.domain

/**
 * Which engine to ask next when a better one can fail (small refinement, Meka 2026-10-19: "engine fallback latches for
 * the session"): Google's voice (falling back to Amazon Polly) on the server, and Google's / Apple's online speech
 * (falling back to the device's own recogniser) on the Fold, the watch and the Mac. Before this a failure fell back for
 * that one sentence and the next one tried the failing engine again; now a failure — or [TIMEOUTS] timeouts in a row —
 * switches to the fallback for the rest of that Talk session or [HOLD_MS], whichever is longer, and then the next ask
 * quietly tries the better engine again (a probe): it works and the switch ends, it fails and the hold starts over.
 * No setting; held in memory only (a restart starts on the better engine).
 *
 * Immutable: each event returns the next latch.
 */
data class EngineLatch(
    /** Timeouts of the better engine in a row (reset by a success or a switch). */
    val timeoutsInRow: Int = 0,
    /** When the fallback took over; null while the better engine is in use. */
    val switchedAtMs: Long? = null,
    /** The earliest the better engine is tried again (while switched). */
    val holdUntilMs: Long? = null,
    /** How long the last finished switch lasted (from the switch to the probe that worked); null before the first. */
    val lastSwitchedForMs: Long? = null,
) {
    val switched: Boolean get() = switchedAtMs != null
}

object EngineLatchRules {
    /** The least time the fallback keeps the job after a switch (a longer Talk session keeps it for the session). */
    const val HOLD_MS = 15 * 60_000L

    /** Timeouts in a row that switch (one failure that isn't a timeout switches at once). */
    const val TIMEOUTS = 2

    /**
     * Whether to ask the fallback now: switched, and still inside the hold or the Talk session that's open
     * ([sessionOpen]; the server has none and passes false). Otherwise the better engine is asked (a probe while
     * switched).
     */
    fun useFallback(l: EngineLatch, nowMs: Long, sessionOpen: Boolean = false): Boolean =
        l.switched && (sessionOpen || nowMs < (l.holdUntilMs ?: 0))

    /** The better engine failed ([timeout]: it was too slow rather than refusing). */
    fun failed(l: EngineLatch, nowMs: Long, timeout: Boolean): EngineLatch = when {
        // A probe that failed: the hold starts over; the switch keeps its start.
        l.switched -> l.copy(timeoutsInRow = 0, holdUntilMs = nowMs + HOLD_MS)
        timeout && l.timeoutsInRow + 1 < TIMEOUTS -> l.copy(timeoutsInRow = l.timeoutsInRow + 1)
        else -> l.copy(timeoutsInRow = 0, switchedAtMs = nowMs, holdUntilMs = nowMs + HOLD_MS)
    }

    /** The better engine worked: a probe ends the switch (its length kept for Health); timeouts start counting again. */
    fun worked(l: EngineLatch, nowMs: Long): EngineLatch {
        val at = l.switchedAtMs ?: return if (l.timeoutsInRow == 0) l else l.copy(timeoutsInRow = 0)
        return EngineLatch(lastSwitchedForMs = (nowMs - at).coerceAtLeast(0))
    }

    /** How long the fallback has had the job (switched now) or had it last time (null: never switched). */
    fun switchedForMs(l: EngineLatch, nowMs: Long): Long? =
        l.switchedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: l.lastSwitchedForMs

    /** "4 min" · "1 h 10 min" · "under a minute", for Health's and the listening lines. */
    fun duration(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }
}

/**
 * One device's listening latch (main thread only): Talk and the capture sheet share it, so after Google's (or Apple's)
 * online speech fails, both listen with the device's own recogniser until [EngineLatchRules.useFallback] says to try
 * online again. Talk marks its session open ([sessionOpen]) so the switch lasts the whole conversation.
 */
class ListenLatch {
    var latch: EngineLatch = EngineLatch()
        private set

    /** A Talk conversation is under way (set by Talk at its start and end). */
    var sessionOpen: Boolean = false

    /** Whether the online recogniser is resting now (the device's own listens, when it can). */
    fun resting(nowMs: Long): Boolean = EngineLatchRules.useFallback(latch, nowMs, sessionOpen)

    /** Online speech failed before any words ([timeout]: the network was too slow). */
    fun failed(nowMs: Long, timeout: Boolean) { latch = EngineLatchRules.failed(latch, nowMs, timeout) }

    /** Online speech wrote down what he said. */
    fun worked(nowMs: Long) { latch = EngineLatchRules.worked(latch, nowMs) }
}
