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
    /** Slice 2: the shopping list's item a To buy item is linked to ([ShoppingFields.TRIP_ITEM] points back). */
    const val SHOP = "shopId"
    /** True when adding this item made that shopping item, so removing it from the trip takes it off the shopping list too. */
    const val SHOP_MADE = "shopMade"
    /** Slice 2e: the [TripTemplateFields] template this item belongs to; such an item has no [TRIP] and is never shown on a trip. */
    const val TEMPLATE = "templateId"
    /**
     * Slice 3a, Before we leave reminders: the reminder's day, counted from the trip's first day (-1 the night before,
     * 0 on the day), so it moves with the trip's dates and copies into a template; absent for none.
     */
    const val REMIND_DAY = "remindDay"
    /** Slice 3a: the reminder's minute of that day (21:00 = 1260). */
    const val REMIND_MIN = "remindMin"
}

/** Slice 3a: a Before we leave job's reminder, [dayOffset] days from the trip's first day at [minute]. */
data class TripRemind(val dayOffset: Int, val minute: Int)

/**
 * Slice 2e, "Save as a template for next time" (ADR-008 addendum 2026-10-26): one saved set of lists, named for the trip
 * it was saved from; its things are `trip_item`s with [TripItemFields.TEMPLATE] instead of a trip.
 */
object TripTemplateFields {
    /** The trip's name when saved ("Lisbon"). */
    const val NAME = "name"
    /** The trip it was saved from; saving that trip again replaces it. */
    const val SOURCE = "sourceTripId"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** A saved template: [count] things on its lists. */
data class TripTemplate(val id: String, val name: String, val sourceTripId: String?, val addedAtMs: Long, val count: Int)

/** What saving a trip's lists as a template did: "Saved as a template · 23 things"; [refused] when nothing was saved. */
data class TripTemplateSaved(val line: String, val refused: Boolean)

/** The three lists, in tab order. */
enum class TripList(val wire: String, val label: String, val hint: String, val empty: String) {
    BUY("buy", "To buy", "Add something to buy…", "Nothing to buy yet. Type it above: \"sun cream\", \"plug adapters × 2\"."),
    PACK("pack", "To pack", "Add something to pack…", "Nothing to pack yet. Type it above: \"passports × 4\", \"2 chargers\"."),
    LEAVE("leave", "Before we leave", "Add a job before you leave…", "No jobs yet. Type them above: \"put the bins out at 9pm\", \"check in online on the day\". A time sets a reminder.");

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
    /** Slice 2: the linked shopping item (To buy only), whose Got decides [done] while it is on the list. */
    val shopId: String? = null,
    /** Slice 3a: Before we leave only. */
    val remind: TripRemind? = null,
)

/** A typed item ([TripListRules.read]); slice 3a: a Before we leave job may carry its [remind]er. */
data class TripListEntry(val list: TripList, val text: String, val group: String, val qty: Int?, val who: TripWho, val remind: TripRemind? = null)

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
    /** Slice 2b: while this trip's lists are empty, the last trip with lists to start from; else null. */
    val startFrom: TripStartFrom? = null,
    /** Slice 2d: who's going, one chip each (Me, his partner, the kids), lit when going. */
    val going: List<TripGoingChip> = emptyList(),
    /** Slice 2d: the leave time as typed back ("06:30"), or "" when not set; the editor's field starts from it. */
    val leave: String = "",
    /** Slice 2d: true until he has said who's going or when he leaves, so the screen offers [TripListRules.SET_UP]. */
    val needsSetUp: Boolean = false,
    /** Slice 2d: To pack's filters; Kids goes when the kids aren't going and nothing is theirs. */
    val whoFilters: List<TripWho> = TripWho.entries,
    /** Slice 2e: while the lists are empty, every way to start them: saved templates (newest first), then the last trip. */
    val startOptions: List<TripStartFrom> = listOfNotNull(startFrom),
    /** Slice 2e: the foot's button ("Save as a template for next time", "Update the Lisbon template"); null while the lists are empty. */
    val saveTemplate: String? = null,
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

/**
 * Slice 2b, "Start from last trip": [line] "Start from Lisbon trip", [sub] "Copies its 23 things, all unticked",
 * [sourceTripId] the trip copied from.
 */
data class TripStartFrom(
    val sourceTripId: String, val line: String, val sub: String, val spoken: String,
    /** Slice 2e: set when this offer is a saved template (copy with its id), else it is the trip [sourceTripId]. */
    val templateId: String? = null,
)

/** What copying another trip's lists did: "Copied 23 things from Lisbon trip" and Undo's token; [refused] when nothing was copied. */
data class TripListCopied(val line: String, val token: String, val refused: Boolean)

/** Slice 2d: one who's-going chip: [wire] a [TripGoer] wire, "Me" / his partner's first name / the kids' names. */
data class TripGoingChip(val wire: String, val label: String, val on: Boolean, val spoken: String)

/** Slice 2d: what setting the leave time did: "Leaving home 06:30 on Wednesday"; [refused] when nothing was written. */
data class TripLeaveSaved(val line: String, val refused: Boolean)

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
    /** Slice 2b. */
    const val COPY_NOTHING = "That trip has nothing on its lists."
    const val COPY_NOT_EMPTY = "This trip has lists already."
    const val COPY_UNDONE = "Copied lists taken off"
    /** A linked To buy row's line (slice 2). */
    const val ON_SHOPPING = "On your shopping list"
    /** Slice 2d: the header's offer while who's going and the leave time are both unset. */
    const val SET_UP = "Add who's going and when you leave"
    const val EDIT = "Who's going and when you leave"
    const val GOING_LABEL = "WHO'S GOING"
    const val LEAVE_LABEL = "LEAVE HOME AT"
    const val LEAVE_HINT = "06:30"
    const val LEAVE_DESCRIPTION = "The time you leave home on the first day, for example 06:30"
    const val LEAVE_NOT_READ = "Type the time you leave home, for example 06:30 or 6.30am."
    const val LEAVE_CLEARED = "No leave time"
    const val DONE = "Done"
    /** Slice 2e. */
    const val SAVE_TEMPLATE = "Save as a template for next time"
    const val TEMPLATE_EMPTY = "There's nothing on this trip's lists to save."
    const val TEMPLATE_FORGOTTEN = "Template forgotten"
    const val FORGET_TEMPLATE = "Forget this template"
    /** At most this many templates are offered on a new trip. */
    const val MAX_TEMPLATE_OFFERS = 3
    /** Slice 3a: "the night before" with no time reminds at 21:00. */
    const val NIGHT_BEFORE_MIN = 21 * 60
    /** Slice 3a: "on the day" with no time reminds this long before he leaves home (07:00 when no leave time is set). */
    const val ON_THE_DAY_LEAD_MIN = 30
    const val ON_THE_DAY_MIN = 7 * 60
    /** Slice 3a: a job's reminder stands this long after its time (until ticked), then goes quietly. */
    const val REMIND_STANDS_MIN = 12 * 60

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
    fun read(text: String, open: TripList, leaveMinute: Int? = null): TripListEntry? {
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
        // Slice 3a: "put the bins out at 9pm", "check in online at 06:30", "charge everything the night before".
        var remind: TripRemind? = null
        if (list == TripList.LEAVE) readRemind(t, leaveMinute)?.let { (rest, r) -> t = rest; remind = r }
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
        return TripListEntry(list, name, groupOf(list, name, who), qty, who, remind.takeIf { list == TripList.LEAVE })
    }

