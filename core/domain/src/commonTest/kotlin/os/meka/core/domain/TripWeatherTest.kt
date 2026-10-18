package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode completeness, slice 3: the destination's weather in the brief, in the destination's own time. */
class TripWeatherTest {
    private val mon = 20745L // Monday 19 October 2026
    private val uk = LocalCalendar.fixedOffset(3_600_000L)
    private val spain = LocalCalendar.fixedOffset(2 * 3_600_000L)
    private val clocks = TripClocks(uk) { id -> if (id == "Europe/Madrid") spain else null }
    private fun trip(name: String, start: Long, end: Long, vararg b: TripBooking) = TripItem("t", name, start, end, 0, b.toList())
    private fun flight(day: Long, minute: Int) = TripBooking("f", "t", BookingKind.FLIGHT, "Flight BA478", day, null, minute, null, 0)

    /** Monday dry until 15:00 Spanish time (14:00 UK), then light rain; 18–24°. */
    private fun forecast(asked: String? = "Barcelona", found: Boolean = true): WeatherForecast {
        val start = spain.toEpochMs(mon, 0)
        val hours = (0 until 24).map { h -> WeatherHour(start + h * 3_600_000L, 20, if (h >= 15) 61 else 0, if (h >= 15) 80 else 0) }
        return WeatherForecast("Barcelona", hours, listOf(WeatherDay(mon, 18, 24, 61, 80), WeatherDay(mon + 1, 17, 22, 0, 0)), asked, found)
    }

    @Test
    fun theTripOnTodayOrTomorrowNamesThePlaceToForecast() {
        assertEquals("Barcelona", TripWeatherRules.wanted(listOf(trip("Barcelona with the boys", mon, mon + 2)), mon)?.label)
        assertEquals("Europe/Madrid", TripWeatherRules.wanted(listOf(trip("Barcelona", mon, mon + 2)), mon - 1)?.id)
        // An evening flight on the first day: that day he's not away yet, so it's "tomorrow" the day after.
        assertEquals("Barcelona", TripWeatherRules.wanted(listOf(trip("Barcelona", mon, mon + 2, flight(mon, 18 * 60))), mon)?.label)
        // Two days ahead, over, or a place not on the list: nothing.
        assertNull(TripWeatherRules.wanted(listOf(trip("Barcelona", mon, mon + 2)), mon - 2))
        assertNull(TripWeatherRules.wanted(listOf(trip("Barcelona", mon, mon + 2)), mon + 3))
        assertNull(TripWeatherRules.wanted(listOf(trip("Cornwall", mon, mon + 2)), mon))
    }

    @Test
    fun awayTheBriefSaysTheDestinationsDayInItsOwnTime() {
        val w = TripWeatherRules.brief(forecast(), "Barcelona", "Barcelona", mon, clocks)
        assertEquals("In Barcelona today, 18–24°, light rain from 15:00 — take a coat.", w.today)
        assertEquals("Barcelona · 18–24°, light rain from 15:00 — take a coat", w.paneLine)
        assertNull(w.tomorrow)
        val before = TripWeatherRules.brief(forecast(), null, "Barcelona", mon - 1, clocks)
        assertEquals("Tomorrow in Barcelona, 18–24°, light rain from 15:00 — take a coat.", before.tomorrow)
        assertNull(before.today)
        // A forecast still for another place, or not found, says nothing.
        assertEquals(TripWeatherRules.BriefWeather.NONE, TripWeatherRules.brief(forecast(asked = "Lisbon"), "Barcelona", null, mon, clocks))
        assertEquals(TripWeatherRules.BriefWeather.NONE, TripWeatherRules.brief(forecast(found = false), "Barcelona", null, mon, clocks))
        assertEquals(TripWeatherRules.BriefWeather.NONE, TripWeatherRules.brief(forecast(), null, null, mon, clocks))
        // Ask's lines name the place and its days.
        val ask = TripWeatherRules.askLines(forecast(), listOf(trip("Barcelona", mon, mon + 2)), mon, clocks)
        assertEquals(2, ask.size)
        assertTrue(ask.first().startsWith("Barcelona (local time) "), ask.first())
        assertTrue(ask.all { it.length <= 160 })
    }

    @Test
    fun theScriptSaysItInPlaceOfHomesWeather() {
        val base = MorningBriefView.EMPTY.copy(greeting = "Good morning", dateLabel = "Mon 19 Oct", weatherLine = "Barcelona · 18–24°, clear")
        val away = base.copy(story = BriefStory(away = "Barcelona", awayWeather = "In Barcelona today, 18–24°, clear."))
        val s = BriefScriptRules.script(away)
        assertTrue("In Barcelona today, 18 to 24 degrees, clear." in s, s)
        assertTrue("Today, Barcelona" !in s, s)
        val before = base.copy(weatherLine = "9–15°, mostly dry", story = BriefStory(tripTomorrowWeather = "Tomorrow in Barcelona, 18–24°, clear."))
        val t = BriefScriptRules.script(before)
        assertTrue(t.indexOf("Today, 9 to 15 degrees") in 0 until t.indexOf("Tomorrow in Barcelona"), t)
    }
}
