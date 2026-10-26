package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Trip lists (Fix first, Meka 2026-10-26: "I'm travelling on Wednesday"), slice 1: three lists on every trip, **To buy**,
 * **To pack** and **Before we leave**, as tabs on the trip (mockup `docs/design/trip/trip-lists.dc.html`). One add field:
 * "buy sun cream" goes to To buy, "pack 2 chargers" to To pack with "× 2", anything else to the open tab; each item lands
 * in its group by its words (Pharmacy, Supermarket, Online; Documents, Clothes, Toiletries, Tech, Kids, Medicines; The
 * house, On the day), else "Added". Packing knows whose it is ("for the kids", "my …"), filtered Everyone / Me / Kids.
 * A tick is instant on the apps; ticked items sink to the foot of their group. Top: the trip's dates, days to go and
 * Bought n of N, Packed n of N. Plain rules, nothing leaves Meka's synced data. One synced entity type (ADR-008 addendum
 * 2026-10-26): `trip_item`.
 */
object TripItemFields {
    const val TRIP = "tripId"
    /** [TripList.wire]. */
    const val LIST = "list"
    /** "Sun cream SPF 50" (at most [TripListRules.MAX_TEXT]). */
    const val TEXT = "text"
    /** The group's name ("Pharmacy"), worked out when added. */
    const val GROUP = "group"
    /** "× 5"; absent for one. */
    const val QTY = "qty"
    /** [TripWho.wire]; packing only. */
    const val WHO = "who"
    const val DONE = "done"
    const val DONE_AT = "doneAtMs"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
    /** Set on a removed item once Undo has put a copy back, so a second Undo adds nothing. */
    const val RESTORED = "restored"
}

/** The three lists, in tab order. */
enum class TripList(val wire: String, val label: String, val hint: String, val empty: String) {
    BUY("buy", "To buy", "Add something to buy…", "Nothing to buy yet. Type it above: \"sun cream\", \"plug adapters × 2\"."),
    PACK("pack", "To pack", "Add something to pack…", "Nothing to pack yet. Type it above: \"passports × 4\", \"2 chargers\"."),
    LEAVE("leave", "Before we leave", "Add a job before you leave…", "No jobs yet. Type them above: \"put the bins out\", \"check in online\".");

    companion object { fun of(wire: String?): TripList = entries.firstOrNull { it.wire == wire } ?: PACK }
}

/** Whose a packed thing is; the To pack filter. */
enum class TripWho(val wire: String, val label: String) {
    EVERYONE("everyone", "Everyone"), ME("me", "Me"), KIDS("kids", "Kids");

    companion object { fun of(wire: String?): TripWho = entries.firstOrNull { it.wire == wire } ?: EVERYONE }
}

data class TripListItem(
    val id: String, val tripId: String, val list: TripList, val text: String, val group: String, val qty: Int?,
    val who: TripWho, val done: Boolean, val doneAtMs: Long?, val addedAtMs: Long,
)

/** A typed item ([TripListRules.read]). */
data class TripListEntry(val list: TripList, val text: String, val group: String, val qty: Int?, val who: TripWho)

/** One row: "T-shirts" · "Me" · "× 5", [done] drawn struck through and faded. */
data class TripListRow(val id: String, val text: String, val sub: String, val qty: String, val done: Boolean, val spoken: String)

/** One group: "Pharmacy" · "1 of 3" (or "All done", [allDone]), its rows to do first, then the ticked ones. */
data class TripListGroup(val key: String, val name: String, val count: String, val allDone: Boolean, val rows: List<TripListRow>)

/** One tab: its groups (packing's carry [TripListRow.sub] = whose). */
data class TripListTab(val list: TripList, val label: String, val hint: String, val empty: String, val groups: List<TripListGroup>, val left: Int)

/** "Bought" · "3 of 9", and how far along (0..1) for the thin bar. */
data class TripProgress(val label: String, val line: String, val fraction: Float, val done: Int, val total: Int)

