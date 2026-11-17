package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi answers live train status (Meka 2026-11-17): today's trains as their own lines, TfL on demand, a wider search. */
class TrainNowAskTest {
    // Rule 13: the route comes from About you (an invented one here), never the code.
    @BeforeTest
    fun route() { Household.useRoute(InventedRoute.TYPED) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val tue = 20774L
    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private val good = LineStatusCodec.decode("metro=10;north-rail=10;south-rail=9")

    /** His question of 08:11, with the invented route's line and stations in place of his. */
    private val his = "can u check metro line from Oakfield to Riverside is now ok"

    @Test
    fun trainQuestionsAreToldFromOthers() {
        listOf(
            his, "is the jubilee line ok", "any delays on the Elizabeth line?", "is the tube running", "how's my commute looking",
            "is South Rail running now", "is my 08:31 cancelled", "is the DLR working", "what's up at the station",
        ).forEach { assertTrue(TrainNowAskRules.isAbout(it), it) }
        listOf("what's on today?", "is it going to rain", "when's the dentist", "who won the Arsenal game", "is the central heating on")
            .forEach { assertFalse(TrainNowAskRules.isAbout(it), it) }
    }

    @Test
    fun linesAreNamedByTflIdAndOnlyThoseOffHisRouteAreAskedOnDemand() {
        assertEquals(listOf("elizabeth", "jubilee"), TrainNowAskRules.namedLines("Elizabeth line or the Jubilee line, which is better?"))
        assertEquals(listOf("hammersmith-city"), TrainNowAskRules.namedLines("is the Hammersmith & City line ok"))
        // Words that are also places or plain words need "line".
        assertEquals(emptyList(), TrainNowAskRules.namedLines("I'm at Victoria, is the central heating on in the district?"))
        assertEquals(listOf("victoria"), TrainNowAskRules.namedLines("victoria line delays?"))
        assertEquals(listOf("jubilee"), TrainNowAskRules.offRoute(listOf("jubilee", InventedRoute.ONWARD)))
        assertEquals(listOf("Jubilee line: good service", "DLR: severe delays"),
            TrainNowAskRules.lineWords(listOf(LineState("jubilee", 10), LineState("dlr", 6))))
    }

    @Test
    fun anOldStatusSaysWhenItWasReadAndAFreshOneIsLeftToTheRoutesLines() {
        val now = ms(tue, 8 * 60 + 11)
        // Fresh: RouteRules.askLines carries it, with the time it was read.
        val fresh = LineStatusSnapshot(good, ms(tue, 8 * 60 + 9))
        assertTrue(TrainNowAskRules.statusLines(fresh, now, cal).isEmpty())
        assertEquals("train lines on Meka's route (TfL status, checked 08:09): South Rail minor delays; North Rail good service; Metro line good service",
            RouteRules.askLines(fresh, now, cal).first())
        // Old: when it was read and what it said, so Obi never invents it.
        val old = LineStatusSnapshot(good, ms(tue, 6 * 60 + 40))
        assertEquals(listOf("train lines on Meka's route: TfL status last read at 06:40, over an hour ago: South Rail minor delays; North Rail good service; Metro line good service"),
            TrainNowAskRules.statusLines(old, now, cal))
        // Never read today.
        assertEquals(listOf("train lines on Meka's route (South Rail, North Rail, Metro line): TfL status not read yet today"),
            TrainNowAskRules.statusLines(LineStatusSnapshot(good, ms(tue - 1, 18 * 60)), now, cal))
        // TfL's plans for this morning, read the evening before.
        val planned = fresh.copy(planned = LineStatusCodec.decode("south-rail=4"), plannedDay = tue, plannedCheckedMs = ms(tue - 1, 18 * 60 + 30))
        assertEquals(listOf("TfL's plans for this morning's commute (checked 18:30): South Rail planned closure"), TrainNowAskRules.statusLines(planned, now, cal))
        // Without a route, nothing.
        Household.set(HouseholdNames.EMPTY)
        assertTrue(TrainNowAskRules.statusLines(old, now, cal).isEmpty())
    }

    @Test
    fun theTrainsGoFirstInTheContextForATrainQuestionSoAFullContextKeepsThem() {
        val now = ms(tue, 8 * 60 + 11)
        val lines = RouteRules.askLines(LineStatusSnapshot(good, ms(tue, 8 * 60 + 9)), now, cal)
        // A context already full of other lines (the forecast's 24 and each area's).
        val forecast = (1..24).map { "weather hour $it" }
        val full = (1..60).map { "fact $it" }
        val asked = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), now, cal, forecast, facts = full, trains = lines, trainsAsked = true)
        val trains = asked.items.filter { it.kind == AskItemKind.TRAINS }
        assertEquals(lines, trains.map { it.line })
        assertFalse(asked.untrusted)
        // Not asked about: just before the forecast, as before, the first to go when it's full.
        val other = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), now, cal, forecast, trains = lines)
        assertEquals(lines, other.items.filter { it.kind == AskItemKind.TRAINS }.map { it.line })
        assertEquals(AskItemKind.WEATHER, other.items.last().kind)
    }

    @Test
    fun anAnswerThatSaysItCantCheckLiveIsCaught() {
        listOf(
            "I can't check live status from here, but TfL's status page will show the latest.",
            "I can't check that live right now.", "I cannot look that up.", "I don't have live data for that.",
            "Sorry, I’m unable to check real-time traffic.", "I don't have access to live updates.",
        ).forEach { assertTrue(CantCheckRules.says(it), it) }
        listOf("The Metro line has good service as of 08:09.", "You can check off the dentist now.", "I can see the 08:31 is on time.")
            .forEach { assertFalse(CantCheckRules.says(it), it) }
    }

    @Test
    fun questionsAboutTheWorldNowAreSearched() {
        listOf(his, "is the M25 ok now", "any delays at Heathrow today?", "is Tesco open tonight", "check if the A1 is closed", "status of the Eurostar")
            .forEach { assertEquals(it, LiveFactsRules.query(it), it) }
        listOf(
            "what's on today?", "can you check I'm free at 3", "check the shopping list", "is it going to rain today",
            "what's the weather now", "when's the dentist today", "add milk now", "is my train on time now",
        ).forEach { assertNull(LiveFactsRules.query(it), it) }
    }
}
