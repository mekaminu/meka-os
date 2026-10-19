package os.meka.core.domain

/**
 * Where Navilife's AI stands, as the device last heard from its server ([AskRules.statusView]): answering, switched
 * off, this month's budget used up, not answering (a failure or a refused key), or not reached at all.
 */
enum class AiMode { ON, OFF, OVER, FAILING, OFFLINE, NOT_CONNECTED }

/**
 * The features that ask Navilife's AI, each with what still happens without it (build plan V1, AI layer completeness:
 * "every AI feature shows a clear line when AI is off, over budget or offline, with the deterministic fallback where
 * there is one"). [fallback] is what the person gets instead, said plainly.
 */
enum class AiFeature(val label: String, val fallback: String) {
    ASK("Ask and Talk", "adding a task or shopping, your fast, money questions, help and Search still answer on this device"),
    BRIEF("Morning brief", "read from your day as it is, in Navilife's own words"),
    DRAFTS("Drafted replies", "nothing is drafted · write your reply in the editor and Send works as usual"),
    CHASES("Chases", "the plain chase is offered, for you to change and send"),
    HANDLE_THIS("Handle this", "nothing is read · share it again once the AI is back"),
    VOICE_NOTES("Voice notes", "kept with your words · Make cards on the note once the AI is back"),
    MESSAGES("Messages", "groups still go to the digest · no replies are drafted"),
    CALLS("Call assistant", "messages still come with their words, without the one-line summary"),
}

/** One row of Settings' "what still works" list: "Morning brief" · "read from your day as it is…". */
data class AiFallbackLine(val label: String, val detail: String)

/**
 * The line each AI feature shows while the AI can't answer, and Settings' list of what still works. Non-AI, pure;
 * nothing is stored. While the AI answers there is no line at all.
 */
object AiFeatureRules {
    /** The mode from the server's status: [state] on · off · anything else failing; [level] over when the budget's spent. */
    fun mode(state: String, level: String?, keyRefused: Boolean): AiMode = when {
        state == "off" -> AiMode.OFF
        keyRefused -> AiMode.FAILING
        level == "over" -> AiMode.OVER
        state == "on" -> AiMode.ON
        else -> AiMode.FAILING
    }

    /** What comes before a feature's fallback: "Navilife's AI is off". Null while it answers. */
    fun prefix(mode: AiMode): String? = when (mode) {
        AiMode.ON -> null
        AiMode.OFF -> "Navilife's AI is off"
        AiMode.OVER -> "This month's AI budget is used up till the 1st"
        AiMode.FAILING -> "Navilife's AI isn't answering"
        AiMode.OFFLINE -> "Can't reach Navilife's AI just now"
        AiMode.NOT_CONNECTED -> "Navilife's AI works once this device is connected"
    }

    /**
     * The feature's line: "Navilife's AI is off · read from your day as it is, in Navilife's own words". Null while the
     * AI answers, or before the device has heard anything ([view] null): nothing is said until it knows.
     */
    fun line(feature: AiFeature, view: AiStatusView?): String? {
        val p = prefix(view?.mode ?: return null) ?: return null
        return "$p · ${feature.fallback}"
    }

    /** Over Settings' list: "While it's off" · "Until the 1st" · …; null while it answers. */
    fun heading(mode: AiMode): String? = when (mode) {
        AiMode.ON -> null
        AiMode.OFF -> "While it's off"
        AiMode.OVER -> "Until the 1st"
        AiMode.FAILING -> "Until it answers again"
        AiMode.OFFLINE -> "While it can't be reached"
        AiMode.NOT_CONNECTED -> "Until this device is connected"
    }

    /** Settings' list of what each feature does instead; empty while the AI answers. */
    fun fallbacks(mode: AiMode): List<AiFallbackLine> =
        if (mode == AiMode.ON) emptyList() else AiFeature.entries.map { AiFallbackLine(it.label, it.fallback) }

    /** [view] with its [mode] and, unless it answers, the heading and list of what still works. */
    fun withMode(view: AiStatusView, mode: AiMode): AiStatusView =
        view.copy(mode = mode, fallbackHeading = heading(mode), fallbacks = fallbacks(mode))
}
