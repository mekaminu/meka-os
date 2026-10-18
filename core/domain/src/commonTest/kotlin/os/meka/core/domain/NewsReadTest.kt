package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full news hub, slice 1: read and new, by the story's link, and whether it waits in the train queue. */
class NewsReadTest {
    private val now = 1_791_270_000_000L
    private fun h(id: String, title: String, url: String?, agoMin: Long = 10, topic: String = "ai") =
        Headline(id, title, url, "The Verge", topic, now - agoMin * 60_000L)
    private val ai = listOf(NewsTopicChoice("ai", "AI", true))

    @Test
    fun aStoryIsKnownByItsLinkNotItsSlot() {
        val k = NewsRules.readKey("https://www.theverge.com/ai/1?utm_source=rss#top", "Anything")
        assertEquals(12, k.length)
        assertTrue(k.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(k, NewsRules.readKey("https://WWW.theverge.com/ai/1", "Another title"))
        assertFalse(k == NewsRules.readKey("https://www.theverge.com/ai/2", "Anything"))
        // No link: the title (accents and punctuation aside) is the story.
        assertEquals(NewsRules.readKey(null, "Barça win 3–1!"), NewsRules.readKey(null, "Barca win 3-1"))

        // Slot "s1" now holds a different story: it's new, though the old one in that slot was read.
        val read = setOf(NewsRules.readKey("https://www.theverge.com/ai/1", ""))
        val place = NewsRules.place(listOf(h("s1", "New story", "https://www.theverge.com/ai/9"), h("s2", "Old story", "https://www.theverge.com/ai/1", 30)), ai, now, read = read)
        val byId = place.items.associateBy { it.id }
        assertFalse(byId.getValue("s1").read)
        assertTrue(byId.getValue("s2").read)
        assertEquals(1, place.lanes.single().unread)
        assertEquals("1 new", place.lanes.single().unreadLabel)
        assertEquals("1 new · 1 read", place.unreadLine)
        assertTrue(place.canMarkAllRead)
    }

    @Test
    fun theLineSaysWhatsNewAndAllRead() {
        assertNull(NewsRules.unreadLine(0, 0))
        assertEquals("4 new", NewsRules.unreadLine(4, 4))
        assertEquals("All read · new stories get a dot", NewsRules.unreadLine(0, 4))
        val all = NewsRules.place(listOf(h("s1", "A", "https://x.com/a")), ai, now, read = setOf(NewsRules.readKey("https://x.com/a", "A")))
        assertNull(all.lanes.single().unreadLabel)
        assertFalse(all.canMarkAllRead)
    }

    @Test
    fun theReadListKeepsEachOnceAndTheNewest400() {
        val a = NewsRules.readKey("https://x.com/a", "")
        val b = NewsRules.readKey("https://x.com/b", "")
        val one = NewsRules.withRead(null, listOf(a, a))!!
        assertEquals(a, one)
        assertNull(NewsRules.withRead(one, listOf(a)))
        val two = NewsRules.withRead("$one,junk,../x", listOf(b))!!
        assertEquals(listOf(a, b), NewsRules.readKeys(two))
        assertEquals(listOf(b), NewsRules.readKeys(NewsRules.withoutRead(two, listOf(a))))
        assertNull(NewsRules.withoutRead(two, listOf(NewsRules.readKey("https://x.com/c", ""))))
        val many = (0 until 450).map { NewsRules.readKey("https://x.com/$it", "") }
        val kept = NewsRules.readKeys(NewsRules.withRead(null, many))
        assertEquals(NewsRules.MAX_READ, kept.size)
        assertEquals(many.takeLast(NewsRules.MAX_READ), kept)
    }

    @Test
    fun aStoryWaitingInTheTrainQueueSaysSo() {
        val queued = setOf(QueueRules.key("https://www.theverge.com/ai/1?utm_source=share"))
        val place = NewsRules.place(
            listOf(h("s1", "Queued", "https://www.theverge.com/ai/1"), h("s2", "Not yet", "https://www.theverge.com/ai/2"), h("s3", "No link", null)),
            ai, now, queuedLinks = queued,
        )
        val byId = place.items.associateBy { it.id }
        assertTrue(byId.getValue("s1").queued)
        assertFalse(byId.getValue("s1").canQueue)
        assertTrue(byId.getValue("s2").canQueue)
        assertFalse(byId.getValue("s3").canQueue)
    }
}
