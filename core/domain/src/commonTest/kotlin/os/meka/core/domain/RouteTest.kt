package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places item 4 (Meka 2026-10-09 12:54): South Rail → Metro line, North Rail as the fallback; TfL's status. */
class RouteTest {
    // Rule 13: the route comes from About you (an invented one here), never the code.
    @BeforeTest
    fun route() { Household.useRoute(InventedRoute.TYPED) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val thu = fri - 1
    private val sat = fri + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val hours = WorkHours(WorkSchedule.DEFAULT)
    private val office = PlacesRules.officeWindow(hours, fri, cal)

    private fun snap(tl: Int?, gn: Int?, el: Int?, checked: Long = at(fri, 7)) = LineStatusSnapshot(
        listOfNotNull(tl?.let { LineState(InventedRoute.FIRST, it) }, gn?.let { LineState(InventedRoute.FALLBACK, it) },
            el?.let { LineState(InventedRoute.ONWARD, it) }), checked,
    )

    @Test
    fun theCodecKeepsOnlyWellFormedLines() {
        val lines = listOf(LineState("south-rail", 9), LineState("metro", 10), LineState("north-rail", 10))
        val s = LineStatusCodec.encode(lines)
        assertEquals("metro=10;north-rail=10;south-rail=9", s)
        assertEquals(lines.sortedBy { it.id }, LineStatusCodec.decode(s))
        // Junk from the network is skipped, never a broken line.
        assertEquals(listOf(LineState("metro", 6)), LineStatusCodec.decode("metro=6;Bad Id=3;x=;=4;south-rail=abc;victoria=150"))
        assertTrue(LineStatusCodec.decode(null).isEmpty())
        assertEquals("", LineStatusCodec.encode(listOf(LineState("<script>", 1))))
    }

    @Test
    fun severitiesReadAsCalmWordsAndTheWorstOneCounts() {
        assertEquals("good service", RouteRules.words(10))
        assertEquals("minor delays", RouteRules.words(9))
        assertEquals("severe delays", RouteRules.words(6))
        assertEquals(LineLevel.GOOD, RouteRules.level(10))
        assertEquals(LineLevel.MINOR, RouteRules.level(9))
        assertEquals(LineLevel.MAJOR, RouteRules.level(6))
        assertEquals(LineLevel.MAJOR, RouteRules.level(20))
        // A part closure outranks minor delays outranks good service, whatever order TfL lists them in.
        assertEquals(5, RouteRules.worst(listOf(10, 9, 5)))
        assertEquals(9, RouteRules.worst(listOf(10, 9)))
        assertNull(RouteRules.worst(emptyList()))
        assertEquals("Metro line", RouteRules.name("metro"))
        assertEquals("North Rail", RouteRules.name("north-rail"))
    }

    @Test
    fun theLineShowsOnAnOfficeDaysCommuteOnly() {
        val good = snap(10, 10, 10)
        assertNull(RouteRules.todayLine(good, office, at(fri, 5, 59), cal))
        val morning = RouteRules.todayLine(good, office, at(fri, 7, 10), cal)!!
        assertEquals("South Rail · Metro line · good service", morning.text)
        assertTrue(!morning.lit)
        assertEquals("Trains: South Rail, Metro line, good service", morning.spoken)
        // At work: nothing, until 45 minutes before the end; home again by 19:00.
        assertNull(RouteRules.todayLine(good, office, at(fri, 9), cal))
        assertNull(RouteRules.todayLine(good, office, at(fri, 16, 44), cal))
        val evening = RouteRules.todayLine(good.copy(checkedMs = at(fri, 16, 30)), office, at(fri, 16, 45), cal)!!
        assertEquals("Metro line · South Rail · good service", evening.text)
        assertNull(RouteRules.todayLine(good.copy(checkedMs = at(fri, 18, 30)), office, at(fri, 19), cal))
        // Thursday's short day: the evening's line from 14:45.
        val thuOffice = PlacesRules.officeWindow(hours, thu, cal)
        assertTrue(RouteRules.todayLine(good.copy(checkedMs = at(thu, 14, 30)), thuOffice, at(thu, 14, 45), cal) != null)
        // Not at the weekend or on a work-from-home day (no office window), and not on a stale status.
        assertNull(RouteRules.todayLine(good, PlacesRules.officeWindow(hours, sat, cal), at(sat, 7, 10), cal))
        assertNull(RouteRules.todayLine(good, PlacesRules.officeWindow(hours.copy(homeDays = setOf(fri)), fri, cal), at(fri, 7, 10), cal))
        assertNull(RouteRules.todayLine(good.copy(checkedMs = at(fri, 5)), office, at(fri, 7, 10), cal))
        assertNull(RouteRules.todayLine(good.copy(checkedMs = null), office, at(fri, 7, 10), cal))
        assertNull(RouteRules.todayLine(LineStatusSnapshot.EMPTY, office, at(fri, 7, 10), cal))
    }

    @Test
    fun troubleIsLitAndTheFallbackNamedWhenItRuns() {
        val tlDown = RouteRules.todayLine(snap(6, 10, 10), office, at(fri, 7, 10), cal)!!
        assertEquals("South Rail severe delays · Metro line good service — North Rail to Riverside is running", tlDown.text)
        assertTrue(tlDown.lit)
        assertEquals("Trains: South Rail severe delays, Metro line good service. North Rail to Riverside is running", tlDown.spoken)
        // Both down: no fallback to offer.
        assertEquals("South Rail suspended · Metro line good service",
            RouteRules.todayLine(snap(2, 6, 10), office, at(fri, 7, 10), cal)!!.text)
        // The onward leg alone.
        val el = RouteRules.todayLine(snap(10, 10, 9), office, at(fri, 7, 10), cal)!!
        assertEquals("South Rail good service · Metro line minor delays", el.text)
        assertTrue(el.lit)
        // Evening: in the order travelled, the fallback from Riverside.
        assertEquals("Metro line good service · South Rail part suspended — North Rail from Riverside is running",
            RouteRules.todayLine(snap(3, 10, 10, at(fri, 16, 30)), office, at(fri, 17), cal)!!.text)
        // Only what TfL said: a line it left out is left out.
        assertEquals("Metro line · good service", RouteRules.todayLine(snap(null, null, 10), office, at(fri, 7, 10), cal)!!.text)
    }

    @Test
    fun askHearsTheRouteWhileTheStatusIsFresh() {
        assertEquals(
            listOf("train lines on Meka's route (TfL status, checked 07:00): South Rail minor delays; North Rail good service; Metro line good service"),
            RouteRules.askLines(snap(9, 10, 10), at(fri, 7, 20), cal),
        )
        // Any time of day, but not once it's stale.
        assertEquals(1, RouteRules.askLines(snap(9, 10, 10, at(sat, 11)), at(sat, 11, 5), cal).size)
        assertTrue(RouteRules.askLines(snap(9, 10, 10), at(fri, 9), cal).isEmpty())
        assertTrue(RouteRules.askLines(LineStatusSnapshot.EMPTY, at(fri, 7), cal).isEmpty())
        assertTrue(RouteRules.askLines(LineStatusSnapshot(listOf(LineState("victoria", 10)), at(fri, 7)), at(fri, 7), cal).isEmpty())
        assertEquals(at(fri, 7), RouteRules.checkedStep(at(fri, 7, 29)))
        assertEquals(at(fri, 7, 30), RouteRules.checkedStep(at(fri, 7, 30)))
    }

    @Test
    fun theServersStatusReachesBothDevices() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        assertEquals(LineStatusSnapshot.EMPTY, LineStatusStore(a.replica).snapshot())
        // As the server writes it (the apps never do).
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, LineStatusStore.ENTITY_ID, mapOf(
            LineStatusFields.LINES to FieldValue.Text("metro=10;south-rail=6"),
            LineStatusFields.CHECKED to FieldValue.Int64(at(fri, 7)),
        ))
        a.syncWithRetry(); m.syncWithRetry()
        val seen = LineStatusStore(m.replica).snapshot()
        assertEquals(6, seen.severityOf(InventedRoute.FIRST))
        assertEquals(at(fri, 7), seen.checkedMs)
        assertEquals("Train lines", CalendarAccountRules.providerLabel("lines"))
    }

    private val quiet = QuietHours.DEFAULT // 22:00–07:00

    @Test
    fun aLineOnTheRouteNotRunningWellDuringTheCommuteIsAHeadsUpOnce() {
        // 07:30: South Rail suspended, North Rail running: one heads-up naming the fallback.
        val bad = snap(2, 10, 10, checked = at(fri, 7, 30))
        val n = RouteRules.notices(bad, office, quiet, at(fri, 7, 40), cal).single()
        assertEquals("South Rail suspended", n.title)
        assertEquals("North Rail to Riverside is running · TfL 07:30", n.text)
        assertEquals(NoticeSource.TRAINS, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(at(fri, 7, 30), n.atMs)
        assertEquals(at(fri, 9), n.expiresAtMs)
        assertEquals(NoticeTarget.TODAY, n.target)
        // Severe delays a little later: the same key, so it doesn't buzz again.
        val worse = RouteRules.notices(snap(6, 10, 10, checked = at(fri, 8)), office, quiet, at(fri, 8, 5), cal).single()
        assertEquals(n.key, worse.key)
        val gov = Governor.evaluate(listOf(n), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), at(fri, 7, 40), cal)
        assertEquals(listOf(n.key), gov.post.map { it.key })
        assertTrue(Governor.evaluate(listOf(worse), NotificationSettings.DEFAULT, DeviceAlerts.ALL, gov.state, at(fri, 8, 5), cal).post.isEmpty())
        // The fallback not running either, or unknown.
        assertEquals("North Rail severe delays too · TfL 07:30",
            RouteRules.notices(snap(2, 6, 10, checked = at(fri, 7, 30)), office, quiet, at(fri, 7, 40), cal).single().text)
        assertEquals("Check before you leave · TfL 07:30",
            RouteRules.notices(snap(2, null, 10, checked = at(fri, 7, 30)), office, quiet, at(fri, 7, 40), cal).single().text)
        // The Metro line: the onward leg in the morning, the first leg home in the evening (ordered as travelled).
        val both = RouteRules.notices(snap(6, 10, 1, checked = at(fri, 16, 30)), office, quiet, at(fri, 16, 50), cal)
        assertEquals(listOf("Metro line closed", "South Rail severe delays"), both.map { it.title })
        assertEquals("Your first leg, to Oakfield Junction · TfL 16:30", both[0].text)
        assertEquals("North Rail from Riverside is running · TfL 16:30", both[1].text)
        assertEquals(at(fri, 19), both[0].expiresAtMs)
        assertTrue(both[0].key != n.key)
    }

    @Test
    fun goodOrMinorStaleOrOffCommuteTellsNothingAndQuietHoursHoldItUntilTheyEnd() {
        val now = at(fri, 7, 40)
        assertTrue(RouteRules.notices(snap(10, 10, 10, checked = at(fri, 7, 30)), office, quiet, now, cal).isEmpty())
        // Minor delays stay on Today's line.
        assertTrue(RouteRules.notices(snap(9, 10, 9, checked = at(fri, 7, 30)), office, quiet, now, cal).isEmpty())
        // A stale status, at work, the weekend, a work-from-home day.
        assertTrue(RouteRules.notices(snap(2, 10, 10, checked = at(fri, 5)), office, quiet, now, cal).isEmpty())
        assertTrue(RouteRules.notices(snap(2, 10, 10, checked = at(fri, 10)), office, quiet, at(fri, 10, 10), cal).isEmpty())
        assertTrue(RouteRules.notices(snap(2, 10, 10, checked = at(sat, 7, 30)), PlacesRules.officeWindow(hours, sat, cal), quiet, at(sat, 7, 40), cal).isEmpty())
        assertTrue(RouteRules.notices(snap(2, 10, 10, checked = at(fri, 7, 30)),
            PlacesRules.officeWindow(hours.copy(homeDays = setOf(fri)), fri, cal), quiet, now, cal).isEmpty())
        // Read at 06:30 (quiet until 07:00): due at 07:00, so it posts then rather than being lost to a later digest.
        val early = RouteRules.notices(snap(2, 10, 10, checked = at(fri, 6, 30)), office, quiet, at(fri, 6, 35), cal).single()
        assertEquals(at(fri, 7), early.atMs)
        val settings = NotificationSettings.DEFAULT
        assertTrue(Governor.evaluate(listOf(early), settings, DeviceAlerts.ALL, GovernorState(), at(fri, 6, 35), cal).post.isEmpty())
        assertEquals(listOf(early.key), Governor.evaluate(listOf(early), settings, DeviceAlerts.ALL, GovernorState(), at(fri, 7, 1), cal).post.map { it.key })
        // Lowered in Notifications to App only: never posted.
        val silent = settings.copy(tiers = mapOf(NoticeSource.TRAINS to NoticeTier.SILENT))
        assertTrue(Governor.evaluate(listOf(early), silent, DeviceAlerts.ALL, GovernorState(), at(fri, 7, 1), cal).post.isEmpty())
    }

    @Test
    fun theHeadsUpComesThroughTheNoticeSources() {
        val all = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), at(fri, 7, 40), cal,
            lines = snap(6, 10, 10, checked = at(fri, 7, 30)), office = office,
        )
        assertEquals(listOf("South Rail severe delays"), all.filter { it.source == NoticeSource.TRAINS }.map { it.title })
    }

    // ---- Rule 13, release 2 slice 6: the route is About you's Train route ----

    @Test
    fun theTrainRouteIsReadFromWhatHeTyped() {
        val r = RouteRules.parse(InventedRoute.TYPED)!!
        assertEquals(CommuteRoute("South Rail", "Oakfield Junction", "Metro line", "North Rail", "Riverside"), r)
        assertEquals(listOf("south-rail", "north-rail", "metro"), r.ids)
        assertEquals(InventedRoute.TYPED, r.text)
        // Loosely typed: no comma before "then", extra spaces, "instead" for the fallback, a curly apostrophe.
        assertEquals(CommuteRoute("South Rail", "Oak’s Cross".replace('’', '\''), "Metro", "North Rail"),
            RouteRules.parse("  South Rail to Oak’s Cross then  Metro ; instead North Rail"))
        assertEquals(CommuteRoute("Northern line"), RouteRules.parse("Northern line"))
        assertEquals(CommuteRoute("District", fallback = "Piccadilly"), RouteRules.parse("District; or Piccadilly"))
        // The same line twice is one leg.
        assertEquals(CommuteRoute("Metro", "Oakfield"), RouteRules.parse("Metro to Oakfield, then Metro line"))
        assertNull(RouteRules.parse(""))
        assertNull(RouteRules.parse("South Rail; or North Rail; or East Rail"))
        assertNull(RouteRules.parse("07700 900123 to somewhere"))
        assertNull(RouteRules.parse("South Rail to Oakfield 2, then Metro"))
        assertEquals("", AboutYouRules.clean(AboutField.ROUTE, "  "))
        assertEquals(InventedRoute.TYPED, AboutYouRules.clean(AboutField.ROUTE, InventedRoute.TYPED.replace(", then", " then")))
        assertNull(AboutYouRules.clean(AboutField.ROUTE, "<b>"))
    }

    @Test
    fun lineIdsFollowTflsNames() {
        assertEquals("metro", RouteRules.lineId("Metro line"))
        assertEquals("hammersmith-city", RouteRules.lineId("Hammersmith & City"))
        assertEquals("north-rail", RouteRules.lineId(" North  Rail "))
        assertEquals("dlr", RouteRules.lineId("DLR"))
        assertEquals("", RouteRules.lineId("&"))
    }

    @Test
    fun withoutARouteNoLineIsShownOrAskedAbout() {
        Household.set(HouseholdNames.EMPTY)
        val s = snap(6, 10, 10)
        assertNull(RouteRules.todayLine(s, office, at(fri, 7, 30), cal))
        assertTrue(RouteRules.notices(s, office, QuietHours.DEFAULT, at(fri, 7, 30), cal).isEmpty())
        assertTrue(RouteRules.askLines(s, at(fri, 7, 30), cal).isEmpty())
        assertTrue(RouteRules.lines().isEmpty())
        assertEquals(Triple(0, LineLevel.GOOD, null), LeavingRules.allowance(s, at(fri, 7, 30)))
    }

    @Test
    fun aOneLineRouteHasOneLegAndNoFallback() {
        Household.useRoute("Metro line")
        val s = LineStatusSnapshot(listOf(LineState("metro", 6)), at(fri, 7))
        val line = RouteRules.todayLine(s, office, at(fri, 7, 30), cal)!!
        assertEquals("Metro line severe delays", line.text)
        assertEquals(listOf("Metro line severe delays" to "Check before you leave · TfL 07:00"),
            RouteRules.notices(s, office, QuietHours.DEFAULT, at(fri, 7, 30), cal).map { it.title to it.text })
        assertEquals("Metro line from TfL", RouteRules.setupLine(RouteRules.route()!!))
        assertEquals("South Rail, North Rail and Metro line from TfL", RouteRules.setupLine(RouteRules.parse(InventedRoute.TYPED)!!))
    }

    @Test
    fun theProfileHoldsTheRouteAndSaysWhenItWasCopied() {
        val a = AboutYouRules.read(mapOf(AboutYouFields.ROUTE to FieldValue.Text(InventedRoute.TYPED)))
        assertEquals(InventedRoute.TYPED, a.route)
        assertEquals(RouteRules.parse(InventedRoute.TYPED), HouseholdNames.of(a).route)
        assertEquals(true, AboutYouRules.held(mapOf(AboutYouFields.ROUTE to FieldValue.Text(InventedRoute.TYPED)))!!.route)
        assertEquals(false, AboutYouRules.held(mapOf(AboutYouFields.HOME to FieldValue.Text("Oakfield")))!!.route)
        assertTrue("route copied" in AboutYouRules.gateLine(AboutYouHeld(true, 1L, 2L, route = true)) { "t" })
        assertTrue("route NOT copied yet" in AboutYouRules.gateLine(AboutYouHeld(true, 1L, 2L, route = false)) { "t" })
    }
}
