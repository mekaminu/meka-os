package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * The shared shopping list (build plan "Family sharing with Jeanette", slice 1, Meka approved 2026-10-09). One synced
 * `shopping_item` entity per thing to buy (ADR-008 addendum 2026-10-10). Ticking it ("Got") is a field, never a
 * delete, so it can be put back; adding a name that is already on the list never makes a second row (a got one comes
 * back instead). [ShoppingFields.BY] says who added it ("meka", or "jeanette" once her page lands), so Lists can say
 * "From Jeanette". Plain, non-AI rules; nothing here leaves Meka's own synced data.
 */
object ShoppingFields {
    const val TITLE = "title"
    /** True once bought ("Got"); false while still to buy. Last writer wins. */
    const val GOT = "got"
    const val ADDED_AT = "addedAtMs"
    const val GOT_AT = "gotAtMs"
    /** Who added it: [ShoppingRules.OWNER] or a family member's name key ("jeanette"). Display only. */
    const val BY = "by"
    const val DELETED = ActionableFields.DELETED
}

/** One row of the shopping list. [meta] is null for Meka's own items still to buy. */
data class ShoppingItem(val id: String, val title: String, val got: Boolean, val meta: String?, val by: String, val atMs: Long)

/** What one typed line adds ([ShoppingRules.add]): the ids now to buy in order, and each entity's fields to write. */
data class ShoppingAdd(val ids: List<String>, val writes: List<Pair<String, Map<String, FieldValue>>>)

data class ShoppingView(
    /** Still to buy, in the order they were added (oldest first, so the list reads like a note). */
    val toBuy: List<ShoppingItem>,
    /** Bought in the last [ShoppingRules.GOT_DAYS] days, newest first, at most [ShoppingRules.MAX_GOT]. */
    val got: List<ShoppingItem>,
) {
    val count: Int get() = toBuy.size
    /** "3 to buy · 2 got", "Nothing to buy". */
    val line: String get() = ShoppingRules.line(toBuy.size, got.size)

    companion object { val EMPTY = ShoppingView(emptyList(), emptyList()) }
}

object ShoppingRules {
    const val OWNER = "meka"
    const val MAX_TITLE = 120
    /** How long a bought item stays under Got (it can be put back) before it drops off the screen. */
    const val GOT_DAYS = 7
    const val MAX_GOT = 20
    const val ADD_HINT = "Add to shopping… (milk, eggs)"
    const val EMPTY_LINE = "Nothing to buy. Add what you need below; separate several with commas."

    /** "Oat milk" → "oat milk": two items are the same thing when their names match ignoring case and spaces. */
    fun key(title: String): String = tidy(title).lowercase()