    private const val DAY_WORDS =
        "the night before|the evening before|the day before|the night before we leave|on the day|that morning|the morning we leave|on the morning"
    private val REMIND_TIME = Regex(
        "(?i)[\\s,]+(?:remind(?:\\s+me)?\\s+)?(?:($DAY_WORDS)\\s+)?(?:at\\s+(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm)?)|(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm)|\\d{1,2}[:.]\\d{2}))(?:\\s+($DAY_WORDS))?$",
    )
    private val REMIND_DAY = Regex("(?i)[\\s,]+(?:remind(?:\\s+me)?\\s+)?($DAY_WORDS)$")

    private fun offsetOf(words: String): Int = if (words.lowercase().contains("before")) -1 else 0

    /**
     * Slice 3a: a Before we leave job's reminder at the end of its words, and the words without it: "at 9pm" / "21:00" /
     * "at 6.30am", with "the night before" or "on the day" either side, or the day words alone (the night before at
     * [NIGHT_BEFORE_MIN]; on the day [ON_THE_DAY_LEAD_MIN] before [leaveMinute], else [ON_THE_DAY_MIN]). A time with no
     * day is the night before when it is after [leaveMinute] (after noon when that isn't set), else on the day. Null when
     * the words name no reminder, or nothing would be left of the job.
     */
    fun readRemind(text: String, leaveMinute: Int? = null): Pair<String, TripRemind>? {
        REMIND_TIME.find(text)?.let { m ->
            val minute = readLeave(m.groupValues[2].ifEmpty { m.groupValues[3] }) ?: return@let
            val day = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val offset = if (day.isNotEmpty()) offsetOf(day) else if (minute > (leaveMinute ?: (12 * 60 - 1))) -1 else 0
            val rest = jobOf(text.substring(0, m.range.first)) ?: return null
            return rest to TripRemind(offset, minute)
        }
        REMIND_DAY.find(text)?.let { m ->
            val offset = offsetOf(m.groupValues[1])
            val minute = if (offset < 0) NIGHT_BEFORE_MIN else leaveMinute?.let { (it - ON_THE_DAY_LEAD_MIN).coerceAtLeast(0) } ?: ON_THE_DAY_MIN
            val rest = jobOf(text.substring(0, m.range.first)) ?: return null
            return rest to TripRemind(offset, minute)
        }
        return null
    }

    /** The job's words before its reminder, without a dangling "at" / "remind me"; null when none are left. */
    private fun jobOf(before: String): String? {
        val rest = before.trim().replace(Regex("(?i)(^|[\\s,]+)(at|remind(\\s+me)?)$"), "").trim(' ', ',')
        return rest.takeIf { r -> r.any { it.isLetterOrDigit() } }
    }

    /** Slice 3a: "Remind Tue 21:00" for a trip starting on [startDay]. */
    fun remindLabel(startDay: Long, r: TripRemind): String {
        val day = startDay + r.dayOffset
        return "Remind ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]} ${LocalClock.formatMinute(r.minute)}"
    }

    private fun remindOf(s: Map<String, FieldValue>): TripRemind? {
        val min = s[TripItemFields.REMIND_MIN]?.longOrNull?.toInt()?.takeIf { it in 0 until 24 * 60 } ?: return null
        val day = s[TripItemFields.REMIND_DAY]?.longOrNull?.toInt()?.coerceIn(-7, 0) ?: return null
        return TripRemind(day, min)
    }

