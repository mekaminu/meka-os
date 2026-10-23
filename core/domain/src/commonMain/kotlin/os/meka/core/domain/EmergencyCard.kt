package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * The emergency card (V1, Meka approved 2026-10-10): one page his wife can open from her family link — who to call,
 * where important documents are, and key accounts by name only. Meka edits it on his apps; she sees it read-only.
 * One synced entity type (ADR-008 addendum 2026-10-10): `emergency_entry`, one line of the card, a random id.
 *
 * The card never holds a secret: [EmergencyRules.refusal] turns away passwords, PINs, card numbers, sort codes and
 * account, policy or passport numbers (a phone number is fine under "Who to call"), on the devices before anything is
 * written and again on the server before her page is answered. Plain, non-AI rules; nothing leaves Meka's own synced
 * data except to her page.
 */
object EmergencyFields {
    /** [EmergencySection.key]: `call` · `documents` · `accounts`. */
    const val SECTION = "section"
    const val TITLE = "title"
    /** What follows the name ("Dr Patel, 01632 123456"); empty when only a name was typed. */
    const val DETAIL = "detail"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/**
 * The card's three parts, in the order the card shows them. [hint] is one example on one line on the cover screen
 * (quality gate slice 23); TalkBack and the Mac's label read the fuller [spokenHint].
 */
enum class EmergencySection(val key: String, val label: String, val hint: String, val spokenHint: String) {
    CALL("call", "Who to call", "GP: Dr Patel, 01632 123456",
        "Add someone to call: a name, a colon, then the details, for example GP: Dr Patel, 01632 123456"),
    DOCUMENTS("documents", "Where things are", "Passports: study desk",
        "Add where something is, for example Passports: study desk, top drawer"),
    ACCOUNTS("accounts", "Key accounts", "Home insurance: Aviva",
        "Add an account by name, never its number, for example Home insurance: Aviva"),
    ;

    companion object {
        fun of(key: String?): EmergencySection? = entries.firstOrNull { it.key == key }
    }
}

/** One line as stored. */
data class EmergencyEntry(val id: String, val section: EmergencySection, val title: String, val detail: String, val addedAtMs: Long)

/** One line as the card shows it: "GP" · "Dr Patel, 01632 123456", with a number to ring when there is one. */
data class EmergencyRow(
    val id: String,
    val section: String,
    val title: String,
    /** Null when only a name was typed. */
    val detail: String?,
    /** Under "Who to call": the phone number in the line as digits ("01632123456", "+447700900123"), for a tap to ring. */
    val phone: String?,
    val spoken: String,
)

/** One part of the card with its rows. */
data class EmergencyGroup(val section: String, val label: String, val hint: String, val rows: List<EmergencyRow>, val spokenHint: String = hint)

data class EmergencyView(
    /** Always the three parts, in order (an empty part shows its hint). */
    val groups: List<EmergencyGroup>,
    /** "5 lines · your partner sees it read-only", or how to start. */
    val summary: String,
    val count: Int,
) {
    companion object {
        val EMPTY = EmergencyView(
            EmergencySection.entries.map { EmergencyGroup(it.key, it.label, it.hint, emptyList(), it.spokenHint) },
            EmergencyRules.EMPTY_SUMMARY, 0,
        )
    }
}

/** What one typed line says: the name and the rest. */
data class EmergencyLine(val title: String, val detail: String)

/** What adding a line did: the line to show and whether it was turned away (the pane lights it in the accent). */
data class EmergencyAdded(val line: String, val refused: Boolean)

object EmergencyRules {
    const val MAX_TITLE = 40
    const val MAX_DETAIL = 160
    /** Most lines the card holds (it is one page, read in a hurry). */
    const val MAX_LINES = 60

    const val EMPTY_SUMMARY = "Empty. Add who to call, where important documents are and key accounts by name; your partner sees it on their family page."
    const val NOT_READ = "Type a name, then a colon and the details: “GP: Dr Patel, 01632 123456”."
    const val REFUSED_SECRET = "Navilife keeps passwords and PINs off the card. Write where they're kept instead: “Passwords: in the family password manager”."
    const val REFUSED_NUMBER = "Navilife keeps card, account and policy numbers off the card. Write the name and where the paperwork is: “Home insurance: Aviva, policy in the blue folder”."
    const val FULL = "The card is full. Remove a line first."
    const val SHARED = "Your partner sees this card on their family page, read-only. Passwords, PINs and card or account numbers never go on it."

    private val SEPARATORS = listOf(":", " - ", " – ", " — ")

