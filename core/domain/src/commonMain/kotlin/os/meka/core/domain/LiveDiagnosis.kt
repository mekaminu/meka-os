package os.meka.core.domain

/**
 * Why Live does or doesn't engage (Meka 2026-10-24 19:31 + 19:38: "Obi takes ~10 s to answer each turn, even with Live
 * switched On"; non-AI, pure). Two halves:
 *
 * - **The key, as Navilife's server last found it** ([KeyState]): the server asks Google for a one-use token with the
 *   stored key (the `x-goog-api-key` header) at start-up, when a device asks for the meter and for every session, and
 *   keeps Google's exact answer: its HTTP status and its own message (the key itself never; anything shaped like a
 *   key is cut out of the message, [redact]). Health → Obi's voice and Live's settings line say it ([keyLine]), and a
 *   refusal names the Google Cloud console step that fixes it ([consoleStep]).
 * - **Each talk on this device** ([LiveOutcome]): whether Live opened, or why normal Obi answered instead
 *   ([LiveFallback]): never quietly. The stage says it for that talk ([stageLine]) and Health keeps the last one
 *   ([healthLine]). Reasons and times only, never words.
 *
 * Waiting: [LiveTalkRules.FALLBACK_MS] for Navilife's server to hand over a token, then, with a token in hand, up to
 * [CONNECT_MS] for Google to set the session up ([fallBack]): normal Obi takes about ten seconds a turn, so a Live
 * session that needs a few seconds to open is still the quicker answer.
 */
object LiveDiagnosisRules {
    /** With a token in hand, how long (from asking) Google may take to set the session up before normal Obi answers. */
    const val CONNECT_MS = 6_000L

    /** Whether to give up on Live now: [tokenIn] once the server's ready session has arrived. */
    fun fallBack(askedAtMs: Long, nowMs: Long, setUp: Boolean, tokenIn: Boolean): Boolean =
        !setUp && nowMs - askedAtMs >= (if (tokenIn) CONNECT_MS else LiveTalkRules.FALLBACK_MS)

    /** The key's state as the meter carries it (wire values; an older server sends none: [UNCHECKED]). */
    enum class KeyState(val wire: String) {
        /** Google gave a token with the key. */
        READY("ready"),
        /** No secret, or an empty one: nothing to try. */
        NO_KEY("no_key"),
        /** The secret is there but holds no usable key (not `{"api_key": "…"}` or a bare key). */
        BAD_SECRET("bad_secret"),
        /** Google answered with a refusal (a 4xx). */
        REFUSED("refused"),
        /** Google couldn't be reached, or answered with a 5xx. */
        UNREACHABLE("unreachable"),
        /** Not tried yet since the server started. */
        UNCHECKED("unchecked");

        companion object {
            fun of(wire: String?): KeyState = entries.firstOrNull { it.wire == wire } ?: UNCHECKED
        }
    }

    /** The longest message from Google kept or shown. */
    const val MAX_DETAIL = 200

    /**
     * Google's [message] with anything that could be a key cut out: the stored [key] itself, Google's "AQ." auth keys
     * and the older "AIza" keys, and any long unbroken run of key-like characters. Trimmed to [MAX_DETAIL].
     */
    fun redact(message: String?, key: String? = null): String? {
        var m = message?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        key?.takeIf { it.length >= 8 }?.let { m = m.replace(it, "…") }
        m = m.replace(Regex("AQ\\.[A-Za-z0-9_\\-.]{6,}"), "AQ.…")
        m = m.replace(Regex("AIza[A-Za-z0-9_\\-]{6,}"), "AIza…")
        m = m.replace(Regex("[A-Za-z0-9_\\-]{32,}"), "…")
        return if (m.length <= MAX_DETAIL) m else m.take(MAX_DETAIL - 1).trimEnd() + "…"
    }

