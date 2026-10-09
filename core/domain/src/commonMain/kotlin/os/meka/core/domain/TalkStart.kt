package os.meka.core.domain

// ---- Talk without tapping the mic (build plan, Fix first: "Talk without tapping the mic", slice 1) ----

/**
 * Where a conversation was started from without tapping Ask's mic. [SIDE_BUTTON]: the phone's assistant gesture (the
 * side key held, or the navigation bar's assist gesture) with MEKA as the digital assistant app, which Android sends as
 * `android.intent.action.ASSIST`. [HEADSET_BUTTON]: a wired or Bluetooth headset's button held, which Android sends as
 * `android.intent.action.VOICE_COMMAND`. Either opens MEKA on Ask already listening.
 *
 * Slice 2, where listening is clearly wanted: [WIDGET], the home screen's Talk widget tapped; [HEADPHONES] and [CAR],
 * MEKA opened from the launcher while Bluetooth audio is connected or the phone is in car mode ([TalkStartRules.onOpen]).
 *
 * Slice 3: [BEDSIDE], the mic on the bedside clock (the Fold half folded on a table), which springs a Talk pane up over
 * the clock already listening; [COVER], the mic on the closed Fold's now card, which opens Ask already listening.
 */
enum class TalkStart { SIDE_BUTTON, HEADSET_BUTTON, WIDGET, HEADPHONES, CAR, BEDSIDE, COVER }

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
     * MEKA was opened from the launcher ([fromLauncher]; never a notification, a widget's other taps or a return from
     * Recents, which Android doesn't deliver as an open): listen at once when that's clearly wanted. In car mode
     * ([carMode]) → [TalkStart.CAR]; with Bluetooth audio connected ([bluetoothAudio]: headphones, buds, a car's
     * hands-free) → [TalkStart.HEADPHONES]; otherwise (the phone's own speaker, a room) → null, Ask waits for the mic.
     * Nothing is heard until the on-device recogniser starts, with the same yes-before-anything as the mic.
     */
    fun onOpen(fromLauncher: Boolean, bluetoothAudio: Boolean, carMode: Boolean): TalkStart? = when {
        !fromLauncher -> null
        carMode -> TalkStart.CAR
        bluetoothAudio -> TalkStart.HEADPHONES
        else -> null
    }

    /** The home screen's Talk widget: its one line and the description the launcher's widget list shows. */
    const val WIDGET_LABEL = "Talk to MEKA"

    /** The bedside clock's and the now card's mic, as a screen reader says it. */
    const val MIC_LABEL = "Talk to MEKA"

    /** The pane that springs up over the bedside clock: its back line and title. */
    const val BEDSIDE_BACK = "‹ Clock"
    const val BEDSIDE_TITLE = "Talk"

    /**
     * Where a mic on the Fold's own screens starts from: the bedside clock ([bedside]) or the closed Fold's now card.
     * Both start listening at once, with the same yes-before-anything as Ask's mic.
     */
    fun fromMic(bedside: Boolean): TalkStart = if (bedside) TalkStart.BEDSIDE else TalkStart.COVER

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
                    "Headphones and the car",
                    "Hold the button on your headphones and MEKA opens listening. With Bluetooth headphones on, or in " +
                        "the car, opening MEKA starts listening too.",
                    lit = false,
                    steps = listOf(
                        "The first time, Android may ask which app should answer: choose MEKA, Always.",
                        "Opening MEKA from a notification never starts listening.",
                    ),
                    action = null,
                ),
                TalkSetupSection(
                    "Home screen",
                    "Add the Talk widget: one tap opens MEKA listening.",
                    lit = false,
                    steps = listOf("Hold an empty spot on the home screen → Widgets → MEKA → $WIDGET_LABEL."),
                    action = null,
                ),
                TalkSetupSection(
                    "Bedside and the cover screen",
                    "Tap the mic on the bedside clock (the phone half folded on a table) or on the closed phone's now " +
                        "card, and MEKA starts listening.",
                    lit = false,
                    steps = listOf("At the bedside MEKA answers over the clock; Back returns to the clock."),
                    action = null,
                ),
                TalkSetupSection("Safety", SAFETY, lit = false, steps = emptyList(), action = null),
            ),
        )
    }
}
