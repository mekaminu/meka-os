package os.meka.android.widgets

import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.NewsWidgetRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsWidgetRoutingTest {
    private fun item(id: String) = NewsItem(id, "Story $id", null, "Sport", "barca", "Sport · 2 h ago", null, 0L)

    @Test
    fun aTapCarriesItsStoryToMeka() {
        val card = NewsWidgetRules.view(NewsTicker(null, listOf(item("abc"))), 0L).cards.single()
        assertEquals("news:abc", NewsWidgetRouting.openExtra(card))
        assertEquals("abc", NewsWidgetRouting.storyFrom("news:abc"))
        // No story (the match, or "No news yet") opens News itself.
        assertEquals("news:", NewsWidgetRouting.openExtra(null as String?))
        assertEquals("", NewsWidgetRouting.storyFrom("news:"))
        assertNull(NewsWidgetRouting.storyFrom("dest:TODAY"))
    }

    @Test
    fun picturesAreShrunkToTheWidgetsSizeInPowersOfTwo() {
        assertEquals(1, NewsWidgetRouting.sampleSize(320, 180, NewsWidgetRouting.CARD_PICTURE_PX))
        assertEquals(2, NewsWidgetRouting.sampleSize(320, 320, NewsWidgetRouting.STRIP_PICTURE_PX))
        assertEquals(1, NewsWidgetRouting.sampleSize(100, 80, NewsWidgetRouting.STRIP_PICTURE_PX))
        assertEquals(4, NewsWidgetRouting.sampleSize(1000, 600, 240))
        assertTrue(NewsWidgetRouting.picturePx(NewsWidgetSize.STRIP) < NewsWidgetRouting.picturePx(NewsWidgetSize.CARD))
    }

    @Test
    fun eachSizeHasItsOwnLayoutsAndRequestCodes() {
        val layouts = NewsWidgetSize.entries.flatMap { listOf(NewsWidgetRouting.layout(it, false), NewsWidgetRouting.layout(it, true)) }
        assertEquals(4, layouts.toSet().size)
        assertNotEquals(NewsWidgetRouting.itemLayout(NewsWidgetSize.STRIP), NewsWidgetRouting.itemLayout(NewsWidgetSize.CARD))
        val codes = NewsWidgetSize.entries.map { NewsWidgetRouting.requestCode(it) } +
            HomeWidget.entries.map { WidgetRouting.requestCode(it) } + listOf(1, 2)
        assertEquals(codes.size, codes.toSet().size)
        assertNotEquals(NewsWidgetRouting.pageRequestCode(7, -1), NewsWidgetRouting.pageRequestCode(7, 1))
        assertNotEquals(NewsWidgetRouting.pageRequestCode(7, 1), NewsWidgetRouting.pageRequestCode(8, -1))
    }
}
