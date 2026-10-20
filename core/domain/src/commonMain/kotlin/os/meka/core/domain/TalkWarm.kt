package os.meka.core.domain

/**
 * The orb speed pass, slice 5: **the server gets ready as he starts talking.** When the orb is tapped, Talk opens or
 * listening starts, the device tells Navilife's server (`POST /v1/talk/warm`, nothing he said, nothing about him), and
 * the server opens its connections to the AI and to Navilife's voice with free requests (the model list, the voice
 * list: no answer written, nothing said, nothing counted), so the answer that follows doesn't pay for new connections.
 * (Non-AI, pure.)
 *
 * A connection left idle is closed again within about half a minute, so a warm-up older than [DEVICE_GAP_MS] is worth
 * sending again; a newer one isn't. The server keeps its own gap ([SERVER_GAP_MS], for the Fold and the Mac together).
 */
object TalkWarmRules {
    /** A device sends at most one warm-up this often. */
    const val DEVICE_GAP_MS = 20_000L

    /** The server warms at most this often, whichever device asks. */
    const val SERVER_GAP_MS = 15_000L

    /** Whether the device sends a warm-up now: none yet, or the last at [lastMs] is [DEVICE_GAP_MS] old (a clock jump back counts as due). */
    fun due(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs >= DEVICE_GAP_MS || nowMs < lastMs

    /** Whether the server warms now (the same rule with its own gap). */
    fun serverDue(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs >= SERVER_GAP_MS || nowMs < lastMs
}
