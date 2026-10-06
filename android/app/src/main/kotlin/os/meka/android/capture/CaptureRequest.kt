package os.meka.android.capture

/**
 * What opened the capture sheet (build plan M1, "Capture from anywhere"). Pure so it can be unit-tested without
 * Android: [CaptureActivity] passes the intent's parts in.
 *
 * Whatever arrives here is only ever shown in the sheet; nothing is saved until Meka taps Add.
 */
data class CaptureRequest(
    val text: String,
    val subject: String?,
    /** Open straight into on-device speech recognition (widget mic, tile long-press). */
    val startVoice: Boolean,
)

object CaptureRequests {
    /** MEKA's own entry points: the home-screen widget and the quick-settings tile. */
    const val ACTION_CAPTURE = "os.meka.action.CAPTURE"
    const val EXTRA_VOICE = "os.meka.capture.voice"

    // Platform action and extra names, copied so this file needs no Android classes.
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"

    /** Shared text longer than this is cut before it reaches the sheet (the core caps notes anyway). */
    const val MAX_SHARED = 20_000

    /**
     * @param text EXTRA_TEXT for a share, EXTRA_PROCESS_TEXT for "Capture in MEKA" on selected text.
     * Returns null for anything we don't handle (another action, or a share that isn't text).
     */
    fun from(action: String?, mimeType: String?, text: CharSequence?, subject: CharSequence?, voice: Boolean): CaptureRequest? =
        when (action) {
            ACTION_CAPTURE -> CaptureRequest("", null, startVoice = voice)
            ACTION_SEND, ACTION_PROCESS_TEXT -> {
                val isText = mimeType == null || mimeType.startsWith("text/")
                val body = text?.toString()?.take(MAX_SHARED).orEmpty()
                val subj = subject?.toString()?.trim()?.takeIf { it.isNotEmpty() && action == ACTION_SEND }
                if (!isText || (body.isBlank() && subj == null)) null else CaptureRequest(body, subj, startVoice = false)
            }
            else -> null
        }

    /** The recogniser's best guess (first non-blank), tidied; null when it heard nothing. */
    fun spoken(results: List<String>?): String? =
        results?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercaseChar() }

    /** What goes to the core: the (edited) title on the first line, kept notes after it. */
    fun compose(title: String, notes: String?): String =
        if (notes.isNullOrBlank()) title.trim() else title.trim() + "\n" + notes
}
