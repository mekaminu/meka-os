package os.meka.core.domain

// ---- Talking over MEKA (MEKA as the default assistant: voice barge-in) ----

/**
 * Where MEKA's voice comes out while it speaks: [SPEAKER] (the phone's or the Mac's own speaker, so the microphone hears
 * MEKA too) or [HEADPHONES] (wired, Bluetooth or the car: little of MEKA reaches the microphone).
 */
enum class BargeInOutput { SPEAKER, HEADPHONES }

/** The detector's numbers for one [BargeInOutput]. */
data class BargeInTuning(
    /** How far above MEKA's own echo Meka's voice has to be, in dB. */
    val marginDb: Float,
    /** And never quieter than this, in dB full scale (a quiet room is about -60, speech up close -20). */
    val minDbfs: Float,
    /** How long it has to keep up before MEKA stops. */
    val sustainMs: Long,
)

/**
 * The detector's memory between frames: [floorDb], what MEKA's own voice sounds like at the microphone (its echo,
 * learnt while it speaks; NaN until the first frame); when MEKA's audio began ([playingSinceMs], null while nothing
 * plays: a clip still on its way, the gap between pieces); and the current loud stretch ([runStartMs], [lastLoudMs]).
 */
data class BargeInState(
    val floorDb: Float = Float.NaN,
    val playingSinceMs: Long? = null,
    val runStartMs: Long? = null,
    val lastLoudMs: Long? = null,
    val lastMs: Long? = null,
)

data class BargeInStep(val state: BargeInState, val interrupt: Boolean)

/**
 * "Talk over MEKA" (on by default; kept on each device, never synced): while MEKA speaks, the microphone's **level** is
 * watched (a number a few times a second, never words, never audio, nothing kept or sent; the recogniser isn't running)
 * and when Meka's voice rises clearly above MEKA's own for a moment, MEKA stops and listens, as a tap on the orb does
 * ([TalkFlow.bargeIn]). Tapping the orb still works. (Non-AI, pure.)
 *
 * MEKA's own voice reaches the microphone, more on the speaker than in headphones; the phone's echo canceller takes
 * most of it out where it has one, and [step] learns what is left as a floor: it follows MEKA's voice up at once and
 * comes down slowly ([FALL_DB_PER_S]), so the next sentence starting after a pause isn't taken for Meka. For the first
 * [GRACE_MS] after MEKA's audio (re)starts it only learns. Meka's voice has to stay [BargeInTuning.marginDb] above the
 * floor, and above [BargeInTuning.minDbfs], for [BargeInTuning.sustainMs] (dips of up to [GAP_MS] between syllables
 * don't break it): a cough or a door is too short. When unsure it doesn't interrupt: a missed barge-in costs a tap, a
 * false one cuts MEKA off.
 */
object BargeInRules {
    const val DEFAULT_ON = true

    val SPEAKER = BargeInTuning(marginDb = 12f, minDbfs = -40f, sustainMs = 320L)
    val HEADPHONES = BargeInTuning(marginDb = 8f, minDbfs = -46f, sustainMs = 220L)

    /** After MEKA's audio starts (or starts again after a gap), only learn its echo. */
    const val GRACE_MS = 400L
    /** Quiet dips this short inside Meka's speech don't end the loud stretch. */
    const val GAP_MS = 140L
    /** How fast the floor comes down when the microphone goes quieter. */
    const val FALL_DB_PER_S = 3f
    /** How much of the way up to a louder level the floor goes each frame (MEKA's voice getting louder). */
    const val RISE = 0.5f
    /** A frame longer than this after the last (the app was busy) counts as this long. */
    const val MAX_FRAME_MS = 200L

    fun tuning(output: BargeInOutput): BargeInTuning = if (output == BargeInOutput.HEADPHONES) HEADPHONES else SPEAKER

    /** A fresh detector, for each line MEKA starts to say. */
    fun start(): BargeInState = BargeInState()

    /**
     * One reading of the microphone: [levelDbfs] (the loudest since the last reading, dB full scale), whether MEKA's
     * audio is [playing] right now, where it comes out ([output]) and the time ([nowMs]). [BargeInStep.interrupt] once
     * Meka has clearly been talking over it; the state then starts afresh.
     */
    fun step(s: BargeInState, levelDbfs: Float, playing: Boolean, output: BargeInOutput, nowMs: Long): BargeInStep {
        if (!playing) return BargeInStep(s.copy(playingSinceMs = null, runStartMs = null, lastLoudMs = null, lastMs = nowMs), false)
        val level = if (levelDbfs.isNaN()) -160f else levelDbfs.coerceIn(-160f, 0f)
        val since = s.playingSinceMs ?: nowMs
        val dt = (nowMs - (s.lastMs ?: nowMs)).coerceIn(0L, MAX_FRAME_MS)
        val floor = if (s.floorDb.isNaN()) level else s.floorDb
        val t = tuning(output)
        val inGrace = nowMs - since < GRACE_MS
        val loud = !inGrace && level >= maxOf(floor + t.marginDb, t.minDbfs)
        if (loud) {
            val run = s.runStartMs ?: nowMs
            if (nowMs - run >= t.sustainMs) return BargeInStep(BargeInState(floorDb = floor, lastMs = nowMs), true)
            return BargeInStep(s.copy(floorDb = floor, playingSinceMs = since, runStartMs = run, lastLoudMs = nowMs, lastMs = nowMs), false)
        }
        val newFloor = if (level > floor) floor + (level - floor) * RISE else maxOf(level, floor - FALL_DB_PER_S * dt / 1000f)
        val broken = s.lastLoudMs == null || nowMs - s.lastLoudMs > GAP_MS
        return BargeInStep(
            s.copy(
                floorDb = newFloor,
                playingSinceMs = since,
                runStartMs = if (broken) null else s.runStartMs,
                lastLoudMs = if (broken) null else s.lastLoudMs,
                lastMs = nowMs,
            ),
            false,
        )
    }

    /** A block of 16-bit samples as dB full scale (the Fold's microphone), -160 for silence or nothing. */
    fun dbfs(samples: ShortArray, count: Int = samples.size): Float {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return -160f
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return TalkOrb.dbfs((kotlin.math.sqrt(sum / n) / 32768.0).toFloat())
    }

    const val LABEL = "Talk over MEKA"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"

    /** The Talk pane's section with its switch; lit while on. The Fold's and the Mac's ([mac]: "click"). */
    fun section(on: Boolean, mac: Boolean = false): TalkSetupSection = TalkSetupSection(
        LABEL,
        if (on) "On: start talking while MEKA speaks and it stops and listens. ${if (mac) "Clicking" else "Tapping"} the orb still works."
        else "Off: ${if (mac) "click" else "tap"} the orb to stop MEKA while it speaks.",
        lit = on,
        steps = listOf(
            "While MEKA speaks it watches only how loud the microphone is: no words, no recording, nothing kept.",
            if (mac) "Headphones work best. On the Mac's speakers, speak up a little." else
                "Headphones work best. On the phone's speaker, speak up a little.",
            "Your first word or two stop MEKA, so say the rest after it stops.",
        ),
        action = if (on) TURN_OFF else TURN_ON,
    )
}
