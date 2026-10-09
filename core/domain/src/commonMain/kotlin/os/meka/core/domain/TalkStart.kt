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
 *
 * Slice 4: [OPEN], MEKA opened from the launcher with "Listen when I open MEKA" on ([TalkOnOpenRules]): a room check
 * first, then a short listening window.
 */
enum class TalkStart { SIDE_BUTTON, HEADSET_BUTTON, WIDGET, HEADPHONES, CAR, BEDSIDE, COVER, OPEN }

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

    /**
     * [onOpen] with "Listen when I open MEKA" ([listenOnOpen], off by default): a launcher open that headphones or the
     * car didn't already start listens too ([TalkStart.OPEN]), but only once the microphone is allowed ([micAllowed]):
     * an open never pops a permission prompt. Never from a notification (not a launcher open).
     */
    fun openStart(
        fromLauncher: Boolean,
        bluetoothAudio: Boolean,
        carMode: Boolean,
        listenOnOpen: Boolean,
        micAllowed: Boolean,
    ): TalkStart? = onOpen(fromLauncher, bluetoothAudio, carMode)
        ?: if (fromLauncher && listenOnOpen && micAllowed) TalkStart.OPEN else null

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

// ---- "Listen when I open MEKA" (Talk without tapping the mic, slice 4) ----

/**
 * "Listen when I open MEKA" (off by default; kept on the device, not synced): opening MEKA from the launcher measures
 * the room for [ROOM_CHECK_MS]; above about [NOISY_DBA] it doesn't listen and says "Too noisy — tap to talk"
 * ([TalkProblem.TOO_NOISY]); otherwise a quiet chime and Ask listens for up to [WINDOW_MS]: if no speech has begun by
 * then, listening stops and Today comes back. The recogniser's own end-of-speech still stops it on silence. Nothing is
 * kept from the room check: it is a level, never audio. (Non-AI, pure.)
 */
object TalkOnOpenRules {
    const val WINDOW_MS = 6_000L
    const val ROOM_CHECK_MS = 300L
    /** About a busy café or a loud television; a quiet room is ~30–40, conversation ~55–60. */
    const val NOISY_DBA = 60.0
    /**
     * A phone microphone's full scale in dB SPL, roughly: 16-bit 0 dBFS ≈ 90 dB. Uncalibrated and unweighted, so the
     * level is an estimate ("about dBA"), good enough to tell a quiet room from a noisy one.
     */
    const val FULL_SCALE_DB = 90.0
    /** Below this the room reads as silent (a dead or muted microphone gives all zeros). */
    const val FLOOR_DB = 20.0

    const val LABEL = "When I open MEKA"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"

    /**
     * The room's level from [samples] (16-bit PCM read for [ROOM_CHECK_MS]): the RMS in dB below full scale, moved up by
     * [FULL_SCALE_DB] and floored at [FLOOR_DB]. Null when nothing was read (the room couldn't be measured: listen).
     */
    fun roomLevel(samples: ShortArray, count: Int = samples.size): Double? {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return null
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return levelAt(kotlin.math.sqrt(sum / n) / 32768.0)
    }

    /**
     * The room's level from an RMS already on full scale (0 … 1: the Mac's float samples), as [roomLevel] gives it for
     * 16-bit ones: below one 16-bit step it reads as silent ([FLOOR_DB]).
     */
    fun levelAt(rms: Double): Double {
        if (rms * 32768.0 < 1.0) return FLOOR_DB
        return maxOf(FLOOR_DB, 20.0 * kotlin.math.log10(rms) + FULL_SCALE_DB)
    }

    /** The Mac's room check: too noisy at this full-scale RMS ([levelAt] above [NOISY_DBA]). */
    fun tooNoisyAt(rms: Double): Boolean = tooNoisy(levelAt(rms))

    /**
     * A Dock click reaches MEKA as a reopen, also while it's already in front: it counts as opening MEKA only when it
     * brought MEKA forward, i.e. MEKA wasn't active yet ([msSinceActivated] null) or became active just now (within
     * [REOPEN_FRESH_MS]; macOS activates the app around the reopen, in either order).
     */
    fun reopenFromBackground(msSinceActivated: Long?): Boolean =
        msSinceActivated == null || msSinceActivated in 0 until REOPEN_FRESH_MS

    const val REOPEN_FRESH_MS = 1_000L

    /**
     * The Mac's open: launching MEKA plainly ([plainOpen]: from the Dock, Finder or Spotlight, not to open a link, a
     * file or a notification) or a Dock click that brought it forward ([reopenFromBackground]) listens with the
     * setting on ([listenOnOpen]) and the microphone and speech recognition already allowed ([micAllowed]); an open
     * never asks for either.
     */
    fun macOpenStart(plainOpen: Boolean, listenOnOpen: Boolean, micAllowed: Boolean): TalkStart? =
        if (plainOpen && listenOnOpen && micAllowed) TalkStart.OPEN else null

    /** Too noisy to listen on open: show "Too noisy — tap to talk" instead. An unmeasured room isn't. */
    fun tooNoisy(level: Double?): Boolean = level != null && level > NOISY_DBA

    /** The listening window is over with nothing said ([speechBegan] false): stop, and Today comes back. */
    fun windowLapsed(startedAtMs: Long, nowMs: Long, speechBegan: Boolean): Boolean =
        !speechBegan && nowMs - startedAtMs >= WINDOW_MS

    /**
     * The Talk pane's section with its switch ([TURN_ON] / [TURN_OFF]); lit while on. The Fold's ([mac] false) and the
     * Mac's sheet's (launching MEKA or a Dock click while it's in the background; "click to talk").
     */
    fun section(on: Boolean, mac: Boolean = false): TalkSetupSection = TalkSetupSection(
        LABEL,
        when {
            on && mac -> "On: launching MEKA, or clicking it in the Dock while it's in the background, listens for a " +
                "few seconds, so you can just talk."
            on -> "On: opening MEKA from the home screen listens for a few seconds, so you can just talk."
            else -> "Off: opening MEKA shows Today and waits for you."
        },
        lit = on,
        steps = listOf(
            "It checks the room first: if it's noisy it says “${if (mac) TalkProblem.TOO_NOISY.macLine else TalkProblem.TOO_NOISY.line}” instead of listening.",
            "A quiet chime marks listening. Say nothing for 6 seconds and Today comes back.",
            if (mac) "Opening MEKA from a notification or a link never starts listening, and it never asks for the " +
                "microphone: allow it once by clicking the mic."
            else "Opening MEKA from a notification never starts listening.",
        ),
        action = if (on) TURN_OFF else TURN_ON,
    )
}