    /** Health's and the settings' line for the key: "Live ready" / "Live · Google said 403: …" / "Live · no key …". */
    fun keyLine(state: KeyState, code: Int?, detail: String?): String = when (state) {
        KeyState.READY -> "Live ready"
        KeyState.NO_KEY -> "Live · no key on Navilife's server ($SECRET is empty)"
        KeyState.BAD_SECRET -> "Live · $SECRET holds no usable key: it should read {\"api_key\": \"…\"}"
        KeyState.REFUSED -> "Live · Google said ${code ?: "no"}" + (detail?.let { ": $it" } ?: "")
        KeyState.UNREACHABLE -> "Live · couldn't reach Google" + (code?.let { " (HTTP $it)" } ?: "") + (detail?.let { ": $it" } ?: "")
        KeyState.UNCHECKED -> "Live · the server hasn't tried the key yet"
    }

    /** Whether the key's state is worth a look (amber in Health). */
    fun keyFault(state: KeyState): Boolean = state != KeyState.READY && state != KeyState.UNCHECKED

    /**
     * The Google Cloud console step that fixes a refusal, from Google's [code] and [detail]; null when there is nothing
     * he can do in the console (Google down, or a refusal we don't recognise; the line still quotes Google).
     */
    fun consoleStep(state: KeyState, code: Int?, detail: String?): String? {
        val d = detail.orEmpty().lowercase()
        return when {
            state == KeyState.NO_KEY || state == KeyState.BAD_SECRET ->
                "In AWS Secrets Manager, open $SECRET and set its value to {\"api_key\": \"<the key>\"} (plaintext, the key only)."
            state != KeyState.REFUSED -> null
            "has not been used" in d || "is disabled" in d || "service_disabled" in d || "enable it" in d ->
                "In the Google Cloud console for the key's project, open APIs & Services → Library → Generative Language API (Gemini API) → Enable."
            "api_key_service_blocked" in d || "are blocked" in d || "api restrictions" in d || "blocked" in d ->
                "In the Google Cloud console → APIs & Services → Credentials, open the key and add Generative Language API (Gemini API) to its API restrictions."
            "iam_permission_denied" in d || "aiplatform" in d || "service account" in d || ("permission" in d && code == 403) ->
                "In the Google Cloud console → IAM, give the key's service account the role the error names (for the Gemini API, its own project's Gemini API access), then try again."
            "not valid" in d || ("invalid" in d && "key" in d) || code == 401 ->
                "Google doesn't accept the key: in Google AI Studio → API keys, check the key is still there and copy it into $SECRET again."
            "model" in d && (code == 404 || "not found" in d || "not supported" in d) ->
                "Google doesn't offer the Live model to this key: set MEKA_LIVE_MODEL to a Live model AI Studio lists for it."
            "billing" in d || "quota" in d || code == 429 ->
                "In Google AI Studio → Billing (or the Cloud console's Billing), check the key's project has billing on and quota left."
            else -> null
        }
    }

    /** The secret holding the Gemini key (named, never its value). */
    const val SECRET = "meka-os-dev/ai/gemini"

    /** Live's settings line when the meter's key state says it can't run (null: the meter's own line). */
    fun settingsLine(state: KeyState, code: Int?, detail: String?): String? =
        if (keyFault(state)) keyLine(state, code, detail).removePrefix("Live · ").replaceFirstChar { it.uppercase() } else null

    // ---- Each talk on this device ----

    /** Why normal Obi answered instead of Live. */
    enum class LiveFallback(val line: String) {
        /** The server has no usable key. */
        NO_KEY("no Gemini key on Navilife's server"),
        /** This month's Live allowance is used up. */
        OVER("this month's Live allowance is used up"),
        /** The server asked Google and Google refused (the detail quotes Google). */
        REFUSED("Google refused the session"),
        /** Navilife's server didn't answer (offline, an older server, an error). */
        NO_ANSWER("Navilife's server didn't answer"),
        /** The server's answer came after [LiveTalkRules.FALLBACK_MS]. */
        SLOW_SERVER("Navilife's server took over 2 s"),
        /** The token came but Google hadn't set the session up within [CONNECT_MS]. */
        SLOW_GOOGLE("Google took over 6 s to open the session"),
        /** Google's connection closed before the session was set up (the detail quotes its close reason). */
        CLOSED("Google closed the connection before it was set up"),
        /** The microphone couldn't be opened for Live. */
        MIC("the microphone couldn't open for Live"),
    }