/** A trip's lists: the header, the two bars and the three tabs. */
data class TripListsView(
    val tripId: String,
    /** "Barcelona trip". */
    val title: String,
    /** "Wed 28 Oct – Sun 1 Nov · 5 days". */
    val dates: String,
    /** "2 days to go", "Tomorrow", "Day 2 of 5", "Ended Sun 1 Nov". */
    val countdown: String,
    val countdownLit: Boolean,
    val bought: TripProgress,
    val packed: TripProgress,
    val tabs: List<TripListTab>,
    /** The Trips row's line: "Bought 3 of 9 · packed 2 of 14", or [TripListRules.LISTS_EMPTY]. */
    val summary: String,
) {
    fun tab(list: TripList): TripListTab = tabs.first { it.list == list }

    /** [list]'s groups with To pack filtered to [who] (Everyone's things show for Me and Kids too); empty groups go. */
    fun groups(list: TripList, who: TripWho): List<TripListGroup> {
        val t = tab(list)
        if (list != TripList.PACK || who == TripWho.EVERYONE) return t.groups
        return t.groups.mapNotNull { g ->
            val rows = g.rows.filter { it.sub == who.label || it.sub == TripWho.EVERYONE.label }
            if (rows.isEmpty()) null else TripListRules.group(g.key, g.name, rows)
        }
    }
}

/** What adding a line did: the line under the field, the list it went on (the app opens that tab), and whether nothing was written. */
data class TripListAdded(val line: String, val list: TripList, val refused: Boolean)

/** What removing an item did: "Removed Sun cream" and the token Undo takes. */
data class TripListRemoved(val line: String, val token: String)

object TripListRules {
    const val MAX_TEXT = 60
    const val MAX_QTY = 99
    const val ADDED = "Added"
    const val ADD_DESCRIPTION = "Add an item. Start with buy or pack to choose the list, for example buy sun cream, or pack 2 chargers"
    const val ADD_NOTE = "Type it: \"buy sun cream\", \"pack 2 chargers\". Navilife puts it on the right list."
    const val LISTS_EMPTY = "To buy, to pack and before we leave"
    const val OPEN_LISTS = "Lists"
    const val NOTE = "Ticked items stay ticked on the Fold and the Mac."
    const val NOT_READ = "Type what to buy, pack or do, for example \"buy sun cream\"."
    const val GONE = "That trip has gone."
    const val NOTHING_YET = "Nothing yet"

    /** Each list's groups in the order they show; [ADDED] (anything not matched) comes last. */
    val GROUPS: Map<TripList, List<String>> = mapOf(
        TripList.BUY to listOf("Pharmacy", "Supermarket", "Online", ADDED),
        TripList.PACK to listOf("Documents", "Clothes", "Toiletries", "Medicines", "Tech", "Kids", ADDED),
        TripList.LEAVE to listOf("The house", "On the day", ADDED),
    )

    private fun words(vararg w: String) = w.toSet()

