package os.meka.core.domain

/**
 * The companion is called Ada (build plan Fix first, Meka 2026-10-20 21:04): the app stays Navilife; the voice and the
 * orb are Ada (Igbo "first daughter", and Ada Lovelace). Slice 1: her name on the orb and the stage, the wake phrase
 * "Hey Ada" ([HelloWakeRules]), and how often she speaks of herself as Ada. Non-AI, pure.
 */
object AdaRules {
    const val NAME = "Ada"

    /** How help and onboarding introduce her. */
    const val WHO = "Ada is Navilife's assistant"

    /** The device's own store key: the day (epoch day) she last introduced herself on this device. Never synced. */
    const val INTRO_KEY = "ada.introducedDay"

    /**
     * Whether this conversation's first question tells the companion she may introduce herself ("Ada here", "It's
     * Ada"): only the first conversation of the day on this device ([lastDay] the day she last could, [today] now) and
     * only at its start (no [earlierTurns]), so it is now and then, never every time.
     */
    fun introduces(lastDay: Long?, today: Long, earlierTurns: Int): Boolean = earlierTurns == 0 && lastDay != today

    /** [INTRO_KEY]'s stored text read back; null when missing or unreadable. */
    fun dayOf(stored: String?): Long? = stored?.trim()?.toLongOrNull()
}
