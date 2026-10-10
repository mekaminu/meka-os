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
    fun view(): ShoppingView {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        val live = replica.entities(EntityTypes.SHOPPING_ITEM).filter { it[ShoppingFields.DELETED].boolOrNull != true }
        val toBuy = live.filter { it[ShoppingFields.GOT].boolOrNull != true }
            .map { s ->
                val at = s[ShoppingFields.ADDED_AT].longOrNull ?: 0L
                val by = s[ShoppingFields.BY].textOrNull ?: ShoppingRules.OWNER
                ShoppingItem(s.ref.entityId, s[ShoppingFields.TITLE].textOrNull.orEmpty(), false, ShoppingRules.toBuyMeta(by, at, now, calendar), by, at)
            }
            .filter { it.title.isNotEmpty() }
            .sortedWith(compareBy<ShoppingItem> { it.atMs }.thenBy { it.id })
        val got = live.filter { it[ShoppingFields.GOT].boolOrNull == true }
            .map { s ->
                val at = s[ShoppingFields.GOT_AT].longOrNull ?: 0L
                val by = s[ShoppingFields.BY].textOrNull ?: ShoppingRules.OWNER
                ShoppingItem(s.ref.entityId, s[ShoppingFields.TITLE].textOrNull.orEmpty(), true, ShoppingRules.gotMeta(at, now, calendar), by, at)
            }
            .filter { it.title.isNotEmpty() && today - calendar.epochDayOf(it.atMs) < ShoppingRules.GOT_DAYS }
            .sortedWith(compareByDescending<ShoppingItem> { it.atMs }.thenBy { it.id })
            .take(ShoppingRules.MAX_GOT)
        return ShoppingView(toBuy, got)
    }

    /**
     * Adds each thing in [text] ([ShoppingRules.split]) for [by]. A name already to buy is left as it is; one under
     * Got (or bought longer ago) comes back to buy, keeping its row. Returns the ids now to buy, in order; empty when
     * nothing was typed.
     */
    fun add(text: String, by: String = ShoppingRules.OWNER): List<String> {
        val names = ShoppingRules.split(text)
        if (names.isEmpty()) return emptyList()
        val live = replica.entities(EntityTypes.SHOPPING_ITEM).filter { it[ShoppingFields.DELETED].boolOrNull != true }
        val byKey = live.groupBy { ShoppingRules.key(it[ShoppingFields.TITLE].textOrNull.orEmpty()) }
        val who = by.trim().lowercase().ifEmpty { ShoppingRules.OWNER }.take(40)
        return names.map { name ->
            val same = byKey[ShoppingRules.key(name)].orEmpty()
            same.firstOrNull { it[ShoppingFields.GOT].boolOrNull != true }?.let { return@map it.ref.entityId }
            val back = same.maxByOrNull { it[ShoppingFields.GOT_AT].longOrNull ?: 0L }
            if (back != null) {
                replica.commitLocal(
                    EntityTypes.SHOPPING_ITEM, back.ref.entityId,
                    mapOf(ShoppingFields.GOT to false.fv(), ShoppingFields.ADDED_AT to nowMs().fv(), ShoppingFields.BY to who.fv()),
                )
                return@map back.ref.entityId
            }
            val id = newId()
            replica.commitLocal(
                EntityTypes.SHOPPING_ITEM, id,
                mapOf(
                    ShoppingFields.TITLE to name.fv(),
                    ShoppingFields.GOT to false.fv(),
                    ShoppingFields.ADDED_AT to nowMs().fv(),
                    ShoppingFields.BY to who.fv(),
                ),
            )
            id
        }
    }

    /** Ticks [id] as bought; false when it isn't on the list or is already got. */
    fun got(id: String): Boolean {
        val s = live(id) ?: return false
        if (s[ShoppingFields.GOT].boolOrNull == true) return false
        replica.commitLocal(EntityTypes.SHOPPING_ITEM, id, mapOf(ShoppingFields.GOT to true.fv(), ShoppingFields.GOT_AT to nowMs().fv()))
        return true
    }

    /** Puts a got item back to buy (unticking it, or Undo); its place in the list is where it was first added. */
    fun putBack(id: String): Boolean {
        val s = live(id) ?: return false
        if (s[ShoppingFields.GOT].boolOrNull != true) return false
        replica.commitLocal(EntityTypes.SHOPPING_ITEM, id, mapOf(ShoppingFields.GOT to false.fv(), ShoppingFields.GOT_AT to FieldValue.Null))
        return true
    }

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

    private fun live(id: String) =
        replica.entity(EntityTypes.SHOPPING_ITEM, id)?.takeIf { it[ShoppingFields.DELETED].boolOrNull != true }
}
