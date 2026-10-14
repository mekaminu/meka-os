package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.RequestKind
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MessageRequestCodec
import os.meka.core.wire.VoiceNoteCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A voice note's tidy-up through the facade (Voice notes into actions, slice 2): words out, a tidy note and cards back. */
class VoiceNoteFacadeTest {
    private val now = 1_791_668_400_000L // Sat 10 Oct 2026, 22:40 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<VoiceNoteCodec.Request>()
        var reply = VoiceNoteCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun voiceNote(request: VoiceNoteCodec.Request): VoiceNoteCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val words = "Um so ring the garage about the service tomorrow and uh maybe a family film night on Fridays with Sam."

    @Test
    fun theWordsGoOutAloneAndATidyNoteAndCheckedCardsComeBack() = runTest {
        val c = core()
        server.reply = VoiceNoteCodec.Response(
            "answered", "Ring the garage about the service tomorrow.\nMaybe a family film night on Fridays with Sam.",
            listOf("Sam", "07700 900123", "sam"),
            listOf(
                MessageRequestCodec.Proposal("task", "Ring the garage about the service", words = "tomorrow"),
                MessageRequestCodec.Proposal("idea", "Family film night on Fridays"),
                MessageRequestCodec.Proposal("reply", "Reply to Sam", body = "Yes"),
            ),
        )
        val read = c.tidyVoiceNote(words, "Voice note · 22:40")
        val sent = server.sent.single()
        assertEquals(words, sent.text)
        assertEquals("Voice note · 22:40", sent.subject)
        assertEquals("2026-10-10", sent.date)
        assertTrue(read.tidy.startsWith("Ring the garage"))
        // A number is never kept as a person; repeats go.
        assertEquals(listOf("Sam"), read.people)
        val out = assertIs<HandleOutcome.Cards>(read.cards)
        assertEquals("Tidied · 2 things to add", out.line)
        // No reply from a note to himself; the idea has no Change and says where it goes.
        assertEquals(listOf(RequestKind.TASK, RequestKind.IDEA), out.cards.map { it.proposal.kind })
        val idea = out.cards.last()
        assertEquals("Add to Ideas: Family film night on Fridays", idea.action)
        assertNull(idea.changeLabel)
        assertTrue(out.cards.all { it.from == "You shared · 22:40" })

        // Add on the idea: it lands in Someday → Ideas; Undo takes it out.
        val done = assertNotNull(c.acceptRequest(idea.id))
        assertEquals("Kept “Family film night on Fridays” in Someday · Ideas", done.line)
        val ideas = c.listsView.value.someday.first { it.kind == SomedayKind.IDEA }.items
        assertEquals(listOf("Family film night on Fridays"), ideas.map { it.title })
        assertTrue(c.undoRequest(done))
        assertTrue(c.listsView.value.someday.none { g -> g.items.any { it.title == "Family film night on Fridays" } })

        // The same words again today: nothing goes out, the open card comes back with no tidy note.
        val again = c.tidyVoiceNote(words, "Voice note · 22:40")
        assertEquals(1, server.sent.size)
        assertEquals("", again.tidy)
        assertEquals(1, assertIs<HandleOutcome.Cards>(again.cards).cards.size)
    }

    @Test
    fun offlineOrOffKeepsNothingAndSaysSo() = runTest {
        val c = core()
        server.down = true
        val off = c.tidyVoiceNote(words, "Voice note · 22:40")
        assertEquals("", off.tidy)
        assertIs<HandleOutcome.Unavailable>(off.cards)
        server.down = false
        server.reply = VoiceNoteCodec.Response("off")
        assertIs<HandleOutcome.Unavailable>(c.tidyVoiceNote(words, "Voice note · 22:40").cards)
        assertTrue(c.requests.value.isEmpty())
        // Nothing in it: a tidy note, nothing to add.
        server.reply = VoiceNoteCodec.Response("answered", "Long day. Tired.")
        val none = c.tidyVoiceNote("long day today really tired honestly", "Voice note · 22:41")
        assertEquals("Long day. Tired.", none.tidy)
        assertEquals("Tidied · nothing in it to add", assertIs<HandleOutcome.NothingToDo>(none.cards).line)
    }

    @Test
    fun slice3TheMacsRowsSubjectAndLinesComeFromTheCore() = runTest {
        val c = core()
        val vn = os.meka.core.domain.VoiceNoteRules
        // 22:40 tonight and 08:05 three days ago, in London.
        val tonight = vn.made("vn-a", now, 83_000, listOf("ring the garage"), failed = false)
        val earlier = vn.made("vn-b", now - 3 * 86_400_000L - (14 * 60 + 35) * 60_000L, 5_000, emptyList(), failed = true)
        assertEquals("Voice note · 22:40", c.voiceNoteSubject(now))
        val rows = c.voiceNoteRows(listOf(earlier, tonight), onMac = true)
        assertEquals(listOf("vn-a", "vn-b"), rows.map { it.id })
        assertEquals("Today 22:40 · 1:23", rows[0].title)
        assertEquals("Kept until Mon 9 Nov", rows[0].keepLine)
        assertTrue(rows[1].title.endsWith("08:05 · 0:05"))
        assertEquals("Not turned into words on this Mac", rows[1].text)
        assertEquals(os.meka.core.domain.DailyLine("2 notes · newest today", true), c.voiceNotesLine(listOf(earlier, tonight)))
        assertNull(c.voiceNotesLine(emptyList()))

        // What the AI's answer says on the Mac's sheet and in Voice notes.
        server.reply = VoiceNoteCodec.Response("answered", "Ring the garage.", listOf("Sam"),
            listOf(MessageRequestCodec.Proposal("task", "Ring the garage", words = "tomorrow")))
        val read = c.tidyVoiceNote(words, "Voice note · 22:40")
        assertEquals(1, read.cardCount)
        assertEquals("Mentions Sam", read.peopleLine)
        assertEquals("Tidied · 1 thing to add", read.captureLine)
        assertEquals("Tidied · 1 thing to add · in Needs you", read.paneLine)
        server.down = true
        val off = c.tidyVoiceNote("buy bread and call the plumber about the boiler", "Voice note · 22:41")
        assertEquals(-1, off.cardCount)
        assertTrue(off.captureLine.endsWith(" · make cards in Voice notes later"))
        assertNull(vn.tidied(tonight, off.cardCount, off.tidy, off.people))
    }
}
