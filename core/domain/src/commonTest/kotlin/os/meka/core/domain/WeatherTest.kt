package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val sat = fri + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    /** Hours from Friday 00:00 local, one per entry: (°C, code, rain %). */
    private fun hours(vararg h: Triple<Int, Int, Int>, from: Long = at(fri, 0)) =
        h.mapIndexed { i, (t, c, p) -> WeatherHour(from + i * WeatherCodec.HOUR_MS, t, c, p) }

    private fun dry(n: Int, t: Int = 14) = Array(n) { Triple(t, 2, 10) }
    private fun wet(n: Int, t: Int = 12) = Array(n) { Triple(t, 61, 70) }
    private val days = listOf(WeatherDay(fri, 8, 15, 61, 70), WeatherDay(sat, 9, 15, 61, 80), WeatherDay(sat + 1, 7, 13, 3, 10))

    private fun forecast(vararg h: Triple<Int, Int, Int>) = WeatherForecast("Biggleswade", hours(*h), days)

    @Test
    fun theForecastRoundTripsAndSkipsWhatItCantRead() {
        val f = forecast(*dry(3), *wet(2))
        val hoursText = WeatherCodec.encodeHours(f.hours)
        assertEquals("${at(fri, 0) / WeatherCodec.HOUR_MS}|14,2,10;14,2,10;14,2,10;12,61,70;12,61,70", hoursText)
        assertEquals(f.hours, WeatherCodec.decodeHours(hoursText))
        val daysText = WeatherCodec.encodeDays(days.reversed())
        assertEquals("2026-10-09=8,15,61,70;2026-10-10=9,15,61,80;2026-10-11=7,13,3,10", daysText)
        assertEquals(days, WeatherCodec.decodeDays(daysText))
        // Text from the network: a bad hour is skipped (its neighbours keep their own times), a bad day dropped.
        val bad = WeatherCodec.decodeHours("${at(fri, 0) / WeatherCodec.HOUR_MS}|14,2,10;x;15,3,200")
        assertEquals(listOf(at(fri, 0), at(fri, 2)), bad.map { it.startMs })
        assertEquals(100, bad.last().rainChance)
        assertEquals(1, WeatherCodec.decodeDays("2026-10-09=8,15,61,70;2026-02-30=1,2,3,4;junk").size)
        assertTrue(WeatherCodec.decodeHours("nope").isEmpty())
        assertTrue(WeatherCodec.decodeHours(null).isEmpty())
        // A run stops at a gap; readings are rounded so a small wobble writes nothing.
        val gap = listOf(WeatherHour(at(fri, 0), 1, 0, 0), WeatherHour(at(fri, 2), 1, 0, 0))
        assertEquals(1, WeatherCodec.decodeHours(WeatherCodec.encodeHours(gap)).size)
        assertEquals(15, WeatherCodec.temp(14.5))
        assertEquals(70, WeatherCodec.chance(67.0))
        assertEquals(0, WeatherCodec.chance(4.0))
    }

    @Test
    fun todaysLineSaysTheTemperatureNowAndWhatTheRestOfTheDayDoes() {
        // Dry now, rain from 16:00.
        val f = forecast(*dry(16), *wet(3), *dry(5))
        assertEquals("14° · light rain from 16:00", WeatherRules.nowLine(f, at(fri, 10, 25), cal))
        // Raining now, dry again from 19:00.
        assertEquals("12° · light rain until 19:00", WeatherRules.nowLine(f, at(fri, 17, 5), cal))
        // Dry for the rest of the day.
        assertEquals("14° · partly cloudy, dry today", WeatherRules.nowLine(f, at(fri, 20), cal))
        // Raining until midnight: no "until".
        assertEquals("12° · light rain", WeatherRules.nowLine(forecast(*dry(20), *wet(4)), at(fri, 21), cal))
        // The last hour of the day has no rest to speak of.
        assertEquals("14° · partly cloudy", WeatherRules.nowLine(forecast(*dry(24)), at(fri, 23, 30), cal))
        // A high chance counts as rain whatever the code; a wet code with a low chance doesn't.
        val chancey = forecast(*dry(12), Triple(13, 3, 60), Triple(13, 61, 20))
        assertEquals("14° · rain from 12:00", WeatherRules.nowLine(chancey, at(fri, 9), cal))
        // No forecast for now (an old one, or none yet): no line.
        assertNull(WeatherRules.nowLine(forecast(*dry(3)), at(fri, 10), cal))
        assertEquals(WeatherView.EMPTY, WeatherRules.view(WeatherForecast.EMPTY, at(fri, 10), cal))
        val v = WeatherRules.view(f, at(fri, 10), cal)
        assertEquals("14 degrees · light rain from 16:00", v.nowSpoken)
        assertEquals("Biggleswade", v.place)
    }

    @Test
    fun tomorrowsLineGivesTheRangeAndWhenTheRainComes() {
        // Saturday: dry morning, rain from 15:00 to the evening.
        val f = forecast(*dry(24), *dry(15, 10), *wet(9, 11))
        assertEquals("Tomorrow 9–15°, light rain from 15:00 — take a coat", WeatherRules.tomorrowLine(f, sat, cal))
        assertEquals("Tomorrow 9–15°, light rain from 15:00 — take a coat", WeatherRules.view(f, at(fri, 21), cal).tomorrowLine)
        // Wet through the waking day.
        assertEquals("Tomorrow 9–15°, light rain all day — take a coat", WeatherRules.tomorrowLine(forecast(*dry(24), *wet(24)), sat, cal))
        // Rain only at night doesn't need a coat; a wet day code with dry waking hours is "mostly dry".
        assertEquals("Tomorrow 9–15°, mostly dry", WeatherRules.tomorrowLine(forecast(*dry(24), *wet(6), *dry(18)), sat, cal))
        // Snow says wrap up.
        assertEquals("Tomorrow 9–15°, light snow from 08:00 — wrap up",
            WeatherRules.tomorrowLine(forecast(*dry(32), Triple(0, 71, 70)), sat, cal))
        // Beyond the hours: the day's own summary.
        assertEquals("Tomorrow 7–13°, cloudy", WeatherRules.tomorrowLine(forecast(*dry(24)), sat + 1, cal))
        assertEquals("Sun 7–13°, cloudy", WeatherRules.dayLine("Sun", forecast(), sat + 1, cal))
        assertEquals("Tomorrow 9–15°, light rain likely — take a coat", WeatherRules.tomorrowLine(forecast(*dry(24)), sat, cal))
        assertNull(WeatherRules.tomorrowLine(forecast(), sat + 5, cal))
    }

    @Test
    fun askHearsWhereNowEachDayAndEveryThirdHourThroughTomorrow() {
        val f = forecast(*dry(16), *wet(3), *dry(29))
        val lines = WeatherRules.askLines(f, at(fri, 10, 20), cal)
        assertEquals("now in Biggleswade: 14° · light rain from 16:00", lines[0])
        assertEquals("today (Fri 9 Oct): 8–15° light rain, up to 70 % chance of rain", lines[1])
        assertEquals("tomorrow (Sat 10 Oct): 9–15° light rain, up to 80 % chance of rain", lines[2])
        assertEquals("Sun 11 Oct: 7–13° cloudy, up to 10 % chance of rain", lines[3])
        assertEquals("today 12:00 14° partly cloudy, 10 % chance of rain", lines[4])
        assertTrue("today 18:00 12° light rain, 70 % chance of rain" in lines)
        assertTrue(lines.any { it.startsWith("tomorrow 21:00") })
        assertTrue(lines.all { it.length <= AskRules.MAX_LINE })
        assertTrue(lines.size <= WeatherRules.MAX_ASK_LINES)
        assertTrue(WeatherRules.askLines(WeatherForecast.EMPTY, at(fri, 10), cal).isEmpty())
    }

    @Test
    fun theServersForecastReachesBothDevices() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val f = forecast(*dry(24))
        // What the server writes; written on a device replica here to stand in for it.
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, WeatherStore.ENTITY_ID,
            mapOf(
                WeatherFields.HOURS to WeatherCodec.encodeHours(f.hours).fv(),
                WeatherFields.DAYS to WeatherCodec.encodeDays(f.days).fv(),
                WeatherFields.PLACE to "Biggleswade".fv(),
            ),
        )
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals(f, WeatherStore(m.replica).forecast())
        assertEquals(WeatherForecast.EMPTY, WeatherStore(world.device("other").replica).forecast())
    }

    @Test
    fun aTypedPlaceIsKeptOnlyWhenItCanBeATown() {
        assertEquals("Bedford", WeatherPlaceRules.normalize("  Bedford "))
        assertEquals("St Neots", WeatherPlaceRules.normalize("St   Neots"))
        assertEquals("Stratford-upon-Avon", WeatherPlaceRules.normalize("Stratford-upon-Avon"))
        assertEquals("King's Lynn", WeatherPlaceRules.normalize("King's Lynn"))
        assertNull(WeatherPlaceRules.normalize(""))
        assertNull(WeatherPlaceRules.normalize("   "))
        assertNull(WeatherPlaceRules.normalize("12345"))
        // A pasted line break is just a space; anything a town name doesn't have is refused.
        assertEquals("Bedford Kempston", WeatherPlaceRules.normalize("Bedford\nKempston"))
        assertNull(WeatherPlaceRules.normalize("Bedford; drop"))
        assertNull(WeatherPlaceRules.normalize("<script>"))
        assertNull(WeatherPlaceRules.normalize("x".repeat(61)))
        assertTrue(WeatherPlaceRules.isHome(null))
        assertTrue(WeatherPlaceRules.isHome("biggleswade"))
    }

    @Test
    fun thePlaceSyncsAndItsLineSaysWhatTheForecastIsDoing() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val fold = WeatherPlaceStore(a.replica)
        val home = forecast(*dry(24))
        // Home by default: nothing stored, nothing written for home.
        assertNull(fold.wanted())
        assertEquals(WeatherPlaceView.HOME, WeatherPlaceRules.view(null, home))
        assertTrue(fold.set("Biggleswade"))
        assertTrue(fold.set(""))
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, WeatherPlaceStore.ENTITY_ID))
        // Something that can't be a place is refused and nothing is written.
        assertTrue(!fold.set("???"))
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, WeatherPlaceStore.ENTITY_ID))

        // Set on the Fold, read on the Mac.
        assertTrue(fold.set(" bedford "))
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals("bedford", WeatherPlaceStore(m.replica).wanted())
        // Until the server answers, the line says it's on its way.
        val waiting = WeatherPlaceRules.view("bedford", home)
        assertEquals("Finding “bedford”… the forecast follows within a few minutes", waiting.line)
        assertTrue(waiting.pending)
        assertEquals("bedford", waiting.name)
        // Found (the geocoder's own spelling).
        val found = home.copy(place = "Bedford", asked = "bedford", found = true)
        assertEquals(WeatherPlaceView("bedford", "Forecast for Bedford · from Open-Meteo"), WeatherPlaceRules.view("bedford", found))
        // Not found: still home's forecast, lit.
        val missing = home.copy(asked = "Xyzzy", found = false)
        val lit = WeatherPlaceRules.view("Xyzzy", missing)
        assertEquals("Couldn't find “Xyzzy” — showing Biggleswade. Try the nearest town.", lit.line)
        assertTrue(lit.lit)
        // Back home: on its way until the server forecasts home again.
        assertTrue(WeatherPlaceStore(m.replica).set("Biggleswade"))
        m.syncWithRetry(); a.syncWithRetry()
        assertNull(fold.wanted())
        assertTrue(WeatherPlaceRules.view(null, found).pending)
        assertEquals(WeatherPlaceView.HOME, WeatherPlaceRules.view(null, home))

        // The server's answer reaches the store (asked, found) and the view carries the setting's line.
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, WeatherStore.ENTITY_ID,
            mapOf(
                WeatherFields.HOURS to WeatherCodec.encodeHours(home.hours).fv(),
                WeatherFields.DAYS to WeatherCodec.encodeDays(home.days).fv(),
                WeatherFields.PLACE to "Biggleswade".fv(),
                WeatherFields.ASKED to "Xyzzy".fv(),
                WeatherFields.FOUND to false.fv(),
            ),
        )
        assertTrue(fold.set("Xyzzy"))
        val read = WeatherStore(a.replica).forecast()
        assertEquals("Xyzzy", read.asked)
        assertTrue(!read.found)
        assertTrue(WeatherRules.view(read, at(fri, 9), cal, fold.wanted()).placeChoice.lit)
        assertEquals(WeatherPlaceView.HOME.line, WeatherRules.view(WeatherForecast.EMPTY, at(fri, 9), cal).placeChoice.line)
    }

    @Test
    fun aDaysGlanceDropsTheLabelForPlacesThatAlreadyNameTheDay() {
        val f = forecast(*dry(24), *dry(15), *wet(3), *dry(6))
        assertEquals("9–15°, light rain from 15:00 — take a coat", WeatherRules.dayGlance(f, sat, cal))
        assertEquals("7–13°, cloudy", WeatherRules.dayGlance(forecast(*dry(24)), sat + 1, cal))
        assertNull(WeatherRules.dayGlance(f, sat + 5, cal))
    }

    @Test
    fun rainStillToComeTodayBecomesBandsOnTheDayRing() {
        // Wet 06–08 (over by 10:00), 16–19 and 22–24.
        val f = forecast(*dry(6), *wet(2), *dry(8), *wet(3), *dry(3), *wet(2), *dry(24))
        assertEquals(listOf(DayBand(16 * 60, 19 * 60), DayBand(22 * 60, 24 * 60)), WeatherRules.rainBands(f, at(fri, 10), cal))
        // Raining now: the band is current; tomorrow's rain isn't on today's ring.
        assertEquals(listOf(DayBand(16 * 60, 19 * 60, current = true), DayBand(22 * 60, 24 * 60)), WeatherRules.rainBands(f, at(fri, 17, 30), cal))
        assertEquals(listOf(DayBand(6 * 60, 8 * 60, current = true), DayBand(16 * 60, 19 * 60), DayBand(22 * 60, 24 * 60)),
            WeatherRules.rainBands(f, at(fri, 7), cal))
        assertTrue(WeatherRules.rainBands(forecast(*dry(24), *wet(24)), at(fri, 10), cal).isEmpty())
        assertTrue(WeatherRules.rainBands(WeatherForecast.EMPTY, at(fri, 10), cal).isEmpty())
        // A screen reader hears it after the ring's own line.
        val ring = DayRing(emptyList(), 10 * 60, 0, 0, rain = WeatherRules.rainBands(f, at(fri, 10), cal))
        assertTrue(ring.spokenLine.endsWith(" Rain from 16:00."), ring.spokenLine)
        assertTrue(ring.copy(rain = listOf(DayBand(600, 660, current = true))).spokenLine.endsWith(" Raining now."))
    }

    private fun event(id: String, title: String, h: Int, m: Int = 0, place: String? = "SG18 Sports Ground, Biggleswade", mins: Int = 60,
                      provider: String = "google", join: String? = null, allDay: Boolean = false) =
        CalendarEvent(id, title, at(fri, h, m), at(fri, h, m) + mins * 60_000L, allDay, place, provider, "me@x", "Personal", joinUrl = join)

    @Test
    fun rainAtTodaysPlansYouGoOutForIsANudgeInTheNextDigest() {
        // Dry until 17:00, light rain 17:00–20:00, dry after.
        val f = forecast(*dry(17), *wet(3), *dry(4))
        val training = event("t", "Training", 17, 30)
        val events = listOf(
            training,
            event("call", "Standup", 18, join = "https://meet.example/abc"),   // a video call: you don't go out
            event("home", "Dinner", 18, place = null),                          // no place
            event("cl", "Barça v Sevilla", 19, provider = "fixtures"),          // watched, not attended
            event("all", "Away day", 0, allDay = true),
            event("lunch", "Lunch with Ade", 12),                               // dry
            event("late", "Five-a-side", 19, 45),                               // rain until 20:00
            event("hidden", "Choir", 18),
        )
        val marks = EventMarks(setOf("hidden"), emptyMap())
        val gym = BookedSession("h1", "Gym", "Push", fri, at(fri, 16, 45), at(fri, 17, 45), "Today 16:45")
        val sessions = SessionsView(emptyList(), listOf(gym), emptyMap())
        val n = WeatherRules.notices(f, events, marks, sessions, at(fri, 9), cal)
        assertEquals(
            listOf("Light rain at 17:00 — Gym · Push", "Light rain at 17:30 — Training at SG18 Sports Ground", "Light rain at 19:45 — Five-a-side at SG18 Sports Ground"),
            n.map { it.title },
        )
        val t = n[1]
        assertEquals(NoticeSource.WEATHER, t.source)
        assertEquals(NoticeTier.DIGEST, t.tier)
        assertEquals("weather:event:t:${at(fri, 17, 30)}", t.key)
        assertEquals(at(fri, 0), t.atMs)
        assertEquals(at(fri, 17, 30), t.expiresAtMs)

        // The midday digest sums them; by the evening digest (18:00) only the one still ahead is left.
        val settings = NotificationSettings.DEFAULT
        val midday = Governor.evaluate(n, settings, DeviceAlerts.ALL, GovernorState(), at(fri, 12, 30), cal)
        assertEquals("Midday digest · 3 things", midday.digest?.title)
        assertEquals("rain on 3 plans", midday.digest?.summary)
        assertEquals("Light rain at 17:30 — Training at SG18 Sports Ground", midday.digest?.lines?.get(1))
        assertTrue(midday.post.isEmpty())
        val evening = Governor.evaluate(WeatherRules.notices(f, events, marks, sessions, at(fri, 18), cal), settings, DeviceAlerts.ALL, midday.state, at(fri, 18), cal)
        assertEquals(listOf("Light rain at 19:45 — Five-a-side at SG18 Sports Ground"), evening.digest?.lines)
        // Lowered to app only, nothing is posted.
        val off = settings.copy(tiers = mapOf(NoticeSource.WEATHER to NoticeTier.SILENT))
        assertNull(Governor.evaluate(n, off, DeviceAlerts.ALL, GovernorState(), at(fri, 12, 30), cal).digest)
    }

    @Test
    fun aDryForecastOrNoForecastNudgesNothingAndRainJustBeforeYouLeaveCounts() {
        val events = listOf(event("t", "Training", 18))
        assertTrue(WeatherRules.notices(forecast(*dry(24)), events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 9), cal).isEmpty())
        assertTrue(WeatherRules.notices(WeatherForecast.EMPTY, events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 9), cal).isEmpty())
        // Rain 17:00–18:00 only: the walk there at 17:30 is wet, so it's told at the start.
        val before = WeatherRules.notices(forecast(*dry(17), *wet(1), *dry(6)), events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 9), cal)
        assertEquals(listOf("Light rain at 18:00 — Training at SG18 Sports Ground"), before.map { it.title })
        // Rain from 16:00 to 17:00 is over before you leave (17:30).
        assertTrue(WeatherRules.notices(forecast(*dry(16), *wet(1), *dry(7)), events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 9), cal).isEmpty())
        // Snow says so; a started plan isn't told; tomorrow's plans wait for tomorrow.
        val snow = forecast(*dry(18), *Array(2) { Triple(0, 73, 80) }, *dry(4))
        assertEquals("Snow at 18:00 — Training at SG18 Sports Ground", WeatherRules.notices(snow, events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 9), cal).single().title)
        assertTrue(WeatherRules.notices(snow, events, EventMarks.NONE, SessionsView.EMPTY, at(fri, 18, 5), cal).isEmpty())
        assertTrue(WeatherRules.notices(snow, events, EventMarks.NONE, SessionsView.EMPTY, at(fri - 1, 9), cal).isEmpty())
        // A long place is cut at its first comma and to 40 characters.
        assertEquals("SG18", WeatherRules.shortPlace("SG18, Biggleswade"))
        assertEquals(40, WeatherRules.shortPlace("A".repeat(60))!!.length)
        assertNull(WeatherRules.shortPlace(" , x"))
    }

    @Test
    fun theNudgesComeThroughTheNoticeSources() {
        val f = forecast(*dry(17), *wet(3), *dry(4))
        val ns = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, CalendarAgenda.let { TodayProjection.project(emptyList(), at(fri, 9), it.window(fri, cal), emptyList(), cal) },
            at(fri, 9), cal, events = listOf(event("t", "Training", 17, 30)), forecast = f,
        )
        assertEquals(listOf("Light rain at 17:30 — Training at SG18 Sports Ground"), ns.filter { it.source == NoticeSource.WEATHER }.map { it.title })
    }
}
