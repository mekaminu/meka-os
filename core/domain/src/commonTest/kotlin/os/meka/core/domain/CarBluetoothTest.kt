package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarBluetoothTest {
    // Invented devices (rule 13).
    private val golf = PairedDevice("AA:BB:CC:00:00:01", "MY VW 1234")
    private val family = PairedDevice("AA:BB:CC:00:00:02", "Toyota Touch")
    private val buds = PairedDevice("AA:BB:CC:00:00:03", "Galaxy Buds")
    private val t0 = 1_800_000_000_000L

    private fun cars(vararg titles: String) = CarsView(
        titles.mapIndexed { i, t -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t) }, true,
    )

    private fun spot(carId: String, at: Long) = ParkedSpot(carId, 51.5, -0.1, 6.0, at)

    @Test
    fun aDeviceBelongsToOneCarAndTheLinksRoundTrip() {
        val one = CarBluetoothRules.link(emptyMap(), "car", golf)
        val two = CarBluetoothRules.link(one, "car2", family)
        assertEquals(setOf("car", "car2"), two.keys)
        // The Golf's device moved to car 2: car 1 loses it, car 2 has one device.
        val moved = CarBluetoothRules.link(two, "car2", golf.copy(address = "aa:bb:cc:00:00:01"))
        assertEquals(setOf("car2"), moved.keys)
        assertEquals("MY VW 1234", moved.getValue("car2").name)

        val withTime = CarBluetoothRules.onConnect(two, emptyMap(), golf.address, t0).links
        assertEquals(withTime, CarBluetoothRules.decode(CarBluetoothRules.encode(withTime.values)))
        assertEquals(emptyMap(), CarBluetoothRules.decode("junk\n\tno-car\t\t"))
        assertEquals(setOf("car"), CarBluetoothRules.prune(two, listOf("car")).keys)
        assertEquals(setOf("car2"), CarBluetoothRules.unlink(two, "car").keys)
    }

    @Test
    fun aDisconnectAfterADriveParksTheCarButABlipDoesNot() {
        val links = CarBluetoothRules.link(CarBluetoothRules.link(emptyMap(), "car", golf), "car2", family)
        val on = CarBluetoothRules.onConnect(links, emptyMap(), golf.address, t0)
        assertNull(on.droveOff)
        assertEquals(t0, on.links.getValue("car").connectedAtMs)

        // Twenty minutes later the Golf is switched off: parked.
        val off = CarBluetoothRules.onDisconnect(on.links, golf.address, t0 + 20 * 60_000L)
        assertEquals("car", off.parkedCar)
        assertNull(off.links.getValue("car").connectedAtMs)

        // Sat in the family car for 20 s: not a drive.
        val blipOn = CarBluetoothRules.onConnect(off.links, emptyMap(), family.address, t0)
        assertNull(CarBluetoothRules.onDisconnect(blipOn.links, family.address, t0 + 20_000L).parkedCar)

        // Navilife never saw it connect (restarted mid-drive): the disconnect still counts.
        assertEquals("car2", CarBluetoothRules.onDisconnect(links, family.address, t0).parkedCar)

        // Earbuds aren't a car.
        val buds = CarBluetoothRules.onDisconnect(links, buds.address, t0)
        assertNull(buds.parkedCar)
        assertEquals(links, buds.links)
    }

    @Test
    fun connectingAgainLaterClearsTheSpotButNotWhileParking() {
        val links = CarBluetoothRules.link(emptyMap(), "car", golf)
        val parked = mapOf("car" to spot("car", t0))
        // The link drops and comes back a minute after the save: still parked.
        assertNull(CarBluetoothRules.onConnect(links, parked, golf.address, t0 + 60_000L).droveOff)
        // Back from the shops an hour later: drove off.
        assertEquals("car", CarBluetoothRules.onConnect(links, parked, golf.address, t0 + 3_600_000L).droveOff)
        // Another car's spot is never cleared by this one.
        assertNull(CarBluetoothRules.onConnect(links, mapOf("car2" to spot("car2", t0)), golf.address, t0 + 3_600_000L).droveOff)
    }

    @Test
    fun thePickListPutsTheCarsFirstAndSaysWhoHasEach() {
        val cv = cars("the Golf", "the family car")
        val links = CarBluetoothRules.link(emptyMap(), "car2", family)
        val list = CarBluetoothRules.choices(listOf(buds, family, golf, golf), links, "car", cv)
        assertEquals(listOf("MY VW 1234", "Toyota Touch", "Galaxy Buds"), list.map { it.name })
        assertEquals("the family car", list[1].linkedTo)
        assertFalse(list[1].chosen)
        assertTrue(CarBluetoothRules.choices(listOf(family), links, "car2", cv).single().chosen)

        assertTrue(CarBluetoothRules.looksLikeCar("Ford SYNC"))
        assertFalse(CarBluetoothRules.looksLikeCar("Minimal speaker"))
        assertFalse(CarBluetoothRules.looksLikeCar("Galaxy Buds"))
    }

    @Test
    fun whatTheRowAndTheNotificationsSay() {
        assertEquals("Bluetooth · MY VW 1234", CarBluetoothRules.rowLine(CarLink("car", golf.address, golf.name)))
        assertEquals(CarBluetoothRules.NOT_LINKED, CarBluetoothRules.rowLine(null))
        assertEquals("Parked the Golf?", CarBluetoothRules.askNote("the Golf").title)
        assertEquals("Parked the car?", CarBluetoothRules.askNote(ParkingRules.TITLE).title)
        val saved = ParkingRules.save(emptyMap(), "car", "the Golf", LocationFix(51.5, -0.1, 8.0, t0), t0)
        val note = CarBluetoothRules.savedNote(saved)
        assertEquals("Saved where the Golf is · ±8 m", note.title)
        assertEquals("Add the bay", note.action)
        assertEquals(ParkingRules.PHOTO, note.photoAction)
        assertEquals(null, CarBluetoothRules.askNote("the Golf").photoAction)
        assertEquals("Save spot", CarBluetoothRules.failedNote("the Golf").action)
        assertEquals(CarBluetoothRules.ALWAYS_ON, CarBluetoothRules.modeLine(true))
    }
}
