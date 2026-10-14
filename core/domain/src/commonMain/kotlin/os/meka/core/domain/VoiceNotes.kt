package os.meka.core.domain

/*
 * Voice notes into actions (build plan V1, slice 1; non-AI, pure).
 *
 * Meka holds the capture sheet's mic (or says "take a voice note" to Talk on the Fold) and rambles. The Fold records
 * the note and turns it into words **on the phone** (Android's on-device recogniser; nothing is sent to do it). The
 * recording and its words are kept on the phone only, encrypted, for [VoiceNoteRules.KEEP_DAYS] days unless he keeps
 * them (Ask → Daily → Voice notes). The words alone — never the audio — then go through "Handle this" on his behalf, so
 * the tasks, dates and reminders in it come back as cards he accepts or leaves; nothing is added until he taps.
 *
 * These rules say how a note is named and labelled, when it goes, how the words are tidied for reading, and how the
 * phone's index of notes is written down (one line per note, no JSON library in the domain).
 */

/** Whether the phone could turn a note into words. */
enum class VoiceNoteWords(val wire: String) {
    /** The on-device recogniser heard words. */
    HEARD("heard"),
    /** It ran but heard nothing it could write down (silence, noise). */
    NOTHING("nothing"),
    /** It couldn't run here (no on-device recogniser, Android too old, it failed): the recording is still kept. */
    FAILED("failed"),
    ;

    companion object {
        fun of(wire: String?): VoiceNoteWords = entries.firstOrNull { it.wire == wire } ?: FAILED
    }
}

/** One voice note as the phone's index keeps it. The recording itself is a separate encrypted file named by [id]. */
data class VoiceNote(
    val id: String,
    val atMs: Long,
    val durationMs: Long,
    val words: VoiceNoteWords,
    /** What was heard, tidied ([VoiceNoteRules.transcript]); "" unless [words] is HEARD. */
    val transcript: String,
    /** Kept past [VoiceNoteRules.KEEP_DAYS] by his tap. */
    val kept: Boolean = false,
    /** How many cards Handle this offered for it (null: not asked yet, or it couldn't answer). */
    val cards: Int? = null,
)

/** A note as the Voice notes pane shows it. */
data class VoiceNoteRow(
    val id: String,
    /** "Today 14:02 · 1:23" */
    val title: String,
    /** The words, or why there are none. */
    val text: String,
    /** Whether [text] is the words (else a quiet line saying why there are none). */
    val heard: Boolean,
    /** "Kept until Fri 13 Nov" · "Kept · Navilife won't delete it" */
    val keepLine: String,
    val kept: Boolean,
    /** "2 cards offered" · "Nothing to add" · null */
    val cardsLine: String?,
    /** Make cards is offered: there are words and Handle this hasn't answered for them yet. */
    val canMakeCards: Boolean,
)

object VoiceNoteRules {
    const val TITLE = "Voice notes"
    const val ROW_LINE = "Ramble it, Navilife pulls out the tasks · kept 30 days"

    /** How long a note may run; recording stops by itself here. */
    const val MAX_MS = 5 * 60_000L
    /** From here the recording line says when it will stop. */
    const val WARN_MS = MAX_MS - 30_000L
    /** Shorter than this is a slip of the finger: nothing is kept. */
    const val MIN_MS = 1_000L
    /** Notes (and their recordings) go after this many days unless kept. */
    const val KEEP_DAYS = 30
    private const val DAY_MS = 86_400_000L

