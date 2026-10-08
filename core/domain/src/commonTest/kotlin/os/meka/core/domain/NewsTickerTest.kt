package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTickerTest {
    private fun item(id: String, topic: String, title: String = id) = NewsItem(id, title, null, "Sport", topic, "Sport · 2 h ago", null, 0L)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, NewsTopics.byId(topic)!!.label, "From Sport", ids.map { item(it, topic) })

    private val match = NewsMatchday(
        "espn-1", "Barça v Real Madrid", "Barça v Real Madrid · 21:00 · in 3 h", false,
        CalendarEvent("espn-1", "Barça v Real Madrid", 0L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    @Test
    fun theStripTakesOneStoryFromEachLaneInTurnBarcaFirst() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2", "b3"), lane("ai", "a1"), lane("top", "t1", "t2")), emptyList(), null)
        val t = TickerRules.ticker(place)
        assertEquals(listOf("b1", "a1", "t1", "b2", "t2", "b3"), t.items.map { it.id })
        assertNull(t.matchday)
        assertFalse(t.isEmpty)
    }

    @Test
    fun theStripHoldsAtMostTwelveStoriesAndLeadsWithTheMatch() {
        val many = (1..10).map { "b$it" }.toTypedArray()
        val more = (1..10).map { "a$it" }.toTypedArray()
        val t = TickerRules.ticker(NewsPlace(listOf(lane("barca", *many), lane("ai", *more)), emptyList(), null, match))
        assertEquals(TickerRules.MAX_ITEMS, t.items.size)
        assertEquals(listOf("b1", "a1", "b2", "a2"), t.items.take(4).map { it.id })
        assertEquals(match, t.matchday)
        // A matchday with no stories still makes a strip.
        assertFalse(TickerRules.ticker(NewsPlace(emptyList(), emptyList(), null, match)).isEmpty)
    }

    @Test
    fun nothingToShowMeansNoStrip() {
        val t = TickerRules.ticker(NewsPlace.EMPTY)
        assertTrue(t.isEmpty)
        assertFalse(TickerRules.shown(TickerMode.CALM, t))
        val some = TickerRules.ticker(NewsPlace(listOf(lane("ai", "a1")), emptyList(), null))
        assertTrue(TickerRules.shown(TickerMode.CALM, some))
        assertTrue(TickerRules.shown(TickerMode.MOVING, some))
        assertFalse(TickerRules.shown(TickerMode.OFF, some))
    }

    @Test
    fun theEdgesFadeOverFortyEightDpSoWordsFadeRatherThanCut() {
        assertEquals(48f, TickerRules.EDGE_FADE_DP)
        assertEquals(0.1f, TickerRules.edgeFadeFraction(480f))
        assertEquals(0.25f, TickerRules.edgeFadeFraction(120f))
        assertEquals(0f, TickerRules.edgeFadeFraction(0f))
    }

    @Test
    fun theChoiceDefaultsToCalm() {
        assertEquals(TickerMode.CALM, TickerRules.mode(null))
        assertEquals(TickerMode.CALM, TickerRules.mode("sideways"))
        assertEquals(TickerMode.MOVING, TickerRules.mode("moving"))
        assertEquals(TickerMode.OFF, TickerRules.mode("off"))
        assertEquals(listOf("Calm", "Always moving", "Off"), TickerMode.entries.map { it.label })
    }

    @Test
    fun calmDriftsTwiceThenRestsAndPlaySetsItGoingAgain() {
        val m = TickerMode.CALM
        assertTrue(TickerRules.moving(m, reducedMotion = false, onScreen = true, held = false, loopsDone = 0, hasItems = true))
        assertTrue(TickerRules.moving(m, false, true, false, 1, true))
        assertFalse(TickerRules.moving(m, false, true, false, 2, true))
        assertTrue(TickerRules.offersPlay(m, false, 2, true))
        assertFalse(TickerRules.offersPlay(m, false, 1, true))
        // Always moving never rests and never needs ▸.
        assertTrue(TickerRules.moving(TickerMode.MOVING, false, true, false, 40, true))
        assertFalse(TickerRules.offersPlay(TickerMode.MOVING, false, 40, true))
    }

    @Test
    fun theStripStandsStillWhenTouchedOffScreenEmptyOrWithReducedMotion() {
        val m = TickerMode.MOVING
        assertFalse(TickerRules.moving(m, reducedMotion = true, onScreen = true, held = false, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, onScreen = false, held = false, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, true, held = true, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, true, false, 0, hasItems = false))
        assertFalse(TickerRules.moving(TickerMode.OFF, false, true, false, 0, true))
        // Reduced motion pages instead: no ▸.
        assertFalse(TickerRules.offersPlay(TickerMode.CALM, true, 2, true))
    }

    @Test
    fun theStripDriftsAtFortyDpASecondAndCountsLoops() {
        var d = TickerDrift.START
        d = TickerRules.step(d, 50, 100f)
        assertEquals(2f, d.offsetDp)
        // A long gap (a pause, a dropped frame) moves no further than 100 ms.
        d = TickerRules.step(d, 5_000, 100f)
        assertEquals(6f, d.offsetDp)
        // Wrapping at the loop's width counts a loop and keeps the remainder.
        d = TickerRules.step(TickerDrift(99f, 0), 50, 100f)
        assertEquals(1, d.loops)
        assertTrue(kotlin.math.abs(d.offsetDp - 1f) < 0.001f)
        // Nothing moves before the width is known, or with no time passed.
        assertEquals(TickerDrift.START, TickerRules.step(TickerDrift.START, 50, 0f))
        assertEquals(TickerDrift(3f, 1), TickerRules.step(TickerDrift(3f, 1), 0, 100f))
    }

    @Test
    fun scrubbingKeepsThePlaceWithinOneLoopAndIsNeverALoop() {
        val d = TickerDrift(10f, 1)
        assertEquals(TickerDrift(30f, 1), TickerRules.scrubbed(d, 130f, 100f))
        assertEquals(TickerDrift(80f, 1), TickerRules.scrubbed(d, -20f, 100f))
        assertEquals(d, TickerRules.scrubbed(d, 50f, 0f))
    }

    @Test
    fun reducedMotionPagesWrapBothWays() {
        assertEquals(1, TickerRules.page(0, 1, 3))
        assertEquals(0, TickerRules.page(2, 1, 3))
        assertEquals(2, TickerRules.page(0, -1, 3))
        assertEquals(0, TickerRules.page(0, 1, 0))
        assertEquals("2 of 12", TickerRules.pageLabel(1, 12))
        assertEquals("", TickerRules.pageLabel(0, 0))
    }

    @Test
    fun screenReadersHearTheLaneSourceAndTitle() {
        assertEquals("Barça · Sport · 2 h ago: Pedri returns", TickerRules.spoken(item("b1", "barca", "Pedri returns")))
    }
}

