package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Errands by location (V1): "when I'm near Tesco: milk, batteries" — things that wait for a place, not a time. One
 * synced entity type (ADR-008 addendum 2026-10-11): `errand`, one thing to do or get at one place, a random id.
 *
 * Slice 1 is the list itself on both apps (typed in Ask → More → Errands, grouped by place). Where a place is, and the
 * reminder when the Fold is near it, are slice 2 and stay on the Fold: a place's location is never synced and never
 * leaves the phone (the item's "on-device location only"). Plain, non-AI rules; nothing here leaves Meka's own data.
 */
object ErrandFields {
    /** The place as Meka typed it, tidied: "Tesco", "Post office". Errands at the same place share its key ([ErrandRules.placeKey]). */
    const val PLACE = "place"
    const val TITLE = "title"
    const val ADDED_AT = "addedAtMs"
    /** When Meka ticked it off; 0 while it waits. */
    const val DONE_AT = "doneAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** One errand as stored. */
data class ErrandItem(val id: String, val place: String, val title: String, val addedAtMs: Long, val doneAtMs: Long) {
    val done: Boolean get() = doneAtMs > 0
}

/** One errand as the list shows it. [meta] is null for a waiting one; "Tesco · done today" once done. */
data class ErrandRow(val id: String, val title: String, val meta: String?, val done: Boolean)

/** The errands waiting at one place, oldest first: "Tesco" · "milk, batteries". */
data class ErrandGroup(val place: String, val key: String, val rows: List<ErrandRow>) {
    /** "milk, batteries" (what a reminder near the place says). */
    val line: String get() = rows.joinToString(", ") { it.title }
}

data class ErrandView(
    /** Places with something waiting, the place waiting longest first. */
    val places: List<ErrandGroup>,
    /** Ticked off in the last [ErrandRules.DONE_DAYS] days, newest first, at most [ErrandRules.MAX_DONE]. */
    val done: List<ErrandRow>,
    /** "3 errands at 2 places", "1 errand at Tesco", or [ErrandRules.EMPTY_SUMMARY]. */
    val summary: String,
    val count: Int,
) {
    companion object { val EMPTY = ErrandView(emptyList(), emptyList(), ErrandRules.EMPTY_SUMMARY, 0) }
}

/** What one typed line read as: the place and the things to do there. */
data class ErrandParse(val place: String, val things: List<String>)

/** What adding a typed line did: the errands now waiting for it, the line to show, and whether nothing was written. */
data class ErrandAdded(val ids: List<String>, val place: String?, val line: String, val refused: Boolean)

/** Non-AI, pure. */
object ErrandRules {
    const val MAX_TITLE = 120
    const val MAX_PLACE = 40
    /** A place typed before a colon ("Tesco: milk") is at most this many words, so a sentence isn't taken for one. */
    const val MAX_PLACE_WORDS = 4
    const val DONE_DAYS = 7
    const val MAX_DONE = 10
    const val HINT = "Tesco: milk, batteries"
    const val EMPTY_SUMMARY = "No errands waiting"
    const val REFUSED = "Say where first: “Tesco: milk, batteries” or “when I'm near the post office: parcel”"
    const val NOTE = "Errands wait for a place, not a time. Type the place, a colon, then what to do there; separate several with commas."

    private val LEAD = Regex(
        "^(?:remind me\\s+(?:to\\s+)?)?(?:(?:when|whenever|next time|once|if)\\s+(?:i'm|im|i am)\\s+)?(?:near|at|by|in|passing)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex(
        "^(.+?)\\s+(?:when|whenever|next time|once|if)\\s+(?:i'm|im|i am)\\s+(?:near|at|by|in|passing)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val VERB = Regex("\\s+(?:get|buy|pick up|grab|to)\\s+", RegexOption.IGNORE_CASE)
    private val SEPARATORS = listOf(":", " - ", " – ", " — ", ",")

    private fun clean(text: String): String =
        text.replace('’', '\'').replace('‘', '\'').replace(Regex("[ \\t]+"), " ").trim()

    /** "the post office." → "Post office"; "tesco" → "Tesco". Null when nothing is left. */
    fun tidyPlace(place: String): String? {
        val p = place.trim().trimEnd('.', '!', '?', ',', ':', ';').trim()
            .replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").take(MAX_PLACE).trim()
        if (p.isEmpty()) return null
        return p.replaceFirstChar { it.uppercase() }
    }

    /** Errands at "Tesco", "tesco" and "the Tesco" are at one place. */
    fun placeKey(place: String): String = (tidyPlace(place) ?: "").lowercase()

    /** "milk, eggs and bread." → [milk, eggs and bread]; a leading "and " on the last one goes ("milk, and eggs"). */
    fun things(text: String): List<String> =
        text.split(',', ';', '\n')
            .map { it.trim().trimEnd('.', '!').replace(Regex("^and\\s+", RegexOption.IGNORE_CASE), "") }
            .map(ShoppingRules::tidy).map { it.take(MAX_TITLE) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /**
     * Reads where and what from a typed line: "Tesco: milk, batteries", "when I'm near Tesco: milk, batteries",
     * "at the post office - parcel", "near Boots get plasters", "milk when I'm at Tesco". Null when it says no place.
     */
    fun parse(text: String): ErrandParse? {
        val t = clean(text)
        if (t.isEmpty()) return null
        TRAILING.matchEntire(t)?.let { m ->
            val place = tidyPlace(m.groupValues[2]) ?: return null
            val things = things(m.groupValues[1])
            return if (things.isEmpty()) null else ErrandParse(place, things)
        }
        val lead = LEAD.matchEntire(t)
        val rest = lead?.groupValues?.get(1) ?: t
        val split = SEPARATORS.mapNotNull { s -> rest.indexOf(s).takeIf { it > 0 }?.let { it to s } }.minByOrNull { it.first }
        val (placePart, thingsPart) = when {
            split != null -> rest.substring(0, split.first) to rest.substring(split.first + split.second.length)
            lead != null -> VERB.find(rest)?.let { v -> rest.substring(0, v.range.first) to rest.substring(v.range.last + 1) } ?: return null
            else -> return null
        }
        // Without "near/at …" in front, only a short place before a colon counts ("Tesco: milk", not a sentence).
        if (lead == null && (split?.second != ":" || placePart.trim().split(' ').size > MAX_PLACE_WORDS)) return null
        val place = tidyPlace(placePart) ?: return null
        val things = things(thingsPart)
        return if (things.isEmpty()) null else ErrandParse(place, things)
    }

    /** "milk", "milk and batteries", "milk, batteries and stamps". */
    fun joined(things: List<String>): String = when (things.size) {
        0 -> ""
        1 -> things[0]
        else -> things.dropLast(1).joinToString(", ") + " and " + things.last()
    }

    fun summary(count: Int, places: List<String>): String = when {
        count == 0 -> EMPTY_SUMMARY
        places.size == 1 -> "$count ${if (count == 1) "errand" else "errands"} at ${places[0]}"
        else -> "$count errands at ${places.size} places"
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live errands from each entity's fields (id → fields); removed ones and blanks are gone. */
    fun items(raw: Map<String, Map<String, FieldValue>>): List<ErrandItem> =
        raw.filterValues { it.f(ErrandFields.DELETED).boolOrNull != true }
            .map { (id, s) ->
                ErrandItem(
                    id,
                    s.f(ErrandFields.PLACE).textOrNull.orEmpty(),
                    s.f(ErrandFields.TITLE).textOrNull.orEmpty(),
                    s.f(ErrandFields.ADDED_AT).longOrNull ?: 0L,
                    s.f(ErrandFields.DONE_AT).longOrNull ?: 0L,
                )
            }
            .filter { it.title.isNotEmpty() && placeKey(it.place).isNotEmpty() }

    private fun day(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        return if (label == "Today" || label == "Yesterday") label.lowercase() else label
    }

    fun view(items: List<ErrandItem>, nowMs: Long, cal: LocalCalendar): ErrandView {
        val waiting = items.filter { !it.done }.sortedWith(compareBy<ErrandItem> { it.addedAtMs }.thenBy { it.id })
        val groups = waiting.groupBy { placeKey(it.place) }.map { (key, list) ->
            ErrandGroup(tidyPlace(list.first().place) ?: list.first().place, key, list.map { ErrandRow(it.id, it.title, null, false) })
        }
        val today = cal.epochDayOf(nowMs)
        val done = items.filter { it.done && today - cal.epochDayOf(it.doneAtMs) < DONE_DAYS }
            .sortedWith(compareByDescending<ErrandItem> { it.doneAtMs }.thenBy { it.id })
            .take(MAX_DONE)
            .map { ErrandRow(it.id, it.title, "${tidyPlace(it.place) ?: it.place} · done ${day(it.doneAtMs, nowMs, cal)}", true) }
        return ErrandView(groups, done, summary(waiting.size, groups.map { it.place }), waiting.size)
    }

    /**
     * What adding [text] writes: a thing already waiting at that place stays one errand; one ticked off there comes
     * back; anything else is a new errand from [newId]. Typed order is kept (each a millisecond after the one before).
     */
    fun add(
        text: String,
        items: List<ErrandItem>,
        nowMs: Long,
        newId: () -> String,
    ): Pair<ErrandAdded, List<Pair<String, Map<String, FieldValue>>>> {
        val p = parse(text) ?: return ErrandAdded(emptyList(), null, REFUSED, refused = true) to emptyList()
        val key = placeKey(p.place)
        val here = items.filter { placeKey(it.place) == key }.groupBy { it.title.lowercase() }
        val writes = mutableListOf<Pair<String, Map<String, FieldValue>>>()
        val fresh = mutableListOf<String>()
        val ids = p.things.mapIndexed { i, thing ->
            val same = here[thing.lowercase()].orEmpty()
            same.firstOrNull { !it.done }?.let { return@mapIndexed it.id }
            fresh += thing
            val back = same.maxByOrNull { it.doneAtMs }
            if (back != null) {
                writes += back.id to mapOf(ErrandFields.DONE_AT to 0L.fv(), ErrandFields.ADDED_AT to (nowMs + i).fv())
                return@mapIndexed back.id
            }
            val id = newId()
            writes += id to mapOf(
                ErrandFields.PLACE to p.place.fv(),
                ErrandFields.TITLE to thing.fv(),
                ErrandFields.ADDED_AT to (nowMs + i).fv(),
                ErrandFields.DONE_AT to 0L.fv(),
            )
            id
        }
        val line = if (fresh.isEmpty()) "Already waiting at ${p.place}: ${joined(p.things)}" else "At ${p.place}: ${joined(fresh)}"
        return ErrandAdded(ids, p.place, line, refused = false) to writes
    }
}

/** The errands on a replica. */
class Errands(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<ErrandItem> =
        ErrandRules.items(replica.entities(EntityTypes.ERRAND).associate { it.ref.entityId to it.fields })

    fun view(): ErrandView = ErrandRules.view(items(), nowMs(), calendar)

    /** Adds what [text] says ([ErrandRules.add]); refused (nothing written) when it names no place. */
    fun add(text: String): ErrandAdded {
        val (added, writes) = ErrandRules.add(text, items(), nowMs(), newId)
        writes.forEach { (id, fields) -> replica.commitLocal(EntityTypes.ERRAND, id, fields) }
        return added
    }

    /** Ticks an errand off ([done] true) or puts it back; false when it isn't on the list or already so. */
    fun setDone(id: String, done: Boolean): Boolean {
        val item = items().firstOrNull { it.id == id } ?: return false
        if (item.done == done) return false
        replica.commitLocal(EntityTypes.ERRAND, id, mapOf(ErrandFields.DONE_AT to (if (done) nowMs() else 0L).fv()))
        return true
    }

    /** Takes an errand off the list for good (both apps). */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.ERRAND, id)?.takeIf { it[ErrandFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.ERRAND, id, mapOf(ErrandFields.DELETED to true.fv()))
        return true
    }
}
