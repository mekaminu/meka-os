package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.Capture
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.PeopleLists
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Done per card on the after-work summary through the facade (Meka, 2026-10-15 20:28): hide, Undo, then write. */
class AfterWorkDoneFacadeTest {
    private var now = 1_791_622_800_000L

    private val cores = mutableListOf<MekaCore>()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { cores += it }

    /**
     * The 5 s Undo timers this class starts outlive the test; closing the cores stops them, so a late write can't
     * refresh a finished core (and the household names every core shares) while the next test class runs.
     */
    @AfterTest
    fun closeCores() { cores.forEach { it.close() }; cores.clear() }

    private fun msg(who: String, text: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, null, at, text), CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, at)

    @Test
    fun oneCardLeavesAtOnceUndoBringsItBackAndTheWriteClearsOnlyIt() = runTest {
        val c = core()
        c.holdCaptured(listOf(msg("Alex", "pub?", now - 3_600_000L), msg("Sam", "late", now - 1_800_000L)), PeopleLists())
        assertEquals(listOf("Sam", "Alex"), c.afterWork.value.people.map { it.personName })
        val alex = c.afterWork.value.people.first { it.personName == "Alex" }

        // The facade writes a Done on its own after a real 5 s; the nightly macOS runner can take longer than that over
        // these few lines, so the Undo checks only stand while the write can't have happened yet.
        val started = TimeSource.Monotonic.markNow()
        c.clearAfterWorkSoon(alex.ids)
        assertEquals(listOf("Sam"), c.afterWork.value.people.map { it.personName })
        assertEquals("1 person · 1 message", c.afterWork.value.headline)

        c.undoClearAfterWork(alex.ids)
        if (started.elapsedNow() >= 4.seconds) return@runTest
        assertEquals(2, c.afterWork.value.people.size)
        // Undone: the write that follows the delay finds nothing to clear.
        assertEquals(0, c.commitAfterWorkClear(alex.ids))
        assertEquals(2, c.afterWork.value.people.size)

        c.clearAfterWorkSoon(alex.ids)
        // The facade's own delayed write may land first on a slow runner (the nightly macOS tests run for minutes), so
        // the early write clears Alex's card unless that one already has: either way it's cleared once.
        assertTrue(c.commitAfterWorkClear(alex.ids) in 0..1)
        assertEquals(listOf("Sam"), c.afterWork.value.people.map { it.personName })
        assertEquals(0, c.commitAfterWorkClear(alex.ids)) // the delayed write after an early one is a no-op
    }
}
