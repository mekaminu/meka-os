package os.meka.core.domain

/**
 * Obi's Live mode, slice 2 (non-AI, pure): how a Live conversation runs on a device and how its settings read.
 *
 * Live is a synced switch ([MekaVoiceFields.LIVE], off until Meka turns it on) with its own voice
 * ([MekaVoiceFields.LIVE_VOICE], one of Google's prebuilt Live voices, [LiveModeRules.DEFAULT_VOICE] until he picks
 * one). With it on, starting Talk opens a Gemini Live session through Navilife's server; anything that keeps the
 * session from being ready within [FALLBACK_MS] (no key, the month's cap, Google refusing, no signal, a slow open)
 * hands over to normal Obi at once, so he is never left waiting on a dead orb.
 */
object LiveTalkRules {
    /** From asking for a session to Google saying it is set up: past this, normal Obi answers instead. */
    const val FALLBACK_MS = 2_000L

    /** Microphone audio Google expects: 16 kHz, 16-bit, mono. */
    const val IN_RATE = 16_000
    /** What Google speaks back: 24 kHz, 16-bit, mono. */
    const val OUT_RATE = 24_000

    /** One microphone chunk sent up: 40 ms (small, so a barge-in is heard at once). */
    const val CHUNK_MS = 40
    val CHUNK_SAMPLES: Int get() = IN_RATE * CHUNK_MS / 1_000

    /** How long after the last audio arrived Obi counts as having finished speaking (the orb goes back to listening). */
    const val SPEAKING_TAIL_MS = 300L

    /** A session ends this long before its token's window closes, so it never cuts off mid-word. */
    const val END_BEFORE_MS = 15_000L

    /**
     * On the phone's own speaker the microphone hears Obi too: while he speaks, only a chunk clearly louder than
     * [SPEAKER_OVER_DBFS] (Meka talking over him) goes up, the rest goes as silence so Google doesn't hear Obi as Meka.
     * With headphones everything goes up.
     */
    const val SPEAKER_OVER_DBFS = -28f

    fun sendWhileSpeaking(output: BargeInOutput, dbfs: Float): Boolean =
        output == BargeInOutput.HEADPHONES || dbfs >= SPEAKER_OVER_DBFS

    /** When the queued audio will have played (elapsed ms), after a chunk of [bytes] at [OUT_RATE] lands at [nowMs]. */
    fun playUntil(previousUntilMs: Long, nowMs: Long, bytes: Int): Long =
        maxOf(previousUntilMs, nowMs) + bytes.coerceAtLeast(0) * 1_000L / (OUT_RATE * 2)

    /** Obi is still speaking at [nowMs] (his queued audio, plus a short tail). */
    fun speaking(playUntilMs: Long, nowMs: Long): Boolean = nowMs < playUntilMs + SPEAKING_TAIL_MS

    /** Whole seconds of 16-bit mono audio in [bytes] at [rate], rounded up (what the usage report counts). */
    fun seconds(bytes: Long, rate: Int): Long {
        if (bytes <= 0 || rate <= 0) return 0
        val perSecond = rate * 2L
        return (bytes + perSecond - 1) / perSecond
    }

    /** The orb's level, 0 … 1, from a chunk's loudness in dBFS ([BargeInRules.dbfs]): -50 dB and below is still. */
    fun level(dbfs: Float): Float = ((dbfs + 50f) / 40f).coerceIn(0f, 1f)

    /** When the session should end by itself (elapsed ms on the device), from its token's [expiresAtMs] in wall time. */
    fun endAtElapsed(nowElapsedMs: Long, nowWallMs: Long, expiresAtMs: Long): Long =
        nowElapsedMs + (expiresAtMs - nowWallMs - END_BEFORE_MS).coerceIn(0, LiveModeRules.TOKEN_MS)

    /** Whether Google's set-up came too late (or never) and normal Obi takes over. */
    fun fallBack(askedAtMs: Long, nowMs: Long, setUp: Boolean): Boolean = !setUp && nowMs - askedAtMs >= FALLBACK_MS

    /** One of Google's Live voices as the picker shows it. */
    data class Voice(val name: String, val detail: String)

    /**
     * The voices the picker offers (Google's prebuilt Live voices with Google's own one-word character), the default
     * first: Obi is a man (the Obi plan, slice 2), so the men lead; the women he heard before stay offered after them.
     * The Live prompt asks for a British accent whichever voice speaks.
     */
    val VOICES: List<Voice> = listOf(
        Voice("Charon", "Informative · Obi's voice"),
        Voice("Orus", "Firm"),
        Voice("Iapetus", "Clear"),
        Voice("Achird", "Friendly"),
        Voice("Umbriel", "Easy-going"),
        Voice("Puck", "Upbeat"),
        Voice("Kore", "Firm · a woman's voice"),
        Voice("Aoede", "Breezy · a woman's voice"),
        Voice("Leda", "Youthful · a woman's voice"),
        Voice("Sulafat", "Warm · a woman's voice"),
        Voice("Achernar", "Soft · a woman's voice"),
        Voice("Vindemiatrix", "Gentle · a woman's voice"),
    )

    /**
     * Slice 4: the one tool a Live session has so far: Obi asks Navilife to search the web for something about the
     * world. The device checks the query ([LiveFactsRules.searchable]: never his own life) before Navilife's server
     * searches it with its own search key, the same way Ask's live answers are found.
     */
    const val SEARCH_TOOL = "search_the_web"

    /** What the model is told when a search was refused because it touched his own life. */
    const val SEARCH_REFUSED = "Navilife never searches the web for anything about his own life, family or plans. Answer from the <today> block, or say plainly you don't know."
    /** ... when the search couldn't run (no search key, the day's searches used, slow or failed). */
    const val SEARCH_FAILED = "The web search didn't work just now. Say you can't check that live at the moment, and give what you know, saying it may be out of date."

