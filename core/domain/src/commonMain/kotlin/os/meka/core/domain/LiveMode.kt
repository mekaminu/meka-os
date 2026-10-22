package os.meka.core.domain

/**
 * Ada's Live mode, slice 1 (Meka 2026-10-21 20:56, new paid service and data sharing approved; non-AI, pure): the
 * money side of real-time speech-to-speech on Google's Gemini Live.
 *
 * A device never holds the Gemini key: Navilife's server mints a short-lived, single-session token for each Live
 * session ([TOKEN_MS] to talk, [START_MS] to open it) and the device talks to Google with that. Google bills per
 * minute of audio each way ([IN_MICRO_USD_PER_MIN], [OUT_MICRO_USD_PER_MIN]); Navilife keeps the month inside
 * Meka's £15 ([DEFAULT_CAP_GBP], `MEKA_LIVE_BUDGET_GBP`). Each minted session is counted at once as if it ran its
 * whole window ([reserveMicroUsd]); when the device reports how long it really heard and spoke, the count drops to
 * that ([reportedMicroUsd], never more than the window allows), so a device that crashes or goes offline mid-session
 * can never let the month run past the cap unseen. From [HEADS_UP_PERCENT] a heads-up; at the cap Live switches
 * itself off until the 1st and normal Ada carries on.
 */
object LiveModeRules {
    /** Google's paid-tier audio prices for the Live models (ai.google.dev pricing, 2026-10-22), per minute. */
    const val IN_MICRO_USD_PER_MIN = 5_000L
    const val OUT_MICRO_USD_PER_MIN = 18_000L

    /** How long one token lets a session talk; a longer conversation asks for a fresh token and resumes. */
    const val TOKEN_MS = 10 * 60_000L

    /** How long the device has to open the session with a new token. */
    const val START_MS = 60_000L

    /** Meka's monthly cap, in pounds, unless MEKA_LIVE_BUDGET_GBP says otherwise. */
    const val DEFAULT_CAP_GBP = 15

    /** The heads-up, as a share of the cap. */
    const val HEADS_UP_PERCENT = 80

    /** Pounds per dollar used when the day's rate can't be read: on the dear side, so the cap is never passed. */
    const val FALLBACK_GBP_PER_USD_MICROS = 850_000L

    /** Google's current Live model, unless MEKA_LIVE_MODEL names another. */
    const val DEFAULT_MODEL = "gemini-3.8-live"

    /** The voice he hears until he picks one (Google's prebuilt voices; a warm, clear female voice, nearest Amy). */
    const val DEFAULT_VOICE = "Kore"

    enum class Level(val wire: String) {
        OK("ok"), HEADS_UP("heads_up"), OVER("over");

        companion object {
            fun of(wire: String?): Level = entries.firstOrNull { it.wire == wire } ?: OK
        }
    }

    /** What [inSeconds] heard and [outSeconds] spoken cost, rounded up to the micro-dollar. */
    fun costMicroUsd(inSeconds: Long, outSeconds: Long): Long {
        val i = inSeconds.coerceAtLeast(0)
        val o = outSeconds.coerceAtLeast(0)
        return ceilDiv(i * IN_MICRO_USD_PER_MIN, 60) + ceilDiv(o * OUT_MICRO_USD_PER_MIN, 60)
    }

    /** What a new session is counted at until it reports: its whole window, both ways. */
    fun reserveMicroUsd(): Long = costMicroUsd(TOKEN_MS / 1_000, TOKEN_MS / 1_000)

    /**
     * What a reported session is counted at: what it says it heard and spoke, each at most the window (and never
     * less than nothing).
     */
    fun reportedMicroUsd(inSeconds: Long, outSeconds: Long): Long {
        val max = TOKEN_MS / 1_000
        return costMicroUsd(inSeconds.coerceIn(0, max), outSeconds.coerceIn(0, max))
    }

    /** [microUsd] in pence at [gbpPerUsdMicros] pounds per dollar (× 1,000,000), rounded up. */
    fun pence(microUsd: Long, gbpPerUsdMicros: Long): Long {
        if (microUsd <= 0) return 0
        val rate = gbpPerUsdMicros.takeIf { it > 0 } ?: FALLBACK_GBP_PER_USD_MICROS
        // micro-dollars × (pounds per dollar) = micro-pounds; 10,000 micro-pounds a penny.
        return ceilDiv(microUsd * rate, 1_000_000L * 10_000L)
    }

    /** The month's level: off at the cap (or a cap of nothing), the heads-up from [HEADS_UP_PERCENT]. */
    fun level(spentPence: Long, capPence: Long): Level = when {
        capPence <= 0 || spentPence >= capPence -> Level.OVER
        spentPence * 100 >= capPence * HEADS_UP_PERCENT -> Level.HEADS_UP
        else -> Level.OK
    }

    /** The cap from MEKA_LIVE_BUDGET_GBP ([raw]), in pence: 0–200 pounds, else [DEFAULT_CAP_GBP]. */
    fun capPence(raw: String?): Long = (raw?.trim()?.toLongOrNull()?.takeIf { it in 0..200 } ?: DEFAULT_CAP_GBP.toLong()) * 100

    /** A Google voice name as the device may ask for it (letters only, as Google names them), else null. */
    fun voice(raw: String?): String? = raw?.trim()?.takeIf { it.length in 2..24 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' } }

    /** The model name from MEKA_LIVE_MODEL ([raw]) when it reads as a Live model's name, else [DEFAULT_MODEL]. */
    fun model(raw: String?): String = raw?.trim()?.removePrefix("models/")
        ?.takeIf { it.length in 6..80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '.' } && "live" in it }
        ?: DEFAULT_MODEL

    /** The settings line under the switch: what Live costs so far and what goes to Google. */
    fun line(level: Level, spentPence: Long, capPence: Long): String = when (level) {
        Level.OVER -> "Live is off until the 1st · this month's ${money(capPence)} is used up"
        Level.HEADS_UP -> "${money(spentPence)} of ${money(capPence)} this month · nearly used up"
        Level.OK -> "${money(spentPence)} of ${money(capPence)} this month"
    }

    /** What goes to Google while Live is on, as the settings say it. */
    const val SHARING = "Your voice and today's Navilife context (Needs you, tasks, calendar, weather, commute, shopping) go to Google while Live is on, with any other day you ask her about, and Ada may search the web for things about the world, never your life"

    /** "£3.40", or "£15" for whole pounds. */
    fun money(pence: Long): String {
        val p = pence.coerceAtLeast(0)
        return if (p % 100 == 0L) "£${p / 100}" else "£${p / 100}.${(p % 100).toString().padStart(2, '0')}"
    }

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
}
