package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Kids' admin (V1, Meka approved 2026-10-12), slice 1: each child's **sizes** — shoes, football boots, tops, trousers,
 * coat and school jumper — with a nudge to check them every three months, on Ask's **Kids** tile and in the Kids pane on
 * both apps. The children themselves come from About you (rule 13: names live in his data, never in code); a child taken
 * out of About you drops out of Kids too (what was kept for them stays in his data, unseen, and comes back with them).
 *
 * Stored as one synced `kid_admin` entity per child (ADR-008 addendum 2026-10-15, additive), id `c-<the child's name
 * folded>` ([KidsRules.idFor]) so both apps write the same entity: [KidFields], all LWW. Typing a size counts as having
 * checked them ([KidFields.CHECKED_AT]); "All still fit" stamps the same without changing a size.
 *
 * Non-AI, pure rules ([KidsRules]) over a thin store ([Kids]). Tests use invented children.
 */
object KidFields {
    /** The child's name as About you had it when last written (for export; the view always shows About you's). */
    const val NAME = "name"
    /** Int64: when the sizes were last typed or confirmed ("All still fit"). */
    const val CHECKED_AT = "checkedAtMs"
}

/** The sizes Kids keeps per child, in the pane's order; [key] is the entity's field. */
enum class KidSize(val key: String, val label: String, val hint: String) {
    SHOES("shoes", "Shoes", "Shoe size… (13 or 1½)"),
    BOOTS("boots", "Football boots", "Boot size… (12)"),
    TOPS("tops", "Tops", "Tops… (7–8 years)"),
    TROUSERS("trousers", "Trousers", "Trousers… (7–8 years)"),
    COAT("coat", "Coat", "Coat… (8–9 years)"),
    JUMPER("jumper", "School jumper", "Jumper… (26\" or 7–8)"),
    ;

    companion object {
        fun of(key: String?): KidSize? = entries.firstOrNull { it.key == key }
    }
}

/** What Kids holds for one child, as stored. */
data class KidRecord(val id: String, val sizes: Map<KidSize, String>, val checkedAtMs: Long?)

/** One size row of a child's card: the label, what's kept (null when empty) and the field's hint. */
data class KidSizeRow(val size: String, val label: String, val value: String?, val hint: String)

/**
 * A child's card: [name] and [line] from About you ("Under 7s · Oakfield School"); [sizes] every size row; [sizesLine]
 * the kept ones in one line ("Shoes 13 · Tops 7–8"); [checkedLine] when they were checked, lit ([due]) once three months
 * have gone by; [spoken] for screen readers.
 */
data class KidCard(
    val name: String,
    val line: String,
    val sizes: List<KidSizeRow>,
    val sizesLine: String?,
    val checkedLine: String,
    val due: Boolean,
    val spoken: String,
)

/** The Kids pane: a [summary], one card per child (About you's order), and [empty] when there are no children yet. */
data class KidsView(val summary: String, val kids: List<KidCard>, val empty: String?, val tile: LifeTileLine?) {
    val isEmpty: Boolean get() = kids.isEmpty()

    companion object {
        val EMPTY = KidsView(KidsRules.NO_CHILDREN, emptyList(), KidsRules.NO_CHILDREN, null)
    }
}

/** What a size save said: [line] under the field, [refused] when nothing was written, [undo] the value before a clear. */
data class KidSaved(val line: String, val refused: Boolean = false, val undo: String? = null)

/** Non-AI, pure. */
object KidsRules {
    const val TITLE = "Kids"
    const val ROW_LINE = "Each child's sizes · a nudge to check them every 3 months"
    /** Three months, near enough: a size typed or confirmed more than this long ago asks for a check. */
    const val CHECK_DAYS = 91
    const val MAX_SIZE = 16
    const val NO_CHILDREN = "Add the children in Ask → Settings → About you first; each gets a card here for their sizes."
    const val NO_SIZES = "No sizes yet · add what they wear now"
    const val NOT_SIZE = "A size has a number in it (13, 1½, 7–8 years, 26\") or is XS, S, M, L or XL, up to 16 characters."
    const val NOT_CHILD = "That child isn't in About you any more."
    const val CLEARED = "Cleared · Undo puts it back"
    const val FOOT = "Kept in your own data (synced, encrypted). Typing a size counts as checking them; \"All still fit\" says so without changing one."