    const val RECORDING = "Recording"
    const val PAUSED = "Paused"
    const val PAUSE = "Pause"
    const val RESUME = "Resume"
    const val STOP = "Done"
    const val CANCEL = "Cancel"
    const val FINISHING = "Turning it into words…"
    const val HINT = "Hold the mic for a voice note"
    const val NOTHING_HEARD = "Nothing was heard in that · the recording is kept in Voice notes"
    const val FAILED = "This phone couldn't turn it into words · the recording is kept in Voice notes"
    const val OLD_ANDROID = "Android 13 or later turns voice notes into words on the phone · the recording is kept"
    const val NEEDS_MIC = "Allow the microphone to record a voice note"
    const val TOO_SHORT = "Too short to keep · hold the mic and talk"
    const val KEPT_WHERE = "Kept with its recording in Ask → Daily → Voice notes"
    const val MAKE_CARDS = "Make cards"
    const val KEEP = "Keep"
    const val UNKEEP = "Let it go after 30 days"
    const val DELETE = "Delete"
    const val PLAY = "▶ Play"
    const val STOP_PLAYING = "■ Stop"
    const val EMPTY = "No voice notes yet · hold the 🎙 in Capture and talk, or say “take a voice note” to Talk"
    const val NOTE = "Recordings stay on this phone, encrypted, and go after 30 days unless you keep them. Only the words go to Navilife's AI, when it pulls out what to add."
    const val PLAY_FAILED = "Couldn't play that recording"

    /** What Talk says before the capture sheet opens recording. */
    const val OPENING = "Go ahead, I'm listening."
    const val OPENED = "Started a voice note."

    /** The note's id from when it started: sortable, unique enough on one phone. */
    fun id(atMs: Long): String = "vn" + atMs.toString(36)

