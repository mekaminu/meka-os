package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskRules
import os.meka.core.domain.MoneyChatObi
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MoneyChatCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Ask Obi (slice 2) through the facade: what the rules can't answer goes to Obi with the facts only, checked on return. */
class MoneyChatObiFacadeTest {
    private val now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private class Server(ops: InMemoryServerOpStore) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(SyncService(ops))
        val sent = mutableListOf<MoneyChatCodec.Request>()
        var reply = MoneyChatCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun moneyChat(request: MoneyChatCodec.Request): MoneyChatCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private fun core(server: Server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val statement = "Date,Description,Amount\n" +
        "10/09/2026,TESCO STORES 2041,-60.00\n" +
        "02/10/2026,TESCO STORES 2041,-40.00\n" +
        "03/10/2026,COSTA COFFEE,-3.20\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

    @Test
    fun obiAnswersWhatTheRulesCantWithTheFactsOnly() = runTest(timeout = 5.minutes) {
        val server = Server(ops)
        val fold = core(server)
        // Locked: nothing goes out.
        assertEquals(MoneyRules.LOCKED, fold.moneyChat("Is Tesco becoming a habit?").text)
        assertTrue(server.sent.isEmpty())

        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        // The rules answer what they can; nothing is sent.
        val rules = fold.moneyChat("What if I save £300 a month for 6 months?")
        assertFalse(rules.byObi)
        assertTrue(server.sent.isEmpty())

        server.reply = MoneyChatCodec.Response("answered", "October so far is £43.20 out, mostly Tesco at £40.")
        val t = fold.moneyChat("Is Tesco becoming a habit?")
        assertTrue(t.byObi, t.text)
        assertEquals("October so far is £43.20 out, mostly Tesco at £40.", t.text)
        val sent = server.sent.single()
        assertEquals("Is Tesco becoming a habit?", sent.question)
        assertEquals("2026-10-16", sent.date)
        assertTrue(sent.facts.any { it.startsWith("October 2026 so far: spent £43.20") }, sent.facts.toString())
        // Nothing of the accounts: no account name, no balance.
        assertFalse("Current account" in MoneyChatCodec.encodeRequest(sent))
        // The earlier turn goes with a follow-up.
        assertEquals(listOf("What if I save £300 a month for 6 months?", "Is Tesco becoming a habit?"),
            run { fold.moneyChat("And coffee?"); server.sent.last().history.map { it.question } })

        // A figure Obi made up is never shown.
        server.reply = MoneyChatCodec.Response("answered", "You'll spend £900 on Tesco this year.")
        val made = fold.moneyChat("What about the year?")
        assertEquals(MoneyChatObi.UNCHECKED, made.text)
        assertFalse(made.byObi)
        // Offline and the AI off say so.
        server.down = true
        assertEquals(AskRules.OFFLINE_LINE, fold.moneyChat("Why so much on groceries?").text)
        server.down = false
        server.reply = MoneyChatCodec.Response("off", reason = "Navilife's AI is off")
        assertTrue(fold.moneyChat("Why so much on groceries?").refused)
        // Closing Money forgets the chat: the next question goes alone.
        fold.closeMoney(); fold.openMoney()
        server.reply = MoneyChatCodec.Response("answered", "Tesco was £40 in October.")
        fold.moneyChat("Is Tesco becoming a habit?")
        assertTrue(server.sent.last().history.isEmpty())
    }
}
