package os.meka.core.domain

/*
 * Trip lists, slice 3b (Meka 2026-10-26, mockup `docs/design/trip/trip-lists.dc.html`): **Obi's suggestions** — one card
 * per tab of what he might have forgotten, from the trip's length, who's going, where it is (passports, GHIC cards and
 * plug adapters abroad), its bookings (a flight: boarding passes, travel-size toiletries, check in online the night
 * before at the flight's time), the destination's forecast once the server has it (sun cream and swimwear when it's
 * warm, coats when it's cold, an umbrella when rain is likely) and the last trip's lists. Nothing is ever added on its
 * own: a chip adds that one thing, "Add all" adds every chip, "Not now" puts those away on this trip on both devices
 * (the trip's `suggestNo`), and anything already on the lists, ticked or not, is never suggested. Plain rules on the
 * device (no AI call, so it is instant and free); nothing is sent anywhere.
 */

/** One suggested thing: [label] "Swimwear × 4" or "Charge everything · Tue 21:00"; a tap adds [entry]. */
data class TripSuggestChip(val key: String, val label: String, val entry: TripListEntry, val spoken: String)

/** One tab's card: [lead] "It's 24–29° in Lisbon. For 5 days with 4 people, worth packing:" and its chips. */
data class TripSuggestion(val list: TripList, val lead: String, val chips: List<TripSuggestChip>)

/** What adding suggestions did ("Added 3 things to To pack") with Undo's token; [refused] when nothing was added. */
data class TripSuggestAdded(val line: String, val token: String, val refused: Boolean)

object TripSuggestRules {
    const val TITLE = "Obi's suggestions"
    const val ADD_ALL = "Add all"
    const val NOT_NOW = "Not now"
    const val NOTHING = "Those are on the lists already."
    /** At most this many chips on a card, the most useful first. */
    const val MAX_CHIPS = 6
    /** At most this many of them from the last trip's lists. */
    const val MAX_FROM_LAST = 3
    /** At most this many put-away keys are kept on a trip (the oldest go first). */
    const val MAX_DISMISSED = 80
    /** Warm enough for sun cream and swimwear: the warmest day's top. */
    const val WARM_C = 23
    /** Cold enough for coats: the coldest night, or a top this low. */
    const val COLD_MIN_C = 7
    const val COLD_MAX_C = 12
    /** A day counts as rainy at this chance or more. */
    const val RAIN_CHANCE = 50

    private val UK_ZONE = "Europe/London"

    /** Where a GHIC card is the card to take (the EU and Switzerland). */
    private val GHIC_ZONES = setOf(
        "Europe/Lisbon", "Atlantic/Madeira", "Atlantic/Azores", "Europe/Madrid", "Atlantic/Canary", "Africa/Ceuta", "Europe/Paris",
        "Europe/Berlin", "Europe/Vienna", "Europe/Prague", "Europe/Budapest", "Europe/Warsaw", "Europe/Rome", "Europe/Athens",
        "Asia/Nicosia", "Asia/Famagusta", "Europe/Malta", "Europe/Zagreb", "Europe/Amsterdam", "Europe/Brussels", "Europe/Zurich",
        "Europe/Luxembourg", "Europe/Copenhagen", "Europe/Stockholm", "Europe/Helsinki", "Europe/Tallinn", "Europe/Riga",
        "Europe/Vilnius", "Europe/Bucharest", "Europe/Sofia", "Europe/Ljubljana", "Europe/Bratislava",
    )

    /** Where UK plugs fit, so no adapters. */
    private val UK_PLUG_ZONES = setOf(
        UK_ZONE, "Europe/Dublin", "Europe/Malta", "Asia/Nicosia", "Asia/Famagusta", "Asia/Dubai", "Asia/Qatar", "Asia/Hong_Kong",
        "Asia/Singapore", "Asia/Kuala_Lumpur", "Africa/Lagos", "Africa/Accra", "Africa/Nairobi",
    )

    /** What the rules know about one trip. */
    private class Facts(
        val trip: TripItem, val days: Int, val place: NamedZone?, val adults: Int?, val people: Int?, val kids: Boolean,
        val warm: Boolean, val cold: Boolean, val rainy: Int, val forecastDays: Int, val minC: Int?, val maxC: Int?,
        val flight: TripBooking?,
    ) {
        val abroad: Boolean get() = place != null && place.id != UK_ZONE
    }

    /** One thing the rules may suggest: [match] are the words (any one, whole words) that mean it's on the lists already. */
    private class Idea(
        val key: String, val list: TripList, val text: String, val group: String, val match: List<String>,
        val who: TripWho = TripWho.EVERYONE, val qty: Int? = null, val remind: TripRemind? = null,
    )

