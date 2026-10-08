package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTest {
    private val world = SyncWorld()
    private val hourMs = 3_600_000L
    private val minMs = 60_000L
    private val a = world.device("android")
    private val m = world.device("mac")

    init {
        world.clock.nowMs = 1_791_270_000_000L // a morning in October 2026
    }

    private val now get() = world.clock.nowMs

    private fun h(id: String, title: String, topic: String, agoMin: Long, url: String? = "https://www.bbc.com/news/articles/$id", source: String = "BBC News") =
        Headline(id, title, url, source, topic, now - agoMin * minMs)

    /** What the server writes: one headline per (topic, slot). Here written on a device replica to stand in for it. */
    private fun mirror(id: String, title: String, topic: String, agoMin: Long, url: String, removed: Boolean = false) {
        a.replica.commitLocal(
            EntityTypes.HEADLINE, id,
            mapOf(
                HeadlineFields.TITLE to title.fv(), HeadlineFields.URL to url.fv(), HeadlineFields.SOURCE to "BBC News".fv(),
                HeadlineFields.TOPIC to topic.fv(), HeadlineFields.PUBLISHED_AT to (now - agoMin * minMs).fv(),
                HeadlineFields.REMOVED to removed.fv(),
            ),
        )
    }

    @Test
    fun theBriefShowsChosenTopicsNewestFirstEachStoryOnce() {
        val all = listOf(
            h("1", "Rates held at 4%", "business", 30),
            h("2", "Storm warning for the north", "top", 90),
            h("3", "Storm warning for the north", "uk", 80, url = "https://www.bbc.com/news/articles/3x"), // same title
            h("4", "Summit opens in Geneva", "world", 10),
            h("2b", "Storm warning for the north", "world", 90, url = "https://www.bbc.com/news/articles/2?at_medium=RSS"), // same story
            h("5", "Old news", "top", 37 * 60),
        )
        val shown = NewsRules.forBrief(all, listOf("top", "world"), now)
        assertEquals(listOf("Summit opens in Geneva", "Storm warning for the north"), shown.map { it.title })
        assertEquals("BBC News · World · 10 min ago", shown[0].meta)
        assertEquals("BBC News · Top stories · 1 h ago", shown[1].meta)
        // One topic chosen: the topic isn't repeated on every line.
        assertEquals("BBC News · 30 min ago", NewsRules.forBrief(all, listOf("business"), now).single().meta)
        assertTrue(NewsRules.forBrief(all, emptyList(), now).isEmpty())
    }

    @Test
    fun atMostFiveHeadlines() {
        val all = (1..9).map { h("$it", "Story $it", "top", it.toLong()) }
        assertEquals((1..5).map { "Story $it" }, NewsRules.forBrief(all, listOf("top"), now).map { it.title })
    }

    @Test
    fun headlinesAreUntrustedTextAndOnlyHttpsLinksAreOffered() {
        assertEquals("Breaking: talks resume", NewsRules.clean("  <b>Breaking:</b>\n talks\u0007 resume "))
        assertEquals("https://www.bbc.com/news/x", NewsRules.safeUrl("https://www.bbc.com/news/x"))
        assertNull(NewsRules.safeUrl("http://www.bbc.com/news/x"))
        assertNull(NewsRules.safeUrl("javascript:alert(1)"))
        assertNull(NewsRules.safeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(NewsRules.safeUrl("https://user@evil.example/"))
        assertNull(NewsRules.safeUrl("https:///nohost"))
        assertNull(NewsRules.safeUrl("https://a.example/with space"))

        mirror("hl1", "<i>Summit</i> opens", "top", 5, "file:///etc/passwd")
        val read = News(a.replica).all().single()
        assertEquals("Summit opens", read.title)
        assertNull(read.url)
    }

    @Test
    fun emptySlotsAreNotShownAndAgesRead() {
        mirror("hl1", "Summit opens", "top", 5, "https://www.bbc.com/news/a")
        mirror("hl2", "Gone", "top", 5, "https://www.bbc.com/news/b", removed = true)
        assertEquals(listOf("Summit opens"), News(a.replica).all().map { it.title })
        assertEquals("just now", NewsRules.age(now - 4 * minMs, now))
        assertEquals("59 min ago", NewsRules.age(now - 59 * minMs, now))
        assertEquals("23 h ago", NewsRules.age(now - 23 * hourMs - 59 * minMs, now))
        assertEquals("yesterday", NewsRules.age(now - 30 * hourMs, now))
    }

    @Test
    fun topicChoiceSyncsAndDefaultsToBarcaAiTopStoriesAndWorld() {
        val na = News(a.replica)
        assertEquals(listOf("barca", "ai", "top", "world"), na.topics())
        na.setTopic("technology", true)
        na.setTopic("world", false)
        assertEquals(listOf("barca", "ai", "top", "technology"), na.topics()) // catalogue order
        a.sync(); m.sync()
        val nm = News(m.replica)
        assertEquals(listOf("barca", "ai", "top", "technology"), nm.topics())
        assertEquals(listOf("barca", "ai", "top", "technology"), nm.choices().filter { it.chosen }.map { it.id })
        for (t in listOf("barca", "ai", "top", "technology")) nm.setTopic(t, false)
        m.sync(); a.sync()
        assertTrue(na.topics().isEmpty()) // no news at all is a valid choice
        // Unknown ids from a newer app version are ignored.
        assertEquals(listOf("uk"), NewsTopics.decode("uk,weather"))
    }

    @Test
    fun aChoiceMadeBeforeTheNewTopicsGetsBarcaAndAiButKeepsWhatWasTurnedOff() {
        // Stored by an older app: Top stories and Technology, no record of which topics it knew (the first nine).
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, News.ENTITY_ID, mapOf(NewsFields.TOPICS to "top,technology".fv()))
        val na = News(a.replica)
        assertEquals(listOf("barca", "ai", "top", "technology"), na.topics())
        // Turning Barça off now sticks: the choice records that it knew about it.
        na.setTopic("barca", false)
        a.sync(); m.sync()
        assertEquals(listOf("ai", "top", "technology"), News(m.replica).topics())
        // Pure rule: nothing stored is the defaults; a topic the choice knew and left out stays off.
        assertEquals(listOf("barca", "ai", "top", "world"), NewsRules.chosen(null, null))
        assertEquals(listOf("world"), NewsRules.chosen("world", NewsTopics.ALL.joinToString(",") { it.id }))
        assertEquals(emptyList(), NewsRules.chosen("", NewsTopics.ALL.joinToString(",") { it.id }))
    }

    @Test
    fun theSameStoryFromSeveralSourcesIsOneKey() {
        assertEquals(NewsRules.storyKey("Barça beat Real Madrid 3–1!"), NewsRules.storyKey("  barca BEAT real madrid 3-1"))
        assertEquals(NewsRules.storyKey("Flick: \"Lamine está bien\""), NewsRules.storyKey("Flick - Lamine esta bien"))
        assertTrue(NewsRules.storyKey("Barça beat Real Madrid") != NewsRules.storyKey("Barça lose to Real Madrid"))
    }

    private fun nh(id: String, title: String, topic: String, agoMin: Long, source: String, summary: String? = null) =
        Headline(id, title, "https://news.example/$id", source, topic, now - agoMin * minMs, summary)

    private fun choices(vararg chosen: String) = NewsTopics.ALL.map { NewsTopicChoice(it.id, it.label, it.id in chosen) }

    @Test
    fun theNewsPlaceLeadsWithBarcaThenAiEachStoryOnceNewestFirst() {
        val all = listOf(
            nh("1", "Summit opens in Geneva", "world", 10, "BBC News"),
            nh("2", "Flick names his XI for El Clásico", "barca", 30, "Mundo Deportivo", "Flick has named the side to face Real Madrid."),
            nh("3", "Flick names his XI for El Clásico", "barca", 25, "Sport"), // the same story from another paper
            nh("4", "Pedri back in training", "barca", 90, "Sport"),
            nh("5", "A new open model tops the charts", "ai", 5, "The Verge"),
            nh("6", "A new open model tops the charts", "tech", 4, "Hacker News"), // same story, later lane
            nh("7", "Rust 2.0 announced", "tech", 50, "Hacker News"),
            nh("8", "Old Barça story", "barca", 49 * 60, "Sport"), // older than two days
            nh("9", "Rates held", "business", 5, "BBC News"), // topic not chosen
        )
        val place = NewsRules.place(all, choices("top", "world", "tech", "ai", "barca"), now)
        assertEquals(listOf("barca", "ai", "world", "tech"), place.lanes.map { it.topicId })
        val barca = place.lanes[0]
        assertEquals("Barça", barca.label)
        assertEquals(listOf("Flick names his XI for El Clásico", "Pedri back in training"), barca.items.map { it.title })
        assertEquals("Sport · 25 min ago", barca.items[0].meta) // the newest copy of the story wins
        assertEquals("From Sport", barca.sources)
        assertEquals(listOf("Rust 2.0 announced"), place.lanes[3].items.map { it.title })
        assertNull(place.emptyLine)

        // Detail: position and neighbours run across lanes in reading order.
        val d = place.detail("5")!!
        assertEquals("3 of 5", d.position)
        assertEquals("4", d.previousId)
        assertEquals("1", d.nextId)
        assertNull(place.detail("2")) // left out as a duplicate
        assertNull(place.detail("3")!!.previousId)

        assertEquals("No topics chosen · pick some below", NewsRules.place(all, choices(), now).emptyLine)
        assertEquals("No headlines in the last two days · they refresh every hour", NewsRules.place(emptyList(), choices("ai"), now).emptyLine)
    }

    @Test
    fun aLaneNamesItsSourcesAndKeepsTen() {
        val all = (1..14).map { nh("b$it", "Barça story $it", "barca", it.toLong(), if (it % 3 == 0) "Sport" else "Mundo Deportivo") } +
            nh("g1", "Barça story from Google", "barca", 20, "Marca")
        val lane = NewsRules.place(all, choices("barca"), now).lanes.single()
        assertEquals(NewsRules.MAX_IN_LANE, lane.items.size)
        assertEquals("From Mundo Deportivo and Sport", lane.sources)
    }

    @Test
    fun summariesArePlainTextFromTheMirror() {
        a.replica.commitLocal(
            EntityTypes.HEADLINE, "hlx",
            mapOf(
                HeadlineFields.TITLE to "Pedri back".fv(), HeadlineFields.URL to "https://www.sport.es/x".fv(), HeadlineFields.SOURCE to "Sport".fv(),
                HeadlineFields.TOPIC to "barca".fv(), HeadlineFields.PUBLISHED_AT to now.fv(), HeadlineFields.REMOVED to false.fv(),
                HeadlineFields.SUMMARY to "<p>El centrocampista\n vuelve</p> a entrenar".fv(),
            ),
        )
        a.sync(); m.sync()
        val read = News(m.replica).all().single()
        assertEquals("El centrocampista vuelve a entrenar", read.summary)
        assertEquals("El centrocampista vuelve a entrenar", News(m.replica).place(now).detail("hlx")!!.item.summary)
    }

    @Test
    fun theBriefCarriesHeadlinesFromTheChosenTopics() {
        mirror("hl1", "Summit opens", "world", 5, "https://www.bbc.com/news/a")
        mirror("hl2", "New phone launched", "technology", 20, "https://www.bbc.com/news/b")
        val news = News(a.replica)
        val day = now.floorDiv(CivilDate.DAY_MS)
        fun brief() = MorningBrief(a.replica, { now }).view(
            emptyList(), emptyList(), WorkSchedule.DEFAULT, QuietHours.DEFAULT, ListsView.EMPTY, GoalsView.EMPTY, FastingView.EMPTY,
            DayWindow(day * CivilDate.DAY_MS, (day + 1) * CivilDate.DAY_MS), news.all(), news.choices(),
        )
        assertEquals(listOf("Summit opens"), brief().headlines.map { it.title })
        news.setTopic("technology", true)
        assertEquals(listOf("Summit opens", "New phone launched"), brief().headlines.map { it.title })
        assertEquals(NewsTopics.ALL.size, brief().newsTopics.size)
    }

    // News ticker, slice 2: the matchday lead and the Barça lane.

    private val bst = LocalCalendar.fixedOffset(hourMs)

    private fun fixture(id: String, title: String, startMs: Long, provider: String = "fixtures", allDay: Boolean = false) =
        CalendarEvent(id, title, startMs, startMs + 2 * hourMs, allDay, null, provider, null, "FC Barcelona")

    /** Local [h]:[m] today in the test's time zone. */
    private fun todayAt(h: Int, m: Int = 0) = bst.toEpochMs(bst.epochDayOf(now), h * 60 + m)

    @Test
    fun onMatchdayTheNewsPlaceLeadsWithTheFixture() {
        world.clock.nowMs = todayAt(18, 0)
        val match = fixture("espn-1", "Barça v Real Madrid", todayAt(21, 0))
        val md = NewsRules.matchday(listOf(match), now, bst)!!
        assertEquals("Barça v Real Madrid · 21:00 · in 3 h", md.line)
        assertEquals("espn-1", md.eventId)
        assertEquals(match, md.event)
        assertEquals(false, md.live)
        assertEquals("Barça v Real Madrid · 21:00 · in 25 min", NewsRules.matchday(listOf(match), todayAt(20, 35), bst)!!.line)
        assertEquals("Barça v Real Madrid · 21:00 · kicking off", NewsRules.matchday(listOf(match), todayAt(20, 59) + 30_000, bst)!!.line)
        val on = NewsRules.matchday(listOf(match), todayAt(21, 40), bst)!!
        assertEquals("Barça v Real Madrid · 21:00 · on now", on.line)
        assertTrue(on.live)
        // Gone at the final whistle (the fixture's end).
        assertNull(NewsRules.matchday(listOf(match), todayAt(23, 0), bst))
        // Kick-off not set yet.
        assertEquals("Girona v Barça · today · kick-off TBC",
            NewsRules.matchday(listOf(fixture("espn-2", "Girona v Barça (kick-off TBC)", todayAt(20, 0))), now, bst)!!.line)
        // Tomorrow's fixture, another calendar's event or an all-day entry aren't matchday.
        assertNull(NewsRules.matchday(listOf(fixture("espn-3", "Barça v Sevilla", todayAt(21, 0) + 24 * hourMs)), now, bst))
        assertNull(NewsRules.matchday(listOf(fixture("g-1", "Watch Barça v Madrid", todayAt(21, 0), provider = "google")), now, bst))
        assertNull(NewsRules.matchday(listOf(fixture("espn-4", "Barça v Sevilla", bst.epochDayOf(now) * 86_400_000L, allDay = true)), now, bst))
        // Two today (a friendly and the league): the next one leads.
        assertEquals("espn-5", NewsRules.matchday(listOf(match, fixture("espn-5", "Barça v Como", todayAt(19, 0))), now, bst)!!.eventId)
    }

    @Test
    fun theMatchdayLineShowsOnlyWithBarcaChosenAndTheBarcaLaneIsMarked() {
        world.clock.nowMs = todayAt(18, 0)
        val md = NewsRules.matchday(listOf(fixture("espn-1", "Barça v Real Madrid", todayAt(21, 0))), now, bst)
        val all = listOf(nh("b1", "Pedri fit for the Clásico", "barca", 30, "Sport"), nh("a1", "New model released", "ai", 20, "The Verge"))
        val withBarca = NewsRules.place(all, choices("barca", "ai"), now, md)
        assertEquals("Barça v Real Madrid · 21:00 · in 3 h", withBarca.matchday?.line)
        assertEquals(listOf(true, false), withBarca.lanes.map { it.isBarca })
        assertNull(NewsRules.place(all, choices("ai"), now, md).matchday)
        // No headlines yet but a match today: the line still leads.
        assertEquals(md, NewsRules.place(emptyList(), choices("barca"), now, md).matchday)
    }

    @Test
    fun theFacadesNewsPlaceReadsTodaysFixtureAndHidesAHiddenOne() {
        world.clock.nowMs = todayAt(18, 0)
        val match = fixture("espn-1", "Barça v Real Madrid", todayAt(21, 0))
        assertEquals("Barça v Real Madrid · 21:00 · in 3 h", News(m.replica).place(now, listOf(match), bst).matchday?.line)
        // The facade passes only the events on my day; a hidden fixture isn't among them.
        assertNull(News(m.replica).place(now, emptyList(), bst).matchday)
    }
}
