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
    /**
     * The first piece must start playing within this, else the device's own voice says the whole line. 4 s (was 1.2 s):
     * on the phone's mobile data the round trip to MEKA's voice often took longer, so after one answer the rest came in
     * the robotic phone voice (Meka, 2026-10-09). The orb shows "Thinking…" meanwhile; one voice beats a fast switch.
     */
    const val FIRST_AUDIO_MS = 4_000L
    /** Later pieces are fetched while the one before plays; one this late hands the rest to the device's voice. */
    const val NEXT_AUDIO_MS = 6_000L
    /**
     * The orb speed pass, slice 3: a clip asked for ahead of being said (the hello's openers, the rest of an answer
     * while its first sentence plays) may take this long; nobody is waiting on it yet.
     */
    const val AHEAD_AUDIO_MS = 10_000L
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

    /**
     * How long a device waits for a piece before it misses (or holds): the first piece's wait; a long [reading]'s later
     * pieces [READ_PIECE_WAIT_MS] (counted from when the piece was asked for, which with [READ_AHEAD] is well before it is
     * needed); else [NEXT_AUDIO_MS].
     */
    fun waitMs(first: Boolean, reading: Boolean): Long = when {
        first -> firstWaitMs(reading)
        reading -> READ_PIECE_WAIT_MS
        else -> NEXT_AUDIO_MS
    }

    /**
     * Brief changes voice mid-read (Meka, 2026-10-10 07:50: "when it read the matches bit it sounded robotic"): in a long
     * reading a later piece that the server is still working on is waited for up to this long (MEKA's own pause, no
     * "One moment…" mid-brief) instead of [NEXT_AUDIO_MS]'s 6 s, so the voice only changes when the server refused or
     * failed, or after this. Generative Polly is slowest on long pieces, and the brief's pieces were joined to 300.
     */
    const val READ_PIECE_WAIT_MS = 15_000L
    /** A long reading asks for this many pieces at once from the start, so later ones are ready before they are needed. */
    const val READ_AHEAD = 3
    /** How many pieces are asked for at once: [READ_AHEAD] in a long [reading], else one (the next while one plays). */
    fun ahead(reading: Boolean): Int = if (reading) READ_AHEAD else 1
    /** A long reading's pieces are at most this long (each clip quick to make); sentences joined up to it. */
    const val READ_PIECE = 200

    /**
     * When a piece is late in a conversation where Ada's voice has already been heard, the device keeps waiting up to
     * this much longer for it, quietly (the orb still shows her speaking), instead of switching to the device's voice
     * mid-conversation (Meka, 2026-10-09: "never switch voice"). Nothing is said meanwhile: talking to Ada like a
     * conversation, slice 1 (2026-10-21) dropped the old "One moment…" holding line.
     */
    const val HOLD_AUDIO_MS = 6_000L
    /** MEKA's voice heard within this counts as the same conversation (a holding pause, not a new start). */
    const val CONVERSATION_GAP_MS = 120_000L

    /**
     * Whether a piece of a conversation's line may hold (wait quietly up to [HOLD_AUDIO_MS] more) when late: never
     * in a long [reading] (the brief has [onMiss]'s piece-by-piece fallback), never while the server is [resting] (it
     * refused: waiting won't help), and only once MEKA's voice was heard in this conversation — at [lastMekaVoiceMs],
     * within [CONVERSATION_GAP_MS] of [nowMs] — so the first answer of a conversation still falls back after its wait.
     */
    fun holds(reading: Boolean, resting: Boolean, lastMekaVoiceMs: Long?, nowMs: Long): Boolean =
        !reading && !resting && lastMekaVoiceMs != null && nowMs - lastMekaVoiceMs in 0..CONVERSATION_GAP_MS

    /** The longest a piece's request may run: its [waitMs], plus [HOLD_AUDIO_MS] when it may [hold]. */
    fun budgetMs(first: Boolean, reading: Boolean, hold: Boolean): Long =
        waitMs(first, reading) + if (hold) HOLD_AUDIO_MS else 0L

    /** Clip timings kept for Activity (in memory on the device, never sent). */
    const val TIMINGS_KEPT = 10

    /**
     * Activity's line about how quickly MEKA's voice answered on this device: the last [TIMINGS_KEPT] clips fetched
     * from the server ([timings] oldest first, as they happened), newest first ("Time to MEKA's voice · 1.8 s · 0.9 s · late · no answer"), then the middle of the
     * spoken ones ("· middle 1.4 s") when there are three or more. Clips played from memory aren't counted. Null when
     * nothing was fetched yet.
     */
    fun timingLine(timings: List<SpeechTiming>): String? {
        val last = timings.takeLast(TIMINGS_KEPT).asReversed()
        if (last.isEmpty()) return null
        val parts = last.map { t ->
            when (t.outcome) {
                SpeechTiming.Outcome.SPOKEN -> seconds(t.ms) + if (t.bluetooth) " + Bluetooth" else ""
                SpeechTiming.Outcome.LATE -> "late"
                SpeechTiming.Outcome.FAILED -> "no answer"
                SpeechTiming.Outcome.WITH_ANSWER -> WITH_ANSWER + if (t.bluetooth) " + Bluetooth" else ""
            }
        }
        val spoken = last.filter { it.outcome == SpeechTiming.Outcome.SPOKEN }.map { it.ms + if (it.bluetooth) BLUETOOTH_MS else 0L }.sorted()
        val middle = if (spoken.size >= 3) " · middle ${seconds(spoken[spoken.size / 2])}" else ""
        val bt = if (last.any { it.bluetooth && it.outcome == SpeechTiming.Outcome.SPOKEN }) BLUETOOTH_NOTE else ""
        return "$TIMING_LABEL · ${parts.joinToString(" · ")}$middle$bt"
    }

    /**
     * Voice completeness, slice 3 (the first words sooner): a spoken answer's first piece that came with the answer itself
     * (no wait of its own), shown so in [timingLine] and left out of its middle.
     */
    const val WITH_ANSWER = "with the answer"

    /**
     * Voice completeness (2026-10-19): what headphones or car audio add before he hears a clip. Android and macOS don't
     * report a Bluetooth route's own delay to an app, so the line counts this typical figure for a clip played over
     * Bluetooth (the middle includes it) and says it's an estimate.
     */
    const val BLUETOOTH_MS = 200L
    const val BLUETOOTH_NOTE = " · Bluetooth or car audio adds about 0.2 s, counted in the middle"

    /**
     * Said once, in the device's own voice, before the rest of an answer when Navilife's voice couldn't be reached (no
     * signal, the server unreachable), instead of switching voice without a word (voice completeness, 2026-10-19). Said
     * again only after Navilife's voice has been heard since. [mac]: the Mac's wording.
     */
    fun offlineNote(mac: Boolean): String =
        if (mac) "Using this Mac's voice while Navilife's can't be reached." else "Using the phone's voice while offline."

    /**
     * Whether [offlineNote] is said now: the voice wasn't reached ([unreachable]), it isn't the device's own voice he
     * chose ([deviceChosen]), and the note hasn't been said since Navilife's voice was last heard ([saidSinceHeard]).
     */
    fun saysOfflineNote(unreachable: Boolean, deviceChosen: Boolean, saidSinceHeard: Boolean): Boolean =
        unreachable && !deviceChosen && !saidSinceHeard

    /** 1834 → "1.8 s"; under a tenth reads "0.1 s". */
    fun seconds(ms: Long): String {
        val tenths = ((ms.coerceAtLeast(0) + 50) / 100).coerceAtLeast(1)
        return "${tenths / 10}.${tenths % 10} s"
    }

    const val TIMING_LABEL = "Time to Navilife's voice"
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
    fun pieces(text: String): List<String> = pieces(text, reading = false)

    /** [pieces] for a long [reading]: every piece at most [READ_PIECE] characters (a longer sentence cut at a comma or space). */
    fun pieces(text: String, reading: Boolean): List<String> {
        val clean = text.trim().replace(WHITESPACE, " ")
        if (clean.isEmpty()) return emptyList()
        val max = if (reading) READ_PIECE else MAX_PIECE
        val joinTo = if (reading) READ_PIECE else JOIN_TO
        val sentences = sentences(clean).flatMap { fit(it, max) }
        val out = mutableListOf(sentences.first())
        sentences.drop(1).forEach { s ->
            val last = out.last()
            if (out.size > 1 && last.length + 1 + s.length <= joinTo) out[out.lastIndex] = "$last $s" else out += s
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
    private fun fit(sentence: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = window.lastIndexOf(", ").takeIf { it > max / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: max
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

    const val USAGE_LABEL = "Navilife's voice"
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
 * How one clip request to MEKA's voice went on this device (kept in memory for Activity's [SpeechRules.timingLine]):
 * at [atMs], answered in [ms] ([Outcome.SPOKEN]), given up after its budget ([Outcome.LATE]) or refused/unreachable
 * ([Outcome.FAILED]).
 */
data class SpeechTiming(val atMs: Long, val ms: Long, val outcome: Outcome, val bluetooth: Boolean = false) {
    enum class Outcome { SPOKEN, LATE, FAILED, WITH_ANSWER }
}

/**
 * The synced "MEKA's voice" setting (one `context_mode` entity, id [MekaVoiceStore.ENTITY_ID], LWW): which voice Talk,
 * the spoken brief and the call assistant use on every device.
 */
object MekaVoiceFields {
    /** A Polly voice name ("Amy"); [MekaVoiceRules.DEVICE] for the device's own voice; Null (or absent) for MEKA's default. */
    const val NAME = "name"
    /**
     * The companion, slice 3: how Navilife addresses him, [CompanionRules.CALL_SIR] ("sir"), or Null (or absent) for by
     * name. Synced, so Ask, Talk and Hello say the same on every device.
     */
    const val CALL_ME = "call_me"
}

object MekaVoiceRules {
    /** The device's own text-to-speech voice, never sent anywhere. */
    const val DEVICE = "device"
    /** A Polly name ("Amy") or a Google Chirp 3 HD short name ("Aoede", the American one "Aoede-US"). */
    private val POLLY = Regex("[A-Z][a-z]{1,19}(-US)?")

    /** A stored or chosen value as one MEKA can use: a server voice's name, [DEVICE], or null (the server's default). */
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

    /** The companion, slice 3: whether Navilife calls him "sir" (false: by name, the default). */
    fun callsSir(): Boolean =
        CompanionRules.callMe(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(MekaVoiceFields.CALL_ME)?.textOrNull) != null

    /** Chooses "sir" ([sir]) or by name, on every device; nothing written when it's already so. */
    fun callSir(sir: Boolean) {
        if (sir == callsSir()) return
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(MekaVoiceFields.CALL_ME to (if (sir) FieldValue.Text(CompanionRules.CALL_SIR) else FieldValue.Null)),
        )
    }

    companion object {
        const val ENTITY_ID = "voice"
    }
}

/**
 * One of the server's voices as the picker sees it: its [id] ("Amy", "Aoede", "Aoede-US"), [gender] ("Female"), best
 * [engine] here and [provider] ("polly" or "google").
 */
data class OfferedVoice(val id: String, val gender: String, val engine: String, val provider: String = VoicePickerRules.POLLY)

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
    /** The heading this row sits under ("Google · Chirp 3 HD · British"); shown above the first row of each group. */
    val group: String? = null,
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
    /**
     * Google's voices this month or how to add them (natural voice, slice 1, [VoicePickerRules.googleLine]): "Google
     * voices · 84k of 1M free this month", or "More natural Google voices · add the key …"; null from an older server.
     */
    val googleLine: String? = null,
    /**
     * What ▶ Sample plays (natural voice, slice 2, [VoicePickerRules.sampleLine]): "▶ plays the start of today's brief…"
     * or the stock line's note; null from an older facade.
     */
    val sampleLine: String? = null,
)

/** The voice picker's words and order (non-AI, pure), the same on the Fold and the Mac. */
object VoicePickerRules {
    /** What ▶ Sample says: MEKA's own words, never anything of Meka's. */
    const val SAMPLE = "Good morning, Meka. You've got three things today and it's 14 degrees."
    const val TITLE = "Ada's voice"

    /** The longest sample sent (natural voice, slice 2): two short sentences, never a whole paragraph of the brief. */
    const val SAMPLE_MAX = 240

    const val SAMPLE_FROM_BRIEF = "▶ Sample reads the start of today's brief in each voice · each voice's sample is kept, so playing it again is free"
    const val SAMPLE_STOCK = "▶ Sample reads a stock line until today's brief is ready"

    /**
     * What ▶ Sample says (natural voice, slice 2): the first two sentences of [script] (today's brief as Navilife reads
     * it), so each voice is heard saying something real; just the first when two run past [SAMPLE_MAX]; [SAMPLE] when
     * there's no brief yet or its first sentence alone is too long.
     */
    fun sampleFrom(script: String?): String {
        val text = script?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.isEmpty()) return SAMPLE
        val sentences = sentences(text)
        val first = sentences.firstOrNull() ?: return SAMPLE
        if (first.length > SAMPLE_MAX) return SAMPLE
        val two = sentences.take(2).joinToString(" ")
        return if (two.length <= SAMPLE_MAX) two else first
    }

    /** The note under the picker: whether ▶ Sample reads today's brief ([sampleFrom] gave something else than [SAMPLE]). */
    fun sampleLine(words: String): String = if (words == SAMPLE) SAMPLE_STOCK else SAMPLE_FROM_BRIEF

    /**
     * [text] cut after each ".", "!", "?" or "…" (with any closing quote or bracket) that a space follows, so "14.5°" and
     * "St.Albans" stay whole. Plain loop: no look-behind, the same on every platform.
     */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < text.length && text[end] in "”\"’')") end++
                if (end >= text.length || text[end] == ' ') {
                    val piece = text.substring(start, end).trim()
                    if (piece.isNotEmpty()) out += piece
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
    const val INTRO = "How Navilife sounds in Talk, the spoken brief and when it answers calls. The choice follows you to every device."

    const val POLLY = "polly"
    const val GOOGLE = "google"
    const val GROUP_GOOGLE_GB = "Google · Chirp 3 HD · British"
    const val GROUP_GOOGLE_US = "Google · Chirp 3 HD · American"
    const val GROUP_POLLY = "Amazon Polly · British"

    /** An American Google voice's id ends "-US" ("Aoede-US"); its row reads "Aoede". */
    fun american(v: OfferedVoice): Boolean = v.provider == GOOGLE && v.id.endsWith("-US")

    /** The row's name: the voice's id without the "-US" ("Aoede"). */
    fun label(v: OfferedVoice): String = v.id.removeSuffix("-US")

    /** The heading a server voice sits under. */
    fun group(v: OfferedVoice): String = when {
        v.provider != GOOGLE -> GROUP_POLLY
        american(v) -> GROUP_GOOGLE_US
        else -> GROUP_GOOGLE_GB
    }

    /** "British · female · most natural" (Polly) · "British · female · most natural · Google" (Chirp 3 HD). */
    fun detail(v: OfferedVoice, isDefault: Boolean): String {
        val engine = when (v.engine.lowercase()) {
            "generative", "chirp3hd" -> "most natural"
            "neural" -> "natural"
            else -> v.engine.lowercase().ifEmpty { null }
        }
        val accent = if (american(v)) "American" else "British"
        return listOfNotNull(accent, v.gender.lowercase().ifEmpty { null }, engine, if (v.provider == GOOGLE) "Google" else null, if (isDefault) "Navilife's default" else null)
            .joinToString(" · ")
    }

    /** Google's free characters a month and where Navilife stops using them (natural voice, slice 1). */
    const val GOOGLE_FREE = 1_000_000L

    const val GOOGLE_ADD_KEY = "More natural Google voices · add the key: Google Cloud console → APIs & Services → Library → " +
        "Cloud Text-to-Speech API → Enable, then Credentials → your API key → allow Cloud Text-to-Speech API. " +
        "Navilife uses the same key as travel times, free up to 1M characters a month."
    const val GOOGLE_UNREACHABLE = "Couldn't reach Google's voices just now · a chosen Google voice speaks with Amazon Polly meanwhile"

    /**
     * The Google line under the picker: [state] "on" · "nokey" · "failed" (null: an older server, no line); this
     * month's [usedChars] of [freeChars] free, Polly from [fallbackAtChars]; [failedClock] "08:12" when the last Google
     * clip failed and Polly said it (today's local time, else null); [month] "2026-10" names the 1st it resets.
     *
     * "Google voices · 84k of 1M free this month" · "Google voices · 900k of the free 1M used, so Amazon Polly speaks
     * until 1 Nov (never paid without your yes)" · "… · Google voice failed at 08:12 · used Polly"
     */
    fun googleLine(state: String?, usedChars: Long, freeChars: Long, fallbackAtChars: Long, failedClock: String?, month: String?): String? {
        return when (state) {
            null -> null
            "nokey" -> GOOGLE_ADD_KEY
            "on" -> {
                val free = freeChars.takeIf { it > 0 } ?: GOOGLE_FREE
                val base = if (fallbackAtChars in 1..usedChars) {
                    val m = month?.let { Regex("\\d{4}-(\\d{2})").matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..12 }
                    val until = m?.let { " until 1 ${MONTHS3[it % 12]}" }.orEmpty()
                    "Google voices · ${short(fallbackAtChars)} of the free ${short(free)} used, so Amazon Polly speaks$until (never paid without your yes)"
                } else {
                    "Google voices · ${short(usedChars.coerceAtLeast(0))} of ${short(free)} free this month"
                }
                if (failedClock != null) "$base · Google voice failed at $failedClock · used Polly" else base
            }
            else -> GOOGLE_UNREACHABLE
        }
    }

    /** 84_213 → "84k", 900_000 → "900k", 1_000_000 → "1M", 1_500_000 → "1.5M", 512 → "512". */
    fun short(n: Long): String = when {
        n >= 1_000_000 -> {
            val tenths = (n + 50_000) / 100_000
            if (tenths % 10 == 0L) "${tenths / 10}M" else "${tenths / 10}.${tenths % 10}M"
        }
        n >= 1_000 -> "${(n + 500) / 1_000}k".let { if (it == "1000k") "1M" else it }
        else -> n.toString()
    }

    private val MONTHS3 = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The device's own voice row: "This phone's own voice" / "This Mac's own voice". */
    fun deviceLabel(mac: Boolean): String = if (mac) "This Mac's own voice" else "This phone's own voice"

    const val DEVICE_DETAIL = "Speaks on the device · nothing is sent · each device uses its own"

    fun help(mac: Boolean): String = if (mac) {
        "For a better Mac voice: System Settings → Accessibility → Spoken Content → System voice → Manage Voices, " +
            "and download a Premium or Enhanced English (United Kingdom) voice. Navilife picks the best one installed."
    } else {
        "For a better free phone voice: install Speech Services by Google, choose it in Settings → General management → " +
            "Text-to-speech → Preferred engine, then download English (United Kingdom) in high quality. Navilife picks the best one installed."
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
        // Google's British voices first, then the American ones, then Polly's (each group under its heading).
        val ordered = polly.filter { it.provider == GOOGLE && !american(it) } + polly.filter { american(it) } + polly.filter { it.provider != GOOGLE }
        val rows = ordered.map { v -> VoiceChoice(v.id, label(v), detail(v, v.id == default), selected = v.id == picked, group = group(v)) }.toMutableList()
        if (!on && chosen != null && !device) {
            rows += VoiceChoice(chosen, chosen, "Navilife's voice · not reachable right now", selected = true, sample = false)
        }
        rows += VoiceChoice(
            MekaVoiceRules.DEVICE, deviceLabel(mac), DEVICE_DETAIL, selected = device || (picked == null && !rows.any { it.selected }),
            group = if (rows.isEmpty()) null else if (mac) "On this Mac" else "On this phone",
        )
        val where = if (mac) "the Mac" else "the phone"
        val status = when {
            !loaded -> null
            !connected -> "Navilife's voices come from your Navilife server. Until this device is connected, $where's own voice speaks."
            state == SpeechRules.OFF -> "Navilife's voices aren't switched on for this server, so $where's own voice speaks."
            !on -> "Couldn't reach Navilife's voices just now, so $where's own voice speaks. Try again in a moment."
            chosen != null && !device && chosen !in polly.map { it.id } -> "“$chosen” isn't offered any more, so $default speaks."
            else -> null
        }
        return VoicePickerView(loaded, INTRO, rows, status, usageLine, help(mac))
    }
}
