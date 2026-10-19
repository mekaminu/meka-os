package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full news hub, slice 2: sources he picks, and a line for each chosen topic with nothing to show. */
class NewsSourcesTest {
    private val now = 1_791_270_000_000L
    private fun h(id: String, title: String, source: String, topic: String = "ai", agoMin: Long = 10) =
        Headline(id, title, "https://example.com/$id", source, topic, now - agoMin * 60_000L)
    private val topics = listOf(
        NewsTopicChoice("ai", "AI", true), NewsTopicChoice("health", "Health", true), NewsTopicChoice("tech", "Tech news", true),
        NewsTopicChoice("world", "World", false),
    )

    @Test
    fun theOffListKeepsNamesOnceAndMatchesLoosely() {
        val one = NewsRules.withSource(null, "The Verge", on = false)
        assertEquals("The Verge", one)
        assertNull(NewsRules.withSource(one, "the  verge", on = false))
        assertEquals("The Verge,Daily Star", NewsRules.withSource(one, "Daily, Star", on = false))
        assertEquals("", NewsRules.withSource(one, "THE VERGE", on = true))
        assertNull(NewsRules.withSource(one, "TechCrunch", on = true))
        assertNull(NewsRules.withSource(null, " , ", on = false))
        assertEquals(setOf("the verge", "daily star"), NewsRules.sourcesOffKeys("The Verge, Daily Star,,the verge"))
        // At most 40 kept, the oldest dropped.
        var s: String? = null
        for (i in 1..45) s = NewsRules.withSource(s, "Source $i", on = false)
        val off = NewsRules.sourcesOff(s)
        assertEquals(NewsRules.MAX_SOURCES_OFF, off.size)
        assertEquals("Source 6", off.first())
    }

    @Test
    fun anOffSourceLeavesTheLanesAndIsStillListedToTurnBackOn() {
        val all = listOf(
            h("a1", "Model ships", "The Verge"), h("a2", "Chip news", "The Verge", agoMin = 20), h("a3", "Funding round", "TechCrunch"),
            h("t1", "Show HN", "Hacker News", topic = "tech"),
            h("w1", "Elsewhere", "BBC News", topic = "world"),
            h("x1", "Old", "Old Source", agoMin = 3 * 24 * 60),
        )
        val on = NewsRules.place(all, topics, now)
        assertEquals(listOf("The Verge", "Hacker News", "TechCrunch"), on.sources.map { it.name })
        assertEquals("The Verge · 2", on.sources.first().label)
        assertTrue(on.sources.all { it.on })
        assertEquals("3 sources · all on · tap one to leave its stories out of News and the brief", on.sourcesLine)
        assertEquals(listOf("Health · nothing in the last two days"), on.quiet)

        val off = NewsRules.place(all, topics, now, sourcesOff = listOf("The Verge", "hacker news", "Gone Away"))
        assertEquals(listOf("a3"), off.items.map { it.id })
        assertEquals(listOf("The Verge · off", "Hacker News · off", "TechCrunch · 1", "Gone Away · off"), off.sources.map { it.label })
        assertEquals("4 sources · 3 off · tap one to leave its stories out of News and the brief", off.sourcesLine)
        assertEquals(listOf("Health · nothing in the last two days", "Tech news · its sources are off"), off.quiet)
        assertNull(off.emptyLine)
    }

    @Test
    fun withEverySourceOffTheEmptyLineSaysSo() {
        val all = listOf(h("a1", "Model ships", "The Verge"))
        val place = NewsRules.place(all, topics, now, sourcesOff = listOf("The Verge"))
        assertTrue(place.lanes.isEmpty())
        assertEquals("Nothing from the sources you have on · turn some back on under Topics", place.emptyLine)
        assertTrue(place.quiet.isEmpty())
        assertNull(NewsRules.place(emptyList(), emptyList(), now).sourcesLine)
    }

    @Test
    fun theSameStoryFromAnotherSourceStillShowsWhenOneIsOff() {
        val all = listOf(h("a1", "Model ships today", "The Verge"), h("a2", "Model ships today", "TechCrunch", agoMin = 30))
        assertEquals(listOf("a1"), NewsRules.place(all, topics, now).items.map { it.id })
        assertEquals(listOf("a2"), NewsRules.place(all, topics, now, sourcesOff = listOf("The Verge")).items.map { it.id })
        assertEquals(listOf("a2"), NewsRules.fromSourcesOn(all, setOf("the verge")).map { it.id })
    }
}