    /**
     * Slice 3a: each Before we leave job with a reminder, not yet ticked, as a heads-up at its time ("Before Lisbon:
     * Put the bins out" · "Lisbon trip · Wed 28 Oct"), standing [REMIND_STANDS_MIN] or until ticked. Jobs of trips
     * already over, and reminders more than a day gone, are left out.
     */
    fun notices(trips: List<TripItem>, items: List<TripListItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val byId = trips.associateBy { it.id }
        return items.mapNotNull { i ->
            val r = i.remind ?: return@mapNotNull null
            if (i.list != TripList.LEAVE || i.done) return@mapNotNull null
            val trip = byId[i.tripId] ?: return@mapNotNull null
            val at = cal.toEpochMs(trip.startDay + r.dayOffset, r.minute)
            val until = at + REMIND_STANDS_MIN * 60_000L
            if (nowMs >= until || cal.epochDayOf(nowMs) > trip.endDay) return@mapNotNull null
            Notice(
                key = "trip-job:${i.id}:${trip.startDay + r.dayOffset}:${r.minute}", source = NoticeSource.TRIP_JOBS, tier = NoticeTier.HEADS_UP,
                title = "Before ${trip.name}: ${i.text}", text = "${tripLabel(trip)} · ${CivilDate.shortLabel(trip.startDay)}",
                atMs = at, target = NoticeTarget.TODAY, expiresAtMs = until,
            )
        }
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

    /**
     * The items from their fields; [shop] is each shopping item still on the list → whether it is got. A To buy item
     * linked to one of those is done exactly when it is got, so a tick on the shopping list (on any device, or by his
     * wife on the family page) ticks the trip's item too, and the other way round.
     */
    fun items(entities: Map<String, Map<String, FieldValue>>, shop: Map<String, Boolean> = emptyMap()): List<TripListItem> = entities.mapNotNull { (id, s) ->
        if (s.f(TripItemFields.DELETED).boolOrNull == true) return@mapNotNull null
        val trip = s.f(TripItemFields.TRIP).textOrNull ?: return@mapNotNull null
        val text = s.f(TripItemFields.TEXT).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val list = TripList.of(s.f(TripItemFields.LIST).textOrNull)
        val group = s.f(TripItemFields.GROUP).textOrNull?.takeIf { it in GROUPS.getValue(list) } ?: groupOf(list, text)
        val shopId = s.f(TripItemFields.SHOP).textOrNull?.takeIf { list == TripList.BUY && it.isNotBlank() }
        val stored = s.f(TripItemFields.DONE).boolOrNull == true
        TripListItem(
            id, trip, list, text, group,
            s.f(TripItemFields.QTY).longOrNull?.toInt()?.takeIf { it > 1 },
            TripWho.of(s.f(TripItemFields.WHO).textOrNull),
            shopId?.let { shop[it] } ?: stored,
            s.f(TripItemFields.DONE_AT).longOrNull,
            s.f(TripItemFields.ADDED_AT).longOrNull ?: 0L,
            shopId,
            remindOf(s).takeIf { list == TripList.LEAVE },
        )
    }.sortedWith(compareBy<TripListItem> { it.addedAtMs }.thenBy { it.id })

    fun fields(tripId: String, e: TripListEntry, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(TripItemFields.TRIP, tripId.fv())
        put(TripItemFields.LIST, e.list.wire.fv())
        put(TripItemFields.TEXT, e.text.fv())
        put(TripItemFields.GROUP, e.group.fv())
        e.qty?.let { put(TripItemFields.QTY, it.toLong().fv()) }
        if (e.list == TripList.PACK) put(TripItemFields.WHO, e.who.wire.fv())
        e.remind?.takeIf { e.list == TripList.LEAVE }?.let {
            put(TripItemFields.REMIND_DAY, it.dayOffset.toLong().fv())
            put(TripItemFields.REMIND_MIN, it.minute.toLong().fv())
        }
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
    fun addedLine(e: TripListEntry, startDay: Long? = null): String =
        "Added ${e.text}" + (e.qty?.let { " × $it" } ?: "") + " to ${e.list.label}" + (e.group.takeIf { it != ADDED }?.let { " · $it" } ?: "") +
            (if (startDay != null) e.remind?.let { " · " + remindLabel(startDay, it).replaceFirstChar { c -> c.lowercaseChar() } } else null).orEmpty()

    fun removedLine(i: TripListItem): String = "Removed ${i.text}"

    /**
     * What a To buy item is called on the shopping list: "Plug adapters × 2" (one line, so no commas or semicolons,
     * which would split it into several things).
     */
    fun shoppingTitle(text: String, qty: Int?): String =
        ShoppingRules.tidy(text.replace(',', ' ').replace(';', ' ').replace('\n', ' ') + (qty?.takeIf { it > 1 }?.let { " × $it" } ?: ""))

    /** The trip's name on its shopping items: "Lisbon trip". */
    fun tripLabel(trip: TripItem): String = "${trip.name} trip"

    internal fun group(key: String, name: String, rows: List<TripListRow>): TripListGroup {
        val done = rows.count { it.done }
        val all = rows.isNotEmpty() && done == rows.size
        return TripListGroup(key, name, if (all) "All done" else "$done of ${rows.size}", all, rows)
    }

    private fun row(i: TripListItem, startDay: Long): TripListRow {
        val sub = when {
            i.list == TripList.PACK -> i.who.label
            i.shopId != null -> ON_SHOPPING
            i.remind != null && !i.done -> remindLabel(startDay, i.remind)
            else -> ""
        }
        val qty = qtyLabel(i.qty)
        val spoken = listOfNotNull(
            i.text, qty.takeIf { it.isNotEmpty() }?.let { "times ${i.qty}" },
            sub.takeIf { it.isNotEmpty() }?.let { if (i.list == TripList.LEAVE) it.replaceFirstChar { c -> c.lowercaseChar() } else "for ${it.lowercase()}" },
            if (i.done) "ticked" else null,
        ).joinToString(", ")
        return TripListRow(i.id, i.text, sub, qty, i.done, spoken)
    }

    private fun progress(label: String, items: List<TripListItem>): TripProgress {
        val done = items.count { it.done }
        // Never "0 of 0" (the quality gate reads an empty count as nonsense).
        return TripProgress(label, if (items.isEmpty()) NOTHING_YET else "$done of ${items.size}", if (items.isEmpty()) 0f else done.toFloat() / items.size, done, items.size)
    }

    private fun tab(list: TripList, items: List<TripListItem>, startDay: Long): TripListTab {
        val mine = items.filter { it.list == list }
        val byGroup = mine.groupBy { it.group }
        val groups = GROUPS.getValue(list).mapNotNull { g ->
            val inGroup = byGroup[g] ?: return@mapNotNull null
            // To do in the order added; ticked ones sink below, the latest ticked last.
            val sorted = inGroup.filter { !it.done } + inGroup.filter { it.done }.sortedWith(compareBy<TripListItem> { it.doneAtMs ?: 0L }.thenBy { it.addedAtMs })
            group("${list.wire}:$g", g, sorted.map { row(it, startDay) })
        }
        return TripListTab(list, list.label, list.hint, list.empty, groups, mine.count { !it.done })
    }

    /**
     * "2 days to go", "Tomorrow", "Day 2 of 5", "Ended Sun 1 Nov"; slice 2d adds the leave time until the first day is
     * over: "2 days to go · leave home 06:30 Wednesday", "Tomorrow · leave home 06:30", "Day 1 of 5 · leave home 06:30".
     */
    fun countdown(t: TripItem, today: Long): String {
        val base = when {
            today < t.startDay -> when (val n = t.startDay - today) { 1L -> "Tomorrow"; else -> "$n days to go" }
            else -> TripRules.status(t, today)
        }
        val leave = t.leaveMinute?.takeIf { today <= t.startDay } ?: return base
        val day = if (t.startDay - today >= 2) " " + CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(t.startDay) - 1] else ""
        return "$base · leave home ${LocalClock.formatMinute(leave)}$day"
    }

    /**
     * Slice 2d: a typed leave time → its minute of the day: "06:30", "6:30", "6.30", "0630", "7", "6am", "6:30 pm",
     * "leave at 7". Null for anything else.
     */
    fun readLeave(typed: String): Int? {
        val t = typed.trim().lowercase().removePrefix("leave").trim().removePrefix("home").trim().removePrefix("at").trim()
        val m = Regex("""^(\d{1,2})(?:[:.]?(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$""").matchEntire(t) ?: return null
        var h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val mer = m.groupValues[3].replace(".", "")
        if (mi !in 0..59) return null
        when (mer) {
            "am" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            "pm" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            else -> if (h !in 0..23) return null
        }
        return h * 60 + mi
    }

    /** "Leaving home 06:30 on Wed 28 Oct". */
    fun leaveSavedLine(startDay: Long, minute: Int): String =
        "Leaving home ${LocalClock.formatMinute(minute)} on ${CivilDate.shortLabel(startDay)}"

    private fun firstName(name: String?): String? = name?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotEmpty() }

