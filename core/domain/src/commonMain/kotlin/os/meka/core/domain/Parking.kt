package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Car and parking, slice 1 (build plan V1, "Car and parking"; non-AI, pure): "Where I parked" and Find my car.
 *
 * On a tap ("I've parked"), the Fold takes a few precise fixes, keeps the best one ([ParkingRules.bestFix]) and saves
 * it as the car's spot, with an optional label typed or said ("level 3 row F" → "Level 3 · Row F"). The spot is kept in
 * the phone's own storage only: never synced and never sent (the item's "location stays on the phone"). With two cars
 * each keeps its own spot; a tap save asks which car when there is more than one. Find my car shows the distance and
 * an arrow towards the spot that follows the phone's heading ("84 m · ahead left"), says honestly how sure the fixes
 * are ("±8 m"), and says "Within 15 m · look around" (one buzz) when close; Open in Maps hands a walking route to his
 * maps app only when he taps it. Drove off clears the spot (with Undo).
 *
 * The Car tile on Ask's Life grid says where the car is while one is parked, else the next of the cars' dates ([carLine]).
 */

/** One car's saved spot, on this phone only. [label]: "Level 3 · Row F", or null. */
data class ParkedSpot(
    val carId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val savedAtMs: Long,
    val label: String? = null,
)

/** One location fix as the phone gave it ([accuracyM] null when the phone gave none). */
data class LocationFix(val latitude: Double, val longitude: Double, val accuracyM: Double?, val atMs: Long)

/** What saving did: the spots now kept, the line the pane shows and whether anything was saved. */
data class ParkSaved(val spots: Map<String, ParkedSpot>, val line: String, val saved: Boolean)

/** A parked car as the Car pane shows it. */
data class ParkedRow(
    val carId: String,
    /** "the Golf", "Car 2", or "Car" with one car. */
    val title: String,
    val label: String?,
    /** "Parked 14:05 · 2 h ago · ±8 m". */
    val line: String,
    val spot: ParkedSpot,
)

/** Find my car right now: the line, the arrow's turn from straight ahead (null without a heading) and whether he's there. */
data class FindView(
    /** "84 m · ahead left", "84 m · north-east", "Within 15 m · look around", "Finding where you are…". */
    val line: String,
    /** Degrees clockwise from the top of the phone; null when the phone has no heading (or no fix yet). */
    val arrowDeg: Double?,
    val close: Boolean,
    /** "You ±6 m · the car ±8 m", or null before a fix. */
    val accuracy: String?,
    /** Metres to the car; null before a fix. */
    val distanceM: Double?,
)

object ParkingRules {
    /** A fix less certain than this isn't saved (deep underground with no fix at all): the label leads instead. */
    const val MAX_ACCURACY_M = 250.0
    /** Over this the spot is rough, and the pane says so ("rough · add the bay"). */
    const val ROUGH_M = 30.0
    /** How close counts as "there": one buzz and "look around". */
    const val CLOSE_M = 15.0
    /** The best fix is picked from the last this-long of fixes (GPS settles over a few seconds). */
    const val BEST_FIX_WINDOW_MS = 30_000L
    /** How long the Fold listens for fixes on "I've parked" before saving the best one. */
    const val SETTLE_MS = 6_000L
    /** How long the Fold waits for a first fix before giving up. */
    const val FIX_TIMEOUT_MS = 20_000L
    const val MAX_LABEL = 40

