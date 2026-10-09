package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * MEKA's voice on the devices (build plan V1, "Weather and a voice Meka likes", item 3; non-AI, pure): how a line MEKA
 * says is cut into pieces for the server's voice (Amazon Polly in MEKA's own AWS account), how long a device waits for
 * the first piece before its own voice speaks instead, and how long it leaves the server alone after a refusal.
 *
 * Only MEKA's own words are ever sent to be spoken, never what Meka said.
 */
object SpeechRules {
    /** The first piece must start playing within this, else the device's own voice says the whole line. */
    const val FIRST_AUDIO_MS = 1_200L
    /** Later pieces are fetched while the one before plays; one this late hands the rest to the device's voice. */
    const val NEXT_AUDIO_MS = 6_000L
    /**
     * A long read (the morning brief's Listen, and after the wake alarm) waits this long for its first piece: Meka asked
     * for the brief and sees "Stop" at once, so a few seconds for a cold server beats the phone's voice for all of it.
     */
    const val READ_FIRST_AUDIO_MS = 5_000L

    /** How long to wait for a line's first piece: [READ_FIRST_AUDIO_MS] for a long [reading], else [FIRST_AUDIO_MS]. */
    fun firstWaitMs(reading: Boolean): Long = if (reading) READ_FIRST_AUDIO_MS else FIRST_AUDIO_MS

    /** What a device does when a piece of a line didn't come in time or wouldn't play. */
    enum class Miss {
        /** The device's own voice says this piece and everything after it. */
        REST_ON_DEVICE,
        /** The device's own voice says only this piece; MEKA's voice carries on with the next. */
        PIECE_ON_DEVICE,
    }

    /**
     * A missed piece in a conversation hands the rest to the device (one voice per answer). In a long [reading] only that
     * piece goes to the device and MEKA's voice picks up again, unless the server is [resting] (refused, used up, off,
     * or the device's voice is chosen), when the device says the rest in one go.
     */
    fun onMiss(reading: Boolean, resting: Boolean): Miss =
        if (reading && !resting) Miss.PIECE_ON_DEVICE else Miss.REST_ON_DEVICE
    /** The server refuses more than this per piece (SpeechCodec.MAX_TEXT). */
    const val MAX_PIECE = 600
    /** Sentences after the first are joined up to about this many characters, so a reply is one or two requests. */
    const val JOIN_TO = 300
    /** Clips kept on the device (in memory) per voice and text. */
    const val CACHE_CLIPS = 64

    /** After "off" (no voice on this server) MEKA asks again after this. */
    const val OFF_QUIET_MS = 6 * 3_600_000L
    /** After a failure or no answer (offline) MEKA asks again after this. */
    const val FAILED_QUIET_MS = 60_000L

    /** Lines MEKA says often, fetched once when a conversation starts so they play at once. */
    val COMMON: List<String> = listOf(TalkRules.ANYTHING_ELSE, TalkRules.LEFT_IT, TalkRules.NOT_HEARD_CARD)

    /**
     * [text] in the pieces it is spoken in: the first sentence alone (so the first audio comes quickly), then the rest
     * joined up to [JOIN_TO] characters; a sentence longer than [MAX_PIECE] is split at a comma or a space. Whitespace
     * is collapsed; blank text has no pieces.
     */
    fun pieces(text: String): List<String> {
        val clean = text.trim().replace(WHITESPACE, " ")
        if (clean.isEmpty()) return emptyList()
        val sentences = sentences(clean).flatMap(::fit)
        val out = mutableListOf(sentences.first())
        sentences.drop(1).forEach { s ->
            val last = out.last()
            if (out.size > 1 && last.length + 1 + s.length <= JOIN_TO) out[out.lastIndex] = "$last $s" else out += s
        }
        return out
    }

