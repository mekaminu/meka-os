package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.ProtectedMailRules
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
import kotlin.test.assertNull

/** Protected time, slice 3: the business account's mail waits through protected time on both devices. */
class ProtectedMailFacadeTest {
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
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvpm${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, subject: String, account: String): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields(account, "Sam Taylor", null, subject, "Hi", now - hour, 1))
        return id
    }

    @Test
    fun businessMailWaitsOverTheWeekendAndComesBackOnMondayMorning() = runTest {
        val personal = email("m1", "Football on Saturday", "Personal")
        val business = email("m2", "Invoice question", "Business")
        val fold = core("android")
        val mac = core("mac")
        fold.syncNow(); mac.syncNow()
        // Protected time off: both show.
        assertEquals(listOf(personal, business), fold.mailNeeds.value?.rows?.map { it.id })
        assertNull(fold.mailNeeds.value?.heldLine)

        fold.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true)
        val card = assertNotNull(fold.mailNeeds.value)
        assertEquals(listOf(personal), card.rows.map { it.id })
        assertEquals("1 email needs you", card.headline)
        assertEquals("1 business email waits till Mon 19 Oct 07:00 · protected time", card.heldLine)
        assertEquals("Held now · 1 business email till Mon 19 Oct 07:00", fold.protectedView.value.mailLine)
        fold.syncNow(); mac.syncNow(); mac.tick()
        assertEquals(listOf(personal), mac.mailNeeds.value?.rows?.map { it.id })

        // Off on the Mac: shows as it comes on both.
        assertEquals(ProtectedMailRules.OFF, mac.setProtectedMail(false))
        assertNull(mac.setProtectedMail(false))
        assertEquals(listOf(personal, business), mac.mailNeeds.value?.rows?.map { it.id })
        mac.syncNow(); fold.syncNow(); fold.tick()
        assertEquals(listOf(personal, business), fold.mailNeeds.value?.rows?.map { it.id })
        assertNull(fold.protectedView.value.mailLine)

        // On again; Monday 07:00 it comes back by itself.
        assertEquals(ProtectedMailRules.ON, fold.setProtectedMail(true))
        assertEquals(listOf(personal), fold.mailNeeds.value?.rows?.map { it.id })
        now += 45 * hour + 30 * 60_000L // Mon 19 Oct, 07:00
        fold.tick()
        assertEquals(setOf(personal, business), fold.mailNeeds.value?.rows?.map { it.id }?.toSet())
        assertNull(fold.mailNeeds.value?.heldLine)
    }

    @Test
    fun notProtectedTodayLiftsItOnBothDevicesAndUndoPutsItBack() = runTest {
        val business = email("m3", "Invoice question", "Business")
        val fold = core("android")
        val mac = core("mac")
        fold.syncNow(); mac.syncNow()
        // Nothing to lift while protected time is off.
        assertNull(fold.setProtectedOpenToday(true))
        fold.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true)
        assertEquals("Not protected today", fold.protectedView.value.openAction)
        assertNull(fold.mailNeeds.value?.rows?.firstOrNull { it.id == business })

        assertEquals(os.meka.core.domain.ProtectedRules.Lifted.DAY.done, fold.setProtectedOpenToday(true))
        assertNull(fold.setProtectedOpenToday(true))
        assertEquals(listOf(business), fold.mailNeeds.value?.rows?.map { it.id })
        assertEquals(true, fold.protectedView.value.openToday)
        fold.syncNow(); mac.syncNow(); mac.tick()
        assertEquals(true, mac.protectedView.value.openToday)
        assertEquals(listOf(business), mac.mailNeeds.value?.rows?.map { it.id })

        // Undo on the Mac: protected again on both.
        assertEquals(os.meka.core.domain.ProtectedRules.Lifted.DAY.again, mac.setProtectedOpenToday(false))
        assertEquals("Not protected today", mac.protectedView.value.openAction)
        mac.syncNow(); fold.syncNow(); fold.tick()
        assertEquals(false, fold.protectedView.value.openToday)
        assertNull(fold.mailNeeds.value?.rows?.firstOrNull { it.id == business })
    }

    @Test
    fun mondayMorningBringsOneNoteForTheWorkMailThatWaited() = runTest {
        email("m4", "Invoice question", "Business") // Sat 08:30, inside the weekend's protected time
        email("m5", "Football on Saturday", "Personal")
        val fold = core("android")
        fold.syncNow()
        fold.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true)
        fun waited(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.PROTECTED_MAIL }
        // Still the weekend: nothing.
        assertEquals(0, waited(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).size)
        now += 45 * hour + 35 * 60_000L // Mon 19 Oct, 07:05
        fold.tick()
        val gov = fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val n = waited(gov).single()
        assertEquals("1 work email waited since Fri 19:00", n.title)
        assertEquals("Sam Taylor · in From your mail", n.text)
        assertEquals(os.meka.core.domain.NoticeTarget.NEEDS_YOU, n.target)
        assertEquals(0, waited(fold.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).size) // once
    }
}
