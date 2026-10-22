package os.meka.core.domain

/**
 * Ada's Live mode, slice 2c (non-AI, pure): Live after the hello, and a conversation that carries on past one
 * session's window.
 *
 * **After the hello.** With Live on, "Hello, Navilife" (the orb tapped, the phrase, the bedside clock's orb) still
 * says the greeting at once in Navilife's own voice from memory, and while it plays the Live session is asked for, so
 * when the hello ends (or he talks over it) Ada carries on live instead of normal listening. The session is told what
 * she just said ([greeted]) so she doesn't greet him twice. A session asked for but never used (he said "thanks" or
 * tapped the orb during the hello) is reported as 0 seconds, so it costs nothing.
 *
 * **Past the window.** Google ends a Live connection after about ten minutes and Navilife's token is for one
 * [LiveModeRules.TOKEN_MS] window. Google keeps a resumption handle for the conversation; when the window is closing
 * (or Google says it is going away) the device asks the server for a fresh token carrying that handle and carries on
 * where it was, the microphone and her voice never closed. It does so only while he is still talking ([IDLE_MS]) and
 * at most [MAX_RESUMES] times (about an hour), so an open microphone left on a table never runs up the month; each
 * window is metered against the £15 cap like any session, and at the cap Live ends as before.
 */
object LiveHandOnRules {
    /** At most this many fresh windows after the first (about an hour in all). */
    const val MAX_RESUMES = 5

    /** He must have spoken within this long for the conversation to carry on into a fresh window. */
    const val IDLE_MS = 2 * 60_000L

    /** The longest resumption handle passed on (Google's are far shorter; anything longer is not one). */
    const val MAX_HANDLE = 2_000

    /** The longest greeting passed to the Live session. */
    const val MAX_GREETED = 400

    /** A session asked for during the hello is used only while its token can still open one (a little inside [LiveModeRules.START_MS]). */
    const val PREPARED_FOR_MS = LiveModeRules.START_MS - 10_000L

    /**
     * Whether the conversation carries on into a fresh window: Google gave a [handle], fewer than [MAX_RESUMES] were
     * used, and he spoke within [IDLE_MS] of [nowMs] ([lastHeardAtMs], elapsed ms; null: not a word yet).
     */
    fun resume(handle: String?, resumes: Int, lastHeardAtMs: Long?, nowMs: Long): Boolean =
        handle(handle) != null && resumes < MAX_RESUMES && lastHeardAtMs != null && nowMs - lastHeardAtMs <= IDLE_MS

    /** [h] as a handle worth passing on (one line, printable, not too long), else null. */
    fun handle(h: String?): String? {
        val t = h?.trim() ?: return null
        if (t.isEmpty() || t.length > MAX_HANDLE || t.any { it.code < 0x21 || it.code > 0x7e }) return null
        return t
    }

    /** The hello as the Live session is told it: one line, at most [MAX_GREETED]; null when there was none. */
    fun greeted(greeting: String?): String? =
        greeting?.replace(Regex("""\s+"""), " ")?.trim()?.take(MAX_GREETED)?.takeIf { it.isNotEmpty() }

    /** Whether a session asked for at [preparedAtMs] (elapsed) can still be opened at [nowMs]. */
    fun preparedUsable(preparedAtMs: Long, nowMs: Long): Boolean = nowMs - preparedAtMs in 0..PREPARED_FOR_MS
}
