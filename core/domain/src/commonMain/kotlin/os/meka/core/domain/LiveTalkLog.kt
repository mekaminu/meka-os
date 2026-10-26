package os.meka.core.domain

/**
 * Live repeating itself (Meka 2026-10-26 20:27: on the speaker Obi "says the same sentence 20 times"). One talk's log on
 * the device, and the guard that stops a loop.
 *
 * Every turn Obi starts is noted by its opening (his first [OPENING_WORDS] words, from Google's transcript of what he
 * says), with Google's interruptions (and whether his voice or only silence was going up at the time), the session
 * going away, fresh windows and resumptions. When a turn opens with the same words as one that started within
 * [REPEAT_WINDOW_MS], Obi is repeating himself (his own voice heard back as a question, or a resumed session replaying
 * its last turn): [said] says so, and the talk stops and hands over to normal Obi with the reason on screen, never
 * looping. The log stays on this device for Health → Obi's voice; only Obi's own opening words are kept, never Meka's.
 *
 * Pure state, one per talk, used from one thread at a time (the Fold guards it with its lock).
 */
class LiveTalkLog(private val startMs: Long) {
    enum class Kind { TURN, STOPPED_FOR_VOICE, STOPPED_ON_SILENCE, GOING_AWAY, NEW_WINDOW, CARRIED_ON, REPEATED }

    data class Entry(val atMs: Long, val kind: Kind, val words: String? = null)

    private val entries = ArrayList<Entry>()
    private val openings = ArrayList<Pair<Long, String>>()
    private var turnStartedAt = -1L
    private var noted = false
    private var againAsked = false

    /** Turns Obi started this talk. */
    var turns = 0
        private set
    /** Google stopped Obi this talk, for his voice or with only silence going up. */
    var stops = 0
        private set
    /** The opening that repeated, once one has (the talk then stops). */
    var repeated: String? = null
        private set

    /** Everything noted, oldest first (at most [MAX_ENTRIES], the newest kept). */
    val all: List<Entry> get() = entries.toList()

    /**
     * Obi's words of this turn so far ([saidSoFar], Google's transcript) at [nowMs]. True when this turn's opening
     * repeats a turn that started within [REPEAT_WINDOW_MS]: the talk should stop now.
     */
    fun said(saidSoFar: String, nowMs: Long): Boolean {
        if (turnStartedAt < 0) turnStartedAt = nowMs
        if (noted) return false
        val words = LiveTalkLogRules.words(saidSoFar)
        if (words.size < OPENING_WORDS) return false
        return note(words, turnStartedAt)
    }

    /**
     * Meka's words this turn ([heardSoFar]): when he asks Obi to say it again ("sorry, what?", "say that again"), the
     * next turn may repeat once without stopping the talk.
     */
    fun heard(heardSoFar: String) {
        if (LiveTalkLogRules.asksAgain(heardSoFar)) againAsked = true
    }

    /** Obi's turn ended at [nowMs] with [said] (a short answer is noted here, if it has [MIN_WORDS]). */
    fun turnComplete(said: String, nowMs: Long): Boolean {
        val repeat = if (!noted) {
            val words = LiveTalkLogRules.words(said)
            if (words.size >= MIN_WORDS) note(words, if (turnStartedAt >= 0) turnStartedAt else nowMs) else false
        } else false
        turnStartedAt = -1
        noted = false
        return repeat
    }

    /** Google stopped Obi at [nowMs]; [letThrough] whether Meka's voice was going up then (false: only silence was). */
    fun interrupted(letThrough: Boolean, nowMs: Long) {
        stops++
        add(Entry(nowMs, if (letThrough) Kind.STOPPED_FOR_VOICE else Kind.STOPPED_ON_SILENCE))
        turnStartedAt = -1
        noted = false
    }

    fun goingAway(nowMs: Long) = add(Entry(nowMs, Kind.GOING_AWAY))
    fun newWindow(nowMs: Long) = add(Entry(nowMs, Kind.NEW_WINDOW))
    fun carriedOn(nowMs: Long) = add(Entry(nowMs, Kind.CARRIED_ON))

    private fun note(words: List<String>, atMs: Long): Boolean {
        noted = true
        turns++
        val opening = words.take(OPENING_WORDS).joinToString(" ")
        val asked = againAsked
        againAsked = false
        val again = !asked && openings.any { (at, o) -> o == opening && atMs - at <= REPEAT_WINDOW_MS }
        openings += atMs to opening
        while (openings.size > MAX_OPENINGS) openings.removeAt(0)
        add(Entry(atMs, if (again) Kind.REPEATED else Kind.TURN, opening))
        if (again && repeated == null) repeated = opening
        return again
    }

    private fun add(e: Entry) {
        entries += e
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Health's lines for this talk, newest last ("0:05 Obi: right so tomorrow you've got"). */
    fun lines(): List<String> = entries.map { LiveTalkLogRules.line(it, startMs) }

    companion object {
        /** A turn's opening: his first words, as many as this. */
        const val OPENING_WORDS = 6
        /** A turn shorter than this is never taken for a repeat ("Okay." twice is fine). */
        const val MIN_WORDS = 4
        /** The same opening again within this long is a repeat. */
        const val REPEAT_WINDOW_MS = 30_000L
        const val MAX_ENTRIES = 40
        const val MAX_OPENINGS = 20
    }
}

/** How [LiveTalkLog] reads words and writes its lines (pure). */
object LiveTalkLogRules {
    /** Lower-case words without punctuation ("Right, so — tomorrow's" → right, so, tomorrow's). */
    fun words(text: String): List<String> =
        text.lowercase().split(Regex("""\s+""")).map { w -> w.filter { it.isLetterOrDigit() || it == '\'' || it == '’' }.replace('’', '\'') }
            .filter { it.isNotEmpty() }

    private val AGAIN = Regex("""\b(say (that|it) again|repeat (that|it)|pardon|come again|what was that|what did you say|didn't catch (that|it)|missed that)\b""")

    /** He asked Obi to say it again. */
    fun asksAgain(heard: String): Boolean = AGAIN.containsMatchIn(heard.lowercase().replace('’', '\''))

    /** "0:05 Obi: right so tomorrow you've got" / "1:12 Google stopped Obi with only silence going up". */
    fun line(e: LiveTalkLog.Entry, startMs: Long): String {
        val s = ((e.atMs - startMs) / 1_000).coerceAtLeast(0)
        val at = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        val what = when (e.kind) {
            LiveTalkLog.Kind.TURN -> "Obi: \"${e.words}…\""
            LiveTalkLog.Kind.REPEATED -> "Obi repeated \"${e.words}…\": Live stopped"
            LiveTalkLog.Kind.STOPPED_FOR_VOICE -> "Google stopped Obi for your voice"
            LiveTalkLog.Kind.STOPPED_ON_SILENCE -> "Google stopped Obi with only silence going up"
            LiveTalkLog.Kind.GOING_AWAY -> "Google said the session is closing"
            LiveTalkLog.Kind.NEW_WINDOW -> "a fresh window opened"
            LiveTalkLog.Kind.CARRIED_ON -> "the talk carried on in it"
        }
        return "$at $what"
    }

    /** Health shows the last this many lines of the last talk. */
    const val HEALTH_LINES = 8

    /** The device's local key for the last talk's lines (on this device only). */
    const val KEY = "live.lastTalkLog"

    fun encode(lines: List<String>): String = lines.takeLast(HEALTH_LINES).joinToString("\n") { it.replace('\n', ' ') }
    fun decode(text: String?): List<String> = text?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
