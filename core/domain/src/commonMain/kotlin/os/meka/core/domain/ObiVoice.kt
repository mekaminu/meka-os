package os.meka.core.domain

/**
 * The Obi plan, slice 2: Obi is a man with a British voice. Navilife's default voice is Polly's Brian (the server's
 * default), Live's is Charon, and a woman's voice chosen while the assistant was Ada is put back once
 * ([MekaVoiceStore.obiVoiceOnce]). The phone's and Mac's own voices stay the offline fallback.
 */
object ObiVoiceRules {
    /** [MekaVoiceFields.OBI_VOICE] once the switch has run. */
    const val DONE = "done"

    /**
     * Women's voices among the ones Navilife has offered: Polly's English voices and Google's Chirp 3 HD / Live
     * prebuilt voices (Google publishes each voice's gender; the same names are used by both).
     */
    val FEMALE: Set<String> = setOf(
        // Amazon Polly (English)
        "Amy", "Emma", "Olivia", "Niamh", "Joanna", "Kendra", "Kimberly", "Salli", "Ivy", "Ruth", "Danielle", "Aria",
        "Ayanda", "Raveena", "Kajal", "Nicole",
        // Google (Chirp 3 HD and Gemini Live)
        "Achernar", "Aoede", "Autonoe", "Callirrhoe", "Despina", "Erinome", "Gacrux", "Kore", "Laomedeia", "Leda",
        "Pulcherrima", "Sulafat", "Vindemiatrix", "Zephyr",
    )

    /** Whether [name] ("Amy", "Aoede-US") is a woman's voice; false for null, the device's own voice or any other. */
    fun female(name: String?): Boolean {
        val n = name?.trim()?.removeSuffix("-US") ?: return false
        return FEMALE.any { it.equals(n, ignoreCase = true) }
    }
}

/**
 * One conversation, one voice (Meka 2026-10-25 14:38: "when I clicked the orb it started with one voice then another"):
 * the hello was Polly's Brian and Live answered in Gemini's Charon. While Live is on, every word Navilife's own voice
 * says (the hello, its openers made ahead, normal Obi if Live drops mid-talk, the brief) asks for the Live voice by its
 * name: Google's studio voice of the same name (Chirp 3 HD) is the voice Live's is built on. One Obi's voice, slice 2
 * (2026-11-13): the same with Live off too, and Settings → Voice is one choice ([view]). The device's own voice, when
 * chosen, stays the device's.
 */
object OneVoiceRules {
    /**
     * The voice to ask the server for (one Obi's voice, slice 2, Meka 2026-11-11: "orb sounds fab"): Obi's voice
     * ([voice], the one Live speaks in) whether Live is on or off, so the brief, reminders and answers sound like the
     * orb; the device's own voice when that is [chosen]. An older Polly pick in [chosen] no longer changes it.
     */
    fun asked(chosen: String?, voice: String): String? =
        if (chosen == MekaVoiceRules.DEVICE) MekaVoiceRules.DEVICE else LiveTalkRules.voice(voice)

    const val INTRO = VoicePickerRules.INTRO
    const val GROUP = "Obi's voice · Google"

    /**
     * Settings → Voice as one choice (slice 2): Obi's voices (Live's list, Obi's first) with [voice] lit, then the
     * device's own voice (lit when [chosen] is [MekaVoiceRules.DEVICE]); the status, usage and help lines as the old
     * picker had them. [state] is the server's speech answer ("on", "off", "failed"; null when not [connected]).
     */
    fun view(
        voice: String,
        chosen: String?,
        state: String?,
        usageLine: String?,
        mac: Boolean,
        connected: Boolean = true,
    ): VoicePickerView {
        val device = chosen == MekaVoiceRules.DEVICE
        val lit = LiveTalkRules.voice(voice)
        val reachable = connected && state == SpeechRules.ON
        val rows = LiveTalkRules.VOICES.map {
            VoiceChoice(it.name, it.name, it.detail, selected = !device && it.name == lit, sample = reachable, group = GROUP)
        } + VoiceChoice(
            MekaVoiceRules.DEVICE, VoicePickerRules.deviceLabel(mac), VoicePickerRules.DEVICE_DETAIL, selected = device,
            group = if (mac) "On this Mac" else "On this phone",
        )
        val where = if (mac) "the Mac" else "the phone"
        val status = when {
            device -> null
            !connected -> "Obi's voice comes from your Navilife server. Until this device is connected, $where's own voice speaks."
            state == SpeechRules.OFF -> "Navilife's voices aren't switched on for this server, so $where's own voice speaks."
            state != SpeechRules.ON -> "Couldn't reach Obi's voice just now, so $where's own voice speaks. Try again in a moment."
            else -> null
        }
        return VoicePickerView(true, INTRO, rows, status, usageLine, VoicePickerRules.help(mac))
    }

    /**
     * What Health → Obi's voice says after "Speaking as": the voice the last clip came in ([spoke]); while Live is on,
     * whether that is Live's voice ("Charon · the same as Live") or the server stood in with another
     * ("Brian · not Live's Charon"). Null before the first clip. At most 40 characters (else the voice alone).
     */
    fun inUse(spoke: String?, liveOn: Boolean, liveVoice: String?): String? {
        val s = spoke?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 } ?: return null
        val live = liveVoice?.let { LiveTalkRules.voice(it) }
        if (!liveOn || live == null) return s
        val line = if (s.removeSuffix("-US").equals(live, ignoreCase = true)) "$s · the same as Live" else "$s · not Live's $live"
        return line.takeIf { it.length <= 40 } ?: s
    }
}