    /** Trimmed, inner runs of spaces made one, cut at [MAX_TITLE]. */
    fun tidy(title: String): String = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE).trim()

    /**
     * What one typed line adds: "milk, eggs\nbread" → [milk, eggs, bread]. Commas, semicolons and new lines separate
     * (never "and", so "fish and chips" stays one thing); blanks and repeats within the line are dropped.
     */
    fun split(text: String): List<String> =
        text.split(',', ';', '\n').map(::tidy).filter { it.isNotEmpty() }.distinctBy(::key)

    /** "jeanette" → "Jeanette"; [OWNER] and blanks → null (Meka's own items carry no name). */
    fun byName(by: String?): String? {
        val b = by?.trim().orEmpty()
        if (b.isEmpty() || b.equals(OWNER, ignoreCase = true)) return null
        return b.replaceFirstChar { it.uppercase() }
    }

    /** A to-buy row's line: "From Jeanette · today"; null for Meka's own. */
    fun toBuyMeta(by: String?, addedAtMs: Long, nowMs: Long, cal: LocalCalendar): String? =
        byName(by)?.let { "From $it · ${day(addedAtMs, nowMs, cal)}" }

    /** A got row's line: "Got today", "Got Thu 8 Oct". */
    fun gotMeta(gotAtMs: Long, nowMs: Long, cal: LocalCalendar): String = "Got ${day(gotAtMs, nowMs, cal)}"

    fun line(toBuy: Int, got: Int): String = when {
        toBuy == 0 && got == 0 -> "Nothing to buy"
        toBuy == 0 -> "All got · $got this week"
        got == 0 -> "$toBuy to buy"
        else -> "$toBuy to buy · $got got"
    }

    /** "Jeanette " → "jeanette": who added a thing, as stored in [ShoppingFields.BY]; blank is [OWNER]. */
    fun whoKey(by: String): String = by.trim().lowercase().ifEmpty { OWNER }.take(40)

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    private fun live(items: Map<String, Map<String, FieldValue>>) =
        items.filterValues { it.f(ShoppingFields.DELETED).boolOrNull != true }

    /**
     * What the list shows, from each item's current fields (id → fields). Pure, so the devices ([Shopping]) and MEKA's
     * server (the family page, slice 2) read the list the same way.
     */
    fun view(items: Map<String, Map<String, FieldValue>>, nowMs: Long, cal: LocalCalendar): ShoppingView {
        val today = cal.epochDayOf(nowMs)
        val live = live(items)
        val toBuy = live.filter { it.value.f(ShoppingFields.GOT).boolOrNull != true }
            .map { (id, s) ->
                val at = s.f(ShoppingFields.ADDED_AT).longOrNull ?: 0L
                val by = s.f(ShoppingFields.BY).textOrNull ?: OWNER
                ShoppingItem(id, s.f(ShoppingFields.TITLE).textOrNull.orEmpty(), false, toBuyMeta(by, at, nowMs, cal), by, at)
            }
            .filter { it.title.isNotEmpty() }
            .sortedWith(compareBy<ShoppingItem> { it.atMs }.thenBy { it.id })
        val got = live.filter { it.value.f(ShoppingFields.GOT).boolOrNull == true }
            .map { (id, s) ->
                val at = s.f(ShoppingFields.GOT_AT).longOrNull ?: 0L
                val by = s.f(ShoppingFields.BY).textOrNull ?: OWNER
                ShoppingItem(id, s.f(ShoppingFields.TITLE).textOrNull.orEmpty(), true, gotMeta(at, nowMs, cal), by, at)
            }
            .filter { it.title.isNotEmpty() && today - cal.epochDayOf(it.atMs) < GOT_DAYS }
            .sortedWith(compareByDescending<ShoppingItem> { it.atMs }.thenBy { it.id })
            .take(MAX_GOT)
        return ShoppingView(toBuy, got)
    }

    /**
     * What adding [text] for [by] writes ([split]): a name already to buy is left as it is; one under Got (or bought
     * longer ago) comes back to buy on its own entity; anything else is a new entity from [newId] (asked in typed
     * order). Several things in one line keep their typed order: each is stamped a millisecond after the one before.
     */
    fun add(text: String, items: Map<String, Map<String, FieldValue>>, by: String, nowMs: Long, newId: () -> String): ShoppingAdd {
        val names = split(text)
        if (names.isEmpty()) return ShoppingAdd(emptyList(), emptyList())
        val byKey = live(items).entries.groupBy { key(it.value.f(ShoppingFields.TITLE).textOrNull.orEmpty()) }
        val who = whoKey(by)
        val writes = mutableListOf<Pair<String, Map<String, FieldValue>>>()
        val ids = names.mapIndexed { i, name ->
            val at = nowMs + i
            val same = byKey[key(name)].orEmpty()
            same.firstOrNull { it.value.f(ShoppingFields.GOT).boolOrNull != true }?.let { return@mapIndexed it.key }
            val back = same.maxByOrNull { it.value.f(ShoppingFields.GOT_AT).longOrNull ?: 0L }
            if (back != null) {
                writes += back.key to mapOf(ShoppingFields.GOT to false.fv(), ShoppingFields.ADDED_AT to at.fv(), ShoppingFields.BY to who.fv())
                return@mapIndexed back.key
            }
            val id = newId()
            writes += id to mapOf(
                ShoppingFields.TITLE to name.fv(),
                ShoppingFields.GOT to false.fv(),
                ShoppingFields.ADDED_AT to at.fv(),
                ShoppingFields.BY to who.fv(),
            )
            id
        }
        return ShoppingAdd(ids, writes)
    }

    /** What ticking an item as bought writes; null when it isn't on the list ([fields] null or deleted) or is already got. */
    fun gotFields(fields: Map<String, FieldValue>?, nowMs: Long): Map<String, FieldValue>? {
        if (fields == null || fields.f(ShoppingFields.DELETED).boolOrNull == true) return null
        if (fields.f(ShoppingFields.GOT).boolOrNull == true) return null
        return mapOf(ShoppingFields.GOT to true.fv(), ShoppingFields.GOT_AT to nowMs.fv())
    }

    /** What putting a got item back writes; null when it isn't on the list or isn't got. */
    fun putBackFields(fields: Map<String, FieldValue>?): Map<String, FieldValue>? {
        if (fields == null || fields.f(ShoppingFields.DELETED).boolOrNull == true) return null
        if (fields.f(ShoppingFields.GOT).boolOrNull != true) return null
        return mapOf(ShoppingFields.GOT to false.fv(), ShoppingFields.GOT_AT to FieldValue.Null)
    }

    private fun day(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        return if (label == "Today" || label == "Yesterday") label.lowercase() else label
    }
}