    /** Sentences end at . ! ? or … followed by a space (closing quotes and brackets stay with their sentence). */
    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in ENDS) {
                var j = i + 1
                while (j < text.length && text[j] in CLOSERS) j++
                if (j < text.length && text[j] == ' ') {
                    // "e.g. " and "3.5 " don't end a sentence: the next word must start with a capital or a digit.
                    val next = text.getOrNull(j + 1)
                    if (next != null && (next.isUpperCase() || next.isDigit() || next in OPENERS) && !abbreviation(text, start, i)) {
                        out += text.substring(start, j).trim()
                        start = j + 1
                        i = j
                    }
                }
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    private fun abbreviation(text: String, start: Int, dot: Int): Boolean {
        if (text[dot] != '.') return false
        val word = text.substring(start, dot).substringAfterLast(' ')
        return word.lowercase() in ABBREVIATIONS
    }

    /** A sentence too long for one request, cut at the last comma (else space) that fits. */
    private fun fit(sentence: String): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_PIECE) {
            val window = rest.substring(0, MAX_PIECE)
            val cut = window.lastIndexOf(", ").takeIf { it > MAX_PIECE / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: MAX_PIECE
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /**
     * Until when the device leaves the server's voice alone after it answered [state] ("off", "over", "failed") at
     * [nowMs]: "over" until the 1st of next month (UTC, when the server's count starts again), "off" for
     * [OFF_QUIET_MS], a failure for [FAILED_QUIET_MS]. Null for anything else (spoken).
     */
    fun quietUntil(state: String, nowMs: Long): Long? = when (state) {
        OVER -> nextUtcMonthStartMs(nowMs)
        OFF -> nowMs + OFF_QUIET_MS
        FAILED -> nowMs + FAILED_QUIET_MS
        else -> null
    }

    /** Midnight UTC on the 1st of the month after the one [nowMs] falls in. */
    fun nextUtcMonthStartMs(nowMs: Long): Long {
        val day = nowMs.floorDiv(DAY_MS)
        val (y, m, _) = civil(day)
        val (ny, nm) = if (m == 12) y + 1 to 1 else y to m + 1
        return daysFromCivil(ny, nm, 1) * DAY_MS
    }

    /**
     * Activity's line about MEKA's voice this month, from the server's voices answer: [state] ("on", "off", "failed"),
     * the UTC [month] ("2026-10"), the characters spoken so far against [capChars], and the [voice] Talk uses (the
     * chosen one when offered, else the server's default). [deviceChosen]: the device's own voice is chosen, so nothing
     * is sent. Null when there's nothing to say (MEKA's voice isn't on, or the server didn't answer).
     *
     * "MEKA's voice · Amy · 12,400 of 1,000,000 characters in October" ·
     * "MEKA's voice · October's 1,000,000 characters are used; the phone's own voice speaks until 1 Nov" ·
     * "MEKA's voice · the device's own voice, nothing is sent"
     */
    fun usageLine(state: String, month: String?, usedChars: Long, capChars: Long, voice: String?, deviceChosen: Boolean): String? {
        if (deviceChosen) return "$USAGE_LABEL · the device's own voice, nothing is sent"
        if (state != ON) return null
        val m = month?.let(MONTH::matchEntire)?.destructured?.let { (_, mm) -> mm.toInt() }?.takeIf { it in 1..12 } ?: return null
        val name = MONTHS[m - 1]
        if (capChars > 0 && usedChars >= capChars) {
            return "$USAGE_LABEL · $name's ${grouped(capChars)} characters are used; the device's own voice speaks until 1 ${MONTHS[m % 12].take(3)}"
        }
        val who = voice?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val of = if (capChars > 0) " of ${grouped(capChars)}" else ""
        return "$USAGE_LABEL$who · ${grouped(usedChars.coerceAtLeast(0))}$of characters in $name"
    }

    /** 1234567 → "1,234,567". */
    fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    const val USAGE_LABEL = "MEKA's voice"
    const val ON = "on"
    private val MONTH = Regex("(\\d{4})-(\\d{2})")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )

    /** The cache's key: the voice (or the server's default) and the exact words. */
    fun cacheKey(voice: String?, text: String): String = "${voice ?: ""}|$text"

    const val OFF = "off"
    const val OVER = "over"
    const val FAILED = "failed"

    private const val DAY_MS = 86_400_000L
    private val WHITESPACE = Regex("\\s+")
    private const val ENDS = ".!?…"
    private const val CLOSERS = "\"'”’)]"
    private const val OPENERS = "\"'“‘("
    private val ABBREVIATIONS = setOf("e.g", "i.e", "etc", "mr", "mrs", "ms", "dr", "st", "vs", "approx")

    // Howard Hinnant's civil-from-days and days-from-civil (proleptic Gregorian), so no date library is needed here.
    private fun civil(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = ((month + 9) % 12).toLong()
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

/**
 * The synced "MEKA's voice" setting (one `context_mode` entity, id [MekaVoiceStore.ENTITY_ID], LWW): which voice Talk,
 * the spoken brief and the call assistant use on every device.
 */
object MekaVoiceFields {
    /** A Polly voice name ("Amy"); [MekaVoiceRules.DEVICE] for the device's own voice; Null (or absent) for MEKA's default. */
    const val NAME = "name"
}

object MekaVoiceRules {
    /** The device's own text-to-speech voice, never sent anywhere. */
    const val DEVICE = "device"
    private val POLLY = Regex("[A-Z][a-z]{1,19}")

    /** A stored or chosen value as one MEKA can use: a Polly name, [DEVICE], or null (the server's default). */
    fun normalize(name: String?): String? {
        val n = name?.trim().orEmpty()
        return when {
            n.isEmpty() -> null
            n.equals(DEVICE, ignoreCase = true) -> DEVICE
            POLLY.matches(n) -> n
            else -> null
        }
    }
}

class MekaVoiceStore(private val replica: Replica) {
    /** The chosen voice: a Polly name, [MekaVoiceRules.DEVICE], or null for MEKA's default. */
    fun chosen(): String? =
        MekaVoiceRules.normalize(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(MekaVoiceFields.NAME)?.textOrNull)

    /** Chooses [name] (null for MEKA's default). False, and nothing written, for a name that can't be a voice. */
    fun choose(name: String?): Boolean {
        val wanted = MekaVoiceRules.normalize(name)
        if (name != null && name.isNotBlank() && wanted == null) return false
        if (wanted == chosen()) return true
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(MekaVoiceFields.NAME to (wanted?.let { FieldValue.Text(it) } ?: FieldValue.Null)),
        )
        return true
    }

    companion object {
        const val ENTITY_ID = "voice"
    }
}

/** One of the server's voices as the picker sees it: Polly's [id] ("Amy"), [gender] ("Female") and best [engine] here. */
data class OfferedVoice(val id: String, val gender: String, val engine: String)

/**
 * One row of the voice picker: [id] is what choosing it saves (a Polly name, or [MekaVoiceRules.DEVICE]); [sample] is
 * false where there's nothing to play (a voice the server isn't reachable for).
 */
data class VoiceChoice(
    val id: String,
    val label: String,
    val detail: String,
    val selected: Boolean,
    val sample: Boolean = true,
)

/**
 * Ask → More → MEKA's voice (build plan V1, "Weather and a voice Meka likes", item 2): MEKA's voices first (Polly's
 * British voices, the server's default marked), then the device's own voice; [statusLine] says why MEKA's voices are
 * missing or used up; [usageLine] is Activity's month line; [help] says how to get a better free device voice.
 */
data class VoicePickerView(
    val loaded: Boolean,
    val intro: String,
    val choices: List<VoiceChoice>,
    val statusLine: String?,
    val usageLine: String?,
    val help: String,
)

/** The voice picker's words and order (non-AI, pure), the same on the Fold and the Mac. */
object VoicePickerRules {
    /** What ▶ Sample says: MEKA's own words, never anything of Meka's. */
    const val SAMPLE = "Good morning, Meka. You've got three things today and it's 14 degrees."
    const val TITLE = "MEKA's voice"
    const val INTRO = "How MEKA sounds in Talk, the spoken brief and when it answers calls. The choice follows you to every device."

    /** "Amy · British · female · most natural". */
    fun detail(v: OfferedVoice, isDefault: Boolean): String {
        val engine = when (v.engine.lowercase()) {
            "generative" -> "most natural"
            "neural" -> "natural"
            else -> v.engine.lowercase().ifEmpty { null }
        }
        return listOfNotNull("British", v.gender.lowercase().ifEmpty { null }, engine, if (isDefault) "MEKA's default" else null)
            .joinToString(" · ")
    }

    /** The device's own voice row: "This phone's own voice" / "This Mac's own voice". */
    fun deviceLabel(mac: Boolean): String = if (mac) "This Mac's own voice" else "This phone's own voice"

    const val DEVICE_DETAIL = "Speaks on the device · nothing is sent · each device uses its own"

    fun help(mac: Boolean): String = if (mac) {
        "For a better Mac voice: System Settings → Accessibility → Spoken Content → System voice → Manage Voices, " +
            "and download a Premium or Enhanced English (United Kingdom) voice. MEKA picks the best one installed."
    } else {
        "For a better free phone voice: install Speech Services by Google, choose it in Settings → General management → " +
            "Text-to-speech → Preferred engine, then download English (United Kingdom) in high quality. MEKA picks the best one installed."
    }

    /**
     * The picker: [state] is the server's answer ("on", "off", "failed"), or null while it hasn't answered or the device
     * isn't connected ([connected]); [offered] the server's voices (best first), [defaultVoice] its default; [chosen] the
     * synced choice ([MekaVoiceStore.chosen]). The selected row is the chosen voice when offered, the device's when
     * chosen, else the server's default. A chosen voice the server can't be reached for still shows (selected, no
     * sample) so the choice is never hidden.
     */
    fun view(
        state: String?,
        offered: List<OfferedVoice>,
        defaultVoice: String?,
        chosen: String?,
        usageLine: String?,
        mac: Boolean,
        connected: Boolean = true,
        loaded: Boolean = true,
    ): VoicePickerView {
        val on = state == SpeechRules.ON && offered.isNotEmpty()
        val device = chosen == MekaVoiceRules.DEVICE
        val polly = if (on) offered else emptyList()
        val default = polly.firstOrNull { it.id == defaultVoice }?.id ?: polly.firstOrNull()?.id
        val picked = when {
            device -> MekaVoiceRules.DEVICE
            chosen != null && polly.any { it.id == chosen } -> chosen
            else -> default
        }
        val rows = polly.map { v -> VoiceChoice(v.id, v.id, detail(v, v.id == default), selected = v.id == picked) }.toMutableList()
        if (!on && chosen != null && !device) {
            rows += VoiceChoice(chosen, chosen, "MEKA's voice · not reachable right now", selected = true, sample = false)
        }
        rows += VoiceChoice(MekaVoiceRules.DEVICE, deviceLabel(mac), DEVICE_DETAIL, selected = device || (picked == null && !rows.any { it.selected }))
        val where = if (mac) "the Mac" else "the phone"
        val status = when {
            !loaded -> null
            !connected -> "MEKA's voices come from your MEKA server. Until this device is connected, $where's own voice speaks."
            state == SpeechRules.OFF -> "MEKA's voices aren't switched on for this server, so $where's own voice speaks."
            !on -> "Couldn't reach MEKA's voices just now, so $where's own voice speaks. Try again in a moment."
            chosen != null && !device && chosen !in polly.map { it.id } -> "“$chosen” isn't offered any more, so $default speaks."
            else -> null
        }
        return VoicePickerView(loaded, INTRO, rows, status, usageLine, help(mac))
    }
}