    /** Words that place an item in a group, per list (whole words, singular or plural). */
    private val KEYWORDS: Map<TripList, List<Pair<String, Set<String>>>> = mapOf(
        TripList.BUY to listOf(
            "Pharmacy" to words(
                "sun", "suncream", "sunscreen", "spf", "calpol", "paracetamol", "ibuprofen", "plasters", "plaster", "medicine",
                "medicines", "tablets", "antihistamine", "piriton", "deet", "repellent", "rehydration", "dioralyte", "toothpaste",
                "toothbrush", "toothbrushes", "shampoo", "conditioner", "deodorant", "razor", "razors", "contact", "lens", "lenses",
                "aftersun", "lip", "balm", "nappies", "nappy", "wipes", "travel-size", "mini", "miniature", "sickness", "bands",
            ),
            "Supermarket" to words(
                "snacks", "snack", "water", "bottles", "bottle", "crisps", "biscuits", "fruit", "sandwiches", "drinks", "juice",
                "sweets", "food", "bread", "milk", "nuts", "gum", "bags", "batteries",
            ),
            "Online" to words(
                "adapter", "adapters", "plug", "plugs", "lock", "locks", "headphones", "earphones", "charger", "chargers", "cable",
                "cables", "case", "suitcase", "luggage", "amazon", "online", "power", "bank", "sim", "esim", "pillow",
            ),
        ),
        TripList.PACK to listOf(
            "Documents" to words(
                "passport", "passports", "ghic", "ehic", "boarding", "pass", "passes", "insurance", "visa", "visas", "tickets",
                "ticket", "licence", "license", "documents", "itinerary", "cash", "euros", "cards", "card", "wallet", "railcard",
            ),
            "Medicines" to words("medicine", "medicines", "inhaler", "inhalers", "calpol", "paracetamol", "ibuprofen", "pills", "prescription", "plasters", "first-aid", "epipen"),
            "Clothes" to words(
                "t-shirts", "t-shirt", "tshirts", "shirts", "shirt", "trousers", "jeans", "shorts", "jumper", "jumpers", "socks",
                "pants", "underwear", "pyjamas", "pjs", "dress", "dresses", "coat", "coats", "jacket", "jackets", "shoes", "trainers",
                "sandals", "flip-flops", "swimwear", "swimsuit", "trunks", "hat", "hats", "outfits", "outfit", "hoodie", "hoodies",
                "leggings", "sunglasses", "belt", "clothes", "raincoat", "boots",
            ),
            "Toiletries" to words(
                "toothbrush", "toothbrushes", "toothpaste", "deodorant", "shampoo", "conditioner", "razor", "shower", "gel",
                "toiletries", "suncream", "sunscreen", "moisturiser", "hairbrush", "comb", "makeup", "towel", "towels", "wipes",
                "sun", "aftersun",
            ),
            "Tech" to words(
                "charger", "chargers", "phone", "phones", "laptop", "tablet", "tablets", "ipad", "headphones", "earbuds", "cable",
                "cables", "adapter", "adapters", "power", "watch", "kindle", "camera", "plug", "plugs",
            ),
            "Kids" to words("toys", "toy", "teddy", "comfort", "buggy", "pushchair", "nappies", "nappy", "dummy", "colouring", "books", "games", "car-seat"),
        ),
        TripList.LEAVE to listOf(
            "The house" to words(
                "bins", "bin", "heating", "boiler", "plants", "fridge", "windows", "lights", "post", "milk", "neighbour",
                "alarm", "water", "hoover", "bed", "beds", "dishwasher", "washing", "laundry", "rubbish", "cancel", "lock",
                "thermostat", "freezer",
            ),
            "On the day" to words(
                "check-in", "checkin", "check", "taxi", "parking", "park", "boarding", "passports", "leave", "airport",
                "charge", "door", "bags", "uber", "online", "flight", "train", "petrol",
            ),
        ),
    )

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "a couple of" to 2, "a pair of" to 1,
    )

    private fun bare(w: String): String = w.trim { !it.isLetterOrDigit() && it != '-' }.lowercase()

    /**
     * Reads a typed line: a leading "buy"/"get"/"pick up" chooses To buy, "pack"/"bring" To pack, "before we leave"/
     * "remember to"/"to do:" Before we leave, else [open] (a leading "add" is dropped); a count before the thing ("2 chargers", "five t-shirts") or after
     * it ("t-shirts × 5", "x5") is its quantity; "for the kids"/"kids'" or "for me"/"my" says whose (packing only).
     * Null when nothing is left to add.
     */
    fun read(text: String, open: TripList): TripListEntry? {
        var t = text.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', ',')
        if (t.isEmpty()) return null
        var list = open
        val lower = t.lowercase()
        val lead = listOf(
            "before we leave:" to TripList.LEAVE, "before we leave" to TripList.LEAVE, "before leaving" to TripList.LEAVE,
            "remember to" to TripList.LEAVE, "to do:" to TripList.LEAVE, "todo:" to TripList.LEAVE,
            "need to buy" to TripList.BUY, "buy" to TripList.BUY, "get" to TripList.BUY, "pick up" to TripList.BUY,
            "pack" to TripList.PACK, "bring" to TripList.PACK,
        ).firstOrNull { (w, _) -> lower == w || lower.startsWith("$w ") }
        if (lead != null) {
            list = lead.second
            t = t.substring(lead.first.length).trim().removePrefix(":").trim()
        } else if (lower.startsWith("add ")) {
            t = t.substring(4).trim()
        }
        // "… to my trip list", "… for the trip"
        t = t.replace(Regex("(?i)\\s+(to|on|for)\\s+(the|my|our)\\s+trip(\\s+list)?$"), "").trim()
        var who = TripWho.EVERYONE
        val kids = Regex("(?i)\\s+for\\s+(the\\s+)?(kids|children|boys|girls)$")
        val me = Regex("(?i)\\s+for\\s+me$")
        when {
            kids.containsMatchIn(t) -> { who = TripWho.KIDS; t = t.replace(kids, "").trim() }
            me.containsMatchIn(t) -> { who = TripWho.ME; t = t.replace(me, "").trim() }
        }
        val lw = t.lowercase()
        when {
            lw.startsWith("kids' ") || lw.startsWith("kids ") || lw.startsWith("children's ") -> who = TripWho.KIDS
            lw.startsWith("my ") -> { who = TripWho.ME; t = t.substring(3).trim() }
            lw.startsWith("our ") -> t = t.substring(4).trim()
        }
        // Quantity after: "T-shirts × 5", "x5", "x 5"
        var qty: Int? = null
        Regex("(?i)\\s*[×x]\\s*(\\d{1,2})$").find(t)?.let { m -> qty = m.groupValues[1].toInt(); t = t.substring(0, m.range.first).trim() }
        if (qty == null) {
            Regex("^(\\d{1,2})\\s+(.+)$").find(t)?.let { m -> qty = m.groupValues[1].toInt(); t = m.groupValues[2].trim() }
        }
        if (qty == null) {
            val lt = t.lowercase()
            NUMBER_WORDS.entries.sortedByDescending { it.key.length }.firstOrNull { (w, _) -> lt.startsWith("$w ") && lt.length > w.length + 1 }
                ?.let { (w, n) ->
                    // "a" and "an" only drop the article; "a lock" stays one lock.
                    qty = n.takeIf { it > 1 }
                    t = t.substring(w.length).trim()
                }
        }
        qty = qty?.takeIf { it > 1 }?.coerceAtMost(MAX_QTY)
        t = t.trim(' ', ',', '-', ':').take(MAX_TEXT).trim()
        if (t.isEmpty() || t.none { it.isLetterOrDigit() }) return null
        val name = t.replaceFirstChar { it.uppercaseChar() }
        if (list != TripList.PACK) who = TripWho.EVERYONE
        return TripListEntry(list, name, groupOf(list, name, who), qty, who)
    }

    /** The group [text] falls in on [list]: the first group with one of its words, then Kids for a kid's thing, else [ADDED]. */
    fun groupOf(list: TripList, text: String, who: TripWho = TripWho.EVERYONE): String {
        val ws = text.split(' ', '/', ',').map(::bare).filter { it.isNotEmpty() }
        val joined = ws.joinToString(" ")
        for ((group, keys) in KEYWORDS[list].orEmpty()) {
            if (ws.any { it in keys } || keys.any { it.contains(' ') && joined.contains(it) }) return group
        }
        return if (list == TripList.PACK && who == TripWho.KIDS) "Kids" else ADDED
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun items(entities: Map<String, Map<String, FieldValue>>): List<TripListItem> = entities.mapNotNull { (id, s) ->
        if (s.f(TripItemFields.DELETED).boolOrNull == true) return@mapNotNull null
        val trip = s.f(TripItemFields.TRIP).textOrNull ?: return@mapNotNull null
        val text = s.f(TripItemFields.TEXT).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val list = TripList.of(s.f(TripItemFields.LIST).textOrNull)
        val group = s.f(TripItemFields.GROUP).textOrNull?.takeIf { it in GROUPS.getValue(list) } ?: groupOf(list, text)
        TripListItem(
            id, trip, list, text, group,
            s.f(TripItemFields.QTY).longOrNull?.toInt()?.takeIf { it > 1 },
            TripWho.of(s.f(TripItemFields.WHO).textOrNull),
            s.f(TripItemFields.DONE).boolOrNull == true,
            s.f(TripItemFields.DONE_AT).longOrNull,
            s.f(TripItemFields.ADDED_AT).longOrNull ?: 0L,
        )
    }.sortedWith(compareBy<TripListItem> { it.addedAtMs }.thenBy { it.id })

    fun fields(tripId: String, e: TripListEntry, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(TripItemFields.TRIP, tripId.fv())
        put(TripItemFields.LIST, e.list.wire.fv())
        put(TripItemFields.TEXT, e.text.fv())
        put(TripItemFields.GROUP, e.group.fv())
        e.qty?.let { put(TripItemFields.QTY, it.toLong().fv()) }
        if (e.list == TripList.PACK) put(TripItemFields.WHO, e.who.wire.fv())
        put(TripItemFields.DONE, false.fv())
        put(TripItemFields.ADDED_AT, nowMs.fv())
    }

    fun tickFields(done: Boolean, nowMs: Long): Map<String, FieldValue> = mapOf(
        TripItemFields.DONE to done.fv(),
        TripItemFields.DONE_AT to (if (done) nowMs.fv() else FieldValue.Null),
    )

    /** "× 5", or "" for one. */
    fun qtyLabel(qty: Int?): String = qty?.takeIf { it > 1 }?.let { "× $it" }.orEmpty()

    /** "Added Sun cream to To buy · Pharmacy", "Added Chargers × 2 to To pack · Tech". */
    fun addedLine(e: TripListEntry): String =
        "Added ${e.text}" + (e.qty?.let { " × $it" } ?: "") + " to ${e.list.label}" + (e.group.takeIf { it != ADDED }?.let { " · $it" } ?: "")

    fun removedLine(i: TripListItem): String = "Removed ${i.text}"

    internal fun group(key: String, name: String, rows: List<TripListRow>): TripListGroup {
        val done = rows.count { it.done }
        val all = rows.isNotEmpty() && done == rows.size
        return TripListGroup(key, name, if (all) "All done" else "$done of ${rows.size}", all, rows)
    }

    private fun row(i: TripListItem): TripListRow {
        val sub = if (i.list == TripList.PACK) i.who.label else ""
        val qty = qtyLabel(i.qty)
        val spoken = listOfNotNull(
            i.text, qty.takeIf { it.isNotEmpty() }?.let { "times ${i.qty}" }, sub.takeIf { it.isNotEmpty() }?.let { "for ${it.lowercase()}" },
            if (i.done) "ticked" else null,
        ).joinToString(", ")
        return TripListRow(i.id, i.text, sub, qty, i.done, spoken)
    }

    private fun progress(label: String, items: List<TripListItem>): TripProgress {
        val done = items.count { it.done }
        // Never "0 of 0" (the quality gate reads an empty count as nonsense).
        return TripProgress(label, if (items.isEmpty()) NOTHING_YET else "$done of ${items.size}", if (items.isEmpty()) 0f else done.toFloat() / items.size, done, items.size)
    }

    private fun tab(list: TripList, items: List<TripListItem>): TripListTab {
        val mine = items.filter { it.list == list }
        val byGroup = mine.groupBy { it.group }
        val groups = GROUPS.getValue(list).mapNotNull { g ->
            val inGroup = byGroup[g] ?: return@mapNotNull null
            // To do in the order added; ticked ones sink below, the latest ticked last.
            val sorted = inGroup.filter { !it.done } + inGroup.filter { it.done }.sortedWith(compareBy<TripListItem> { it.doneAtMs ?: 0L }.thenBy { it.addedAtMs })
            group("${list.wire}:$g", g, sorted.map(::row))
        }
        return TripListTab(list, list.label, list.hint, list.empty, groups, mine.count { !it.done })
    }

    /** "2 days to go", "Tomorrow", "Day 2 of 5", "Ended Sun 1 Nov". */
    fun countdown(t: TripItem, today: Long): String = when {
        today < t.startDay -> when (val n = t.startDay - today) { 1L -> "Tomorrow"; else -> "$n days to go" }
        else -> TripRules.status(t, today)
    }

    fun view(trip: TripItem, all: List<TripListItem>, today: Long): TripListsView {
        val items = all.filter { it.tripId == trip.id }
        val bought = progress("Bought", items.filter { it.list == TripList.BUY })
        val packed = progress("Packed", items.filter { it.list == TripList.PACK })
        val summary = if (items.isEmpty()) LISTS_EMPTY else listOfNotNull(
            bought.takeIf { it.total > 0 }?.let { "bought ${it.line}" },
            packed.takeIf { it.total > 0 }?.let { "packed ${it.line}" },
            items.filter { it.list == TripList.LEAVE }.takeIf { it.isNotEmpty() }?.let { l -> "${l.count { it.done }} of ${l.size} jobs done" },
        ).joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
        return TripListsView(
            tripId = trip.id,
            title = "${trip.name} trip",
            dates = CalendarAgenda.spanLabel(trip.startDay, trip.endDay) + " · " + (if (trip.days == 1) "1 day" else "${trip.days} days"),
            countdown = countdown(trip, today),
            countdownLit = today >= trip.startDay - 2 && today <= trip.endDay,
            bought = bought,
            packed = packed,
            tabs = TripList.entries.map { tab(it, items) },
            summary = summary,
        )
    }

    /** Every trip's lists, by trip, in the trips' order. */
    fun views(trips: List<TripItem>, items: List<TripListItem>, today: Long): List<TripListsView> = trips.map { view(it, items, today) }
}

