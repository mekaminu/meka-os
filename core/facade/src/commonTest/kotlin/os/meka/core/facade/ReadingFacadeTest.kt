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
}
