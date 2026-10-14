package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Car and parking, slice 6 (build plan V1, Meka approved 2026-10-12): the cheapest fuel near him when the Car pane opens.
 *
 * Prices come from the government's **Fuel Finder** open data (every UK forecourt must publish its prices within 30
 * minutes of a change; the old CMA retailer feeds were withdrawn on 1 May 2026). Navilife's server holds the free Fuel
 * Finder key (Needs Meka #34) and a copy of the forecourts and prices; the phone sends only a point rounded to about
 * 1 km ([HereRules.round]) and the fuel, and gets the cheapest forecourts within [RADIUS_KM] back. Nothing is stored or
 * logged on the server, and nothing is synced: the answer lives in memory while the pane is open.
 *
 * Each car takes one fuel ([CarFields.FUEL], unleaded E10 unless he picks another); with two cars on different fuels
 * the pane shows a block per fuel. Insight only: Navilife never buys or pays for anything.
 */
enum class FuelKind(val code: String, val label: String, val short: String) {
    E10("E10", "Unleaded (E10)", "unleaded"),
    E5("E5", "Super unleaded (E5)", "super unleaded"),
    B7("B7", "Diesel", "diesel"),
    SDV("SDV", "Premium diesel", "premium diesel"),
    ;

    companion object {
        val DEFAULT = E10

        /** Index access for Swift. */
        val all: List<FuelKind> get() = entries

        /** A stored code back to its kind; null for anything else (the default then applies). */
        fun of(code: String?): FuelKind? = code?.trim()?.uppercase()?.let { c -> entries.firstOrNull { it.code == c } }
    }
}

/** One forecourt as the server keeps it: where it is and its latest price per fuel. */
data class FuelSite(
    val id: String,
    val name: String,
    val brand: String?,
    val postcode: String?,
    val lat: Double,
    val lon: Double,
    /** Pence a litre and when the forecourt last changed it (ms; null when it didn't say). */
    val prices: Map<FuelKind, Pair<Double, Long?>>,
)

/** One forecourt near him with the price of the fuel asked for. */
data class FuelStation(
    val name: String,
    val brand: String?,
    val postcode: String?,
    val lat: Double,
    val lon: Double,
    /** Pence a litre, e.g. 139.9. */
    val pence: Double,
    val updatedMs: Long?,
    /** From the rounded point (so within about a kilometre of the truth). */
    val distanceM: Double,
)

data class FuelPriceRow(
    /** "Tesco · Oakfield Road" — the forecourt's own name, its brand first when the name doesn't carry it. */
    val title: String,
    /** "139.9p". */
    val price: String,
    /** "1.2 km · AB1 2CD · updated today 09:10". */
    val line: String,
    /** The cheapest in its block (lit). */
    val cheapest: Boolean,
    /** Its price is over [FuelRules.STALE_DAYS] old ("· price from Mon 12 Oct"). */
    val stale: Boolean,
    val lat: Double,
    val lon: Double,
)

data class FuelBlock(
    val fuel: FuelKind,
    /** "UNLEADED (E10)" or, with two cars, "DIESEL · THE GOLF". */
    val heading: String,
    /** "Cheapest within 8 km · Tesco 139.9p · 1.2 km" or "No diesel prices within 8 km". */
    val summary: String,
    /** "A 50-litre fill there is £2.40 less than at Shell (3.1 km)", or null when the spread is small. */
    val saving: String?,
    val rows: List<FuelPriceRow>,
) {
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): FuelPriceRow = rows[index]
}

/**
 * The pane's fuel section. [state]: [FuelRules.OK] with [blocks], or one of the other states with only [line] to show.
 */
data class FuelView(
    val state: String,
    /** Under the heading: where prices come from and how fresh, or what's missing ("Allow location to …"). */
    val line: String,
    val blocks: List<FuelBlock>,
) {
    val blockCount: Int get() = blocks.size
    fun blockAt(index: Int): FuelBlock = blocks[index]

    companion object {
        val IDLE = FuelView(FuelRules.IDLE, FuelRules.IDLE_LINE, emptyList())
    }
}

object FuelRules {
    const val OK = "ok"
    /** Not looked yet (the pane hasn't had a location). */
    const val IDLE = "idle"
    const val LOOKING = "looking"
    /** The server has no Fuel Finder key yet (Needs Meka #34), or is older than this slice. */
    const val OFF = "off"
    /** The server is fetching the forecourts for the first time: look again in a minute. */
    const val LOADING = "loading"
    const val FAILED = "failed"
    /** No location: he hasn't allowed it, or the phone has no fix. */
    const val NO_LOCATION = "no_location"
    const val OFFLINE = "offline"