/** The trips' lists on a replica. */
class TripLists(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
) {
    fun items(): List<TripListItem> =
        TripListRules.items(replica.entities(EntityTypes.TRIP_ITEM).associate { it.ref.entityId to it.fields })

    /** Adds what [text] says to the trip [tripId] ([TripListRules.read]); refused (nothing written) when there's nothing to add or the trip is gone. */
    fun add(tripId: String, text: String, open: TripList, tripExists: Boolean): TripListAdded {
        if (!tripExists) return TripListAdded(TripListRules.GONE, open, refused = true)
        val e = TripListRules.read(text, open) ?: return TripListAdded(TripListRules.NOT_READ, open, refused = true)
        replica.commitLocal(EntityTypes.TRIP_ITEM, newId(), TripListRules.fields(tripId, e, nowMs()))
        return TripListAdded(TripListRules.addedLine(e), e.list, refused = false)
    }

    /** Ticks (or unticks) one item; false when it isn't there or is already so. */
    fun tick(id: String, done: Boolean): Boolean {
        val f = replica.entity(EntityTypes.TRIP_ITEM, id)?.fields ?: return false
        if (f[TripItemFields.DELETED]?.boolOrNull == true) return false
        if ((f[TripItemFields.DONE]?.boolOrNull == true) == done) return false
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, TripListRules.tickFields(done, nowMs()))
        return true
    }

    /** Removes one item, with Undo's token (its id); null when it isn't there. */
    fun remove(id: String): TripListRemoved? {
        val item = items().firstOrNull { it.id == id } ?: return null
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, mapOf(TripItemFields.DELETED to true.fv()))
        return TripListRemoved(TripListRules.removedLine(item), id)
    }

    /**
     * Undo after [remove]: a removal is for good on every device (`deleted` is TrueWins), so the item comes back as a
     * copy under a new id, ticked as it was. False when [token] names nothing removed.
     */
    fun undoRemove(token: String): Boolean {
        val f = replica.entity(EntityTypes.TRIP_ITEM, token)?.fields ?: return false
        if (f[TripItemFields.DELETED]?.boolOrNull != true || f[TripItemFields.RESTORED]?.boolOrNull == true) return false
        replica.commitLocal(EntityTypes.TRIP_ITEM, token, mapOf(TripItemFields.RESTORED to true.fv()))
        replica.commitLocal(EntityTypes.TRIP_ITEM, newId(), f - TripItemFields.DELETED - TripItemFields.RESTORED)
        return true
    }
}