    /** Lowercase words, a plural's "s" dropped ("chargers" → "charger"), so "Phone chargers" covers "charger". */
    internal fun words(text: String): List<String> =
        text.lowercase().split(' ', '/', ',', '&', '+', '(', ')', ':', ';', '.').map { w ->
            val t = w.trim { !it.isLetterOrDigit() && it != '-' }
            if (t.length > 3 && t.endsWith("s") && !t.endsWith("ss")) t.dropLast(1) else t
        }.filter { it.isNotEmpty() }

    /** Whether [text] has one of [match] (each a word or a few words, matched whole and in order). */
    internal fun covers(text: String, match: List<String>): Boolean {
        val ws = words(text)
        return match.any { m ->
            val mw = words(m)
            mw.isNotEmpty() && (0..ws.size - mw.size).any { i -> mw.indices.all { j -> ws[i + j] == mw[j] } }
        }
    }

    private fun facts(trip: TripItem, about: AboutYou, forecast: WeatherForecast): Facts {
        val place = TripZoneRules.placeOf(trip.name)
        val going = trip.going?.takeIf { it.isNotEmpty() }
        val adults = going?.let { g -> listOf(TripGoer.ME, TripGoer.PARTNER).count { it in g } }
        val kids = going?.contains(TripGoer.KIDS) == true
        val people = when {
            going == null -> null
            kids && about.children.isEmpty() -> null
            else -> (adults ?: 0) + if (kids) about.children.size else 0
        }
        val days = if (TripWeatherRules.fresh(forecast, place)) forecast.days.filter { it.epochDay in trip.startDay..trip.endDay } else emptyList()
        val maxC = days.maxOfOrNull { it.maxC }
        val minC = days.minOfOrNull { it.minC }
        val flight = trip.bookings.firstOrNull { it.kind == BookingKind.FLIGHT && it.day <= trip.startDay } ?: trip.bookings.firstOrNull { it.kind == BookingKind.FLIGHT }
        return Facts(
            trip, trip.days, place, adults, people, kids,
            warm = maxC != null && maxC >= WARM_C,
            cold = (minC != null && minC <= COLD_MIN_C) || (maxC != null && maxC <= COLD_MAX_C),
            rainy = days.count { it.rainChance >= RAIN_CHANCE }, forecastDays = days.size, minC = minC, maxC = maxC, flight = flight,
        )
    }

    private fun each(n: Int?): Int? = n?.takeIf { it > 1 }

