package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailSenderFields
import os.meka.core.domain.MailUnopenedRules
import os.meka.core.domain.MailUnsubscribeRules
import os.meka.core.domain.UnsubscribeMethod
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
import kotlin.test.assertTrue

/** Inbox autopilot, slice 5c: the weekly "Not opened in 30 days" offer through the facade, on both devices. */
class MailUnopenedFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val day = 86_400_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvun${k++}", "hh", EntityTypes.MAIL_SENDER, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun sender(address: String, name: String, unopened: Int, method: UnsubscribeMethod = UnsubscribeMethod.ONE_CLICK): String {
        val id = MailUnsubscribeRules.senderId("acc1", address)
        serverWrite(id, MailUnsubscribeRules.senderFields("Personal", name, address.substringAfter('@'), method, now - day, unopened + 1) +
            MailUnopenedRules.serverFields(null, unopened, 0, now - 3_600_000)!!)
        return id
    }

    @Test
    fun unsubscribeFromAllAsksForEachAndFoldsTheOfferAwayOnBothDevicesWithUndo() = runTest {
        val digest = sender("news@digest.example", "Weekly Digest", 6)
        val club = sender("hello@club.example", "Club News", 4, UnsubscribeMethod.MAILTO)
        sender("info@page.example", "Page News", 9, UnsubscribeMethod.LINK) // a page only: not offered
        sender("hi@few.example", "Few", 2) // too few
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = assertNotNull(fold.mailUnopened.value)
        assertEquals("Unsubscribe from 2?", card.headline)
        assertEquals(listOf(digest, club), card.ids)
        assertNotNull(mac.mailUnopened.value)

        assertEquals("Unsubscribing from 2 senders at the next check", fold.unsubscribeUnopened())
        assertNull(fold.mailUnopened.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailUnopened.value)
        // The server sees both asks.
        val asked = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.field == MailSenderFields.ASKED_AT && it.value != FieldValue.Int64(0) }.map { it.entityId }.toSet()
        assertEquals(setOf(digest, club), asked)

        // Undo before the server's check: the asks are taken back and the offer returns.
        fold.undoUnopened()
        val back = assertNotNull(fold.mailUnopened.value)
        assertEquals(listOf(digest, club), back.ids)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNotNull(mac.mailUnopened.value)
        // A second Undo does nothing.
        fold.undoUnopened()
        assertNotNull(fold.mailUnopened.value)
    }

    @Test
    fun keepAndNotNow() = runTest {
        val digest = sender("news@digest.example", "Weekly Digest", 6)
        val club = sender("hello@club.example", "Club News", 4)
        val mac = core("mac")
        assertTrue(mac.syncNow())
        assertEquals("Keeping Weekly Digest · not offered again", mac.keepMailSender(digest))
        assertEquals(listOf(club), mac.mailUnopened.value?.ids)
        mac.undoUnopened()
        assertEquals(listOf(digest, club), mac.mailUnopened.value?.ids)
        assertEquals(MailUnopenedRules.NOT_NOW_LINE, mac.notNowUnopened())
        assertNull(mac.mailUnopened.value)
        // Next Saturday it's back.
        now += 7 * day
        serverWrite(digest, MailUnopenedRules.serverFields(null, 7, 0, now)!!)
        serverWrite(club, MailUnopenedRules.serverFields(null, 5, 0, now)!!)
        assertTrue(mac.syncNow())
        assertEquals(listOf(digest, club), mac.mailUnopened.value?.ids)
        assertEquals(MailUnopenedRules.NONE_LEFT, core("android").unsubscribeUnopened())
        assertEquals(os.meka.core.domain.MailSenderPanelRules.UNKNOWN, mac.keepMailSender("nothing"))
    }
}
