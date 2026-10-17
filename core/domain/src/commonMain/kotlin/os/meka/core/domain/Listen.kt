package os.meka.core.domain

/**
 * Which recogniser writes down what Meka says ("the mic should just add it, fast, and understand him", step 4;
 * ADR-006 addendum 2026-10-17): the phone's (or Mac's) **online** speech whenever there's signal, the device's own
 * on-device one only without it. Meka said yes on 2026-10-17 ("use Google's online speech when I have signal").
 */
enum class ListenEngine { ONLINE, ON_DEVICE }

/** Rules for listening on every device: which recogniser, the fallback, and when he has finished (step 3). */
object ListenRules {
    /**
     * Online when there is signal and an online recogniser is there; else the on-device one; null when neither can
     * listen (no signal and no on-device English).
     */
    fun engine(hasSignal: Boolean, onlineAvailable: Boolean, onDeviceAvailable: Boolean): ListenEngine? = when {
        hasSignal && onlineAvailable -> ListenEngine.ONLINE
        onDeviceAvailable -> ListenEngine.ON_DEVICE
        else -> null
    }

    /**
     * After [engine] failed: a network or server failure while online listens again with the device's own recogniser
     * (once; the caller doesn't ask again for the same words). Anything else, or a failure on the device, is final.
     */
    fun fallback(engine: ListenEngine, networkFailure: Boolean, onDeviceAvailable: Boolean): ListenEngine? =
        if (engine == ListenEngine.ONLINE && networkFailure && onDeviceAvailable) ListenEngine.ON_DEVICE else null

    /** The pause after his words that ends listening (step 3: "about 0.8 s after he stops"). */
    const val END_PAUSE_MS = TalkEndpoint.PAUSE_MS

    /** A microphone level (0 … 1, as the orb draws it) at or over this is him still speaking. */
    const val VOICE_LEVEL = 0.35f

    /** Voice on the microphone keeps listening open at most this long past his last new word (a noisy room can't hold it). */
    const val VOICE_GRACE_MS = 1_500L

    /**
     * When he last made a sound: the later of the transcript last changing and the microphone last hearing his voice
     * ([lastVoiceMs], null when never), so an online recogniser whose words lag his voice doesn't cut him off; the voice
     * counts only up to [VOICE_GRACE_MS] past the last word.
     */
    fun lastSound(lastWordsMs: Long, lastVoiceMs: Long?): Long =
        if (lastVoiceMs == null) lastWordsMs else maxOf(lastWordsMs, minOf(lastVoiceMs, lastWordsMs + VOICE_GRACE_MS))

    /** Whether a microphone [level] counts as his voice. */
    fun isVoice(level: Float): Boolean = level >= VOICE_LEVEL

    /**
     * The capture sheet's line while it listens: "Listening · Google's speech" online ([onlineName] is the device's
     * provider), "Listening · on this phone, no signal" on the device.
     */
    fun listeningLine(engine: ListenEngine, onlineName: String, device: String = "phone"): String = when (engine) {
        ListenEngine.ONLINE -> "Listening · $onlineName"
        ListenEngine.ON_DEVICE -> "Listening · on this $device, no signal"
    }

    /** The Fold's and the watch's online provider, as the lines name it. */
    const val GOOGLE = "Google's speech"
    /** The Mac's. */
    const val APPLE = "Apple's speech"
}

/** One "speech to Added" measurement (step 3): from the end of his words to "Added" on screen, kept in memory only. */
data class HeardTiming(val ms: Long, val engine: ListenEngine)

/** Activity's line about how quickly what he says is added (step 3: keep it under about a second). */
object HeardTimingRules {
    const val KEPT = 10
    const val TARGET_MS = 1_000L
    const val LABEL = "Speech to Added"

    /**
     * Newest first: "Speech to Added · 0.6 s · 1.4 s slow · 0.7 s offline · middle 0.7 s" ("slow" over [TARGET_MS],
     * "offline" when the device's own recogniser heard it; the middle once there are three). Null before the first.
     */
    fun timingLine(timings: List<HeardTiming>): String? {
        val last = timings.takeLast(KEPT).asReversed()
        if (last.isEmpty()) return null
        val parts = last.map { t ->
            SpeechRules.seconds(t.ms) + (if (t.ms > TARGET_MS) " slow" else "") + (if (t.engine == ListenEngine.ON_DEVICE) " offline" else "")
        }
        val sorted = last.map { it.ms }.sorted()
        val middle = if (sorted.size >= 3) " · middle ${SpeechRules.seconds(sorted[sorted.size / 2])}" else ""
        return "$LABEL · ${parts.joinToString(" · ")}$middle"
    }
}
