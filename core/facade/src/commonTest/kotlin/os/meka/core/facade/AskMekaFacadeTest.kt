package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.AskRules
import os.meka.core.domain.AskUndo
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.TalkEffect
import os.meka.core.domain.TalkFlow
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.ValidationException
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ask MEKA through the facade (build plan V1, AI layer slice 3): handles stay here, cards do nothing until tapped. */
class AskMekaFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London (16:15 UTC)

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<String, AskContext>>()
        val histories = mutableListOf<Pair<List<TalkTurn>, Boolean>>()
        var reply: AskReply = AskReply.Answered("Nothing yet.", emptyList())
        var down = false
        var status: AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun aiStatus(): AiStatusReply? {
            if (down) throw TransportException("offline")
            return status
        }
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += question to context
            histories += history to voice
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val serverOps = InMemoryServerOpStore()
    private val server = Server(SyncService(serverOps))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aQuestionCarriesTodayByHandlesAndCardsComeBackChecked() = runTest {
        val c = core()
        val id = c.addTask("Book dentist")
        server.reply = AskReply.Answered(
            "Moved it to tomorrow at 9.",
            listOf(
                AskRawAction("move_task", ref = "t1", date = "2026-10-09", time = "09:00"),
                AskRawAction("complete_task", ref = "t9"),
                AskRawAction("send_email", title = "everything"),
            ),
        )
        val out = assertIs<AskOutcome.Answered>(c.askMeka("  Move the dentist to tomorrow morning "))
        val (q, ctx) = server.asked.single()
        assertEquals("Move the dentist to tomorrow morning", q)
        assertEquals("2026-10-08", ctx.dateIso)
        assertTrue(ctx.nowLine.endsWith("· 17:15"), ctx.nowLine)
        assertEquals("t1", ctx.items.single().ref)
        assertFalse(id in ctx.items.single().line)

        assertEquals("Moved it to tomorrow at 9.", out.answer.text)
        val card = out.answer.cards.single()
        assertEquals("Move “Book dentist” to Tomorrow · 09:00", card.line)
        // Nothing happened until the tap.
        assertEquals(id, c.today.value.upNext?.id)
        assertEquals("Moved “Book dentist” to Tomorrow · 09:00", c.doAsk(card).line)
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).none { it.id == id })
    }

    @Test
    fun theServersForecastShowsOnTodayAndTravelsWithAQuestion() = runTest {
        val c = core()
        assertEquals(os.meka.core.domain.WeatherView.EMPTY, c.weatherView.value)
        // What the server's weather mirror writes: Thursday 8 Oct from 00:00 London (23:00 UTC the day before), rain at 19:00.
        val midnight = 1_791_414_000_000L
        val hours = (0 until 48).map { os.meka.core.domain.WeatherHour(midnight + it * 3_600_000L, 13, if (it == 19) 61 else 3, if (it == 19) 80 else 10) }
        val days = listOf(os.meka.core.domain.WeatherDay(20_734, 9, 14, 61, 80), os.meka.core.domain.WeatherDay(20_735, 8, 15, 3, 10))
        val clock = os.meka.core.sync.HlcClock("server", { now })
        listOf(
            os.meka.core.domain.WeatherFields.HOURS to os.meka.core.domain.WeatherCodec.encodeHours(hours),
            os.meka.core.domain.WeatherFields.DAYS to os.meka.core.domain.WeatherCodec.encodeDays(days),
            os.meka.core.domain.WeatherFields.PLACE to "Biggleswade",
        ).forEachIndexed { i, (field, text) ->
            serverOps.append(os.meka.core.sync.Op("srvwx$i", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
                os.meka.core.domain.WeatherStore.ENTITY_ID, field, os.meka.core.sync.FieldValue.Text(text), clock.now(), emptyList(), "server"))
        }
        c.syncNow()
        assertEquals("13° · light rain from 19:00", c.weatherView.value.nowLine)
        assertEquals("Tomorrow 8–15°, cloudy", c.weatherView.value.tomorrowLine)
        // Weather slice 2: the brief's today, the shutdown's (and the bedside clock's) tomorrow, rain on the Day ring.
        assertEquals("9–14°, light rain from 19:00 — take a coat", c.briefView.value.weatherLine)
        assertEquals("8–15°, cloudy", c.shutdownView.value.tomorrow.weatherLine)
        assertEquals(listOf(os.meka.core.domain.DayBand(19 * 60, 20 * 60)), c.today.value.dayRing.rain)

        c.addTask("Book dentist")
        c.askMeka("what's the weather tomorrow?")
        val ctx = server.asked.single().second
        assertEquals("t1", ctx.items.first().ref)
        val weather = ctx.items.filter { it.kind == os.meka.core.domain.AskItemKind.WEATHER }.map { it.line }
        assertEquals("now in Biggleswade: 13° · light rain from 19:00", weather.first())
        assertTrue("tomorrow (Fri 9 Oct): 8–15° cloudy, up to 10 % chance of rain" in weather, weather.toString())
        assertTrue("today 18:00 13° cloudy, 10 % chance of rain" in weather, weather.toString())
    }

    @Test
    fun onAnOfficeDayTodaysLineSaysBothPlacesAndAskHearsWork() = runTest {
        now = 1_791_450_000_000L // Thu 8 Oct 2026, 10:00 in London: at work (Thursday 09:00–15:30)
        val c = core()
        val midnight = 1_791_414_000_000L
        val clock = os.meka.core.sync.HlcClock("server", { now })
        // What the server's weather mirror writes: home (rain at 20:00) and, apart, work (rain at 14:00; Places item 2).
        fun mirror(entity: String, place: String, temp: Int, rainAt: Int, prefix: String) {
            val hours = (0 until 48).map { os.meka.core.domain.WeatherHour(midnight + it * 3_600_000L, temp, if (it == rainAt) 61 else 3, if (it == rainAt) 80 else 10) }
            val days = listOf(os.meka.core.domain.WeatherDay(20_734, 9, 14, 3, 10), os.meka.core.domain.WeatherDay(20_735, 8, 15, 3, 10))
            listOf(
                os.meka.core.domain.WeatherFields.HOURS to os.meka.core.domain.WeatherCodec.encodeHours(hours),
                os.meka.core.domain.WeatherFields.DAYS to os.meka.core.domain.WeatherCodec.encodeDays(days),
                os.meka.core.domain.WeatherFields.PLACE to place,
            ).forEachIndexed { i, (field, text) ->
                serverOps.append(os.meka.core.sync.Op("$prefix$i", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
                    entity, field, os.meka.core.sync.FieldValue.Text(text), clock.now(), emptyList(), "server"))
            }
        }
        mirror(os.meka.core.domain.WeatherStore.ENTITY_ID, "Biggleswade", 11, 20, "srvwx")
        mirror(os.meka.core.domain.WeatherStore.WORK_ENTITY_ID, "Canary Wharf", 15, 14, "srvww")
        c.syncNow()
        assertEquals("Biggleswade 11° now · Canary Wharf 15°, light rain from 14:00 — take a coat", c.weatherView.value.nowLine)
        // The ring's rain follows where Meka will be: work's at 14:00, home's at 20:00.
        assertEquals(listOf(os.meka.core.domain.DayBand(14 * 60, 15 * 60), os.meka.core.domain.DayBand(20 * 60, 21 * 60)), c.today.value.dayRing.rain)

        c.askMeka("will it rain at work?")
        val weather = server.asked.single().second.items.filter { it.kind == os.meka.core.domain.AskItemKind.WEATHER }.map { it.line }
        assertEquals("now in Biggleswade: 11° · light rain from 20:00", weather.first())
        assertTrue("now at work in Canary Wharf: 15° · light rain from 14:00" in weather, weather.toString())

        // The work place is set from the app and reaches the server.
        assertEquals("Canary Wharf", c.weatherView.value.workChoice.name)
        assertTrue(!c.setWorkPlace("<nope>"))
        assertTrue(c.setWorkPlace("Cambridge"))
        assertEquals("Finding “Cambridge”… the forecast follows within a few minutes", c.weatherView.value.workChoice.line)
        c.syncNow()
        val sent = serverOps.after("hh", 0, 10_000).map { it.op }
            .single { it.entityId == os.meka.core.domain.WorkPlaceStore.ENTITY_ID && it.field == os.meka.core.domain.WorkPlaceFields.NAME }
        assertEquals(os.meka.core.sync.FieldValue.Text("Cambridge"), sent.value)
    }

    @Test
    fun theRoutesTrainLinesShowOnTheCommuteAndAskHearsThem() = runTest {
        now = 1_791_526_200_000L // Fri 9 Oct 2026, 07:10 in London: an office day, before work
        val c = core()
        val clock = os.meka.core.sync.HlcClock("server", { now })
        // What the server's TfL mirror writes (Places item 4): Thameslink in trouble, Great Northern running.
        serverOps.append(os.meka.core.sync.Op("srvln0", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
            os.meka.core.domain.LineStatusStore.ENTITY_ID, os.meka.core.domain.LineStatusFields.LINES,
            os.meka.core.sync.FieldValue.Text("elizabeth=10;great-northern=10;thameslink=6"), clock.now(), emptyList(), "server"))
        serverOps.append(os.meka.core.sync.Op("srvln1", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
            os.meka.core.domain.LineStatusStore.ENTITY_ID, os.meka.core.domain.LineStatusFields.CHECKED,
            os.meka.core.sync.FieldValue.Int64(1_791_525_600_000L), clock.now(), emptyList(), "server"))
        c.syncNow()
        val route = c.weatherView.value.route!!
        assertEquals("Thameslink severe delays · Elizabeth line good service — Great Northern to King's Cross is running", route.text)
        assertTrue(route.lit)
        // The same status is a heads-up once on this commute (Places item 4, before live departures).
        val gov = c.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Thameslink severe delays" to "Great Northern to King's Cross is running · TfL 07:00"),
            gov.post.filter { it.source == os.meka.core.domain.NoticeSource.TRAINS }.map { it.title to it.text })
        assertTrue(c.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL).post
            .none { it.source == os.meka.core.domain.NoticeSource.TRAINS })

        c.askMeka("are the trains ok?")
        val lines = server.asked.single().second.items.filter { it.kind == os.meka.core.domain.AskItemKind.WEATHER }.map { it.line }
        assertTrue("train lines on Meka's route (TfL status, checked 07:00): Thameslink severe delays; Great Northern good service; Elizabeth line good service" in lines,
            lines.toString())
    }

    @Test
    fun theWeatherPlaceIsSetFromTheAppAndReachesTheServer() = runTest {
        val c = core()
        assertEquals(os.meka.core.domain.WeatherPlaceView.HOME, c.weatherView.value.placeChoice)
        assertTrue(!c.setWeatherPlace("<nope>"))
        assertTrue(c.setWeatherPlace("Bedford"))
        assertEquals("Finding “Bedford”… the forecast follows within a few minutes", c.weatherView.value.placeChoice.line)
        c.syncNow()
        // The server reads it from the op log (Weather place setting).
        val sent = serverOps.after("hh", 0, 10_000).map { it.op }
            .single { it.entityId == os.meka.core.domain.WeatherPlaceStore.ENTITY_ID && it.field == os.meka.core.domain.WeatherPlaceFields.NAME }
        assertEquals(os.meka.core.sync.FieldValue.Text("Bedford"), sent.value)
        // Home again: blank goes back to Biggleswade.
        assertTrue(c.setWeatherPlace(""))
        assertEquals("Biggleswade", c.weatherView.value.placeChoice.name)
    }

    @Test
    fun cardsAddTicksOffAndSetTimers() = runTest {
        val c = core()
        val id = c.addTask("Create CR")
        server.reply = AskReply.Answered("Here.", listOf(AskRawAction("add_task", title = "Milk"), AskRawAction("complete_task", ref = "t1"), AskRawAction("set_timer", minutes = 20)))
        val cards = assertIs<AskOutcome.Answered>(c.askMeka("do things")).answer.cards
        assertEquals(listOf("Add “Milk”", "Tick off “Create CR”", "Timer · 20 min"), cards.map { it.line })
        assertEquals("Added “Milk”", c.doAsk(cards[0]).line)
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).any { it.title == "Milk" })
        assertEquals("Ticked off “Create CR”", c.doAsk(cards[1]).line)
        assertTrue(c.today.value.doneToday.any { it.id == id })
        // Ticked off already: the card can't do it twice.
        assertFailsWith<ValidationException> { c.doAsk(cards[1]) }
        assertEquals("Timer set · 20 min", c.doAsk(cards[2]).line)
        val fast = os.meka.core.domain.AskRules.cardOf(AskProposal.StartFast(36), 0)
        assertEquals("Started a 36 h fast", c.doAsk(fast).line)
        assertFailsWith<ValidationException> { c.doAsk(fast) }
    }

    @Test
    fun noAnswerSaysWhy() = runTest {
        val c = core()
        server.reply = AskReply.Unavailable("off", null)
        assertEquals(AskOutcome.Unavailable("MEKA's AI is off"), c.askMeka("hi"))
        server.reply = AskReply.Unavailable("over", null)
        assertEquals(AskOutcome.Unavailable("This month's AI budget is used up · back on the 1st"), c.askMeka("hi"))
        server.reply = AskReply.Unavailable("failed", "Couldn't reach Anthropic")
        assertEquals(AskOutcome.Unavailable("Couldn't ask: Couldn't reach Anthropic"), c.askMeka("hi"))
        server.down = true
        assertEquals(AskOutcome.Unavailable(AskRules.OFFLINE_LINE), c.askMeka("hi"))
        assertEquals(AskOutcome.Unavailable("Ask something first"), c.askMeka("   "))
        assertEquals(4, server.asked.size)
        assertEquals(AskOutcome.Unavailable(AskRules.NOT_CONNECTED_LINE), core(transport = null).askMeka("hi"))
    }

    @Test
    fun addToShoppingFromAskAddsToTheListTakesBackAndSearchFindsIt() = runTest {
        val c = core()
        c.addShopping("eggs, bread")
        val bread = c.listsView.value.shopping.toBuy.single { it.title == "bread" }.id
        c.gotShopping(bread)
        server.reply = AskReply.Answered("Added.", listOf(AskRawAction("add_shopping", title = "eggs, bread, milk")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("we need eggs, bread and milk"))
        // The list went with the question as one line.
        val line = server.asked.single().second.items.single { it.kind == os.meka.core.domain.AskItemKind.SHOPPING }
        assertEquals("Shopping list · 1 to buy: eggs", line.line)
        val card = out.answer.cards.single()
        assertEquals("Add to shopping · eggs, bread and milk", card.line)
        // Nothing until tapped.
        assertEquals(listOf("eggs"), c.listsView.value.shopping.toBuy.map { it.title })

        val done = c.doAsk(card)
        assertEquals("Added eggs, bread and milk to shopping", done.line)
        assertEquals(listOf("eggs", "bread", "milk"), c.listsView.value.shopping.toBuy.map { it.title })
        c.search("bread")
        val hit = c.searchView.value.hits.single()
        assertEquals(os.meka.core.domain.SearchTarget.LISTS_SHOPPING, hit.target)
        assertEquals("To buy", hit.detail)

        // Undo: milk leaves, bread goes back under Got, eggs (already there) stay.
        val undo = assertIs<AskUndo.TakeBackShopping>(done.undo)
        assertTrue(c.undoAsk(undo))
        assertEquals(listOf("eggs"), c.listsView.value.shopping.toBuy.map { it.title })
        assertEquals(listOf("bread"), c.listsView.value.shopping.got.map { it.title })
        assertEquals("Got today", c.searchView.value.hits.single().detail)
        assertFalse(c.undoAsk(undo))
    }

    @Test
    fun everyCardCanBeTakenBack() = runTest {
        val c = core()
        c.addTask("Book dentist")
        c.addTask("Create CR")
        server.reply = AskReply.Answered("Here.", listOf(
            AskRawAction("add_task", title = "Milk", date = "2026-10-09"),
            AskRawAction("complete_task", ref = "t1"),
            AskRawAction("move_task", ref = "t2", date = "2026-10-10", time = "09:00"),
        ))
        val cards = assertIs<AskOutcome.Answered>(c.askMeka("do things")).answer.cards
        assertEquals(3, cards.size)
        val dentist = assertIs<AskProposal.CompleteTask>(cards[1].proposal).taskId
        val cr = assertIs<AskProposal.MoveTask>(cards[2].proposal).taskId
        assertTrue(dentist != cr)
        fun open() = (listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).map { it.id }

        val added = c.doAsk(cards[0])
        val milk = assertIs<AskUndo.RemoveTask>(added.undo).taskId
        assertTrue(c.undoAsk(added.undo!!))
        assertFalse(c.undoAsk(added.undo!!))
        assertFalse(milk in open())

        val ticked = c.doAsk(cards[1])
        assertTrue(c.today.value.doneToday.any { it.id == dentist })
        assertTrue(c.undoAsk(ticked.undo!!))
        assertTrue(dentist in open())
        // Already put back: nothing more to do.
        assertFalse(c.undoAsk(ticked.undo!!))

        val moved = c.doAsk(cards[2])
        assertFalse(cr in open())
        assertTrue(c.undoAsk(moved.undo!!))
        assertTrue(cr in open())

        // Changed since (moved again by hand): Undo leaves it.
        val again = c.doAsk(cards[2])
        c.setWhen(cr, AskRules.parseDay("2026-10-13")!!, null)
        assertFalse(c.undoAsk(again.undo!!))

        val fast = c.doAsk(AskRules.cardOf(AskProposal.StartFast(36), 0))
        assertTrue(c.undoAsk(fast.undo!!))
        assertFalse(c.undoAsk(fast.undo!!))
        // Thrown away, so a new fast can start.
        c.doAsk(AskRules.cardOf(AskProposal.StartFast(36), 0))

        val timer = c.doAsk(AskRules.cardOf(AskProposal.Timer(20), 0))
        assertIs<AskUndo.CancelAlarm>(timer.undo)
        assertTrue(c.undoAsk(timer.undo!!))
    }

    @Test
    fun theStatusLineComesFromTheServer() = runTest {
        val c = core()
        assertEquals(AiStatusView("On · $1.20 of $20 this month", lit = false, canAsk = true), c.aiStatus())
        server.status = AiStatusReply("on", null, 2000, 2000, "over")
        assertFalse(c.aiStatus().canAsk)
        server.status = null
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        server.down = true
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        assertEquals(AskRules.STATUS_NOT_CONNECTED, core(transport = null).aiStatus())
    }

    @Test
    fun talkingSendsTheConversationAndASpokenYesDoesWhatATapWould() = runTest {
        val c = core()
        val id = c.addTask("Book dentist")
        // A one-off question sends no history and isn't spoken.
        c.askMeka("what's left?")
        assertEquals(emptyList<TalkTurn>() to false, server.histories.last())

        var step = TalkFlow.start()
        step = TalkFlow.heard(step.session, "What's on today?")
        val ask1 = assertIs<TalkEffect.Ask>(step.effects.single())
        server.reply = AskReply.Answered("Just the dentist to book.", emptyList())
        step = TalkFlow.answered(step.session, ask1.question, c.talk(ask1.question, ask1.history), c.todayEpochDay())
        assertEquals(TalkEffect.Speak("Just the dentist to book."), step.effects.single())
        assertEquals(emptyList<TalkTurn>() to true, server.histories.last())
        step = TalkFlow.spoke(step.session)

        step = TalkFlow.heard(step.session, "move it to tomorrow at nine")
        val ask2 = assertIs<TalkEffect.Ask>(step.effects.single())
        server.reply = AskReply.Answered("Tomorrow at 9.", listOf(AskRawAction("move_task", ref = "t1", date = "2026-10-09", time = "09:00")))
        step = TalkFlow.answered(step.session, ask2.question, c.talk(ask2.question, ask2.history), c.todayEpochDay())
        // The first exchange went with the second question.
        assertEquals(listOf(TalkTurn("What's on today?", "Just the dentist to book.")) to true, server.histories.last())
        assertEquals(TalkEffect.Speak("Tomorrow at 9. Shall I move Book dentist to tomorrow at 09:00?"), step.effects.single())
        // Nothing moved until the yes.
        assertEquals(id, c.today.value.upNext?.id)
        step = TalkFlow.spoke(step.session)
        step = TalkFlow.heard(step.session, "yes please")
        val todo = assertIs<TalkEffect.Do>(step.effects.single())
        val done = todo.cards.map { c.doAsk(it) }
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).none { it.id == id })
        step = TalkFlow.did(step.session, todo.cards.map { it.proposal }, done.map { it.line }, 0, c.todayEpochDay())
        assertEquals(TalkEffect.Speak("Moved Book dentist to tomorrow at 09:00. Anything else?"), step.effects.single())
        assertEquals(listOf("Moved “Book dentist” to Tomorrow · 09:00"), step.session.conversation.turns.last().done)
        // The undo chip still takes it back.
        assertTrue(c.undoAsk(done.single().undo!!))

        step = TalkFlow.spoke(step.session)
        step = TalkFlow.heard(step.session, "that's all, thanks")
        assertEquals(TalkPhase.ENDED, step.session.phase)
        assertEquals(TalkEffect.End, step.effects.single())
    }

    @Test
    fun aSpokenYesDoesEveryCardItCanAndOneUndoTakesThemAllBack() = runTest {
        val c = core()
        val id = c.addTask("Book dentist")
        val today = c.todayEpochDay()
        val move = AskRules.cardOf(AskProposal.MoveTask(id, "Book dentist", today + 1, 9 * 60), today)
        val add = AskRules.cardOf(AskProposal.AddTask("Milk", null, null), today)
        val gone = AskRules.cardOf(AskProposal.CompleteTask("nope", "Gone"), today)
        val did = c.doTalk(listOf(move, gone, add))
        assertEquals(listOf(move.proposal, add.proposal), did.done)
        assertEquals(1, did.failed)
        assertEquals(2, did.undos.size)
        fun open() = (listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).map { it.title }
        assertTrue("Milk" in open())
        assertFalse("Book dentist" in open())
        // The undo bar's Undo takes both back, newest first.
        assertTrue(c.undoTalk(did.undos))
        assertFalse("Milk" in open())
        assertTrue("Book dentist" in open())
        // Again, nothing is left to take back.
        assertFalse(c.undoTalk(did.undos))
    }
}
