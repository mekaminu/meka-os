package os.meka.core.domain

/*
 * Car and parking, slice 3 (build plan V1, "Car and parking"; non-AI, pure): the car's Bluetooth saves the spot.
 *
 * Each car can be linked to one of the phone's paired Bluetooth devices (the car's own audio, or the Android Auto
 * link). The link lives on the phone only (a device address is the phone's business, never synced or sent). When the
 * linked device disconnects after a drive, the car has just been parked: with "Allow all the time" location the Fold
 * saves the spot by itself and says "Saved where the Golf is · ±8 m · add the bay?"; without it, a quiet "Parked the
 * Golf? · tap to save where it is" opens the Car pane saving the spot. When the device connects again a while later,
 * the car has been driven off: its spot is cleared (the Drove off tap, done for him).
 *
 * A connection shorter than [CarBluetoothRules.MIN_DRIVE_MS] (sitting in the car a moment, a flaky link) isn't a
 * drive, so its disconnect saves nothing; a reconnect within [CarBluetoothRules.DROVE_OFF_AFTER_MS] of the spot being
 * saved (the link dropping and coming back as he parks) doesn't clear it.
 */

/** One car's Bluetooth device on this phone. [connectedAtMs]: when it last connected (null when it's not connected). */
data class CarLink(
    val carId: String,
    val address: String,
    val name: String,
    val connectedAtMs: Long? = null,
)

/** A paired device as the phone lists it. */
data class PairedDevice(val address: String, val name: String)

/** A paired device as the pick list shows it: [linkedTo] names the other car already using it, if any. */
data class DeviceChoice(val address: String, val name: String, val chosen: Boolean, val linkedTo: String?)

/** What a disconnect means: [parkedCar] is the car just parked (null: not a linked car, or not a drive). */
data class DisconnectOutcome(val links: Map<String, CarLink>, val parkedCar: String?)

/** What a connect means: [droveOff] is the car whose spot should be cleared (null: none). */
data class ConnectOutcome(val links: Map<String, CarLink>, val droveOff: String?)

/** The parked notification's words. */
data class ParkedNote(val title: String, val text: String, val action: String)

object CarBluetoothRules {
    /** Connected at least this long before the disconnect counts as a drive (and so a park). */
    const val MIN_DRIVE_MS = 60_000L
    /** A spot saved less than this long ago survives a reconnect (the link dropping and coming back while parking). */
    const val DROVE_OFF_AFTER_MS = 120_000L
    /** How long the Fold listens for a fix after the disconnect when it may save by itself. */
    const val QUIET_FIX_MS = 15_000L

    const val HEADING = "BLUETOOTH"
    const val INTRO = "Pick each car's Bluetooth: when it disconnects, Navilife saves where you parked. When it connects again, the spot is cleared."
    const val PICK = "Pick"
    const val CHANGE = "Change"
    const val NONE = "None"
    const val NOT_LINKED = "Not linked · tap “I've parked here” yourself"
    const val NEEDS_PERMISSION = "Allow Nearby devices so Navilife can see the car's Bluetooth"
    const val ALLOW = "Allow"
    const val NO_DEVICES = "No paired devices · pair the car with the phone first (Settings → Connections → Bluetooth)"
    const val BLUETOOTH_OFF = "Bluetooth is off on the phone"
    const val ALWAYS_LINE = "To save the spot without a tap, allow location “All the time” (Android asks on its own page)"
    const val ALWAYS_ON = "Saves the spot by itself when the car switches off"
    const val TAP_ON = "When the car switches off, a notification asks to save the spot"

    const val CHANNEL_NAME = "Parked"
    const val CHANNEL_ABOUT = "When the car's Bluetooth disconnects: where you parked, or a tap to save it"

    /** Words that make a paired device look like a car (listed first in the pick list). */
    private val CAR_WORDS = listOf(
        "car", "auto", "audio", "media", "multimedia", "sync", "uconnect", "mmi", "idrive", "comand", "mbux",
        "my vw", "vw", "volkswagen", "ford", "toyota", "honda", "nissan", "kia", "hyundai", "bmw", "audi", "mercedes",
        "skoda", "seat", "cupra", "vauxhall", "opel", "peugeot", "citroen", "renault", "dacia", "mazda", "volvo",
        "tesla", "mini", "fiat", "jaguar", "land rover", "range rover", "lexus", "suzuki", "mg", "byd", "polestar",
    )

    // ---- the links on the phone ----

    fun encode(links: Collection<CarLink>): String =
        links.sortedBy { it.carId }.joinToString("\n") { l ->
            listOf(clean(l.carId), clean(l.address), clean(l.name), l.connectedAtMs?.toString().orEmpty()).joinToString("\t")
        }

