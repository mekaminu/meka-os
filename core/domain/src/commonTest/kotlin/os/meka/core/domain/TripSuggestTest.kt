package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 3b: Obi's suggestions, what he might have forgotten on each list; never added without a tap. */
class TripSuggestTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private val wed = d(10, 28)
    private val sun = d(11, 1)
    private val family = AboutYou(partnerName = "Jo Smith", children = listOf(AboutChild("Alex"), AboutChild("Sam")))
    private val everyone = setOf(TripGoer.ME, TripGoer.PARTNER, TripGoer.KIDS)

    private fun trip(
        name: String = "Lisbon", start: Long = wed, end: Long = sun, going: Set<TripGoer>? = everyone, leave: Int? = null,
        bookings: List<TripBooking> = emptyList(), no: Set<String> = emptySet(), id: String = "t1",
    ) = TripItem(id, name, start, end, 0L, bookings, going, leave, no)

    private var n = 0
    private fun item(tripId: String, list: TripList, text: String, done: Boolean = false, qty: Int? = null) =
        TripListItem("i${n++}", tripId, list, text, TripListRules.groupOf(list, text), qty, TripWho.EVERYONE, done, null, n.toLong())

    private fun labels(s: List<TripSuggestion>, list: TripList) = s.firstOrNull { it.list == list }?.chips.orEmpty().map { it.label }

    @Test
    fun aFamilyTripAbroadGetsDocumentsChargersAndThingsForTheKids() {
        val s = TripSuggestRules.suggest(trip(), emptyList(), mon, about = family)
        val pack = assertNotNull(s.firstOrNull { it.list == TripList.PACK })
        assertEquals("For 5 days with 4 people, worth packing:", pack.lead)
        assertEquals(listOf("Passports × 4", "GHIC cards × 4", "Travel insurance", "Phone chargers × 2", "Your medicines", "Toothbrushes and paste"), pack.chips.map { it.label })
        assertEquals(TripWho.ME, pack.chips.first { it.key == "pack:medicines" }.entry.who)
        assertEquals("Documents", pack.chips.first().entry.group)
        assertEquals(listOf("Plug adapters × 2", "Calpol sachets", "Snacks for the journey", "Plasters"), labels(s, TripList.BUY))
        assertTrue(TripSuggestRules.MAX_CHIPS >= pack.chips.size)
        // Before we leave: charge everything and bags by the door the night before, the house jobs for five days.
        assertEquals(
            listOf("Charge everything · Tue 21:00", "Put the bins out", "Heating to holiday mode", "Empty the fridge", "Water the plants", "Bags by the door · Tue 21:30"),
            labels(s, TripList.LEAVE),
        )
    }

    @Test
    fun aTripAtHomeOnYourOwnHasNoPassportsOrKidsThings() {
        val s = TripSuggestRules.suggest(trip(name = "Lake District", going = setOf(TripGoer.ME)), emptyList(), mon, about = family)
        val pack = labels(s, TripList.PACK)
        assertFalse(pack.any { it.startsWith("Passports") || it.startsWith("GHIC") || it.startsWith("Kids") })
        assertEquals("For 5 days on your own, worth packing:", s.first { it.list == TripList.PACK }.lead)
        assertEquals(listOf("Plasters"), labels(s, TripList.BUY))
        // Dublin: passports, but no GHIC card and no adapters (the same plugs).
        val dublin = TripSuggestRules.suggest(trip(name = "Dublin"), emptyList(), mon, about = family)
        assertTrue(labels(dublin, TripList.PACK).contains("Passports × 4"))
        assertFalse(labels(dublin, TripList.PACK).any { it.startsWith("GHIC") })
        assertFalse(labels(dublin, TripList.BUY).any { it.startsWith("Plug") })
    }

    @Test
    fun anythingOnTheListsTickedOrNotIsNeverSuggested() {
        val t = trip()
        val items = listOf(
            item(t.id, TripList.PACK, "Passports", done = true, qty = 4), item(t.id, TripList.PACK, "Chargers"),
            item(t.id, TripList.PACK, "My medicines"), item(t.id, TripList.PACK, "Plug adapters"),
            item(t.id, TripList.LEAVE, "Put the bins out"), item(t.id, TripList.BUY, "Sun cream SPF 50"),
        )
        val s = TripSuggestRules.suggest(t, items, mon, about = family)
        val pack = labels(s, TripList.PACK)
        assertFalse(pack.any { it.startsWith("Passports") || it.startsWith("Phone chargers") || it.startsWith("Your medicines") })
        // Packed adapters need no buying.
        assertFalse(labels(s, TripList.BUY).any { it.startsWith("Plug") })
        assertFalse(labels(s, TripList.LEAVE).contains("Put the bins out"))
        assertTrue(TripSuggestRules.covers("Sun cream SPF 50", listOf("sun cream")))
        assertTrue(TripSuggestRules.covers("2 phone chargers", listOf("charger")))
        assertFalse(TripSuggestRules.covers("Chargrilled snacks", listOf("charger")))
    }

    @Test
    fun theForecastBringsSunCreamSwimwearAndAnUmbrella() {
        val f = WeatherForecast(
            "Lisbon", emptyList(),
            listOf(
                WeatherDay(wed, 18, 26, 0, 10), WeatherDay(wed + 1, 17, 25, 61, 70),
                WeatherDay(wed + 2, 18, 24, 2, 20), WeatherDay(wed + 3, 19, 27, 0, 5),
            ),
            asked = "Lisbon",
        )
        val s = TripSuggestRules.suggest(trip(), emptyList(), mon, about = family, forecast = f)
        val pack = s.first { it.list == TripList.PACK }
        assertEquals("It's 17–27° in Lisbon with rain likely on 1 of 4 days. For 5 days with 4 people, worth packing:", pack.lead)
        assertTrue(pack.chips.any { it.label == "Swimwear × 4" })
        assertEquals("Sun cream", labels(s, TripList.BUY).first())
        // A forecast for somewhere else is not used.
        val other = TripSuggestRules.suggest(trip(), emptyList(), mon, about = family, forecast = f.copy(asked = "Porto"))
        assertFalse(labels(other, TripList.BUY).contains("Sun cream"))
        // Cold and wet: coats and an umbrella, no swimwear.
        val cold = f.copy(days = listOf(WeatherDay(wed, 2, 9, 61, 80)))
        val c = labels(TripSuggestRules.suggest(trip(), emptyList(), mon, about = family, forecast = cold), TripList.PACK)
        assertTrue(c.contains("Warm coats × 4"))
        assertTrue(c.contains("Travel umbrella"))
        assertFalse(c.any { it.startsWith("Swimwear") })
    }

    @Test
    fun aFlightBringsBoardingPassesAndCheckInTheNightBeforeAtItsTime() {
        val flight = TripBooking("b1", "t1", BookingKind.FLIGHT, "Flight TP1351", wed, null, 9 * 60 + 40, null, 0L)
        val s = TripSuggestRules.suggest(trip(bookings = listOf(flight), leave = 6 * 60 + 30), emptyList(), mon, about = family)
        assertTrue(labels(s, TripList.PACK).contains("Boarding passes"))
        assertTrue(labels(s, TripList.BUY).contains("Travel-size toiletries"))
        val leave = s.first { it.list == TripList.LEAVE }
        assertEquals("You leave home at 06:30. Worth doing before you go:", leave.lead)
        val check = leave.chips.first()
        assertEquals("Check in online · Tue 09:40", check.label)
        assertEquals(TripRemind(-1, 9 * 60 + 40), check.entry.remind)
        assertEquals("Check in online, remind Tue 09:40", check.spoken)
    }

    @Test
    fun theLastTripsThingsAreOfferedOnceThisTripHasLists() {
        val paris = trip(name = "Paris", start = d(8, 1), end = d(8, 5), id = "t0")
        val t = trip()
        val items = listOf(
            item(paris.id, TripList.PACK, "Sun hats"), item(paris.id, TripList.PACK, "Passports"), item(paris.id, TripList.PACK, "Kindle"),
            item(t.id, TripList.PACK, "T-shirts"),
        )
        val s = TripSuggestRules.suggest(t, items, mon, listOf(paris, t), family)
        val pack = s.first { it.list == TripList.PACK }
        assertTrue(pack.lead.endsWith("Some are from your Paris trip."))
        // After the documents, so they show on a full card; Passports isn't doubled.
        assertEquals(listOf("Passports × 4", "GHIC cards × 4", "Travel insurance", "Sun hats", "Kindle"), pack.chips.take(5).map { it.label })
        assertEquals("last:pack:sun-hat", pack.chips[3].key)
        // With nothing on this trip's lists, Start from last trip offers them all instead.
        val fresh = TripSuggestRules.suggest(t, items.dropLast(1), mon, listOf(paris, t), family)
        assertFalse(fresh.first { it.list == TripList.PACK }.chips.any { it.key.startsWith("last:") })
    }

    @Test
    fun notNowPutsThemAwayOnThisTripOnBothDevices() {
        val world = SyncWorld()
        val cal = LocalCalendar.fixedOffset(0)
        var k = 0
        world.clock.nowMs = cal.toEpochMs(mon, 600)
        val fold = world.device("android")
        val mac = world.device("mac")
        val tFold = Trips(fold.replica, { "t${k++}" }, { world.clock.nowMs }, cal)
        val tMac = Trips(mac.replica, { "t${k++}" }, { world.clock.nowMs }, cal)
        assertFalse(tFold.add("Lisbon 28 Oct – 1 Nov").refused)
        val id = tFold.items().single().id
        assertTrue(tFold.dismissSuggestions(id, listOf("pack:passports", "pack:ghic")))
        assertFalse(tFold.dismissSuggestions(id, listOf("pack:passports")))
        fold.sync(); mac.sync()
        val onMac = tMac.items().single()
        assertEquals(setOf("pack:passports", "pack:ghic"), onMac.suggestNo)
        val pack = labels(TripSuggestRules.suggest(onMac, emptyList(), mon), TripList.PACK)
        assertFalse(pack.any { it.startsWith("Passports") || it.startsWith("GHIC") })
        assertTrue(pack.contains("Travel insurance"))
        // Kept to the newest few.
        val many = TripSuggestRules.dismissed(emptySet(), (1..100).map { "k$it" })
        assertEquals(TripSuggestRules.MAX_DISMISSED, many.size)
        assertEquals("k100", many.last())
    }

    @Test
    fun aTripThatIsOverSuggestsNothing() {
        assertTrue(TripSuggestRules.suggest(trip(), emptyList(), sun + 1).isEmpty())
        assertNull(TripListRules.view(trip(), emptyList(), sun + 1).suggestion(TripList.PACK))
        assertNotNull(TripListRules.view(trip(), emptyList(), mon, about = family).suggestion(TripList.PACK))
    }
}