    /** Forecourts this far from the rounded point count as nearby. */
    const val RADIUS_KM = 8.0
    /** Rows per fuel. */
    const val MAX_ROWS = 5
    /** A price older than this many days is marked. */
    const val STALE_DAYS = 3
    /** The fill the saving line is worked out on. */
    const val FILL_LITRES = 50
    /** A saving under this (pence on the fill) isn't worth a line. */
    const val MIN_SAVING_PENCE = 100

    const val IDLE_LINE = "Cheapest fuel near you · from the government's Fuel Finder"
    const val LOOKING_LINE = "Looking up fuel prices near you…"
    const val OFF_LINE = "Fuel prices · off until Navilife's server has the Fuel Finder key (free; see Needs Meka)"
    const val LOADING_LINE = "Fetching every forecourt's prices for the first time · look again in a minute"
    const val FAILED_LINE = "Fuel prices aren't available right now · try again later"
    const val NO_LOCATION_LINE = "Allow location to see the cheapest fuel near you · only a point rounded to about 1 km is sent"
    const val OFFLINE_LINE = "Fuel prices need a connection · try again when you're online"
    const val SOURCE = "Fuel Finder"

    /**
     * A Fuel Finder fuel type to Navilife's kind: "E10", "E5", "B7", "B7_STANDARD" → diesel, "B7_PREMIUM", "SDV", "super
     * diesel" → premium diesel. Null for anything else (B10, HVO, LPG…), which is left out.
     */
    fun kindOf(apiType: String?): FuelKind? {
        val t = apiType?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        return when {
            t == "E10" || t == "UNLEADED" || t == "PETROLE10" -> FuelKind.E10
            t == "E5" || t == "SUPERUNLEADED" || t == "PETROLE5" -> FuelKind.E5
            t == "SDV" || t.contains("PREMIUM") && (t.startsWith("B7") || t.contains("DIESEL")) || t == "SUPERDIESEL" -> FuelKind.SDV
            t == "B7" || t == "B7STANDARD" || t == "DIESEL" || t == "B7DIESEL" -> FuelKind.B7
            else -> null
        }
    }

    /** A published price in pence a litre: pounds (1.399) become pence; anything outside 50p–£4 is a typo and dropped. */
    fun pence(raw: Double?): Double? {
        val v = raw?.takeIf { !it.isNaN() && !it.isInfinite() } ?: return null
        val p = if (v < 20.0) v * 100.0 else v
        return ((p * 10).roundToLong() / 10.0).takeIf { it in 50.0..400.0 }
    }

    /**
     * The cheapest [max] forecourts selling [fuel] within [radiusKm] of [point], cheapest first (nearer first on a tie).
     * The server's pick: pure so it is tested here.
     */
    fun nearest(sites: List<FuelSite>, point: Coord, fuel: FuelKind, radiusKm: Double = RADIUS_KM, max: Int = MAX_ROWS): List<FuelStation> =
        sites.asSequence()
            .mapNotNull { s ->
                val (p, at) = s.prices[fuel] ?: return@mapNotNull null
                if (!ErrandPlaceRules.valid(s.lat, s.lon)) return@mapNotNull null
                val km = HereRules.distanceKm(point, Coord(s.lat, s.lon))
                if (km > radiusKm) null else FuelStation(s.name, s.brand, s.postcode, s.lat, s.lon, p, at, km * 1000.0)
            }
            .sortedWith(compareBy<FuelStation> { it.pence }.thenBy { it.distanceM })
            .take(max)
            .toList()

    /** "139.9p". */
    fun priceText(pence: Double): String {
        val tenths = (pence * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10}p"
    }

    /** "£2.40" for a whole number of pence. */
    fun pounds(pence: Long): String = "£${pence / 100}.${(pence % 100).toString().padStart(2, '0')}"

    /** The forecourt's title: its name, with the brand first when the name doesn't already say it. */
    fun title(s: FuelStation): String {
        val name = s.name.trim().ifEmpty { s.brand?.trim().orEmpty() }.ifEmpty { "Forecourt" }
        val brand = s.brand?.trim()?.takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
        return if (brand != null) "$brand · $name" else name
    }

    /** "updated today 09:10", "updated yesterday 18:02", "price from Mon 12 Oct". */
    fun updatedText(atMs: Long?, nowMs: Long, cal: LocalCalendar): String? {
        atMs ?: return null
        val days = cal.epochDayOf(nowMs) - cal.epochDayOf(atMs)
        val hhmm = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        return when {
            days <= 0L -> "updated today $hhmm"
            days == 1L -> "updated yesterday $hhmm"
            days < STALE_DAYS -> "updated ${CivilDate.shortLabel(cal.epochDayOf(atMs))}"
            else -> "price from ${CivilDate.shortLabel(cal.epochDayOf(atMs))}"
        }
    }