class FloatingTickerTest {
    private val screen = TickerRect(0.0, 0.0, 1440.0, 875.0) // a laptop's visible area (y up from the Dock)
    private fun item(id: String) = NewsItem(id, id, null, "Sport", "ai", "Sport · 2 h ago", null, 0L)

    @Test
    fun offByDefaultAndHiddenWithNothingToShow() {
        val some = TickerRules.ticker(NewsPlace(listOf(NewsLane("ai", "AI", "From Sport", listOf(item("a1")))), emptyList(), null))
        assertFalse(FloatingTickerRules.shown(false, some))
        assertTrue(FloatingTickerRules.shown(true, some))
        assertFalse(FloatingTickerRules.shown(true, NewsTicker.EMPTY))
        assertEquals(TickerMode.MOVING, FloatingTickerRules.MODE)
    }

    @Test
    fun theStoredPlacementDefaultsToTheBottomMiddle() {
        assertEquals(FloatingTickerRules.DEFAULT, FloatingTickerRules.placement(null, Double.NaN))
        assertEquals(FloatingPlacement(FloatingEdge.TOP, 1.0), FloatingTickerRules.placement("top", 3.0))
        assertEquals(FloatingPlacement(FloatingEdge.BOTTOM, 0.5), FloatingTickerRules.placement("sideways", Double.NaN))
        assertEquals(FloatingPlacement(FloatingEdge.BOTTOM, 0.0), FloatingTickerRules.placement("bottom", -1.0))
    }

