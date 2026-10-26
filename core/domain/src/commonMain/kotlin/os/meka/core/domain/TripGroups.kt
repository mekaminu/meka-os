package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * Trip lists editing (Take first, Meka 2026-10-26 18:04, on his trip's To pack: "doesn't allow me to move an item from
 * one group header to another, and can't rename a header to my liking"), slice 1: **the groups are his**. An item can be
 * moved to any group (its sheet's Group chips, or "New group…"); a group can be renamed, added, deleted (its things move
 * to Other, with Undo) and moved up or down; the item's sheet also changes its name, quantity and whose it is. Smart
 * add matches the words against the trip's own groups first (a "Kitchen" group catches "Blender"), then the built-in
 * map; when nothing matches the app offers the groups as chips for a few seconds.
 *
 * Stored (ADR-008 addendum 2026-10-26, slice 4a): an item's `group` is now any name he gives (at most [MAX_NAME]); the
 * trip keeps each list's group order in one LWW field per list ([TripFields.GROUPS_BUY] …, names joined by a newline),
 * written the first time he renames, adds, deletes or moves a group, and a template saved from the trip keeps the same
 * fields. Plain rules; nothing leaves Meka's synced data.
 */
object TripGroupRules {
    /** A group's name is at most this long. */
    const val MAX_NAME = 24
    /** At most this many groups on one list. */
    const val MAX_GROUPS = 16
    const val NEW_GROUP = "New group…"
    const val ADD_GROUP = "+ Group"
    const val ADD_GROUP_HINT = "Name a new group…"
    const val RENAME = "Rename"
    const val RENAME_HINT = "A new name for this group…"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val DELETE = "Delete group"
    const val EMPTY = "Empty"
    const val NOT_READ = "Type a name for the group."
    const val OTHER_FIXED = "Other keeps anything without a group."
    const val GROUP_LABEL = "GROUP"
    const val NAME_LABEL = "NAME"
    const val QTY_LABEL = "HOW MANY"
    const val WHO_LABEL = "WHOSE"
    const val REMOVE = "Remove"
    const val PUT_IN = "Put it in:"
    /** How long the "Put it in:" chips stay under the field after an add that matched no group. */
    const val ASK_MS = 5_000L

    /** The trip field holding [list]'s group order. */
    fun field(list: TripList): String = when (list) {
        TripList.BUY -> TripFields.GROUPS_BUY
        TripList.PACK -> TripFields.GROUPS_PACK
        TripList.LEAVE -> TripFields.GROUPS_LEAVE
    }

    /**
     * A typed group name tidied: one line, spaces collapsed, at most [MAX_NAME], first letter capital; "Added" (the old
     * name) and "other" read as [TripListRules.ADDED]. Null when nothing is left.
     */
    fun tidy(typed: String?): String? {
        val t = typed?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim(' ', ',', '.', ':', '-')?.take(MAX_NAME)?.trim() ?: return null
        if (t.none { it.isLetterOrDigit() }) return null
        if (t.equals(TripListRules.LEGACY_ADDED, ignoreCase = true) || t.equals(TripListRules.ADDED, ignoreCase = true)) return TripListRules.ADDED
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** [list]'s stored order, from the trip's fields: names in order, [TripListRules.ADDED] left out (it is always last). */
    fun readOrder(fields: Map<String, FieldValue>): Map<TripList, List<String>> = TripList.entries.mapNotNull { l ->
        val raw = fields[field(l)]?.textOrNull ?: return@mapNotNull null
        l to raw.split('\n').mapNotNull(::tidy).filter { it != TripListRules.ADDED }.distinctBy { it.lowercase() }
    }.toMap()

    fun writeOrder(names: List<String>): String = names.filter { it != TripListRules.ADDED }.distinctBy { it.lowercase() }.take(MAX_GROUPS).joinToString("\n")

    /**
     * The groups [list] shows, in order: his stored order first (every one, empty or not), then the built-in groups
     * that have things, then any other names on items (by when first added), and [TripListRules.ADDED] ("Other") last
     * when it has things. With no stored order, the built-in order.
     */
    fun order(list: TripList, items: List<TripListItem>, stored: List<String>?): List<String> {
        val present = items.filter { it.list == list }.sortedBy { it.addedAtMs }.map { it.group }.distinct()
        val builtIn = TripListRules.GROUPS.getValue(list).filter { it != TripListRules.ADDED }
        val out = mutableListOf<String>()
        fun add(n: String) { if (n != TripListRules.ADDED && out.none { it.equals(n, ignoreCase = true) }) out += n }
        // A built-in group in his order shows only while it has things; one he made or renamed shows empty too.
        stored.orEmpty().filter { n -> builtIn.none { it.equals(n, ignoreCase = true) } || present.any { it.equals(n, ignoreCase = true) } }.forEach(::add)
        builtIn.filter { b -> present.any { it.equals(b, ignoreCase = true) } }.forEach(::add)
        present.forEach(::add)
        if (TripListRules.ADDED in present) out += TripListRules.ADDED
        return out
    }

    /** Words in a group's name that place a thing in it ("Kitchen stuff" → kitchen, stuff), three letters or more. */
    private fun nameWords(name: String): Set<String> =
        name.lowercase().split(' ', '/', ',', '&', '-').map { w -> w.trim { !it.isLetterOrDigit() } }.filter { it.length >= 3 }.toSet()

    /** A few more words for groups he might make: "Kitchen" catches a blender, "Beach" a bucket and spade. */
    private val EXTRA: Map<String, Set<String>> = mapOf(
        "kitchen" to setOf("blender", "kettle", "pan", "pans", "knife", "knives", "cutlery", "plates", "plate", "cups", "mugs", "mug",
            "toaster", "utensils", "tupperware", "lunchbox", "lunchboxes", "flask", "spices", "tea", "coffee", "foil", "corkscrew", "opener"),
        "beach" to setOf("bucket", "spade", "spades", "snorkel", "snorkels", "goggles", "inflatable", "lilo", "windbreak", "beach",
            "swimwear", "swimsuit", "trunks", "flip-flops", "sunglasses", "towel", "towels"),
        "baby" to setOf("nappies", "nappy", "wipes", "bottles", "bottle", "dummy", "buggy", "pushchair", "formula", "muslin", "muslins", "sling"),
        "food" to setOf("snacks", "snack", "crisps", "biscuits", "fruit", "sandwiches", "sweets", "nuts", "bread"),
        "snacks" to setOf("snacks", "snack", "crisps", "biscuits", "fruit", "sweets", "nuts", "bars"),
        "sport" to setOf("ball", "balls", "racket", "rackets", "boots", "shin", "pads", "kit", "trainers", "goggles"),
        "games" to setOf("cards", "games", "game", "puzzle", "puzzles", "switch", "lego", "toys", "toy"),
        "gifts" to setOf("gift", "gifts", "present", "presents", "card"),
        "car" to setOf("car", "sat-nav", "satnav", "jump", "leads", "screenwash", "petrol", "charger"),
        "shoes" to setOf("shoes", "trainers", "sandals", "boots", "flip-flops", "slippers"),
        "electronics" to setOf("charger", "chargers", "phone", "laptop", "tablet", "ipad", "headphones", "earbuds", "cable", "cables", "adapter", "adapters", "camera", "kindle"),
        "bathroom" to setOf("toothbrush", "toothbrushes", "toothpaste", "shampoo", "conditioner", "razor", "deodorant", "towel", "towels"),
        "ski" to setOf("gloves", "goggles", "helmet", "thermals", "salopettes", "ski", "skis", "boots"),
    )

    /**
     * The group of [stored] (his own groups on this list, built-in ones included) that [text] belongs in, or null: a
     * word of the group's name in the text ("kitchen roll" → Kitchen), else the [EXTRA] words for a group so named.
     */
    fun matchOwn(text: String, stored: List<String>): String? {
        if (stored.isEmpty()) return null
        val ws = text.lowercase().split(' ', '/', ',').map { w -> w.trim { !it.isLetterOrDigit() && it != '-' } }.filter { it.isNotEmpty() }.toSet()
        for (g in stored) if (g != TripListRules.ADDED && nameWords(g).any { it in ws || it.removeSuffix("s") in ws }) return g
        for (g in stored) {
            if (g == TripListRules.ADDED) continue
            val keys = nameWords(g).flatMap { EXTRA[it].orEmpty() + EXTRA[it.removeSuffix("s")].orEmpty() }.toSet()
            if (keys.isNotEmpty() && ws.any { it in keys }) return g
        }
        return null
    }

    // ---- Lines ----

    fun movedLine(text: String, group: String): String = "Moved $text to $group"
    fun renamedLine(old: String, new: String): String = "Renamed $old to $new"
    fun mergedLine(old: String, into: String): String = "Moved everything in $old to $into"
    fun addedLine(name: String): String = "Added the group $name"
    fun deletedLine(name: String, n: Int): String =
        "Deleted $name" + if (n > 0) " · ${TripListRules.things(n)} moved to ${TripListRules.ADDED}" else ""
    fun exists(name: String): String = "There's a group called $name already."
    fun tooMany(): String = "A list can have $MAX_GROUPS groups at most."
    fun savedLine(text: String): String = "Saved $text"

    // ---- Slice 4b: drag ----

    /** How far (dp) a held thing must move before it is a drag rather than a press that opens its sheet. */
    const val DRAG_SLOP_DP = 12f
    /** Within this far (dp) of the list's top or foot a drag scrolls the list. */
    const val EDGE_DP = 64f
    const val DRAG_HINT = "Press and hold to drag it to another group"

    /**
     * The group a dragged thing is over: of the group heads' tops ([tops], in screen order, any units), the last one
     * at or above [y], so the whole stretch from a head down to the next is that group; above the first head, the
     * first. Null when there are no heads.
     */
    fun groupAt(tops: List<Pair<String, Float>>, y: Float): String? {
        if (tops.isEmpty()) return null
        val sorted = tops.sortedBy { it.second }
        return sorted.lastOrNull { it.second <= y }?.first ?: sorted.first().first
    }

    /**
     * Where a dragged group head lands: [names] (his groups, Other left out) with [dragged] moved to sit among the
     * others by [y] against their heads' middles ([middles]). Names without a middle keep their order.
     */
    fun reorder(names: List<String>, dragged: String, middles: Map<String, Float>, y: Float): List<String> {
        if (dragged !in names) return names
        val rest = names.filter { it != dragged }
        val at = rest.count { n -> (middles[n] ?: Float.NEGATIVE_INFINITY) < y }
        return rest.toMutableList().also { it.add(at.coerceIn(0, it.size), dragged) }
    }

    /** The Mac's drop of the head [dragged] on the head [target]: it takes [target]'s place (the rest shift). */
    fun dropOn(names: List<String>, dragged: String, target: String): List<String> {
        if (dragged == target || dragged !in names || target !in names) return names
        val from = names.indexOf(dragged)
        val to = names.indexOf(target)
        return names.toMutableList().also { val x = it.removeAt(from); it.add(to, x) }
    }

    // ---- Undo of a deleted group: "list|name|index|id,id" ----

    fun deleteToken(list: TripList, name: String, index: Int, ids: List<String>): String =
        listOf(list.wire, name.replace('|', ' '), index.toString(), ids.joinToString(",")).joinToString("|")

    data class Deleted(val list: TripList, val name: String, val index: Int, val ids: List<String>)

    fun readDeleteToken(token: String): Deleted? {
        val p = token.split('|')
        if (p.size != 4) return null
        val name = tidy(p[1]) ?: return null
        return Deleted(TripList.of(p[0]), name, p[2].toIntOrNull() ?: return null, p[3].split(',').filter { it.isNotBlank() })
    }

    /**
     * What the apps draw in the same frame as a tap, before the core's write comes back: [moves] (item id → group),
     * [renames] (old name → new), [deleted] group names (their rows go to Other) and a new [order] of names. The groups
     * stay [TripListGroup]s so the screens draw them the same way.
     */
    fun preview(
        groups: List<TripListGroup>, moves: Map<String, String> = emptyMap(), renames: Map<String, String> = emptyMap(),
        deleted: Set<String> = emptySet(), order: List<String>? = null, added: List<String> = emptyList(), listWire: String = "",
    ): List<TripListGroup> {
        if (moves.isEmpty() && renames.isEmpty() && deleted.isEmpty() && order == null && added.isEmpty()) return groups
        // Rows by their (renamed) group, after moves and deletions.
        val named = groups.map { g -> (renames[g.name] ?: g.name) to g }
        val names = named.map { it.first }.filter { it !in deleted }.toMutableList()
        added.forEach { a -> if (names.none { it.equals(a, ignoreCase = true) }) names.add(names.indexOf(TripListRules.ADDED).takeIf { it >= 0 } ?: names.size, a) }
        val rows = linkedMapOf<String, MutableList<TripListRow>>()
        names.forEach { rows[it] = mutableListOf() }
        for ((name, g) in named) for (r in g.rows) {
            val target = moves[r.id] ?: if (name in deleted) TripListRules.ADDED else name
            rows.getOrPut(target) { mutableListOf() } += r
        }
        // Other last; a moved-into group not shown yet goes before it.
        val other = rows.remove(TripListRules.ADDED)
        if (other != null && other.isNotEmpty()) rows[TripListRules.ADDED] = other
        val keys = rows.keys.toList()
        val ordered = if (order == null) keys else order.filter { it in rows } + keys.filter { it !in order }
        val emptyOk = groups.filter { it.rows.isEmpty() }.map { renames[it.name] ?: it.name }.toSet() + added
        return ordered.mapNotNull { n ->
            val rs = rows.getValue(n)
            if (rs.isEmpty() && n !in emptyOk) return@mapNotNull null
            val keyPrefix = groups.firstOrNull()?.key?.substringBefore(':') ?: listWire
            TripListRules.group("$keyPrefix:$n", n, rs.filter { !it.done } + rs.filter { it.done })
        }
    }
}
