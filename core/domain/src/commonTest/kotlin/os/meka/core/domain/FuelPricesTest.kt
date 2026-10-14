package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 6: the cheapest fuel nearby (invented forecourts; no real place or address). */
class FuelPricesTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 14)
    private val now = cal.toEpochMs(day, 14 * 60) // Wed 14 Oct 14:00
    private val here = Coord(52.10, -0.30)

    private fun site(id: String, dLat: Double, e10: Double?, b7: Double? = null, at: Long? = now - 3_600_000L, brand: String? = "Fuelco") =
        FuelSite(
            id, "$id Road", brand, "AB1 2CD", here.lat + dLat, here.lon,
            buildMap { e10?.let { put(FuelKind.E10, it to at) }; b7?.let { put(FuelKind.B7, it to at) } },
        )

    @Test
    fun fuelFinderTypesAndPricesReadSafely() {
        assertEquals(FuelKind.E10, FuelRules.kindOf("E10"))
        assertEquals(FuelKind.E5, FuelRules.kindOf("e5"))
        assertEquals(FuelKind.B7, FuelRules.kindOf("B7_STANDARD"))
        assertEquals(FuelKind.B7, FuelRules.kindOf("B7"))
        assertEquals(FuelKind.SDV, FuelRules.kindOf("B7_PREMIUM"))
        assertEquals(FuelKind.SDV, FuelRules.kindOf("SDV"))
        assertNull(FuelRules.kindOf("HVO"))
        assertNull(FuelRules.kindOf("B10"))
        assertNull(FuelRules.kindOf(null))
        assertEquals(139.9, FuelRules.pence(139.9))
        assertEquals(139.9, FuelRules.pence(1.399)) // pounds become pence
        assertNull(FuelRules.pence(13990.0)) // a typo, dropped
        assertNull(FuelRules.pence(0.0))
        assertNull(FuelRules.pence(Double.NaN))
        assertEquals(FuelKind.SDV, FuelKind.of("sdv"))
        assertNull(FuelKind.of("diesel"))
    }

    @Test
    fun theCheapestWithinTheRadiusComeFirst() {
        val sites = listOf(
            site("A", 0.01, 142.9),
            site("B", 0.02, 139.9),
            site("C", 0.03, 139.9), // the same price further away: after B
            site("D", 0.20, 120.0), // 22 km away: not nearby
            site("E", 0.005, null, b7 = 150.9), // diesel only
        )
        val near = FuelRules.nearest(sites, here, FuelKind.E10)
        assertEquals(listOf("B Road", "C Road", "A Road"), near.map { it.name })
        assertTrue(near.first().distanceM in 2_000.0..2_400.0, near.first().distanceM.toString())
        assertEquals(listOf("E Road"), FuelRules.nearest(sites, here, FuelKind.B7).map { it.name })
        assertTrue(FuelRules.nearest(sites, here, FuelKind.SDV).isEmpty())
        assertEquals(2, FuelRules.nearest(sites, here, FuelKind.E10, max = 2).size)
    }

    @Test
    fun theBlockNamesTheCheapestTheSavingAndStalePrices() {
        val stations = FuelRules.nearest(
            listOf(site("A", 0.01, 145.9, brand = "Shell"), site("B", 0.02, 139.9, brand = "Tesco"), site("C", 0.015, 141.9, at = now - 4 * CivilDate.DAY_MS)),
            here, FuelKind.E10,
        )
        val b = FuelRules.block(FuelKind.E10, stations, emptyList(), now, cal)
        assertEquals("UNLEADED (E10)", b.heading)
        assertEquals("Cheapest within 8 km · Tesco 139.9p · 2.2 km", b.summary)
        assertEquals("Tesco · B Road", b.rows[0].title)
        assertEquals("139.9p", b.rows[0].price)
        assertTrue(b.rows[0].cheapest)
        assertEquals("2.2 km · AB1 2CD · updated today 13:00", b.rows[0].line)
        assertTrue(b.rows[1].stale)
        assertTrue(b.rows[1].line.contains("price from"), b.rows[1].line)
        // (145.9 − 139.9) × 50 litres = £3.00
        assertEquals("A 50-litre fill there is £3.00 less than at Shell (1.1 km)", b.saving)
        // Within a penny or two on a fill: no saving line.
        assertNull(FuelRules.saving(stations.filter { it.pence < 142.0 }.map { it.copy(pence = 139.9) }))
        val none = FuelRules.block(FuelKind.B7, emptyList(), listOf("the Golf"), now, cal)
        assertEquals("DIESEL · THE GOLF", none.heading)
        assertEquals("No diesel prices within 8 km", none.summary)
        assertTrue(none.rows.isEmpty())
        assertTrue(FuelRules.mapsUrl(b.rows[0]).startsWith("https://www.google.com/maps/dir/?api=1&destination="))
    }

    @Test
    fun theViewSaysWhatIsMissing() {
        assertEquals(FuelRules.IDLE, FuelRules.view(emptyList(), now, cal).state)
        val off = FuelRules.view(listOf(Triple(FuelKind.E10, emptyList(), FuelRules.OFF to emptyList())), now, cal)
        assertEquals(FuelRules.OFF, off.state)
        assertEquals(FuelRules.OFF_LINE, off.line)
        val loading = FuelRules.view(listOf(Triple(FuelKind.E10, emptyList(), FuelRules.LOADING to emptyList())), now, cal)
        assertTrue(loading.line.contains("look again in a minute"))
        // One fuel answered, the other failed: the answered one shows.
        val mixed = FuelRules.view(
            listOf(
                Triple(FuelKind.E10, listOf("Car 1"), FuelRules.OK to FuelRules.nearest(listOf(site("A", 0.01, 140.0)), here, FuelKind.E10)),
                Triple(FuelKind.B7, listOf("the Golf"), FuelRules.FAILED to emptyList()),
            ),
            now, cal,
        )
        assertEquals(FuelRules.OK, mixed.state)
        assertEquals(1, mixed.blockCount)
        assertEquals("UNLEADED (E10) · CAR 1", mixed.blockAt(0).heading)
    }

    @Test
    fun eachCarKeepsItsOwnFuelAndTheBlocksFollowThem() {
        var n = 0
        val r = Replica("hh", "android", HlcClock("android", { now }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        val car = Car(r)
        // One car, nothing picked: unleaded, no names.
        val one = CarRules.views(car.cars(), emptyList(), null, day, cal)
        assertEquals(FuelKind.E10, one.first.fuel)
        assertEquals(listOf(FuelKind.E10 to emptyList<String>()), FuelRules.fuelsFor(one))
        val golf = car.addCar("the Golf")!!
        assertTrue(car.setFuel(golf, FuelKind.B7))
        assertFalse(car.setFuel(golf, FuelKind.B7))
        assertEquals(FuelKind.B7, car.car(golf)!!.fuel)
        val two = CarRules.views(car.cars(), emptyList(), null, day, cal)
        assertEquals(listOf(FuelKind.E10 to listOf("Car 1"), FuelKind.B7 to listOf("the Golf")), FuelRules.fuelsFor(two))
        // Both on diesel: one block naming both.
        assertTrue(car.setFuel(CarRules.ENTITY_ID, FuelKind.B7))
        assertEquals(listOf(FuelKind.B7 to listOf("Car 1", "the Golf")), FuelRules.fuelsFor(CarRules.views(car.cars(), emptyList(), null, day, cal)))
        assertFalse(car.setFuel("car3", FuelKind.E5)) // no such car
    }
}
