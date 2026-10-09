package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places item 2 (Meka 2026-10-09): home Biggleswade, work Canary Wharf; both on office days. */
class PlacesTest {
    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val thu = fri - 1
    private val sat = fri + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun hours(day: Long, vararg h: Triple<Int, Int, Int>) =
        h.mapIndexed { i, (t, c, p) -> WeatherHour(at(day, 0) + i * WeatherCodec.HOUR_MS, t, c, p) }

    private fun dry(n: Int, t: Int) = Array(n) { Triple(t, 2, 10) }
    private fun wet(n: Int, t: Int, code: Int = 61) = Array(n) { Triple(t, code, 70) }

    private val home = WeatherForecast("Biggleswade", hours(fri, *dry(24, 9)), listOf(WeatherDay(fri, 6, 13, 2, 10)))
    /** Dry until 15:00, light rain 15:00–18:00, dry after. */
    private val work = WeatherForecast("Canary Wharf", hours(fri, *dry(9, 12), *dry(6, 14), *wet(3, 13), *dry(6, 11)),
        listOf(WeatherDay(fri, 10, 15, 61, 70), WeatherDay(sat, 11, 16, 3, 20)))

    private val hoursMeka = WorkHours(WorkSchedule.DEFAULT)
    private val office = PlacesRules.officeWindow(hoursMeka, fri, cal)!!

    @Test
    fun anOfficeDayIsAWorkDayAwayFromHome() {
        assertEquals(OfficeWindow(at(fri, 9), at(fri, 17, 30)), office)
        // Thursday is the short day.
        assertEquals(OfficeWindow(at(thu, 9), at(thu, 15, 30)), PlacesRules.officeWindow(hoursMeka, thu, cal))
        // Not at the weekend, not on a work-from-home day, not when work was switched off for the day.
        assertNull(PlacesRules.officeWindow(hoursMeka, sat, cal))
        assertNull(PlacesRules.officeWindow(hoursMeka.copy(homeDays = setOf(fri)), fri, cal))
        assertNull(PlacesRules.officeWindow(hoursMeka.copy(offDay = fri), fri, cal))
        // A night shift isn't an office day this line knows about.
        assertNull(PlacesRules.officeWindow(WorkHours(WorkSchedule(setOf(5), 22 * 60, 6 * 60)), fri, cal))
    }

    @Test
    fun bothPlacesShareOneLineUntilWorkEnds() {
        // Before leaving home: work's temperature at the start of work, and its rain from 15:00.
        assertEquals("Biggleswade 9° now · Canary Wharf 14°, light rain from 15:00 — take a coat",
            PlacesRules.placesLine(home, work, at(fri, 7, 10), office, cal))
        // At work, raining until after work ends: no "until".
        assertEquals("Biggleswade 9° now · Canary Wharf 13°, light rain — take a coat",
            PlacesRules.placesLine(home, work, at(fri, 16, 20), office, cal))
        // Raining as work starts, dry from 11:00.
        val morning = work.copy(hours = hours(fri, *dry(9, 12), *wet(2, 10), *dry(13, 12)))
        assertEquals("Biggleswade 9° now · Canary Wharf 10°, light rain until 11:00 — take a coat",
            PlacesRules.placesLine(home, morning, at(fri, 8), office, cal))
        // Wet until work ends: no "until".
        val wetDay = work.copy(hours = hours(fri, *dry(9, 12), *wet(15, 10)))
        assertEquals("Biggleswade 9° now · Canary Wharf 10°, light rain — take a coat",
            PlacesRules.placesLine(home, wetDay, at(fri, 10), office, cal))
        // Snow says wrap up.
        val snow = work.copy(hours = hours(fri, *dry(12, 1), *wet(12, 0, code = 71)))
        assertEquals("Biggleswade 9° now · Canary Wharf 1°, light snow from 12:00 — wrap up",
            PlacesRules.placesLine(home, snow, at(fri, 8), office, cal))
        // Dry through work.
        val dryDay = work.copy(hours = hours(fri, *dry(24, 16)))
        assertEquals("Biggleswade 9° now · Canary Wharf 16°, dry", PlacesRules.placesLine(home, dryDay, at(fri, 8), office, cal))
        // Rain after work doesn't count.
        val evening = work.copy(hours = hours(fri, *dry(18, 15), *wet(6, 12)))
        assertEquals("Biggleswade 9° now · Canary Wharf 15°, dry", PlacesRules.placesLine(home, evening, at(fri, 8), office, cal))
        // From the end of work, or without either forecast: nothing (Today keeps home's own line).
        assertNull(PlacesRules.placesLine(home, work, at(fri, 17, 30), office, cal))
        assertNull(PlacesRules.placesLine(home, WeatherForecast.EMPTY, at(fri, 8), office, cal))
        assertNull(PlacesRules.placesLine(WeatherForecast.EMPTY, work, at(fri, 8), office, cal))
    }

