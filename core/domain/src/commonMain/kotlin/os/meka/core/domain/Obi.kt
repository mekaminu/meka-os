package os.meka.core.domain

/**
 * The assistant is called Obi (build plan Fix first, "the assistant becomes Obi", Meka 2026-10-22/23; before that he
 * was Ada): the app stays Navilife; the voice and the orb are Obi (Igbo for "heart"), a man, warm, quick, a little wry
 * and British. His name on the orb and the stage, the wake phrase "Hey Obi" ([HelloWakeRules]; "Hello Navilife" still
 * works), and how often he speaks of himself as Obi. Non-AI, pure.
 */
object ObiRules {
    const val NAME = "Obi"

    /** How help and onboarding introduce him. */
    const val WHO = "Obi is Navilife's assistant"

    /**
     * The device's own store key: the day (epoch day) he last introduced himself on this device. Never synced. The key
     * keeps the name it had when he was Ada, so the rename doesn't make him introduce himself twice in a day.
     */
    const val INTRO_KEY = "ada.introducedDay"

    /**
     * Whether this conversation's first question tells the companion he may introduce himself ("Obi here", "It's
     * Obi"): only the first conversation of the day on this device ([lastDay] the day he last could, [today] now) and
     * only at its start (no [earlierTurns]), so it is now and then, never every time.
     */
    fun introduces(lastDay: Long?, today: Long, earlierTurns: Int): Boolean = earlierTurns == 0 && lastDay != today

    /** [INTRO_KEY]'s stored text read back; null when missing or unreadable. */
    fun dayOf(stored: String?): Long? = stored?.trim()?.toLongOrNull()
}
