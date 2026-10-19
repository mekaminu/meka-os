package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Hello, Navilife": the greeting built by rules, its order and limits, quiet hours, and the orb's states. */
class HelloTest {
    private val evening = 19 * 60 + 5

    @Test
    fun anEmptyDayGreetsByNameAndSaysItsClear() {
        val g = HelloRules.greeting(HelloFacts(minuteOfDay = evening, name = "Alex Smith"))
        assertEquals("Good evening, Alex.", g.opening)
        assertTrue(g.points.isEmpty())
        assertEquals("Good evening, Alex. Nothing needs you. Your evening's clear. Anything you'd like?", g.spoken)
        // No name in About you: no name, no placeholder.
        assertEquals("Good morning. Nothing needs you. Your morning's clear. Anything you'd like?",
            HelloRules.greeting(HelloFacts(minuteOfDay = 8 * 60, name = "  ")).spoken)
        assertEquals("Good afternoon", HelloRules.salutation(12 * 60))
        assertEquals("Good evening", HelloRules.salutation(2 * 60)) // small hours
        assertEquals("Good morning", HelloRules.salutation(5 * 60))
    }

    @Test
    fun urgentFirstThenTheNextThingThenHisPartnerThenDueTodayAtMostThree() {
        val f = HelloFacts(
            minuteOfDay = evening, name = "Alex",
            urgent = listOf("Pay the council tax", "Book the MOT"), needsYou = 4,
            event = HelloEvent("Parents' evening", 19 * 60 + 30, 20 * 60 + 30, minutesAway = 25),
            partnerName = "Sam", partnerAsks = 2,
            dueToday = listOf("Send the invoice"),
        )
        val g = HelloRules.greeting(f)
        assertEquals(listOf(HelloKind.NEEDS_YOU, HelloKind.EVENT, HelloKind.PARTNER), g.points.map { it.kind })
        assertEquals(
            "Good evening, Alex. 2 things need you, starting with Pay the council tax. Parents' evening starts in 25 minutes. " +
                "Sam asked you for 2 things. Anything you'd like?",
            g.spoken,
        )
        // Without the event and the partner, due today gets its place.
        val g2 = HelloRules.greeting(f.copy(event = null, partnerAsks = 0))
        assertEquals(listOf(HelloKind.NEEDS_YOU, HelloKind.DUE), g2.points.map { it.kind })
        assertEquals("Send the invoice is due today.", g2.points[1].text)
    }

    @Test
    fun theTrainWhenItLeavesFirstElseTheEvent() {
        val train = TrainDeparture(depMin = 17 * 60 + 42, expMin = 17 * 60 + 47)
        val event = HelloEvent("Dinner with Sam", 19 * 60, 21 * 60, minutesAway = 90)
        val f = HelloFacts(minuteOfDay = 17 * 60 + 20, train = train, trainMinutesAway = 27, event = event)
        assertEquals(HelloPoint(HelloKind.TRAIN, "Your 17:42 train is 5 minutes late."), HelloRules.points(f).single())
        assertEquals(HelloPoint(HelloKind.EVENT, "Next, Dinner with Sam at 19:00."), HelloRules.points(f.copy(trainMinutesAway = 120)).single())
        assertEquals("Your 17:42 train is on time.", HelloRules.trainLine(train.copy(expMin = 17 * 60 + 42)))
        assertEquals("Your 17:42 train is cancelled.", HelloRules.trainLine(train.copy(expMin = null, cancelled = true)))
        assertEquals("Your 17:42 train is delayed.", HelloRules.trainLine(train.copy(expMin = null, delayed = true)))
        assertEquals("Standup is on now, until 15:30.", HelloRules.eventLine(HelloEvent("Standup.", 15 * 60, 15 * 60 + 30, minutesAway = -5)))
        // Further off than three hours, the evening is still clear of it.
        assertTrue(HelloRules.points(f.copy(train = null, event = event.copy(minutesAway = 200))).isEmpty())
    }

    @Test
    fun quietHoursSayOnlyTheOneThingThatCantWait() {
        val f = HelloFacts(
            minuteOfDay = 23 * 60, quiet = true,
            event = HelloEvent("Late call", 23 * 60 + 40, 24 * 60 - 1, minutesAway = 40),
            partnerName = "Sam", partnerAsks = 1, dueToday = listOf("Bins"),
        )
        assertEquals(listOf(HelloKind.EVENT), HelloRules.points(f).map { it.kind })
        assertEquals(listOf(HelloKind.NEEDS_YOU), HelloRules.points(f.copy(urgent = listOf("Renew the insurance"))).map { it.kind })
        // An event more than an hour off isn't said in quiet hours; nor are his partner's asks or due today.
        assertEquals("Good evening. Nothing needs you. Your evening's clear. Anything you'd like?",
            HelloRules.greeting(f.copy(event = f.event!!.copy(minutesAway = 90))).spoken)
    }

