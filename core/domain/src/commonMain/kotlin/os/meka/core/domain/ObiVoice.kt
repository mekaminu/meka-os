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
 * name: Google's studio voice of the same name (Chirp 3 HD) is the voice Live's is built on. With Live off nothing
 * changes. The device's own voice, when chosen, stays the device's.
 */
object OneVoiceRules {
    /** The voice to ask the server for: [liveVoice] while [liveOn], else [chosen] (null: the server's default). */
    fun asked(chosen: String?, liveOn: Boolean, liveVoice: String): String? =
        if (liveOn && chosen != MekaVoiceRules.DEVICE) LiveTalkRules.voice(liveVoice) else chosen

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
