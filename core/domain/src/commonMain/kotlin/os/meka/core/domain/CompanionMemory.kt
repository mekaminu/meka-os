package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * The companion, slice 5 (remembering across conversations; Meka 2026-10-19: "interactive like a human talking to
 * someone"). After an open conversation worth picking up another day (a topic he's exploring, a plan he's weighing, a
 * decision he made), the server's answer may carry one short **note** of what was talked about ("Talked through heat
 * pumps vs a new boiler; leaning heat pump"). The device keeps the newest [MAX_NOTES] notes for [KEEP_DAYS] days in
 * his own synced data, sends the newest [MAX_RECALL] back with his next open question (or one that asks about an
 * earlier chat) so Navilife can pick up where they left off, and shows them in Ask → Settings → Navilife's voice under
 * "What Navilife remembers", where he can turn it off (which forgets them) or forget them all. Non-AI and pure.
 *
 * Nothing other people wrote becomes a note: an answer that read the web, or a question that carried other people's
 * words (mail, calendar invitations, his wife's items), keeps none (ADR-006). A note that carries a long number, an
 * email address, a link or a password-like word is dropped whole.
 */
data class CompanionNote(val day: Long, val text: String)

object CompanionMemoryFields {
    /** The notes, one per line, "<epoch day>\t<text>", oldest first; Null (or absent) for none. */
    const val NOTES = "notes"
    /** True when he turned remembering off; Null (or absent) for on, the default. */
    const val OFF = "off"
}

object CompanionMemoryRules {
    const val ENTITY_ID = "companion_memory"
    /** The most notes kept, and the most sent back with a question. */
    const val MAX_NOTES = 8
    const val MAX_RECALL = 6
    /** A note older than this is forgotten. */
    const val KEEP_DAYS = 30L
    /** The longest note kept (a recall line adds its day: "Sun 18 Oct · "). */
    const val MAX_NOTE = 140

    /** Settings → Navilife's voice. */
    const val TITLE = "What Obi remembers"
    const val ON_LABEL = "Remember"
    const val OFF_LABEL = "Don't remember"
    const val FORGET_ALL = "Forget all"
    const val EMPTY = "Nothing yet. After a proper chat in Ask or Talk, a line about it appears here for 30 days."
    const val OFF_LINE = "Off: each conversation starts fresh, and nothing about it is kept."

    fun onLine(count: Int): String =
        "A line about each proper chat, kept 30 days in your own data, so Obi can pick up where you left off" +
            if (count > 0) " · ${plural(count, "note")}" else ""

    private val BLOCKED = Regex("""\b(password|passcode|pin|sort code|account number|card number|cvv)\b""")
    private val LONG_NUMBER = Regex("""\d{5,}""")

    /** Asking about an earlier chat ("what did we talk about last time?"), which carries the notes even when not open. */
    private val RECALL_WORDS = listOf(
        "last time", "we talked", "we discussed", "we spoke", "you said", "you told me", "remember when", "what did we",
        "where were we", "pick up where", "carry on from", "our chat", "our conversation",
    )

    /**
     * [raw] (the server's note) as kept: one line, no data-block brackets, at most [MAX_NOTE] (cut at a word), or null
     * when it is blank or carries anything that must never be kept.
     */
    fun clean(raw: String?): String? {
        val one = raw?.replace(Regex("""\s+"""), " ")?.replace("<", "‹")?.replace(">", "›")?.trim().orEmpty()
        if (one.length < 3) return null
        val low = one.lowercase()
        if ('@' in one || "http" in low || "www." in low) return null
        if (LONG_NUMBER.containsMatchIn(one.replace(" ", "").replace("-", ""))) return null
        if (BLOCKED.containsMatchIn(low)) return null
        if (one.length <= MAX_NOTE) return one
        val cut = one.take(MAX_NOTE).substringBeforeLast(' ').trimEnd(',', ';', ' ', '·')
        return (if (cut.length >= MAX_NOTE / 2) cut else one.take(MAX_NOTE)) + "…"
    }

    /** The notes still kept on [today]: none older than [KEEP_DAYS], the newest [MAX_NOTES], oldest first. */
    fun kept(notes: List<CompanionNote>, today: Long): List<CompanionNote> =
        notes.filter { it.day in (today - KEEP_DAYS)..today }.sortedBy { it.day }.takeLast(MAX_NOTES)

    /** [notes] with [text] kept on [today]: the same words again replace the older note, never a second copy. */
    fun add(notes: List<CompanionNote>, text: String, today: Long): List<CompanionNote> {
        val t = clean(text) ?: return kept(notes, today)
        val rest = notes.filterNot { it.text.equals(t, ignoreCase = true) }
        return kept(rest + CompanionNote(today, t), today)
    }

    /** Whether [question] asks about an earlier chat. */
    fun asksRecall(question: String): Boolean {
        val q = question.lowercase().replace('’', '\'')
        return RECALL_WORDS.any { it in q }
    }

    /**
     * Whether [question] goes with the notes: remembering on, notes kept, and an open turn ([CompanionRules.isOpen]) or
     * a question about an earlier chat. A quick look-up of his day never carries them.
     */
    fun sends(question: String, earlier: List<String>, on: Boolean, notes: List<CompanionNote>): Boolean =
        on && notes.isNotEmpty() && (CompanionRules.isOpen(question, earlier) || asksRecall(question))

    /** The lines sent back: the newest [MAX_RECALL], oldest first, each "Sun 18 Oct · Talked through …". */
    fun recall(notes: List<CompanionNote>, today: Long): List<String> =
        kept(notes, today).takeLast(MAX_RECALL).map { "${CivilDate.shortLabel(it.day)} · ${it.text}" }

    /** Settings' rows, newest first: "Today · …", "Yesterday · …", "Sun 18 Oct · …". */
    fun rows(notes: List<CompanionNote>, today: Long): List<String> =
        kept(notes, today).reversed().map { "${dayLabel(it.day, today)} · ${it.text}" }

    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> CivilDate.shortLabel(day)
    }

    /** The synced field's text for [notes]; null for none. */
    fun encode(notes: List<CompanionNote>): String? =
        notes.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.day}\t${it.text.replace('\n', ' ').replace('\t', ' ')}" }

    /** Reads the synced field leniently: a malformed line is skipped, never the rest. */
    fun decode(raw: String?): List<CompanionNote> =
        raw.orEmpty().split('\n').mapNotNull { line ->
            val day = line.substringBefore('\t', "").toLongOrNull() ?: return@mapNotNull null
            val text = line.substringAfter('\t', "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CompanionNote(day, text)
        }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}

