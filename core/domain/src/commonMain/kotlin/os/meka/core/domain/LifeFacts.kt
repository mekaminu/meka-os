package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * What MEKA knows about my life (V1): a facts page — the car's reg, the insurer, the boys' school, sizes, the GP, the
 * utilities — that Ask and Talk answer from. Every fact is visible, editable (type the same name again) and deletable,
 * and each can be kept from the AI. One synced entity type (ADR-008 addendum 2026-10-10): `life_fact`, a random id.
 * Stored in Meka's own synced data only (his devices and his own server).
 *
 * The page never holds a secret or an identity number: [FactRules.refusal] turns away passwords and PINs (unless the
 * line says where they're kept), card and bank numbers, sort codes, and passport, National Insurance, driving licence
 * and NHS numbers, before anything is written; a stored line that fails the check is never shown or sent.
 *
 * Ask (slice 1): only the facts that bear on the question go with it ([FactAskRules.askLines]), never one kept from the
 * AI; they are Meka's own words, so never untrusted. Plain, non-AI rules.
 */
object FactFields {
    const val LABEL = "label"
    const val VALUE = "value"
    /** [FactGroup.key], worked out from the label when the fact is added. */
    const val GROUP = "group"
    /** True: kept from the AI (shown on the page, never sent with a question). */
    const val AI_OFF = "aiOff"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** The page's groups, in the order it shows them; a fact's group comes from the words of its name. */
enum class FactGroup(val key: String, val label: String, val words: Set<String>) {
    // Sizes first ("Alex shoe size" is a size), then health and care, the car, the home, the family (whose words also
    // take in the partner's and children's first names from About you: rule 13, never in the code).
    SIZES("sizes", "Sizes", setOf("size", "shoe", "shirt", "collar", "waist", "dress", "trouser", "jean", "chest", "bra", "glove", "hat", "ring", "leg")),
    HEALTH("health", "Health and care", setOf("gp", "doctor", "surgery", "dentist", "optician", "pharmacy", "chemist", "nhs", "vet", "physio", "hospital", "clinic", "health", "blood")),
    CAR("car", "Car", setOf("car", "reg", "registration", "mot", "tyre", "vehicle", "breakdown", "rac", "aa", "garage", "van", "plate")),
    HOME("home", "Home and utilities", setOf("home", "house", "boiler", "electric", "electricity", "gas", "water", "energy", "broadband", "wifi", "internet", "council", "bin", "mortgage", "landlord", "rent", "licence", "meter", "utility", "supplier", "plumber", "electrician", "address", "postcode")),
    FAMILY("family", "Family and school", setOf("school", "kid", "boy", "son", "daughter", "wife", "teacher", "nursery", "club", "childminder", "family", "birthday", "mum", "dad")),
    OTHER("other", "Other", emptySet()),
    ;

    /** Whether [ws] (lower-case words) says this group: its own words, and for [FAMILY] the household's names. */
    fun has(ws: Collection<String>): Boolean =
        words.any { it in ws } || (this == FAMILY && Household.names.familyWords.any { it in ws })

    companion object {
        fun of(key: String?): FactGroup = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One fact as stored. */
data class LifeFact(val id: String, val label: String, val value: String, val group: FactGroup, val aiOff: Boolean, val addedAtMs: Long)

/** One fact as the page shows it: "Car reg" · "AB12 CDE", with "Kept from AI" when it is. */
data class FactRow(
    val id: String,
    val label: String,
    val value: String,
    val group: String,
    /** True when MEKA's AI never sees it. */
    val keptFromAi: Boolean,
    /** "Kept from AI" or null; the row's button reads [aiButton]. */
    val aiLine: String?,
    /** "Keep from AI" / "Let MEKA use it". */
    val aiButton: String,
    val spoken: String,
    /** What Copy puts on the clipboard: the fact alone ("AB12 CDE"), ready to paste into a form (slice 3). */
    val copyText: String,
    /** The Copy button's spoken label: "Copy Car reg". */
    val copySpoken: String,
)

/** One group of the page with its facts, oldest first. */
data class FactGroupView(val group: String, val label: String, val rows: List<FactRow>)

data class FactsView(
    /** Only groups with facts, in [FactGroup] order. */
    val groups: List<FactGroupView>,
    /** "12 facts · MEKA answers from 11 · 1 kept from AI", or how to start. */
    val summary: String,
    val count: Int,
) {
    companion object {
        val EMPTY = FactsView(emptyList(), FactRules.EMPTY_SUMMARY, 0)
    }
}

/** What one typed line says: the fact's name and the fact. */
data class FactLine(val label: String, val value: String)

/** What adding a line did: the line to show under the field and whether it was turned away (lit in the accent). */
data class FactAdded(val line: String, val refused: Boolean)

object FactRules {
    const val MAX_LABEL = 40
    const val MAX_VALUE = 200
    const val MAX_FACTS = 200

    /** One example, on one line on the cover screen (quality gate slice 23); TalkBack reads [DESCRIPTION]. */
    const val HINT = "Car reg: AB12 CDE"
    const val DESCRIPTION = "Add a fact: its name, a colon, then the fact, for example Car reg: AB12 CDE"
    const val EMPTY_SUMMARY = "Nothing yet. Add the things you look up: the car's reg, the GP, shoe sizes, the energy supplier. Navilife answers from them."
    const val NOT_READ = "Type the name, a colon, then the fact: “Car reg: AB12 CDE”."
    const val REFUSED_SECRET = "Navilife keeps passwords and PINs off this page. Write where they're kept instead: “Passwords: in the family password manager”."
    const val REFUSED_BANK = "Navilife keeps card and bank numbers and sort codes off this page. Keep the bank's name instead: “Bank: Monzo”."
    const val REFUSED_ID = "Navilife doesn't keep passport, National Insurance, driving licence or NHS numbers. Write where the document is: “Passports: study desk, top drawer”."
    const val FULL = "The page is full. Remove a fact first."
    const val NOTE = "Kept in your own Navilife data only. Navilife's AI sees a fact only when a question needs it, never one kept from AI. Passwords and card, bank or ID numbers never go here."
    const val KEPT_LINE = "Kept from AI"
    const val KEEP_BUTTON = "Keep from AI"
    const val USE_BUTTON = "Let Navilife use it"
    /** The row's button that puts the fact on the clipboard (filling forms, slice 3). */
    const val COPY_BUTTON = "Copy"
    /** How much of the fact the "Copied …" line shows before "…". */
    const val COPIED_SHOWN = 40

    /** "my car reg is …", "car reg: …", "car reg - …", "car reg = …". The first separator wins. */
    private val SEPARATORS = listOf(":", " - ", " – ", " — ", " = ", " is ", " are ")
    private val LEADING = Regex("^(my|our|the)\\s+", RegexOption.IGNORE_CASE)

    private val BANK_WORDS = Regex("\\b(account (number|no)|bank account|iban|card (number|no)|credit card|debit card|bank card)\\b", RegexOption.IGNORE_CASE)
    private val ID_WORDS = Regex("\\b(passports?|national insurance|ni (number|no)|nino|driving licen[cs]e|nhs (number|no)|unique taxpayer|utr)\\b", RegexOption.IGNORE_CASE)
    /** A National Insurance number: "QQ 12 34 56 C". */
    private val NI_NUMBER = Regex("\\b[A-CEGHJ-PR-TW-Z]{2}\\s?\\d{2}\\s?\\d{2}\\s?\\d{2}\\s?[A-D]\\b", RegexOption.IGNORE_CASE)

    /**
     * Reads one typed line: "Car reg: AB12 CDE", "my shoe size is 10" → the name and the fact. Null when there's no
     * name, no fact or no separator between them.
     */
    fun read(text: String): FactLine? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        val sep = SEPARATORS.map { lower.indexOf(it) to it }.filter { it.first > 0 }.minByOrNull { it.first } ?: return null
        var label = t.substring(0, sep.first).trim().trimEnd('.', ',', ';').trim()
        var value = t.substring(sep.first + sep.second.length).trim().trimEnd(';').trim()
        label = label.replace(LEADING, "").trim()
        if (label.isEmpty() || value.isEmpty()) return null
        if (label.length > MAX_LABEL) return null
        if (value.length > MAX_VALUE) value = value.take(MAX_VALUE).substringBeforeLast(' ', value.take(MAX_VALUE)).trim()
        return FactLine(label.replaceFirstChar { it.uppercase() }, value)
    }

    /** A word as the matching sees it: lower case, no possessive, no plural "s" (shoes → shoe, Theo's → theo). */
    fun word(w: String): String {
        var s = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }.removeSuffix("'s").removeSuffix("'").filter { it.isLetterOrDigit() }
        if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
        return s
    }

    fun words(text: String): List<String> = text.split(Regex("[^\\p{L}\\p{N}']+")).map(::word).filter { it.isNotEmpty() }

    /** The group a fact's name puts it in: the first group (in [FactGroup] order) one of its words belongs to. */
    fun groupOf(label: String): FactGroup {
        val ws = words(label).toSet()
        return FactGroup.entries.firstOrNull { g -> g.has(ws) } ?: FactGroup.OTHER
    }

    /**
     * Why a line can't go on the page, or null when it can: a password or PIN with no "where" words after it; a sort
     * code, a card-length number (13–19 digits that isn't a phone number) or a bank account with 6 or more digits; a
     * passport, National Insurance, driving licence or NHS number (the word with 6 or more digits, or an NI number
     * anywhere). A phone number, a policy or customer reference, a reg plate and a size are all fine.
     */
    fun refusal(text: String): String? {
        if (EmergencyRules.holdsSortCode(text)) return REFUSED_BANK
        val runs = EmergencyRules.digitRuns(text)
        val digits = runs.map { r -> r.count { it.isDigit() } }
        if (runs.zip(digits).any { (r, d) -> d in 13..19 && !EmergencyRules.isPhone(r) }) return REFUSED_BANK
        if (BANK_WORDS.containsMatchIn(text) && digits.any { it >= 6 }) return REFUSED_BANK
        if (NI_NUMBER.containsMatchIn(text)) return REFUSED_ID
        if (ID_WORDS.containsMatchIn(text) && digits.any { it >= 6 }) return REFUSED_ID
        if (EmergencyRules.holdsSecret(text)) return REFUSED_SECRET
        return null
    }

    /** Two facts are the same thing when their names match ignoring case, spaces and a leading "my". */
    fun key(label: String): String = label.trim().replace(Regex("\\s+"), " ").replace(LEADING, "").lowercase()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The facts on the page (id → fields); a fact that would now be refused is left out (never shown or sent). */
    fun facts(entities: Map<String, Map<String, FieldValue>>): List<LifeFact> = entities.mapNotNull { (id, s) ->
        if (s.f(FactFields.DELETED).boolOrNull == true) return@mapNotNull null
        val label = s.f(FactFields.LABEL).textOrNull?.trim().orEmpty()
        val value = s.f(FactFields.VALUE).textOrNull?.trim().orEmpty()
        if (label.isEmpty() || value.isEmpty()) return@mapNotNull null
        if (refusal("$label: $value") != null) return@mapNotNull null
        LifeFact(id, label, value, FactGroup.of(s.f(FactFields.GROUP).textOrNull), s.f(FactFields.AI_OFF).boolOrNull == true,
            s.f(FactFields.ADDED_AT).longOrNull ?: 0L)
    }.sortedWith(compareBy({ it.addedAtMs }, { it.id }))

    fun row(f: LifeFact) = FactRow(
        f.id, f.label, f.value, f.group.key, f.aiOff,
        if (f.aiOff) KEPT_LINE else null,
        if (f.aiOff) USE_BUTTON else KEEP_BUTTON,
        "${f.label}: ${f.value}" + if (f.aiOff) ". Kept from AI" else "",
        f.value,
        "$COPY_BUTTON ${f.label}",
    )

    /**
     * The line under the field once a fact is copied: "Copied Car reg · AB12 CDE". A long
     * fact is cut at [COPIED_SHOWN] characters with "…" (the clipboard still gets all of it). Copying is the same for a
     * fact kept from AI: the clipboard is Meka's own, nothing goes to MEKA's AI or the server.
     */
    fun copiedLine(row: FactRow): String {
        val v = row.copyText.trim()
        val shown = if (v.length > COPIED_SHOWN) v.take(COPIED_SHOWN).trimEnd() + "…" else v
        return "Copied ${row.label} · $shown"
    }

    fun view(facts: List<LifeFact>): FactsView {
        if (facts.isEmpty()) return FactsView.EMPTY
        val groups = FactGroup.entries.mapNotNull { g ->
            facts.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { fs -> FactGroupView(g.key, g.label, fs.map(::row)) }
        }
        val n = facts.size
        val kept = facts.count { it.aiOff }
        val summary = listOfNotNull(
            if (n == 1) "1 fact" else "$n facts",
            if (kept == 0) "Navilife answers from them" else "Navilife answers from ${n - kept}",
            "$kept kept from AI".takeIf { kept > 0 },
        ).joinToString(" · ")
        return FactsView(groups, summary, n)
    }

    /**
     * Adding one typed line: refused when unreadable or when it holds a secret or a number ([refusal]); a name already
     * on the page gets the newly typed fact (its group and AI choice stay). Returns what to show and the fields to write.
     */
    fun addPlan(text: String, facts: List<LifeFact>, nowMs: Long, newId: () -> String): Pair<FactAdded, List<Pair<String, Map<String, FieldValue>>>> {
        val line = read(text) ?: return FactAdded(NOT_READ, true) to emptyList()
        refusal("${line.label}: ${line.value}")?.let { return FactAdded(it, true) to emptyList() }
        val same = facts.firstOrNull { key(it.label) == key(line.label) }
        if (same != null) {
            if (same.value == line.value) return FactAdded("${same.label} is already on the page", false) to emptyList()
            return FactAdded("Updated ${same.label}", false) to listOf(same.id to mapOf(FactFields.VALUE to line.value.fv()))
        }
        if (facts.size >= MAX_FACTS) return FactAdded(FULL, true) to emptyList()
        val group = groupOf(line.label)
        return FactAdded("Added ${line.label} to ${group.label}", false) to listOf(newId() to mapOf(
            FactFields.LABEL to line.label.fv(),
            FactFields.VALUE to line.value.fv(),
            FactFields.GROUP to group.key.fv(),
            FactFields.AI_OFF to false.fv(),
            FactFields.ADDED_AT to nowMs.fv(),
        ))
    }
}

/** Facts with a question (Ask and Talk): only the ones that bear on it, never one kept from the AI. */
object FactAskRules {
    /** Most facts sent with an ordinary question. */
    const val ASK_ITEMS = 12
    /** Most sent when Meka asks what MEKA knows about him. */
    const val ASK_ALL = 20

    private val ASKS_ALL = Regex("what (do|does) (you|meka|navilife|navi life|navy life) know|about me\\b|my facts|facts page|what have i told you", RegexOption.IGNORE_CASE)
    /** Words too common to tie a fact to a question. */
    private val STOP = setOf("my", "our", "the", "a", "an", "of", "for", "is", "and", "to", "in", "on", "at", "name", "number", "no", "what", "who", "whats", "which", "where", "when")

    /**
     * Which of [facts] go with [question]: every usable one (up to [ASK_ALL]) when he asks what MEKA knows about him;
     * else those whose name shares a word with the question, or whose group the question names ("energy supplier" →
     * Home and utilities), up to [ASK_ITEMS]. Kept-from-AI facts never go.
     */
    fun relevant(facts: List<LifeFact>, question: String): List<LifeFact> {
        val usable = facts.filter { !it.aiOff }
        if (usable.isEmpty()) return emptyList()
        if (ASKS_ALL.containsMatchIn(question)) return usable.take(ASK_ALL)
        val qs = FactRules.words(question).filter { it !in STOP }.toSet()
        if (qs.isEmpty()) return emptyList()
        val groups = FactGroup.entries.filter { g -> g.has(qs) }.toSet()
        return usable.filter { f ->
            f.group in groups || FactRules.words(f.label).any { it !in STOP && it in qs }
        }.take(ASK_ITEMS)
    }

    /**
     * The lines sent (Ask's `facts` lines): a head ("Facts · 14 kept · 3 bear on the question") once any are
     * usable, then each fact "Fact · Car · Car reg: AB12 CDE". Nothing at all when the page has no usable fact.
     */
    fun askLines(facts: List<LifeFact>, question: String): List<String> {
        val usable = facts.count { !it.aiOff }
        if (usable == 0) return emptyList()
        val chosen = relevant(facts, question)
        val head = when {
            chosen.isEmpty() -> "Facts · $usable kept · none bear on this question"
            chosen.size == usable -> "Facts · $usable kept · all below"
            chosen.size == 1 -> "Facts · $usable kept · 1 bears on the question"
            else -> "Facts · $usable kept · ${chosen.size} bear on the question"
        }
        return listOf(head) + chosen.map { "Fact · ${it.group.label} · ${it.label}: ${it.value.replace(Regex("\\s+"), " ")}" }
    }
    /** Where a card puts the fact, in its words. */
    const val PAGE = "What Navilife knows"

    /**
     * The fact a model's `add_fact` would keep (slice 2: "remember that my shoe size is 10" → `{"title":"Shoe size: 10"}`),
     * read as a line typed on the page: null when it can't be read, would be turned away ([FactRules.refusal]: a
     * password, a card, bank or ID number), is already on the page with that value, or the page is full. A name already
     * on the page (even one kept from the AI) makes a card that updates it; the card never shows the old value.
     */
    fun proposal(facts: List<LifeFact>, title: String?): AskProposal.AddFact? {
        val line = FactRules.read(title ?: return null) ?: return null
        if (FactRules.refusal("${line.label}: ${line.value}") != null) return null
        val same = facts.firstOrNull { FactRules.key(it.label) == FactRules.key(line.label) }
        if (same != null) return if (same.value == line.value) null else AskProposal.AddFact(same.label, line.value, same.id)
        if (facts.size >= FactRules.MAX_FACTS) return null
        return AskProposal.AddFact(line.label, line.value, null)
    }

    /** The card's words: "Keep on What MEKA knows · Shoe size: 10" (button Keep), or "Update on … · GP: Dr Patel" (Update). */
    fun cardLine(p: AskProposal.AddFact): String = (if (p.factId == null) "Keep on $PAGE · " else "Update on $PAGE · ") + "${p.label}: ${p.value}"

    /** The undo bar's line: "Kept Shoe size: 10 on What MEKA knows" / "Updated GP on What MEKA knows". */
    fun doneLine(p: AskProposal.AddFact): String =
        if (p.factId == null) "Kept ${p.label}: ${p.value} on $PAGE" else "Updated ${p.label} on $PAGE"
}

/** The facts page on a replica (Meka's apps). */
class LifeFacts(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    fun all(): List<LifeFact> = FactRules.facts(replica.entities(EntityTypes.LIFE_FACT).associate { it.ref.entityId to it.fields })

    fun view(): FactsView = FactRules.view(all())

    /** Adds (or updates) a fact from one typed line. */
    fun add(text: String): FactAdded {
        val (added, writes) = FactRules.addPlan(text, all(), nowMs(), newId)
        writes.forEach { (id, fields) -> replica.commitLocal(EntityTypes.LIFE_FACT, id, fields) }
        return added
    }

    /**
     * What an Ask/Talk fact card does on Meka's tap, checked again now: a new fact is added (its id returned with a null
     * old value), or the fact [factId] gets [value] (its id and the value it had). Throws [ValidationException] when it
     * would be turned away, the page is full, or the same fact is already there.
     */
    fun keep(label: String, value: String, factId: String?): Pair<String, String?> {
        val facts = all()
        FactRules.refusal("$label: $value")?.let { throw ValidationException(it) }
        val same = facts.firstOrNull { it.id == factId } ?: facts.firstOrNull { FactRules.key(it.label) == FactRules.key(label) }
        if (same != null) {
            if (same.value == value) throw ValidationException("${same.label} is already on the page")
            replica.commitLocal(EntityTypes.LIFE_FACT, same.id, mapOf(FactFields.VALUE to value.fv()))
            return same.id to same.value
        }
        val (added, writes) = FactRules.addPlan("$label: $value", facts, nowMs(), newId)
        val (id, fields) = writes.singleOrNull()?.takeIf { !added.refused } ?: throw ValidationException(added.line)
        replica.commitLocal(EntityTypes.LIFE_FACT, id, fields)
        return id to null
    }

    /** The fact card's Undo: [before] comes back while the fact still reads [after]. False when it changed since or went. */
    fun putBack(id: String, before: String, after: String): Boolean {
        val f = all().firstOrNull { it.id == id }?.takeIf { it.value == after } ?: return false
        replica.commitLocal(EntityTypes.LIFE_FACT, f.id, mapOf(FactFields.VALUE to before.fv()))
        return true
    }

    /** Lets MEKA's AI use a fact ([use] true) or keeps it from the AI. False when there's no such fact. */
    fun setAi(id: String, use: Boolean): Boolean {
        val f = all().firstOrNull { it.id == id } ?: return false
        if (f.aiOff == !use) return true
        replica.commitLocal(EntityTypes.LIFE_FACT, id, mapOf(FactFields.AI_OFF to (!use).fv()))
        return true
    }

    /** Takes a fact off the page for good. */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.LIFE_FACT, id)?.takeIf { it[FactFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.LIFE_FACT, id, mapOf(FactFields.DELETED to true.fv()))
        return true
    }
}