    private val LETTER_SIZES = setOf("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL")

    /** The entity id for a child: "c-alex" for "Alex", "c-mary-jane" for "Mary Jane"; null for a name with no letters. */
    fun idFor(name: String): String? {
        val folded = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
        return if (folded.isEmpty()) null else "c-$folded"
    }

    /**
     * A typed size made tidy, or null when it can't be one: spaces collapsed, "7-8" → "7–8", "s"/"xl" upper-cased; it
     * must hold a digit or be a letter size, and fit [MAX_SIZE].
     */
    fun tidy(typed: String): String? {
        var t = typed.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > MAX_SIZE) return null
        if (t.uppercase() in LETTER_SIZES) return t.uppercase()
        if (t.none { it.isDigit() || it == '½' }) return null
        t = Regex("([0-9½]) ?- ?([0-9])").replace(t) { m -> m.groupValues[1] + "–" + m.groupValues[2] }
        return t
    }

    /** The kept sizes and when they were checked, from stored fields. */
    fun record(id: String, fields: Map<String, FieldValue>): KidRecord = KidRecord(
        id = id,
        sizes = KidSize.entries.mapNotNull { s -> fields[s.key]?.textOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { s to it } }.toMap(),
        checkedAtMs = fields[KidFields.CHECKED_AT]?.longOrNull,
    )

    /** "Shoes 13 · Tops 7–8"; null with nothing kept. */
    fun sizesLine(sizes: Map<KidSize, String>): String? =
        KidSize.entries.mapNotNull { s -> sizes[s]?.let { "${s.label} $it" } }.joinToString(" · ").ifEmpty { null }

    /** Whether a child's sizes want a look: some kept, and checked [CHECK_DAYS] or more days ago (or never stamped). */
    fun due(r: KidRecord?, todayDay: Long, cal: LocalCalendar): Boolean {
        if (r == null || r.sizes.isEmpty()) return false
        val checked = r.checkedAtMs ?: return true
        return todayDay - cal.epochDayOf(checked) >= CHECK_DAYS
    }

    /** "Sizes checked today" · "Sizes checked Wed 14 Oct" · "Last checked Tue 14 Jul · do they still fit?" · [NO_SIZES]. */
    fun checkedLine(r: KidRecord?, todayDay: Long, cal: LocalCalendar): String {
        if (r == null || r.sizes.isEmpty()) return NO_SIZES
        val checked = r.checkedAtMs ?: return "Not checked yet · do they still fit?"
        val day = cal.epochDayOf(checked)
        val label = if (day == todayDay) "today" else CivilDate.shortLabel(day)
        return if (due(r, todayDay, cal)) "Last checked $label · do they still fit?" else "Sizes checked $label"
    }

    /** "Alex", "Alex and Sam", "Alex, Sam and Jo". */
    fun names(ns: List<String>): String = when (ns.size) {
        0 -> ""
        1 -> ns[0]
        else -> ns.dropLast(1).joinToString(", ") + " and " + ns.last()
    }

    /** "Alex's", or "Alex's and Sam's" for the tile and summary. */
    private fun possessive(ns: List<String>): String = names(ns.map { "$it's" })

    fun card(c: AboutChild, r: KidRecord?, todayDay: Long, cal: LocalCalendar): KidCard {
        val sizes = r?.sizes.orEmpty()
        val line = sizesLine(sizes)
        val checked = checkedLine(r, todayDay, cal)
        return KidCard(
            name = c.name,
            line = AboutYouRules.childLine(c),
            sizes = KidSize.entries.map { s -> KidSizeRow(s.key, s.label, sizes[s], s.hint) },
            sizesLine = line,
            checkedLine = checked,
            due = due(r, todayDay, cal),
            spoken = listOfNotNull(c.name, line, checked).joinToString(". "),
        )
    }

    /**
     * The pane and the tile for the children About you holds, in its order; a record whose child has left About you is
     * not shown. The tile asks for a check (lit) when one is due, else names the first child's shoe size (or first size).
     */
    fun view(children: List<AboutChild>, records: Map<String, KidRecord>, todayDay: Long, cal: LocalCalendar): KidsView {
        if (children.isEmpty()) return KidsView.EMPTY
        val cards = children.map { c -> card(c, idFor(c.name)?.let { records[it] }, todayDay, cal) }
        val dueNames = cards.filter { it.due }.map { it.name }
        val withSizes = cards.filter { it.sizesLine != null }
        val summary = when {
            dueNames.isNotEmpty() -> "Time to check ${possessive(dueNames)} sizes · tap All still fit or type the new ones"
            withSizes.isEmpty() -> "Add what ${if (cards.size == 1) cards[0].name + " wears" else "each child wears"} now; Navilife asks you to check every 3 months."
            withSizes.size < cards.size -> "Sizes for ${names(withSizes.map { it.name })} · add ${possessive(cards.filter { it.sizesLine == null }.map { it.name })}"
            else -> "Sizes for ${names(cards.map { it.name })} · checked every 3 months"
        }
        return KidsView(summary, cards, null, tile(cards, dueNames))
    }

    private fun tile(cards: List<KidCard>, dueNames: List<String>): LifeTileLine? {
        if (dueNames.isNotEmpty()) return LifeTileLine("Check ${possessive(dueNames)} sizes", true)
        val first = cards.firstOrNull { it.sizesLine != null } ?: return null
        val shoes = first.sizes.firstOrNull { it.size == KidSize.SHOES.key }?.value
        val size = if (shoes != null) "shoes $shoes" else first.sizes.first { it.value != null }.let { "${it.label.lowercase()} ${it.value}" }
        val more = cards.count { it.sizesLine != null } - 1
        return LifeTileLine("${first.name} · $size" + if (more > 0) " · +$more" else "", false)
    }

    /** The line after a save: "Saved · shoes 13". */
    fun savedLine(size: KidSize, value: String): String = "Saved · ${size.label.lowercase()} $value"

    /** The line after All still fit. */
    fun fitLine(name: String): String = "Marked $name's sizes as still fitting · Navilife asks again in 3 months"
}

