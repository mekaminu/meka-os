package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The orb says hello like a person, differently each time (Fix first, Meka 2026-10-20 18:13). */
class HelloOpenersTest {
    private val o = HelloOpeners
    private val day = 20_747L // a Wednesday
    private val evening = 19 * 60

    @Test
    fun thereAreFortyPlusOpenersAndEveryOneSaysHisNameAndAsks() {
        assertTrue(o.ALL.size >= 40, "${o.ALL.size}")
        assertEquals(o.ALL.size, o.ALL.map { it.id }.toSet().size)
        o.ALL.forEach { op ->
            assertTrue("Alex" in op.openFor("Alex Smith"), op.open)
            assertTrue(op.ask.endsWith("?"), op.ask)
            assertFalse("{n}" in op.openFor(null), op.open)
            assertFalse(op.openFor(null).contains(" ,") || op.openFor(null).startsWith(","), op.openFor(null))
        }
    }

    @Test
    fun withoutANameTheNameIsLeftOutCleanly() {
        assertEquals("Hey, it's late.", o.fill("Hey {n}, it's late.", null))
        assertEquals("Still up?", o.fill("Still up, {n}?", ""))
        assertEquals("There he is.", o.fill("There he is, {n}.", null))
        assertEquals("Back again, Alex?", o.fill("Back again, {n}?", "Alex"))
    }

    @Test
    fun noOpenerComesBackWithinTheLastTen() {
        var memory = HelloOpenerMemory()
        val said = mutableListOf<String>()
        var now = 1_000_000L
        repeat(40) { i ->
            // Taps spread out (never "again"), all evening, across a few days.
            val d = day + i / 12
            val m = o.moment(evening, d, now, memory, introduced = true, welcome = false)
            assertFalse(m.again)
            val pick = o.pick(m, memory.recent, d)
            assertFalse(pick.id in said.takeLast(HelloOpeners.RECENT), "$i: ${pick.id} in ${said.takeLast(10)}")
            said += pick.id
            memory = o.said(memory, pick, now)
            now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
        }
        assertTrue(said.toSet().size >= 11)
    }

    @Test
    fun eachPartOfTheDayHasItsOwnOpeners() {
        val pick = { minute: Int -> o.pick(HelloMoment(minute, 3), emptyList(), day) }
        assertTrue(pick(8 * 60) in o.MORNING + o.ANY)
        assertTrue(pick(14 * 60) in o.AFTERNOON + o.ANY)
        assertTrue(pick(evening) in o.EVENING + o.ANY)
        assertTrue(pick(1 * 60) in o.LATE)
        assertTrue(pick(23 * 60 + 30) in o.LATE)
        // Monday morning, Friday and the weekend add their own.
        assertTrue(o.candidates(HelloMoment(8 * 60, 1)).containsAll(o.MONDAY))
        assertTrue(o.candidates(HelloMoment(18 * 60, 5)).containsAll(o.FRIDAY))
        assertTrue(o.candidates(HelloMoment(11 * 60, 6)).containsAll(o.WEEKEND))
        assertFalse(o.candidates(HelloMoment(8 * 60, 2)).any { it in o.MONDAY })
    }

    @Test
    fun aQuickSecondTapSaysBackAgain() {
        val first = o.pick(HelloMoment(evening, 3), emptyList(), day)
        val memory = o.said(HelloOpenerMemory(), first, 5_000_000L)
        val m = o.moment(evening, day, 5_000_000L + 3 * 60_000, memory, introduced = true, welcome = false)
        assertTrue(m.again)
        val again = o.pick(m, memory.recent, day)
        assertTrue(again in o.AGAIN)
        assertNotEquals(first, again)
        // Ten minutes on, it's a fresh hello again.
        assertFalse(o.moment(evening, day, 5_000_000L + HelloOpeners.AGAIN_WITHIN_MS, memory, true, false).again)
    }

    @Test
    fun theFirstHelloOfTheDayIsAdaIntroducingHerself() {
        val m = o.moment(8 * 60, day, 1L, HelloOpenerMemory(), introduced = false, welcome = true)
        assertTrue(m.first)
        val pick = o.pick(m, emptyList(), day)
        assertTrue("Ada" in pick.openFor("Alex"), pick.open)
        // Later hellos don't name her.
        val later = o.pick(o.moment(9 * 60, day, 1L, HelloOpenerMemory(), introduced = true, welcome = false), listOf(pick.id), day)
        assertFalse("Ada" in later.open, later.open)
        // Every first-of-day opener names her; no other does.
        (o.FIRST_MORNING + o.FIRST_AFTERNOON + o.FIRST_EVENING + o.FIRST_LATE).forEach { assertTrue("Ada" in it.open) }
        (o.ALL - (o.FIRST_MORNING + o.FIRST_AFTERNOON + o.FIRST_EVENING + o.FIRST_LATE).toSet()).forEach { assertFalse("Ada" in it.open, it.open) }
    }

    @Test
    fun theMacsWelcomeBackHasItsOwn() {
        val m = o.moment(15 * 60, day, 1L, HelloOpenerMemory(), introduced = true, welcome = true)
        assertTrue(o.pick(m, emptyList(), day) in o.WELCOME)
    }

    @Test
    fun theSamePickOnARecheckSoItCanBeMadeAhead() {
        val m = HelloMoment(evening, 3)
        assertEquals(o.pick(m, listOf("e1"), day), o.pick(m, listOf("e1"), day))
        val warm = o.warm(evening, day, 1L, HelloOpenerMemory(), introduced = true, welcome = false, name = "Alex")
        val next = o.pick(o.moment(evening, day, 1L, HelloOpenerMemory(), true, false), emptyList(), day)
        assertEquals(SpeechRules.pieces("${next.openFor("Alex")} ${next.ask}").first(), warm.first())
        assertTrue(warm.any { "Alex" in it })
    }

    @Test
    fun theMemoryRoundTripsAndToleratesJunk() {
        val m = HelloOpenerMemory(listOf("e3", "e1"), 42L)
        assertEquals(m, o.decode(o.encode(m)))
        assertEquals(HelloOpenerMemory(), o.decode(null))
        assertEquals(HelloOpenerMemory(), o.decode("rubbish"))
        assertEquals(HelloOpenerMemory(listOf("a1"), null), o.decode(";a1,,"))
    }

    @Test
    fun withAnOpenerTheGreetingIsTheOpenerOneTouchAndItsQuestion() {
        val opener = HelloOpener("t1", "Evening, {n}. Good day?", "What can I do for you?")
        val clear = HelloRules.greeting(HelloFacts(evening, name = "Alex", opener = opener))
        assertEquals("Evening, Alex. Good day? What can I do for you?", clear.spoken)
        val busy = HelloRules.greeting(
            HelloFacts(
                evening, name = "Alex", urgent = listOf("Pay the council tax", "Book MOT"),
                event = HelloEvent("Parents' evening", evening + 25, evening + 85, 25),
                partnerName = "Sam", partnerAsks = 2, opener = opener,
            ),
        )
        assertEquals("Evening, Alex. Good day? 2 things need you, starting with Pay the council tax. What can I do for you?", busy.spoken)
        assertEquals(1, busy.cards.size)
        // Never anyone else's name in the opener itself.
        assertFalse("Sam" in busy.opening)
    }
}