    @Test
    fun theFrameSitsOnItsEdgeAtMostNineSixtyWideAndNeverPastASide() {
        val bottom = FloatingTickerRules.frame(screen, FloatingTickerRules.DEFAULT)
        assertEquals(TickerRect(240.0, 8.0, 960.0, 64.0), bottom)
        val top = FloatingTickerRules.frame(screen, FloatingPlacement(FloatingEdge.TOP, 0.5))
        assertEquals(875.0 - 8.0 - 64.0, top.y)
        // Pushed right: stops 8 pt from the side.
        assertEquals(1440.0 - 8.0 - 960.0, FloatingTickerRules.frame(screen, FloatingPlacement(FloatingEdge.TOP, 1.0)).x)
        assertEquals(8.0, FloatingTickerRules.frame(screen, FloatingPlacement(FloatingEdge.TOP, 0.0)).x)
        // A second screen to the left (negative x) and above the Dock.
        val left = TickerRect(-1920.0, 40.0, 1920.0, 1000.0)
        val f = FloatingTickerRules.frame(left, FloatingPlacement(FloatingEdge.BOTTOM, 0.25))
        assertEquals(-1912.0, f.x) // the centre would put it past the left side
        assertEquals(48.0, f.y)
        // A narrow screen: the strip fills it within the margins, centred.
        val small = TickerRect(0.0, 0.0, 600.0, 400.0)
        assertEquals(TickerRect(8.0, 8.0, 584.0, 64.0), FloatingTickerRules.frame(small, FloatingPlacement(FloatingEdge.BOTTOM, 0.9)))
        // Tinier than the minimum: never wider than the screen.
        assertEquals(300.0, FloatingTickerRules.frame(TickerRect(0.0, 0.0, 300.0, 300.0), FloatingTickerRules.DEFAULT).width)
    }

    @Test
    fun draggingKeepsThePanelOnTheScreenAndLettingGoPicksTheNearerEdge() {
        val start = FloatingTickerRules.frame(screen, FloatingTickerRules.DEFAULT)
        val moved = FloatingTickerRules.dragged(start, 100.0, 500.0, screen)
        assertEquals(TickerRect(340.0, 508.0, 960.0, 64.0), moved)
        // Can't be dragged off the screen.
        val far = FloatingTickerRules.dragged(start, 5000.0, -5000.0, screen)
        assertEquals(480.0, far.x)
        assertEquals(0.0, far.y)
        // Let go in the upper half: the top, centre kept (340 + 480 = 820 of 1440).
        val p = FloatingTickerRules.dropped(screen, moved)
        assertEquals(FloatingEdge.TOP, p.edge)
        assertTrue(kotlin.math.abs(820.0 / 1440.0 - p.centre) < 1e-9)
        assertTrue(kotlin.math.abs(340.0 - FloatingTickerRules.frame(screen, p).x) < 1e-9)
        // Lower half: the bottom.
        assertEquals(FloatingEdge.BOTTOM, FloatingTickerRules.dropped(screen, FloatingTickerRules.dragged(start, 0.0, 300.0, screen)).edge)
        assertEquals(0.5, FloatingTickerRules.dropped(TickerRect(0.0, 0.0, 0.0, 0.0), start).centre)
    }
}

