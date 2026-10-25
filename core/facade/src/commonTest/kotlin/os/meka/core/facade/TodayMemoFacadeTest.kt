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
 * Taps feel instant, slice 9, through the facade: Today and the morning brief are kept while the minute, what's handed
 * in and every record type they read are unchanged. The lines they draw from the emails are worked out first and handed
 * in, so an email that changes none of them (one already archived) leaves both kept; an email that needs him, a task or
 * a new minute still shows at once.
 */
class TodayMemoFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(9), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long, gone: Boolean = false) {
        val id = MailNeedsRules.itemId("acc1", message)
        val fields: Map<String, FieldValue> = MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1) +
            (if (gone) MailNeedsRules.goneFields(now) else emptyMap())
        fields.forEach { (f, v) ->
            serverOps.append(Op("srvtm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    private val parts = listOf("Today", "Brief")
    private fun builds(c: MekaCore) = parts.associateWith { c.viewBuilds(it) }

    @Test
    fun anArchivedEmailKeepsTodayAndTheBriefAndATaskOrAMinuteRebuildsThem() = runTest(timeout = 5.minutes) {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val c = core()
        assertTrue(c.syncNow())
        val today = c.today.value
        val brief = c.briefView.value
        val first = builds(c)
        parts.forEach { assertTrue(first.getValue(it) >= 1, "$it built") }

        // An email already archived changes no line on Today or in the brief: both kept, the very same views.
        email("m2", "Alex Reed", "Your weekly newsletter", 0, gone = true)
        assertTrue(c.syncNow())
        assertEquals(first, builds(c), "an archived email doesn't rebuild Today or the brief")
        assertTrue(today === c.today.value)
        assertTrue(brief === c.briefView.value)

        // An email that needs him changes the brief's mail line: rebuilt at once.
        email("m3", "Jo Park", "Can you sign the form?", 0)
        assertTrue(c.syncNow())
        assertTrue(c.viewBuilds("Brief") > first.getValue("Brief"), "an email that needs him rebuilds the brief")
        assertTrue((c.briefView.value.story.mail?.count ?: 0) > (brief.story.mail?.count ?: 0), "the brief counts the new email")

        // A task is on Today and in the brief: both rebuilt at once, in the same minute.
        val beforeTask = builds(c)
        c.addTask("Ring the garage")
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeTask.getValue(it), "a task rebuilds $it") }

        // A new minute is.
        val beforeMinute = builds(c)
        now += 60_000
        email("m4", "Pat Lee", "Receipt", 0, gone = true)
        assertTrue(c.syncNow())
        parts.forEach { assertTrue(c.viewBuilds(it) > beforeMinute.getValue(it), "the minute rebuilds $it") }
    }
}
