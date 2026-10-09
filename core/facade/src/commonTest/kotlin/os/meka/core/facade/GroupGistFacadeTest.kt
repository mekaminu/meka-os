package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.TriageLane
import os.meka.core.domain.TriageSettings
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.GroupDigestCodec
import os.meka.core.wire.MessageRequestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The group digest's gist through the facade (V1, messages slice 4b): one batched call per slot, checked, synced without the messages. */
class GroupGistFacadeTest {
    private var now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London: the lunchtime digest is due

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<GroupDigestCodec.Request>()
        var reply = GroupDigestCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun groupDigest(request: GroupDigestCodec.Request): GroupDigestCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val service = SyncService(InMemoryServerOpStore())
    private val server = Server(service)
    private fun core(device: String = "android", transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun msg(who: String, text: String, group: String, minsAgo: Int) =
        CapturedItem("wa-$group-$who-$minsAgo", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, now - minsAgo * 60_000L)

    private val lads = (1..6).map { i -> msg(listOf("Tunde", "Femi", "Obi")[i % 3], "Getafe line $i", "Barça lads", 120 - i) }
    private val family = listOf(msg("Mum", "pizza Saturday?", "Family", 90))
    private val items = lads + family

    @Test
    fun theBusyGroupsGoOutInOneCallAndEveryGroupGetsASyncedCard() = runTest {
        val fold = core()
        server.reply = GroupDigestCodec.Response(
            "answered",
            listOf(
                GroupDigestCodec.GroupAnswer(
                    "Barça lads", "Lineup debate for Getafe",
                    listOf(
                        GroupDigestCodec.Ask("needs_reply", "Tunde", "Asks if you want a ticket"),
                        GroupDigestCodec.Ask("action", "Femi", "Asks you to bring the ball", listOf(MessageRequestCodec.Proposal("task", "Bring the ball", words = "tomorrow"))),
                    ),
                ),
            ),
        )
        val read = assertIs<GistRead.Read>(fold.gistGroupDigest(items, TriageSettings(), emptyMap()))
        val sent = server.sent.single()
        // Family has one message: its card goes without a call.
        assertEquals(listOf("Barça lads"), sent.groups.map { it.name })
        assertEquals(6, sent.groups[0].lines.size)
        assertEquals("2026-10-09", sent.date)
        assertEquals(setOf("barça lads", "family"), read.groups.toSet())
        assertEquals(listOf("Lineup debate for Getafe", null), fold.groupGists.value.map { it.gist })
        // The asks are Needs you cards: a reply to write himself (no draft) and a task to add.
        val reply = read.replies.single()
        assertEquals(TriageLane.NEEDS_REPLY, reply.lane)
        assertNull(reply.draft)
        assertTrue(reply.from.startsWith("Tunde in Barça lads"), reply.from)
        assertEquals("Bring the ball", read.requests.single().proposal.title)

        // Called again in the same slot: nothing more is sent.
        assertIs<GistRead.NotDue>(fold.gistGroupDigest(items, TriageSettings(), emptyMap()))
        assertEquals(1, server.sent.size)

        // The Mac sees the cards (never the lines) and Caught up there reaches the Fold's digest.
        fold.syncNow()
        val mac = core("mac")
        mac.syncNow()
        assertEquals(listOf("Barça lads", "Family"), mac.groupGists.value.map { it.title })
        val undo = mac.catchUpGroupDigest(listOf("barça lads"))
        assertEquals(listOf("Family"), mac.groupGists.value.map { it.title })
        mac.syncNow(); fold.syncNow()
        assertEquals(setOf("barça lads"), fold.groupDigestCaughtUp.value.keys)
        mac.undoCatchUpGroupDigest(undo)
        assertEquals(listOf("Barça lads", "Family"), mac.groupGists.value.map { it.title })
    }

    @Test
    fun offlineWritesNothingAndAiOffStillSendsTheCardsWithoutGists() = runTest {
        val fold = core()
        server.down = true
        assertIs<GistRead.Unavailable>(fold.gistGroupDigest(items, TriageSettings(), emptyMap()))
        assertTrue(fold.groupGists.value.isEmpty())
        server.down = false
        server.reply = GroupDigestCodec.Response("off", reason = "MEKA's AI is off")
        assertIs<GistRead.Read>(fold.gistGroupDigest(items, TriageSettings(), emptyMap()))
        assertEquals(listOf<String?>(null, null), fold.groupGists.value.map { it.gist })
    }

    @Test
    fun beforeLunchtimeOrOnceCaughtUpNothingIsDue() = runTest {
        val fold = core()
        now -= 3 * 60 * 60_000L // 11:03
        assertIs<GistRead.NotDue>(fold.gistGroupDigest(items.map { it.copy(atMs = it.atMs - 3 * 60 * 60_000L) }, TriageSettings(), emptyMap()))
        now += 3 * 60 * 60_000L
        val caughtUp = mapOf("barça lads" to now - 60_000L, "family" to now - 60_000L)
        assertIs<GistRead.NotDue>(fold.gistGroupDigest(items, TriageSettings(), caughtUp))
        assertTrue(server.sent.isEmpty())
    }
}
