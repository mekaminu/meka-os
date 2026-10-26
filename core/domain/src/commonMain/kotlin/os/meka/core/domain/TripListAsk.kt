package os.meka.core.domain

/**
 * Trip lists, slice 2c (Meka 2026-10-26: "voice capture and Ask ('add sun cream to my trip list') do the same"): the
 * plain, non-AI rules for the trips' lists in Ask, Talk and Live. A question carries `trips` lines for each trip on now
 * or coming with its lists (how much is bought, packed and done, and what is still to buy, pack or do), and the model
 * may propose `add_trip_item` ({"title": "Lisbon: buy sun cream, pack 2 chargers"}), read here into a card that does
 * nothing until Meka taps it (or says yes in Talk). Each thing is read exactly as the trip's own add field reads it
 * ([TripListRules.read]), so "buy" goes on To buy (and the shopping list), "pack 2 chargers" on To pack "× 2".
 */
object TripListAskRules {
    /** Trips whose lists go with a question (on now first, then soonest). */
    const val ASK_TRIPS = 2
    /** Things still to do named per list. */
    const val ASK_ITEMS = 12
    /** At most this many lines in all. */
    const val ASK_LINES = 1 + ASK_TRIPS * 4
    /** Things one card adds at most. */
    const val MAX_ITEMS = 10
    const val ALREADY = "Those are already on the trip's lists"
    const val GONE = TripListRules.GONE

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    /** The trips that can be asked about: on now or coming, soonest first. */
    fun live(trips: List<TripItem>, today: Long): List<TripItem> =
        trips.filter { it.endDay >= today }.sortedWith(compareBy<TripItem> { it.startDay }.thenBy { it.addedAtMs })

