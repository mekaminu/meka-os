package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/*
 * Find my car on the watch (Needs Meka #35, answered "Yes" 2026-11-11 20:13): where each car is parked now syncs to
 * the Galaxy Watch through Navilife's own server, like the rest of his data, so the watch can show the arrow and the
 * distance. The Fold is still where a spot is saved (its own store stays the source); every change there is handed to
 * the core, which keeps one synced field per car ([SharedSpotFields.SPOT]: the point, how sure, when, the bay). Drove
 * off clears it, and the server then forgets every earlier value of that field ([os.meka.core.sync.ForgetfulFields]),
 * so it holds at most the spot he's parked in now. The bay's photo never leaves the phone.
 */

object SharedSpotFields {
    /** On the car's [EntityTypes.CONTEXT_MODE] entity (beside its parking timer): one line, or null when not parked. */
    const val SPOT = "parkedSpot"
}

/** A parked car on the wrist. */
data class WatchSpot(
    val carId: String,
    /** "PARKED" · "PARKED · THE GOLF". */
    val label: String,
    /** "Level 3 · Row F · parked 14:05" · "Parked 14:05 · 2 h ago". */
    val line: String,
    val spot: ParkedSpot,
)

object SharedSpotRules {
    const val LABEL = "PARKED"
    const val FIND = "Find my car"
    const val BACK = "Back"
    const val NO_PERMISSION = "Allow location so the watch can point to the car"
    const val ALLOW = "Allow"
    const val LOCATION_OFF = "Location is off on the watch"
    const val GONE = "The car isn't saved as parked any more"
    const val NO_COMPASS = "No compass on the watch · the arrow points north-up"

    /** The point, how sure (whole metres), when, and the bay; never the photo. Tabs and line breaks can't get in. */
    fun encode(s: ParkedSpot): String =
        listOf(
            ParkingRules.fixed6(s.latitude), ParkingRules.fixed6(s.longitude), s.accuracyM.toLong().coerceAtLeast(1).toString(),
            s.savedAtMs.toString(), s.label.orEmpty().replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")

    fun decode(carId: String, text: String?): ParkedSpot? {
        val f = text?.split('\t') ?: return null
        if (f.size != 5) return null
        val lat = f[0].toDoubleOrNull() ?: return null
        val lon = f[1].toDoubleOrNull() ?: return null
        if (!ErrandPlaceRules.valid(lat, lon)) return null
        return ParkedSpot(
            carId, lat, lon, f[2].toDoubleOrNull() ?: ParkingRules.MAX_ACCURACY_M, f[3].toLongOrNull() ?: 0L,
            f[4].trim().take(ParkingRules.MAX_LABEL).ifEmpty { null },
        )
    }

    /**
     * What to write so the synced spots match the phone's [phone] spots: the cars whose line is new or changed (with
     * the line), and the cars to clear (parked in [shared] but no longer on the phone). Only known car ids.
     */
    fun changes(phone: Map<String, ParkedSpot>, shared: Map<String, String>): Pair<Map<String, String>, Set<String>> {
        val wanted = phone.filterKeys { it in CarRules.CAR_IDS }.mapValues { encode(it.value) }
        val put = wanted.filter { (id, line) -> shared[id] != line }
        val clear = shared.keys.filter { it !in wanted }.toSet()
        return put to clear
    }

    /** The parked cars for the wrist, in the cars' order: "PARKED · THE GOLF" with two or more cars. */
    fun watch(spots: Map<String, ParkedSpot>, cars: CarsView, nowMs: Long, cal: LocalCalendar): List<WatchSpot> =
        ParkingRules.rows(spots, cars, nowMs, cal).map { r ->
            val label = if (cars.cars.size > 1) "$LABEL · ${r.title.uppercase()}" else LABEL
            val parked = ParkingRules.parkedLine(r.spot.savedAtMs, nowMs, cal)
            WatchSpot(r.carId, label, r.label?.let { "$it · ${parked.replaceFirstChar { c -> c.lowercaseChar() }}" } ?: parked, r.spot)
        }

    /**
     * What the watch's Find my car screen says under the arrow: the line, then how sure ("You ±6 m · the car ±8 m").
     * Without a compass the arrow is the bearing from north and the line says so once.
     */
    fun findLines(view: FindView, compass: Boolean): List<String> =
        listOfNotNull(view.line, view.accuracy, NO_COMPASS.takeIf { !compass && view.distanceM != null })

    /** The arrow's turn on the watch: from straight ahead with a compass; from north without one. */
    fun arrowDeg(spot: ParkedSpot, here: LocationFix?, view: FindView): Double? =
        view.arrowDeg ?: here?.let { ParkingRules.bearingDeg(it.latitude, it.longitude, spot.latitude, spot.longitude) }
}

/** The cars' synced spots on a replica. */
class SharedSpots(private val replica: Replica) {
    private fun lines(): Map<String, String> = CarRules.CAR_IDS.mapNotNull { id ->
        replica.entity(EntityTypes.CONTEXT_MODE, id)?.get(SharedSpotFields.SPOT)?.textOrNull?.let { id to it }
    }.toMap()

    fun all(): Map<String, ParkedSpot> = lines().mapNotNull { (id, line) -> SharedSpotRules.decode(id, line)?.let { id to it } }.toMap()

    /** Makes the synced spots match the phone's [phone] spots; how many cars changed. */
    fun share(phone: Map<String, ParkedSpot>): Int {
        val (put, clear) = SharedSpotRules.changes(phone, lines())
        put.forEach { (id, line) -> replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(SharedSpotFields.SPOT to FieldValue.Text(line))) }
        clear.forEach { id -> replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(SharedSpotFields.SPOT to FieldValue.Null)) }
        return put.size + clear.size
    }
}