    /**
     * Slice 4: one Live turn as Ask shows it: what Obi said (the transcript of his voice, one line, at most
     * [AskRules.MAX_ANSWER]) with its sources. Null when nothing of Obi's was heard (no transcript yet).
     */
    fun turnAnswer(said: String, sources: List<AskSource>): AskOutcome? {
        val text = said.replace(Regex("""\s+"""), " ").trim().take(AskRules.MAX_ANSWER)
        if (text.isEmpty()) return null
        return AskOutcome.Answered(AskAnswer(text, emptyList(), sources = sources))
    }

    /** His words for a Live turn as Ask titles it ("You asked"), one line; [HEARD_NONE] when none were transcribed. */
    fun turnQuestion(heard: String): String =
        heard.replace(Regex("""\s+"""), " ").trim().take(AskRules.MAX_QUESTION).ifEmpty { HEARD_NONE }

    const val HEARD_NONE = "In Live"

    /** A stored Live voice as one the picker offers, else the default. */
    fun voice(stored: String?): String =
        VOICES.firstOrNull { it.name.equals(stored?.trim(), ignoreCase = true) }?.name ?: LiveModeRules.DEFAULT_VOICE

    const val TITLE = "Live"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    const val INTRO = "Talk with Obi in real time: no waiting for his turn, talk over him any time."
    const val OFF_LINE = "Off · Obi answers as he does now"
    const val VOICE_TITLE = "Obi's Live voice"
    const val UNREACHABLE = "Live can't be reached right now · Obi answers as usual"
    const val NO_KEY = "Live needs its Google key on Navilife's server first · Obi answers as usual until then"
    const val FELL_BACK = "Live didn't open in time · Obi answered as usual"

    /** Slice 2b: ▶ Sample on a Live voice row. */
    const val SAMPLE_LABEL = "▶  Sample"
    const val STOP_LABEL = "■  Stop"
    /**
     * What ▶ Sample plays: Google's studio voice of the same name (Chirp 3 HD, through Navilife's voice on the server),
     * which Live's voice is built on; never a different voice standing in.
     */
    const val SAMPLE_NOTE = "▶ Sample plays Google's studio voice of the same name; Live sounds very like it"
    const val SAMPLE_FAILED = "Couldn't play that voice just now · Google's voices may not be reachable"

    /** The prefix of Activity's Live line. */
    const val ACTIVITY_LABEL = "Obi's Live"

    /**
     * Slice 2b: Activity's line about Live this month, from the server's meter: "Obi's Live · 3 talks · 12 min · £0.42
     * of £15 in October", "Obi's Live · no talks yet in October · up to £15", or with the cap used "… · off until
     * 1 Nov". Null when Live has no key yet and nothing was used (nothing to say), or the meter couldn't be read.
     * Minutes count the sessions that reported (the longer of heard and spoken), rounded up.
     */
    fun activityLine(configured: Boolean, month: String?, sessions: Long, seconds: Long, level: String, spentPence: Long, capPence: Long): String? {
        if (!configured && sessions <= 0) return null
        val m = month?.let(MONTH::matchEntire)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..12 }
        val inMonth = m?.let { "in ${MONTHS[it - 1]}" } ?: "this month"
        val cap = LiveModeRules.money(capPence)
        if (sessions <= 0) return "$ACTIVITY_LABEL · no talks yet $inMonth · up to $cap"
        val parts = mutableListOf(ACTIVITY_LABEL, if (sessions == 1L) "1 talk" else "$sessions talks")
        if (seconds > 0) parts += "${(seconds + 59) / 60} min"
        parts += "${LiveModeRules.money(spentPence)} of $cap $inMonth"
        if (LiveModeRules.Level.of(level) == LiveModeRules.Level.OVER) {
            parts += m?.let { "off until 1 ${MONTHS[it % 12].take(3)}" } ?: "off until the 1st"
        }
        return parts.joinToString(" · ")
    }

    private val MONTH = Regex("(\\d{4})-(\\d{2})")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )

    /**
     * The Live block in Obi's voice settings. [meterKnown] false when the server couldn't be asked (offline, older
     * server); [configured] false until the Gemini key is in.
     */
    fun view(
        on: Boolean,
        voice: String?,
        meterKnown: Boolean,
        configured: Boolean,
        level: String,
        spentPence: Long,
        capPence: Long,
    ): LiveSettingsView {
        val chosen = voice(voice)
        val meter = when {
            !meterKnown -> UNREACHABLE
            !configured -> NO_KEY
            else -> LiveModeRules.line(LiveModeRules.Level.of(level), spentPence, capPence)
        }
        return LiveSettingsView(
            on = on,
            line = if (on) meter else OFF_LINE,
            meterLine = if (on) null else meter.takeIf { meterKnown && configured },
            sharing = LiveModeRules.SHARING,
            voices = VOICES.map { LiveVoiceRow(it.name, it.detail, it.name == chosen) },
            showVoices = on,
        )
    }
}

/** One row of the Live voice picker. */
data class LiveVoiceRow(val name: String, val detail: String, val selected: Boolean)

/**
 * The Live block in Obi's voice settings: the switch, the line under it (the month's meter when on, or why Live can't
 * run), what goes to Google, and the voice picker while on.
 */
data class LiveSettingsView(
    val on: Boolean,
    val line: String,
    /** While off: the month so far, when there is a meter to show. */
    val meterLine: String?,
    val sharing: String,
    val voices: List<LiveVoiceRow>,
    val showVoices: Boolean,
) {
    val title: String get() = LiveTalkRules.TITLE
    val intro: String get() = LiveTalkRules.INTRO
}
