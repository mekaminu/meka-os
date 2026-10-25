package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.FieldValue
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
 * Taps feel instant, slice 7, through the facade: the Week board (eight weeks of days) and the week ahead are kept
 * while what they're built from is unchanged. A new email no longer rebuilds the board, a new task no longer rebuilds
 * the week ahead; a task on the board, an email the week ahead shows, or a new minute still shows at once.
 */
class BoardMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(5), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvbm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    @Test
    fun theBoardFollowsTasksAndTheMinuteButNotTheMail() = runTest(timeout = 5.minutes) {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val c = core()
        assertTrue(c.syncNow())
        val board = c.weekBoard.value
        val builds = c.viewBuilds("Week board")
        assertTrue(builds >= 1)

        // An email isn't on the board: kept, the very same board.
        email("m2", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(builds, c.viewBuilds("Week board"), "a new email doesn't rebuild the Week board")
        assertTrue(board === c.weekBoard.value)

        // A task is: rebuilt at once, in the same minute.
        c.addTask("Ring the garage")
        assertTrue(c.viewBuilds("Week board") > builds)
        assertTrue(c.weekBoard.value !== board)

        // The minute is (free time and capacity move on).
        val afterTask = c.viewBuilds("Week board")
        now += 60_000
        email("m3", "Jo Park", "Lunch?", 0)
        assertTrue(c.syncNow())
        assertTrue(c.viewBuilds("Week board") > afterTask)
    }

    @Test
    fun theWeekAheadIsKeptByATaskAndRebuiltByTheMinute() = runTest(timeout = 5.minutes) {
        val c = core()
        assertTrue(c.syncNow())
        c.addTask("Ring the garage")
        val builds = c.viewBuilds("Week ahead")
        assertTrue(builds >= 1)

        // A plain task isn't in the week ahead: kept.
        c.addTask("Water the plants")
        assertEquals(builds, c.viewBuilds("Week ahead"), "a new task doesn't rebuild the week ahead")

        // A new minute is.
        now += 60_000
        c.addTask("Fix the shelf")
        assertEquals(builds + 1, c.viewBuilds("Week ahead"))
    }
}