    private fun itemName(i: TripListItem): String = tidy(i.text) + (TripListRules.qtyLabel(i.qty).takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")

    /**
     * The trips' lists as MEKA describes them to the model: "Trip lists · Lisbon trip · Wed 28 Oct · bought 1 of 3 ·
     * packed 2 of 9 · 0 of 2 jobs done", then for each list with something left "Lisbon trip · To buy · 2 left: Sun
     * cream, Plasters" (a list all done says "all 3 done"). A trip with nothing on its lists says "nothing on its lists
     * yet". Nothing at all while no trip is on or coming.
     */
    fun askLines(trips: List<TripItem>, items: List<TripListItem>, today: Long): List<String> {
        val out = mutableListOf<String>()
        for (t in live(trips, today).take(ASK_TRIPS)) {
            val mine = items.filter { it.tripId == t.id }
            val label = TripListRules.tripLabel(t)
            val head = "Trip lists · $label · ${CalendarAgenda.spanLabel(t.startDay, t.endDay)}"
            if (mine.isEmpty()) { out += "$head · nothing on its lists yet"; continue }
            val v = TripListRules.view(t, items, today)
            out += "$head · ${v.summary.replaceFirstChar { it.lowercase() }}"
            for (list in TripList.entries) {
                val on = mine.filter { it.list == list }
                if (on.isEmpty()) continue
                val left = on.filter { !it.done }.sortedBy { it.addedAtMs }
                out += if (left.isEmpty()) "$label · ${list.label} · all ${on.size} done"
                else "$label · ${list.label} · ${left.size} left: " + left.take(ASK_ITEMS).joinToString(", ", transform = ::itemName) +
                    (if (left.size > ASK_ITEMS) ", and ${left.size - ASK_ITEMS} more" else "")
            }
        }
        return out.take(ASK_LINES)
    }

    /**
     * The trip [name] means: one on now or coming whose name is it ("Lisbon", "the Lisbon trip"), else the only one
     * whose name holds it as whole words. Null when none or more than one could be meant.
     */
    fun find(trips: List<TripItem>, name: String, today: Long): TripItem? {
        val k = key(name).takeIf { it.isNotEmpty() } ?: return null
        val open = live(trips, today)
        open.filter { key(it.name) == k }.let { if (it.size == 1) return it[0] else if (it.size > 1) return null }
        val words = Regex("""(^|\W)${Regex.escape(k)}($|\W)""")
        return open.filter { words.containsMatchIn(key(it.name)) }.singleOrNull()
    }

    /** How a trip's name is matched: lower case, spaces tidied, "the"/"my"/"our" and a trailing "trip"/"trip list" off. */
    fun key(s: String): String = tidy(s).lowercase()
        .removePrefix("the ").removePrefix("my ").removePrefix("our ")
        .removeSuffix(" lists").removeSuffix(" list").removeSuffix(" trip").removeSuffix(" holiday")
        .trim()

    /**
     * A model's `add_trip_item` as a card. [title] is "Lisbon: buy sun cream, pack 2 chargers" (the trip's name, a colon,
     * then the things, each read as the trip's add field reads a line; one with no buy/pack/before-we-leave word goes
     * on the list of the thing before it, the first on To pack) or the things alone, for the next trip (on now, else
     * the soonest coming). At most [MAX_ITEMS], none already waiting on the same list. Null when no trip is meant
     * without guessing, or nothing is new.
     */
    fun proposal(trips: List<TripItem>, items: List<TripListItem>, title: String?, today: Long): AskProposal.AddTripItems? {
        val raw = title?.let(::tidy)?.takeIf { it.isNotEmpty() && it.length <= 400 } ?: return null
        val colon = raw.indexOf(':')
        // "Lisbon: …" names the trip; "before we leave: …" is a list word, not a trip; any other name isn't guessed at.
        val lead = if (colon > 0) raw.substring(0, colon) else null
        val named = lead?.let { find(trips, it, today) }
        if (lead != null && named == null && !listWord(lead)) return null
        val trip = named ?: live(trips, today).firstOrNull() ?: return null
        val body = if (named != null) raw.substring(colon + 1) else raw
        val waiting = items.filter { it.tripId == trip.id && !it.done }.map { it.list to it.text.lowercase() }.toSet()
        var open = TripList.PACK
        val entries = mutableListOf<TripListEntry>()
        for (part in body.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bare = part.removePrefix("and ").trim()
            val e = TripListRules.read(bare, open) ?: continue
            open = e.list
            if ((e.list to e.text.lowercase()) in waiting) continue
            if (entries.any { it.list == e.list && it.text.equals(e.text, ignoreCase = true) }) continue
            entries += e
            if (entries.size == MAX_ITEMS) break
        }
        return if (entries.isEmpty()) null else AskProposal.AddTripItems(trip.id, TripListRules.tripLabel(trip), entries)
    }

    /** A colon's lead that is a list's word ("before we leave", "to do", "buy"), not a trip's name. */
    private fun listWord(lead: String): Boolean {
        val k = tidy(lead).lowercase()
        return LIST_WORDS.any { k == it || k.startsWith("$it ") }
    }

    private val LIST_WORDS = listOf("before we leave", "before leaving", "to do", "todo", "remember to", "buy", "get", "pick up", "pack", "bring", "need to buy")

    /** "Sun cream", "2 chargers" (a quantity reads first, as said). */
    fun spoken(e: TripListEntry): String = (if ((e.qty ?: 1) > 1) "${e.qty} ${e.text.replaceFirstChar { it.lowercaseChar() }}" else e.text.replaceFirstChar { it.lowercaseChar() })

    /** "sun cream and 2 chargers". */
    fun names(entries: List<TripListEntry>): String = AskRules.shoppingNames(entries.map(::spoken))

    /** The card's words: "Add to Lisbon trip · To buy: sun cream · To pack: 2 chargers". */
    fun cardLine(p: AskProposal.AddTripItems): String =
        "Add to ${p.tripLabel} · " + TripList.entries.mapNotNull { l ->
            p.entries.filter { it.list == l }.takeIf { it.isNotEmpty() }?.let { "${l.label}: ${names(it)}" }
        }.joinToString(" · ")

    /** The undo bar's line: "Added sun cream and 2 chargers to Lisbon trip". */
    fun doneLine(p: AskProposal.AddTripItems): String = "Added ${names(p.entries)} to ${p.tripLabel}"

    /**
     * What tapping the card writes, against the lists as they are now: a thing waiting on the same list stays one row,
     * a ticked one is unticked ([revive], ids), the rest are added ([add]).
     */
    fun plan(tripId: String, current: List<TripListItem>, entries: List<TripListEntry>): TripListAskPlan {
        val add = mutableListOf<TripListEntry>()
        val revive = mutableListOf<String>()
        entries.forEach { e ->
            val same = current.filter { it.tripId == tripId && it.list == e.list && it.text.equals(e.text, ignoreCase = true) }
            when {
                same.any { !it.done } -> Unit
                same.isNotEmpty() -> revive += same.first().id
                else -> add += e
            }
        }
        return TripListAskPlan(add, revive)
    }
}

/** What a trip list card writes: [add] new things, [revive] ticked items' ids to untick. */
data class TripListAskPlan(val add: List<TripListEntry>, val revive: List<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && revive.isEmpty()
}
