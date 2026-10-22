package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DailyRow
import os.meka.core.domain.ReadingRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import os.meka.core.domain.AskOutcome

/** Reading and learning through the facade (slice 1): a book added on the Fold is moved on from the Mac; Daily's row follows. */
class ReadingFacadeTest {
    private var now = 1_792_314_000_000L // Sun 18 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aBookAddedOnTheFoldIsMovedOnFromTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(ReadingRules.EMPTY_SUMMARY, fold.readingView.value.summary)
        assertTrue(fold.addReading("book").refused)
        val added = fold.addReading("Atomic Habits by James Clear, 320 pages")
        assertEquals("Added book Atomic Habits by James Clear · up next", added.line)
        assertEquals("1 up next", fold.dailyLines.value.of(DailyRow.READING)?.text)

        fold.syncNow(); mac.syncNow()
        val id = mac.readingView.value.upNext.single().id
        assertEquals("Atomic Habits · Page 160 of 320 · 50%", mac.readingProgress(id, "p 160").line)
        assertTrue(mac.readingProgress(id, "lots").refused)
        mac.syncNow(); fold.syncNow()
        val v = fold.readingView.value
        assertEquals("Page 160 of 320 · 50%", v.onTheGo.single().progress)
        assertEquals("October · 1 still going", v.month)
        assertEquals("Atomic Habits · 50%", fold.dailyLines.value.of(DailyRow.READING)?.text)

        val finished = fold.finishReading(id)
        assertEquals(listOf("Atomic Habits"), fold.readingView.value.finished.map { it.title })
        assertTrue(fold.undoReading(finished.undo!!))
        assertEquals(listOf("Atomic Habits"), fold.readingView.value.onTheGo.map { it.title })
    }

    @Test
    fun askAndTalkKnowTheListAndMoveItOnAtHisWord() = runTest {
        val fold = core("android")
        val empty = assertIs<AskOutcome.Answered>(fold.askMeka("What am I reading?")).answer
        assertEquals(os.meka.core.domain.ReadingAskRules.EMPTY, empty.text)
        fold.addReading("reading Atomic Habits by James Clear, 320 pages")
        now += 60_000
        fold.addReading("Dune")

        val list = assertIs<AskOutcome.Answered>(fold.askMeka("what am I reading")).answer
        assertEquals("On the go: Atomic Habits. Up next: Dune.", list.text)

        // His own words: done at once, no AI (none is connected here), with Undo through Activity.
        val page = assertIs<AskOutcome.Answered>(fold.talk("I'm on page 200", emptyList())).answer
        assertEquals("Atomic Habits · Page 200 of 320 · 62%", page.did.single().line)
        assertTrue(page.cards.isEmpty())
        assertEquals("Page 200 of 320 · 62%", fold.readingView.value.onTheGo.single().progress)
        fold.undoAutoAsk(page.did.single().activityId)
        assertEquals("Started Sun 18 Oct", fold.readingView.value.onTheGo.single().progress)

        now += 60_000
        val added = assertIs<AskOutcome.Answered>(fold.askMeka("add the podcast The Rest Is History to my reading list")).answer
        assertEquals("Added podcast The Rest Is History · up next", added.did.single().line)
        assertEquals(listOf("Dune", "The Rest Is History"), fold.readingView.value.upNext.map { it.title })

        val done = assertIs<AskOutcome.Answered>(fold.askMeka("I've finished Atomic Habits")).answer
        assertEquals("Finished Atomic Habits", done.did.single().line)
        assertEquals(listOf("Atomic Habits"), fold.readingView.value.finished.map { it.title })
    }

    @Test
    fun aShareOffersAddToReadingAndAddsItOnHisTap() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val offer = fold.readingFromShared("https://www.goodreads.com/book/show/1", "Dune by Frank Herbert | Goodreads")
        assertEquals("Add to Reading · Dune by Frank Herbert", offer?.line)
        assertEquals(null, fold.readingFromShared("https://www.bbc.co.uk/news/1", "Rail fares | BBC News"))
        // Nothing is added until he taps it.
        assertTrue(fold.readingView.value.upNext.isEmpty())
        val added = fold.addReadingFromShared("https://www.goodreads.com/book/show/1", "Dune by Frank Herbert | Goodreads")
        assertEquals("Added book Dune by Frank Herbert · up next", added.line)
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Dune"), mac.readingView.value.upNext.map { it.title })
        assertTrue(fold.undoReading(added.undo!!))
        assertTrue(fold.readingView.value.upNext.isEmpty())
    }

    @Test
    fun aReadingGoalSetOnTheFoldIsTrackedInGoalsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(null, fold.goalsView.value.reading)
        assertTrue(fold.setReadingGoal("lots").refused)
        assertEquals("Goal: 12 books in 2026 · Goals shows how it's going", fold.setReadingGoal("12 books").line)
        assertEquals(12, fold.readingView.value.goalTarget)
        val id = fold.addReading("reading Atomic Habits").let { fold.readingView.value.onTheGo.single().id }
        fold.finishReading(id)
        fold.syncNow(); mac.syncNow()
        val g = mac.goalsView.value.reading!!
        assertEquals("Read 12 books in 2026", g.title)
        assertEquals("1 of 12", g.count)
        assertEquals("8 behind pace · 11 to go · about 4 a month", g.line)
        assertEquals("1 of 12", mac.readingView.value.goal?.count)
        val cleared = mac.setReadingGoal("none")
        assertEquals(null, mac.goalsView.value.reading)
        assertTrue(mac.undoReading(cleared.undo!!))
        assertEquals("1 of 12", mac.goalsView.value.reading?.count)
    }
}
