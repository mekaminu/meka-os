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