    /** Every idea that fits the trip, the most useful first on each list. */
    private fun ideas(f: Facts): List<Idea> = buildList {
        val nights = f.days >= 2
        // To pack: documents first, then what a trip of this length and weather needs.
        if (f.abroad) add(Idea("pack:passports", TripList.PACK, "Passports", "Documents", listOf("passport"), qty = each(f.people)))
        if (f.abroad && f.place?.id in GHIC_ZONES) add(Idea("pack:ghic", TripList.PACK, "GHIC cards", "Documents", listOf("ghic", "ehic"), qty = each(f.people)))
        if (f.flight != null) add(Idea("pack:boarding", TripList.PACK, "Boarding passes", "Documents", listOf("boarding")))
        if (f.abroad) add(Idea("pack:insurance", TripList.PACK, "Travel insurance", "Documents", listOf("insurance")))
        if (f.warm) add(Idea("pack:swim", TripList.PACK, "Swimwear", "Clothes", listOf("swimwear", "swimsuit", "swimming", "trunks", "swim"), qty = each(f.people)))
        if (f.cold) add(Idea("pack:coats", TripList.PACK, "Warm coats", "Clothes", listOf("coat", "jacket", "raincoat"), qty = each(f.people)))
        if (f.rainy > 0) add(Idea("pack:umbrella", TripList.PACK, "Travel umbrella", "Clothes", listOf("umbrella", "brolly")))
        add(Idea("pack:chargers", TripList.PACK, "Phone chargers", "Tech", listOf("charger"), qty = each(f.adults)))
        add(Idea("pack:medicines", TripList.PACK, "Your medicines", "Medicines", listOf("medicine", "prescription", "inhaler", "pill", "tablets"), who = TripWho.ME))
        add(Idea("pack:teeth", TripList.PACK, "Toothbrushes and paste", "Toiletries", listOf("toothbrush", "toothpaste")))
        if (nights) add(Idea("pack:underwear", TripList.PACK, "Underwear and socks", "Clothes", listOf("underwear", "pant", "sock", "boxer", "knicker"), qty = f.days.coerceAtMost(14)))
        if (nights) add(Idea("pack:pyjamas", TripList.PACK, "Pyjamas", "Clothes", listOf("pyjama", "pj", "nightie", "nightwear"), qty = each(f.people)))
        if (f.warm) add(Idea("pack:sunglasses", TripList.PACK, "Sunglasses", "Clothes", listOf("sunglasse", "sunglass", "shades")))
        if (f.kids) add(Idea("pack:comfort", TripList.PACK, "Kids comfort toys", "Kids", listOf("toy", "teddy", "comfort"), who = TripWho.KIDS))
        if (f.kids) add(Idea("pack:kids-tech", TripList.PACK, "Kids tablets and headphones", "Kids", listOf("tablet", "ipad", "headphone"), who = TripWho.KIDS))
        if (f.days >= 5) add(Idea("pack:laundry", TripList.PACK, "Laundry bag", "Clothes", listOf("laundry")))
        add(Idea("pack:powerbank", TripList.PACK, "Power bank", "Tech", listOf("power bank", "battery pack")))
        // To buy.
        if (f.warm) add(Idea("buy:suncream", TripList.BUY, "Sun cream", "Pharmacy", listOf("sun cream", "suncream", "sunscreen", "spf", "sun lotion")))
        if (f.abroad && f.place?.id !in UK_PLUG_ZONES) add(Idea("buy:adapters", TripList.BUY, "Plug adapters", "Online", listOf("adapter", "adaptor"), qty = 2))
        if (f.flight != null) add(Idea("buy:minis", TripList.BUY, "Travel-size toiletries", "Pharmacy", listOf("travel-size", "travel size", "mini", "miniature")))
        if (f.kids) add(Idea("buy:calpol", TripList.BUY, "Calpol sachets", "Pharmacy", listOf("calpol", "paracetamol", "ibuprofen", "nurofen")))
        if (f.kids || f.days == 1) add(Idea("buy:snacks", TripList.BUY, "Snacks for the journey", "Supermarket", listOf("snack")))
        add(Idea("buy:plasters", TripList.BUY, "Plasters", "Pharmacy", listOf("plaster", "first-aid", "first aid")))
        // Before we leave, with a reminder where one helps.
        val f0 = f.flight
        if (f0 != null) {
            val r = f0.minute?.takeIf { f0.day == f.trip.startDay }?.let { TripRemind(-1, it) }
            add(Idea("leave:checkin", TripList.LEAVE, "Check in online", "On the day", listOf("check in", "check-in", "checkin"), remind = r))
        }
        if (f.abroad || f0 != null) add(Idea("leave:charge", TripList.LEAVE, "Charge everything", "On the day", listOf("charge"), remind = TripRemind(-1, TripListRules.NIGHT_BEFORE_MIN)))
        if (nights) add(Idea("leave:bins", TripList.LEAVE, "Put the bins out", "The house", listOf("bin", "rubbish", "recycling")))
        if (f.days >= 3) add(Idea("leave:heating", TripList.LEAVE, "Heating to holiday mode", "The house", listOf("heating", "thermostat", "boiler")))
        if (f.days >= 4) add(Idea("leave:fridge", TripList.LEAVE, "Empty the fridge", "The house", listOf("fridge")))
        if (f.days >= 4) add(Idea("leave:plants", TripList.LEAVE, "Water the plants", "The house", listOf("plant", "garden")))
        add(Idea("leave:bags", TripList.LEAVE, "Bags by the door", "On the day", listOf("bag", "suitcase", "luggage"), remind = TripRemind(-1, TripListRules.NIGHT_BEFORE_MIN + 30)))
        val lockAt = f.trip.leaveMinute?.let { (it - 15).coerceAtLeast(0) }
        add(Idea("leave:lock", TripList.LEAVE, "Lock windows and back door", "The house", listOf("lock", "window"), remind = lockAt?.let { TripRemind(0, it) }))
    }

    private fun chipLabel(e: TripListEntry, startDay: Long): String =
        e.text + TripListRules.qtyLabel(e.qty).let { if (it.isEmpty()) "" else " $it" } +
            (e.remind?.let { " · " + TripListRules.remindLabel(startDay, it).removePrefix("Remind ") } ?: "")

    private fun spoken(e: TripListEntry, startDay: Long): String =
        listOfNotNull(e.text, e.qty?.let { "times $it" }, e.remind?.let { TripListRules.remindLabel(startDay, it).replaceFirstChar { c -> c.lowercaseChar() } })
            .joinToString(", ")

    private fun chip(key: String, e: TripListEntry, startDay: Long) = TripSuggestChip(key, chipLabel(e, startDay), e, spoken(e, startDay))

    /** "last:pack:sun-hat": a last-trip chip's key, from its words. */
    private fun lastKey(list: TripList, text: String): String = "last:${list.wire}:" + words(text).joinToString("-").take(40)

    /** The last trip before [trip] with anything on its lists (as Start from last trip finds it), or null. */
    private fun lastTrip(trip: TripItem, trips: List<TripItem>, all: List<TripListItem>): TripItem? {
        val withLists = all.map { it.tripId }.toSet()
        return trips.filter { it.id != trip.id && it.id in withLists && it.startDay <= trip.startDay }
            .maxWithOrNull(compareBy<TripItem> { it.startDay }.thenBy { it.addedAtMs })
    }

