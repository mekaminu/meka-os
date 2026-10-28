package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.MoneyCheckWords
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MoneyCheckCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Obi's wording of the money check through the facade: only the ideas go out, checked words come back on both devices. */
class MoneyCheckWordsFacadeTest {
    private val now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private class Server(ops: InMemoryServerOpStore) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(SyncService(ops))
        val sent = mutableListOf<MoneyCheckCodec.Request>()
        var reply = MoneyCheckCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun moneyCheck(request: MoneyCheckCodec.Request): MoneyCheckCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private fun core(device: String, server: Server) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val coffees = "Date,Description,Amount\n10/09/2026,TESCO STORES 2041,-60.00\n" +
        (1..10).joinToString("") { d -> "${d.toString().padStart(2, '0')}/10/2026,COSTA COFFEE,-4.00\n" }

    @Test
    fun obisWordsShowOnBothDevicesAndMadeUpNumbersDont() = runTest(timeout = 5.minutes) {
        val foldServer = Server(ops)
        val fold = core("android", foldServer)
        val mac = core("mac", Server(ops))
        // Locked: nothing goes out.
        assertEquals(MoneyCheckWords.NO_IDEAS, fold.moneyCheckWords()?.line)
        assertTrue(foldServer.sent.isEmpty())
        foldServer.reply = MoneyCheckCodec.Response("answered", listOf(
            MoneyCheckCodec.Worded("habit-coffee", "Coffee most days", "10 coffees so far in October; halving it saves ~£240/yr."),
        ))
        fold.openMoney()
        fold.importMoneyStatement("Current account", coffees)
        // The weekly run starts by itself once the ideas show (never run before).
        fold.awaitMoneyWordsForTest()
        assertTrue(foldServer.sent.isNotEmpty())
        val idea = fold.moneyView.value.check!!.ideas.first { it.id == "habit-coffee" }
        val r = fold.moneyCheckWords()
        assertEquals(MoneyCheckWords.WROTE, r?.line)
        val sent = foldServer.sent.last()
        assertEquals(idea.ruleTitle, sent.ideas.first { it.id == "habit-coffee" }.title)
        // Nothing of the accounts goes: no account name in what was sent.
        assertFalse("Current account" in MoneyCheckCodec.encodeRequest(sent))
        val shown = fold.moneyView.value.check!!
        assertEquals("Coffee most days", shown.ideas.first { it.id == "habit-coffee" }.title)
        assertEquals("Written by Obi · Fri 16 Oct", shown.by)
        // The Mac shows the same words once synced.
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("Coffee most days", mac.moneyView.value.check!!.ideas.first { it.id == "habit-coffee" }.title)
        // A number Obi made up is never shown: the rules' words stay.
        foldServer.reply = MoneyCheckCodec.Response("answered", listOf(MoneyCheckCodec.Worded("habit-coffee", "Coffee", "You spend £90 a month on coffee.")))
        assertEquals(MoneyCheckWords.NOTHING, fold.moneyCheckWords()?.line)
        assertEquals(idea.ruleTitle, fold.moneyView.value.check!!.ideas.first { it.id == "habit-coffee" }.title)
        // Offline: says so and keeps the rules' words.
        foldServer.down = true
        val off = fold.moneyCheckWords()
        assertTrue(off!!.refused)
        // Not due again this week: the weekly run asks nothing.
        foldServer.down = false
        val before = foldServer.sent.size
        assertEquals(null, fold.moneyCheckWords(force = false))
        assertEquals(before, foldServer.sent.size)
    }
}