/** The companion's notes in his synced data (context_mode "companion_memory"); last writer wins, like every setting. */
class CompanionMemoryStore(private val replica: Replica) {
    private fun entity() = replica.entity(EntityTypes.CONTEXT_MODE, CompanionMemoryRules.ENTITY_ID)

    /** Whether Navilife remembers conversations (on unless he turned it off). */
    fun remembers(): Boolean = entity()?.get(CompanionMemoryFields.OFF)?.boolOrNull != true

    fun notes(today: Long): List<CompanionNote> =
        CompanionMemoryRules.kept(CompanionMemoryRules.decode(entity()?.get(CompanionMemoryFields.NOTES)?.textOrNull), today)

    /** Keeps [text] on [today] when remembering is on and it is a note that may be kept; false when nothing was written. */
    fun keep(text: String?, today: Long): Boolean {
        if (!remembers()) return false
        val t = CompanionMemoryRules.clean(text) ?: return false
        val before = notes(today)
        val after = CompanionMemoryRules.add(before, t, today)
        if (after == before) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, CompanionMemoryRules.ENTITY_ID, mapOf(CompanionMemoryFields.NOTES to notesValue(after)))
        return true
    }

    /** Forgets every note, on every device. */
    fun forgetAll() {
        if (entity()?.get(CompanionMemoryFields.NOTES)?.textOrNull == null) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, CompanionMemoryRules.ENTITY_ID, mapOf(CompanionMemoryFields.NOTES to FieldValue.Null))
    }

    /** Turns remembering on or off on every device; off forgets the notes too. */
    fun setRemembers(on: Boolean) {
        if (on == remembers() && (on || entity()?.get(CompanionMemoryFields.NOTES)?.textOrNull == null)) return
        val fields = if (on) mapOf(CompanionMemoryFields.OFF to FieldValue.Null)
        else mapOf(CompanionMemoryFields.OFF to FieldValue.Bool(true), CompanionMemoryFields.NOTES to FieldValue.Null)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, CompanionMemoryRules.ENTITY_ID, fields)
    }

    private fun notesValue(notes: List<CompanionNote>): FieldValue =
        CompanionMemoryRules.encode(notes)?.let { FieldValue.Text(it) } ?: FieldValue.Null
}
