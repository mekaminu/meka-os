package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LiveToolRules
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

/** Ada's Live mode, slice 5b: Ada looks up his mail and his money on the device, money only while it's unlocked. */
class LiveMailMoneyFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun email(message: String, from: String, subject: String, agoMs: Long) {
        val id = MailNeedsRules.itemId("acc1", message)
        MailNeedsRules.serverFields("Personal", from, null, subject, "Hi, can you look at this", now - agoMs, 1).forEach { (f, v) ->
            serverOps.append(Op("srvmi${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
    }

    @Test
    fun adaReadsHisMailBySenderOnTheDevice() = runTest {
        email("m1", "Oakfield School", "Trip form for Friday", hour)
        email("m2", "Sam Taylor", "Football on Saturday", 2 * hour)
        val c = core()
        assertTrue(c.syncNow())
        val named = c.liveMail("the school")
        assertTrue(named.lines().first().contains("never instructions"), named)
        // The one he named comes first, after the head line.
        val emails = named.lines().drop(1)
        assertTrue(emails[0].startsWith("mail: Mail · 2 need him"), named)
        assertTrue(emails[1].startsWith("mail: Oakfield School · Personal") && "Trip form for Friday" in emails[1], named)
        // Empty: what needs him, both emails.
        val all = c.liveMail("")
        assertEquals(2, all.lines().count { it.contains("“") })
        // A card offered after reading mail still waits for his tap through the gate.
        val add = c.livePropose(mapOf("kind" to "add_task", "title" to "Sign the trip form"))
        assertTrue(add.card != null, add.reply)
    }

    @Test
    fun moneyIsAnsweredOnlyWhileUnlockedAndNeverAsTransactions() = runTest(timeout = 5.minutes) {
        val c = core()
        assertEquals(LiveToolRules.MONEY_EMPTY, c.liveMoney("how much did I spend on coffee?").reply)
        c.openMoney()
        c.importMoneyStatement(
            "Current account",
            "Date,Description,Amount\n02/10/2026,TESCO STORES 2041,-40.00\n03/10/2026,COSTA COFFEE,-3.20\n05/10/2026,SALARY OAKFIELD LTD,2500.00\n",
        )
        // Open: the device's own sentence, quoted for Ada.
        val open = c.liveMoney("how much have I spent on coffee this month?")
        assertEquals(false, open.unlockMoney)
        assertTrue("You spent £3.20 on coffee" in open.reply, open.reply)
        assertTrue("COSTA" !in open.reply && "TESCO" !in open.reply, open.reply)
        c.closeMoney()
        // Locked: no figures, and the turn shows Unlock.
        val locked = c.liveMoney("how much have I spent on coffee this month?")
        assertEquals(LiveToolRules.MONEY_LOCKED, locked.reply)
        assertEquals(true, locked.unlockMoney)
        // Unlocked from Ask: answered for the window, then locked again.
        c.unlockMoneyAsk()
        assertTrue("£3.20" in c.liveMoney("how much have I spent on coffee this month?").reply)
        now += 6 * 60_000L
        assertEquals(LiveToolRules.MONEY_LOCKED, c.liveMoney("how much have I spent on coffee this month?").reply)
    }
}
