package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToInt

// ---- The device's own voice, one by one, with speed and pitch (build plan V1, Weather and a voice, slice 10) ----

/**
 * A text-to-speech voice the device offers, as the picker lists it: [name] is the engine's id for it (Android's
 * `Voice.name`, the Mac's `AVSpeechSynthesisVoice.identifier`), [displayName] what the engine calls it ("Daniel"; blank
 * on Android, whose voices have only ids), [engine] the engine's label ("Speech Services by Google", "Apple"); the rest
 * as [VoiceCandidate].
 */
data class DeviceVoice(
    val name: String,
    val displayName: String,
    val language: String,
    val quality: Int,
    val engine: String,
    val needsNetwork: Boolean = false,
    val installed: Boolean = true,
) {
    fun candidate(): VoiceCandidate = VoiceCandidate(name, language, quality, needsNetwork, installed)
}

/**
 * How this device's own voice speaks, kept on the device only (each device has its own voices, so never synced):
 * [voice] the chosen voice's [DeviceVoice.name] (null: MEKA picks the best installed, [TalkVoice.best]); [rate] and
 * [pitch] where 1 is the engine's normal.
 */
data class DeviceVoiceSettings(val voice: String? = null, val rate: Float = 1f, val pitch: Float = 1f) {
    companion object {
        val DEFAULT = DeviceVoiceSettings()
    }
}

/** One row of the device's voices: [id] is what choosing it saves ([DeviceVoiceRules.AUTOMATIC] for MEKA's pick). */
data class DeviceVoiceRow(val id: String, val label: String, val detail: String, val selected: Boolean)

/**
 * The device-voice section under the picker: a heading, the [rows] (Automatic first, then the voices best first; at most
 * [DeviceVoiceRules.SHOWN] until [more] unfolds the rest), the speed and pitch lines, and what they change.
 */
data class DeviceVoiceView(
    val title: String,
    val rows: List<DeviceVoiceRow>,
    val more: List<DeviceVoiceRow>,
    val moreLabel: String?,
    val rateLine: String,
    val pitchLine: String,
    val note: String,
    val emptyLine: String?,
)

/** The device voices' words, order and numbers (non-AI, pure), the same on the Fold and the Mac. */
object DeviceVoiceRules {
    /** The row that leaves the choice to MEKA ([TalkVoice.best]). */
    const val AUTOMATIC = ""
    const val SHOWN = 6
    const val RATE_MIN = 0.6f
    const val RATE_MAX = 1.6f
    const val PITCH_MIN = 0.75f
    const val PITCH_MAX = 1.35f
    const val STEP = 0.05f

    /** Accents by language tag, as the rows say them. */
    private val ACCENTS = mapOf(
        "en-gb" to "British", "en-ie" to "Irish", "en-au" to "Australian", "en-nz" to "New Zealand",
        "en-us" to "American", "en-ca" to "Canadian", "en-in" to "Indian", "en-za" to "South African",
        "en-ng" to "Nigerian", "en-sc" to "Scottish",
    )

    fun title(mac: Boolean): String = if (mac) "This Mac's voices" else "This phone's voices"

    fun note(mac: Boolean): String =
        "Speed and pitch change ${if (mac) "the Mac's" else "the phone's"} own voice, kept on this device only. " +
            "MEKA's voices speak at their own pace."

    /** The voices MEKA may speak with: installed, on the device (nothing sent away), English; best first, as [TalkVoice.best]. */
    fun usable(voices: List<DeviceVoice>): List<DeviceVoice> {
        val best = voices.filter { it.installed && !it.needsNetwork && tag(it.language).startsWith("en") }
        fun rank(v: DeviceVoice): Int = TalkVoice.PREFERRED.indexOfFirst { tag(it) == tag(v.language) }.let { if (it < 0) TalkVoice.PREFERRED.size else it }
        return best.sortedWith(compareBy<DeviceVoice>({ rank(it) }, { -it.quality }, { it.name })).distinctBy { it.name }
    }

    /** The voice to speak with: the chosen one while it is still usable, else MEKA's pick; null: the engine's default. */
    fun pick(voices: List<DeviceVoice>, settings: DeviceVoiceSettings): DeviceVoice? {
        val list = usable(voices)
        return list.firstOrNull { it.name == settings.voice } ?: list.firstOrNull()
    }

    /** "British", "American", "English (en-JM)". */
    fun accent(language: String): String = ACCENTS[tag(language)] ?: "English (${language.replace('_', '-')})"

    /** The Mac's default · enhanced · premium (1 · 2 · 3); Android's `Voice.QUALITY_*` (100 … 500). */
    fun quality(q: Int, mac: Boolean): String = if (mac) {
        when {
            q >= 3 -> "Premium"
            q == 2 -> "Enhanced"
            else -> "standard quality"
        }
    } else {
        when {
            q >= 500 -> "very high quality"
            q >= 400 -> "high quality"
            q >= 300 -> "normal quality"
            else -> "low quality"
        }
    }

