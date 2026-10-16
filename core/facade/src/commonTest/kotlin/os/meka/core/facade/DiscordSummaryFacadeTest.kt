package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.DiscordCaptured
import os.meka.core.domain.DiscordChannel
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.TriageSettings
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.ChatSummaryCodec
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Discord channel watch, slice 3, through the facade: one batched call with the posts' words, checked, synced to the Mac. */
class DiscordSummaryFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<ChatSummaryCodec.Request>()
        var reply = ChatSummaryCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun chatSummary(request: ChatSummaryCodec.Request): ChatSummaryCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val ideas = DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas")

    @Test
    fun aBusyChannelIsSummarisedOnceAndTheMacShowsTheLine() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now)))
        fold.setDiscordWatched(ideas.key, true)
        now += 60_000
        fold.noteDiscord(listOf(
            DiscordCaptured(ideas, "Admin", "BTC long idea above 68k, stop 66.5k", now),
            DiscordCaptured(ideas, "Admin", "Sent an image", now + 1_000),
        ))
        // Two posts: not worth a call.
        assertIs<ChatSummaryRead.NotDue>(fold.summariseDiscord())
        now += 60_000
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Sam", "In at 67.9k", now)))

        // Offline: nothing written, tried again next time.
        server.down = true
        assertIs<ChatSummaryRead.Unavailable>(fold.summariseDiscord())
        server.down = false

        server.reply = ChatSummaryCodec.Response("answered", listOf(ChatSummaryCodec.ChatAnswer("#trade-ideas · Example Traders", "Admin's BTC long above 68k, stop 66.5k, with a chart; Sam is in")))
        val read = assertIs<ChatSummaryRead.Read>(fold.summariseDiscord())
        assertEquals(1, read.chats.size)
        val sent = server.sent.last().chats.single()
        assertEquals("#trade-ideas · Example Traders", sent.name)
        assertEquals(listOf("Admin: BTC long idea above 68k, stop 66.5k", "Admin: sent an image", "Sam: In at 67.9k"), sent.lines.map { "${it.from}: ${it.text}" })
        assertEquals("Admin's BTC long above 68k, stop 66.5k, with a chart; Sam is in", fold.discord.value!!.cards.single().summary)

        // Up to date: nothing more goes.
        val calls = server.sent.size
        assertIs<ChatSummaryRead.NotDue>(fold.summariseDiscord())
        assertEquals(calls, server.sent.size)

        fold.syncNow(); mac.syncNow()
        assertEquals("Admin's BTC long above 68k, stop 66.5k, with a chart; Sam is in", mac.discord.value!!.cards.single().summary)

        // Done clears the card; the summary doesn't come back with a new post until there are three again.
        mac.discordDone(ideas.key)
        mac.syncNow(); fold.syncNow()
        now += 60_000
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "Moving stop to entry", now)))
        assertNull(fold.discord.value!!.cards.single().summary)
    }

    @Test
    fun aChannelKeptFromTheAiIsNeverSent() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first", now)))
        fold.setDiscordWatched(ideas.key, true)
        repeat(3) { i -> now += 60_000; fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "post $i", now))) }
        assertIs<ChatSummaryRead.NotDue>(fold.summariseDiscord(TriageSettings(neverToAi = setOf("Example Traders"))))
        assertEquals(0, server.sent.size)
    }
}