    @Test
    fun partnerAndDueWords() {
        assertEquals("Your partner asked you for something.", HelloRules.partnerLine(null, 1))
        assertEquals(null, HelloRules.partnerLine("Sam", 0))
        assertEquals("One thing needs you: Pay the council tax.", HelloRules.needsYouLine(listOf("Pay the council tax.")))
        assertEquals("Bins and 2 more are due today.", HelloRules.dueLine(listOf("Bins", "Milk", "Post")))
        assertEquals(null, HelloRules.dueLine(emptyList()))
    }

    @Test
    fun theOrbFollowsThePhaseAndGlowsWarmWhileSomethingNeedsHim() {
        assertEquals(OrbState.IDLE, HelloOrb.state(TalkPhase.ENDED, 0))
        assertEquals(OrbState.NEEDS_YOU, HelloOrb.state(TalkPhase.ENDED, 2))
        assertEquals(OrbState.LISTENING, HelloOrb.state(TalkPhase.LISTENING, 2))
        assertEquals(OrbState.THINKING, HelloOrb.state(TalkPhase.THINKING, 0))
        assertEquals(OrbState.THINKING, HelloOrb.state(TalkPhase.DOING, 0))
        assertEquals(OrbState.SPEAKING, HelloOrb.state(TalkPhase.SPEAKING, 0))
        assertEquals(0f, HelloOrb.swirl(0, reduced = false))
        assertEquals(0.5f, HelloOrb.swirl(HelloOrb.SWIRL_MS / 2, reduced = false))
        assertEquals(0f, HelloOrb.swirl(HelloOrb.SWIRL_MS / 2, reduced = true))
        assertEquals(0f, HelloOrb.pulse(0, reduced = false))
        assertEquals(1f, HelloOrb.pulse(HelloOrb.PULSE_MS / 2, reduced = false))
        assertEquals(0.5f, HelloOrb.pulse(123, reduced = true))
        assertEquals(1f + TalkOrb.SWELL, HelloOrb.scale(OrbState.LISTENING, 1f, 0f))
        assertEquals("Tap to say hello", HelloOrb.restingLine(0, mac = false))
        assertEquals("3 things need you · click to say hello", HelloOrb.restingLine(3, mac = true))
    }

    @Test
    fun greetingSpeaksFirstThenListensAndRemembersWhatItSaid() {
        val g = "Good evening. Nothing needs you. Your evening's clear. Anything you'd like?"
        val step = TalkFlow.greet(g)
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.Speak(g)), step.effects)
        assertEquals(TalkTurn(HelloRules.ASKED, g), step.session.conversation.turns.single())
        val after = TalkFlow.spoke(step.session)
        assertEquals(TalkPhase.LISTENING, after.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), after.effects)
        // "That's all" ends it; talking over it listens.
        assertEquals(TalkPhase.ENDED, TalkFlow.heard(after.session, "that's all, thanks").session.phase)
        assertEquals(TalkPhase.LISTENING, TalkFlow.bargeIn(step.session).session.phase)
    }

    @Test
    fun theHotKeyGreetsOnAReturnAndOtherwiseJustListens() {
        assertTrue(HelloRules.hotkeyGreets(-1)) // never greeted on this device
        assertTrue(HelloRules.hotkeyGreets(HelloRules.REGREET_MIN))
        assertTrue(!HelloRules.hotkeyGreets(5))
        assertEquals(TalkStart.HELLO, TalkStartRules.fromOpen(TalkStartRules.openValue(TalkStart.HELLO)))
    }

    @Test
    fun hisPartnersOpenAsksAreCountedByHerNameOrHerRole() {
        val people = listOf("Sam Taylor", "Wife", "Alex", "", "sam")
        assertEquals(3, HelloRules.partnerAsks("Sam", people))
        assertEquals(1, HelloRules.partnerAsks("Jordan Lee", people)) // only the role
        assertEquals(0, HelloRules.partnerAsks(null, people))
        assertEquals(0, HelloRules.partnerAsks("Sam", emptyList()))
        assertEquals("Sam asked you for something.", HelloRules.partnerLine("Sam Taylor", 1))
    }
}
