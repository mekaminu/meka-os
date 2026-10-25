package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.GoalHorizon
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
 * Taps feel instant, slice 10, through the facade: Lists, Goals and Done for the day are kept while the tasks, the
 * minute and every record type they read are unchanged, and the notices' count of emails waiting is kept over the
 * emails. A new email leaves Lists, Goals and the shutdown kept; a task tick leaves the email counts kept; a shopping
 * item, a goal, a task or a new minute still shows at once.
 */
class ListsMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(10), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvlm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    private val parts = listOf("Lists", "Goals", "Shutdown")
    private fun builds(c: MekaCore) = parts.associateWith { c.viewBuilds(it) }

    @Test
    fun anEmailKeepsListsGoalsAndTheShutdownAndTheirOwnChangesRebuildThem() = runTest(timeout = 5.minutes) {
        for (i in 0 until 50) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        c.addTask("Ring the garage")
        val lists = c.listsView.value
        val goals = c.goalsView.value
        val shutdown = c.shutdownView.value
        val first = builds(c)
        parts.forEach { assertTrue(first.getValue(it) >= 1, "$it built") }

        // A new email is another type: all three kept, the very same views.
        email("new", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(first, builds(c), "a new email doesn't rebuild Lists, Goals or the shutdown")
        assertTrue(lists === c.listsView.value)
        assertTrue(goals === c.goalsView.value)
        assertTrue(shutdown === c.shutdownView.value)

        // A shopping item is on Lists: rebuilt at once, in the same minute.
        val beforeShop = c.viewBuilds("Lists")
        c.addShopping("Milk")
        assertTrue(c.viewBuilds("Lists") > beforeShop, "a shopping item rebuilds Lists")
        assertTrue(c.listsView.value.shopping.toBuy.any { it.title.contains("Milk", ignoreCase = true) })

        // A goal is on Goals.
        val beforeGoal = c.viewBuilds("Goals")
        c.addGoal("Run a 10k", null, GoalHorizon.values().first())
        assertTrue(c.viewBuilds("Goals") > beforeGoal, "a goal rebuilds Goals")
        assertTrue(c.goalsView.value.goals.any { it.title == "Run a 10k" })

        // A task is in all three.
        val beforeTask = builds(c)
        c.addTask("Book the MOT")
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeTask.getValue(it), "a task rebuilds $it") }

        // A new minute is too.
        val beforeMinute = builds(c)
        now += 60_000
        c.addShopping("Bread")
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeMinute.getValue(it), "the minute rebuilds $it") }
    }

    @Test
    fun aTaskKeepsTheEmailCountsAndANewEmailRecountsThem() = runTest(timeout = 5.minutes) {
        for (i in 0 until 50) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        val waiting = c.viewBuilds("Mail waiting")
        val held = c.viewBuilds("Mail held")
        assertTrue(waiting >= 1)
        assertTrue(held >= 1)

        c.addTask("Ring the garage")
        assertEquals(waiting, c.viewBuilds("Mail waiting"), "a task doesn't recount the emails waiting")
        assertEquals(held, c.viewBuilds("Mail held"), "a task doesn't recount the held emails")

        email("new", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertTrue(c.viewBuilds("Mail waiting") > waiting, "a new email recounts them")
    }
}