    /** "Alex", "Alex and Sam", "Alex, Sam and Jo"; "The kids" when About you names none (or more than three). */
    private fun kidsLabel(about: AboutYou): String {
        val names = about.children.mapNotNull { firstName(it.name) }
        return when (names.size) {
            0 -> "The kids"
            1 -> names[0]
            2, 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            else -> "The kids"
        }
    }

    /** Slice 2d: the who's-going chips, from About you's names. */
    fun goingChips(trip: TripItem, about: AboutYou): List<TripGoingChip> = TripGoer.entries.map { g ->
        val label = when (g) {
            TripGoer.ME -> "Me"
            TripGoer.PARTNER -> firstName(about.partnerName) ?: "Partner"
            TripGoer.KIDS -> kidsLabel(about)
        }
        val on = trip.going?.contains(g) == true
        TripGoingChip(g.wire, label, on, "$label, " + if (on) "going" else "not going")
    }

    /** Slice 2d: "4 people", "Just you", "2 people and the kids" (children not in About you); null until chosen or when nobody is. */
    fun people(trip: TripItem, about: AboutYou): String? {
        val going = trip.going?.takeIf { it.isNotEmpty() } ?: return null
        val adults = listOf(TripGoer.ME, TripGoer.PARTNER).count { it in going }
        val kids = TripGoer.KIDS in going
        if (kids && about.children.isEmpty()) return when (adults) {
            0 -> "The kids"
            1 -> "1 person and the kids"
            else -> "$adults people and the kids"
        }
        val n = adults + if (kids) about.children.size else 0
        return if (going == setOf(TripGoer.ME)) "Just you" else if (n == 1) "1 person" else "$n people"
    }

