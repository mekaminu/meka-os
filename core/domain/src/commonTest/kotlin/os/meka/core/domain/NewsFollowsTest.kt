package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full news hub, slice 3: words and names he follows, from any topic, leading News and the brief. */
class NewsFollowsTest {
    private val now = 1_791_270_000_000L
    private fun h(id: String, title: String, topic: String = "ai", agoMin: Long = 10, summary: String? = null, source: String = "BBC News") =
        Headline(id, title, "https://example.com/$id", source, topic, now - agoMin * 60_000L, summary)
    private val topics = listOf(NewsTopicChoice("ai", "AI", true), NewsTopicChoice("world", "World", false))

    @Test
    fun typedFollowsSplitOnCommasAndAreKeptOnceInOrder() {
        assertEquals(listOf("Arsenal", "Lamine Yamal", "Marks and Spencer"), NewsRules.parseFollows(" Arsenal, #Lamine  Yamal ; \"Marks and Spencer\", x, arsenal"))
        val (one, added) = NewsRules.withFollows(null, listOf("Arsenal", "Lamine Yamal"), on = true)!!
        assertEquals("Arsenal,Lamine Yamal", one)
        assertEquals(listOf("Arsenal", "Lamine Yamal"), added)
        assertNull(NewsRules.withFollows(one, listOf("ARSENAL"), on = true))
        assertEquals("Lamine Yamal" to listOf("Arsenal"), NewsRules.withFollows(one, listOf("arsenal"), on = false))
        assertNull(NewsRules.withFollows(one, listOf("Chelsea"), on = false))
        // At most 12: the newest past the limit aren't added.
        var s: String? = null
        for (i in 1..15) s = NewsRules.withFollows(s, listOf("Word$i"), on = true)?.first ?: s
        assertEquals(NewsRules.MAX_FOLLOWS, NewsRules.follows(s).size)
        assertEquals("Word12", NewsRules.follows(s).last())
    }

    @Test
    fun aMatchIsWholeWordsInTheTitleOrSummaryWithAccentsIgnored() {
        val follows = listOf("AI", "Barça", "Lamine Yamal")
        assertNull(NewsRules.followMatch(h("1", "What the minister said"), follows))
        assertEquals("AI", NewsRules.followMatch(h("2", "New AI rules agreed"), follows))
        assertEquals("Barça", NewsRules.followMatch(h("3", "Barca win again"), follows))
        assertEquals("Lamine Yamal", NewsRules.followMatch(h("4", "Teenager shines", summary = "Lamine Yamal scored twice."), follows))
        assertNull(NewsRules.followMatch(h("5", "Lamine shines"), listOf("Lamine Yamal")))
    }

    @Test
    fun followedStoriesLeadInTheirOwnLaneFromAnyTopicAndShowOnce() {
        val all = listOf(
            h("a1", "Model ships"), h("a2", "Arsenal back AI training", agoMin = 30),
            h("w1", "Arsenal win in Madrid", topic = "world", agoMin = 20),
            h("w2", "Elsewhere", topic = "world"),
        )
        val place = NewsRules.place(all, topics, now, follows = listOf("Arsenal", "Bitcoin"))
        assertEquals(listOf("following", "ai"), place.lanes.map { it.topicId })
        assertTrue(place.lanes.first().isFollowing)
        assertEquals(listOf("w1", "a2"), place.lanes.first().items.map { it.id })
        assertEquals("BBC News · 20 min ago · Arsenal", place.lanes.first().items.first().meta)
        assertEquals(listOf("a1"), place.lanes[1].items.map { it.id })
        assertEquals(listOf("Arsenal · 2", "Bitcoin · none yet"), place.follows.map { it.label })
        assertEquals("Following 2 · stories naming them lead News, the ticker and the brief · tap one to stop", place.followsLine)
        // Nothing followed: as before, and the line says what following does.
        val none = NewsRules.place(all, topics, now)
        assertEquals(listOf("ai"), none.lanes.map { it.topicId })
        assertTrue(none.followsLine.startsWith("Follow a team"))
        // No topics chosen but a follow: its lane alone.
        val onlyFollow = NewsRules.place(all, topics.map { it.copy(chosen = false) }, now, follows = listOf("Arsenal"))
        assertEquals(listOf("following"), onlyFollow.lanes.map { it.topicId })
        assertNull(onlyFollow.emptyLine)
    }

    @Test
    fun theBriefLeadsWithAtMostThreeFollowedStories() {
        val all = (1..5).map { h("w$it", "Arsenal story $it", topic = "world", agoMin = it * 10L) } + (1..5).map { h("a$it", "AI story $it", agoMin = it * 11L) }
        val brief = NewsRules.forBrief(all, listOf("ai"), now, listOf("Arsenal"))
        assertEquals(listOf("Arsenal story 1", "Arsenal story 2", "Arsenal story 3", "AI story 1", "AI story 2"), brief.map { it.title })
        assertEquals("BBC News · Following Arsenal · 10 min ago", brief.first().meta)
        assertEquals((1..5).map { "AI story $it" }, NewsRules.forBrief(all, listOf("ai"), now).map { it.title })
    }
}
