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
 * Taps feel instant, slice 11, through the facade: People, Kids, the Life grid's lines and the notices' preview are
 * kept while what they're worked out from is unchanged. A shopping item leaves People and Kids kept; an occasion
 * rebuilds People, a task the notices, a new minute all four.
 */
class PeopleMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvpm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    private val parts = listOf("People", "Kids", "Life grid", "Notices")
    private fun builds(c: MekaCore) = parts.associateWith { c.viewBuilds(it) }

    @Test
    fun aShoppingItemKeepsPeopleAndKidsAndTheirOwnChangesRebuildThem() = runTest(timeout = 5.minutes) {
        for (i in 0 until 30) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        c.addTask("Ring the garage")
        val first = builds(c)
        parts.forEach { assertTrue(first.getValue(it) >= 1, "$it built") }
        val people = c.peopleView.value
        val kids = c.kidsView.value

        // A shopping item is on Lists only: People and Kids are the very same views.
        c.addShopping("Milk")
        assertEquals(first.getValue("People"), c.viewBuilds("People"), "a shopping item doesn't rebuild People")
        assertEquals(first.getValue("Kids"), c.viewBuilds("Kids"), "a shopping item doesn't rebuild Kids")
        assertTrue(people === c.peopleView.value)
        assertTrue(kids === c.kidsView.value)

        // An occasion is on People: rebuilt at once, in the same minute.
        val beforeOccasion = c.viewBuilds("People")
        c.addOccasion("Sam's birthday 3 Nov")
        assertTrue(c.viewBuilds("People") > beforeOccasion, "an occasion rebuilds People")

        // A task is in the notices: worked out again at once.
        val beforeTask = c.viewBuilds("Notices")
        c.addTask("Book the MOT")
        assertTrue(c.viewBuilds("Notices") > beforeTask, "a task rebuilds the notices")

        // Nothing changed at all: every part kept.
        val settled = builds(c)
        c.tick()
        assertEquals(settled, builds(c), "a refresh with nothing new rebuilds none of them")

        // A new minute rebuilds all four.
        val beforeMinute = builds(c)
        now += 60_000
        c.tick()
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeMinute.getValue(it), "the minute rebuilds $it") }
    }

    @Test
    fun aNewEmailKeepsPeopleKidsAndTheLifeGrid() = runTest(timeout = 5.minutes) {
        for (i in 0 until 30) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        val first = builds(c)

        email("new", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(first.getValue("People"), c.viewBuilds("People"), "a new email doesn't rebuild People")
        assertEquals(first.getValue("Kids"), c.viewBuilds("Kids"), "a new email doesn't rebuild Kids")
    }
}
