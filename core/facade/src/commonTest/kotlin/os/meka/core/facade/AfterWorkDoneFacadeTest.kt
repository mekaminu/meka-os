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
import kotlin.test.Test
import kotlin.test.assertEquals

/** Done per card on the after-work summary through the facade (Meka, 2026-10-15 20:28): hide, Undo, then write. */
class AfterWorkDoneFacadeTest {
    private var now = 1_791_622_800_000L

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun msg(who: String, text: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, null, at, text), CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, at)

    @Test
    fun oneCardLeavesAtOnceUndoBringsItBackAndTheWriteClearsOnlyIt() = runTest {
        val c = core()
        c.holdCaptured(listOf(msg("Alex", "pub?", now - 3_600_000L), msg("Sam", "late", now - 1_800_000L)), PeopleLists())
        assertEquals(listOf("Sam", "Alex"), c.afterWork.value.people.map { it.personName })
        val alex = c.afterWork.value.people.first { it.personName == "Alex" }

        c.clearAfterWorkSoon(alex.ids)
        assertEquals(listOf("Sam"), c.afterWork.value.people.map { it.personName })
        assertEquals("1 person · 1 message", c.afterWork.value.headline)

        c.undoClearAfterWork(alex.ids)
        assertEquals(2, c.afterWork.value.people.size)
        // Undone: the write that follows the delay finds nothing to clear.
        assertEquals(0, c.commitAfterWorkClear(alex.ids))
        assertEquals(2, c.afterWork.value.people.size)

        c.clearAfterWorkSoon(alex.ids)
        assertEquals(1, c.commitAfterWorkClear(alex.ids))
        assertEquals(listOf("Sam"), c.afterWork.value.people.map { it.personName })
        assertEquals(0, c.commitAfterWorkClear(alex.ids)) // the delayed write after an early one is a no-op
    }
}