    /** Words that name a secret; a line holding one must say where it's kept, not what it is. */
    private val SECRET = Regex(
        "\\b(passwords?|passcodes?|pass codes?|pins?|pin codes?|pin numbers?|memorable (word|answer|information)|" +
            "security (questions?|answers?)|cvv|cvc|recovery (code|phrase)s?|seed phrase|login codes?|one[- ]time codes?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SORT_CODE = Regex("(?<![\\d-])\\d{2}-\\d{2}-\\d{2}(?![\\d-])")
    /** A run of digits, allowing single spaces or hyphens between them, optionally led by +. */
    private val DIGIT_RUN = Regex("\\+?\\d(?:[ -]?\\d)*")
    private val FILLER = setOf("is", "are", "=", "-", "–", "—", ":", "my", "our", "the")

    /**
     * Reads one typed line: "GP: Dr Patel, 01632 123456" → GP and the rest. A name alone is fine; a long line with no
     * separator keeps its first words (up to [MAX_TITLE], cut at a word) as the name and the rest as the detail.
     */
    fun read(text: String): EmergencyLine? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val sep = SEPARATORS.map { t.indexOf(it) to it }.filter { it.first >= 0 }.minByOrNull { it.first }
        if (sep?.first == 0) return null // details with no name
        var (name, rest) = if (sep != null) t.substring(0, sep.first) to t.substring(sep.first + sep.second.length) else t to ""
        name = name.trim().trimEnd('.', ',', ';').trim()
        rest = rest.trim().trimEnd(';').trim()
        if (name.isEmpty()) return null
        if (name.length > MAX_TITLE) {
            // A long line with no separator: the first words are the name, the rest the detail.
            val cut = name.take(MAX_TITLE).substringBeforeLast(' ', name.take(MAX_TITLE)).trim()
            rest = (name.drop(cut.length).trim() + if (rest.isEmpty()) "" else " $rest").trim()
            name = cut
        }
        if (rest.length > MAX_DETAIL) rest = rest.take(MAX_DETAIL).substringBeforeLast(' ', rest.take(MAX_DETAIL)).trim()
        return EmergencyLine(name.replaceFirstChar { it.uppercase() }, rest)
    }

    private fun digitsOf(run: String) = run.count { it.isDigit() }

    /** A UK or international phone number: 10–13 digits starting 0 or + (short numbers like 999 or 111 are under 6 digits anyway). */
    fun isPhone(run: String): Boolean {
        val d = digitsOf(run)
        val t = run.trim()
        return (t.startsWith("0") || t.startsWith("+")) && d in 10..13
    }

    /**
     * Why a line can't go on the card under [section], or null when it can: a password or PIN with no "where" words
     * after it ([REFUSED_SECRET]); a card number (13–19 digits), a sort code, or any other run of 6 or more digits that
     * isn't a phone number under "Who to call" ([REFUSED_NUMBER]).
     */
    fun refusal(section: EmergencySection, text: String): String? {
        if (SORT_CODE.containsMatchIn(text)) return REFUSED_NUMBER
        for (m in DIGIT_RUN.findAll(text)) {
            // Card numbers (13–19 digits), account, policy and passport numbers: anything of 6 or more digits, except a
            // phone number under "Who to call".
            if (digitsOf(m.value) >= 6 && !(section == EmergencySection.CALL && isPhone(m.value))) return REFUSED_NUMBER
        }
        if (holdsSecret(text)) return REFUSED_SECRET
        return null
    }

    /**
     * Whether [text] holds a password, PIN or the like rather than saying where it's kept: "Passwords: in the family
     * password manager" says where; "PIN: 4821" or "wifi password is BlueFox77" doesn't. Only the first secret word
     * counts, so "password manager" later in a "where" line is fine. Shared with the facts page ([FactRules]).
     */
    fun holdsSecret(text: String): Boolean {
        val m = SECRET.find(text) ?: return false
        val after = text.substring(m.range.last + 1).split(Regex("[\\s:=]+"))
            .map { it.trim(',', '.', ';', '“', '”', '"', '\'') }
            .filter { it.isNotEmpty() && it.lowercase() !in FILLER }
        return after.size < 2
    }

    /** A sort code ("20-00-00") anywhere in [text]. Shared with the facts page ([FactRules]). */
    fun holdsSortCode(text: String): Boolean = SORT_CODE.containsMatchIn(text)

    /** Every run of digits in [text] (single spaces or hyphens allowed between them, a leading + kept). */
    fun digitRuns(text: String): List<String> = DIGIT_RUN.findAll(text).map { it.value }.toList()

    /** The phone number in a "Who to call" line, as digits (a leading + kept); null when there is none. */
    fun phone(detail: String): String? = DIGIT_RUN.findAll(detail).map { it.value }.firstOrNull(::isPhone)
        ?.let { r -> (if (r.trim().startsWith("+")) "+" else "") + r.filter { it.isDigit() } }

