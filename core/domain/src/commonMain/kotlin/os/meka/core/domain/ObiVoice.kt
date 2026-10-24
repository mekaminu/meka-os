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