/** Kids' admin on a replica (both apps). The child is named as About you holds them; the facade checks they're there. */
class Kids(private val replica: Replica, private val nowMs: () -> Long) {
    fun records(): Map<String, KidRecord> =
        replica.entities(EntityTypes.KID_ADMIN).associate { it.ref.entityId to KidsRules.record(it.ref.entityId, it.fields) }

    /**
     * Keeps [typed] as [childName]'s [sizeKey]; blank clears it (with Undo). Refused, with nothing written, when it can't
     * be a size or the size or name is unknown. Any save stamps the check.
     */
    fun setSize(childName: String, sizeKey: String, typed: String): KidSaved {
        val size = KidSize.of(sizeKey) ?: return KidSaved(KidsRules.NOT_SIZE, refused = true)
        val id = KidsRules.idFor(childName) ?: return KidSaved(KidsRules.NOT_CHILD, refused = true)
        val before = records()[id]?.sizes?.get(size)
        if (typed.isBlank()) {
            if (before == null) return KidSaved(KidsRules.CLEARED.substringBefore(" ·"))
            replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(KidFields.NAME to childName.trim().fv(), size.key to FieldValue.Null))
            return KidSaved(KidsRules.CLEARED, undo = before)
        }
        val clean = KidsRules.tidy(typed) ?: return KidSaved(KidsRules.NOT_SIZE, refused = true)
        replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(
            KidFields.NAME to childName.trim().fv(),
            size.key to clean.fv(),
            KidFields.CHECKED_AT to nowMs().fv(),
        ))
        return KidSaved(KidsRules.savedLine(size, clean))
    }

    /** "All still fit": stamps the check for [childName] without changing a size; false when nothing is kept for them. */
    fun allFit(childName: String): Boolean {
        val id = KidsRules.idFor(childName) ?: return false
        if (records()[id]?.sizes.isNullOrEmpty()) return false
        replica.commitLocal(EntityTypes.KID_ADMIN, id, mapOf(KidFields.CHECKED_AT to nowMs().fv()))
        return true
    }
}