    /** One talk with Live on: it opened ([live]) or why not ([fallback], with Google's or the server's [detail]). */
    data class LiveOutcome(
        val atMs: Long,
        val live: Boolean,
        val fallback: LiveFallback?,
        val detail: String?,
        /** 2026-10-25: how a talk that ran in Live sounded (the mode, cut-offs, waits); null for an older one or a fallback. */
        val audio: LiveAudioStats? = null,
    )

    /** The server's session answer as a reason: [state] is the session's wire state, [reason] its reason line. */
    fun fromSession(state: String?, reason: String?): LiveFallback? = when (state) {
        null -> LiveFallback.NO_ANSWER
        "ready" -> null
        "off" -> LiveFallback.NO_KEY
        "over" -> LiveFallback.OVER
        else -> if (reason == null) LiveFallback.NO_ANSWER else LiveFallback.REFUSED
    }

    /** Google's WebSocket close as a detail: "closed 1008: models/x is not found…"; null for a plain close. */
    fun closeLine(code: Int, reason: String?): String? {
        val r = redact(reason)
        if (code == 1000 && r == null) return null
        return "closed $code" + (r?.let { ": $it" } ?: "")
    }

    /** "Google said 403: …" from the reason the server gave, kept short; null when there's nothing more to say. */
    fun detail(reason: String?): String? = redact(reason)

    /** Under the orb for that talk: "Normal Obi · Live unavailable: Google said 403: …". */
    fun stageLine(fallback: LiveFallback, detail: String?): String =
        "Normal Obi · Live unavailable: " + (detail?.takeIf { fallback == LiveFallback.REFUSED || fallback == LiveFallback.CLOSED || fallback == LiveFallback.NO_KEY } ?: fallback.line)

    /** Health's line for the last talk: "Last talk 21:40 · Live" / "Last talk 21:40 · normal Obi: Google took over 6 s …". */
    fun healthLine(o: LiveOutcome?, nowMs: Long, cal: LocalCalendar): String? = o?.let {
        val at = HealthRules.at(it.atMs, nowMs, cal)
        if (it.live || it.fallback == null) "Last talk $at · Live" + (it.audio?.let { a -> "\n" + LiveAudioLines.line(a) } ?: "")
        else "Last talk $at · normal Obi: ${it.fallback.line}" + (it.detail?.let { d -> " ($d)" } ?: "")
    }

    /** A talk as text for the device's storage: the time, Live or the reason's name, and the redacted detail. */
    fun encode(o: LiveOutcome): String = (listOf(o.atMs.toString(), if (o.live) "LIVE" else (o.fallback?.name ?: "LIVE"), redact(o.detail).orEmpty().replace('\n', ' ').replace('\t', ' ')) +
        (o.audio?.let { LiveAudioLines.encode(it) } ?: emptyList())).joinToString("\t")

    fun decode(text: String?): LiveOutcome? {
        val parts = text?.split('\t') ?: return null
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val detail = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        if (parts[1] == "LIVE") return LiveOutcome(at, true, null, null, LiveAudioLines.decode(parts.drop(3)))
        val f = LiveFallback.entries.firstOrNull { it.name == parts[1] } ?: return null
        return LiveOutcome(at, false, f, detail)
    }

    /** The device's local key for the last talk. */
    const val KEY = "live.lastOutcome"

    /** Health's Live line (null while Live is off and the key is fine): the key's state, then the last talk. */
    fun health(h: LiveHealth?, nowMs: Long, cal: LocalCalendar): String? {
        if (h == null || !h.on) return null
        val key = h.keyState?.let { keyLine(it, h.keyCode, h.keyDetail) } ?: "Live · couldn't ask Navilife's server"
        return listOfNotNull(key, healthLine(h.last, nowMs, cal)).joinToString("\n")
    }
}

/** Health's Live facts: the switch, the key as the server last found it (null: couldn't ask) and the last talk here. */
data class LiveHealth(
    val on: Boolean,
    val keyState: LiveDiagnosisRules.KeyState?,
    val keyCode: Int? = null,
    val keyDetail: String? = null,
    val last: LiveDiagnosisRules.LiveOutcome? = null,
)
