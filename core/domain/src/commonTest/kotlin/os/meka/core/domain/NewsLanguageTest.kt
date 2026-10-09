package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** English-only news by default, Spanish sources behind a switch (Fold review 2026-10-09 07:26 item 1). */
class NewsLanguageTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private val minMs = 60_000L

    init {
        world.clock.nowMs = 1_791_270_000_000L
    }

    private val now get() = world.clock.nowMs

    private fun mirror(id: String, title: String, source: String, topic: String, lang: String?) {
        val fields = mutableMapOf(
            HeadlineFields.TITLE to title.fv(), HeadlineFields.URL to "https://news.example/$id".fv(), HeadlineFields.SOURCE to source.fv(),
            HeadlineFields.TOPIC to topic.fv(), HeadlineFields.PUBLISHED_AT to (now - 10 * minMs).fv(), HeadlineFields.REMOVED to false.fv(),
        )
        if (lang != null) fields[HeadlineFields.LANG] = lang.fv()
        a.replica.commitLocal(EntityTypes.HEADLINE, id, fields)
    }

    private fun seed() {
        mirror("en1", "Barcelona beat Getafe 3-0", "Barca Universal", "barca", null)
        mirror("en2", "Flick names his XI", "Football España", "barca", "en")
        mirror("es1", "Flick ya tiene el once", "Mundo Deportivo", "barca", "es")
        mirror("es2", "La selección prepara el partido", "Marca", "spain", "ES")
        mirror("fr1", "Le Barça gagne", "L'Équipe", "barca", "fr")
    }

    @Test
    fun spanishStoriesAreLeftOutUntilTheSwitchIsOnAndTheSwitchSyncs() {
        seed()
        a.sync(); m.sync()
        val news = News(m.replica)
        assertFalse(news.spanish())
        assertEquals(setOf("en1", "en2"), news.all().map { it.id }.toSet())
        assertEquals(5, news.everything().size) // still mirrored, just not shown
        val place = news.place(now)
        assertEquals(listOf("en1", "en2"), place.items.map { it.id }.sorted())
        assertFalse(place.spanishSources)
        assertEquals("Off · English only", place.spanishLine)
        assertFalse("Mundo Deportivo" in place.sourcesCaption)
        assertTrue(NewsRules.forBrief(news.all(), news.topics(), now).none { it.title.startsWith("Flick ya") })

        News(a.replica).setSpanish(true)
        a.sync(); m.sync()
        assertTrue(news.spanish())
        // Spanish shows in its own topic's lane; another language still never does.
        assertEquals(setOf("en1", "en2", "es1", "es2"), news.all().map { it.id }.toSet())
        val on = news.place(now)
        assertTrue("es1" in on.lanes.first { it.topicId == "barca" }.items.map { it.id })
        assertEquals("On · Mundo Deportivo, Sport and Spanish Google News", on.spanishLine)
        assertTrue("Mundo Deportivo" in on.sourcesCaption)

        news.setSpanish(false)
        m.sync(); a.sync()
        assertEquals(setOf("en1", "en2"), News(a.replica).all().map { it.id }.toSet())
    }

    @Test
    fun theTickerAndBriefReadTheSameEnglishOnlyList() {
        seed()
        a.sync()
        val news = News(a.replica)
        val ticker = TickerRules.ticker(news.place(now))
        assertTrue(ticker.toString().contains("Barcelona beat Getafe"))
        assertFalse(ticker.toString().contains("Flick ya tiene"))
    }
}