    private fun lead(list: TripList, f: Facts, fromLast: TripItem?): String {
        val weather = if (f.minC != null && f.maxC != null && f.place != null && list != TripList.LEAVE) {
            val range = if (f.minC == f.maxC) "${f.maxC}°" else "${f.minC}–${f.maxC}°"
            "It's $range in ${f.place.label}" + (if (f.rainy > 0) " with rain likely on ${f.rainy} of ${f.forecastDays} days" else "") + ". "
        } else ""
        val days = if (f.days == 1) "a day" else "${f.days} days"
        val who = when {
            f.trip.going == setOf(TripGoer.ME) -> " on your own"
            f.people != null && f.people > 1 -> " with ${f.people} people"
            f.kids -> " with the kids"
            else -> ""
        }
        val base = when (list) {
            TripList.PACK -> "For $days$who, worth packing:"
            TripList.BUY -> "Not on the list yet, worth getting:"
            TripList.LEAVE -> f.trip.leaveMinute?.let { "You leave home at ${LocalClock.formatMinute(it)}. Worth doing before you go:" }
                ?: "Worth doing before you go:"
        }
        val last = fromLast?.let { " Some are from your ${TripListRules.tripLabel(it)}." }.orEmpty()
        return weather + base + last
    }

    /**
     * Each tab's card for [trip] (To buy, To pack, Before we leave; a list with nothing to suggest has none): the ideas
     * that fit it, then up to [MAX_FROM_LAST] things from the last trip's lists, leaving out anything already on this
     * trip's lists (ticked or not; for To buy, To pack's too) and anything put away with Not now. Empty once the trip is
     * over. [forecast] is the trip forecast the server writes ([TripWeatherRules]); used only when it is for this place.
     */
    fun suggest(
        trip: TripItem, all: List<TripListItem>, today: Long, trips: List<TripItem> = emptyList(), about: AboutYou = AboutYou.EMPTY,
        forecast: WeatherForecast = WeatherForecast.EMPTY,
    ): List<TripSuggestion> {
        if (today > trip.endDay) return emptyList()
        val f = facts(trip, about, forecast)
        val mine = all.filter { it.tripId == trip.id }
        val no = trip.suggestNo
        val last = if (mine.isEmpty()) null else lastTrip(trip, trips, all)
        val ideas = ideas(f)
        return TripList.entries.mapNotNull { list ->
            val against = mine.filter { it.list == list || (list == TripList.BUY && it.list == TripList.PACK) }
            fun onLists(text: String, match: List<String>) = against.any { covers(it.text, match) || covers(text, words(it.text).let { w -> listOf(w.joinToString(" ")) }) }
            val chips = ideas.filter { it.list == list && it.key !in no && !onLists(it.text, it.match) }.map { i ->
                chip(i.key, TripListEntry(i.list, i.text, i.group, i.qty, i.who, i.remind), trip.startDay)
            }.toMutableList()
            var fromLast: TripItem? = null
            if (last != null) {
                val extra = all.filter { it.tripId == last.id && it.list == list }
                    .filter { li -> val k = lastKey(list, li.text); k !in no && chips.none { c -> c.key == k || covers(c.entry.text, listOf(li.text)) } }
                    .filter { li -> !onLists(li.text, listOf(li.text)) }
                    .distinctBy { lastKey(list, it.text) }
                    .take(MAX_FROM_LAST)
                    .map { li -> chip(lastKey(list, li.text), TripListEntry(li.list, li.text, li.group, li.qty, li.who, li.remind), trip.startDay) }
                if (extra.isNotEmpty()) fromLast = last
                // The last trip's things go in after the documents, so a full card still shows some of them.
                val docs = chips.count { it.entry.group == "Documents" }
                chips.addAll(docs, extra)
            }
            val shown = chips.take(MAX_CHIPS)
            if (shown.isEmpty()) null else TripSuggestion(list, lead(list, f, fromLast?.takeIf { shown.any { it.key.startsWith("last:") } }), shown)
        }
    }

    /** "Added Swimwear × 4 to To pack", "Added 3 things to Before we leave". */
    fun addedLine(entries: List<TripListEntry>, list: TripList): String =
        if (entries.size == 1) "Added ${entries[0].text}" + TripListRules.qtyLabel(entries[0].qty).let { if (it.isEmpty()) "" else " $it" } + " to ${list.label}"
        else "Added ${TripListRules.things(entries.size)} to ${list.label}"

    /** The trip's put-away keys after putting [keys] away too (newest kept when there are too many). */
    fun dismissed(before: Set<String>, keys: Collection<String>): List<String> =
        (before.toList() + keys.filter { it.isNotBlank() && it !in before }).takeLast(MAX_DISMISSED)
}