    fun decode(text: String?): Map<String, CarLink> =
        text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 4 || f[0].isBlank() || f[1].isBlank()) return@mapNotNull null
            CarLink(f[0], f[1], f[2].ifBlank { f[1] }, f[3].toLongOrNull())
        }.associateBy { it.carId }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    /** Links [carId] to [device]; a device already linked to another car moves to this one (one car per device). */
    fun link(links: Map<String, CarLink>, carId: String, device: PairedDevice): Map<String, CarLink> {
        val others = links.filterValues { !sameAddress(it.address, device.address) } - carId
        return others + (carId to CarLink(carId, device.address, device.name.ifBlank { device.address }))
    }

    fun unlink(links: Map<String, CarLink>, carId: String): Map<String, CarLink> = links - carId

    /** Cars no longer in the car list lose their link (a removed car). */
    fun prune(links: Map<String, CarLink>, carIds: Collection<String>): Map<String, CarLink> =
        if (carIds.isEmpty()) links else links.filterKeys { it in carIds }

    fun sameAddress(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun carFor(links: Map<String, CarLink>, address: String): CarLink? =
        links.values.firstOrNull { sameAddress(it.address, address) }

    /**
     * [address] connected at [nowMs]: notes when, and says which car has been driven off — a parked car whose spot
     * ([spots]) was saved more than [DROVE_OFF_AFTER_MS] ago.
     */
    fun onConnect(links: Map<String, CarLink>, spots: Map<String, ParkedSpot>, address: String, nowMs: Long): ConnectOutcome {
        val link = carFor(links, address) ?: return ConnectOutcome(links, null)
        val updated = links + (link.carId to link.copy(connectedAtMs = nowMs))
        val spot = spots[link.carId]
        val drove = spot != null && nowMs - spot.savedAtMs >= DROVE_OFF_AFTER_MS
        return ConnectOutcome(updated, if (drove) link.carId else null)
    }

    /**
     * [address] disconnected at [nowMs]: the car it belongs to has been parked when it was connected for at least
     * [MIN_DRIVE_MS] (or when the phone didn't see it connect: Navilife installed or restarted mid-drive).
     */
    fun onDisconnect(links: Map<String, CarLink>, address: String, nowMs: Long): DisconnectOutcome {
        val link = carFor(links, address) ?: return DisconnectOutcome(links, null)
        val since = link.connectedAtMs
        val drive = since == null || nowMs - since >= MIN_DRIVE_MS
        return DisconnectOutcome(links + (link.carId to link.copy(connectedAtMs = null)), if (drive) link.carId else null)
    }

    // ---- what the pane and the notification say ----

    /** Car-looking devices first, then by name; each says whether it's [carId]'s, or which other car has it. */
    fun choices(paired: List<PairedDevice>, links: Map<String, CarLink>, carId: String, cars: CarsView): List<DeviceChoice> =
        paired.distinctBy { it.address.uppercase() }
            .sortedWith(compareBy<PairedDevice> { if (looksLikeCar(it.name)) 0 else 1 }.thenBy { it.name.lowercase() })
            .map { d ->
                val owner = carFor(links, d.address)
                DeviceChoice(
                    d.address, d.name.ifBlank { d.address },
                    chosen = owner?.carId == carId,
                    linkedTo = owner?.carId?.takeIf { it != carId }?.let { ParkingRules.titleOf(it, cars) },
                )
            }

    fun looksLikeCar(name: String): Boolean {
        val n = " ${name.lowercase()} "
        return CAR_WORDS.any { w -> Regex("[^a-z]${Regex.escape(w)}[^a-z]").containsMatchIn(n) }
    }

    /** One car's row: "Bluetooth · Golf Audio" or [NOT_LINKED]. */
    fun rowLine(link: CarLink?): String = link?.let { "Bluetooth · ${it.name}" } ?: NOT_LINKED

    /** What happens when it switches off, by whether location may be read in the background. */
    fun modeLine(always: Boolean): String = if (always) ALWAYS_ON else TAP_ON

    private fun named(title: String): String = if (title == ParkingRules.TITLE) "the car" else title

    /** Without background location: "Parked the Golf?" · "Tap to save where it is, then add the bay". */
    fun askNote(title: String): ParkedNote =
        ParkedNote("Parked ${named(title)}?", "Tap to save where it is, then add the bay from the sign", "Save spot")

    /** Saved by itself: "Saved where the Golf is · ±8 m" · "Add the bay?" (rough fixes say so). */
    fun savedNote(saved: ParkSaved): ParkedNote =
        ParkedNote(saved.line, "Add the bay from the sign, so Find my car takes you to it", "Add the bay")

    /** Saving by itself failed (no fix in the car park): ask for the tap instead, which also takes the bay. */
    fun failedNote(title: String): ParkedNote =
        ParkedNote("Parked ${named(title)}?", "Couldn't find where you are · tap to try again, or type the bay", "Save spot")
}
