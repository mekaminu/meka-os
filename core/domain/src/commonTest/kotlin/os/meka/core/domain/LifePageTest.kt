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
    fun anEmptyCarPageSaysSoWithDashes() {
        val p = CarPageRules.page(CarsView.EMPTY, emptyList(), TimersView.EMPTY, FuelView.IDLE, day, at(9), cal)
        assertEquals(listOf("—", "—", "—"), p.tiles.map { it.number })
        assertTrue(p.cards.all { it.rows.isEmpty() && it.empty != null })
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
        assertEquals(listOf("3 wks", "2", "—"), p.tiles.map { it.number })
        assertEquals("dates on the radar", p.tiles[1].label)
        assertEquals(listOf("Dates"), p.cards.map { it.title })
        assertEquals(listOf("timer", "date"), p.addChoices.map { it.key })
    }
}