    @Test
    fun todaysLineSaysBothPlacesOnlyOnAnOfficeDay() {
        val v = WeatherRules.view(home, at(fri, 7, 10), cal, null, work, null, office)
        assertEquals("Biggleswade 9° now · Canary Wharf 14°, light rain from 15:00 — take a coat", v.nowLine)
        assertEquals("Biggleswade 9 degrees now · Canary Wharf 14 degrees, light rain from 15:00 — take a coat", v.nowSpoken)
        // No office day: home's own line.
        assertEquals("9° · partly cloudy, dry today", WeatherRules.view(home, at(fri, 7, 10), cal, null, work, null, null).nowLine)
        // After work: home's own line again.
        assertEquals("9° · partly cloudy, dry today", WeatherRules.view(home, at(fri, 18), cal, null, work, null, office).nowLine)
        assertEquals(WeatherPlaceView.WORK, v.workChoice)
    }

    @Test
    fun theRingsRainFollowsWhereMekaWillBe() {
        // Home is dry all day; work's rain 15:00–18:00 shows only while Meka is at work (until 17:30's hour).
        val merged = PlacesRules.whereYouAre(home, work, office)
        assertEquals(listOf(DayBand(15 * 60, 18 * 60, current = false)), WeatherRules.rainBands(merged, at(fri, 8), cal))
        // The 18:00 hour is home's again (dry).
        assertEquals(9, merged.hours.first { it.startMs == at(fri, 18) }.tempC)
        assertEquals(13, merged.hours.first { it.startMs == at(fri, 17) }.tempC)
        assertEquals(9, merged.hours.first { it.startMs == at(fri, 8) }.tempC)
        // No office day (or no work forecast): home's forecast as it is.
        assertEquals(home, PlacesRules.whereYouAre(home, work, null))
        assertEquals(home, PlacesRules.whereYouAre(home, WeatherForecast.EMPTY, office))
        assertTrue(WeatherRules.rainBands(home, at(fri, 8), cal).isEmpty())
    }

    @Test
    fun askHearsWorksForecastToo() {
        val lines = PlacesRules.workAskLines(work, at(fri, 10), cal)
        assertEquals("now at work in Canary Wharf: 14° · light rain from 15:00", lines[0])
        assertEquals("today at work in Canary Wharf: 10–15°, light rain from 15:00 — take a coat", lines[1])
        assertEquals("tomorrow at work in Canary Wharf: 11–16°, cloudy", lines[2])
        assertTrue(PlacesRules.workAskLines(WeatherForecast.EMPTY, at(fri, 10), cal).isEmpty())
    }

    @Test
    fun theWorkPlaceSyncsAndItsLineSaysWhatTheForecastIsDoing() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val fold = WorkPlaceStore(a.replica)
        // Canary Wharf by default: nothing stored, nothing written for it.
        assertNull(fold.wanted())
        assertTrue(fold.set("Canary Wharf"))
        assertTrue(fold.set(""))
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, WorkPlaceStore.ENTITY_ID))
        assertTrue(!fold.set("???"))
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, WorkPlaceStore.ENTITY_ID))
        assertEquals(WeatherPlaceView("Canary Wharf", "Work forecast for Canary Wharf · on office days"), PlacesRules.workView(null, work))
        assertEquals(WeatherPlaceView.WORK, PlacesRules.workView(null, WeatherForecast.EMPTY))

        // Set on the Fold, read on the Mac; on its way until the server answers.
        assertTrue(fold.set(" cambridge "))
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals("cambridge", WorkPlaceStore(m.replica).wanted())
        assertTrue(PlacesRules.workView("cambridge", work).pending)
        val found = work.copy(place = "Cambridge", asked = "cambridge")
        assertEquals(WeatherPlaceView("cambridge", "Work forecast for Cambridge · on office days"), PlacesRules.workView("cambridge", found))
        val lit = PlacesRules.workView("Xyzzy", work.copy(asked = "Xyzzy", found = false))
        assertEquals("Couldn't find “Xyzzy” — showing Canary Wharf. Try the nearest town.", lit.line)
        assertTrue(lit.lit)
        // Back to Canary Wharf from the Mac.
        assertTrue(WorkPlaceStore(m.replica).set("canary wharf"))
        m.syncWithRetry(); a.syncWithRetry()
        assertNull(fold.wanted())
        assertTrue(PlacesRules.workView(null, found).pending)

        // The server's work forecast reaches the devices in its own entity, apart from home's.
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, WeatherStore.WORK_ENTITY_ID,
            mapOf(
                WeatherFields.HOURS to WeatherCodec.encodeHours(work.hours).fv(),
                WeatherFields.DAYS to WeatherCodec.encodeDays(work.days).fv(),
                WeatherFields.PLACE to "Canary Wharf".fv(),
            ),
        )
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals(work, WeatherStore(m.replica, WeatherStore.WORK_ENTITY_ID).forecast())
        assertEquals(WeatherForecast.EMPTY, WeatherStore(m.replica).forecast())
    }
}