/** The shopping list on a replica. */
class Shopping(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun items(): Map<String, Map<String, FieldValue>> =
        replica.entities(EntityTypes.SHOPPING_ITEM).associate { it.ref.entityId to it.fields }

    fun view(): ShoppingView = ShoppingRules.view(items(), nowMs(), calendar)

    /**
     * Adds each thing in [text] ([ShoppingRules.add]) for [by]. Returns the ids now to buy, in order; empty when nothing
     * was typed.
     */
    fun add(text: String, by: String = ShoppingRules.OWNER): List<String> {
        val a = ShoppingRules.add(text, items(), by, nowMs(), newId)
        a.writes.forEach { (id, fields) -> replica.commitLocal(EntityTypes.SHOPPING_ITEM, id, fields) }
        return a.ids
    }

    /** Ticks [id] as bought; false when it isn't on the list or is already got. */
    fun got(id: String): Boolean = write(id, ShoppingRules.gotFields(replica.entity(EntityTypes.SHOPPING_ITEM, id)?.fields, nowMs()))

    /** Puts a got item back to buy (unticking it, or Undo); its place in the list is where it was first added. */
    fun putBack(id: String): Boolean = write(id, ShoppingRules.putBackFields(replica.entity(EntityTypes.SHOPPING_ITEM, id)?.fields))

    /** Removes [id] from the list for good (added by mistake). */
    fun remove(id: String): Boolean {
        live(id) ?: return false
        replica.commitLocal(EntityTypes.SHOPPING_ITEM, id, mapOf(ShoppingFields.DELETED to true.fv()))
        return true
    }

    /** "Clear": every item under Got leaves the list for good. Returns how many went. */
    fun clearGot(): Int {
        val ids = view().got.map { it.id }
        ids.forEach { replica.commitLocal(EntityTypes.SHOPPING_ITEM, it, mapOf(ShoppingFields.DELETED to true.fv())) }
        return ids.size
    }

    private fun write(id: String, fields: Map<String, FieldValue>?): Boolean {
        fields ?: return false
        replica.commitLocal(EntityTypes.SHOPPING_ITEM, id, fields)
        return true
    }

    private fun live(id: String) =
        replica.entity(EntityTypes.SHOPPING_ITEM, id)?.takeIf { it[ShoppingFields.DELETED].boolOrNull != true }
}
