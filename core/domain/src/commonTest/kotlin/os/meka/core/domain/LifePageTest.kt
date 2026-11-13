package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifePageTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)

    private fun row(id: String, title: String, due: Long?) =
        CarRow(id, title, "note for $title", "line", if (due != null) "r-$id" else null, dueDay = due)

    private fun car(id: String, title: String, vararg rows: CarRow, lapses: List<String> = emptyList()) =
        CarView.EMPTY.copy(carId = id, title = title, rows = rows.toList(), lapses = lapses)

    private val fuel = FuelView(
        FuelRules.OK, "line",
        listOf(
            FuelBlock(
                FuelKind.E10, "UNLEADED (E10)", "summary", null,
                listOf(
                    FuelPriceRow("Oakfield Fuel", "141.9p", "2 km", false, false, 51.5, -0.1),
                    FuelPriceRow("Sam's Garage", "138.9p", "1 km", true, false, 51.5, -0.1),
                ),
            ),
        ),
    )

    @Test
    fun aCardShowsFiveRowsCountsAllAndOffersSeeAll() {
        val rows = (1..7).map { PageRow("k$it", "Row $it", "", "", false) }
        val c = LifePageRules.card("x", "Things", rows)
        assertEquals(5, c.rows.size)
        assertEquals(7, c.count)
        assertEquals("See all", c.more)
        assertNull(LifePageRules.card("x", "Things", rows.take(5)).more)
        assertEquals("Find my car", LifePageRules.card("x", "Things", rows.take(1), more = "Find my car").more)
    }

    @Test
    fun untilSaysHowFarOffADayIs() {
        assertEquals("today", LifePageRules.until(day, day))
        assertEquals("tomorrow", LifePageRules.until(day + 1, day))
        assertEquals("5 days", LifePageRules.until(day + 5, day))
        assertEquals("3 wks", LifePageRules.until(day + 21, day))
        assertEquals("4 mths", LifePageRules.until(day + 125, day))
        assertEquals("overdue", LifePageRules.until(day - 1, day))
    }

    @Test
    fun carPageHasThreeTilesDatesSoonestFirstAndParked() {
        val cars = CarsView(listOf(car(CarRules.ENTITY_ID, "Car", row("mot", "MOT", day + 21), row("tax", "Car tax", day + 3), row("service", "Car service", null))), true)
        val spot = ParkedSpot(CarRules.ENTITY_ID, 51.5, -0.1, 6.0, at(8, 10), "Level 2")
        val parked = ParkingRules.rows(mapOf(spot.carId to spot), cars, at(9), cal)
        val p = CarPageRules.page(cars, parked, TimersView.EMPTY, fuel, day, at(9), cal)

        assertEquals("Car", p.title)
        assertEquals(3, p.tiles.size)
        assertEquals(PageTile("3 days", "to the tax", true), p.tiles[0])
        assertEquals(PageTile("138.9p", "cheapest unleaded near", false), p.tiles[1])
        assertEquals(PageTile("Level 2", "parked · 08:10", false), p.tiles[2])

        val dates = p.cards[0]
        assertEquals("Dates", dates.title)
        assertEquals(listOf("Car tax", "MOT"), dates.rows.map { it.name }) // untracked service left out
        assertEquals("Sat 17 Oct", dates.rows[0].whenText)
        assertTrue(dates.rows[0].lit)
        assertFalse(dates.rows[1].lit)
        assertEquals("date:car:tax", dates.rows[0].key)
        assertEquals("All dates", dates.more)

        val park = p.cards[1]
        assertEquals("Find my car", park.more)
        assertEquals("Level 2", park.rows.single().name)
        assertEquals("08:10", park.rows.single().whenText)
        assertEquals("parked:car", park.rows.single().key)
        assertEquals(listOf("parked", "timer", "date"), p.addChoices.map { it.key })
    }

    @Test
    fun anEmptyCarPageSaysSoInWordsNeverALoneDash() {
        val p = CarPageRules.page(CarsView.EMPTY, emptyList(), TimersView.EMPTY, FuelView.IDLE, day, at(9), cal)
        assertEquals(listOf("No dates", "Fuel", "Not parked"), p.tiles.map { it.number })
        assertTrue(ScreenSenseRules.problems(p.tiles.flatMap { listOf(it.number, it.label) }).isEmpty())
        assertTrue(p.cards.filter { it.key != CarPageRules.BLUETOOTH }.all { it.rows.isEmpty() && it.empty != null })
        // The one car there always is can still be linked to its Bluetooth.
        assertEquals(CarBluetoothRules.NOT_LINKED, p.cards.single { it.key == CarPageRules.BLUETOOTH }.rows.single().line)
        assertNull(p.cards[1].more)
    }

    @Test
    fun twoCarsNameTheCarAndARanOutDateLeads() {
        val cars = CarsView(
            listOf(
                car(CarRules.ENTITY_ID, "the Golf", row("mot", "MOT", day + 40)),
                car("car2", "Car 2", row("insurance", "Car insurance", day + 10), lapses = listOf("Untaxed · tax it before you drive")),
            ),
            true,
        )
        val p = CarPageRules.page(cars, emptyList(), TimersView.EMPTY, FuelView.IDLE, day, at(9), cal)
        assertEquals(PageTile("Untaxed", "tax it before you drive · Car 2", true), p.tiles[0])
        assertEquals("Car 2 · note for Car insurance", p.cards[0].rows[0].line)
        assertEquals("date:car2:insurance", p.cards[0].rows[0].key)
    }

    @Test
    fun theMacPageCountsDatesAndShowsTheTimerWithNoParkingOrFuel() {
        val cars = CarsView(listOf(car(CarRules.ENTITY_ID, "Car", row("mot", "MOT", day + 21), row("tax", "Car tax", day + 40))), true)
        val p = CarPageRules.macPage(cars, TimersView.EMPTY, day)
        assertEquals(listOf("3 wks", "2", "No timer"), p.tiles.map { it.number })
        assertEquals("dates on the radar", p.tiles[1].label)
        assertEquals(listOf("Dates"), p.cards.map { it.title })
        assertEquals(listOf("timer", "date"), p.addChoices.map { it.key })
    }

    @Test
    fun theFuelCardListsForecourtsCheapestLitAndTakesTwoFuelsInTurn() {
        val one = CarPageRules.fuelCard(fuel)
        assertEquals("Fuel near you", one.title)
        assertEquals(listOf("Oakfield Fuel", "Sam's Garage"), one.rows.map { it.name })
        assertEquals("2 km", one.rows[0].line)
        assertEquals("138.9p", one.rows[1].whenText)
        assertTrue(one.rows[1].lit)
        assertEquals("See all", one.more)
        assertEquals("Sam's Garage", CarPageRules.fuelRowOf(fuel, one.rows[1].key)?.second?.title)
        assertNull(CarPageRules.fuelRowOf(fuel, "fuel:B7:0"))

        val diesel = FuelBlock(
            FuelKind.B7, "DIESEL", "summary", null,
            (1..4).map { FuelPriceRow("Diesel $it", "14$it.9p", "$it km", it == 1, false, 51.5, -0.1) },
        )
        val two = CarPageRules.fuelCard(fuel.copy(blocks = fuel.blocks + diesel))
        assertEquals(6, two.count)
        assertEquals(listOf("Oakfield Fuel", "Diesel 1", "Sam's Garage", "Diesel 2", "Diesel 3"), two.rows.map { it.name })
        assertEquals("Diesel · 1 km", two.rows[1].line)
        assertEquals("fuel:B7:2", two.rows[4].key)
    }

    @Test
    fun theFuelCardSaysWhyItIsEmptyAndStillOpensItsSheet() {
        val none = CarPageRules.fuelCard(FuelView(FuelRules.NO_LOCATION, FuelRules.NO_LOCATION_LINE, emptyList()))
        assertTrue(none.rows.isEmpty())
        assertEquals(FuelRules.NO_LOCATION_LINE, none.empty)
        assertEquals("See all", none.more)
        val nothingNear = CarPageRules.fuelCard(FuelView(FuelRules.OK, "line", listOf(FuelBlock(FuelKind.B7, "DIESEL", "No diesel prices within 8 km", null, emptyList()))))
        assertEquals("No diesel prices within 8 km", nothingNear.empty)
    }

    @Test
    fun theBluetoothCardHasARowACarAndAsksForNearbyDevicesFirst() {
        val cars = CarsView(listOf(car(CarRules.ENTITY_ID, "the Golf"), car("car2", "Car 2")), true)
        val links = mapOf("car2" to CarLink("car2", "AA:BB", "MY VW 1234"))
        val c = CarPageRules.bluetoothCard(cars, links, allowed = true)
        assertEquals("Car Bluetooth", c.title)
        assertEquals(listOf("The Golf", "Car 2"), c.rows.map { it.name })
        assertEquals(listOf("Pick", "Change"), c.rows.map { it.whenText })
        assertEquals(listOf(true, false), c.rows.map { it.lit })
        assertEquals("Bluetooth · MY VW 1234", c.rows[1].line)
        assertEquals("bt:car2", c.rows[1].key)
        assertNull(c.more)

        val ask = CarPageRules.bluetoothCard(cars, links, allowed = false)
        assertTrue(ask.rows.isEmpty())
        assertEquals("Allow", ask.more)
        assertEquals(CarBluetoothRules.NEEDS_PERMISSION, ask.empty)
    }

    @Test
    fun theCarPageEndsWithTheFuelAndBluetoothCards() {
        val cars = CarsView(listOf(car(CarRules.ENTITY_ID, "Car", row("mot", "MOT", day + 21))), true)
        val p = CarPageRules.page(cars, emptyList(), TimersView.EMPTY, fuel, day, at(9), cal)
        assertEquals(listOf("Dates", "Parked", "Fuel near you", "Car Bluetooth"), p.cards.map { it.title })
        assertTrue(ScreenSenseRules.problems(p.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line, it.whenText) } + listOfNotNull(c.empty) }).isEmpty())
    }
}