    /**
     * What a voice is called: its own name when the engine gives one ("Daniel"), else the short part of Android's id
     * ("en-gb-x-gba-local" → "Voice GBA"), else the id itself.
     */
    fun label(v: DeviceVoice): String {
        v.displayName.trim().takeIf { it.isNotEmpty() && it != v.name }?.let { return it.removeSuffix(" (Enhanced)").removeSuffix(" (Premium)") }
        val short = Regex("-x-([a-z0-9]+)(-|$)").find(v.name.lowercase())?.groupValues?.get(1)
        return if (short != null) "Voice ${short.uppercase()}" else v.name
    }

    /** "Speech Services by Google · British · high quality". */
    fun detail(v: DeviceVoice, mac: Boolean): String =
        listOfNotNull(v.engine.trim().ifEmpty { null }, accent(v.language), quality(v.quality, mac)).joinToString(" · ")

    /** Speed and pitch kept in their ranges, on the slider's steps. */
    fun rate(r: Float): Float = snap(r.coerceIn(RATE_MIN, RATE_MAX))
    fun pitch(p: Float): Float = snap(p.coerceIn(PITCH_MIN, PITCH_MAX))

    /** "Speed · normal", "Speed · 1.2× faster", "Speed · 0.8× slower". */
    fun rateLine(r: Float): String {
        val v = rate(r)
        return when {
            abs(v - 1f) < 0.001f -> "Speed · normal"
            v > 1f -> "Speed · ${times(v)}× faster"
            else -> "Speed · ${times(v)}× slower"
        }
    }

    /** "Pitch · normal", "Pitch · 10% higher", "Pitch · 15% lower". */
    fun pitchLine(p: Float): String {
        val pct = ((pitch(p) - 1f) * 100).roundToInt()
        return when {
            pct == 0 -> "Pitch · normal"
            pct > 0 -> "Pitch · $pct% higher"
            else -> "Pitch · ${-pct}% lower"
        }
    }

    /** The Mac's `AVSpeechUtterance.rate` for [r]: its normal is 0.5 of 0…1. */
    fun macRate(r: Float): Float = (0.5f * rate(r)).coerceIn(0.1f, 1f)

    /** The section: [voices] as the engine lists them, [settings] this device's. */
    fun view(voices: List<DeviceVoice>, settings: DeviceVoiceSettings, mac: Boolean, expanded: Boolean = false): DeviceVoiceView {
        val list = usable(voices)
        val chosen = list.firstOrNull { it.name == settings.voice }
        val best = list.firstOrNull()
        val auto = DeviceVoiceRow(
            AUTOMATIC,
            "Automatic",
            if (best != null) "MEKA picks the best installed · now ${label(best)}" else "The engine's own voice",
            selected = chosen == null,
        )
        val rows = list.map { DeviceVoiceRow(it.name, label(it), detail(it, mac), selected = it == chosen) }
        // A chosen voice past the first few stays in sight.
        val shownCount = if (expanded) rows.size else maxOf(SHOWN, rows.indexOfFirst { it.selected } + 1)
        val shown = rows.take(shownCount)
        val more = rows.drop(shownCount)
        val empty = if (list.isEmpty()) {
            if (mac) "No English voice is installed on this Mac yet, so the system's default speaks."
            else "No English voice is installed on the phone yet, so its engine's default speaks."
        } else null
        return DeviceVoiceView(
            title(mac), listOf(auto) + shown, more, if (more.isEmpty()) null else "+${more.size} more",
            rateLine(settings.rate), pitchLine(settings.pitch), note(mac), empty,
        )
    }

    /** Stored on the device as one line ("v=en-gb-x-gba-local;r=1.1;p=0.95"); anything unreadable reads as the default. */
    fun encode(s: DeviceVoiceSettings): String =
        listOfNotNull(s.voice?.takeIf { it.isNotEmpty() }?.let { "v=" + it.replace(";", "") }, "r=${rate(s.rate)}", "p=${pitch(s.pitch)}")
            .joinToString(";")

    fun decode(line: String?): DeviceVoiceSettings {
        if (line.isNullOrBlank()) return DeviceVoiceSettings.DEFAULT
        val parts = line.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        return DeviceVoiceSettings(
            voice = parts["v"]?.trim()?.ifEmpty { null },
            rate = parts["r"]?.toFloatOrNull()?.let { rate(it) } ?: 1f,
            pitch = parts["p"]?.toFloatOrNull()?.let { pitch(it) } ?: 1f,
        )
    }

    private fun snap(v: Float): Float = ((v / STEP).roundToInt() * STEP * 100).roundToInt() / 100f

    private fun times(v: Float): String {
        val s = ((v * 100).roundToInt() / 100f).toString()
        return s.trimEnd('0').trimEnd('.')
    }

    private fun tag(language: String) = language.replace('_', '-').lowercase()
}