    /** Two lines are the same thing when their names match ignoring case and spaces, in the same part. */
    fun key(title: String): String = title.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The lines still on the card (id → fields); a line that would now be refused is left out (her page never shows one). */
    fun entries(entities: Map<String, Map<String, FieldValue>>): List<EmergencyEntry> = entities.mapNotNull { (id, s) ->
        if (s.f(EmergencyFields.DELETED).boolOrNull == true) return@mapNotNull null
        val section = EmergencySection.of(s.f(EmergencyFields.SECTION).textOrNull) ?: return@mapNotNull null
        val title = s.f(EmergencyFields.TITLE).textOrNull?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val detail = s.f(EmergencyFields.DETAIL).textOrNull?.trim().orEmpty()
        if (refusal(section, "$title: $detail") != null) return@mapNotNull null
        EmergencyEntry(id, section, title, detail, s.f(EmergencyFields.ADDED_AT).longOrNull ?: 0L)
    }

    fun row(e: EmergencyEntry) = EmergencyRow(
        e.id, e.section.key, e.title, e.detail.ifEmpty { null },
        if (e.section == EmergencySection.CALL) phone(e.detail) else null,
        if (e.detail.isEmpty()) e.title else "${e.title}. ${e.detail}",
    )

    /** The card: the three parts in order, each in the order its lines were added. */
    fun view(entries: List<EmergencyEntry>, viewer: String = ShoppingRules.OWNER): EmergencyView {
        val groups = EmergencySection.entries.map { s ->
            EmergencyGroup(s.key, s.label, s.hint, entries.filter { it.section == s }.sortedWith(compareBy({ it.addedAtMs }, { it.id })).map(::row), s.spokenHint)
        }
        val n = entries.size
        val summary = when {
            n == 0 -> if (viewer == ShoppingRules.OWNER) EMPTY_SUMMARY else "Meka hasn't filled the emergency card in yet."
            viewer != ShoppingRules.OWNER -> "Kept up to date by Meka"
            else -> "${if (n == 1) "1 line" else "$n lines"} · your partner sees it read-only on their family page"
        }
        return EmergencyView(groups, summary, n)
    }

    /**
     * Adding one typed line under [section]: refused when it holds a secret or a number ([refusal]); a name already on
     * that part gets the newly typed details (none typed leaves them). Returns what to show and the fields to write.
     */
    fun addPlan(
        section: EmergencySection,
        text: String,
        entries: List<EmergencyEntry>,
        nowMs: Long,
        newId: () -> String,
    ): Pair<EmergencyAdded, List<Pair<String, Map<String, FieldValue>>>> {
        val line = read(text) ?: return EmergencyAdded(NOT_READ, true) to emptyList()
        refusal(section, "${line.title}: ${line.detail}")?.let { return EmergencyAdded(it, true) to emptyList() }
        val same = entries.firstOrNull { it.section == section && key(it.title) == key(line.title) }
        if (same != null) {
            if (line.detail.isEmpty() || line.detail == same.detail) return EmergencyAdded("${same.title} is already on the card", false) to emptyList()
            return EmergencyAdded("Updated ${same.title}", false) to listOf(same.id to mapOf(EmergencyFields.DETAIL to line.detail.fv()))
        }
        if (entries.size >= MAX_LINES) return EmergencyAdded(FULL, true) to emptyList()
        return EmergencyAdded("Added ${line.title} to ${section.label}", false) to listOf(newId() to mapOf(
            EmergencyFields.SECTION to section.key.fv(),
            EmergencyFields.TITLE to line.title.fv(),
            EmergencyFields.DETAIL to line.detail.fv(),
            EmergencyFields.ADDED_AT to nowMs.fv(),
        ))
    }
}

/** The emergency card on a replica (Meka's apps; her page reads it through the server). */
class EmergencyCard(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    fun entries(): List<EmergencyEntry> =
        EmergencyRules.entries(replica.entities(EntityTypes.EMERGENCY_ENTRY).associate { it.ref.entityId to it.fields })

    fun view(): EmergencyView = EmergencyRules.view(entries())

    /** Adds (or updates) a line under [sectionKey]; an unknown part is refused like an unreadable line. */
    fun add(sectionKey: String, text: String): EmergencyAdded {
        val section = EmergencySection.of(sectionKey) ?: return EmergencyAdded(EmergencyRules.NOT_READ, true)
        val (added, writes) = EmergencyRules.addPlan(section, text, entries(), nowMs(), newId)
        writes.forEach { (id, fields) -> replica.commitLocal(EntityTypes.EMERGENCY_ENTRY, id, fields) }
        return added
    }

    /** Takes a line off the card for good. */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.EMERGENCY_ENTRY, id)?.takeIf { it[EmergencyFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.EMERGENCY_ENTRY, id, mapOf(EmergencyFields.DELETED to true.fv()))
        return true
    }
}