    fun things(n: Int): String = if (n == 1) "1 thing" else "$n things"

    /** "Copied 23 things from Lisbon trip". */
    fun copiedLine(n: Int, from: TripItem): String = "Copied ${things(n)} from ${tripLabel(from)}"

    /**
     * The trip to start [trip]'s empty lists from: of the other trips with anything on their lists, the latest to start
     * on or before [trip] ("your last trip"), else the latest of any. Null when [trip] has lists or no other trip does.
     */
    fun startFrom(trip: TripItem, trips: List<TripItem>, all: List<TripListItem>): TripStartFrom? {
        if (all.any { it.tripId == trip.id }) return null
        val counts = all.groupingBy { it.tripId }.eachCount()
        val others = trips.filter { it.id != trip.id && (counts[it.id] ?: 0) > 0 }
        val src = others.filter { it.startDay <= trip.startDay }.maxWithOrNull(compareBy<TripItem> { it.startDay }.thenBy { it.addedAtMs })
            ?: others.maxWithOrNull(compareBy<TripItem> { it.startDay }.thenBy { it.addedAtMs })
            ?: return null
        val n = counts.getValue(src.id)
        val line = "Start from ${tripLabel(src)}"
        val sub = "Copies its ${things(n)}, all unticked"
        return TripStartFrom(src.id, line, sub, "$line. $sub")
    }

    /** "Update the Lisbon template" once this trip has been saved as one. */
    fun updateTemplateLabel(name: String): String = "Update the $name template"

    /** "Saved as a template · 23 things", or "Lisbon template updated · 23 things" when it replaced one. */
    fun templateSavedLine(name: String, n: Int, replaced: Boolean): String =
        (if (replaced) "$name template updated" else "Saved as a template") + " · ${things(n)}"

    /**
     * Slice 2e: the saved templates from their entities and the template items' fields, newest first; each counts the
     * things saved with it, and one with nothing left is not offered.
     */
    fun templates(entities: Map<String, Map<String, FieldValue>>, itemEntities: Map<String, Map<String, FieldValue>>): List<TripTemplate> {
        val counts = templateEntries(itemEntities).mapValues { it.value.size }
        return entities.mapNotNull { (id, s) ->
            if (s.f(TripTemplateFields.DELETED).boolOrNull == true) return@mapNotNull null
            val name = s.f(TripTemplateFields.NAME).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val n = counts[id] ?: 0
            if (n == 0) return@mapNotNull null
            TripTemplate(id, name, s.f(TripTemplateFields.SOURCE).textOrNull, s.f(TripTemplateFields.ADDED_AT).longOrNull ?: 0L, n)
        }.sortedWith(compareByDescending<TripTemplate> { it.addedAtMs }.thenBy { it.id })
    }

