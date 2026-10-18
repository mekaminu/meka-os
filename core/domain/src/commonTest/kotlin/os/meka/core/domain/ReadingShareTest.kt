package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading and learning, slice 3: a book, podcast or course shared from another app offers Add to Reading. */
class ReadingShareTest {
    @Test
    fun aBookPageFromGoodreadsAudibleOrABookshopOffersItsTitleAndAuthor() {
        val gr = ReadingShareRules.offer("https://www.goodreads.com/book/show/40121378-atomic-habits", "Atomic Habits by James Clear | Goodreads")!!
        assertEquals("📖", gr.icon)
        assertEquals("Add to Reading · Atomic Habits by James Clear", gr.line)
        assertEquals(ReadingKind.BOOK, gr.kind)

        val app = ReadingShareRules.offer("Check out this book on Goodreads: Dune by Frank Herbert https://www.goodreads.com/book/show/44767458", null)!!
        assertEquals("Add to Reading · Dune by Frank Herbert", app.line)

        val amazon = ReadingShareRules.offer(
            "https://www.amazon.co.uk/dp/1847941834",
            "Atomic Habits: the life-changing million-copy bestseller : Clear, James: Amazon.co.uk: Books",
        )!!
        assertEquals("Add to Reading · Atomic Habits by James Clear", amazon.line)
        // Amazon's other pages aren't books.
        assertNull(ReadingShareRules.offer("https://www.amazon.co.uk/dp/B0ABC", "Kettle 1.7L : Amazon.co.uk: Home & Kitchen"))
    }

    @Test
    fun aPodcastOrACourseOffersItsKindAndAnArticleIsTheTrainQueues() {
        val pod = ReadingShareRules.offer("https://overcast.fm/+abc123", "Hard Fork")!!
        assertEquals("🎧", pod.icon)
        assertEquals(ReadingKind.PODCAST, pod.kind)
        assertEquals("Hard Fork", pod.title)

        val course = ReadingShareRules.offer("https://www.udemy.com/course/kotlin-coroutines/", "Kotlin Coroutines in depth | Udemy")!!
        assertEquals("🎓", course.icon)
        assertEquals("Add to Reading · Kotlin Coroutines in depth", course.line)

        assertNull(ReadingShareRules.offer("https://www.bbc.co.uk/news/articles/c123", "Rail fares to rise | BBC News"))
    }

    @Test
    fun aPlainLineOnlyWhenItSaysWhatItIs() {
        assertEquals("Add to Reading · Dune by Frank Herbert", ReadingShareRules.offer("Dune by Frank Herbert", null)!!.line)
        assertEquals(ReadingKind.PODCAST, ReadingShareRules.offer("podcast The Rest Is History", null)!!.kind)
        assertEquals("Add to Reading · Project Hail Mary", ReadingShareRules.offer("want to read Project Hail Mary", null)!!.line)
        assertNull(ReadingShareRules.offer("Meet Sam by the station", null))
        assertNull(ReadingShareRules.offer("Call the garage by Friday", null))
        assertNull(ReadingShareRules.offer("read the contract tonight", null))
        assertNull(ReadingShareRules.offer("Dear parents,\nThe trip is on Friday by coach", null))
        assertNull(ReadingShareRules.offer("", null))
    }

    @Test
    fun addingASharePutsItUpNextOnceAndOnTheMacToo() {
        val world = SyncWorld()
        var n = 0
        val fold = world.device("android")
        val mac = world.device("mac")
        val r = Reading(fold.replica, { "s${n++}" }, { world.clock.nowMs })
        val added = r.addShared("https://www.goodreads.com/book/show/1", "Dune by Frank Herbert | Goodreads")
        assertEquals("Added book Dune by Frank Herbert · up next", added.line)
        assertEquals("https://www.goodreads.com/book/show/1", r.items().single().url)
        assertEquals("Already on your list: Dune", r.addShared("Dune by Frank Herbert", null).line)
        assertTrue(r.addShared("https://www.bbc.co.uk/news/1", "Rail fares | BBC News").refused)
        // A plain line sent here on purpose (the Mac's Services entry) goes in as typed.
        assertEquals("Added book Sapiens · up next", r.addShared("Sapiens", null).line)
        fold.sync(); mac.sync()
        val onMac = Reading(mac.replica, { "m${n++}" }, { world.clock.nowMs }).items().first { it.title == "Dune" }
        assertEquals("Dune", onMac.title)
        assertEquals(ReadingState.WANT, onMac.state)
    }
}
