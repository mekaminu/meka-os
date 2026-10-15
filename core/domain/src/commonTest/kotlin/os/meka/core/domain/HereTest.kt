package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places item 3 (Meka 2026-10-09): "where I am now", approximate and on demand only. */
class HereTest {
    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    @Test
    fun aPointLeavesTheDeviceRoundedToAboutAKilometre() {
        assertEquals(Coord(52.40, -1.50), HereRules.round(52.4012, -1.5021))
        assertEquals(Coord(51.7, -1.2), HereRules.round(51.7012, -1.2034))
        assertEquals(Coord(-33.87, 151.21), HereRules.round(-33.8688, 151.2093))
        // Rounding moves a point by well under a kilometre.
        val exact = Coord(52.13456, -0.31549)
        assertTrue(HereRules.distanceKm(exact, HereRules.round(exact.lat, exact.lon)!!) < 0.8)
        assertTrue(HereRules.isRounded(HereRules.round(52.13456, -0.31549)!!))
        assertFalse(HereRules.isRounded(exact))
        // Nothing that isn't a place on Earth is ever sent.
        assertNull(HereRules.round(Double.NaN, 0.0))
        assertNull(HereRules.round(91.0, 0.0))
        assertNull(HereRules.round(0.0, -180.5))
    }

    @Test
    fun awayMeansFartherThanFifteenKilometresFromHomeAndWork() {
        // Invented places (rule 13): home Oakfield and work Riverside, about 85 km apart, as the server locates them.
        val home = Coord(52.40, -1.50)
        val work = Coord(51.70, -1.20)
        val known = listOf(home, work)
        val d = HereRules.distanceKm(home, work)
        assertTrue(d in 75.0..90.0, "$d")
        // At home, 4 km from home, at work, 6 km from work: not away.
        assertFalse(HereRules.isAway(home, known))
        assertFalse(HereRules.isAway(Coord(52.43, -1.48), known))
        assertFalse(HereRules.isAway(work, known))
        assertFalse(HereRules.isAway(Coord(51.70, -1.29), known))
        // 25 km from home, and Manchester: away.
        assertTrue(HereRules.isAway(Coord(52.21, -1.30), known))
        assertTrue(HereRules.isAway(Coord(53.48, -2.24), known))
        // With no known places everywhere is away.
        assertTrue(HereRules.isAway(home, emptyList()))
    }

    @Test
    fun onlyAQuestionAboutHereAsksForTheLocation() {
        listOf(
            "what's the weather here?", "Will it rain where I am?", "is it cold where i'm going to be", "Is it cold outside",
            "anything open near me", "coffee around me", "nearby pharmacies", "weather at my location", "HERE",
        ).forEach { assertTrue(HereRules.asksAboutHere(it), it) }
        listOf(
            "what's the weather tomorrow?", "Here's the list", "here’s what I need", "can you hear me", "is there rain",
            "where is my dentist", "will it rain at training", "Somewhere nice",
        ).forEach { assertFalse(HereRules.asksAboutHere(it), it) }
    }

    @Test
    fun theDeviceLocatesOnlyWhenAllowedAndNotWhileAFixIsFresh() {
        val now = at(fri, 9)
        // Off, or the phone hasn't allowed location: never.
        assertFalse(HereRules.shouldLocate(false, true, "weather here?", null, now))
        assertFalse(HereRules.shouldLocate(true, false, "weather here?", null, now))
        // A question about here: yes; any other question: no.
        assertTrue(HereRules.shouldLocate(true, true, "weather here?", null, now))
        assertFalse(HereRules.shouldLocate(true, true, "weather tomorrow?", null, now))
        // Today opening (no question): once, then the fix is reused for 30 minutes.
        assertTrue(HereRules.shouldLocate(true, true, null, null, now))
        assertFalse(HereRules.shouldLocate(true, true, null, now - 10 * 60_000L, now))
        assertFalse(HereRules.shouldLocate(true, true, "weather here?", now - 29 * 60_000L, now))
        assertTrue(HereRules.shouldLocate(true, true, null, now - HereRules.FIX_FRESH_MS, now))
        // A clock that went backwards doesn't keep a fix forever.
        assertTrue(HereRules.shouldLocate(true, true, null, now + 60_000L, now))
    }

