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

/**
 * What adding a typed line did: the errands now waiting for it, the line to show, and whether nothing was written.
 * [created] are new errands and [revived] ones brought back from Done (what an Ask card's Undo takes back).
 */
data class ErrandAdded(
    val ids: List<String>,
    val place: String?,
    val line: String,
    val refused: Boolean,
    val created: List<String> = emptyList(),
    val revived: List<String> = emptyList(),
)

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
        return addParsed(p, items, nowMs, newId)
    }

    /** [add] for a place and things already read (an Ask card's, [ErrandAskRules.proposal]). */
    fun addParsed(
        p: ErrandParse,
        items: List<ErrandItem>,
        nowMs: Long,
        newId: () -> String,
    ): Pair<ErrandAdded, List<Pair<String, Map<String, FieldValue>>>> {
        val key = placeKey(p.place)
        val here = items.filter { placeKey(it.place) == key }.groupBy { it.title.lowercase() }
        val writes = mutableListOf<Pair<String, Map<String, FieldValue>>>()
        val fresh = mutableListOf<String>()
        val created = mutableListOf<String>()
        val revived = mutableListOf<String>()
        val ids = p.things.mapIndexed { i, thing ->
            val same = here[thing.lowercase()].orEmpty()
            same.firstOrNull { !it.done }?.let { return@mapIndexed it.id }
            fresh += thing
            val back = same.maxByOrNull { it.doneAtMs }
            if (back != null) {
                writes += back.id to mapOf(ErrandFields.DONE_AT to 0L.fv(), ErrandFields.ADDED_AT to (nowMs + i).fv())
                revived += back.id
                return@mapIndexed back.id
            }
            val id = newId()
            writes += id to mapOf(
                ErrandFields.PLACE to p.place.fv(),
                ErrandFields.TITLE to thing.fv(),
                ErrandFields.ADDED_AT to (nowMs + i).fv(),
                ErrandFields.DONE_AT to 0L.fv(),
            )
            created += id
            id
        }
        val line = if (fresh.isEmpty()) "Already waiting at ${p.place}: ${joined(p.things)}" else "At ${p.place}: ${joined(fresh)}"
        return ErrandAdded(ids, p.place, line, refused = false, created = created, revived = revived) to writes
    }
}

/**
 * Errands in Ask and Talk (errands by location, slice 2b): "what do I need at Tesco?" is answered from the list, and
 * "add batteries to Tesco" is a card that adds on Meka's tap. Non-AI, pure. Only the list goes with a question (places
 * and things, as Meka typed them); where a place is never leaves the Fold.
 */
object ErrandAskRules {
    /** At most this many places go with a question (the place waiting longest first). */
    const val ASK_PLACES = 8
    /** Things one card adds at most. */
    const val MAX_THINGS = 10
    const val ALREADY = "Those are already waiting there"

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    /**
     * The list as MEKA describes it to the model (Ask's `errands` lines): "Errands · 3 errands at 2 places", then each
     * place "At Tesco · milk, batteries". Nothing at all while nothing waits.
     */
    fun askLines(view: ErrandView): List<String> {
        if (view.places.isEmpty()) return emptyList()
        val more = if (view.places.size > ASK_PLACES) " · the first $ASK_PLACES places below" else ""
        return listOf("Errands · ${view.summary}$more") +
            view.places.take(ASK_PLACES).map { g -> "At ${tidy(g.place)} · ${g.rows.joinToString(", ") { tidy(it.title) }}" }
    }

    /**
     * A model's `add_errand` ("Tesco: batteries", read like a line typed in Errands) as a card: the place as the list
     * already spells it, at most [MAX_THINGS] things, only those not already waiting there. Null when it names no place
     * or nothing new.
     */
    fun proposal(items: List<ErrandItem>, title: String?): AskProposal.AddErrand? {
        val p = ErrandRules.parse(title ?: return null) ?: return null
        val key = ErrandRules.placeKey(p.place)
        val here = items.filter { ErrandRules.placeKey(it.place) == key }
        val place = here.minByOrNull { it.addedAtMs }?.let { ErrandRules.tidyPlace(it.place) } ?: p.place
        val waiting = here.filter { !it.done }.map { it.title.lowercase() }.toSet()
        val things = p.things.filter { it.lowercase() !in waiting }.take(MAX_THINGS)
        return if (things.isEmpty()) null else AskProposal.AddErrand(place, things)
    }

