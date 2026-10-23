package os.meka.core.domain

import os.meka.core.sync.Sha256

/**
 * The recovery key (build plan V1 "Recovery key"; ADR-005 "Recovery", amendment 2026-10-12): 24 words shown once on a
 * keyed device, which can connect a new phone or Mac to the household if both devices are lost. Non-AI, pure.
 *
 * - **The words** are 256 random bits written as a BIP-39 English phrase (the last word carries an 8-bit checksum, so a
 *   mistyped word is caught on the device before anything is sent). They are made and shown on the device only.
 * - **What leaves the device.** Making a key sends only its [verifier] (a SHA-256 of a SHA-256 of the bits), so MEKA's
 *   server never sees the words or anything that could connect a device. Recovering sends the [secret] (one SHA-256
 *   of the bits); the server hashes it once more and compares. The bits are 256 random bits, so neither hash can be
 *   guessed backwards.
 * - **One use.** A key that connected a device is spent; the recovered device asks for a new one.
 * - Nothing here touches the replica: data comes back from the server's op log once the new device is connected
 *   (ADR-002, ADR-003).
 */
object RecoveryKeyRules {
    const val WORDS = 24
    const val ENTROPY_BYTES = 32
    /** How many words Meka types back before the key is saved (proof it was written down). */
    const val CONFIRM_WORDS = 3
    private const val DOMAIN = "MEKA-RECOVERY-1"

    /** What reading a typed phrase found. */
    sealed interface Reading {
        /** All 24 words, checksum good: these are the key's bits. */
        class Ok(val entropy: ByteArray) : Reading
        /** Not 24 words ([found] were). */
        data class Count(val found: Int) : Reading
        /** These word numbers (1-based) aren't on the list (or match more than one word). */
        data class Unknown(val positions: List<Int>) : Reading
        /** Every word is on the list but the checksum fails: a word is the wrong one. */
        object Mistyped : Reading
    }

    // ---- making ----

