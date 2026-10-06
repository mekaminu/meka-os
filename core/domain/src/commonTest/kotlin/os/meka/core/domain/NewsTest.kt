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
    fun topicChoiceSyncsAndDefaultsToTopStoriesAndWorld() {
        val na = News(a.replica)
        assertEquals(listOf("top", "world"), na.topics())
        na.setTopic("technology", true)
        na.setTopic("world", false)
        assertEquals(listOf("top", "technology"), na.topics()) // catalogue order
        a.sync(); m.sync()
        val nm = News(m.replica)
        assertEquals(listOf("top", "technology"), nm.topics())
        assertEquals(listOf("top", "technology"), nm.choices().filter { it.chosen }.map { it.id })
        nm.setTopic("top", false); nm.setTopic("technology", false)
        m.sync(); a.sync()
        assertTrue(na.topics().isEmpty()) // no news at all is a valid choice
        // Unknown ids from a newer app version are ignored.
        assertEquals(listOf("uk"), NewsTopics.decode("uk,weather"))
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
}
