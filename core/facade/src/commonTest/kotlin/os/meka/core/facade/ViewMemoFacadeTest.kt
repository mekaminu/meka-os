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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Taps feel instant, slice 6, through the facade: the mail cards over every email and Calendar's agenda are kept while
 * what they're built from is unchanged, so a tap on a task no longer rebuilds "From your mail" over every email, and a
 * new email no longer rebuilds the agenda. A new email, a new minute or a new task still shows at once.
 */
class ViewMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmi${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String, agoMs: Long): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1))
        return id
    }

    @Test
    fun aTaskTapKeepsTheMailCardsAndANewEmailOrMinuteRebuildsThem() = runTest(timeout = 5.minutes) {
        for (i in 0 until 300) email("m$i", "Sender $i", "Question $i", hour + i * 60_000L)
        val c = core()
        assertTrue(c.syncNow())
        val card = assertNotNull(c.mailNeeds.value)
        val builds = c.viewBuilds("Mail needs you")
        val rowBuilds = c.viewBuilds("Mail rows")
        assertTrue(builds >= 1)

        // A task (another type) changes nothing the mail cards read.
        c.addTask("Ring the garage")
        assertEquals(builds, c.viewBuilds("Mail needs you"), "a new task doesn't rebuild From your mail")
        assertEquals(rowBuilds, c.viewBuilds("Mail rows"))
        assertTrue(card === c.mailNeeds.value)

        // A new email shows at once.
        val fresh = email("new", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertTrue(c.viewBuilds("Mail needs you") > builds)
        assertTrue(c.mailNeeds.value!!.rows.any { it.id == fresh } || c.mailNeeds.value!!.headline != card.headline)

        // Done on one is a write to the emails: rebuilt at once, in the same minute.
        val afterNew = c.viewBuilds("Mail needs you")
        c.doneMailItem(fresh)
        assertTrue(c.viewBuilds("Mail needs you") > afterNew)
        assertTrue(c.mailNeeds.value!!.rows.none { it.id == fresh })

        // A new minute rebuilds it ("08:30", a snooze ending).
        val beforeMinute = c.viewBuilds("Mail needs you")
        now += 60_000
        c.addTask("Book the MOT")
        assertEquals(beforeMinute + 1, c.viewBuilds("Mail needs you"))
    }

    @Test
    fun theAgendaFollowsTasksAndTheMinuteButNotTheMail() = runTest(timeout = 5.minutes) {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val c = core()
        assertTrue(c.syncNow())
        val agenda = c.viewBuilds("Calendar agenda")
        assertTrue(agenda >= 1)

        // A task is on the agenda: rebuilt.
        c.addTask("Ring the garage")
        assertTrue(c.viewBuilds("Calendar agenda") > agenda)

        // An email isn't: kept.
        val afterTask = c.viewBuilds("Calendar agenda")
        email("m2", "Alex Reed", "Invoice question", 0)
        assertTrue(c.syncNow())
        assertEquals(afterTask, c.viewBuilds("Calendar agenda"), "a new email doesn't rebuild the agenda")

        // The minute is.
        now += 60_000
        c.addTask("Book the MOT")
        assertTrue(c.viewBuilds("Calendar agenda") > afterTask)
    }
}
