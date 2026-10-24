package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskItemKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun theQuestionCarriesTheNameTodayAndTheOrbUse() = runTest {
        // One name everywhere, slice 2: the companion on the server is told Your name's first word, or none.
        val c = core()
        c.askMeka("Why is the sky blue?")
        assertNull(server.asked.last().second.callName)
        c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
        c.askMeka("Why is the sky blue?")
        assertEquals("Alex", server.asked.last().second.callName)
        assertEquals("Alex", c.aboutYouView.value.callName)
    }

    @Test
    fun anOpenChatsNoteIsKeptSentBackWithTheNextOpenQuestionAndForgottenOnRequest() = runTest {
        // The companion, slice 5: remembering across conversations.
        val c = core()
        server.reply = AskReply.Answered("Heat pumps move heat rather than make it.", emptyList(), note = "Talked through heat pumps vs a new boiler; leaning heat pump")
        c.askMeka("Explain how a heat pump works")
        assertEquals(listOf("Today · Talked through heat pumps vs a new boiler; leaning heat pump"), c.companionMemoryRows())
        assertTrue(server.asked.last().second.recall.isEmpty())
        // The next open question carries it; a look-up of his day doesn't.
        server.reply = AskReply.Answered("Ok.", emptyList())
        c.askMeka("Any ideas for half term?")
        assertEquals(listOf("Thu 8 Oct · Talked through heat pumps vs a new boiler; leaning heat pump"), server.asked.last().second.recall)
        c.askMeka("What's on today?")
        assertTrue(server.asked.last().second.recall.isEmpty())
        c.askMeka("Can we pick up where we left off?")
        assertEquals(1, server.asked.last().second.recall.size)
        // A note that carries a long number is never kept.
        server.reply = AskReply.Answered("Ok.", emptyList(), note = "Talked about the account 12345678")
        c.askMeka("Why is the sky blue?")
        assertEquals(1, c.companionMemoryRows().size)
        // Forget all, then off: nothing kept, nothing sent.
        c.forgetCompanionMemory()
        assertTrue(c.companionMemoryRows().isEmpty())
        c.setCompanionRemembers(false)
        assertFalse(c.companionRemembers())
        server.reply = AskReply.Answered("Ok.", emptyList(), note = "Talked about learning Spanish on the train")
        c.askMeka("Should I learn Spanish or Italian?")
        assertTrue(c.companionMemoryRows().isEmpty())
        c.setCompanionRemembers(true)
        c.askMeka("Should I learn Spanish or Italian?")
        assertEquals(listOf("Today · Talked about learning Spanish on the train"), c.companionMemoryRows())
        // An answer that read the web keeps no note, even if one came.
        c.forgetCompanionMemory()
        server.reply = AskReply.Answered("The BBC says so.", emptyList(), sources = listOf("Strike" to "https://bbc.co.uk/a"), note = "Talked about the rail strike")
        c.askMeka("What's the latest on the rail strike?")
        assertTrue(c.companionMemoryRows().isEmpty())
    }

    @Test
    fun aLiveAnswerShowsItsSourcesHttpsOnlyAndOneASite() = runTest {
        // The companion, slice 2: where a live answer came from, as cards under the words.
        val c = core()
        server.reply = AskReply.Answered(
            "The BBC says the strike is off.", emptyList(),
            sources = listOf("Strike off" to "https://www.bbc.co.uk/a", "Also BBC" to "https://bbc.co.uk/b", "Plain" to "http://x.example.com", "Met" to "https://met.example.com/f"),
        )
        val out = assertIs<AskOutcome.Answered>(c.askMeka("What's the latest on the rail strike?"))
        assertEquals(listOf("bbc.co.uk · Strike off", "met.example.com · Met"), out.answer.sources.map { os.meka.core.domain.LiveFactsRules.line(it) })
        assertEquals("https://www.bbc.co.uk/a", out.answer.sources.first().url)
        server.reply = AskReply.Answered("Nothing yet.", emptyList())
        assertTrue(assertIs<AskOutcome.Answered>(c.askMeka("Why is the sky blue?")).answer.sources.isEmpty())
    }

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
        // The task, the commute's lines (an office day today and tomorrow, and the wake alarm) and protected time's one
        // "off" line (both sent with every question, so "no" is an answer too). The companion, slice 4: "tomorrow" carries
        // tomorrow too, first in the tail: clear, nothing in the calendar and no tasks.
        assertEquals(listOf(AskItemKind.TASK, AskItemKind.DAYS) + List(4) { AskItemKind.COMMUTE } + AskItemKind.PROTECTED, ctx.items.map { it.kind })
        assertEquals("Days asked about · Fri 9 Oct · nothing in the calendar and no tasks", ctx.items[1].line)
        assertEquals("t1", ctx.items.first().ref)
        assertFalse(id in ctx.items.first().line)

        assertEquals("Moved it to tomorrow at 9.", out.answer.text)
        val card = out.answer.cards.single()
        assertEquals("Move “Book dentist” to Tomorrow · 09:00", card.line)
        // Nothing happened until the tap.
        assertEquals(id, c.today.value.upNext?.id)
        assertEquals("Moved “Book dentist” to Tomorrow · 09:00", c.doAsk(card).line)
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).none { it.id == id })
    }

    @Test
    fun aQuestionAboutNavilifeItselfIsAnsweredFromTheBundledHelp() = runTest {
        // Ask knows how Navilife itself works: "flex mode" is answered on the device, with Take me there, no AI call.
        val c = core()
        val out = assertIs<AskOutcome.Answered>(c.askMeka("flex mode"))
        assertTrue(server.asked.isEmpty())
        assertTrue(out.answer.text.startsWith("Bedside clock: "), out.answer.text)
        assertEquals(os.meka.core.domain.HelpPlace.BEDSIDE, out.answer.help?.place)
        // Offline and with AI off it still answers.
        server.down = true
        assertIs<AskOutcome.Answered>(c.askMeka("How does the call assistant work?"))
        // On the Mac: the Mac's words, and no button for a Fold-only feature.
        c.helpOnMac = true
        val mac = assertIs<AskOutcome.Answered>(c.askMeka("bedside clock"))
        assertTrue(mac.answer.text.startsWith("Bedside clock is on the Fold only"), mac.answer.text)
        assertEquals(null, mac.answer.help)
        assertTrue(server.asked.isEmpty())
    }

    @Test
    fun stillStuckAsksTheAiPastTheHelpWithTheEntryAsContext() = runTest {
        // Slice 2: the help answer offers "Still stuck? Ask Navilife"; the tap asks the AI the same question.
        val c = core()
        val out = assertIs<AskOutcome.Answered>(c.askMeka("flex mode"))
        assertEquals("Still stuck? Ask Navilife", out.answer.stillStuck)
        assertTrue(server.asked.isEmpty())
        c.askBeyondHelp("flex mode")
        val (q, ctx) = server.asked.single()
        assertEquals("flex mode", q)
        val help = ctx.items.filter { it.kind == AskItemKind.HELP }
        assertTrue(help.isNotEmpty() && help.first().line.startsWith("Help · Bedside clock"), help.toString())
        // Offline it says so rather than repeating the help.
        server.down = true
        assertIs<AskOutcome.Unavailable>(c.askBeyondHelp("flex mode"))
    }

    @Test
    fun searchFindsTheHelpEntriesWithEachAppsPaths() = runTest {
        val c = core()
        c.search("flex")
        val fold = c.searchView.value.groups.single { it.kind == os.meka.core.domain.SearchKind.HELP }.hits.first()
        assertEquals(os.meka.core.domain.HelpPlace.BEDSIDE, fold.place)
        c.helpOnMac = true
        c.search("gmail")
        val mac = c.searchView.value.groups.single { it.kind == os.meka.core.domain.SearchKind.HELP }.hits.first { it.id == "help:mail" }
        assertEquals("Ask → Settings → Mail", mac.detail)
    }

    @Test
    fun aQuestionThatOnlyMentionsAFeatureTakesItsHelpToTheAi() = runTest {
        val c = core()
        c.askMeka("Can the call assistant take a message from my boss?")
        val ctx = server.asked.single().second
        val help = ctx.items.filter { it.kind == AskItemKind.HELP }
        assertTrue(help.isNotEmpty() && help.all { it.line.startsWith("Help · Call assistant") }, help.toString())
        // A question about the day carries no help lines.
        c.askMeka("What's on today?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.HELP })
    }

    @Test
    fun aQuestionCarriesTheSchoolYearAsSchoolLines() = runTest {
        val c = core()
        c.askMeka("When's half term?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.SCHOOL })
        // Rule 13, release 2: the children come from About you (invented here).
        c.addAboutYouChild("Max, U7"); c.addAboutYouChild("Felix, U10")
        c.addSchool("Half term 26–30 Oct")
        c.addSchool("Max PE Tue")
        c.askMeka("When's half term?")
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Day off · Half term · Mon 26 – Fri 30 Oct · Max and Felix", "Every Tuesday · PE · Max · next Tue 13 Oct"),
            ctx.items.filter { it.kind == AskItemKind.SCHOOL }.map { it.line },
        )
        assertFalse(ctx.untrusted)
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
            os.meka.core.domain.WeatherFields.PLACE to "Oakfield",
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
        assertEquals("now in Oakfield: 13° · light rain from 19:00", weather.first())
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
        // Rule 13: home and work are About you's (invented here), never the code's.
        c.setAboutYou(os.meka.core.domain.AboutField.HOME, "Oakfield")
        c.setAboutYou(os.meka.core.domain.AboutField.WORK, "Riverside")
        mirror(os.meka.core.domain.WeatherStore.ENTITY_ID, "Oakfield", 11, 20, "srvwx")
        mirror(os.meka.core.domain.WeatherStore.WORK_ENTITY_ID, "Riverside", 15, 14, "srvww")
        c.syncNow()
        assertEquals("Oakfield 11° now · Riverside 15°, light rain from 14:00 — take a coat", c.weatherView.value.nowLine)
        // The ring's rain follows where Meka will be: work's at 14:00, home's at 20:00.
        assertEquals(listOf(os.meka.core.domain.DayBand(14 * 60, 15 * 60), os.meka.core.domain.DayBand(20 * 60, 21 * 60)), c.today.value.dayRing.rain)

        c.askMeka("will it rain at work?")
        val weather = server.asked.single().second.items.filter { it.kind == os.meka.core.domain.AskItemKind.WEATHER }.map { it.line }
        assertEquals("now in Oakfield: 11° · light rain from 20:00", weather.first())
        assertTrue("now at work in Riverside: 15° · light rain from 14:00" in weather, weather.toString())

        // The work place is set from the app and reaches the server.
        assertEquals("Riverside", c.weatherView.value.workChoice.name)
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
        // Rule 13: the route is About you's Train route (an invented one).
        c.setAboutYouField(os.meka.core.domain.AboutYouFields.ROUTE, os.meka.core.testing.InventedRoute.TYPED)
        val clock = os.meka.core.sync.HlcClock("server", { now })
        // What the server's TfL mirror writes (Places item 4): South Rail in trouble, North Rail running.
        serverOps.append(os.meka.core.sync.Op("srvln0", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
            os.meka.core.domain.LineStatusStore.ENTITY_ID, os.meka.core.domain.LineStatusFields.LINES,
            os.meka.core.sync.FieldValue.Text("metro=10;north-rail=10;south-rail=6"), clock.now(), emptyList(), "server"))
        serverOps.append(os.meka.core.sync.Op("srvln1", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE,
            os.meka.core.domain.LineStatusStore.ENTITY_ID, os.meka.core.domain.LineStatusFields.CHECKED,
            os.meka.core.sync.FieldValue.Int64(1_791_525_600_000L), clock.now(), emptyList(), "server"))
        c.syncNow()
        val route = c.weatherView.value.route!!
        assertEquals("South Rail severe delays · Metro line good service — North Rail to Riverside is running", route.text)
        assertTrue(route.lit)
        // The same status is a heads-up once on this commute (Places item 4, before live departures).
        val gov = c.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("South Rail severe delays" to "North Rail to Riverside is running · TfL 07:00"),
            gov.post.filter { it.source == os.meka.core.domain.NoticeSource.TRAINS }.map { it.title to it.text })
        assertTrue(c.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL).post
            .none { it.source == os.meka.core.domain.NoticeSource.TRAINS })

        c.askMeka("are the trains ok?")
        val lines = server.asked.single().second.items.filter { it.kind == os.meka.core.domain.AskItemKind.WEATHER }.map { it.line }
        assertTrue("train lines on Meka's route (TfL status, checked 07:00): South Rail severe delays; North Rail good service; Metro line good service" in lines,
            lines.toString())
    }

    @Test
    fun tomorrowsTrainsOfferAnEarlierWakeAndOnlyATapMovesIt() = runTest {
        now = 1_791_482_400_000L // Thu 8 Oct 2026, 19:00 in London; Friday is an office day
        val c = core()
        val fri = 20_735L
        // The evening digest has gone already (otherwise a heads-up rides in it).
        val before = c.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        c.setAboutYouField(os.meka.core.domain.AboutYouFields.ROUTE, os.meka.core.testing.InventedRoute.TYPED)
        val clock = os.meka.core.sync.HlcClock("server", { now })
        fun server(id: String, field: String, v: os.meka.core.sync.FieldValue) = serverOps.append(os.meka.core.sync.Op(id, "hh",
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.LineStatusStore.ENTITY_ID, field, v, clock.now(), emptyList(), "server"))
        // What the server's evening read of tomorrow's plans writes (slice 7): a South Rail closure, North Rail running.
        server("srvpl0", os.meka.core.domain.LineStatusFields.PLANNED, os.meka.core.sync.FieldValue.Text("metro=10;north-rail=10;south-rail=4"))
        server("srvpl1", os.meka.core.domain.LineStatusFields.PLANNED_DAY, os.meka.core.sync.FieldValue.Int64(fri))
        server("srvpl2", os.meka.core.domain.LineStatusFields.PLANNED_CHECKED, os.meka.core.sync.FieldValue.Int64(1_791_480_600_000L))
        c.syncNow()
        val w = c.wakeView.value
        assertEquals(null, w.setMinute) // nothing moved by itself
        assertEquals("Wake 10 min earlier · 06:30", w.trains!!.actionLabel)
        assertEquals("Trains · South Rail planned closure tomorrow morning · North Rail to Riverside is running", w.trains!!.line)

        val gov = c.governNotifications(before.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)
        val n = gov.post.single { it.key == "wake-trains:$fri" }
        assertEquals("Wake at 06:30 instead of 06:40? · North Rail to Riverside is running", n.text)
        assertEquals(listOf(os.meka.core.domain.NoticeAction.WAKE_EARLIER), n.actions)

        // Another day's key changes nothing; the right one sets the alarm 10 minutes before the suggestion.
        assertEquals(null, c.wakeEarlierForTrains("wake-trains:${fri + 1}"))
        assertEquals(null, c.wakeView.value.setMinute)
        assertEquals("Wake alarm 06:30 · 10 min early for the trains", c.wakeEarlierForTrains(n.key))
        assertEquals(6 * 60 + 30, c.wakeView.value.setMinute)
        assertEquals("Work at 09:00 · 10 min early for the trains", c.nextAlarm.value?.line)
        assertEquals("Trains · South Rail planned closure tomorrow morning · alarm already 10 min early", c.wakeView.value.trains!!.line)
        // Once moved: no button, no second heads-up, and a second tap does nothing.
        assertEquals(null, c.wakeView.value.trains!!.actionLabel)
        assertEquals(null, c.wakeEarlierForTrains(null))
        assertTrue(c.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL).post.none { it.key == n.key })
    }

    @Test
    fun thisMorningsTrainCancelledMovesTheWakeAlarmOnlyOnATap() = runTest {
        now = 1_791_493_200_000L // Thu 8 Oct 2026, 22:00 in London; Friday is an office day
        val c = core()
        val fri = 20_735L
        c.setAboutYouField(os.meka.core.domain.AboutYouFields.ROUTE, os.meka.core.testing.InventedRoute.TYPED)
        c.setAboutYouField(os.meka.core.domain.AboutYouFields.STATION_WALK, "10, 15")
        assertTrue(c.setWake(6 * 60 + 40))
        now = 1_791_522_300_000L // Fri 9 Oct, 06:05
        val clock = os.meka.core.sync.HlcClock("server", { now })
        fun server(id: String, field: String, v: os.meka.core.sync.FieldValue) = serverOps.append(os.meka.core.sync.Op(id, "hh",
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.LineStatusStore.ENTITY_ID, field, v, clock.now(), emptyList(), "server"))
        // The server's 06:05 read of the morning board (live trains): the 07:42 cancelled.
        val board = listOf(
            os.meka.core.domain.TrainDeparture(7 * 60 + 30, 7 * 60 + 30),
            os.meka.core.domain.TrainDeparture(7 * 60 + 42, null, cancelled = true),
            os.meka.core.domain.TrainDeparture(7 * 60 + 54, 7 * 60 + 54),
        )
        server("srvbd0", os.meka.core.domain.LineStatusFields.DEPARTURES, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoardCodec.encode(board)))
        server("srvbd1", os.meka.core.domain.LineStatusFields.DEPARTURES_DIR, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoard.AM))
        server("srvbd2", os.meka.core.domain.LineStatusFields.DEPARTURES_CHECKED, os.meka.core.sync.FieldValue.Int64(now))
        server("srvbd3", os.meka.core.domain.LineStatusFields.BOARD_STATE, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoard.OK))
        server("srvbd4", os.meka.core.domain.LineStatusFields.BOARD_OK_AT, os.meka.core.sync.FieldValue.Int64(now))
        c.syncNow()
        // The alarm still rings at 06:40 (nothing moved by itself), saying the train to leave for.
        assertEquals("Leave by 07:20 for the 07:30 · 07:42 cancelled", c.nextAlarm.value?.line)
        // The wrong morning's key does nothing; the right one moves this morning's alarm to 06:20.
        assertEquals(null, c.wakeEarlierForTrains(os.meka.core.domain.TrainWakeRules.key(fri + 1, 7 * 60 + 30)))
        val key = os.meka.core.domain.TrainWakeRules.key(fri, 7 * 60 + 30)
        assertEquals("Wake alarm 06:20 · for the 07:30", c.wakeEarlierForTrains(key))
        assertEquals("06:20", c.nextAlarm.value?.timeLabel)
        assertEquals("Leave by 07:20 for the 07:30 · 07:42 cancelled", c.nextAlarm.value?.line)
        // Once moved: a second tap does nothing.
        assertEquals(null, c.wakeEarlierForTrains(key))
    }

    @Test
    fun theWeatherPlaceIsSetFromTheAppAndReachesTheServer() = runTest {
        val c = core()
        // No home in About you: the generic place, and where to add home (rule 13: no home in the code).
        assertEquals(os.meka.core.domain.WeatherPlaceView.NO_HOME, c.weatherView.value.placeChoice)
        c.setAboutYou(os.meka.core.domain.AboutField.HOME, "Oakfield")
        assertEquals("Oakfield", c.weatherView.value.placeChoice.name)
        assertTrue(!c.setWeatherPlace("<nope>"))
        assertTrue(c.setWeatherPlace("Bedford"))
        assertEquals("Finding “Bedford”… the forecast follows within a few minutes", c.weatherView.value.placeChoice.line)
        c.syncNow()
        // The server reads it from the op log (Weather place setting).
        val sent = serverOps.after("hh", 0, 10_000).map { it.op }
            .single { it.entityId == os.meka.core.domain.WeatherPlaceStore.ENTITY_ID && it.field == os.meka.core.domain.WeatherPlaceFields.NAME }
        assertEquals(os.meka.core.sync.FieldValue.Text("Bedford"), sent.value)
        // Home again: blank goes back to About you's home.
        assertTrue(c.setWeatherPlace(""))
        assertEquals("Oakfield", c.weatherView.value.placeChoice.name)
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
        assertEquals(AskOutcome.Unavailable("Navilife's AI is off"), c.askMeka("hi"))
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
    fun askHearsTheWeeksDinnersAndPlanDinnerPlansAFavouriteOrANewOneWithUndo() = runTest {
        val c = core()
        c.askMeka("What's for dinner?")
        assertTrue(server.asked.last().second.items.none { it.kind == os.meka.core.domain.AskItemKind.MEALS })
        c.addMeal("Chilli: mince, kidney beans, rice")
        val chilli = c.mealsView.value.favourites.single().id
        c.planMeal(c.mealsView.value.week[0].day, chilli)

        server.reply = AskReply.Answered("Done.", listOf(
            AskRawAction("plan_dinner", title = "chilli", date = "2026-10-10"),
            AskRawAction("plan_dinner", title = "Pizza", date = "2026-10-09"),
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Plan chilli for Saturday and pizza tomorrow"))
        val line = server.asked.last().second.items.single { it.kind == os.meka.core.domain.AskItemKind.MEALS }.line
        assertTrue(line.startsWith("Dinners · tonight: Chilli · Tomorrow: not planned"), line)
        assertEquals(listOf("Dinner · Chilli for Sat 10 Oct", "Dinner · Pizza for tomorrow · new favourite"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(listOf("Chilli", null, null), c.mealsView.value.week.take(3).map { it.title })

        val sat = c.doAsk(out.answer.cards[0])
        assertEquals("Planned Chilli for Sat 10 Oct", sat.line)
        val pizza = c.doAsk(out.answer.cards[1])
        assertEquals("Planned Pizza for tomorrow", pizza.line)
        assertEquals(listOf("Chilli", "Pizza", "Chilli"), c.mealsView.value.week.take(3).map { it.title })
        assertEquals(listOf("Chilli", "Pizza"), c.mealsView.value.favourites.map { it.title })

        // Undo: the new favourite goes with its day; Saturday is empty again.
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackDinner>(pizza.undo)))
        assertEquals(listOf("Chilli"), c.mealsView.value.favourites.map { it.title })
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackDinner>(sat.undo)))
        assertEquals(listOf("Chilli", null, null), c.mealsView.value.week.take(3).map { it.title })
        // Changed since: nothing left to take back.
        assertFalse(c.undoAsk(sat.undo!!))
    }

    @Test
    fun askHearsTheGiftsAndNotesAnIdeaWithUndo() = runTest {
        val c = core()
        c.askMeka("When's Mum's birthday?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.GIFTS })
        c.addOccasion("Mum's birthday 3 Nov")

        server.reply = AskReply.Answered("Noted.", listOf(AskRawAction("add_gift_idea", title = "Mum: silk scarf")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Note a silk scarf as a gift idea for Mum"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Gifts · 1 birthday or anniversary", "Mum's birthday · Tue 3 Nov · in 26 days · order by Fri 30 Oct · 3 days' delivery · no gift ideas yet"),
            ctx.items.filter { it.kind == AskItemKind.GIFTS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Gift idea for Mum's birthday · silk scarf"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertTrue(c.giftsView.value.rows.single().ideas.isEmpty())

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Noted silk scarf for Mum's birthday", done.line)
        assertEquals(listOf("silk scarf"), c.giftsView.value.rows.single().ideas.map { it.text })
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo takes the idea off, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveGiftIdea>(done.undo)))
        assertTrue(c.giftsView.value.rows.single().ideas.isEmpty())
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsThePeopleAndAddsAPromiseWithUndo() = runTest {
        val c = core()
        c.askMeka("What did I promise Theo?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.PEOPLE })
        c.addPromise("Fix his bike", "Theo", null)

        server.reply = AskReply.Answered("Added.", listOf(AskRawAction("add_promise", title = "theo: lift to football")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("I told Theo I'd give him a lift to football"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("People · 1 person", "Theo · you promised 1", "Theo · you promised: Fix his bike · since today"),
            ctx.items.filter { it.kind == AskItemKind.PEOPLE }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Promise to Theo · Lift to football"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(1, c.listsView.value.promised.size)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Added your promise to Theo: Lift to football", done.line)
        assertEquals(listOf("Fix his bike", "Lift to football"), c.listsView.value.promised.map { it.title }.sorted())
        assertEquals("You promised 2", c.peopleView.value.rows.single().owedLine)
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo takes the promise off, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemovePromise>(done.undo)))
        assertEquals(listOf("Fix his bike"), c.listsView.value.promised.map { it.title })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheTripsAndAddsABookingWithUndo() = runTest {
        val c = core()
        c.askMeka("When do we fly to Barcelona?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.TRIPS })
        c.addTrip("Barcelona 14–16 Oct")

        server.reply = AskReply.Answered("Added.", listOf(AskRawAction("add_booking", title = "Flight BA478 Wed 14 Oct 07:40 ref ABC123")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Add our flight BA478 on the 14th at 7:40, ref ABC123"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Trips · 1 on now or coming", "Trip · Barcelona · Wed 14 – Fri 16 Oct · 3 days · in 6 days · nothing booked yet", "Barcelona · local time 1 h ahead of UK time · the way there in UK time"),
            ctx.items.filter { it.kind == AskItemKind.TRIPS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Add to Barcelona · Flight BA478 · Wed 14 Oct 07:40 · ref ABC123"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertTrue(c.tripsView.value.rows.single().timeline.all { it.lines.isEmpty() })

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Added to Barcelona · Flight BA478 · Wed 14 Oct 07:40 · ref ABC123", done.line)
        assertEquals("Wed 14 – Fri 16 Oct · 3 days · 1 booking · 1 h ahead of UK time", os.meka.core.domain.DateGlue.plain(c.tripsView.value.rows.single().line))
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo takes the booking off, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveBooking>(done.undo)))
        assertEquals("Wed 14 – Fri 16 Oct · 3 days · 1 h ahead of UK time", os.meka.core.domain.DateGlue.plain(c.tripsView.value.rows.single().line))
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheLifeAdminHourAndSkipsThisWeeksWithUndo() = runTest {
        val c = core()
        c.askMeka("When's my admin hour?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.LIFE_ADMIN })
        c.setLifeAdmin(6, 10 * 60)
        c.addTask("Call the GP")

        server.reply = AskReply.Answered("Done.", listOf(AskRawAction("skip_life_admin")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Skip admin this week"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Life admin hour · Saturdays from 10:00 · this week Sat 10 Oct 10:00–11:00 · 1 job: Call the GP"),
            ctx.items.filter { it.kind == AskItemKind.LIFE_ADMIN }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Skip this week's life admin hour · Sat 10 Oct 10:00–11:00"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertFalse(c.lifeAdminView.value.skipped)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Skipped this week's life admin hour · Sat 10 Oct is free again", done.line)
        assertTrue(c.lifeAdminView.value.skipped)
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo keeps it again, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.KeepLifeAdmin>(done.undo)))
        assertFalse(c.lifeAdminView.value.skipped)
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheComingDateNightsAndSkipDateNightSkipsOneWithUndo() = runTest {
        val c = core()
        c.askMeka("When's date night?")
        assertTrue(server.asked.last().second.items.none { it.kind == os.meka.core.domain.AskItemKind.DATE_NIGHT })
        val fri9 = os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 9)
        c.setDateNight(5, 19 * 60, fri9)

        server.reply = AskReply.Answered("Done.", listOf(
            AskRawAction("skip_date_night", date = "2026-10-23"),
            AskRawAction("skip_date_night", date = "2026-10-16"), // not a date night: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Skip date night in a fortnight"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Date night · every other Friday from 19:00 · coming: Fri 9 Oct (tomorrow), Fri 23 Oct, Fri 6 Nov, Fri 20 Nov"),
            ctx.items.filter { it.kind == os.meka.core.domain.AskItemKind.DATE_NIGHT }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Skip date night · Fri 23 Oct"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertTrue(c.dateNightView.value.nights.none { it.skipped })

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Skipped Fri 23 Oct · the evening is free to plan", done.line)
        assertEquals(listOf(false, true, false, false), c.dateNightView.value.nights.map { it.skipped })
        // Done twice: the night is already skipped.
        assertFailsWith<os.meka.core.domain.ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo keeps it clear again, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.KeepDateNight>(done.undo)))
        assertTrue(c.dateNightView.value.nights.none { it.skipped })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheRadarAndDoneRenewalMovesAJobOnWithUndo() = runTest {
        val c = core()
        c.askMeka("When's the boiler service?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.RENEWALS })
        val alarms = c.addHomeUpkeep("alarms")!!
        val nov1 = os.meka.core.domain.CivilDate.toEpochDay(2026, 11, 1)
        val dec1 = os.meka.core.domain.CivilDate.toEpochDay(2026, 12, 1)

        server.reply = AskReply.Answered("Nice one.", listOf(
            AskRawAction("done_renewal", title = "Test the smoke and CO alarms"),
            AskRawAction("done_renewal", title = "Boiler service"), // not on the radar: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("I've tested the smoke alarms"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Radar · Test the smoke and CO alarms · home upkeep · due Sun 1 Nov · every month"),
            ctx.items.filter { it.kind == AskItemKind.RENEWALS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Done · Test the smoke and CO alarms · next Tue 1 Dec"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(nov1, c.listsView.value.renewals.all.single { it.id == alarms }.dueDay)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("“Test the smoke and CO alarms” done · next Tue 1 Dec", done.line)
        assertEquals(dec1, c.listsView.value.renewals.all.single { it.id == alarms }.dueDay)
        // Done twice: it has moved on.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        // Undo puts it back, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackRenewal>(done.undo)))
        assertEquals(nov1, c.listsView.value.renewals.all.single { it.id == alarms }.dueDay)
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheWinsLogAndAddWinKeepsALineWithUndo() = runTest {
        val c = core()
        c.askMeka("Show my wins this quarter")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.WINS })
        c.addWin("Closed the Henley deal")

        server.reply = AskReply.Answered("Nice.", listOf(
            AskRawAction("add_win", title = "Ran the planning session"),
            AskRawAction("add_win", title = "closed the henley deal"), // already this week: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Add a win: ran the planning session"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Wins · this quarter, Oct – Dec · 1 kept · 1 this week", "Win · Thu 8 Oct · Closed the Henley deal"),
            ctx.items.filter { it.kind == AskItemKind.WINS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Keep in this week's wins · “Ran the planning session”"), out.answer.cards.map { it.line })
        assertEquals(1, c.winsView.value.week.size) // nothing until tapped

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Kept “Ran the planning session” in this week's wins", done.line)
        assertEquals(setOf("Closed the Henley deal", "Ran the planning session"), c.winsView.value.week.map { it.text }.toSet())
        // Twice: already there.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }
        // Undo takes it off, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveWin>(done.undo)))
        assertEquals(listOf("Closed the Henley deal"), c.winsView.value.week.map { it.text })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheTrainQueueAndQueueDoneMarksALinkWithUndo() = runTest {
        val c = core()
        c.askMeka("What's in my train queue?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.QUEUE })
        val saved = c.addToQueue("https://www.bbc.co.uk/news/uk-123", "Why trains run late")
        assertFalse(saved.refused)

        server.reply = AskReply.Answered("One article.", listOf(
            AskRawAction("queue_done", title = "the BBC one"),
            AskRawAction("queue_done", title = "Something never saved"), // nothing fits: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("I've read the BBC one"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Train queue · 1 article waiting", "Waiting · Article · bbc.co.uk · Why trains run late · saved Thu 8 Oct"),
            ctx.items.filter { it.kind == AskItemKind.QUEUE }.map { it.line },
        )
        // A saved link's title is someone else's words.
        assertTrue(ctx.untrusted)
        assertEquals(listOf("Done · “Why trains run late” · off the train queue"), out.answer.cards.map { it.line })
        assertEquals(1, c.queueView.value.waiting.size) // nothing until tapped

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Marked “Why trains run late” done · off the train queue", done.line)
        assertTrue(c.queueView.value.waiting.isEmpty())
        assertEquals(listOf("Why trains run late"), c.queueView.value.done.map { it.title })
        // Twice: it no longer waits.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }
        // Undo puts it back, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackQueue>(done.undo)))
        assertEquals(listOf("Why trains run late"), c.queueView.value.waiting.map { it.title })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheLeaveAndBookLeaveBooksDaysOffWithUndo() = runTest {
        val c = core()
        assertEquals("Booked Annual leave · Tue 27 – Fri 30 Oct · 4 days", c.addLeave("27–30 Oct")?.line)
        server.reply = AskReply.Answered("You've 21 days left.", listOf(
            AskRawAction("book_leave", title = "Mon 2 Nov", date = "2026-11-02"),
            AskRawAction("book_leave", title = "28 Oct"), // already booked: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("How many days have I got left? Book the 2nd of November off"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Annual leave · 4 of 25 days booked · 21 days left · Leave year Jan – Dec 2026 · 4 to come",
                "Booked · Annual leave · Tue 27 – Fri 30 Oct · 4 days"),
            ctx.items.filter { it.kind == AskItemKind.LEAVE }.map { it.line },
        )
        assertEquals(listOf("Book annual leave · Mon 2 Nov · 1 day"), out.answer.cards.map { it.line })
        assertEquals(1, c.leaveView.value.booked.size) // nothing until tapped

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Booked annual leave · Mon 2 Nov · 1 day", done.line)
        assertEquals("5 of 25 days booked · 20 days left", c.leaveView.value.summary)
        // Twice: the day is booked already.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }
        // Undo takes the stretch off, once.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveLeave>(done.undo)))
        assertEquals("4 of 25 days booked · 21 days left", c.leaveView.value.summary)
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsProtectedTimeSoIsSaturdayProtectedHasAnAnswer() = runTest {
        val c = core()
        server.reply = AskReply.Answered("Yes, Saturday's kept free.", emptyList())
        assertIs<AskOutcome.Answered>(c.askMeka("Is Saturday protected?"))
        // Off: one line, so "no" is an answer too.
        assertEquals(
            listOf("Protected time · off · the planner may use evenings and weekends"),
            server.asked.last().second.items.filter { it.kind == AskItemKind.PROTECTED }.map { it.line },
        )
        c.setProtectedTime(evenings = true, fromMin = 19 * 60, weekends = true)
        assertIs<AskOutcome.Answered>(c.askMeka("Is Saturday protected?"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Protected time · evenings from 19:00, weekends and bank holidays · nothing is planned into them",
                "Protected today · from 19:00 tonight",
                "Whole days protected · Sat 10 Oct, Sun 11 Oct, Sat 17 Oct, Sun 18 Oct"),
            ctx.items.filter { it.kind == AskItemKind.PROTECTED }.map { it.line },
        )
        assertFalse(ctx.untrusted)
    }

    @Test
    fun askHearsOnlyTheFactsThatBearOnTheQuestionAndNeverOneKeptFromAi() = runTest {
        val c = core()
        assertEquals("Added Car reg to Car", c.addFact("Car reg: AB12 CDE").line)
        now += 1_000
        assertEquals("Added Shoe size to Sizes", c.addFact("my shoe size is 10").line)
        now += 1_000
        assertEquals("Added GP to Health and care", c.addFact("GP: Dr Patel, Ivel Medical Centre").line)
        assertTrue(c.addFact("Bank card: 4111 1111 1111 1111").refused)
        assertEquals("3 facts · Navilife answers from them", c.factsView.value.summary)

        assertIs<AskOutcome.Answered>(c.askMeka("What's the car's reg?"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Facts · 3 kept · 1 bears on the question", "Fact · Car · Car reg: AB12 CDE"),
            ctx.items.filter { it.kind == AskItemKind.FACTS }.map { it.line },
        )
        assertFalse(ctx.untrusted)

        // Kept from AI: still on the page, never sent.
        val car = c.factsView.value.groups.first { it.group == "car" }.rows.single()
        assertTrue(c.setFactAi(car.id, use = false))
        assertEquals("3 facts · Navilife answers from 2 · 1 kept from AI", c.factsView.value.summary)
        assertIs<AskOutcome.Answered>(c.askMeka("What do you know about me?"))
        assertEquals(
            listOf("Facts · 2 kept · all below", "Fact · Sizes · Shoe size: 10", "Fact · Health and care · GP: Dr Patel, Ivel Medical Centre"),
            server.asked.last().second.items.filter { it.kind == AskItemKind.FACTS }.map { it.line },
        )
        assertTrue(c.removeFact(car.id))
        assertEquals(2, c.factsView.value.count)
    }

    @Test
    fun askHearsTheAppointmentsAndSearchFindsThem() = runTest {
        val c = core()
        c.askMeka("When's my dentist?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.APPOINTMENTS })
        assertFalse(c.typeAppointment("Dentist every 6 months, booked Tue 20 Oct 9:30").refused)
        c.askMeka("When's my dentist?")
        val ctx = server.asked.last().second
        val lines = ctx.items.filter { it.kind == AskItemKind.APPOINTMENTS }.map { it.line }
        assertEquals("Appointments · 1 appointment · all in hand", lines.first())
        assertTrue(lines[1].startsWith("Dentist · every 6 months · Booked · Tue 20 Oct 09:30"), lines[1])
        assertFalse(ctx.untrusted)
        c.search("dent")
        val hit = c.searchView.value.groups.single().hits.single()
        assertEquals(os.meka.core.domain.SearchTarget.APPOINTMENTS, hit.target)
        assertEquals("Booked · Tue 20 Oct 09:30", hit.detail)
    }

    @Test
    fun askHearsTheChecklistsAndAddChecklistAddsOnTheTapWithUndo() = runTest {
        val c = core()
        fun steps(id: String) = (listOfNotNull(c.today.value.upNext) + c.today.value.yourDay + c.today.value.needsYou.map { it.task })
            .single { it.id == id }.checklist
        val id = c.addTask("Party")
        val ids = c.addSteps(id, "balloons, cake")
        c.tickSteps(listOf(ids[1]))

        server.reply = AskReply.Answered("Balloons are left.", listOf(
            AskRawAction("add_checklist", title = "party: eggs, cake"),
            AskRawAction("add_checklist", title = "Party: balloons"), // already waiting: no card
            AskRawAction("add_checklist", title = "Barbecue: eggs"), // no such task: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("What's left on the party checklist? Add eggs and cake"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Checklists · 1 task with checklists", "Checklist “Party” · 1 of 2 ticked · to tick: balloons"),
            ctx.items.filter { it.kind == AskItemKind.CHECKLISTS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Add to “Party” · eggs and cake"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(2, steps(id).size)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Added eggs and cake to “Party”", done.line)
        // Cake was ticked: it comes back unticked; eggs is new.
        assertEquals(listOf("balloons" to false, "cake" to false, "eggs" to false), steps(id).map { it.text to it.checked })
        // Tapping again finds nothing new.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        assertTrue(c.undoAsk(assertIs<AskUndo.TakeBackChecklist>(done.undo)))
        assertEquals(listOf("balloons" to false, "cake" to true), steps(id).map { it.text to it.checked })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askHearsTheGoalsAndAddGoalAddsOnTheTapWithUndo() = runTest {
        val c = core()
        val g = c.addGoal("Save £2,000", null, os.meka.core.domain.GoalHorizon.YEAR)
        c.addGoalMilestone(g, "First £500")

        server.reply = AskReply.Answered("Saving is at 0%.", listOf(
            AskRawAction("add_goal", title = "Run 5k", date = "2026-12-31"),
            AskRawAction("add_goal", title = "save £2,000"), // already a goal: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("How are my goals? Add a goal to run 5k by December"))
        val ctx = server.asked.last().second
        assertEquals(
            listOf("Goals · 1 open", "Goal “Save £2,000” · 0% · This year · 0 of 1 milestone · Next milestone · First £500"),
            ctx.items.filter { it.kind == AskItemKind.GOALS }.map { it.line },
        )
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Add goal “Run 5k” · This quarter · by Thu 31 Dec"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(1, c.goalsView.value.goals.size)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Added goal “Run 5k”", done.line)
        val added = c.goalsView.value.goals.single { it.title == "Run 5k" }
        assertEquals(os.meka.core.domain.GoalHorizon.QUARTER, added.horizon)
        assertEquals(os.meka.core.domain.CivilDate.toEpochDay(2026, 12, 31), added.dueDay)
        // Tapping again finds it already there.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }

        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveGoal>(done.undo)))
        assertEquals(listOf("Save £2,000"), c.goalsView.value.goals.map { it.title })
        assertFalse(c.undoAsk(done.undo!!))

        // A goal Meka has started on (a milestone added) isn't lost to a late Undo.
        val again = c.doAsk(out.answer.cards.single())
        val id = c.goalsView.value.goals.single { it.title == "Run 5k" }.id
        c.addGoalMilestone(id, "Run 2k")
        assertFalse(c.undoAsk(again.undo!!))
        assertEquals(2, c.goalsView.value.goals.size)
    }

    @Test
    fun askHearsTheErrandsAndAddErrandAddsOnTheTapWithUndoAndSearchFindsIt() = runTest {
        val c = core()
        c.askMeka("What do I need at Tesco?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.ERRANDS })
        c.addErrand("Tesco: milk")
        now += 1_000 // the card's errand comes after milk in the list

        server.reply = AskReply.Answered("Milk so far.", listOf(
            AskRawAction("add_errand", title = "tesco: batteries"),
            AskRawAction("add_errand", title = "Tesco: milk"), // already waiting: no card
            AskRawAction("add_errand", title = "batteries"), // no place: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("What do I need at Tesco? Add batteries"))
        val ctx = server.asked.last().second
        assertEquals(listOf("Errands · 1 errand at Tesco", "At Tesco · milk"), ctx.items.filter { it.kind == AskItemKind.ERRANDS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertEquals(listOf("Errand at Tesco · batteries"), out.answer.cards.map { it.line })
        // Nothing until tapped.
        assertEquals(1, c.errandsView.value.count)

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("At Tesco: batteries", done.line)
        assertEquals(listOf("milk", "batteries"), c.errandsView.value.places.single().rows.map { it.title })
        c.search("batt")
        val hit = c.searchView.value.groups.single().hits.single()
        assertEquals(os.meka.core.domain.SearchTarget.ERRANDS, hit.target)
        assertEquals("At Tesco", hit.detail)

        assertTrue(c.undoAsk(assertIs<AskUndo.TakeBackErrands>(done.undo)))
        assertEquals(listOf("milk"), c.errandsView.value.places.single().rows.map { it.title })
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun rememberThatKeepsAFactOnTheTapWithUndo() = runTest {
        val c = core()
        c.addFact("GP: Dr Patel")
        server.reply = AskReply.Answered("I'll keep that.", listOf(
            AskRawAction("add_fact", title = "Shoe size: 10"),
            AskRawAction("add_fact", title = "GP: Dr Shah"),
            AskRawAction("add_fact", title = "Wifi password: hunter22"), // refused by the page: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Remember that my shoe size is 10 and my GP is Dr Shah now"))
        assertEquals(
            listOf("Keep on What Navilife knows · Shoe size: 10", "Update on What Navilife knows · GP: Dr Shah"),
            out.answer.cards.map { it.line },
        )
        assertEquals(1, c.factsView.value.count) // nothing until tapped

        val kept = c.doAsk(out.answer.cards[0])
        assertEquals("Kept Shoe size: 10 on What Navilife knows", kept.line)
        val updated = c.doAsk(out.answer.cards[1])
        assertEquals("Updated GP on What Navilife knows", updated.line)
        assertEquals(setOf("Shoe size: 10", "GP: Dr Shah"), c.factsView.value.groups.flatMap { g -> g.rows.map { "${it.label}: ${it.value}" } }.toSet())
        // Twice: already there.
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards[0]) }

        // Undo: the new fact comes off, the changed one gets its old value back; once each.
        assertTrue(c.undoAsk(assertIs<AskUndo.RemoveFact>(kept.undo)))
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackFact>(updated.undo)))
        assertEquals(listOf("GP: Dr Patel"), c.factsView.value.groups.flatMap { g -> g.rows.map { "${it.label}: ${it.value}" } })
        assertFalse(c.undoAsk(kept.undo!!))
        assertFalse(c.undoAsk(updated.undo!!))
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
        // AI layer completeness slice 2: the month per feature unfolds in Settings; a refused key reads red in Health.
        server.status = AiStatusReply("on", null, 205, 2000, "ok", month = listOf(
            os.meka.core.domain.AiMonthUse("mail.sort", 300, 60), os.meka.core.domain.AiMonthUse("ask.talk", 40, 145),
        ))
        val spend = c.aiStatus()
        assertEquals(listOf("Talk", "Mail"), spend.spend.map { it.label })
        assertEquals("This month by feature · $2.05 in all", spend.spendHeading)
        server.status = AiStatusReply("failing", "Anthropic refused the key", 205, 2000, "ok", keyRefused = true)
        assertTrue(c.aiStatus().refused)
        server.status = null
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        server.down = true
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        assertEquals(AskRules.STATUS_NOT_CONNECTED, core(transport = null).aiStatus())
    }

    @Test
    fun eachAiFeatureSaysWhatHappensWhileTheAiIsOff() = runTest {
        val c = core()
        val brief = os.meka.core.domain.AiFeature.BRIEF
        assertNull(c.aiFeatureLine(brief))
        // Within two minutes the status last asked is reused; after them it is asked again.
        server.status = AiStatusReply("off", "No AI key set", 0, 2000, "ok")
        assertNull(c.aiFeatureLine(brief))
        now += MekaCore.AI_SEEN_MS
        assertEquals("Navilife's AI is off · read from your day as it is, in Navilife's own words", c.aiFeatureLine(brief))
        // Settings asking refreshes it at once.
        server.status = AiStatusReply("on", null, 2000, 2000, "over")
        assertEquals("Until the 1st", c.aiStatus().fallbackHeading)
        assertTrue(c.aiFeatureLine(os.meka.core.domain.AiFeature.DRAFTS)!!.startsWith("This month's AI budget is used up till the 1st · "))
        server.down = true
        now += MekaCore.AI_SEEN_MS
        assertTrue(c.aiFeatureLine(brief)!!.startsWith("Can't reach Navilife's AI just now · "))
        assertTrue(core(transport = null).aiFeatureLine(brief)!!.startsWith("Navilife's AI works once this device is connected · "))
    }

    @Test
    fun activitysDailyCountComesFromTheServersDay() = runTest {
        val c = core()
        // Nothing asked today (or an older server that doesn't count days): no line.
        assertEquals(null, c.aiTodayLine())
        server.status = AiStatusReply(
            "on", null, 120, 2000, "ok",
            listOf(os.meka.core.domain.AiDayUse("ask", 2, 6_000), os.meka.core.domain.AiDayUse("ask.talk", 6, 12_000)),
        )
        assertEquals("Navilife's AI today · Talk 6 · Ask 2 · 2¢", c.aiTodayLine())
        server.down = true
        assertEquals(null, c.aiTodayLine())
        assertEquals(null, core(transport = null).aiTodayLine())
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
        assertEquals(TalkEffect.Speak("Right, done. Moved Book dentist to tomorrow at 09:00. Anything else?"), step.effects.single())
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

    @Test
    fun askHearsTheBinsAndTheirComingCollections() = runTest {
        val c = core()
        c.askMeka("Which bin is it this week?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.BINS })
        c.addBin("Black bin Tue fortnightly from 13 Oct")
        c.askMeka("Which bin is it this week?")
        val ctx = server.asked.last().second
        assertEquals(listOf(
            "Bins · 1 bin · typed by Meka",
            "Black bin · every other Tuesday · next Tue 13 Oct (next week) · then Tue 27 Oct",
            "Collection · Tue 13 Oct (next week) · Black bin · out the evening before",
            "Collection · Tue 27 Oct · Black bin · out the evening before",
        ), ctx.items.filter { it.kind == AskItemKind.BINS }.map { it.line })
        assertFalse(ctx.untrusted)
    }

    @Test
    fun askHearsTheChildrenOnlyWhenAskedAndAnswersSizesWithoutTheAi() = runTest {
        val c = core()
        c.askMeka("What size shoes is Alex?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.KIDS }) // no children yet
        c.addAboutYouChild("Alex, U7, Oakfield School")
        c.addAboutYouChild("Sam, U10")
        c.setKidSize("Alex", "shoes", "13")
        c.addKidFee("Sam", "Football £35 monthly from 1 Nov")
        c.askMeka("What size shoes is Alex?")
        val ctx = server.asked.last().second
        val kids = ctx.items.filter { it.kind == AskItemKind.KIDS }.map { it.line }
        assertEquals("Kids · 2 children: Alex and Sam", kids[0])
        assertTrue(kids[1].startsWith("Alex · ") && kids[1].contains("sizes: Shoes 13 · Sizes checked today"), kids[1])
        assertTrue(kids[2].startsWith("Sam · ") && kids[2].endsWith("no sizes kept"), kids[2])
        assertEquals("Sam's club fee · Football · £35 · every month · next Sun 1 Nov", kids[3])
        assertTrue(kids.any { it.startsWith("This week · ") })
        assertFalse(ctx.untrusted)
        // A question about something else carries none of it.
        c.askMeka("When's the MOT?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.KIDS })
        // Without the AI the device answers a size itself.
        server.down = true
        val out = assertIs<AskOutcome.Answered>(c.askMeka("What size shoes is Alex?"))
        assertEquals("Alex's shoes: 13 · sizes checked today.", out.answer.text)
        val coat = assertIs<AskOutcome.Answered>(c.askMeka("what coat size does Sam take"))
        assertEquals("Sam's coat size isn't kept yet · add it in Ask → Life → Kids.", coat.answer.text)
    }

    @Test
    fun askHearsTheCarAndItsDates() = runTest {
        val c = core()
        c.askMeka("When's the MOT?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.CAR })
        assertTrue(c.setCarRegistration("ab12 cde"))
        assertNotNull(c.addCarDate("mot"))
        c.askMeka("When's the MOT?")
        val ctx = server.asked.last().second
        val car = ctx.items.filter { it.kind == AskItemKind.CAR }.map { it.line }
        assertEquals("Car · AB12 CDE · 1 of 4 dates on the radar", car.first())
        assertEquals(os.meka.core.domain.CarAskRules.NO_ANSWER, car[1])
        assertTrue(car[2].startsWith("MOT · On the radar"))
        assertEquals("Car tax · not on the radar", car[3])
        assertFalse(ctx.untrusted)
    }

    @Test
    fun theCommuteAndTheWakeAlarmGoWithEveryQuestionAndAreAnsweredWithoutTheAi() = runTest {
        // The commute counts everywhere, slice 5: Thu 8 Oct 17:15, tomorrow an office day.
        val c = core()
        assertTrue(c.setCommute(75, 90))
        c.askMeka("When do I need to leave tomorrow?")
        val ctx = server.asked.last().second
        val lines = ctx.items.filter { it.kind == AskItemKind.COMMUTE }.map { it.line }
        assertEquals("Commute · 1 h 15 to work · 1 h 30 home · door to door, office days only", lines.first())
        assertTrue(lines[1].startsWith("Today · Thu 8 Oct · office day"), lines[1])
        assertEquals("Tomorrow · Fri 9 Oct · office day · leave 07:45 · work 09:00–17:30 · home about 19:00", lines[2])
        assertEquals("Wake alarm · Tomorrow · Fri 9 Oct · not set · suggested 06:45 (Work at 09:00 · 1 h 15 commute · 1 h to get ready)", lines.last())
        assertFalse(ctx.untrusted)
        // No AI (on the train, say): the device answers from the same numbers.
        server.reply = AskReply.Unavailable("off", null)
        val wake = assertIs<AskOutcome.Answered>(c.askMeka("What time should I get up?"))
        assertEquals("Get up at about 06:45 tomorrow: Work at 09:00 · 1 h 15 commute · 1 h to get ready.", wake.answer.text)
        server.down = true
        now += 15 * 3_600_000L // Fri 08:15: on the way in
        val home = assertIs<AskOutcome.Answered>(c.askMeka("when will I be home?"))
        assertEquals("Work ends at 17:30, so you're home about 19:00.", home.answer.text)
        assertEquals(AskOutcome.Unavailable(AskRules.OFFLINE_LINE), c.askMeka("hi"))
    }

    @Test
    fun aNotificationsQuestionCarriesThePhonesTraceAndIsAnsweredWithoutTheAi() = runTest {
        val c = core()
        val posted = os.meka.core.domain.NotifyTraceEntry(now - 3_600_000, os.meka.core.domain.NotifyTraceKind.POSTED, "1 notice on Heads-ups · next look 18:00")
        var health = os.meka.core.domain.NotificationHealth(
            allowed = true,
            channels = listOf(os.meka.core.domain.NotifyChannelState("meka.heads_up", "Heads-ups", true, now - 3_600_000)),
            trace = listOf(posted),
        )
        c.notificationHealthSource = { health }
        // Another question carries none of it.
        c.askMeka("What's on today?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.NOTIFICATIONS })
        c.askMeka("Why didn't I get a notification?")
        val ctx = server.asked.last().second
        val lines = ctx.items.filter { it.kind == AskItemKind.NOTIFICATIONS }.map { it.line }
        assertEquals("Notifications on this phone · on for Navilife", lines.first())
        assertEquals("Last notification · sent today at 16:15 on Heads-ups", lines[1])
        assertTrue(lines.any { it.startsWith("Digests · Most things wait for") })
        assertTrue(lines.last().startsWith("Trace · ") && "Posted 1 notice on Heads-ups" in lines.last())
        assertFalse(ctx.untrusted)
        // AI off: the phone answers from its own trace.
        server.reply = AskReply.Unavailable("off", null)
        health = health.copy(allowed = false)
        val off = assertIs<AskOutcome.Answered>(c.askMeka("why no notifications?"))
        assertTrue(off.answer.text.startsWith("Notifications are off for Navilife on this phone"))
        assertTrue(off.answer.cards.isEmpty())
        server.down = true
        health = health.copy(allowed = true)
        val quiet = assertIs<AskOutcome.Answered>(c.askMeka("Why didn't I get a notification?"))
        assertTrue(quiet.answer.text.startsWith("The last one went today at 16:15, on Heads-ups."))
        // Not about notifications, or a device with no trace (the Mac): the usual line.
        assertEquals(AskOutcome.Unavailable(AskRules.OFFLINE_LINE), c.askMeka("hi"))
        c.notificationHealthSource = null
        assertEquals(AskOutcome.Unavailable(AskRules.OFFLINE_LINE), c.askMeka("why no notifications?"))
    }

    @Test
    fun aMailQuestionCarriesTheEmailsThatNeedHimAsUntrustedAndIsAnsweredWithoutTheAi() = runTest {
        // Inbox autopilot, slice 9a: the server's Needs-you emails reach the device; only a mail question carries them.
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        fun email(message: String, from: String, subject: String, agoMs: Long) {
            val id = os.meka.core.domain.MailNeedsRules.itemId("acc1", message)
            os.meka.core.domain.MailNeedsRules.serverFields("Personal", from, null, subject, "Hi there", now - agoMs, 1).forEach { (f, v) ->
                serverOps.append(os.meka.core.sync.Op("srvmi${k++}", "hh", os.meka.core.domain.EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
            }
        }
        email("m1", "Oakfield School", "Trip forms due Friday", 3_600_000L)
        email("m2", "Sam Taylor", "Invoice for October", 7_200_000L)
        val c = core()
        assertTrue(c.syncNow())
        c.askMeka("What's on today?")
        assertTrue(server.asked.last().second.items.none { it.kind == AskItemKind.MAIL })
        c.askMeka("What did the school say?")
        val ctx = server.asked.last().second
        val lines = ctx.items.filter { it.kind == AskItemKind.MAIL }.map { it.line }
        assertEquals("Mail · 2 need him · Personal 2 · emails sorted as needing him, last 14 days", lines.first())
        assertTrue(lines[1].startsWith("Oakfield School · Personal · 16:15 · “Trip forms due Friday”"), lines[1])
        assertTrue(ctx.untrusted)
        // No AI: the device answers from the same emails.
        server.reply = AskReply.Unavailable("off", null)
        val out = assertIs<AskOutcome.Answered>(c.askMeka("anything important in my email?"))
        assertEquals("2 emails need you: Oakfield School about “Trip forms due Friday”, Sam Taylor about “Invoice for October”.", out.answer.text)
        // The brief says how many and who from.
        assertEquals(2, c.briefView.value.story.mail?.count)
        assertEquals(listOf("Oakfield School", "Sam Taylor"), c.briefView.value.story.mail?.senders)
    }
}
