package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun theWakePhraseIsAGreetingThenTheNameOrTheNameFirst() {
        assertTrue(HelloWakeRules.heard("Hello Navilife"))
        assertTrue(HelloWakeRules.heard("hey navi life what's on tomorrow"))
        assertTrue(HelloWakeRules.heard("Hi, Navy-Life!"))
        assertTrue(HelloWakeRules.heard("Navilife, are you there"))
        assertTrue(HelloWakeRules.heard("um hello navilife")) // the greeting needn't be first
        // The name inside a sentence, "hello" alone, or nothing: not a wake.
        assertEquals(false, HelloWakeRules.heard("I was telling Sam about navilife"))
        assertEquals(false, HelloWakeRules.heard("hello there"))
        assertEquals(false, HelloWakeRules.heard("hello"))
        assertEquals(false, HelloWakeRules.heard(""))
        assertEquals(false, HelloWakeRules.heard(null))
    }

    @Test
    fun theWakePhraseListensOnlyWhereAndWhenItShould() {
        assertTrue(HelloWakeRules.listens(on = true, micAllowed = true, place = true, talking = false, charging = true, quiet = false))
        assertEquals(false, HelloWakeRules.listens(on = false, micAllowed = true, place = true, talking = false)) // off by default
        assertEquals(false, HelloWakeRules.DEFAULT_ON)
        assertEquals(false, HelloWakeRules.listens(on = true, micAllowed = false, place = true, talking = false)) // never asks
        assertEquals(false, HelloWakeRules.listens(on = true, micAllowed = true, place = false, talking = false)) // pocket
        assertEquals(false, HelloWakeRules.listens(on = true, micAllowed = true, place = true, talking = true))
        assertEquals(false, HelloWakeRules.listens(on = true, micAllowed = true, place = true, talking = false, charging = false))
        assertEquals(false, HelloWakeRules.listens(on = true, micAllowed = true, place = true, talking = false, quiet = true))
        assertEquals(HelloWakeRules.RESTART_MS, HelloWakeRules.restartDelayMs(0))
        assertEquals(1_600L, HelloWakeRules.restartDelayMs(2))
        assertEquals(HelloWakeRules.MAX_DELAY_MS, HelloWakeRules.restartDelayMs(7))
        assertEquals(null, HelloWakeRules.restartDelayMs(HelloWakeRules.MAX_ERRORS))
        val off = HelloWakeRules.section(on = false, mac = false)
        assertEquals(HelloWakeRules.TURN_ON, off.action)
        assertEquals(false, off.lit)
        assertEquals(null, HelloWakeRules.section(on = true, mac = true, available = false).action)
    }

    @Test
    fun welcomeBackGreetsOncePerReturnAfterTwoHoursOutsideQuietAndProtectedTime() {
        fun greets(away: Int, since: Int = -1, quiet: Boolean = false, prot: Boolean = false, on: Boolean = true) =
            WelcomeBackRules.greets(on, away, since, quiet, prot, micAllowed = true)
        assertTrue(greets(away = 130))
        assertEquals(false, greets(away = 90)) // a short break
        assertEquals(false, greets(away = 130, since = 30)) // already greeted this return
        assertEquals(false, greets(away = 130, quiet = true))
        assertEquals(false, greets(away = 130, prot = true))
        assertEquals(false, greets(away = 130, on = false))
        assertEquals(false, WelcomeBackRules.greets(true, 130, -1, false, false, micAllowed = false))
        val g = HelloRules.greeting(HelloFacts(minuteOfDay = 14 * 60, name = "Alex", welcome = true))
        assertEquals("Welcome back, Alex. Nothing needs you. Your afternoon's clear. Anything you'd like?", g.spoken)
        val sheet = WelcomeBackRules.macSetup(
            TalkAnywhereRules.macSetup(anywhere = true, taken = false, listenOnOpen = false, talkOver = true),
            wake = false, wakeAvailable = true, welcome = true,
        )
        val labels = sheet.sections.map { it.label }
        assertEquals(labels.indexOf(TalkOnOpenRules.LABEL) + 1, labels.indexOf(HelloWakeRules.LABEL))
        assertEquals(labels.indexOf(HelloWakeRules.LABEL) + 1, labels.indexOf(WelcomeBackRules.LABEL))
        assertEquals("Safety", labels.last())
    }
    @Test
    fun theDockedOrbSitsBetweenNeedsYouAndCalendarAndSaysHelloOrGoesBack() {
        // Today · Needs you · ◉ · Calendar · Ask
        assertEquals(listOf(0, 1, 3, 4), (0 until 4).map(HelloDock::slot))
        assertEquals(HelloDock.SLOT, (0 until HelloDock.SLOTS).first { it !in (0 until 4).map(HelloDock::slot) })
        // Small, no dust: the 80-point constellation.
        assertEquals(Constellation.POINTS_SMALL, Constellation.points(HelloDock.SIZE, mac = false))
        assertEquals(0, Constellation.dust(HelloDock.SIZE))
        // Amber while Needs you has cards, live while a conversation runs.
        assertEquals(OrbState.NEEDS_YOU, HelloDock.state(TalkPhase.ENDED, 2))
        assertEquals(OrbState.IDLE, HelloDock.state(TalkPhase.ENDED, 0))
        assertEquals(OrbState.LISTENING, HelloDock.state(TalkPhase.LISTENING, 2))
        assertEquals(HelloDock.LIVE_LEVEL, HelloDock.level(OrbState.LISTENING))
        assertEquals(0f, HelloDock.level(OrbState.NEEDS_YOU))
        // A tap says hello at rest; during a conversation it goes back to it.
        assertEquals(DockTap.HELLO, HelloDock.tap(TalkPhase.ENDED))
        assertEquals(DockTap.CONVERSATION, HelloDock.tap(TalkPhase.THINKING))
        assertEquals("Say hello to Navilife", HelloDock.label(TalkPhase.ENDED, 0))
        assertEquals("Say hello to Navilife · 1 thing needs you", HelloDock.label(TalkPhase.ENDED, 1))
        assertEquals("Say hello to Navilife · 3 things need you", HelloDock.label(TalkPhase.ENDED, 3))
        assertEquals("Navilife is listening · back to the conversation", HelloDock.label(TalkPhase.LISTENING, 3))
    }

    @Test
    fun theBigEntranceZoomsScattersGathersThenShocksAndGreets() {
        val big = EntranceKind.BIG
        // It starts at the bar and dark comes up with the zoom; in the middle and near-black after it.
        assertEquals(0f, HelloEntrance.travel(big, 0, false))
        assertEquals(1f, HelloEntrance.travel(big, HelloEntrance.ZOOM_MS, false))
        assertEquals(HelloEntrance.DIM, HelloEntrance.dim(big, HelloEntrance.ZOOM_MS, false))
        // On the sphere, flung out to the edges, back on the sphere before the shockwave.
        assertEquals(0f, HelloEntrance.spread(big, 100, false))
        assertEquals(1f, HelloEntrance.spread(big, HelloEntrance.SCATTER_FROM + HelloEntrance.SCATTER_MS, false))
        assertEquals(0f, HelloEntrance.spread(big, HelloEntrance.REFORM_AT, false))
        // One shockwave as they re-form, then none.
        assertNull(HelloEntrance.shock(big, HelloEntrance.REFORM_AT - 1, false))
        val (reach, a) = HelloEntrance.shock(big, HelloEntrance.REFORM_AT, false)!!
        assertEquals(1f, reach); assertTrue(a > 0.5f)
        assertNull(HelloEntrance.shock(big, HelloEntrance.BIG_MS, false))
        assertEquals(HelloEntrance.GREET_AT, HelloEntrance.greetAt(big, false))
        // Points leave at different moments, all out at full spread, all home at none.
        assertTrue(HelloEntrance.pointSpread(0.5f, 0f) > HelloEntrance.pointSpread(0.5f, 1f))
        assertEquals(1f, HelloEntrance.pointSpread(1f, 1f))
        assertEquals(0f, HelloEntrance.pointSpread(0f, 0f))
    }

    @Test
    fun theHelloStartsDuringTheEntranceAndATapSkipsTheRest() {
        // The orb speed pass: the greeting starts within 0.3 s of the tap, while the orb is still zooming up.
        val at = HelloEntrance.greetAt(EntranceKind.BIG, false)!!
        assertTrue(at <= HelloEntrance.GREET_MAX_MS)
        assertTrue(at < HelloEntrance.ZOOM_MS)
        assertTrue(at < HelloEntrance.REFORM_AT)
        assertTrue(HelloEntrance.greetAt(EntranceKind.BIG, true)!! <= HelloEntrance.GREET_MAX_MS)
        // A tap mid-entrance jumps to its end: the points are home, no shockwave left, the hello carries on.
        val to = HelloEntrance.tapThrough(EntranceKind.BIG, 500, false)!!
        assertEquals(HelloEntrance.BIG_MS, to)
        assertEquals(0f, HelloEntrance.spread(EntranceKind.BIG, to, false))
        assertNull(HelloEntrance.shock(EntranceKind.BIG, to, false))
        assertEquals(1f, HelloEntrance.travel(EntranceKind.BIG, to, false))
        // Nothing to skip once settled, for the calm fade, or for the morning flourish (a tap closes that).
        assertNull(HelloEntrance.tapThrough(EntranceKind.BIG, HelloEntrance.BIG_MS, false))
        assertNull(HelloEntrance.tapThrough(EntranceKind.BIG, 100, true))
        assertNull(HelloEntrance.tapThrough(EntranceKind.SHORT, 100, false))
    }

    @Test
    fun reducedMotionCrossFadesAndTheMorningVersionSaysNothing() {
        // Reduced motion: in the middle at once, no scatter, no shockwave, a short fade, then the greeting.
        assertEquals(1f, HelloEntrance.travel(EntranceKind.BIG, 0, true))
        assertEquals(0f, HelloEntrance.spread(EntranceKind.BIG, 500, true))
        assertNull(HelloEntrance.shock(EntranceKind.BIG, HelloEntrance.REFORM_AT, true))
        assertEquals(0L, HelloEntrance.greetAt(EntranceKind.BIG, true))
        // The short version: no greeting; gathers from part-way out, then ends back in the bar.
        assertNull(HelloEntrance.greetAt(EntranceKind.SHORT, false))
        assertEquals(HelloEntrance.SHORT_FROM, HelloEntrance.spread(EntranceKind.SHORT, 0, false))
        val end = HelloEntrance.length(EntranceKind.SHORT, false)
        assertEquals(0f, HelloEntrance.travel(EntranceKind.SHORT, end, false))
        assertEquals(0f, HelloEntrance.dim(EntranceKind.SHORT, end, false))
        // Once a morning, never in quiet hours or with reduced motion, never in the afternoon.
        assertTrue(HelloEntrance.morningPlays(lastDay = 9L, today = 10L, minuteOfDay = 7 * 60, quiet = false, reduced = false))
        assertFalse(HelloEntrance.morningPlays(10L, 10L, 7 * 60, false, false))
        assertFalse(HelloEntrance.morningPlays(null, 10L, 7 * 60, true, false))
        assertFalse(HelloEntrance.morningPlays(null, 10L, 7 * 60, false, true))
        assertFalse(HelloEntrance.morningPlays(null, 10L, 14 * 60, false, false))
    }

    @Test
    fun onTheStandTheEntranceIsBigExceptInQuietHoursAndTheClockReturnsAfter() {
        // A tap or "Hello Navilife" on the stand: the big entrance in the evening, the calm fade in quiet hours.
        assertFalse(HelloEntrance.standCalm(quiet = false, reduced = false))
        assertTrue(HelloEntrance.standCalm(quiet = true, reduced = false))
        assertTrue(HelloEntrance.standCalm(quiet = false, reduced = true))
        // Calm means the cross-fade's timeline: no scatter, no shockwave, it greets at once as the fade begins.
        assertEquals(0f, HelloEntrance.spread(EntranceKind.BIG, 500, reduced = true))
        assertEquals(0L, HelloEntrance.greetAt(EntranceKind.BIG, reduced = true))
        // Shrunk back: the clock again, unless the answer needs Ask's pane (Take me there, Still stuck).
        assertFalse(HelloEntrance.standKeepsTalk(needsAsk = false))
        assertTrue(HelloEntrance.standKeepsTalk(needsAsk = true))
    }

    @Test
    fun theStageStaysForTheConversationAndShrinksBackWhenItEnds() {
        assertEquals(StageNext.STAY, HelloEntrance.next(TalkPhase.SPEAKING, started = true, needsAsk = false, msSinceGreet = 9_000))
        assertEquals(StageNext.CLOSE, HelloEntrance.next(TalkPhase.ENDED, started = true, needsAsk = false, msSinceGreet = 9_000))
        assertEquals(StageNext.STAY, HelloEntrance.next(TalkPhase.ENDED, started = false, needsAsk = false, msSinceGreet = 1_000))
        assertEquals(StageNext.CLOSE, HelloEntrance.next(TalkPhase.ENDED, started = false, needsAsk = false, msSinceGreet = HelloEntrance.NO_START_MS))
        assertEquals(StageNext.CLOSE, HelloEntrance.next(TalkPhase.LISTENING, started = true, needsAsk = true, msSinceGreet = 9_000))
        // Slice 8: an answer with cards keeps the stage up (they rise round the orb).
        assertEquals(StageNext.STAY, HelloEntrance.next(TalkPhase.SPEAKING, started = true, needsAsk = false, msSinceGreet = 9_000))
        assertTrue(HelloEntrance.swipeCloses(120f)); assertFalse(HelloEntrance.swipeCloses(40f))
        assertEquals(1f, HelloEntrance.closing(0, HelloEntrance.DIM, false).first)
        assertEquals(0f, HelloEntrance.closing(HelloEntrance.CLOSE_MS, HelloEntrance.DIM, false).second)
        // The words under the orb follow who's talking.
        assertEquals("Good morning, Alex.", HelloEntrance.words(TalkPhase.SPEAKING, "", "Good morning, Alex."))
        assertEquals("what's on today", HelloEntrance.words(TalkPhase.LISTENING, "what's on today", "Good morning, Alex."))
        assertNull(HelloEntrance.words(TalkPhase.LISTENING, "", "x"))
        assertNull(HelloEntrance.words(TalkPhase.ENDED, "a", "b"))
    }

    @Test
    fun theConstellationFlingsItsPointsOutAndLetsTheLinesGo() {
        val geo = Constellation.geometry(Constellation.POINTS)
        val home = ConstellationFrame(geo.n, 0); val out = ConstellationFrame(geo.n, 0)
        Constellation.frame(geo, home, OrbState.IDLE, 0f, 0.6f, 1_000, false)
        Constellation.frame(geo, out, OrbState.IDLE, 0f, 0.6f, 1_000, false, spread = 1f, reach = 5f)
        val farthest = (0 until geo.n).maxOf { kotlin.math.hypot(home.px[it], home.py[it]) }
        assertTrue(farthest < 1.3f)
        assertTrue((0 until geo.n).all { kotlin.math.hypot(out.px[it], out.py[it]) > 3.5f })
        assertEquals(0f, out.linkBoost)
    }

    @Test
    fun eachThingTheGreetingSaysBringsItsCardInTheSameOrder() {
        val f = HelloFacts(
            minuteOfDay = evening, name = "Alex",
            urgent = listOf("Pay the council tax", "Book the MOT"), needsYou = 4,
            event = HelloEvent("Parents' evening", 19 * 60 + 30, 20 * 60 + 30, minutesAway = 25),
            partnerName = "Sam Smith", partnerAsks = 2,
        )
        val g = HelloRules.greeting(f)
        assertEquals(g.points.map { it.kind }, g.cards.map { it.kind })
        assertEquals(HelloCard(HelloKind.NEEDS_YOU, "NEEDS YOU", "Pay the council tax", "and 1 more", HelloOpen.NEEDS_YOU, warm = true), g.cards[0])
        assertEquals(HelloCard(HelloKind.EVENT, "IN 25 MIN", "Parents' evening", "19:30–20:30", HelloOpen.CALENDAR), g.cards[1])
        assertEquals(HelloCard(HelloKind.PARTNER, "SAM ASKED", "2 things", "In Needs you", HelloOpen.NEEDS_YOU), g.cards[2])
        // Nothing to say, no cards.
        assertTrue(HelloRules.greeting(HelloFacts(minuteOfDay = evening)).cards.isEmpty())
        // Due today opens Today; one thing has no "and more".
        val due = HelloRules.greeting(HelloFacts(minuteOfDay = evening, dueToday = listOf("Send the invoice"))).cards.single()
        assertEquals(HelloCard(HelloKind.DUE, "DUE TODAY", "Send the invoice", null, HelloOpen.TODAY), due)
        assertEquals("Due today, Send the invoice", HelloCards.label(due))
    }

    @Test
    fun theTrainCardIsTheBoardAndTheEventCardItsTimes() {
        val late = HelloCards.trainCard(TrainDeparture(depMin = 17 * 60 + 42, expMin = 17 * 60 + 47, platform = "2"))
        assertEquals(HelloCard(HelloKind.TRAIN, "TRAIN · 17:42", "5 min late", "Expected 17:47 · Platform 2", HelloOpen.COMMUTE, warm = true), late)
        val onTime = HelloCards.trainCard(TrainDeparture(depMin = 7 * 60 + 12, expMin = 7 * 60 + 12))
        assertEquals("On time", onTime.title); assertNull(onTime.detail); assertFalse(onTime.warm)
        assertEquals("Cancelled", HelloCards.trainCard(TrainDeparture(7 * 60, null, cancelled = true)).title)
        assertEquals("Delayed", HelloCards.trainCard(TrainDeparture(7 * 60, null, delayed = true)).title)
        assertEquals("ON NOW", HelloCards.eventCard(HelloEvent("Standup", 15 * 60, 15 * 60 + 30, minutesAway = -5)).eyebrow)
        assertEquals("NEXT", HelloCards.eventCard(HelloEvent("Dinner", 19 * 60, 21 * 60, minutesAway = 90)).eyebrow)
    }

    @Test
    fun cardsRiseAsTheirSentenceStartsAndSitRoundTheOrbWhenThereIsRoom() {
        val f = HelloFacts(minuteOfDay = evening, name = "Alex", urgent = listOf("Pay the council tax"), dueToday = listOf("Send the invoice"))
        val g = HelloRules.greeting(f)
        val at = HelloCards.revealAt(g)
        assertEquals(2, at.size)
        // The first as soon as "Good evening, Alex." has been said, the next after the first point's sentence.
        assertEquals(("Good evening, Alex.".length + 1) * HelloCards.MS_PER_CHAR - HelloCards.LEAD_MS, at[0])
        assertEquals(at[0] + (g.points[0].text.length + 1) * HelloCards.MS_PER_CHAR, at[1])
        assertEquals(0L, HelloCards.answerRevealAt(0)); assertEquals(240L, HelloCards.answerRevealAt(2))
        // Below and unseen before, risen and whole after.
        val before = HelloCards.rise(-1, false)
        assertEquals(0f, before.alpha); assertEquals(HelloCards.RISE_DP, before.liftDp)
        val after = HelloCards.rise(HelloCards.RISE_MS, false)
        assertEquals(CardRise(0f, 1f, 1f), after)
        assertTrue(HelloCards.rise(HelloCards.RISE_MS / 3, false).liftDp in 0.1f..HelloCards.RISE_DP)
        // Reduced motion: a fade in place.
        assertEquals(0f, HelloCards.rise(50, true).liftDp)
        assertEquals(1f, HelloCards.rise(HelloCards.FADE_MS, true).alpha)
        // Wide: left, right, under; the cover screen: a column under the words; never more than three.
        assertEquals(listOf(CardSlot.LEFT, CardSlot.RIGHT, CardSlot.BELOW), HelloCards.slots(3, wide = true))
        assertEquals(listOf(CardSlot.BELOW), HelloCards.slots(1, wide = true))
        assertEquals(List(3) { CardSlot.BELOW }, HelloCards.slots(5, wide = false))
        assertTrue(HelloCards.wide(700f)); assertFalse(HelloCards.wide(380f))
    }
}
