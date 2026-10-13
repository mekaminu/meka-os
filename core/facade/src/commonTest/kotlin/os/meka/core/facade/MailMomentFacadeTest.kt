package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailMomentRules
import os.meka.core.domain.MailMomentTime
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 7: the ten-minute inbox through the facade on both devices. */
class MailMomentFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long): String {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvmi${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
        return id
    }

    @Test
    fun theSessionGoesThroughEveryEmailAndTheClearDayReachesTheMac() = runTest {
        val a = email("m1", "Sam Taylor", "Football on Saturday", hour)
        val b = email("m2", "Alex Reed", "Invoice question", 2 * hour)
        val c = email("m3", "Jo King", "Book club", 3 * hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = assertNotNull(fold.mailMoment.value)
        assertEquals("3 need you · 2 min each", card.headline)
        assertNull(fold.mailMomentSession.value)

        fold.startMailMoment()
        assertNull(fold.mailMoment.value)
        assertEquals(a, fold.mailMomentSession.value?.row?.id)
        assertEquals("1 of 3", fold.mailMomentSession.value?.position)
        fold.skipMailMoment(a)
        assertEquals(b, fold.mailMomentSession.value?.row?.id)
        fold.doneMailItem(b)
        assertEquals(c, fold.mailMomentSession.value?.row?.id)
        assertEquals("2 of 3", fold.mailMomentSession.value?.position)
        fold.snoozeMailItem(c)
        assertEquals(a, fold.mailMomentSession.value?.row?.id)
        fold.taskFromMailItem(a)
        val end = assertNotNull(fold.mailMomentSession.value)
        assertNull(end.row)
        assertEquals("Inbox clear", end.line)
        assertEquals("Inbox clear today · keep it going", end.streakLine)

        fold.endMailMoment()
        assertNull(fold.mailMomentSession.value)
        assertNull(fold.mailMoment.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        // The clear day is synced: tomorrow both apps say "yesterday".
        now += 24 * hour
        mac.tick()
        email("m4", "Sam Taylor", "Another thing", hour)
        assertTrue(mac.syncNow())
        assertEquals("Inbox clear yesterday · keep it going", mac.mailMoment.value?.streakLine)
    }

    @Test
    fun notTodayHidesTheCardOnBothAppsWithUndo() = runTest {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNotNull(mac.mailMoment.value)
        assertEquals(MailMomentRules.PUT_OFF_LINE, fold.notTodayMailMoment())
        assertNull(fold.mailMoment.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailMoment.value)
        now += 1_000
        fold.undoMailMoment()
        assertNotNull(fold.mailMoment.value)
        // The next day it comes again.
        fold.notTodayMailMoment()
        now += 24 * hour
        fold.tick()
        assertNotNull(fold.mailMoment.value)
    }

    @Test
    fun theTimeIsSyncedAndOffHidesTheCard() = runTest {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailMomentTime.MORNING, mac.mailMomentTime())
        assertEquals("Ten-minute inbox · evenings from 20:00", fold.setMailMomentTime(MailMomentTime.EVENING))
        assertNull(fold.mailMoment.value) // 09:30: not yet
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailMomentTime.EVENING, mac.mailMomentTime())
        assertEquals("evening", mac.mailMomentTimeKey())
        assertEquals("Ten-minute inbox · evenings from 20:00", mac.setMailMomentTimeKey("noon"))
        assertEquals(MailMomentTime.EVENING, mac.mailMomentTime())
        now += 11 * hour // 20:30
        mac.tick()
        assertNotNull(mac.mailMoment.value)
        assertEquals("Ten-minute inbox off", mac.setMailMomentTimeKey("off"))
        assertNull(mac.mailMoment.value)
    }

    @Test
    fun noMailMeansNoClearDayIsNoted() = runTest {
        val fold = core("android")
        fold.tick()
        assertTrue(fold.syncNow())
        assertTrue(serverOps.after("hh", 0, 1_000).none { it.op.entityType == EntityTypes.CONTEXT_MODE && it.op.entityId == MailMomentRules.ENTITY_ID })
    }

    @Test
    fun theHeadsUpComesAtTheMomentButNotIntoProtectedTime() = runTest {
        email("m1", "Sam Taylor", "Football on Saturday", hour)
        email("m2", "Alex Reed", "Invoice question", 2 * hour)
        val fold = core("android")
        assertTrue(fold.syncNow())
        val n = fold.noticesNow().single { it.source == os.meka.core.domain.NoticeSource.MAIL_MOMENT }
        assertEquals("Ten-minute inbox · 2 need you", n.title)
        assertEquals(os.meka.core.domain.NoticeTarget.NEEDS_YOU, n.target)
        // Saturday with weekends protected: the card still waits, the heads-up doesn't call him.
        assertNotNull(fold.setProtectedTime(false, 19 * 60, true))
        assertTrue(fold.noticesNow().none { it.source == os.meka.core.domain.NoticeSource.MAIL_MOMENT })
        assertNotNull(fold.mailMoment.value)
        // Not today drops it too.
        fold.setProtectedTime(false, 19 * 60, false)
        assertEquals(1, fold.noticesNow().count { it.source == os.meka.core.domain.NoticeSource.MAIL_MOMENT })
        fold.notTodayMailMoment()
        assertTrue(fold.noticesNow().none { it.source == os.meka.core.domain.NoticeSource.MAIL_MOMENT })
    }
}
