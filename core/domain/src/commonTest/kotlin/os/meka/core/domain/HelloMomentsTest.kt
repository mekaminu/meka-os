package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orb says hello like a person, slice 2: the moment openers and the last openers shared by both apps. */
class HelloMomentsTest {
    private val o = HelloOpeners
    private val cal = LocalCalendar.UTC
    private val day = 20_747L // a Wednesday
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun moment(h: Int, moments: HelloMoments) = HelloMoment(h * 60, 3, moments = moments)

    @Test
    fun aMomentGetsItsOwnOpenerOnceThenTheUsualOnes() {
        val home = moment(19, HelloMoments(home = true))
        val first = o.pick(home, emptyList(), day)
        assertTrue(first in o.HOME, first.id)
        assertTrue("Alex" in first.openFor("Alex"))
        // Said once: the next tap is an ordinary evening one.
        val next = o.pick(home, listOf(first.id), day)
        assertFalse(next in o.HOME, next.id)
        assertTrue(next in o.EVENING + o.ANY)
        // Football comes before home, home before rain.
        assertTrue(o.pick(moment(19, HelloMoments(home = true, football = true, rain = true)), emptyList(), day) in o.FOOTBALL)
        assertTrue(o.pick(moment(19, HelloMoments(home = true, rain = true)), emptyList(), day) in o.HOME)
        assertTrue(o.pick(moment(10, HelloMoments(rain = true)), emptyList(), day) in o.RAIN)
        // With football said already, rain still gets its turn.
        val fb = o.FOOTBALL.first().id
        assertTrue(o.pick(moment(19, HelloMoments(football = true, rain = true)), listOf(fb), day) in o.RAIN)
    }

    @Test
    fun theFirstHelloASecondTapAndTheWelcomeBackKeepTheirOwn() {
        val m = HelloMoments(home = true, football = true, rain = true)
        assertNull(o.momentPool(HelloMoment(19 * 60, 3, first = true, moments = m), emptyList()))
        assertNull(o.momentPool(HelloMoment(19 * 60, 3, again = true, moments = m), emptyList()))
        assertNull(o.momentPool(HelloMoment(19 * 60, 3, welcome = true, moments = m), emptyList()))
        assertNull(o.momentPool(HelloMoment(19 * 60, 3), emptyList()))
        assertTrue(o.pick(HelloMoment(19 * 60, 3, again = true, moments = m), emptyList(), day) in o.AGAIN)
    }

    @Test
    fun momentOpenersNeverNameAnyoneOrAScore() {
        (o.HOME + o.FOOTBALL + o.RAIN).forEach { op ->
            assertFalse(Regex("\\d").containsMatchIn(op.open + op.ask), op.open)
            assertFalse("Barça" in op.open, op.open)
            assertTrue(op.ask.endsWith("?"), op.ask)
        }
    }

    @Test
    fun homeFromWorkIsAnOfficeDaysEvening() {
        val office = OfficeWindow(at(9), at(17, 30))
        assertFalse(HelloMomentRules.home(office, at(17, 45)))
        assertTrue(HelloMomentRules.home(office, at(18, 5)))
        assertTrue(HelloMomentRules.home(office, at(20, 20)))
        assertFalse(HelloMomentRules.home(office, at(20, 31)))
        assertFalse(HelloMomentRules.home(null, at(18, 30)))
    }

    @Test
    fun afterTheFootballIsAFinishedMatchInTheLastFewHours() {
        fun barca(notes: String?) = CalendarEvent(
            id = "espn-1", title = "Barça v Getafe", startAtMs = at(15), endAtMs = at(17), allDay = false, location = null,
            provider = "fixtures", account = null, calendarName = "LaLiga", description = notes,
        )
        val ft = barca("Kick-off 15:00 UK time\nFull time: Barça 2–1 Getafe")
        assertFalse(HelloMomentRules.football(listOf(ft), at(16), cal))
        assertTrue(HelloMomentRules.football(listOf(ft), at(17, 30), cal))
        assertFalse(HelloMomentRules.football(listOf(ft), at(20, 30), cal))
        assertFalse(HelloMomentRules.football(listOf(barca("Live: Barça 1–0 Getafe · 67'")), at(17, 30), cal))
        assertFalse(HelloMomentRules.football(listOf(barca(null)), at(17, 30), cal))
        assertFalse(HelloMomentRules.football(emptyList(), at(17, 30), cal))
    }

    @Test
    fun rainIsAWetHourAtHome() {
        val h = WeatherCodec.HOUR_MS
        val f = WeatherForecast("Home", listOf(WeatherHour(at(9), 12, 63, 80), WeatherHour(at(10), 13, 2, 10)), emptyList())
        assertTrue(HelloMomentRules.rain(f, at(9, 20)))
        assertFalse(HelloMomentRules.rain(f, at(10, 20)))
        assertFalse(HelloMomentRules.rain(WeatherForecast.EMPTY, at(9) + h / 2))
        val m = HelloMomentRules.of(null, emptyList(), f, at(9, 20), cal)
        assertEquals(HelloMoments(rain = true), m)
    }

    @Test
    fun theSharedListMergesAndToleratesJunk() {
        assertEquals(listOf("e1", "m2", "a3"), o.merged(listOf("m2", "a3"), listOf("e1", "m2")))
        assertEquals(10, o.merged((1..8).map { "a$it" }, (1..8).map { "e$it" }).size)
        assertEquals(listOf("e1", "fb2"), o.decodeIds(o.encodeIds(listOf("e1", "fb2"))))
        assertEquals(listOf("e1", "m2"), o.decodeIds("e1,,BAD,m2,<x>,e1,waytoolongid"))
        assertEquals(emptyList(), o.decodeIds(null))
        // The warm-ups carry the moment too: the next tap's home opener is made ahead.
        val warm = o.warm(19 * 60, day, at(19), HelloOpenerMemory(), true, false, "Alex", HelloMoments(home = true))
        val pick = o.pick(moment(19, HelloMoments(home = true)), emptyList(), day)
        assertEquals(SpeechRules.pieces("${pick.openFor("Alex")} ${pick.ask}").first(), warm.first())
    }
}
