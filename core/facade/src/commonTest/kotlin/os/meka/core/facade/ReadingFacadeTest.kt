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
        assertEquals("October · 1 moved on", v.month)
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
}
