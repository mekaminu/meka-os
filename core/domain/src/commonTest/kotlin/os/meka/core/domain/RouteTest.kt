package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places item 4 (Meka 2026-10-09 12:54): Thameslink → Elizabeth line, Great Northern as the fallback; TfL's status. */
class RouteTest {
    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val thu = fri - 1
    private val sat = fri + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val hours = WorkHours(WorkSchedule.DEFAULT)
    private val office = PlacesRules.officeWindow(hours, fri, cal)

    private fun snap(tl: Int?, gn: Int?, el: Int?, checked: Long = at(fri, 7)) = LineStatusSnapshot(
        listOfNotNull(tl?.let { LineState(RouteRules.THAMESLINK, it) }, gn?.let { LineState(RouteRules.GREAT_NORTHERN, it) },
            el?.let { LineState(RouteRules.ELIZABETH, it) }), checked,
    )

    @Test
    fun theCodecKeepsOnlyWellFormedLines() {
        val lines = listOf(LineState("thameslink", 9), LineState("elizabeth", 10), LineState("great-northern", 10))
        val s = LineStatusCodec.encode(lines)
        assertEquals("elizabeth=10;great-northern=10;thameslink=9", s)
        assertEquals(lines.sortedBy { it.id }, LineStatusCodec.decode(s))
        // Junk from the network is skipped, never a broken line.
        assertEquals(listOf(LineState("elizabeth", 6)), LineStatusCodec.decode("elizabeth=6;Bad Id=3;x=;=4;thameslink=abc;victoria=150"))
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
        assertEquals("Elizabeth line", RouteRules.name("elizabeth"))
        assertEquals("Great Northern", RouteRules.name("great-northern"))
    }

    @Test
    fun theLineShowsOnAnOfficeDaysCommuteOnly() {
        val good = snap(10, 10, 10)
        assertNull(RouteRules.todayLine(good, office, at(fri, 5, 59), cal))
        val morning = RouteRules.todayLine(good, office, at(fri, 7, 10), cal)!!
        assertEquals("Thameslink · Elizabeth line · good service", morning.text)
        assertTrue(!morning.lit)
        assertEquals("Trains: Thameslink, Elizabeth line, good service", morning.spoken)
        // At work: nothing, until 45 minutes before the end; home again by 19:00.
        assertNull(RouteRules.todayLine(good, office, at(fri, 9), cal))
        assertNull(RouteRules.todayLine(good, office, at(fri, 16, 44), cal))
        val evening = RouteRules.todayLine(good.copy(checkedMs = at(fri, 16, 30)), office, at(fri, 16, 45), cal)!!
        assertEquals("Elizabeth line · Thameslink · good service", evening.text)
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
        assertEquals("Thameslink severe delays · Elizabeth line good service — Great Northern to King's Cross is running", tlDown.text)
        assertTrue(tlDown.lit)
        assertEquals("Trains: Thameslink severe delays, Elizabeth line good service. Great Northern to King's Cross is running", tlDown.spoken)
        // Both down: no fallback to offer.
        assertEquals("Thameslink suspended · Elizabeth line good service",
            RouteRules.todayLine(snap(2, 6, 10), office, at(fri, 7, 10), cal)!!.text)
        // The onward leg alone.
        val el = RouteRules.todayLine(snap(10, 10, 9), office, at(fri, 7, 10), cal)!!
        assertEquals("Thameslink good service · Elizabeth line minor delays", el.text)
        assertTrue(el.lit)
        // Evening: in the order travelled, the fallback from King's Cross.
        assertEquals("Elizabeth line good service · Thameslink part suspended — Great Northern from King's Cross is running",
            RouteRules.todayLine(snap(3, 10, 10, at(fri, 16, 30)), office, at(fri, 17), cal)!!.text)
        // Only what TfL said: a line it left out is left out.
        assertEquals("Elizabeth line · good service", RouteRules.todayLine(snap(null, null, 10), office, at(fri, 7, 10), cal)!!.text)
    }

    @Test
    fun askHearsTheRouteWhileTheStatusIsFresh() {
        assertEquals(
            listOf("train lines on Meka's route (TfL status, checked 07:00): Thameslink minor delays; Great Northern good service; Elizabeth line good service"),
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
            LineStatusFields.LINES to FieldValue.Text("elizabeth=10;thameslink=6"),
            LineStatusFields.CHECKED to FieldValue.Int64(at(fri, 7)),
        ))
        a.syncWithRetry(); m.syncWithRetry()
        val seen = LineStatusStore(m.replica).snapshot()
        assertEquals(6, seen.severityOf(RouteRules.THAMESLINK))
        assertEquals(at(fri, 7), seen.checkedMs)
        assertEquals("Train lines", CalendarAccountRules.providerLabel("lines"))
    }
}
