package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Messages grouped per chat, slice 3c: a busy chat's one-line summary — what is sent, what the answer may become, how the card shows it. */
class ChatSummaryTest {
    private fun card(
        id: String, who: String, at: Long, gist: String = "Talks about the lineup",
        lane: TriageLane = TriageLane.FYI, group: String? = "Barça lads",
    ): TriageCard {
        val time = "14:" + (at.toInt() % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, null, CaptureApp.WHATSAPP, group != null, at, "spoken", group)
    }

    private val lads = (1..5).map { i -> card("g$i", listOf("Tunde", "Femi", "Obi")[i % 3], at = i.toLong(), gist = "Lineup point $i") } +
        card("g6", "Femi", at = 6, gist = "Asks if you're coming Saturday?", lane = TriageLane.NEEDS_REPLY)
    private val tunde = (1..2).map { i -> card("t$i", "Tunde", at = 10L + i, group = null) }

    private fun summary(chat: TriageChat, text: String?, upTo: Long = chat.latestMs, made: Long = 0) =
        ChatSummary(chat.key, chat.title, text, upTo, chat.cards.size, made)

    @Test
    fun onlyBusyChatsAreSentWithEachMessagesSenderTimeAndGist() {
        val chats = TriageChatRules.chats(lads + tunde)
        val sent = ChatSummaryRules.toSummarise(chats, emptyList(), TriageSettings(), nowMs = 100)
        // Tunde's chat has two messages: its top lines say it all, so it isn't sent.
        val lads = sent.single()
        assertEquals("Barça lads", lads.name)
        assertTrue(lads.isGroup)
        assertEquals(6, lads.lines.size)
        assertEquals(ChatSummaryRules.Line("Femi", "14:01", "Lineup point 1"), lads.lines.first())
        assertEquals(6L, lads.upToMs)
    }

    @Test
    fun aMessageKeptFromTheAiOrAChatKeptFromItIsNeverSent() {
        val kept = lads.mapIndexed { i, c -> if (i < 3) c.copy(gist = TriageCard.LOCAL_LINE) else c }
        // Three readable lines left: under the minimum, so nothing goes.
        assertTrue(ChatSummaryRules.toSummarise(TriageChatRules.chats(kept), emptyList(), TriageSettings(), 100).isEmpty())
        val chats = TriageChatRules.chats(lads)
        assertTrue(ChatSummaryRules.toSummarise(chats, emptyList(), TriageSettings(neverToAi = setOf("Barça lads")), 100).isEmpty())
        // A person kept from the AI is left out of the lines.
        val noObi = ChatSummaryRules.toSummarise(chats, emptyList(), TriageSettings(neverToAi = setOf("Obi")), 100)
        assertEquals(4, noObi.single().lines.size)
        assertTrue(noObi.single().lines.none { it.from == "Obi" })
    }

    @Test
    fun aSummaryIsMadeOnceForItsMessagesAndAgainOnlyAfterNewsAndTenMinutes() {
        val chat = TriageChatRules.chats(lads).single()
        val now = 1_000_000L
        // Up to date: nothing to send.
        assertTrue(ChatSummaryRules.toSummarise(listOf(chat), listOf(summary(chat, "Lineup", made = now)), TriageSettings(), now).isEmpty())
        // News since, but the summary is fresh: wait.
        val older = summary(chat, "Lineup", upTo = 5, made = now - 60_000)
        assertTrue(ChatSummaryRules.toSummarise(listOf(chat), listOf(older), TriageSettings(), now).isEmpty())
        // News and ten minutes: again.
        assertEquals(1, ChatSummaryRules.toSummarise(listOf(chat), listOf(older), TriageSettings(), now + ChatSummaryRules.REFRESH_MS).size)
        // A summary from before these messages (the chat was finished since): a new one straight away.
        val stale = summary(chat, "Old news", upTo = 0, made = now)
        assertEquals(1, ChatSummaryRules.toSummarise(listOf(chat), listOf(stale), TriageSettings(), now).size)
    }

    @Test
    fun theAnswerIsCheckedAgainstWhatWasSent() {
        val sent = ChatSummaryRules.toSummarise(TriageChatRules.chats(lads), emptyList(), TriageSettings(), 100)
        val checked = ChatSummaryRules.check(
            sent,
            listOf(
                ChatSummaryRules.Raw("Work", "Secret"),
                ChatSummaryRules.Raw("barça lads", "  \"Saturday's lineup;\n Femi asks if you're coming\" "),
            ),
        )
        assertEquals(mapOf(sent[0].chatKey to "Saturday's lineup; Femi asks if you're coming"), checked)
        // No line for a chat sent: it still gets an entry, so it isn't sent again until it has news.
        assertEquals(mapOf(sent[0].chatKey to null), ChatSummaryRules.check(sent, emptyList()))
        val long = ChatSummaryRules.check(sent, listOf(ChatSummaryRules.Raw("Barça lads", "word ".repeat(60))))
        assertTrue(long.values.single()!!.length <= ChatSummaryRules.MAX_SUMMARY)
    }

    @Test
    fun theCardShowsTheSummaryFirstAndKeepsOnlyTheRepliesUnderIt() {
        val chats = TriageChatRules.chats(lads + tunde)
        val ladsChat = chats.first { it.isGroup }
        val shown = ChatSummaryRules.withSummaries(chats, listOf(summary(ladsChat, "Saturday's lineup; Femi asks if you're coming")))
        val lads = shown.first { it.isGroup }
        assertEquals("Saturday's lineup; Femi asks if you're coming", lads.summary)
        assertEquals(listOf("Femi: Asks if you're coming Saturday?"), lads.top)
        assertTrue(lads.spoken.endsWith("In short: Saturday's lineup; Femi asks if you're coming."), lads.spoken)
        // Tunde's chat has no summary: unchanged.
        assertEquals(chats.first { !it.isGroup }, shown.first { !it.isGroup })
        // A summary with no line, or one from before these messages, changes nothing.
        assertEquals(chats, ChatSummaryRules.withSummaries(chats, listOf(summary(ladsChat, null))))
        assertEquals(chats, ChatSummaryRules.withSummaries(chats, listOf(summary(ladsChat, "Old", upTo = 0))))
        assertNull(ChatSummaryRules.withSummaries(chats, emptyList()).first().summary)
    }

    @Test
    fun theSummariesSyncWithoutTheMessages() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val fold = ChatSummaries(a.replica) { world.clock.nowMs }
        val mac = ChatSummaries(m.replica) { world.clock.nowMs }
        val sent = ChatSummaryRules.toSummarise(TriageChatRules.chats(lads), emptyList(), TriageSettings(), world.clock.nowMs).single()
        fold.save(sent, "Saturday's lineup")
        a.sync(); m.sync()
        val got = mac.all().single()
        assertEquals("Saturday's lineup", got.summary)
        assertEquals("Barça lads", got.title)
        assertEquals(6, got.count)
        assertTrue(m.replica.entities(EntityTypes.CHAT_SUMMARY).none { e -> e.fields.values.any { it.toString().contains("Lineup point") } })
        // Saved again with no line: read back as none, and the card keeps its top lines.
        fold.save(sent, null)
        assertNull(fold.all().single().summary)
        // Kept a few days at most.
        world.clock.nowMs += ChatSummaries.RETENTION_MS + 1
        assertTrue(fold.all().isEmpty())
    }
}
