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
 * The system setting never stills MEKA (Meka, 2026-10-08 20:58: Motion had no choice and the phone's animator scale
 * was 0, so MEKA stayed still and he never saw an animation): with nothing chosen MEKA plays Expressive, the chip shows
 * lit, and only an explicit Off keeps it still. Today's one-time card ([systemCard]) is retired with it.
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
     * The motion in effect: what Meka chose; with no choice, Expressive, whatever the device says ([systemOff]: the
     * phone's animator duration scale is 0, or the Mac's Reduce Motion is on; kept for the Motion check). Only an
     * explicit Off keeps MEKA still.
     */
    @Suppress("UNUSED_PARAMETER")
    fun effective(stored: MotionChoice?, systemOff: Boolean): MotionChoice = stored ?: MotionChoice.EXPRESSIVE

    /** The chip shown lit: the choice in effect, so the state is never blank (Expressive with nothing chosen). */
    fun lit(stored: MotionChoice?): MotionChoice = stored ?: MotionChoice.EXPRESSIVE

    /** Cross-fades only, no movement (the apps' "reduced motion"). */
    fun reduced(stored: MotionChoice?, systemOff: Boolean): Boolean = effective(stored, systemOff) == MotionChoice.OFF

    /** The bigger entrances and bouncier springs. */
    fun expressive(stored: MotionChoice?, systemOff: Boolean): Boolean = effective(stored, systemOff) == MotionChoice.EXPRESSIVE

    /** The line under Motion in Appearance: the choice's line (the device's own setting never changes it). */
    @Suppress("UNUSED_PARAMETER")
    fun line(stored: MotionChoice?, systemOff: Boolean, mac: Boolean): String = effective(stored, systemOff).line

    /**
     * Today's one-time card, retired 2026-10-08: the device's animations no longer still MEKA with nothing chosen, so
     * there is nothing to explain and it never shows (null). Kept so the apps' card views stay wired.
     */
    @Suppress("UNUSED_PARAMETER")
    fun systemCard(stored: MotionChoice?, systemOff: Boolean, mac: Boolean): MotionCard? {
        if (stored != null || !systemOff || RETIRED_CARD) return null
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

    private const val RETIRED_CARD = true

    /** "Turn on motion" on the card. */
    val CARD_TURN_ON = MotionChoice.EXPRESSIVE

    /** "Keep it still" on the card. */
    val CARD_KEEP_STILL = MotionChoice.OFF
}

/** One line of Appearance → Motion check: "Phone's animation scale" · "Off (Remove animations)". */
data class MotionCheckRow(val label: String, val value: String)

/**
 * Appearance → "Motion check" (Meka, 2026-10-08: "the animation is something I have not seen work"): what MEKA sees
 * on this device and what it does about it. [on] is whether MEKA is animating; [fix] is the choice a tap on [result]
 * makes (Expressive) when it isn't, else null.
 */
data class MotionCheck(val rows: List<MotionCheckRow>, val result: String, val on: Boolean, val fix: MotionChoice?)

/**
 * Builds the Motion check (non-AI, pure). MEKA plays its own motion whatever the device says once a choice is made
 * (the phone's MotionClock runs at full speed even at animator scale 0); only with nothing chosen does the device's
 * own setting keep it still ([MotionRules.effective]). Power saving is reported but never stills MEKA.
 */
object MotionCheckRules {
    /**
     * The phone's check. [animatorScale] is Developer options → Animator duration scale (null when it can't be read);
     * 0 is "Remove animations". [powerSave] is the phone's power saving mode.
     */
    fun phone(stored: MotionChoice?, animatorScale: Float?, powerSave: Boolean): MotionCheck {
        val systemOff = animatorScale == 0f
        val rows = listOf(
            MotionCheckRow("MEKA Motion", stored?.label ?: "Not chosen"),
            MotionCheckRow("Phone's animation scale", scaleLabel(animatorScale)),
            MotionCheckRow("Power saving", if (powerSave) "On" else "Off"),
        )
        return result(rows, stored, systemOff, powerSave, mac = false)
    }

    /** The Mac's check: [reduceMotion] is Accessibility → Reduce Motion, [lowPower] is Low Power Mode. */
    fun mac(stored: MotionChoice?, reduceMotion: Boolean, lowPower: Boolean): MotionCheck {
        val rows = listOf(
            MotionCheckRow("MEKA Motion", stored?.label ?: "Not chosen"),
            MotionCheckRow("Reduce Motion", if (reduceMotion) "On" else "Off"),
            MotionCheckRow("Low Power Mode", if (lowPower) "On" else "Off"),
        )
        return result(rows, stored, reduceMotion, lowPower, mac = true)
    }

    /** "1×" · "0.5×" · "Off (Remove animations)" · "Unknown". */
    fun scaleLabel(scale: Float?): String = when {
        scale == null || scale.isNaN() || scale < 0f -> "Unknown"
        scale == 0f -> "Off (Remove animations)"
        else -> {
            val tenths = kotlin.math.round(scale * 10f).toInt()
            (if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}") + "×"
        }
    }

    private fun result(rows: List<MotionCheckRow>, stored: MotionChoice?, systemOff: Boolean, saving: Boolean, mac: Boolean): MotionCheck {
        val tap = if (mac) "click" else "tap"
        val effective = MotionRules.effective(stored, systemOff)
        if (effective == MotionChoice.OFF) {
            return MotionCheck(rows, "Animations off because MEKA's Motion is Off — $tap to turn them on", on = false, fix = MotionChoice.EXPRESSIVE)
        }
        val despite = when {
            systemOff && mac -> " · playing even with Reduce Motion on"
            systemOff -> " · playing even with Remove animations on"
            saving && mac -> " · playing even in Low Power Mode"
            saving -> " · playing even with power saving on"
            else -> ""
        }
        return MotionCheck(rows, "Animations on · ${effective.label}$despite", on = true, fix = null)
    }

    /** The "Play the opening" button under the check and what it does. */
    const val PLAY_OPENING = "Play the opening"
    const val PLAY_OPENING_LINE = "Replays Today's opening: the Day ring, its tiles and the greeting"
}