    fun isStale(atMs: Long?, nowMs: Long, cal: LocalCalendar): Boolean =
        atMs != null && cal.epochDayOf(nowMs) - cal.epochDayOf(atMs) >= STALE_DAYS

    /** One fuel's block from the server's answer; [carTitles] are the cars on that fuel (named only with two or more cars). */
    fun block(fuel: FuelKind, stations: List<FuelStation>, carTitles: List<String>, nowMs: Long, cal: LocalCalendar): FuelBlock {
        val heading = (listOf(fuel.label) + carTitles).joinToString(" · ").uppercase()
        val sorted = stations.sortedWith(compareBy<FuelStation> { it.pence }.thenBy { it.distanceM }).take(MAX_ROWS)
        val best = sorted.firstOrNull()
            ?: return FuelBlock(fuel, heading, "No ${fuel.short} prices within ${RADIUS_KM.toInt()} km", null, emptyList())
        val rows = sorted.mapIndexed { i, s ->
            val line = listOfNotNull(ParkingRules.distanceText(s.distanceM), s.postcode?.trim()?.takeIf { it.isNotEmpty() }, updatedText(s.updatedMs, nowMs, cal))
                .joinToString(" · ")
            FuelPriceRow(title(s), priceText(s.pence), line, i == 0, isStale(s.updatedMs, nowMs, cal), s.lat, s.lon)
        }
        val summary = "Cheapest within ${RADIUS_KM.toInt()} km · ${best.brand?.takeIf { it.isNotBlank() } ?: title(best)} ${priceText(best.pence)} · ${ParkingRules.distanceText(best.distanceM)}"
        return FuelBlock(fuel, heading, summary, saving(sorted), rows)
    }

    /**
     * "A 50-litre fill there is £2.40 less than at Shell (3.1 km)": the cheapest against the dearest of the nearby ones
     * shown; null when the difference on a fill is under [MIN_SAVING_PENCE].
     */
    fun saving(stations: List<FuelStation>): String? {
        if (stations.size < 2) return null
        val best = stations.minByOrNull { it.pence } ?: return null
        val worst = stations.maxByOrNull { it.pence } ?: return null
        val diff = ((worst.pence - best.pence) * FILL_LITRES).roundToLong()
        if (diff < MIN_SAVING_PENCE) return null
        val who = worst.brand?.takeIf { it.isNotBlank() } ?: title(worst)
        return "A $FILL_LITRES-litre fill there is ${pounds(diff)} less than at $who (${ParkingRules.distanceText(worst.distanceM)})"
    }

    /**
     * The fuels the live cars take, each with the cars on it (titles shown only when there's more than one car), in the
     * cars' order. One car on the default: one block, no names.
     */
    fun fuelsFor(cars: CarsView): List<Pair<FuelKind, List<String>>> {
        val many = cars.cars.size > 1
        return cars.cars.groupBy { it.fuel }.map { (k, cs) -> k to if (many) cs.map { it.title } else emptyList() }
    }

    /** The section once every fuel has answered ([answers]: each fuel's server state and stations). */
    fun view(answers: List<Triple<FuelKind, List<String>, Pair<String, List<FuelStation>>>>, nowMs: Long, cal: LocalCalendar): FuelView {
        val states = answers.map { it.third.first }
        if (answers.isEmpty()) return FuelView.IDLE
        if (states.any { it == OK }) {
            val blocks = answers.filter { it.third.first == OK }.map { (fuel, cars, a) -> block(fuel, a.second, cars, nowMs, cal) }
            return FuelView(OK, "Prices from the government's $SOURCE · tap one for directions", blocks)
        }
        val first = states.first()
        return FuelView(first, lineFor(first), emptyList())
    }

    fun lineFor(state: String): String = when (state) {
        OFF -> OFF_LINE
        LOADING -> LOADING_LINE
        NO_LOCATION -> NO_LOCATION_LINE
        OFFLINE -> OFFLINE_LINE
        LOOKING -> LOOKING_LINE
        IDLE -> IDLE_LINE
        else -> FAILED_LINE
    }

    /** A maps link for walking or driving there, opened only on his tap. */
    fun mapsUrl(row: FuelPriceRow): String = "https://www.google.com/maps/dir/?api=1&destination=${row.lat},${row.lon}&travelmode=driving"
}