    @Test
    fun awayTodaySaysNearYouAndAskHearsItIsApproximate() {
        val hours = (0 until 24).map { i ->
            val wet = i in 16..18
            WeatherHour(at(fri, 0) + i * WeatherCodec.HOUR_MS, 12, if (wet) 61 else 2, if (wet) 70 else 10)
        }
        val f = WeatherForecast("", hours, listOf(WeatherDay(fri, 8, 14, 61, 70), WeatherDay(fri + 1, 9, 15, 3, 20)))
        assertEquals("Near you · 12° · light rain from 16:00", HereRules.nowLine(f, at(fri, 10, 20), cal))
        val lines = HereRules.askLines(f, at(fri, 10, 20), cal)
        assertEquals(3, lines.size)
        assertEquals("now where Meka is (approximate location): 12° · light rain from 16:00", lines[0])
        assertTrue(lines[1].startsWith("today where Meka is: 8–14°"), lines[1])
        assertTrue(lines[2].startsWith("tomorrow where Meka is: 9–15°"), lines[2])
        assertNull(HereRules.nowLine(WeatherForecast.EMPTY, at(fri, 10), cal))
        assertTrue(HereRules.askLines(WeatherForecast.EMPTY, at(fri, 10), cal).isEmpty())
    }

    @Test
    fun theSettingIsOffByDefaultAndLitWhenTheMissingPermissionStopsIt() {
        val off = HereRules.setting(on = false, permitted = false)
        assertFalse(off.on)
        assertFalse(off.lit)
        assertEquals("Turn on", off.actionLabel)
        assertEquals("Off · the weather is home's and work's", off.statusLine)
        val needs = HereRules.setting(on = true, permitted = false)
        assertTrue(needs.lit)
        assertEquals("Turn off", needs.actionLabel)
        val on = HereRules.setting(on = true, permitted = true)
        assertFalse(on.lit)
        assertTrue(on.statusLine.startsWith("On · "))
        assertTrue("rounded to about 1 km" in on.privacy && "keeps nothing" in on.privacy && "never synced" in on.privacy)
    }

    @Test
    fun aFixIsUsedForHalfAnHourAndOnlyAwaySaysNearYouOnToday() {
        val hours = (0 until 24).map { WeatherHour(at(fri, 0) + it * WeatherCodec.HOUR_MS, 11, 2, 10) }
        val f = WeatherForecast(HereRules.NEAR_YOU, hours, listOf(WeatherDay(fri, 7, 13, 2, 10), WeatherDay(fri + 1, 8, 14, 3, 20)))
        val t = at(fri, 10)
        val away = HereFix(Coord(52.21, 0.12), f, away = true, atMs = t)
        assertEquals("Near you · 11° · partly cloudy, dry today", HereRules.todayLine(away, t + 5 * 60_000L, cal))
        // 30 minutes on, or a clock gone backwards, and the fix is forgotten: Today goes back to home's line.
        assertNull(HereRules.todayLine(away, t + HereRules.FIX_FRESH_MS, cal))
        assertNull(HereRules.todayLine(away, t - 60_000L, cal))
        // At home or work Today keeps its own line.
        assertNull(HereRules.todayLine(away.copy(away = false), t, cal))
        assertNull(HereRules.todayLine(null, t, cal))
        // An empty answer is no fix.
        assertNull(HereRules.fresh(away.copy(forecast = WeatherForecast.EMPTY), t))
        // Ask hears it only for a question about here, and not from a stale fix.
        assertEquals(3, HereRules.askLinesFor("is it cold outside?", away, t, cal).size)
        assertEquals(3, HereRules.askLinesFor("weather here?", away.copy(away = false), t, cal).size)
        assertTrue(HereRules.askLinesFor("weather tomorrow?", away, t, cal).isEmpty())
        assertTrue(HereRules.askLinesFor("weather here?", away, t + HereRules.FIX_FRESH_MS, cal).isEmpty())
    }
}