    /** Slice 2e: each template's things, in the order saved (removed ones left out). */
    fun templateEntries(itemEntities: Map<String, Map<String, FieldValue>>): Map<String, List<TripListEntry>> =
        itemEntities.entries.mapNotNull { (id, s) ->
            if (s.f(TripItemFields.DELETED).boolOrNull == true) return@mapNotNull null
            val tpl = s.f(TripItemFields.TEMPLATE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (s.f(TripItemFields.TRIP).textOrNull != null) return@mapNotNull null
            val text = s.f(TripItemFields.TEXT).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val list = TripList.of(s.f(TripItemFields.LIST).textOrNull)
            val group = s.f(TripItemFields.GROUP).textOrNull?.takeIf { it in GROUPS.getValue(list) } ?: groupOf(list, text)
            val who = if (list == TripList.PACK) TripWho.of(s.f(TripItemFields.WHO).textOrNull) else TripWho.EVERYONE
            Triple(tpl, (s.f(TripItemFields.ADDED_AT).longOrNull ?: 0L) to id,
                TripListEntry(list, text, group, s.f(TripItemFields.QTY).longOrNull?.toInt()?.takeIf { it > 1 }, who,
                    remindOf(s).takeIf { list == TripList.LEAVE }))
        }.groupBy({ it.first }) { it }.mapValues { (_, l) -> l.sortedWith(compareBy({ it.second.first }, { it.second.second })).map { it.third } }

    /** Slice 2e: a template's offer: "Start from your Lisbon template" · "23 things, all unticked". */
    fun templateOffer(t: TripTemplate): TripStartFrom {
        val line = "Start from your ${t.name} template"
        val sub = "${things(t.count).replaceFirstChar { it.uppercaseChar() }}, all unticked"
        return TripStartFrom(t.sourceTripId.orEmpty(), line, sub, "$line. $sub", templateId = t.id)
    }

    /**
     * Slice 2e: every way to start [trip]'s empty lists: up to [MAX_TEMPLATE_OFFERS] saved templates, newest first, then
     * the last trip ([startFrom]) unless a template was saved from that very trip. Empty once the trip has lists.
     */
    fun startOptions(trip: TripItem, trips: List<TripItem>, all: List<TripListItem>, templates: List<TripTemplate>): List<TripStartFrom> {
        if (all.any { it.tripId == trip.id }) return emptyList()
        val tpl = templates.filter { it.sourceTripId != trip.id }.take(MAX_TEMPLATE_OFFERS)
        val last = startFrom(trip, trips, all)?.takeIf { l -> tpl.none { it.sourceTripId == l.sourceTripId } }
        return tpl.map(::templateOffer) + listOfNotNull(last)
    }

    fun view(
        trip: TripItem, all: List<TripListItem>, today: Long, trips: List<TripItem> = emptyList(), about: AboutYou = AboutYou.EMPTY,
        templates: List<TripTemplate> = emptyList(),
    ): TripListsView {
        val items = all.filter { it.tripId == trip.id }
        val options = startOptions(trip, trips, all, templates)
        val bought = progress("Bought", items.filter { it.list == TripList.BUY })
        val packed = progress("Packed", items.filter { it.list == TripList.PACK })
        val summary = if (items.isEmpty()) LISTS_EMPTY else listOfNotNull(
            bought.takeIf { it.total > 0 }?.let { "bought ${it.line}" },
            packed.takeIf { it.total > 0 }?.let { "packed ${it.line}" },
            items.filter { it.list == TripList.LEAVE }.takeIf { it.isNotEmpty() }?.let { l -> "${l.count { it.done }} of ${l.size} jobs done" },
        ).joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
        return TripListsView(
            tripId = trip.id,
            title = tripLabel(trip),
            dates = CalendarAgenda.spanLabel(trip.startDay, trip.endDay) + " · " + (if (trip.days == 1) "1 day" else "${trip.days} days") +
                people(trip, about)?.let { " · $it" }.orEmpty(),
            countdown = countdown(trip, today),
            countdownLit = today >= trip.startDay - 2 && today <= trip.endDay,
            bought = bought,
            packed = packed,
            tabs = TripList.entries.map { tab(it, items, trip.startDay) },
            summary = summary,
            startFrom = options.firstOrNull(),
            going = goingChips(trip, about),
            leave = trip.leaveMinute?.let(LocalClock::formatMinute).orEmpty(),
            needsSetUp = trip.going == null && trip.leaveMinute == null,
            whoFilters = if (trip.going != null && TripGoer.KIDS !in trip.going &&
                items.none { it.list == TripList.PACK && it.who == TripWho.KIDS }) listOf(TripWho.EVERYONE, TripWho.ME) else TripWho.entries,
            startOptions = options,
            saveTemplate = if (items.isEmpty()) null else templates.firstOrNull { it.sourceTripId == trip.id }?.let { updateTemplateLabel(it.name) } ?: SAVE_TEMPLATE,
        )
    }

    /** Every trip's lists, by trip, in the trips' order. */
    fun views(
        trips: List<TripItem>, items: List<TripListItem>, today: Long, about: AboutYou = AboutYou.EMPTY, templates: List<TripTemplate> = emptyList(),
    ): List<TripListsView> = trips.map { view(it, items, today, trips, about, templates) }
}

/**
 * The trips' lists on a replica. With [shopping] (slice 2), every To buy item is also on the shopping list under the
 * trip's name ("For Lisbon trip"), and ticking either ticks both.
 */
class TripLists(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val shopping: Shopping? = null,
) {
    /** Each shopping item still on the list → whether it is got. */
    private fun shopState(): Map<String, Boolean> =
        if (shopping == null) emptyMap()
        else replica.entities(EntityTypes.SHOPPING_ITEM)
            .filter { it.fields[ShoppingFields.DELETED]?.boolOrNull != true }
            .associate { it.ref.entityId to (it.fields[ShoppingFields.GOT]?.boolOrNull == true) }

    fun items(): List<TripListItem> =
        TripListRules.items(replica.entities(EntityTypes.TRIP_ITEM).associate { it.ref.entityId to it.fields }, shopState())

    /**
     * Adds what [text] says to the trip [tripId] ([TripListRules.read]); refused (nothing written) when there's nothing
     * to add or the trip is gone. A To buy item also goes on the shopping list, named for [tripLabel] when given.
     */
    fun add(
        tripId: String, text: String, open: TripList, tripExists: Boolean, tripLabel: String? = null,
        /** Slice 3a: the trip's first day and leave time, for a job's reminder ("at 9pm" the night before). */
        startDay: Long? = null, leaveMinute: Int? = null,
    ): TripListAdded {
        if (!tripExists) return TripListAdded(TripListRules.GONE, open, refused = true)
        val e = TripListRules.read(text, open, leaveMinute) ?: return TripListAdded(TripListRules.NOT_READ, open, refused = true)
        addEntry(tripId, e, tripLabel)
        return TripListAdded(TripListRules.addedLine(e, startDay), e.list, refused = false)
    }

    /**
     * Slice 2c (Ask's trip list card): adds the read entry [e] to the trip [tripId] as the add field would, To buy on the
     * shopping list too; its new id.
     */
    fun addEntry(tripId: String, e: TripListEntry, tripLabel: String? = null): String {
        val id = newId()
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, TripListRules.fields(tripId, e, nowMs()))
        if (e.list == TripList.BUY) link(id, e.text, e.qty, tripLabel)
        return id
    }

