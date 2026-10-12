package os.meka.core.domain

/**
 * The bedside clock as the phone's charging screen saver (bedside clock on the Fold 8, slice 3): Android shows it by
 * itself while the phone charges and sits idle (Settings → Display → Screen saver → MEKA). It is the bedside clock
 * with nothing to press: a screen saver runs over the lock screen, so a touch only wakes the phone, and nothing here
 * acts without unlocking. In quiet hours it dims like the bedside clock. To keep a screen that stays on all night from
 * marking, the whole clock drifts a few dp to a new place every couple of minutes. Non-AI, pure, unit-tested.
 */
data class BedsideDreamView(
    val time: String,
    val dateLabel: String,
    val alarmLine: String,
    val alarmSet: Boolean,
    /** Quiet hours: the colours quieten and the screen goes to its dimmest. */
    val dim: Boolean,
    val heading: String,
    val lines: List<String>,
    /** Where the clock sits this minute, in dp from the centre (burn-in care). */
    val shiftXDp: Int,
    val shiftYDp: Int,
)

object BedsideDreamRules {
    const val SETUP_KEY = "device.screensaver"
    const val SETUP_TITLE = "Bedside screen saver"
    const val SETUP_FIX_LABEL = "Take me there"
    /** Where to find it on a Samsung; also the help line. */
    const val WHERE = "Settings → Display → Screen saver → MEKA"
    /** The label Android lists the screen saver under. */
    const val LABEL = "MEKA bedside clock"

    /** The clock moves to the next place this often. */
    const val SHIFT_EVERY_MINUTES = 2
    const val MAX_SHIFT_DP = 8

    /** A loop of small offsets that never strays more than [MAX_SHIFT_DP] from the centre and starts there. */
    val SHIFTS: List<Pair<Int, Int>> = listOf(
        0 to 0, 6 to -4, -4 to 6, 8 to 2, -6 to -6, 2 to 8, -8 to 0, 4 to -8, 0 to 4, -2 to -2,
    )

    /** The bedside view without its taps: the section's lines stay, the brief or shutdown link goes. */
    fun view(b: BedsideView, nowMs: Long): BedsideDreamView {
        val (x, y) = shift(nowMs)
        return BedsideDreamView(b.time, b.dateLabel, b.alarmLine, b.alarmSet, b.dim, b.heading, b.lines, x, y)
    }

    /** The offset for [nowMs]: changes every [SHIFT_EVERY_MINUTES] minutes, cycling through [SHIFTS]. */
    fun shift(nowMs: Long): Pair<Int, Int> {
        val step = nowMs.floorDiv(60_000L * SHIFT_EVERY_MINUTES)
        return SHIFTS[step.mod(SHIFTS.size.toLong()).toInt()]
    }

    /**
     * Whether MEKA is the screen saver, from Android's two settings: [enabled] ("screensaver_enabled", "1" on) and
     * [components] ("screensaver_components", comma-separated "package/class" names). Null when neither could be read.
     */
    fun isMeka(enabled: String?, components: String?, packageName: String): Boolean? {
        if (enabled == null && components == null) return null
        if (enabled != null && enabled.trim() == "0") return false
        val names = components.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return names.any { it.substringBefore('/') == packageName }
    }

    /** Setup's step under This phone. */
    fun setupStep(isMeka: Boolean?): SetupStep = when (isMeka) {
        true -> SetupStep(SETUP_KEY, SETUP_TITLE, "On · the bedside clock shows while the phone charges", SetupState.DONE)
        false -> SetupStep(SETUP_KEY, SETUP_TITLE, "Show the bedside clock while the phone charges · $WHERE", SetupState.TODO, SetupFix.SCREEN_SAVER, SETUP_FIX_LABEL)
        null -> SetupStep(SETUP_KEY, SETUP_TITLE, "Couldn't check · $WHERE", SetupState.UNKNOWN, SetupFix.SCREEN_SAVER, SETUP_FIX_LABEL)
    }
}
