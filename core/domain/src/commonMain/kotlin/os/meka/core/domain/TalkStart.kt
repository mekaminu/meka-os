package os.meka.core.domain

// ---- Talk without tapping the mic (build plan, Fix first: "Talk without tapping the mic", slice 1) ----

/**
 * Where a conversation was started from without tapping Ask's mic. [SIDE_BUTTON]: the phone's assistant gesture (the
 * side key held, or the navigation bar's assist gesture) with MEKA as the digital assistant app, which Android sends as
 * `android.intent.action.ASSIST`. [HEADSET_BUTTON]: a wired or Bluetooth headset's button held, which Android sends as
 * `android.intent.action.VOICE_COMMAND`. Either opens MEKA on Ask already listening.
 */
enum class TalkStart { SIDE_BUTTON, HEADSET_BUTTON }

/** One section of the Talk pane: its small label, a [status] line ([lit]: in the accent, set up), and [steps]. */
data class TalkSetupSection(
    val label: String,
    val status: String,
    val lit: Boolean,
    val steps: List<String>,
    /** The button under the section ("Open default apps"), or null. */
    val action: String?,
)

/** Ask → More → Talk: how to talk to MEKA without tapping the mic, and why it stays safe. */
data class TalkSetupView(val title: String, val intro: String, val sections: List<TalkSetupSection>)

/** Talk's starting points and their setup words (non-AI, pure), the same on the Fold and the Mac. */
object TalkStartRules {
    const val ACTION_ASSIST = "android.intent.action.ASSIST"
    const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    const val TITLE = "Talk"
    const val SAFETY =
        "Anyone near the phone can talk to MEKA once it's listening, so nothing is sent, deleted or paid without a " +
            "spoken or tapped yes, and every change has Undo."
    const val MAC_SAFETY =
        "Nothing is sent, deleted or paid without a spoken or clicked yes, and every change has Undo."

    /** The intent action Android opened MEKA with → where Talk was started from; anything else isn't a start. */
    fun fromAction(action: String?): TalkStart? = when (action) {
        ACTION_ASSIST -> TalkStart.SIDE_BUTTON
        ACTION_VOICE_COMMAND -> TalkStart.HEADSET_BUTTON
        else -> null
    }

    /** What travels to the main screen in its open extra ("talk:SIDE_BUTTON"), and back. */
    fun openValue(start: TalkStart): String = "$OPEN_PREFIX${start.name}"

    fun fromOpen(open: String?): TalkStart? =
        if (open == null || !open.startsWith(OPEN_PREFIX)) null
        else TalkStart.entries.firstOrNull { it.name == open.removePrefix(OPEN_PREFIX) }

    const val OPEN_PREFIX = "talk:"

    /**
     * The Talk pane. Fold: whether MEKA is the digital assistant app ([assistantHeld], from Android's role manager) and
     * the steps to make it so ([samsung]: One UI's menu names and the side key's own setting); the headphones' button;
     * safety. Mac: ⌥Space and the mic; safety.
     */
    fun setup(mac: Boolean, assistantHeld: Boolean = false, samsung: Boolean = false): TalkSetupView {
        if (mac) {
            return TalkSetupView(
                TITLE,
                "Start talking to MEKA without reaching for the mic.",
                listOf(
                    TalkSetupSection(
                        "On the Mac",
                        "Press ⌥Space while MEKA is in front, or click the mic beside Ask's field.",
                        lit = false,
                        steps = listOf("Talk to MEKA is also in the Edit menu."),
                        action = null,
                    ),
                    TalkSetupSection("Safety", MAC_SAFETY, lit = false, steps = emptyList(), action = null),
                ),
            )
        }
        val side = if (assistantHeld) {
            TalkSetupSection(
                "Side button",
                "MEKA is this phone's digital assistant: press and hold the side key and MEKA opens listening.",
                lit = true,
                steps = if (samsung) listOf("If holding the side key still wakes Bixby: Settings → Advanced features → Side button → Press and hold → Digital assistant.")
                else emptyList(),
                action = null,
            )
        } else {
            TalkSetupSection(
                "Side button",
                "Not set up yet: holding the side key opens another assistant.",
                lit = false,
                steps = if (samsung) listOf(
                    "Settings → Apps → Choose default apps → Digital assistant app → Device assistance app → MEKA.",
                    "Then Settings → Advanced features → Side button → Press and hold → Digital assistant.",
                ) else listOf(
                    "Settings → Apps → Default apps → Digital assistant app → MEKA.",
                ),
                action = "Open default apps",
            )
        }
        return TalkSetupView(
            TITLE,
            "Start talking to MEKA without reaching for the mic.",
            listOf(
                side,
                TalkSetupSection(
                    "Headphones",
                    "Hold the button on your headphones and MEKA opens listening.",
                    lit = false,
                    steps = listOf("The first time, Android may ask which app should answer: choose MEKA, Always."),
                    action = null,
                ),
                TalkSetupSection("Safety", SAFETY, lit = false, steps = emptyList(), action = null),
            ),
        )
    }
}