    /**
     * Puts the To buy item [id] on the shopping list and links the two. A name already to buy there is shared, not
     * doubled (and stays when the trip's item is removed); one under Got comes back to buy.
     */
    private fun link(id: String, text: String, qty: Int?, tripLabel: String?) {
        val shop = shopping ?: return
        val a = shop.addTracked(TripListRules.shoppingTitle(text, qty))
        val shopId = a.ids.firstOrNull() ?: return
        replica.commitLocal(EntityTypes.SHOPPING_ITEM, shopId, buildMap {
            put(ShoppingFields.TRIP_ITEM, id.fv())
            tripLabel?.let { put(ShoppingFields.TRIP, it.fv()) }
        })
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, mapOf(TripItemFields.SHOP to shopId.fv(), TripItemFields.SHOP_MADE to (shopId in a.created).fv()))
    }

    /** Ticks (or unticks) one item, and its shopping item with it; false when it isn't there or is already so. */
    fun tick(id: String, done: Boolean): Boolean {
        val f = replica.entity(EntityTypes.TRIP_ITEM, id)?.fields ?: return false
        if (f[TripItemFields.DELETED]?.boolOrNull == true) return false
        val item = items().firstOrNull { it.id == id } ?: return false
        if (item.done == done) return false
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, TripListRules.tickFields(done, nowMs()))
        item.shopId?.let { s -> if (done) shopping?.got(s) else shopping?.putBack(s) }
        return true
    }

    /**
     * The shopping item [shopId] was ticked ([got]) or put back on this device: the trip items linked to it keep the
     * same tick in their own fields, so it holds after the shopping item is cleared from Got. True when any changed.
     */
    fun followShopping(shopId: String, got: Boolean): Boolean {
        val linked = replica.entities(EntityTypes.TRIP_ITEM).filter {
            it.fields[TripItemFields.SHOP]?.textOrNull == shopId && it.fields[TripItemFields.DELETED]?.boolOrNull != true &&
                (it.fields[TripItemFields.DONE]?.boolOrNull == true) != got
        }
        linked.forEach { replica.commitLocal(EntityTypes.TRIP_ITEM, it.ref.entityId, TripListRules.tickFields(got, nowMs())) }
        return linked.isNotEmpty()
    }

    /**
     * Slice 2b, "Start from last trip": copies every item on [from]'s lists onto [to]'s, unticked, in the same order,
     * groups, quantities and whose; To buy items go on the shopping list for [to] like a typed one. Refused (nothing
     * written) when [to] has lists already, either trip has gone, or [from] has nothing. The token is the new items' ids,
     * for [undoCopy].
     */
    fun copyFrom(from: TripItem?, to: TripItem?): TripListCopied {
        if (from == null || to == null) return TripListCopied(TripListRules.GONE, "", refused = true)
        val all = items()
        if (all.any { it.tripId == to.id }) return TripListCopied(TripListRules.COPY_NOT_EMPTY, "", refused = true)
        val source = all.filter { it.tripId == from.id }
        if (source.isEmpty()) return TripListCopied(TripListRules.COPY_NOTHING, "", refused = true)
        val now = nowMs()
        val label = TripListRules.tripLabel(to)
        val ids = source.mapIndexed { i, item ->
            val id = newId()
            val e = TripListEntry(item.list, item.text, item.group, item.qty, item.who, item.remind)
            // One millisecond apart, so the copies keep the source's order.
            replica.commitLocal(EntityTypes.TRIP_ITEM, id, TripListRules.fields(to.id, e, now + i))
            if (item.list == TripList.BUY) link(id, item.text, item.qty, label)
            id
        }
        return TripListCopied(TripListRules.copiedLine(ids.size, from), ids.joinToString(","), refused = false)
    }

    /** Slice 2e: the saved templates, newest first. */
    fun templates(): List<TripTemplate> = TripListRules.templates(
        replica.entities(EntityTypes.TRIP_TEMPLATE).associate { it.ref.entityId to it.fields },
        replica.entities(EntityTypes.TRIP_ITEM).associate { it.ref.entityId to it.fields },
    )

    /**
     * Slice 2e, "Save as a template for next time": saves every thing on [trip]'s lists (list, group, quantity, whose;
     * never the ticks) as a template named for the trip. A template saved from the same trip, or with the same name, is
     * replaced. Refused (nothing written) when the trip has gone or its lists are empty.
     */
    fun saveTemplate(trip: TripItem?): TripTemplateSaved {
        if (trip == null) return TripTemplateSaved(TripListRules.GONE, refused = true)
        val source = items().filter { it.tripId == trip.id }
        if (source.isEmpty()) return TripTemplateSaved(TripListRules.TEMPLATE_EMPTY, refused = true)
        val name = trip.name.trim().ifEmpty { "Trip" }
        val replaced = replica.entities(EntityTypes.TRIP_TEMPLATE).filter {
            it.fields[TripTemplateFields.DELETED]?.boolOrNull != true &&
                (it.fields[TripTemplateFields.SOURCE]?.textOrNull == trip.id || it.fields[TripTemplateFields.NAME]?.textOrNull?.trim().equals(name, ignoreCase = true))
        }.map { it.ref.entityId }
        replaced.forEach(::forgetTemplate)
        val id = newId()
        val now = nowMs()
        replica.commitLocal(EntityTypes.TRIP_TEMPLATE, id, mapOf(
            TripTemplateFields.NAME to name.fv(), TripTemplateFields.SOURCE to trip.id.fv(), TripTemplateFields.ADDED_AT to now.fv(),
        ))
        source.forEachIndexed { i, item ->
            val e = TripListEntry(item.list, item.text, item.group, item.qty, item.who, item.remind)
            replica.commitLocal(EntityTypes.TRIP_ITEM, newId(), TripListRules.fields("", e, now + i) - TripItemFields.TRIP + (TripItemFields.TEMPLATE to id.fv()))
        }
        return TripTemplateSaved(TripListRules.templateSavedLine(name, source.size, replaced.isNotEmpty()), refused = false)
    }

    /** Slice 2e: forgets a saved template (and its things) on every device; false when it isn't there. */
    fun forgetTemplate(id: String): Boolean {
        val f = replica.entity(EntityTypes.TRIP_TEMPLATE, id)?.fields ?: return false
        if (f[TripTemplateFields.DELETED]?.boolOrNull == true) return false
        replica.commitLocal(EntityTypes.TRIP_TEMPLATE, id, mapOf(TripTemplateFields.DELETED to true.fv()))
        replica.entities(EntityTypes.TRIP_ITEM)
            .filter { it.fields[TripItemFields.TEMPLATE]?.textOrNull == id && it.fields[TripItemFields.DELETED]?.boolOrNull != true }
            .forEach { replica.commitLocal(EntityTypes.TRIP_ITEM, it.ref.entityId, mapOf(TripItemFields.DELETED to true.fv())) }
        return true
    }

    /**
     * Slice 2e: starts [to]'s empty lists from the saved template [templateId], unticked, as [copyFrom] does from a trip
     * (To buy on the shopping list too); Undo with [undoCopy].
     */
    fun copyFromTemplate(templateId: String, to: TripItem?): TripListCopied {
        if (to == null) return TripListCopied(TripListRules.GONE, "", refused = true)
        val tpl = templates().firstOrNull { it.id == templateId } ?: return TripListCopied(TripListRules.COPY_NOTHING, "", refused = true)
        if (items().any { it.tripId == to.id }) return TripListCopied(TripListRules.COPY_NOT_EMPTY, "", refused = true)
        val entries = TripListRules.templateEntries(replica.entities(EntityTypes.TRIP_ITEM).associate { it.ref.entityId to it.fields })[tpl.id].orEmpty()
        if (entries.isEmpty()) return TripListCopied(TripListRules.COPY_NOTHING, "", refused = true)
        val now = nowMs()
        val label = TripListRules.tripLabel(to)
        val ids = entries.mapIndexed { i, e ->
            val id = newId()
            replica.commitLocal(EntityTypes.TRIP_ITEM, id, TripListRules.fields(to.id, e, now + i))
            if (e.list == TripList.BUY) link(id, e.text, e.qty, label)
            id
        }
        return TripListCopied("Copied ${TripListRules.things(ids.size)} from your ${tpl.name} template", ids.joinToString(","), refused = false)
    }

    /** Undo after [copyFrom]: the copied items leave (and the shopping items they made); false when none was there. */
    fun undoCopy(token: String): Boolean =
        token.split(',').filter { it.isNotBlank() }.map { remove(it) != null }.fold(false) { a, b -> a || b }

    /** Removes one item, with Undo's token (its id); null when it isn't there. A shopping item it made, still to buy, goes too. */
    fun remove(id: String): TripListRemoved? {
        val item = items().firstOrNull { it.id == id } ?: return null
        val made = replica.entity(EntityTypes.TRIP_ITEM, id)?.fields?.get(TripItemFields.SHOP_MADE)?.boolOrNull == true
        replica.commitLocal(EntityTypes.TRIP_ITEM, id, mapOf(TripItemFields.DELETED to true.fv()))
        val s = item.shopId
        if (made && s != null && !item.done) {
            val linkedHere = replica.entity(EntityTypes.SHOPPING_ITEM, s)?.fields?.get(ShoppingFields.TRIP_ITEM)?.textOrNull == id
            if (linkedHere) shopping?.remove(s)
        }
        return TripListRemoved(TripListRules.removedLine(item), id)
    }

    /**
     * Undo after [remove]: a removal is for good on every device (`deleted` is TrueWins), so the item comes back as a
     * copy under a new id, ticked as it was (and back on the shopping list if it left it). False when [token] names
     * nothing removed.
     */
    fun undoRemove(token: String): Boolean {
        val f = replica.entity(EntityTypes.TRIP_ITEM, token)?.fields ?: return false
        if (f[TripItemFields.DELETED]?.boolOrNull != true || f[TripItemFields.RESTORED]?.boolOrNull == true) return false
        replica.commitLocal(EntityTypes.TRIP_ITEM, token, mapOf(TripItemFields.RESTORED to true.fv()))
        val copy = newId()
        val shopId = f[TripItemFields.SHOP]?.textOrNull
        val shop = shopId?.let { replica.entity(EntityTypes.SHOPPING_ITEM, it)?.fields }
        val shopGone = shopId != null && (shop == null || shop[ShoppingFields.DELETED]?.boolOrNull == true)
        replica.commitLocal(EntityTypes.TRIP_ITEM, copy, f - TripItemFields.DELETED - TripItemFields.RESTORED - (if (shopGone) setOf(TripItemFields.SHOP, TripItemFields.SHOP_MADE) else emptySet()))
        val text = f[TripItemFields.TEXT]?.textOrNull
        if (shopId != null && text != null) {
            if (shopGone) {
                if (f[TripItemFields.DONE]?.boolOrNull != true) link(copy, text, f[TripItemFields.QTY]?.longOrNull?.toInt(), shop?.get(ShoppingFields.TRIP)?.textOrNull)
            } else {
                replica.commitLocal(EntityTypes.SHOPPING_ITEM, shopId, mapOf(ShoppingFields.TRIP_ITEM to copy.fv()))
            }
        }
        return true
    }
}
