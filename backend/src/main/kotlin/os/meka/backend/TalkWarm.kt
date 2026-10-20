package os.meka.backend

import os.meka.core.domain.TalkWarmRules
import os.meka.core.wire.TalkWarmCodec

/**
 * The orb speed pass, slice 5 (`POST /v1/talk/warm`): when a device says he's about to talk (the orb tapped, Talk
 * opened, listening started), opens the server's connections to the AI ([LanguageModelProvider.touch]) and to
 * Navilife's voice ([SpeechService.touch]) with free requests, off the request path, so the answer and its first clip
 * don't pay for new connections. At most once every [TalkWarmRules.SERVER_GAP_MS] whichever device asks. Nothing is
 * kept or logged; no answer is written, nothing is said, nothing is counted.
 */
class TalkWarm(
    private val ai: (() -> Boolean)?,
    private val voice: (() -> Boolean)?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Runs the warm-up off the request path (a virtual thread on the server; at once in tests). */
    private val start: (() -> Unit) -> Unit = { job -> Thread.ofVirtual().name("talk-warm").start(Runnable { job() }) },
) {
    private var lastMs: Long? = null
    private val lock = Any()

    /** Starts a warm-up unless one ran a moment ago (or there is nothing to warm). Never throws. */
    fun request(): TalkWarmCodec.Response {
        if (ai == null && voice == null) return TalkWarmCodec.Response(TalkWarmCodec.Response.OFF)
        synchronized(lock) {
            val now = nowMs()
            if (!TalkWarmRules.serverDue(lastMs, now)) return TalkWarmCodec.Response(TalkWarmCodec.Response.RECENT)
            lastMs = now
        }
        // Both at once: each opens its own connection.
        ai?.let { a -> runCatching { start { runCatching { a() } } } }
        voice?.let { v -> runCatching { start { runCatching { v() } } } }
        return TalkWarmCodec.Response(TalkWarmCodec.Response.WARMING)
    }
}
