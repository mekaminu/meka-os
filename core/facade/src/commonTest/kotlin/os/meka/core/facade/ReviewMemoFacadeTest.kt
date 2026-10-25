package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Taps feel instant, slice 8, through the facade: Review, Balance and the reflection are kept while the minute and
 * every record type they read are unchanged. A new email no longer rebuilds them; a task (Review, the reflection),
 * a habit or a new minute still shows at once.
 */
class ReviewMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(8), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvrm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    private val parts = listOf("Review", "Balance", "Reflection")
    private fun builds(c: MekaCore) = parts.associateWith { c.viewBuilds(it) }

    @Test
    fun aNewEmailKeepsReviewAndBalanceAndATaskOrHabitRebuildsThem() = runTest(timeout = 5.minutes) {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val c = core()
        assertTrue(c.syncNow())
        val review = c.reviewView.value
        val first = builds(c)
        parts.forEach { assertTrue(first.getValue(it) >= 1, "$it built once") }

        // An email isn't in Review, Balance or the reflection: all kept, the very same review.
        email("m2", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(first, builds(c), "a new email doesn't rebuild Review, Balance or the reflection")
        assertTrue(review === c.reviewView.value)

        // A task is in Review and the reflection: rebuilt at once, in the same minute.
        c.addTask("Ring the garage")
        val afterTask = builds(c)
        listOf("Review", "Reflection").forEach { assertTrue(afterTask.getValue(it) > first.getValue(it), "a task rebuilds $it") }

        // A habit is read by Review itself (not handed in): rebuilt too.
        c.addHabit("Stretch", 3, os.meka.core.domain.HabitTiming.ANYTIME, 10, null)
        assertTrue(c.viewBuilds("Review") > afterTask.getValue("Review"), "a habit rebuilds Review")
        assertTrue(c.reviewView.value.habits.any { it.title == "Stretch" })

        // A new minute is.
        val beforeMinute = builds(c)
        now += 60_000
        email("m3", "Jo Park", "Lunch?", 0)
        assertTrue(c.syncNow())
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeMinute.getValue(it), "the minute rebuilds $it") }
    }
}