    const val TITLE = "Car"
    const val ROW_LINE = "Where you parked, and each car's MOT, tax and service"
    const val PARK = "I've parked here"
    const val FIND = "Find my car"
    const val DROVE_OFF = "Drove off"
    const val MAPS = "Walk there in Maps"
    const val LABEL_HINT = "The bay, e.g. level 3 row F"
    const val SAVING = "Finding where you are…"
    const val FINDING = "Finding where you are…"
    const val NO_FIX = "Couldn't find where you are · try again, or type the bay"
    const val NO_PERMISSION = "Needs precise location · tap to allow"
    const val LOCATION_OFF = "Location is off on the phone · turn it on to save the spot"
    const val NOT_PARKED = "Not parked · tap “I've parked here” when you leave the car"
    const val WHICH_CAR = "Which car?"
    const val PRIVACY = "Where you park stays on this phone: it's never synced or sent. Walk there in Maps hands the spot to your maps app only when you tap it."
    const val DATES_LINE = "MOT, tax, insurance and service · in Lists → Renewals"
    const val DATES_ACTION = "Dates ›"
    const val COMPASS_HINT = "Hold the phone flat and point it ahead"

    /** Talk: "where did I park?", "find my car". */
    const val OPENING = "Here's where you parked."
    const val OPENED = "Opened Find my car."

    // ---- the spots on the phone ----

    fun encode(spots: Collection<ParkedSpot>): String =
        spots.sortedBy { it.carId }.joinToString("\n") { s ->
            listOf(clean(s.carId), s.latitude, s.longitude, s.accuracyM, s.savedAtMs, clean(s.label.orEmpty())).joinToString("\t")
        }