    /** The card's words: "Errand at Tesco · milk and batteries". */
    fun cardLine(p: AskProposal.AddErrand): String = "Errand at ${p.place} · ${ErrandRules.joined(p.things)}"

    /** The undo bar's line: "At Tesco: milk and batteries" (as the Errands pane says it). */
    fun doneLine(p: AskProposal.AddErrand): String = "At ${p.place}: ${ErrandRules.joined(p.things)}"
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

    /** Adds [things] at [place] (an Ask card's); refused when nothing new would be written. */
    fun addThings(place: String, things: List<String>): ErrandAdded {
        val (added, writes) = ErrandRules.addParsed(ErrandParse(place, things), items(), nowMs(), newId)
        writes.forEach { (id, fields) -> replica.commitLocal(EntityTypes.ERRAND, id, fields) }
        return added
    }

    /**
     * Takes back an Ask card's add: [added] errands still waiting leave the list, [revived] ones still waiting go back
     * under Done. One ticked off or removed since is left as it is. True when anything changed.
     */
    fun takeBack(added: List<String>, revived: List<String>): Boolean {
        val waiting = items().filter { !it.done }.map { it.id }.toSet()
        var any = false
        added.forEach { if (it in waiting && remove(it)) any = true }
        revived.forEach { if (it in waiting && setDone(it, true)) any = true }
        return any
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

/**
 * Where an errand's place is, saved on the Fold only (errands by location, slice 2): one precise fix taken when Meka
 * taps "I'm here" in a place's section. Never synced and never sent anywhere (the item's "on-device location only");
 * the phone keeps it in its own storage and hands it to Android's proximity alert.
 */
data class SavedPlace(val key: String, val latitude: Double, val longitude: Double, val savedAtMs: Long)

/** A place the Fold asks Android to watch: its saved spot and the name the reminder says. */
data class ArmedPlace(val key: String, val place: String, val latitude: Double, val longitude: Double)

/** "Near Tesco" · "milk, batteries", with the errands Done ticks off. */
data class ErrandNearNotice(val key: String, val place: String, val title: String, val text: String, val ids: List<String>)

/** The near-me reminders switch in Errands: its line, its button, and whether the line needs a look. */
data class ErrandNearSetting(val statusLine: String, val actionLabel: String, val lit: Boolean, val privacy: String)

/** What saving a place did: the places now saved and the line the pane shows. */
data class PlaceSaved(val places: Map<String, SavedPlace>, val line: String, val saved: Boolean)

/** Non-AI, pure. The phone does the locating; these say what to save, what to watch and what to say. */
object ErrandPlaceRules {
    /** How close counts as "near": about a supermarket's car park. */
    const val RADIUS_M = 150.0
    /** A fix less certain than this isn't saved (indoors with no GPS, say): try again outside. */
    const val MAX_ACCURACY_M = 100.0
    /** At most this many places are watched at once (the ones with errands waiting longest). */
    const val MAX_ARMED = 20
    /** The same place reminds again only after this long (walking along its edge doesn't ring twice). */
    const val REMIND_AGAIN_MS = 3 * 60 * 60_000L
    /** How long the "Done at Tesco · Undo" note stays in the shade. */
    const val DONE_NOTE_MS = 10 * 60_000L

    const val CHANNEL_NAME = "Errands nearby"
    const val CHANNEL_ABOUT = "When you're near a place you saved with errands waiting there."
    const val PRIVACY = "Each place's spot stays on this phone; nothing about where you are is synced or sent. Android watches the saved spots itself, even with MEKA closed."
    const val SAVING = "Finding where you are…"
    const val NO_FIX = "Couldn't find where you are · try again outside"
    const val NO_PERMISSION = "Needs precise location · turn on near-me reminders first"

    fun encode(places: Collection<SavedPlace>): String =
        places.sortedBy { it.key }.joinToString("\n") { p ->
            listOf(p.key.replace('\t', ' ').replace('\n', ' '), p.latitude, p.longitude, p.savedAtMs).joinToString("\t")
        }

    fun decode(text: String?): Map<String, SavedPlace> =
        text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 4 || f[0].isBlank()) return@mapNotNull null
            val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
            if (!valid(lat, lon)) return@mapNotNull null
            SavedPlace(f[0], lat, lon, f[3].toLongOrNull() ?: 0L)
        }.associateBy { it.key }

