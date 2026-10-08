package os.meka.core.domain

/**
 * MEKA's own Motion setting (build plan M1, motion pass 2, slice 1), non-AI and pure. Meka couldn't see any
 * animations: the apps followed the phone's "Remove animations" (and the Mac's Reduce Motion), which stills
 * everything. Now the app follows its own choice, per device like the theme (Ask → More → Appearance → Motion):
 *
 * - **Expressive** (the default): bigger entrances (rise 28 dp, 60 ms apart, scaling up from 0.96), bouncier springs
 *   for completing and approving, slower count-ups.
 * - **Subtle**: the original, smaller motion.
 * - **Off**: cross-fades only, nothing moves (what reduced motion used to mean).
 *
 * The system setting only matters until Meka chooses: with nothing chosen and the device's animations off, MEKA stays
 * still too ([effective] is Off) and Today shows a one-time card ([systemCard]) explaining it and offering
 * Expressive. Either button on the card makes a choice, so the card never comes back.
 */
enum class MotionChoice(val id: String, val label: String, val line: String) {
    EXPRESSIVE("expressive", "Expressive", "Bigger entrances and bouncier springs"),
    SUBTLE("subtle", "Subtle", "Small, quick movement"),
    OFF("off", "Off", "Cross-fades only · nothing moves"),
}

/** The one-time card on Today when the device's own animations are off and nothing is chosen in MEKA yet. */
data class MotionCard(val title: String, val line: String, val turnOn: String, val keepStill: String)

object MotionRules {
    /** The stored choice, or null when Meka hasn't chosen on this device (nothing stored or an unknown id). */
    fun choice(id: String?): MotionChoice? = MotionChoice.entries.firstOrNull { it.id == id }

    /**
     * The motion in effect: what Meka chose; with no choice, Expressive, unless the device's animations are off
     * ([systemOff]: the phone's animator duration scale is 0, or the Mac's Reduce Motion is on), then Off.
     */
    fun effective(stored: MotionChoice?, systemOff: Boolean): MotionChoice =
        stored ?: if (systemOff) MotionChoice.OFF else MotionChoice.EXPRESSIVE

    /** Cross-fades only, no movement (the apps' "reduced motion"). */
    fun reduced(stored: MotionChoice?, systemOff: Boolean): Boolean = effective(stored, systemOff) == MotionChoice.OFF

    /** The bigger entrances and bouncier springs. */
    fun expressive(stored: MotionChoice?, systemOff: Boolean): Boolean = effective(stored, systemOff) == MotionChoice.EXPRESSIVE

    /** The line under Motion in Appearance: the choice's line, or why it's still when the device decided. */
    fun line(stored: MotionChoice?, systemOff: Boolean, mac: Boolean): String =
        if (stored == null && systemOff) "Off · " + (if (mac) "following Reduce Motion" else "following your phone's Remove animations")
        else effective(stored, systemOff).line

    /** Today's one-time card: only while the device's animations are off and nothing is chosen. */
    fun systemCard(stored: MotionChoice?, systemOff: Boolean, mac: Boolean): MotionCard? {
        if (stored != null || !systemOff) return null
        return if (mac) MotionCard(
            "Reduce Motion is on",
            "So MEKA is keeping still. Turn on MEKA's own motion? Change it any time in Ask → Appearance.",
            "Turn on motion", "Keep it still",
        ) else MotionCard(
            "Your phone's animations are off",
            "So MEKA is keeping still. Turn on MEKA's own motion? Change it any time in Ask → More → Appearance.",
            "Turn on motion", "Keep it still",
        )
    }

    /** "Turn on motion" on the card. */
    val CARD_TURN_ON = MotionChoice.EXPRESSIVE

    /** "Keep it still" on the card. */
    val CARD_KEEP_STILL = MotionChoice.OFF
}
