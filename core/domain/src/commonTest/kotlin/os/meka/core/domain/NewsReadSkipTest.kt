package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Full news hub, slice 4: the ticker and the brief lead with what he hasn't read. */
class NewsReadSkipTest {
    private val now = 1_791_270_000_000L
    private fun h(id: String, agoMin: Long, topic: String = "ai", title: String = "Story $id") =
        Headline(id, title, "https://x.com/$id", "The Verge", topic, now - agoMin * 60_000L)
    private fun key(id: String) = NewsRules.readKey("https://x.com/$id", "")
    private val topics = listOf(NewsTopicChoice("ai", "AI", true), NewsTopicChoice("top", "Top", true))

    @Test
    fun theTickerSkipsReadStoriesWhileThereAreNewOnes() {
        val all = listOf(h("a1", 5), h("a2", 10), h("a3", 15), h("t1", 6, "top"), h("t2", 12, "top"))
        val read = setOf(key("a1"), key("t1"))
        val t = TickerRules.ticker(NewsRules.place(all, topics, now, read = read))
        // New ones first, a lane at a time; the read ones only after every new story.
        assertEquals(listOf("a2", "t2", "a3", "a1", "t1"), t.items.map { it.id })
        // Nothing read: the order is the lanes' turn as before.
        assertEquals(listOf("a1", "t1", "a2", "t2", "a3"), TickerRules.ticker(NewsRules.place(all, topics, now)).items.map { it.id })
    }

    @Test
    fun theTickerKeepsReadStoriesWhenEverythingIsRead() {
        val all = listOf(h("a1", 5), h("a2", 10))
        val t = TickerRules.ticker(NewsRules.place(all, topics, now, read = setOf(key("a1"), key("a2"))))
        assertEquals(listOf("a1", "a2"), t.items.map { it.id })
    }

    @Test
    fun theTickerTakesEveryNewStoryBeforeAnyRead() {
        val all = (1..14).map { h("a$it", it.toLong()) }
        val read = (1..4).map { key("a$it") }.toSet()
        val ids = TickerRules.ticker(NewsRules.place(all, topics, now, read = read)).items.map { it.id }
        // The lane holds 10 (the place's cap): its 6 new, then its read ones.
        assertEquals((5..10).map { "a$it" } + (1..4).map { "a$it" }, ids)
    }

    @Test
    fun theBriefSaysWhatsNewAndFallsBackOnReadOnes() {
        val all = (1..7).map { h("a$it", it.toLong()) }
        val read = setOf(key("a1"), key("a2"), key("a3"))
        assertEquals(listOf("a4", "a5", "a6", "a7", "a1"), NewsRules.forBrief(all, listOf("ai"), now, read = read).map { it.id })
        assertEquals((1..5).map { "a$it" }, NewsRules.forBrief(all, listOf("ai"), now).map { it.id })
    }

    @Test
    fun followedStoriesInTheBriefAreNewFirstToo() {
        val all = listOf(
            h("f1", 1, "sport", "Arsenal win again"), h("f2", 2, "sport", "Arsenal sign a keeper"),
            h("f3", 3, "sport", "Arsenal coach speaks"), h("f4", 4, "sport", "Arsenal fans travel"),
            h("a1", 5), h("a2", 6),
        )
        val brief = NewsRules.forBrief(all, listOf("ai"), now, listOf("Arsenal"), read = setOf(key("f1")))
        assertEquals(listOf("f2", "f3", "f4", "a1", "a2"), brief.map { it.id })
    }
}