class NewsWidgetTest {
    private fun item(id: String, topic: String, image: String? = null) =
        NewsItem(id, "Story $id", null, "Mundo Deportivo", topic, "Mundo Deportivo · 2 h ago", null, 0L, image)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, NewsTopics.byId(topic)!!.label, "From Sport", ids.map { item(it, topic) })
    private fun match(live: Boolean) = NewsMatchday(
        "espn-1", "Barça v Real Madrid", if (live) "Barça v Real Madrid · on now" else "Barça v Real Madrid · 21:00 · in 3 h", live,
        CalendarEvent("espn-1", "Barça v Real Madrid", 0L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    @Test
    fun theWidgetFlipsThroughTheTickersStoriesInItsOrder() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2"), lane("ai", "a1"), lane("top", "t1")), emptyList(), null)
        val v = NewsWidgetRules.view(TickerRules.ticker(place), 1_000L)
        assertEquals(listOf("b1", "a1", "t1", "b2"), v.cards.map { it.id })
        assertEquals(listOf("Barça", "AI", "Top stories", "Barça"), v.cards.map { it.label })
        assertEquals(listOf(true, false, false, true), v.cards.map { it.isBarca })
        assertEquals("b1", v.cards.first().openStoryId)
        assertEquals("Mundo Deportivo · 2 h ago", v.cards.first().line)
        assertEquals("M", v.cards.first().tileInitial)
        assertEquals("Barça · Mundo Deportivo · 2 h ago: Story b1", v.cards.first().spoken)
        assertEquals(1_000L + NewsWidgetRules.REFRESH_MS, v.nextChangeMs)
        assertEquals(5_000, NewsWidgetRules.FLIP_INTERVAL_MS)
    }

    @Test
    fun theMatchLeadsAndOpensNews() {
        val place = NewsPlace(listOf(lane("barca", "b1")), emptyList(), null, match(false))
        val v = NewsWidgetRules.view(TickerRules.ticker(place), 0L)
        val m = v.cards.first()
        assertTrue(m.isMatch)
        assertEquals("MATCHDAY", m.label)
        assertEquals("Barça v Real Madrid", m.title)
        assertEquals("21:00 · in 3 h", m.line)
        assertNull(m.openStoryId)
        assertNull(m.imageKey)
        assertTrue(m.isBarca)
        val live = NewsWidgetRules.view(TickerRules.ticker(NewsPlace(emptyList(), emptyList(), null, match(true))), 0L).cards.single()
        assertEquals("ON NOW", live.label)
        assertEquals("on now", live.line)
    }

    @Test
    fun atMostEightCardsAndPicturesTravelAsKeysOnly() {
        val many = (1..10).map { "b$it" }.toTypedArray()
        val place = NewsPlace(listOf(lane("barca", *many)), emptyList(), null, match(false))
        val v = NewsWidgetRules.view(TickerRules.ticker(place), 0L)
        assertEquals(NewsWidgetRules.MAX_CARDS, v.cards.size)
        assertEquals("match", v.cards.first().id)
        val pic = NewsWidgetRules.view(NewsTicker(null, listOf(item("x", "ai", "0123456789abcdef0123456789abcdef"))), 0L)
        assertEquals("0123456789abcdef0123456789abcdef", pic.cards.single().imageKey)
    }

    @Test
    fun nothingToShowSaysWhereToChooseTopicsAndRestsTheClock() {
        val v = NewsWidgetRules.view(TickerRules.ticker(NewsPlace.EMPTY), 0L)
        assertTrue(v.isEmpty)
        assertEquals("No news yet", v.emptyTitle)
        assertEquals(Long.MAX_VALUE, v.nextChangeMs)
        assertEquals("News. No news yet. Choose topics in MEKA · Ask › More › News", NewsWidgetRules.spoken(v))
    }

    @Test
    fun onlyWhatItSaysRedrawsIt() {
        val place = NewsPlace(listOf(lane("ai", "a1")), emptyList(), null)
        val a = NewsWidgetRules.view(TickerRules.ticker(place), 0L)
        val b = NewsWidgetRules.view(TickerRules.ticker(place), 60_000L)
        assertEquals(NewsWidgetRules.signature(a), NewsWidgetRules.signature(b))
        val c = NewsWidgetRules.view(TickerRules.ticker(NewsPlace(listOf(lane("ai", "a2")), emptyList(), null)), 0L)
        assertTrue(NewsWidgetRules.signature(a) != NewsWidgetRules.signature(c))
        assertEquals("News, 1 story. AI · Mundo Deportivo · 2 h ago: Story a1", NewsWidgetRules.spoken(a))
    }
}

