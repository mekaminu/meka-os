package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.LiveCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ada's Live mode, slice 1: the device asks for a session token and reports seconds, never failing. */
class LiveFacadeTest {
    private val meter = LiveCodec.Meter(true, "ok", 40, 1_500)

    private class Server(service: SyncService, private val meter: LiveCodec.Meter) : SyncTransport, LiveApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var down = false
        val reports = mutableListOf<Triple<String, Long, Long>>()
        val days = mutableListOf<LiveCodec.Day?>()
        val searches = mutableListOf<LiveCodec.SearchRequest>()
        override suspend fun liveSearch(request: LiveCodec.SearchRequest): LiveCodec.SearchResult? {
            if (down) throw TransportException("offline")
            searches += request
            return LiveCodec.SearchResult(LiveCodec.SearchResult.FOUND, listOf(LiveCodec.SearchHit("Strike off", "https://bbc.co.uk/1", "Talks")))
        }
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun liveStatus(): LiveCodec.Meter? { if (down) throw TransportException("offline"); return meter }
        val resumes = mutableListOf<String?>()
        override suspend fun liveSession(voice: String?, day: LiveCodec.Day?, resume: String?): LiveCodec.Session? {
            if (down) throw TransportException("offline")
            days += day
            resumes += resume
            return LiveCodec.Session(LiveCodec.Session.READY, meter, "ls_1", "auth_tokens/t", "gemini-3.8-live", "wss://x", voice ?: "Kore", 1, 2)
        }
        override suspend fun liveUsage(sessionId: String, inSeconds: Long, outSeconds: Long): LiveCodec.Usage? {
            if (down) throw TransportException("offline")
            reports += Triple(sessionId, inSeconds, outSeconds)
            return LiveCodec.Usage(true, meter)
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()), meter)

    private fun core(transport: SyncTransport = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 1_792_632_000_000L },
    )

    @Test
    fun aSessionAndItsSeconds() = runTest {
        val c = core()
        assertEquals(meter, c.liveStatus())
        val s = c.liveSession("Leda")!!
        assertEquals(LiveCodec.Session.READY, s.state)
        assertEquals("Leda", s.voice)
        assertEquals(true, c.liveUsage("ls_1", 90, 30)?.counted)
        assertEquals(listOf(Triple("ls_1", 90L, 30L)), server.reports)
    }

    @Test
    fun offlineOrAnOlderServerIsNullAndNormalAdaAnswers() = runTest {
        server.down = true
        val c = core()
        assertNull(c.liveStatus())
        assertNull(c.liveSession(null))
        assertNull(c.liveUsage("ls_1", 1, 1))
        val plain = object : SyncTransport by os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())) {}
        assertNull(core(plain).liveSession(null))
    }

    @Test
    fun theLiveSwitchAndVoiceAreKeptAndTheSettingsReadTheMeter() = runTest {
        // Slice 2.
        val c = core()
        assertEquals(false, c.liveOn())
        assertEquals(os.meka.core.domain.LiveTalkRules.OFF_LINE, c.liveSettings().line)
        assertEquals("£0.40 of £15 this month", c.liveSettings().meterLine)
        c.setLiveOn(true)
        assertEquals(true, c.setLiveVoice("Aoede"))
        assertEquals(false, c.setLiveVoice("Nobody"))
        val on = c.liveSettings()
        assertEquals(true, on.on)
        assertEquals("£0.40 of £15 this month", on.line)
        assertEquals("Aoede", on.voices.single { it.selected }.name)
        server.down = true
        assertEquals(os.meka.core.domain.LiveTalkRules.UNREACHABLE, c.liveSettings().line)
    }

    /** Slice 2b: a server with Navilife's voice too, answering in [answerVoice] (Polly standing in when Google is off). */
    private class Voiced(service: SyncService, meter: LiveCodec.Meter, var answerVoice: String?) : SyncTransport, LiveApi, SpeechApi {
        private val live = Server(service, meter)
        var spoken = 0
        override suspend fun push(request: PushRequest) = live.push(request)
        override suspend fun pull(request: PullRequest) = live.pull(request)
        override suspend fun liveStatus() = live.liveStatus()
        override suspend fun liveSession(voice: String?, day: LiveCodec.Day?, resume: String?) = live.liveSession(voice, day, resume)
        override suspend fun liveUsage(sessionId: String, inSeconds: Long, outSeconds: Long) = live.liveUsage(sessionId, inSeconds, outSeconds)
        override suspend fun speak(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response {
            spoken++
            return os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.SPOKEN, "QUJD", "mp3", answerVoice ?: voice, "chirp3hd")
        }
        override suspend fun speechVoices() = os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.FAILED)
    }

    @Test
    fun aLiveVoicesSampleIsThatVoiceOrNothingAndActivitySaysTheMonth() = runTest {
        val m = meter.copy(month = "2026-10", sessions = 2, seconds = 300)
        val voiced = Voiced(SyncService(InMemoryServerOpStore()), m, answerVoice = null)
        val c = core(voiced)
        assertEquals("QUJD", c.liveVoiceSample("leda"))
        assertEquals("QUJD", c.liveVoiceSample("Leda")) // kept: no second clip
        assertEquals(1, voiced.spoken)
        assertNull(c.liveVoiceSample("Brian")) // not a Live voice
        voiced.answerVoice = "Amy" // Google's voices off: Polly would stand in, so no sample
        assertNull(c.liveVoiceSample("Kore"))
        assertNull(core(server).liveVoiceSample("Kore")) // no voice on this server
        assertEquals("Ada's Live · 2 talks · 5 min · £0.40 of £15 in October", c.liveActivityLine())
        server.down = true
        assertNull(core(server).liveActivityLine())
    }

    @Test
    fun slice4TheSessionAsksWithTheCompactDayAndAdaMayIntroduceHerselfOnce() = runTest {
        val c = core()
        c.liveSession("Kore")
        val day = server.days.single()!!
        assertEquals("2026-10-22", day.date)
        assertEquals(true, day.now.startsWith("Thursday 22 October 2026"))
        assertEquals(true, day.items.all { it.kind in os.meka.core.wire.AskCodec.KINDS })
        assertEquals(true, day.tools) // slice 5: this device answers Navilife's own tools
        assertEquals(true, day.items.size <= LiveCodec.MAX_DAY_ITEMS)
        assertEquals(true, day.introduce) // the day's first conversation
        assertNull(day.callMe)
        // The encoded request is one the server accepts.
        assertEquals(day, LiveCodec.decodeSessionRequest(LiveCodec.encodeSessionRequest(LiveCodec.SessionRequest("Kore", day))).day)
        c.setCompanionCallsSir(true)
        c.liveSession("Kore")
        assertEquals(false, server.days.last()!!.introduce) // once a day
        assertEquals(os.meka.core.wire.AskCodec.CALL_SIR, server.days.last()!!.callMe)
    }

    @Test
    fun slice4ALiveSearchAboutHisLifeNeverLeavesThePhone() = runTest {
        val c = core()
        c.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Jordan")
        c.addAboutYouChild("Alex, U7, Oakfield School")
        assertEquals(LiveCodec.SearchResult.FOUND, c.liveSearch("ls_1", "rail strike").state)
        assertEquals(LiveCodec.SearchResult.REFUSED, c.liveSearch("ls_1", "Oakfield term dates").state)
        assertEquals(LiveCodec.SearchResult.REFUSED, c.liveSearch("ls_1", "alex football").state)
        assertEquals(LiveCodec.SearchResult.REFUSED, c.liveSearch("ls_1", "my train").state)
        assertEquals(listOf(LiveCodec.SearchRequest("ls_1", "rail strike")), server.searches)
        server.down = true
        assertEquals(LiveCodec.SearchResult.FAILED, c.liveSearch("ls_1", "rail strike").state)
    }

    @Test
    fun slice5AdaLooksUpAnotherDayAndOffersCardsThroughTheGate() = runTest {
        val c = core()
        c.liveSession("Kore")
        val id = c.addTask("Book the MOT")
        c.setWhen(id, 20_749, 600) // tomorrow at 10:00
        val lines = c.liveLookUp("tomorrow")
        val taskLine = lines.lines().single { it.startsWith("task ") }
        kotlin.test.assertTrue(taskLine.startsWith("task t1: Book the MOT"), lines)
        kotlin.test.assertTrue(lines.lines().any { it.startsWith("days: ") }, lines)
        assertEquals(os.meka.core.domain.LiveToolRules.NO_DAYS, c.liveLookUp("whenever"))
        // A handle from the look-up can be named back, and stays the same task when looked up again.
        assertEquals(taskLine, c.liveLookUp("tomorrow").lines().single { it.startsWith("task ") })
        val move = c.livePropose(mapOf("kind" to "move_task", "ref" to "t1", "date" to "2026-10-24", "time" to "09:30"))
        val card = move.card!!
        kotlin.test.assertTrue(card.proposal is os.meka.core.domain.AskProposal.MoveTask)
        kotlin.test.assertTrue(move.reply.startsWith("Shown on his screen"), move.reply)
        val add = c.livePropose(mapOf("kind" to "add_task", "title" to "Ring the garage", "date" to "2026-10-23"))
        assertEquals(os.meka.core.domain.AskProposal.AddTask("Ring the garage", 20_749, null), add.card?.proposal)
        // Nothing runs by itself: the task is still where it was until he taps the card.
        assertEquals(1, c.liveLookUp("tomorrow").lines().count { it.startsWith("task ") })
        // Not a kind Navilife offers, or a handle it never sent: no card.
        assertNull(c.livePropose(mapOf("kind" to "send_email", "title" to "hi")).card)
        assertEquals(os.meka.core.domain.LiveToolRules.PROPOSE_UNUSABLE, c.livePropose(mapOf("kind" to "complete_task", "ref" to "t9")).reply)
        // Tapped, it does what Ask's card does.
        c.doAsk(card)
        assertEquals(0, c.liveLookUp("tomorrow").lines().count { it.startsWith("task ") })
    }

    @Test
    fun slice2cLiveAfterTheHelloKnowsTheGreetingAndAFreshWindowSendsTheSameDay() = runTest {
        val c = core()
        c.addTask("Book the MOT")
        c.liveSession("Kore", greeted = "Morning.\n  The MOT is on today.")
        val first = server.days.last()!!
        assertEquals("Morning. The MOT is on today.", first.greeted)
        assertEquals(true, first.introduce)
        assertNull(server.resumes.last())
        // A handle the session gave: the same day again (same handles), no greeting, no introduction.
        c.addTask("Ring the garage")
        c.liveSession("Kore", resume = "CjQKMgo-handle_1")
        val again = server.days.last()!!
        assertEquals("CjQKMgo-handle_1", server.resumes.last())
        assertEquals(first.items, again.items)
        assertNull(again.greeted)
        assertEquals(false, again.introduce)
        // Not a handle: a new conversation, built afresh.
        c.liveSession("Kore", greeted = "Hi.", resume = "bad handle")
        assertNull(server.resumes.last())
        assertEquals("Hi.", server.days.last()!!.greeted)
    }
}