    /** The 24 words for [entropy] (exactly [ENTROPY_BYTES] bytes). */
    fun phrase(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "entropy" }
        val bits = entropy + Sha256.digest(entropy)[0]
        val words = RecoveryWordList.WORDS
        return (0 until WORDS).map { i -> words[index(bits, i)] }
    }

    /** What the making device sends: the server stores it and nothing else. */
    fun verifier(entropy: ByteArray): String = Sha256.hexOf(secret(entropy))

    /** What a recovering device sends; the server checks that its SHA-256 is a stored [verifier]. */
    fun secret(entropy: ByteArray): String = Sha256.hexOf("$DOMAIN\n" + hex(entropy))

    /** [CONFIRM_WORDS] different word positions (0-based, in order) for Meka to type back; [random] gives 0 until n. */
    fun confirmPositions(random: (Int) -> Int): List<Int> {
        val left = (0 until WORDS).toMutableList()
        return List(CONFIRM_WORDS) { left.removeAt(random(left.size).coerceIn(0, left.size - 1)) }.sorted()
    }

    /** True when each answer reads as the word at its position (whole words or their first four letters). */
    fun confirms(phrase: List<String>, positions: List<Int>, answers: List<String>): Boolean =
        positions.size == answers.size && positions.isNotEmpty() &&
            positions.zip(answers).all { (p, a) -> p in phrase.indices && wordOf(tokens(a).singleOrNull().orEmpty()) == phrase[p] }

    /** The words as shown: six rows of four, numbered ("1 abandon"). */
    fun rows(phrase: List<String>): List<List<String>> = rows(phrase, 4)

    /** The words numbered ("1 abandon") in rows of [columns], read across then down. */
    fun rows(phrase: List<String>, columns: Int): List<List<String>> =
        phrase.mapIndexed { i, w -> "${i + 1} $w" }.chunked(columns.coerceIn(1, WORDS))

    /**
     * The narrowest a numbered word ("24 strategy", the longest list words are 8 letters) reads whole in the body
     * style (quality gate slice 39: on the cover screen four to a row left about 64 dp each and split "1 / abandon").
     */
    const val WORD_MIN_DP = 112f

    /** Four words to a row when four fit [widthDp] (with [gapDp] between them) at [WORD_MIN_DP], else two. */
    fun columnsFor(widthDp: Float, gapDp: Float = 8f): Int = if (4 * WORD_MIN_DP + 3 * gapDp <= widthDp) 4 else 2

    // ---- recovering ----

    /**
     * Reads a typed phrase: any case, words separated by spaces, commas or new lines, numbers ("1.", "2)") ignored,
     * and each word may be just its first four letters.
     */
    fun read(text: String): Reading {
        val typed = tokens(text)
        if (typed.size != WORDS) return Reading.Count(typed.size)
        val indexes = typed.map { t -> wordOf(t)?.let { RecoveryWordList.WORDS.indexOf(it) } ?: -1 }
        val unknown = indexes.withIndex().filter { it.value < 0 }.map { it.index + 1 }
        if (unknown.isNotEmpty()) return Reading.Unknown(unknown)
        val bits = ByteArray(ENTROPY_BYTES + 1)
        indexes.forEachIndexed { i, v ->
            for (b in 0 until 11) if ((v shr (10 - b)) and 1 == 1) {
                val at = i * 11 + b
                bits[at / 8] = (bits[at / 8].toInt() or (0x80 ushr (at % 8))).toByte()
            }
        }
        val entropy = bits.copyOf(ENTROPY_BYTES)
        if (Sha256.digest(entropy)[0] != bits[ENTROPY_BYTES]) return Reading.Mistyped
        return Reading.Ok(entropy)
    }

    /** What to say under the field while typing (null when it reads). */
    fun readingLine(r: Reading): String? = when (r) {
        is Reading.Ok -> null
        is Reading.Count -> if (r.found == 0) "Type the 24 words" else "${r.found} of 24 words"
        is Reading.Unknown -> "Word ${r.positions.joinToString(", ")} " + (if (r.positions.size == 1) "isn't" else "aren't") + " one of the key's words"
        Reading.Mistyped -> "One of the words is wrong · check them against the paper"
    }

    /**
     * The same as [readingLine] for the text as typed, so a screen that only holds Strings (the Mac's Connect sheet,
     * Recovery key slice 3) can show the live line without handling a [Reading].
     */
    fun typedLine(text: String): String? = readingLine(read(text))

    /** True when [text] reads as a whole key (24 words, checksum good): Connect can be pressed. */
    fun readable(text: String): Boolean = read(text) is Reading.Ok

    /** The list word [t] stands for: itself, or the only word starting with it when four or more letters are typed. */
    fun wordOf(t: String): String? {
        if (t.isEmpty()) return null
        val words = RecoveryWordList.WORDS
        if (t in words) return t
        if (t.length < 4) return null
        return words.filter { it.startsWith(t) }.singleOrNull()
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().map { if (it in 'a'..'z') it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

    // ---- status (Settings → Recovery key) ----

    /** What the server says about the household's key. */
    data class Status(
        val state: String,
        val createdAtMs: Long? = null,
        val createdBy: String? = null,
        val usedAtMs: Long? = null,
        val usedBy: String? = null,
    ) {
        companion object {
            const val NONE = "none"
            const val READY = "ready"
            const val USED = "used"
        }
    }

    data class StatusView(val line: String, val action: String, val lit: Boolean)

    /** The row in Settings; null [status] while it can't be read (offline). */
    fun view(status: Status?, nowMs: Long, cal: LocalCalendar): StatusView = when (status?.state) {
        null -> StatusView("Can't check right now · Navilife's server didn't answer", "Make a recovery key", lit = false)
        Status.READY -> StatusView(
            "Made ${day(status.createdAtMs, nowMs, cal)}" + (status.createdBy?.let { " on the $it" } ?: "") +
                " · gets you back in if you lose both devices",
            "Make a new one", lit = false,
        )
        Status.USED -> StatusView(
            "Used ${day(status.usedAtMs, nowMs, cal)}" + (status.usedBy?.let { " to connect the $it" } ?: "") +
                " · make a new one and put the old paper away",
            "Make a new one", lit = true,
        )
        else -> StatusView("Not made yet · 24 words on paper get you back in if both devices are lost", "Make a recovery key", lit = true)
    }

    /** The server's refusals (`{"error": …}` from `/v1/recovery/…`) in words. */
    fun refusal(code: String?): String = when (code) {
        "key" -> "That recovery key doesn't open Navilife · check the words, or it was already used"
        "wait" -> "Too many tries · wait an hour and try again"
        "taken", "revoked" -> "This device can't be connected that way · reinstall Navilife and try again"
        "none" -> "No recovery key was made for this household"
        else -> "Navilife's server refused it"
    }

    /** Making a new key replaces the old one: the confirmation sheet's warning when one exists. */
    fun replaceWarning(status: Status?): String? =
        if (status?.state == Status.READY) "The old key stops working once the new one is saved" else null

    // ---- making a key on this device (slice 2: Settings → Recovery key) ----

    /**
     * A new key for the screen: its words, shown in [rows], the [CONFIRM_WORDS] positions to type back, and the
     * [verifier] that is saved. The bits themselves aren't kept; the words leave memory when the screen closes.
     */
    fun draft(entropy: ByteArray, random: (Int) -> Int): RecoveryKeyDraft {
        val words = phrase(entropy)
        return RecoveryKeyDraft(words, rows(words), confirmPositions(random), verifier(entropy))
    }

    /** True when [answers] (in [RecoveryKeyDraft.confirmPositions]' order) are the draft's words. */
    fun confirms(draft: RecoveryKeyDraft, answers: List<String>): Boolean = confirms(draft.words, draft.confirmPositions, answers)

    const val HOW = "Write the 24 words on paper, in order, and keep it at home away from your phone and Mac. " +
        "If you lose both, the words connect a new one. Navilife never sees them; don't photograph them or keep them in notes."
    const val WRITE_DOWN = "I've written them down"
    const val CONFIRM = "Type these words from your paper"
    const val NOT_MATCHING = "Those words don't match · check the paper and try again"
    const val SAVED = "Saved · keep the paper somewhere safe"
    const val NOT_CONNECTED = "Connect this device to your server first."
    const val NO_ROUTE = "Navilife's server can't keep a recovery key yet · it arrives with the next deploy"
    const val NO_KEY = "This device's key isn't registered with Navilife's server yet · try again in a minute"
    const val OFFLINE = "Couldn't reach Navilife's server · try again"
    const val WHY_YOU = "You did this in Recovery key"
    /** The first-run screen's way in (a new phone or Mac when both are lost). */
    const val RECOVER = "Recover with your recovery key"
    const val RECOVER_HOW = "Type the 24 words from your paper. This device joins your household and your data comes back from Navilife's server; the key is then used up, so make a new one."

    /** The pane's state: the row, a warning before replacing a key, and the last problem (null when none). */
    fun pane(status: Status?, nowMs: Long, cal: LocalCalendar, problem: String?): RecoveryKeyView {
        val v = view(status, nowMs, cal)
        return RecoveryKeyView(v.line, v.action, v.lit, replaceWarning(status), problem)
    }

    /** Activity's line when a key was made ("Made a new recovery key"), one per save. */
    fun activityId(atMs: Long): String = "d" + ActivityRules.fnv64("recovery:made:$atMs")
    const val MADE_SUMMARY = "Made a new recovery key"

    /** Settings' row line under "Recovery key" before the pane has asked the server. */
    const val ROW_LINE = "24 words that get you back in if both devices are lost"

    private fun day(atMs: Long?, nowMs: Long, cal: LocalCalendar): String {
        if (atMs == null) return "earlier"
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        return if (label == "Today" || label == "Yesterday") label.lowercase() else label
    }

    private fun index(bits: ByteArray, i: Int): Int {
        var v = 0
        for (b in 0 until 11) {
            val at = i * 11 + b
            v = (v shl 1) or ((bits[at / 8].toInt() ushr (7 - at % 8)) and 1)
        }
        return v
    }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** A key on screen (Settings → Recovery key); never stored or synced. [confirmPositions] are 0-based. */
class RecoveryKeyDraft(
    val words: List<String>,
    val rows: List<List<String>>,
    val confirmPositions: List<Int>,
    /** What is sent to MEKA's server when it's saved; the words never are. */
    val verifier: String,
) {
    /** "Word 3" · "Word 11" · "Word 20": each confirmation field's label. */
    val confirmLabels: List<String> get() = confirmPositions.map { "Word ${it + 1}" }

    /** The words in rows of [columns] (four unfolded and on the Mac, two on the cover screen: [RecoveryKeyRules.columnsFor]). */
    fun rowsIn(columns: Int): List<List<String>> = RecoveryKeyRules.rows(words, columns)
}

/** Settings → Recovery key: the status row, [warning] before replacing a key, and the last [problem]. */
data class RecoveryKeyView(val line: String, val action: String, val lit: Boolean, val warning: String?, val problem: String?)