class DeskNewsWidgetTest {
    private fun item(id: String, topic: String, source: String = "Sport", publishedAtMs: Long = 0L, image: String? = null) =
        NewsItem(id, "Story $id", null, source, topic, "$source · 2 h ago", null, publishedAtMs, image)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, NewsTopics.byId(topic)!!.label, "From Sport", ids.map { item(it, topic) })
    private fun match(live: Boolean) = NewsMatchday(
        "espn-1", "Barça v Real Madrid", if (live) "Barça v Real Madrid · on now" else "Barça v Real Madrid · 21:00 · in 3 h", live,
        CalendarEvent("espn-1", "Barça v Real Madrid", 1_000L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    @Test
    fun threeStoriesStillBarcaAndAiBeforeTheRest() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2"), lane("ai", "a1"), lane("top", "t1")), emptyList(), null)
        val v = DeskNewsWidgetRules.view(TickerRules.ticker(place))
        // The ticker's turn is b1, a1, t1, b2: the top-stories one waits behind Barça and AI.
        assertEquals(listOf("b1", "a1", "b2"), v.cards.map { it.id })
        assertEquals(listOf("Barça", "AI", "Barça"), v.cards.map { it.label })
        assertEquals(listOf(true, false, true), v.cards.map { it.isBarca })
        assertEquals("mekaos://news?story=b1", v.cards.first().openUrl)
        assertEquals("S", v.cards.first().tileInitial)
        assertEquals(Long.MAX_VALUE, v.cards.first().untilMs)
        assertFalse(v.isEmpty)
    }

    @Test
    fun otherLanesFillInWhenBarcaAndAiRunShort() {
        val place = NewsPlace(listOf(lane("ai", "a1"), lane("top", "t1", "t2"), lane("world", "w1")), emptyList(), null)
        assertEquals(listOf("a1", "t1", "w1"), DeskNewsWidgetRules.view(TickerRules.ticker(place)).cards.map { it.id })
    }

    @Test
    fun theMatchLeadsOpensNewsAndGoesAtTheFinalWhistle() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2", "b3")), emptyList(), null, match(false))
        val v = DeskNewsWidgetRules.view(TickerRules.ticker(place))
        assertEquals(listOf("match", "b1", "b2"), v.cards.map { it.id })
        val m = v.cards.first()
        assertTrue(m.isMatch)
        assertEquals("MATCHDAY", m.label)
        assertEquals("21:00 · in 3 h", DeskNewsWidgetRules.line(m, 0L))
        assertEquals("mekaos://news", m.openUrl)
        assertNull(m.imageKey)
        assertEquals(7_200_000L, m.untilMs)
        assertEquals(3, DeskNewsWidgetRules.showing(v, 7_199_999L).size)
        assertEquals(listOf("b1", "b2"), DeskNewsWidgetRules.showing(v, 7_200_000L).map { it.id })
        val live = DeskNewsWidgetRules.view(TickerRules.ticker(NewsPlace(emptyList(), emptyList(), null, match(true)))).cards.single()
        assertEquals("ON NOW", live.label)
        assertEquals("ON NOW · on now: Barça v Real Madrid", DeskNewsWidgetRules.spoken(live, 0L))
    }

    @Test
    fun aStorysLineMovesOnWithTimeLikeTheRestOfNews() {
        val hour = 3_600_000L
        val v = DeskNewsWidgetRules.view(NewsTicker(null, listOf(item("x", "ai", "The Verge", 10 * hour, "0123456789abcdef0123456789abcdef"))))
        val c = v.cards.single()
        assertEquals("0123456789abcdef0123456789abcdef", c.imageKey)
        assertEquals("The Verge · just now", DeskNewsWidgetRules.line(c, 10 * hour + 60_000L))
        assertEquals("The Verge · 25 min ago", DeskNewsWidgetRules.line(c, 10 * hour + 25 * 60_000L))
        assertEquals("The Verge · 2 h ago", DeskNewsWidgetRules.line(c, 12 * hour + 59 * 60_000L))
        assertEquals("The Verge · yesterday", DeskNewsWidgetRules.line(c, 40 * hour))
        assertEquals("AI · The Verge · 2 h ago: Story x", DeskNewsWidgetRules.spoken(c, 12 * hour))
    }

    @Test
    fun onlyPlainStoryIdsGoIntoTheLink() {
        assertEquals("mekaos://news?story=ab12-x_9", DeskNewsWidgetRules.openUrl("ab12-x_9"))
        assertEquals("mekaos://news", DeskNewsWidgetRules.openUrl("a&b=c"))
        assertEquals("mekaos://news", DeskNewsWidgetRules.openUrl(""))
    }

    @Test
    fun nothingToShowSaysWhereToChooseTopics() {
        val v = DeskNewsWidgetRules.view(TickerRules.ticker(NewsPlace.EMPTY))
        assertTrue(v.isEmpty)
        assertEquals("No news yet", v.emptyTitle)
        assertEquals("Choose topics in MEKA · Ask › More › News", v.emptyLine)
        assertEquals(30 * 60_000L, DeskNewsWidgetRules.REFRESH_MS)
    }
}