    fun decode(text: String?): Map<String, ParkedSpot> =
        text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 6 || f[0].isBlank()) return@mapNotNull null
            val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
            if (!ErrandPlaceRules.valid(lat, lon)) return@mapNotNull null
            ParkedSpot(f[0], lat, lon, f[3].toDoubleOrNull() ?: MAX_ACCURACY_M, f[4].toLongOrNull() ?: 0L, f[5].takeIf { it.isNotBlank() })
        }.associateBy { it.carId }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    /**
     * The fix to save from [fixes]: of those in the last [BEST_FIX_WINDOW_MS] before [nowMs] with a real point, the most
     * certain (a fix with no accuracy counts as least certain); a tie goes to the newer. Null when none.
     */
    fun bestFix(fixes: List<LocationFix>, nowMs: Long): LocationFix? =
        fixes.filter { ErrandPlaceRules.valid(it.latitude, it.longitude) && nowMs - it.atMs in 0..BEST_FIX_WINDOW_MS }
            .sortedWith(compareBy<LocationFix> { it.accuracyM ?: Double.MAX_VALUE }.thenByDescending { it.atMs })
            .firstOrNull()

    /**
     * Saves [carId]'s spot from [fix] (replacing what was kept for it; the label typed for the old spot doesn't carry
     * over). Refused with no fix, or one less certain than [MAX_ACCURACY_M].
     */
    fun save(spots: Map<String, ParkedSpot>, carId: String, title: String, fix: LocationFix?, nowMs: Long): ParkSaved {
        if (fix == null || !ErrandPlaceRules.valid(fix.latitude, fix.longitude)) return ParkSaved(spots, NO_FIX, false)
        val acc = fix.accuracyM ?: return ParkSaved(spots, NO_FIX, false)
        if (acc > MAX_ACCURACY_M) return ParkSaved(spots, "Not sure enough where you are (±${metres(acc)}) · type the bay instead", false)
        val spot = ParkedSpot(carId, fix.latitude, fix.longitude, acc, nowMs)
        val rough = if (acc > ROUGH_M) " · rough, add the bay" else ""
        return ParkSaved(spots + (carId to spot), "Saved where $title is · ±${metres(acc)}$rough", true)
    }

    /** Drove off: [carId]'s spot is forgotten (the pane keeps the old one for Undo). */
    fun droveOff(spots: Map<String, ParkedSpot>, carId: String): Map<String, ParkedSpot> = spots - carId

    /** The label typed or said for [carId]'s spot; blank clears it. Unchanged when the car isn't parked. */
    fun withLabel(spots: Map<String, ParkedSpot>, carId: String, text: String?): Map<String, ParkedSpot> {
        val s = spots[carId] ?: return spots
        return spots + (carId to s.copy(label = cleanLabel(text)))
    }

    private val LABEL_WORDS = mapOf(
        "level" to "Level", "lvl" to "Level", "floor" to "Floor", "deck" to "Deck", "storey" to "Floor", "story" to "Floor",
        "row" to "Row", "bay" to "Bay", "space" to "Space", "zone" to "Zone", "area" to "Area", "pillar" to "Pillar",
        "column" to "Column", "aisle" to "Aisle", "section" to "Section", "block" to "Block",
        "stand" to "Stand", "gate" to "Gate", "entrance" to "Entrance", "exit" to "Exit",
    )

    /**
     * A bay as he'd read it off the sign: "level 3 row f" → "Level 3 · Row F", "level 2, blue zone" → "Level 2 · Blue
     * zone", "l3 f12" → "L3 F12", "second floor near the lifts" → "Second floor · Near the lifts". Short codes go upper
     * case; at most [MAX_LABEL] characters; blank is null. Read by hand, no regex.
     */
    fun cleanLabel(text: String?): String? {
        val raw = text.orEmpty().replace('\t', ' ').replace('\n', ' ').trim()
        if (raw.isEmpty()) return null
        val parts = mutableListOf<String>()
        // Commas and " · " split what he gave into parts first; inside each part, a keyword starts a new part.
        for (chunk in raw.split(',', ';', '·').map { it.trim() }.filter { it.isNotEmpty() }) {
            val ws = chunk.split(' ').filter { it.isNotBlank() }
            var pending = mutableListOf<String>()
            fun flush() { if (pending.isNotEmpty()) { parts += capitalise(pending.joinToString(" ") { codeOrWord(it) }); pending = mutableListOf() } }
            var i = 0
            while (i < ws.size) {
                val w = ws[i]
                val key = LABEL_WORDS[w.lowercase()]
                val next = ws.getOrNull(i + 1)
                val nextIsWord = next != null && LABEL_WORDS[next.lowercase()] == null
                when {
                    key == null -> { pending += w; i++ }
                    // "blue zone", "second floor": the keyword closes the words before it.
                    pending.isNotEmpty() && (!nextIsWord || !looksLikeCode(next!!)) -> {
                        pending += w.lowercase(); flush(); i++
                    }
                    // "level 3", "row f": the keyword and its code.
                    nextIsWord && looksLikeCode(next!!) -> { flush(); parts += "$key ${next.uppercase()}"; i += 2 }
                    else -> { flush(); pending += w; i++ }
                }
            }
            flush()
        }
        val out = parts.joinToString(" · ").take(MAX_LABEL).trim().trimEnd('·').trim()
        return out.ifEmpty { null }
    }

    /** "3", "F", "F12", "-1", "B2", "G": a level or bay code, not a word. */
    private fun looksLikeCode(w: String): Boolean = w.length <= 3 || w.any { it.isDigit() }

    private fun codeOrWord(w: String): String = if (w.any { it.isDigit() } && w.length <= 5) w.uppercase() else w.lowercase()

    private fun capitalise(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }

    // ---- geometry ----

    private const val EARTH_M = 6_371_008.8

    private fun rad(d: Double) = d * PI / 180.0

    /** Great-circle metres between two points. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The compass bearing from the first point to the second, 0..360 clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        return norm360(atan2(y, x) * 180.0 / PI)
    }

    fun norm360(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    /** -180 < d ≤ 180. */
    fun norm180(d: Double): Double = norm360(d).let { if (it > 180.0) it - 360.0 else it }

    /** "ahead", "ahead right", "right", "behind right", "behind" … from the turn ([relDeg], clockwise from ahead). */
    fun relativeWords(relDeg: Double): String {
        val r = norm180(relDeg)
        val a = abs(r)
        val side = if (r > 0) "right" else "left"
        return when {
            a <= 22.5 -> "ahead"
            a <= 67.5 -> "ahead $side"
            a <= 112.5 -> side
            a <= 157.5 -> "behind $side"
            else -> "behind"
        }
    }

    private val POINTS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    /** "north-east" for a bearing. */
    fun compassPoint(bearing: Double): String = POINTS[((norm360(bearing) + 22.5) / 45.0).toInt() % 8]

    /** "8 m", "84 m", "350 m", "1.2 km", "12 km". */
    fun distanceText(m: Double): String = when {
        m < 1000.0 -> "${metres(m)}"
        m < 10_000.0 -> "${(m / 100.0).roundToLong() / 10}.${(m / 100.0).roundToLong() % 10} km"
        else -> "${(m / 1000.0).roundToLong()} km"
    }

    /** "8 m", "85 m", "350 m": to the metre close by, to 5 m from 100 m, never 0. */
    fun metres(m: Double): String {
        val r = if (m < 100.0) m.roundToLong() else ((m / 5.0).roundToLong() * 5)
        return "${r.coerceAtLeast(1)} m"
    }

    /**
     * Find my car now, from where he is ([here]) and the phone's compass heading ([headingDeg], degrees from north, null
     * when it has none). Within [CLOSE_M] (the fixes' own uncertainty allowed for, up to 30 m) it says to look around.
     */
    fun find(spot: ParkedSpot, here: LocationFix?, headingDeg: Double?): FindView {
        if (here == null || !ErrandPlaceRules.valid(here.latitude, here.longitude)) return FindView(FINDING, null, false, null, null)
        val d = distanceM(here.latitude, here.longitude, spot.latitude, spot.longitude)
        val bearing = bearingDeg(here.latitude, here.longitude, spot.latitude, spot.longitude)
        val arrow = headingDeg?.let { norm180(bearing - it) }
        val accuracy = "You ±${metres(here.accuracyM ?: MAX_ACCURACY_M)} · the car ±${metres(spot.accuracyM)}"
        val closeEnough = d <= CLOSE_M
        val line = when {
            closeEnough -> "Within ${CLOSE_M.toInt()} m · look around" + (spot.label?.let { " for $it" } ?: "")
            arrow != null -> "${distanceText(d)} · ${relativeWords(arrow)}"
            else -> "${distanceText(d)} · ${compassPoint(bearing)}"
        }
        return FindView(line, arrow, closeEnough, accuracy, d)
    }

    /** Whether to buzz now: he has just come within [CLOSE_M] ([wasClose] false before this reading). */
    fun buzz(wasClose: Boolean, view: FindView): Boolean = view.close && !wasClose

    /**
     * A walking route to the spot in his maps app (only on his tap): the universal Maps link, which Android hands to
     * whichever maps app he uses.
     */
    fun mapsUrl(spot: ParkedSpot): String =
        "https://www.google.com/maps/dir/?api=1&destination=${fixed6(spot.latitude)},${fixed6(spot.longitude)}&travelmode=walking"

    /** "51.501234", "-0.141000": six decimals without exponents. */
    fun fixed6(v: Double): String {
        val neg = v < 0
        val micro = (abs(v) * 1_000_000.0).roundToLong()
        val whole = micro / 1_000_000
        val frac = (micro % 1_000_000).toString().padStart(6, '0')
        return (if (neg && micro != 0L) "-" else "") + "$whole.$frac"
    }

    // ---- what the pane and the tile say ----

    /** "Parked 14:05 · 2 h ago", "Parked yesterday 18:40", "Parked Mon 12 Oct 09:15". */
    fun parkedLine(savedAtMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val hhmm = LocalClock.formatMinute(cal.minuteOfDay(savedAtMs))
        val days = cal.epochDayOf(nowMs) - cal.epochDayOf(savedAtMs)
        val mins = ((nowMs - savedAtMs) / 60_000L).coerceAtLeast(0)
        return when {
            days == 0L -> "Parked $hhmm · ${ago(mins)}"
            days == 1L -> "Parked yesterday $hhmm"
            else -> "Parked ${CivilDate.shortLabel(cal.epochDayOf(savedAtMs))} $hhmm"
        }
    }

    /** "just now", "12 min ago", "2 h ago", "2 h 30 ago". */
    fun ago(mins: Long): String = when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins % 60 == 0L || mins >= 6 * 60 -> "${mins / 60} h ago"
        else -> "${mins / 60} h ${(mins % 60).toString().padStart(2, '0')} ago"
    }

    /** The parked cars, in the cars' own order, each with its title ([cars]: from the core's car list). */
    fun rows(spots: Map<String, ParkedSpot>, cars: CarsView, nowMs: Long, cal: LocalCalendar): List<ParkedRow> {
        val order = cars.cars.map { it.carId }
        val known = spots.values.filter { it.carId in order || order.isEmpty() || it.carId == CarRules.ENTITY_ID }
        return known.sortedBy { order.indexOf(it.carId).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { s ->
            val title = titleOf(s.carId, cars)
            val rough = if (s.accuracyM > ROUGH_M) " · rough" else ""
            ParkedRow(s.carId, title, s.label, parkedLine(s.savedAtMs, nowMs, cal) + " · ±${metres(s.accuracyM)}$rough", s)
        }
    }

    /** [carId]'s title among [cars] ("the Golf", "Car 2"); "Car" when it's the only one or unknown. */
    fun titleOf(carId: String, cars: CarsView): String = cars.cars.firstOrNull { it.carId == carId }?.title ?: TITLE

    /** "I've parked" asks which car only when there is more than one: the choices, in order (carId to title). */
    fun choices(cars: CarsView): List<Pair<String, String>> =
        if (cars.cars.size <= 1) emptyList() else cars.cars.map { it.carId to it.title }

    /** The car "I've parked" saves when there's no choice to make. */
    fun onlyCar(cars: CarsView): String = cars.cars.singleOrNull()?.carId ?: CarRules.ENTITY_ID

    /** The Car tile while parked (lit): "Parked · Level 3 · Row F", "Parked 2 h ago", "2 cars parked". */
    fun tileLine(rows: List<ParkedRow>, nowMs: Long): LifeTileLine? = when {
        rows.isEmpty() -> null
        rows.size > 1 -> LifeTileLine("${rows.size} cars parked", true)
        else -> rows.first().let { r ->
            val who = if (r.title == TITLE) "Parked" else "${r.title.replaceFirstChar { it.titlecaseChar() }} parked"
            LifeTileLine(r.label?.let { "$who · $it" } ?: "$who ${ago(((nowMs - r.spot.savedAtMs) / 60_000L).coerceAtLeast(0))}", true)
        }
    }

    /** The grid's lines with the Car tile saying where the car is ([parked], from the phone): live, not "to set up". */
    fun withParked(lines: LifeLines, parked: LifeTileLine?): LifeLines =
        if (parked == null) lines else LifeLines(lines.lines + (LifeTile.CAR to parked), lines.toSetUp - LifeTile.CAR)

    /**
     * The Car tile's own line (both apps): the soonest of the cars' dates still to come — "MOT due Thu 12 Nov", with
     * the car's title when there are two ("… · the Golf") — lit within a week; else the first car's registration;
     * null with nothing entered (the tile's prompt).
     */
    fun carLine(cars: List<CarEntry>, items: List<RenewalItem>, today: Long): LifeTileLine? {
        val live = cars.filter { !it.removed }
        val due = items.filter { CarRules.isCar(it) && it.dueDay >= today }
            .mapNotNull { i -> CarRules.ownerOf(i, cars)?.let { owner -> i to owner } }
            .minByOrNull { it.first.dueDay }
        if (due != null) {
            val (item, owner) = due
            val car = live.firstOrNull { it.id == owner }
            val who = if (live.size > 1 && car != null) " · ${CarRules.title(car, live.size)}" else ""
            return LifeTileLine("${item.title} ${RenewalRules.verb(item.kind)} ${CivilDate.shortLabel(item.dueDay)}$who", item.dueDay - today <= 7)
        }
        val reg = live.firstNotNullOfOrNull { it.registration } ?: return null
        return LifeTileLine("${CarRules.display(reg)} · add its dates", false)
    }
}