    fun valid(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

    /**
     * Saves where [place] is from one fix (replacing what was saved for it). Refused when there was no fix, or it is
     * less certain than [MAX_ACCURACY_M].
     */
    fun save(
        saved: Map<String, SavedPlace>,
        place: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Double?,
        nowMs: Long,
    ): PlaceSaved {
        val key = ErrandRules.placeKey(place)
        val name = ErrandRules.tidyPlace(place) ?: place
        if (key.isEmpty() || latitude == null || longitude == null || !valid(latitude, longitude)) return PlaceSaved(saved, NO_FIX, false)
        if (accuracyM == null || accuracyM > MAX_ACCURACY_M) return PlaceSaved(saved, "Not close enough to save · try again outside", false)
        return PlaceSaved(saved + (key to SavedPlace(key, latitude, longitude, nowMs)), "Saved $name here · reminds you within ${RADIUS_M.toInt()} m", true)
    }

    /** The places Android should watch: saved, with something waiting there, the place waiting longest first. */
    fun toArm(view: ErrandView, saved: Map<String, SavedPlace>): List<ArmedPlace> =
        view.places.mapNotNull { g -> saved[g.key]?.let { ArmedPlace(g.key, g.place, it.latitude, it.longitude) } }.take(MAX_ARMED)

    /** What to say on arriving near [key]; null when nothing waits there any more. */
    fun near(view: ErrandView, key: String): ErrandNearNotice? {
        val g = view.places.firstOrNull { it.key == key } ?: return null
        if (g.rows.isEmpty()) return null
        return ErrandNearNotice(g.key, g.place, "Near ${g.place}", g.line, g.rows.map { it.id })
    }

    /** Whether to remind about a place last reminded at [lastMs] (null: never). */
    fun shouldRemind(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs !in 0 until REMIND_AGAIN_MS

    /** "Done at Tesco · milk and batteries". */
    fun doneLine(place: String, titles: List<String>): String = "Done at $place · ${ErrandRules.joined(titles)}"

    /** Under a place's label in Errands: whether the Fold knows where it is. */
    fun placeLine(saved: Boolean): String =
        if (saved) "Spot saved · reminds you within ${RADIUS_M.toInt()} m" else "No spot saved · tap I'm here when you're there"

    /** The button beside [placeLine]. */
    fun placeAction(saved: Boolean): String = if (saved) "Forget spot" else "I'm here"

    /** The switch: off by default; on needs precise location, then "Allow all the time" so Android can watch with MEKA closed. */
    fun setting(on: Boolean, precise: Boolean, always: Boolean, savedCount: Int): ErrandNearSetting = when {
        !on -> ErrandNearSetting("Off · errands wait in the list", "Turn on", false, PRIVACY)
        !precise -> ErrandNearSetting("Needs precise location · tap to allow", "Turn off", true, PRIVACY)
        !always -> ErrandNearSetting("Needs location “Allow all the time” · tap to allow", "Turn off", true, PRIVACY)
        savedCount == 0 -> ErrandNearSetting("On · save a place's spot with I'm here", "Turn off", false, PRIVACY)
        else -> ErrandNearSetting("On · watching ${savedCount} ${if (savedCount == 1) "place" else "places"}", "Turn off", false, PRIVACY)
    }

    /** When each place last reminded, kept on the phone: "tesco\t1700000000000" lines. */
    fun encodeReminded(map: Map<String, Long>): String = map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decodeReminded(text: String?): Map<String, Long> =
        text.orEmpty().lines().mapNotNull { l ->
            val f = l.split('\t')
            if (f.size != 2 || f[0].isBlank()) null else f[1].toLongOrNull()?.let { f[0] to it }
        }.toMap()
}