    /** "0:42", "4:31", "12:05" (minutes:seconds). */
    fun elapsed(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The line under the recording dot: "Recording · 0:42", "Paused · 1:10", near the end "… · stops at 5:00". */
    fun recordingLine(ms: Long, paused: Boolean): String {
        val base = (if (paused) PAUSED else RECORDING) + " · " + elapsed(ms)
        return if (ms >= WARN_MS) "$base · stops at ${elapsed(MAX_MS)}" else base
    }

    /** Recording stops by itself. */
    fun atLimit(ms: Long): Boolean = ms >= MAX_MS

    /** Worth keeping at all. */
    fun longEnough(ms: Long): Boolean = ms >= MIN_MS

    /**
     * The words as one readable note: the recogniser's segments joined, spaces collapsed, the first letter of each
     * segment's sentence capitalised and a full stop after a segment that ended without one. "" when nothing was heard.
     */
    fun transcript(segments: List<String>): String =
        segments.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { s ->
                val up = s.replaceFirstChar { it.uppercaseChar() }
                if (up.last() in ".!?…") up else "$up."
            }

    /** What the words say about the recogniser's outcome. */
    fun words(segments: List<String>, failed: Boolean): VoiceNoteWords = when {
        transcript(segments).isNotEmpty() -> VoiceNoteWords.HEARD
        failed -> VoiceNoteWords.FAILED
        else -> VoiceNoteWords.NOTHING
    }

    /** "Voice note · 14:02": the subject Handle this reads it under (and the cards quote). */
    fun subject(minuteOfDay: Int): String = "Voice note · " + LocalClock.formatMinute(minuteOfDay.coerceIn(0, 1439))

    /** Whether the note's words go to Handle this (they need words beyond a few letters). */
    fun worthAsking(note: VoiceNote): Boolean =
        note.words == VoiceNoteWords.HEARD && HandleThisRules.worthAsking(note.transcript, null)

    /** The line the capture sheet shows once the recording has stopped and there are no words to read. */
    fun noWordsLine(words: VoiceNoteWords, oldAndroid: Boolean): String? = when {
        words == VoiceNoteWords.HEARD -> null
        oldAndroid -> OLD_ANDROID
        words == VoiceNoteWords.NOTHING -> NOTHING_HEARD
        else -> FAILED
    }

    /** When [note] goes (its day's end, [KEEP_DAYS] after it was made); null when kept. */
    fun expiresAtMs(note: VoiceNote): Long? = if (note.kept) null else note.atMs + KEEP_DAYS * DAY_MS

    /** The notes due to go at [nowMs]: older than [KEEP_DAYS] days and not kept. */
    fun expired(notes: List<VoiceNote>, nowMs: Long): List<VoiceNote> =
        notes.filter { n -> expiresAtMs(n)?.let { nowMs >= it } == true }

    /**
     * Files in the notes folder that no note in [notes] owns (a deleted note whose Undo has passed, a recording cut
     * short by the app being closed): they are deleted. [files] are the folder's names; the index's own file is kept.
     */
    fun orphans(files: List<String>, notes: List<VoiceNote>): List<String> {
        val owned = notes.map { fileName(it.id) }.toSet()
        return files.filter { it != INDEX_FILE && it !in owned }
    }

    /** The encrypted recording's file name for [id]. */
    fun fileName(id: String): String = "$id.m4a.enc"

    /** The encrypted index's file name. */
    const val INDEX_FILE = "index.enc"

    /** Newest first. */
    fun ordered(notes: List<VoiceNote>): List<VoiceNote> = notes.sortedByDescending { it.atMs }

    /**
     * A note as the pane shows it. [atDay]/[atMinute] are when it was made and [expiryDay] the day it goes, in the
     * phone's time zone; [today] for "Today"/"Yesterday".
     */
    fun row(note: VoiceNote, atDay: Long, atMinute: Int, expiryDay: Long?, today: Long): VoiceNoteRow {
        val day = when (atDay) {
            today -> "Today"
            today - 1 -> "Yesterday"
            else -> CivilDate.shortLabel(atDay)
        }
        val heard = note.words == VoiceNoteWords.HEARD
        val text = when (note.words) {
            VoiceNoteWords.HEARD -> note.transcript
            VoiceNoteWords.NOTHING -> "Nothing was heard in it"
            VoiceNoteWords.FAILED -> "Not turned into words on this phone"
        }
        val keepLine = if (note.kept || expiryDay == null) "Kept · Navilife won't delete it"
        else "Kept until " + CivilDate.shortLabel(expiryDay)
        val cardsLine = when (val c = note.cards) {
            null -> null
            0 -> "Nothing to add in it"
            1 -> "1 card offered · in Needs you until you answer it"
            else -> "$c cards offered · in Needs you until you answer them"
        }
        return VoiceNoteRow(
            id = note.id,
            title = "$day ${LocalClock.formatMinute(atMinute.coerceIn(0, 1439))} · ${elapsed(note.durationMs)}",
            text = text, heard = heard, keepLine = keepLine, kept = note.kept, cardsLine = cardsLine,
            canMakeCards = worthAsking(note) && note.cards == null,
        )
    }

    /** The pane's summary: "3 notes · 4 min 10 s recorded" · "1 note · 0:42". */
    fun summary(notes: List<VoiceNote>): String {
        if (notes.isEmpty()) return EMPTY
        val total = notes.sumOf { it.durationMs }
        return (if (notes.size == 1) "1 note" else "${notes.size} notes") + " · " + elapsed(total) + " recorded"
    }

    // ---- the phone's index: one note per line, tab-separated (the words with tabs and line breaks escaped) ----

    fun encode(notes: Collection<VoiceNote>): String =
        ordered(notes.toList()).joinToString("\n") { n ->
            listOf(
                clean(n.id), n.atMs.toString(), n.durationMs.toString(), n.words.wire, escape(n.transcript),
                if (n.kept) "1" else "0", n.cards?.toString().orEmpty(),
            ).joinToString("\t")
        }

    fun decode(text: String?): List<VoiceNote> =
        text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 7 || f[0].isBlank()) return@mapNotNull null
            val at = f[1].toLongOrNull() ?: return@mapNotNull null
            VoiceNote(
                id = f[0], atMs = at, durationMs = f[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                words = VoiceNoteWords.of(f[3]), transcript = unescape(f[4]), kept = f[5] == "1", cards = f[6].toIntOrNull(),
            )
        }.distinctBy { it.id }

    /** [notes] with [note] in place of the one with its id (or added). */
    fun upsert(notes: List<VoiceNote>, note: VoiceNote): List<VoiceNote> = ordered(notes.filter { it.id != note.id } + note)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun escape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    '\\' -> append('\\')
                    else -> { append(c); append(s[i + 1]) }
                }
                i += 2
            } else { append(c); i++ }
        }
    }
}
