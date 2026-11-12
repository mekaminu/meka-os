package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.MessageRequestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Handle her requests for me" through the facade (Take first, 2026-11-11), slice 1: his partner's work-from-home
 * request is done at once (the home day, three prep steps for today, a reminder) with no card and nothing sent; Undo
 * takes everything back; with the switch off it is today's card.
 */
class HandleForMeFacadeTest {
    private val now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var reply = MessageRequestCodec.Response("answered")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun messageRequest(request: MessageRequestCodec.Request): MessageRequestCodec.Response = reply
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val lists = PeopleLists(family = setOf("Alex"))
    private fun msg(who: String, text: String, at: Long = now - 60_000) =
        CapturedItem("wa-$who-$at", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, at)

    private suspend fun titles(c: MekaCore, ids: List<String>) = ids.mapNotNull { c.taskForDetail(it)?.title }

    @Test
    fun herWorkFromHomeRequestIsDoneAtOnceAndUndoTakesItAllBack() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        server.reply = MessageRequestCodec.Response(
            "answered", listOf(MessageRequestCodec.Proposal("work_from_home", null, words = "Monday")),
        )
        val read = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "can you work from home on Monday?"), lists, emptySet()))
        assertTrue(read.cards.isEmpty(), "no card to tap")
        assertTrue(c.requests.value.isEmpty())
        val row = read.handled.single()
        // Monday is three days away: the steps land on the working day before, today (Friday), reminding at 16:00.
        assertEquals("Work from home Mon 12 Oct · added to Monday, 3 steps for today, reminder 16:00", row.line)
        assertEquals("From Alex · 14:02", row.from)
        assertEquals(listOf(row), c.doneForYou.value)
        assertEquals(3, row.taskIds.size)
        assertEquals(listOf("Tell your manager", "Get approval", "Put the request in at work"), titles(c, row.taskIds))
        assertTrue(row.taskIds.all { c.taskForDetail(it)?.remindAtMs != null }, "each step reminds")
        assertTrue(c.calendarView.value.sections.first { it.title == "Mon 12 Oct" }.workLine.orEmpty().startsWith("Work from home"))

        // Slice 2: the morning brief says what was done, and the steps still open today.
        assertEquals(
            listOf(
                "Done for you: Alex asked you to work from home on Monday, and it's marked as a home day.",
                "Still to do today: Tell your manager, get approval and put the request in at work.",
            ),
            c.briefView.value.story.doneForYou,
        )
        assertTrue(os.meka.core.domain.BriefScriptRules.script(c.briefView.value).contains("Done for you: Alex asked you to work from home on Monday"))
        // Today's ALSO TODAY carries it too, and its tap opens Needs you.
        val tile = c.today.value.alsoToday.first { it.kind == os.meka.core.domain.AlsoKind.DONE_FOR_YOU }
        assertEquals("Work from home Monday", tile.value)
        assertEquals("from Alex · 3 steps to do today", tile.line)
        assertTrue(tile.lit)
        c.complete(row.taskIds[0])
        assertEquals("from Alex · 2 steps to do today", c.today.value.alsoToday.first { it.kind == os.meka.core.domain.AlsoKind.DONE_FOR_YOU }.line)
        assertEquals(
            "Still to do today: Get approval and put the request in at work.",
            c.briefView.value.story.doneForYou.last(),
            "a step done is no longer said",
        )

        assertTrue(c.undoDoneForYou(row.id))
        assertTrue(c.doneForYou.value.isEmpty())
        assertTrue(c.briefView.value.story.doneForYou.isEmpty(), "Undo: nothing to say")
        assertTrue(c.today.value.alsoToday.none { it.kind == os.meka.core.domain.AlsoKind.DONE_FOR_YOU }, "Undo: no tile")
        assertTrue(titles(c, row.taskIds).isEmpty())
        assertFalse(c.calendarView.value.sections.first { it.title == "Mon 12 Oct" }.workLine.orEmpty().startsWith("Work from home"))
        assertFalse(c.undoDoneForYou(row.id), "only once")
    }

    @Test
    fun switchOffUncertainOrSomeoneElseStaysACard() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        c.setAboutYouPartnerHandle(false)
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "get milk", words = "tomorrow")))
        val off = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "get milk tomorrow"), lists, emptySet()))
        assertEquals(1, off.cards.size)
        assertTrue(off.handled.isEmpty())

        c.setAboutYouPartnerHandle(true)
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "sort the garden")))
        val vague = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "we should sort the garden at some point", at = now - 30_000), lists, emptySet()))
        assertEquals(1, vague.cards.size, "no clear day: a card, not a guess")
        assertTrue(vague.handled.isEmpty())

        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "call the garage", words = "tomorrow")))
        val other = assertIs<RequestRead.Read>(c.readRequest(msg("Sam", "call the garage tomorrow"), PeopleLists(family = setOf("Alex", "Sam")), emptySet()))
        assertEquals(1, other.cards.size)
        assertTrue(c.doneForYou.value.isEmpty())
    }

    @Test
    fun someoneElsesRequestsAreHandledOnceHeTurnsTheirSwitchOn() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        val family = PeopleLists(family = setOf("Alex", "Sam"))
        assertFalse(c.notificationSettings.value.handlesFor("Sam"))
        assertTrue(c.setRequestHandle("Sam", true))
        assertTrue(c.notificationSettings.value.handlesFor("sam"))
        assertFalse(c.setRequestHandle("Sam", true), "already on")

        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "call the garage", words = "tomorrow")))
        val read = assertIs<RequestRead.Read>(c.readRequest(msg("Sam", "call the garage tomorrow"), family, emptySet()))
        assertTrue(read.cards.isEmpty(), "done for him, no card")
        val row = read.handled.single()
        assertEquals("From Sam · 14:02", row.from)
        assertEquals(listOf("call the garage"), titles(c, row.taskIds).map { it.lowercase() })
        assertEquals("Copy reply to Sam", row.drafts.first().label)

        // Off again: back to today's card. Her switch stays About you's.
        assertTrue(c.setRequestHandle("Sam", false))
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "book the MOT", words = "tomorrow")))
        val off = assertIs<RequestRead.Read>(c.readRequest(msg("Sam", "book the MOT tomorrow", at = now - 30_000), family, emptySet()))
        assertEquals(1, off.cards.size)
        assertTrue(c.setRequestHandle("Alex", false))
        assertFalse(c.aboutYouView.value.partnerHandle)
        assertEquals("Alex", c.aboutYouView.value.partnerName)
        assertTrue(c.notificationSettings.value.handleFor.isEmpty(), "her switch never lands in the per-person set")
    }

    @Test
    fun herTaskGetsItsDayAndAReminderAndClearKeepsIt() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "pick up the dry cleaning", words = "tomorrow")))
        val row = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "can you pick up the dry cleaning tomorrow?"), lists, emptySet())).handled.single()
        assertEquals("“Pick up the dry cleaning” · Tomorrow · added to Tomorrow, reminder Tomorrow 09:00", row.line)
        assertEquals(listOf("Pick up the dry cleaning"), titles(c, row.taskIds))
        assertTrue(c.taskForDetail(row.taskIds.single())?.remindAtMs != null)
        assertTrue(c.clearDoneForYou(row.id))
        assertTrue(c.doneForYou.value.isEmpty())
        assertEquals(listOf("Pick up the dry cleaning"), titles(c, row.taskIds), "Clear keeps what it made")
    }

    @Test
    fun aNewKindGetsObisStepsAsSuggestionsHeCanUntick() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "buy a present for Sam's birthday", words = "tomorrow")))
        val row = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "can you buy a present for Sam's birthday tomorrow?"), lists, emptySet())).handled.single()
        assertEquals("“Buy a present for Sam's birthday” · Tomorrow · added to Tomorrow, Obi suggests 2 steps, reminder Tomorrow 09:00", row.line)
        assertEquals(listOf("Buy a present for Sam's birthday", "Get a card", "Wrap it"), titles(c, row.taskIds))
        assertEquals(listOf("Get a card", "Wrap it"), row.suggestions.map { it.title })
        assertEquals(row.taskIds.drop(1), row.suggestions.map { it.taskId })

        // Untick "Wrap it": its task is gone, the row keeps the rest, and Undo no longer counts it.
        val wrap = row.suggestions.last()
        assertTrue(c.untickDoneForYouStep(row.id, wrap.taskId))
        assertEquals(null, c.taskForDetail(wrap.taskId))
        val after = c.doneForYou.value.single()
        assertEquals(listOf("Get a card"), after.suggestions.map { it.title })
        assertEquals(2, after.taskIds.size)
        assertFalse(c.untickDoneForYouStep(row.id, wrap.taskId), "already off")
        assertFalse(c.untickDoneForYouStep(row.id, row.taskIds.first()), "the request's own task isn't a suggestion")

        assertTrue(c.undoDoneForYou(row.id))
        assertTrue(titles(c, row.taskIds).isEmpty(), "Undo takes back the rest")
    }
}
