package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Full news hub, slice 5: a feed the server couldn't read is named in News and marked on its source switch. */
class NewsFeedsDownTest {
    private val now = 1_791_270_000_000L
    private val hour = 3_600_000L
    private fun h(id: String, title: String, source: String, topic: String = "ai", agoMin: Long = 10) =
        Headline(id, title, "https://example.com/$id", source, topic, now - agoMin * 60_000L)
    private val topics = listOf(
        NewsTopicChoice("ai", "AI", true), NewsTopicChoice("tech", "Tech news", true), NewsTopicChoice("world", "World", false),
    )
    private val all = listOf(
        h("a1", "Model ships", "The Verge"), h("a2", "Funding round", "TechCrunch"), h("t1", "Show HN", "Hacker News", topic = "tech"),
    )

    @Test
    fun aFeedDownTwiceInARowIsNamedAfterTheLanesAndOnItsSwitch() {
        val down = listOf(NewsFeedDown("The Verge", "ai", now - 5 * hour, 6))
        val place = NewsRules.place(all, topics, now, down = down)
        // Its older stories still show; the line says it isn't coming through now.
        assertEquals(listOf("a1", "a2", "t1"), place.items.map { it.id })
        assertEquals(listOf("The Verge · AI · not coming through for 5 h · Navilife keeps trying"), place.quiet)
        assertEquals("The Verge · 1 · not coming through", place.sources.first { it.name == "The Verge" }.label)
        assertTrue(place.sources.first { it.name == "The Verge" }.down)
        assertFalse(place.sources.first { it.name == "TechCrunch" }.down)
        assertEquals("3 sources · all on · 1 not coming through · tap one to leave its stories out of News and the brief", place.sourcesLine)
    }

    @Test
    fun oneFailedTryAnUnchosenTopicOrASwitchedOffSourceSayNothing() {
        assertTrue(NewsRules.place(all, topics, now, down = listOf(NewsFeedDown("The Verge", "ai", now - hour / 2, 1))).quiet.isEmpty())
        assertTrue(NewsRules.place(all, topics, now, down = listOf(NewsFeedDown("BBC News", "world", now - 9 * hour, 9))).quiet.isEmpty())
        val off = NewsRules.place(all, topics, now, sourcesOff = listOf("The Verge"), down = listOf(NewsFeedDown("The Verge", "ai", now - 9 * hour, 9)))
        assertTrue(off.quiet.isEmpty())
        assertEquals("The Verge · off", off.sources.first { it.name == "The Verge" }.label)
    }

    @Test
    fun oneSourceAcrossTopicsIsOneLineLongestDownFirst() {
        val down = listOf(
            NewsFeedDown("Hacker News", "tech", now - 2 * hour, 2),
            NewsFeedDown("Football España", "tech", now - 3 * 24 * hour, 70),
            NewsFeedDown("football españa", "ai", now - 4 * hour, 4),
        )
        assertEquals(
            listOf(
                "Football España · AI and Tech news · not coming through for 3 days · Navilife keeps trying",
                "Hacker News · Tech news · not coming through for 2 h · Navilife keeps trying",
            ),
            NewsRules.downLines(NewsRules.downShown(down, listOf("ai", "tech"), emptySet()), now),
        )
    }

    @Test
    fun aDownAggregatorGetsALineButNoSwitch() {
        // Google News's stories carry their publishers' names, so there's no "Google News" switch to mark.
        val place = NewsRules.place(all, topics, now, down = listOf(NewsFeedDown("Google News", "ai", now - 3 * hour, 3)))
        assertEquals(listOf("Google News · AI · not coming through for 3 h · Navilife keeps trying"), place.quiet)
        assertTrue(place.sources.none { it.name == "Google News" })
    }

    @Test
    fun withNothingToShowTheEmptyLineStaysAndTheDownLineFollows() {
        val place = NewsRules.place(emptyList(), topics, now, down = listOf(NewsFeedDown("The Verge", "ai", now - 30 * 60_000L, 2)))
        assertEquals("No headlines in the last two days · they refresh every hour", place.emptyLine)
        assertEquals(listOf("The Verge · AI · not coming through for under an hour · Navilife keeps trying"), place.quiet)
    }
}
