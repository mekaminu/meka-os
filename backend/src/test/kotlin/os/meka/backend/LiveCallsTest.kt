package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LiveModeRules
import os.meka.core.domain.MekaVoiceStore
import os.meka.core.domain.MekaVoiceFields
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.PutThroughRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, slice 1: callers talk to Obi live (phone stream ↔ server ↔ Gemini Live). */
class LiveCallsTest {
    private val ops = InMemoryServerOpStore()
    private var nowMs = 1_794_000_000_000L // 2026-11
    private var seq = 0
    private val store = InMemoryLiveCallStore()
    private var keyIn = true
    private val line = LiveCallLine(FieldReader.scanning(ops), store, configured = { keyIn }, nowMs = { nowMs })
    private val woken = mutableListOf<String>()
    private val urgent = mutableListOf<String>()
    private val assistant = CallAssistant(
        ops, FieldReader.scanning(ops), household = { "hh" }, now = { nowMs },
        onWritten = { woken += it }, onUrgent = { urgent += it }, liveLine = line,
    )

    private fun set(field: String, value: FieldValue) {
        ops.append(Op("w${seq++}", "hh", EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, field, value, Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
    }

    private fun on() { set(WorkFields.CALL_ASSISTANT, FieldValue.Bool(true)); set(WorkFields.CALL_LIVE, FieldValue.Bool(true)) }

    private fun field(id: String, f: String) = FieldReader.scanning(ops).latest("hh", EntityTypes.HELD_MESSAGE, id, f)

    @Test
    fun muLawRoundTripsAndResamples() {
        val pcm = shortArrayOf(0, 1_000, -1_000, 8_000, -8_000, 32_000, -32_000)
        val back = CallAudio.muLawToPcm(CallAudio.pcmToMuLaw(pcm))
        pcm.indices.forEach { i -> assertTrue(kotlin.math.abs(back[i] - pcm[i]) <= kotlin.math.abs(pcm[i].toInt()) / 16 + 16, "sample $i: ${back[i]} vs ${pcm[i]}") }
        assertEquals(14, CallAudio.up8to16(pcm).size)
        assertEquals(500, CallAudio.up8to16(shortArrayOf(0, 1_000))[1].toInt())
        assertEquals(pcm.toList(), CallAudio.pcmShorts(CallAudio.pcmBytes(pcm)).toList())
        // 160 μ-law bytes (20 ms) in → 320 samples at 16 kHz → 640 bytes.
        val in20ms = Base64.getEncoder().encodeToString(ByteArray(160) { 0x7F })
        assertEquals(640, Base64.getDecoder().decode(CallAudio.callerToModel(in20ms)).size)
    }

    @Test
    fun downsamplingCarriesAcrossChunks() {
        val d = CallAudio.Down24to8()
        val samples = CallAudio.pcmBytes(ShortArray(9) { 3_000 })
        // Split mid-sample and mid-triple: 9 samples in, 3 out in total.
        val a = d.push(samples.copyOfRange(0, 7))
        val b = d.push(samples.copyOfRange(7, samples.size))
        assertEquals(3, a.size + b.size)
        val out = CallAudio.muLawToPcm(a + b)
        out.forEach { assertTrue(kotlin.math.abs(it - 3_000) < 200) }
    }

    @Test
    fun aCallGoesLiveOnlyWithTheSwitchTheKeyAndUnderTheCap() {
        set(WorkFields.CALL_ASSISTANT, FieldValue.Bool(true))
        assertIs<VoiceReply.AskWho>(assistant.handle("twilio", VoiceEvent.Incoming("CA1", "+447700900123")))
        set(WorkFields.CALL_LIVE, FieldValue.Bool(true))
        keyIn = false
        assertIs<VoiceReply.AskWho>(assistant.handle("twilio", VoiceEvent.Incoming("CA2", "+447700900123")))
        keyIn = true
        assertIs<VoiceReply.LiveStream>(assistant.handle("twilio", VoiceEvent.Incoming("CA3", "+447700900123")))
        // £20 spent this month (a big row): the script again.
        store.add(LiveCallRow("x", "hh", LiveCallRules.month(nowMs), nowMs, 60, 30_000_000))
        assertIs<VoiceReply.AskWho>(assistant.handle("twilio", VoiceEvent.Incoming("CA4", "+447700900123")))
        // A higher cap lets it through again.
        set(WorkFields.CALL_LIVE_CAP, FieldValue.Int64(50))
        assertIs<VoiceReply.LiveStream>(assistant.handle("twilio", VoiceEvent.Incoming("CA5", "+447700900123")))
    }

    @Test
    fun theStreamMarkupCarriesTheTicketAndFallsBackAfter() {
        on()
        val reply = assistant.handle("twilio", VoiceEvent.Incoming("CA1", "+447700900123")) as VoiceReply.LiveStream
        val (_, xml) = TwilioVoice(authToken = { "t" }).render(reply, "https://meka.example/")
        assertTrue("""<Connect><Stream url="wss://meka.example/v1/voice/twilio/stream">""" in xml, xml)
        assertTrue("""<Parameter name="ticket" value="${reply.ticket}"/>""" in xml)
        assertTrue("""<Redirect method="POST">https://meka.example/v1/voice/twilio/live-ended</Redirect>""" in xml)
        assertEquals(VoiceEvent.LiveEnded("CA1", "+1"), TwilioVoice(authToken = { "t" }).parse(VoiceStep.LIVE_ENDED, mapOf("CallSid" to "CA1", "From" to "+1")))
        // Never went live: the script takes the call. Went live: it's over.
        assertIs<VoiceReply.AskWho>(assistant.handle("twilio", VoiceEvent.LiveEnded("CA1", "+447700900123")))
        line.markLive("CA1")
        assertEquals(VoiceReply.HangUp, assistant.handle("twilio", VoiceEvent.LiveEnded("CA1", "+447700900123")))
    }

    @Test
    fun aTicketIsOneUseForItsCallAndExpires() {
        on()
        val t = line.ticket("hh", "twilio", "CA1", null, true)!!
        assertNull(line.claim(t, "CA2"))
        val t2 = line.ticket("hh", "twilio", "CA1", null, true)!!
        assertNotNull(line.claim(t2, "CA1"))
        assertNull(line.claim(t2, "CA1"))
        val t3 = line.ticket("hh", "twilio", "CA9", null, true)!!
        nowMs += LiveCallRules.TICKET_MS + 1
        assertNull(line.claim(t3, "CA9"))
        assertNull(line.claim(null, "CA9"))
    }

    /** One Obi's voice (slice 2): a call Obi answers live speaks in the voice picked in Settings → Voice. */
    @Test
    fun aLiveCallSpeaksInObisPickedVoice() {
        on()
        val t = line.claim(line.ticket("hh", "twilio", "CA1", null, true), "CA1")!!
        assertEquals(LiveModeRules.DEFAULT_VOICE, t.voice)
        ops.append(Op("v${seq++}", "hh", EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID, MekaVoiceFields.LIVE_VOICE, FieldValue.Text("Orus"), Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
        assertEquals("Orus", line.claim(line.ticket("hh", "twilio", "CA2", null, true), "CA2")!!.voice)
        // A name that isn't one of Live's voices: the default.
        ops.append(Op("v${seq++}", "hh", EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID, MekaVoiceFields.LIVE_VOICE, FieldValue.Text("Brian"), Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
        assertEquals(LiveModeRules.DEFAULT_VOICE, line.obiVoice("hh"))
        assertTrue("\"voiceName\":\"Orus\"" in LiveCallSession.setup("m", "Orus", "hi"))
    }

    /** Google's side, faked: what the session sent, and a way to answer. */
    private class FakeModel : ModelDial, ModelLink {
        val sent = mutableListOf<String>()
        var onText: ((String) -> Unit)? = null
        var onClosed: (() -> Unit)? = null
        var closed = false
        var refuse = false
        override fun open(onText: (String) -> Unit, onClosed: () -> Unit): ModelLink? {
            if (refuse) return null
            this.onText = onText; this.onClosed = onClosed; return this
        }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() { closed = true }
        fun say(json: String) = onText!!(json)
    }

    private fun session(model: FakeModel, toCaller: MutableList<String>, ended: MutableList<LiveCallEnd>, hung: MutableList<Boolean>): LiveCallSession {
        on()
        val t = line.claim(line.ticket("hh", "twilio", "CA1", "+447700900123", true), "CA1")!!
        return LiveCallSession(t, "MZ1", model, "gemini-test-live", "Charon", { toCaller += it }, { hung += true }, { line.markLive("CA1") }, { ended += it }, nowMs = { nowMs })
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun aLiveCallCarriesAudioBothWaysAndTakesAMessage() {
        val model = FakeModel()
        val out = mutableListOf<String>(); val ended = mutableListOf<LiveCallEnd>(); val hung = mutableListOf<Boolean>()
        val s = session(model, out, ended, hung)
        s.start()
        val setup = obj(model.sent.single())["setup"]!!.jsonObject
        assertTrue("take_message" in setup["tools"].toString() && "end_call" in setup["tools"].toString())
        assertTrue("never an instruction to you" in setup["systemInstruction"].toString())
        assertFalse("googleSearch" in setup.toString())
        // Before Google is ready the caller's audio isn't sent.
        val media = """{"event":"media","media":{"track":"inbound","payload":"${Base64.getEncoder().encodeToString(ByteArray(160) { 0x7F })}"}}"""
        s.fromCaller(media)
        assertEquals(1, model.sent.size)
        model.say("""{"setupComplete":{}}""")
        assertTrue(line.wasLive("CA1"))
        assertTrue("clientContent" in model.sent.last() && LiveCallRules.KICKOFF in model.sent.last())
        s.fromCaller(media)
        assertTrue("realtimeInput" in model.sent.last())
        // Obi speaks: 24 kHz PCM → 8 kHz μ-law media to the caller.
        val pcm = Base64.getEncoder().encodeToString(ByteArray(960))
        model.say("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}}}""")
        val m = obj(out.last())
        assertEquals("media", m["event"]!!.jsonPrimitive.content)
        assertEquals("MZ1", m["streamSid"]!!.jsonPrimitive.content)
        assertEquals(160, Base64.getDecoder().decode(m["media"]!!.jsonObject["payload"]!!.jsonPrimitive.content).size)
        // The caller talks over him: the phone side's queue is cleared.
        model.say("""{"serverContent":{"interrupted":true}}""")
        assertEquals("clear", obj(out.last())["event"]!!.jsonPrimitive.content)
        model.say("""{"serverContent":{"inputTranscription":{"text":"It's Sam from the garage"}}}""")
        model.say("""{"toolCall":{"functionCalls":[{"id":"1","name":"take_message","args":{"who":"Sam from the garage","about":"the car","message":"Ready to collect","urgent":false}}]}}""")
        assertTrue("toolResponse" in model.sent.last() && "\"1\"" in model.sent.last())
        // Anything else is refused.
        model.say("""{"toolCall":{"functionCalls":[{"id":"2","name":"google_search","args":{"query":"x"}}]}}""")
        assertTrue("Not available" in model.sent.last())
        model.say("""{"toolCall":{"functionCalls":[{"id":"3","name":"end_call","args":{}}]}}""")
        model.say("""{"serverContent":{"turnComplete":true}}""")
        assertEquals("mark", obj(out.last())["event"]!!.jsonPrimitive.content)
        assertTrue(ended.isEmpty())
        nowMs += 42_000
        s.fromCaller("""{"event":"mark","mark":{"name":"${LiveCallSession.BYE_MARK}"}}""")
        val end = ended.single()
        assertTrue(end.live)
        assertEquals(42, end.seconds)
        assertEquals("Sam from the garage", end.message!!.who)
        assertTrue("Sam" in end.heard)
        assertTrue(model.closed && hung.single())
        // Once only.
        s.callerGone(); s.tick()
        assertEquals(1, ended.size)

        // Written as a voice message, and the month's meter for both apps.
        val meter = line.record(line.claim(line.ticket("hh", "twilio", "CA1", "+447700900123", true), "CA1")!!.copy(token = "ticket-a"), end)
        assertEquals(1, meter.calls)
        assertTrue(meter.spentPence > 0)
        val t = LiveCallTicket("tk", "hh", "twilio", "CA1", "+447700900123", true, "", nowMs)
        assistant.liveEnded(t, end, meter)
        val id = CallAssistantRules.heldId("twilio", "CA1")
        assertEquals(FieldValue.Text("Ready to collect"), field(id, HeldMessageFields.TEXT))
        assertEquals(FieldValue.Text("Sam from the garage · the car"), field(id, HeldMessageFields.INTRO))
        assertNull(field(id, HeldMessageFields.URGENT))
        assertEquals(listOf("hh"), woken)
        val spent = FieldReader.scanning(ops).latest("hh", EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, LiveCallRules.METER_PENCE)
        assertEquals(FieldValue.Int64(meter.spentPence), spent)
        // A retry writes nothing more.
        assistant.liveEnded(t, end, meter)
        assertEquals(1, woken.size)
    }

    @Test
    fun anUrgentMessageWakesAtOnceButAScamNeverBreaksThrough() {
        val id = CallAssistantRules.heldId("twilio", "CA7")
        val t = LiveCallTicket("tk", "hh", "twilio", "CA7", "+447700900123", true, "", nowMs)
        val meter = LiveCallRules.Meter(LiveCallRules.month(nowMs), 4, 1)
        assistant.liveEnded(t, LiveCallEnd(true, 30, 10, LiveCallRules.message(mapOf("who" to "Sam", "about" to "the boiler", "urgent" to "true")), ""), meter)
        assertEquals(FieldValue.Bool(true), field(id, HeldMessageFields.URGENT))
        assertEquals(listOf("hh"), urgent)
        val t2 = t.copy(callId = "CA8")
        assistant.liveEnded(t2, LiveCallEnd(true, 30, 10, LiveCallRules.message(mapOf("who" to "the bank", "about" to "fraud", "urgent" to "true", "scam" to "true")), ""), meter)
        assertNull(field(CallAssistantRules.heldId("twilio", "CA8"), HeldMessageFields.URGENT))
        assertEquals(1, urgent.size)
    }

    @Test
    fun googleNotAnsweringEndsTheStreamForTheScript() {
        val model = FakeModel()
        val out = mutableListOf<String>(); val ended = mutableListOf<LiveCallEnd>(); val hung = mutableListOf<Boolean>()
        val s = session(model, out, ended, hung)
        s.start()
        nowMs += LiveCallRules.CONNECT_TIMEOUT_MS + 1
        s.tick()
        assertFalse(ended.single().live)
        assertTrue(hung.single())
        assertFalse(line.wasLive("CA1"))
        // Nothing written for a call that never went live.
        assistant.liveEnded(LiveCallTicket("tk", "hh", "twilio", "CA1", null, true, "", nowMs), ended.single(), LiveCallRules.Meter("x", 0, 0))
        assertTrue(ops.after("hh", 0, 1_000).none { it.op.entityType == EntityTypes.HELD_MESSAGE })

        val refused = FakeModel().also { it.refuse = true }
        val ended2 = mutableListOf<LiveCallEnd>()
        session(refused, mutableListOf(), ended2, mutableListOf()).start()
        assertFalse(ended2.single().live)
    }

    @Test
    fun theCallIsWrappedUpThenEndedAtFiveMinutes() {
        val model = FakeModel()
        val ended = mutableListOf<LiveCallEnd>()
        val s = session(model, mutableListOf(), ended, mutableListOf())
        s.start()
        model.say("""{"setupComplete":{}}""")
        nowMs += LiveCallRules.WRAP_UP_SECONDS * 1_000L
        s.tick()
        assertTrue(LiveCallRules.WRAP_UP in model.sent.last())
        nowMs += (LiveCallRules.MAX_SECONDS - LiveCallRules.WRAP_UP_SECONDS) * 1_000L
        s.tick()
        assertEquals(LiveCallRules.MAX_SECONDS.toLong(), ended.single().seconds)
    }

    @Test
    fun aCallerWhoSaysNothingIsAHangUp() {
        val t = LiveCallTicket("tk", "hh", "twilio", "CA3", "+447700900123", true, "", nowMs)
        assistant.liveEnded(t, LiveCallEnd(true, 8, 3, null, "hm"), LiveCallRules.Meter("x", 0, 1))
        assertNull(field(CallAssistantRules.heldId("twilio", "CA3"), HeldMessageFields.KIND))
        assertEquals(FieldValue.Int64(1), FieldReader.scanning(ops).latest("hh", EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, LiveCallRules.METER_CALLS))
    }

    private fun profile(field: String, value: String) {
        ops.append(Op("p${seq++}", "hh", EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, field, FieldValue.Text(value), Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
    }

    private val urgentCall = """{"toolCall":{"functionCalls":[{"id":"1","name":"take_message","args":{"who":"Sam from Oakfield School","about":"Alex has had a fall","message":"Please call the office","urgent":true}}]}}"""

    @Test
    fun aGenuineUrgentLiveCallerIsToldToHoldAndPutThrough() {
        on()
        profile(AboutYouFields.MOBILE, "07700 900456")
        val reply = assistant.handle("twilio", VoiceEvent.Incoming("CA5", "+447700900123", "+447700900999")) as VoiceReply.LiveStream
        val t = line.claim(reply.ticket, "CA5")!!
        assertTrue(t.canPutThrough)
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val order = mutableListOf<String>()
        val s = LiveCallSession(t, "MZ5", model, "gemini-test-live", "Charon", {}, { order += "hangUp" }, { line.markLive("CA5") },
            { e -> order += "ended"; ended += e; line.ended("CA5", e) }, nowMs = { nowMs })
        s.start()
        model.say("""{"setupComplete":{}}""")
        model.say(urgentCall)
        assertTrue(LiveCallRules.PUT_THROUGH_ANSWER.take(30) in model.sent.last(), model.sent.last())
        model.say("""{"toolCall":{"functionCalls":[{"id":"2","name":"end_call","args":{}}]}}""")
        nowMs += 40_000
        s.tick()
        // The outcome is kept before the phone side is told the stream is over.
        assertEquals(listOf("ended", "hangUp"), order)
        assertTrue(ended.single().putThrough)
        val put = assistant.handle("twilio", VoiceEvent.LiveEnded("CA5", "+447700900123", "+447700900999"))
        assertEquals(VoiceReply.PutThrough("+447700900456", "+447700900999", toldToHold = true), put)
        val (_, xml) = TwilioVoice(authToken = { "t" }).render(put, "https://meka.example/")
        assertFalse(PutThroughRules.HOLD in xml, "Obi already asked them to hold")
        assertTrue("<Dial" in xml && "+447700900456" in xml)
        // His mobile answers before the message is written: the live outcome vouches for the whisper.
        val whisper = assistant.handle("twilio", VoiceEvent.Whisper("CL1", "CA5")) as VoiceReply.Whisper
        assertTrue("Oakfield School" in whisper.text)
        // The usual message, marked urgent, and what happened once he took it.
        assistant.liveEnded(t, ended.single(), line.record(t, ended.single()))
        val id = CallAssistantRules.heldId("twilio", "CA5")
        assertEquals(FieldValue.Bool(true), field(id, HeldMessageFields.URGENT))
        assertEquals(VoiceReply.Connect, assistant.handle("twilio", VoiceEvent.WhisperAnswered("CL1", "CA5", "1")))
        assertEquals(FieldValue.Text(PutThroughRules.TAKEN), field(id, HeldMessageFields.PUT_THROUGH))
    }

    @Test
    fun withoutHisMobileAnUrgentLiveCallerJustLeavesTheMessage() {
        on()
        val reply = assistant.handle("twilio", VoiceEvent.Incoming("CA6", "+447700900123", "+447700900999")) as VoiceReply.LiveStream
        val t = line.claim(reply.ticket, "CA6")!!
        assertFalse(t.canPutThrough)
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = LiveCallSession(t, "MZ6", model, "m", "Charon", {}, {}, { line.markLive("CA6") }, { e -> ended += e; line.ended("CA6", e) }, nowMs = { nowMs })
        s.start()
        model.say("""{"setupComplete":{}}""")
        model.say(urgentCall)
        assertTrue(LiveCallRules.TAKEN_ANSWER in model.sent.last())
        s.callerGone()
        assertFalse(ended.single().putThrough)
        assertEquals(VoiceReply.HangUp, assistant.handle("twilio", VoiceEvent.LiveEnded("CA6", "+447700900123", "+447700900999")))
        // A whisper for a call nobody can vouch for is refused.
        assertEquals(VoiceReply.HangUp, assistant.handle("twilio", VoiceEvent.Whisper("CL2", "CA6")))
    }

    @Test
    fun aPutThroughTurnedDownAtTheEndSaysSo() {
        on()
        profile(AboutYouFields.MOBILE, "07700 900456")
        line.markLive("CA9")
        // Too short to be a person (an automated call), though Obi offered it.
        val m = LiveCallRules.message(mapOf("who" to "Sam from Oakfield School", "about" to "Alex has had a fall", "urgent" to "true"))
        line.ended("CA9", LiveCallEnd(true, 2, 1, m, "", putThrough = true))
        assertEquals(VoiceReply.Goodbye(LiveCallRules.NOT_PUT_THROUGH), assistant.handle("twilio", VoiceEvent.LiveEnded("CA9", "+447700900123", "+447700900999")))
        // His own mobile calling is never put through to itself.
        line.markLive("CA10")
        line.ended("CA10", LiveCallEnd(true, 30, 10, m, "", putThrough = true))
        assertEquals(VoiceReply.Goodbye(LiveCallRules.NOT_PUT_THROUGH), assistant.handle("twilio", VoiceEvent.LiveEnded("CA10", "+447700900456", "+447700900999")))
    }

    @Test
    fun aLiveCallerCanBeOfferedOneOfHisFreeTimesForACallback() {
        // Friday 6 Nov 21:20: nothing left today; Saturday clear of a 08:30–10:00 event, then Sunday morning.
        val sat = LondonCalendar.epochDayOf(nowMs) + 1
        val busy = os.meka.core.domain.CalendarEvent(
            id = "e1", title = "Swimming", startAtMs = LondonCalendar.toEpochMs(sat, 8 * 60 + 30), endAtMs = LondonCalendar.toEpochMs(sat, 10 * 60),
            allDay = false, location = null, provider = "google", account = null, calendarName = null,
        )
        val withEvents = LiveCallLine(FieldReader.scanning(ops), store, configured = { true }, nowMs = { nowMs }, events = { listOf(busy) })
        val talker = CallAssistant(ops, FieldReader.scanning(ops), household = { "hh" }, now = { nowMs }, liveLine = withEvents)
        on()
        val t = withEvents.claim(withEvents.ticket("hh", "twilio", "CB1", "+447700900123", false), "CB1")!!
        assertEquals(2, t.callbackSlots.size)
        assertTrue("\"tomorrow at 10:30\" or \"Sunday 8 November at 09:00\"" in t.instruction, t.instruction)
        assertFalse("Swimming" in t.instruction)
        val meter = LiveCallRules.Meter(LiveCallRules.month(nowMs), 4, 1)
        talker.liveEnded(t, LiveCallEnd(true, 60, 20, LiveCallRules.message(mapOf("who" to "Sam", "about" to "the boiler", "callback_time" to "tomorrow at 10:30")), ""), meter)
        val id = CallAssistantRules.heldId("twilio", "CB1")
        assertEquals(FieldValue.Int64(LondonCalendar.toEpochMs(sat, 10 * 60 + 30)), field(id, HeldMessageFields.CALLBACK_AT))
        assertEquals(FieldValue.Text("call back Sat 7 Nov, 10:30"), field(id, HeldMessageFields.CALLBACK_LINE))
        // A time the caller named that was never offered is not kept.
        val t2 = t.copy(callId = "CB2")
        talker.liveEnded(t2, LiveCallEnd(true, 60, 20, LiveCallRules.message(mapOf("who" to "Sam", "about" to "the boiler", "callback_time" to "Monday at 13:00")), ""), meter)
        assertNull(field(CallAssistantRules.heldId("twilio", "CB2"), HeldMessageFields.CALLBACK_AT))
        // Without "when you'll call back" allowed, no times are offered.
        set(WorkFields.CALL_KNOWS, FieldValue.Text("name"))
        val t3 = withEvents.claim(withEvents.ticket("hh", "twilio", "CB3", "+447700900123", false), "CB3")!!
        assertTrue(t3.callbackSlots.isEmpty())
        // Slice 4: with call back allowed but "Offer a callback slot" off, none either.
        set(WorkFields.CALL_KNOWS, FieldValue.Text("busy,back,name"))
        set(WorkFields.CALL_SLOTS, FieldValue.Bool(false))
        val t4 = withEvents.claim(withEvents.ticket("hh", "twilio", "CB4", "+447700900123", false), "CB4")!!
        assertTrue(t4.callbackSlots.isEmpty())
        assertTrue("Never promise a day or time" in t4.instruction)
    }

    @Test
    fun aBusinessRingingBackAboutABookingTalksToObiWhoKnowsIt() {
        // Rosa's Kitchen was rung yesterday and booked a table for Saturday at 19:30 under Alex.
        val sat = LondonCalendar.epochDayOf(nowMs) + 1
        val card = os.meka.core.domain.BookingCall(
            id = "bc1", business = "Rosa's Kitchen", number = "01632 960111", what = "Table for 4", day = sat, fromMin = 19 * 60, toMin = 20 * 60,
            people = 4, note = null, state = os.meka.core.domain.BookingCallState.BOOKED, askedAtMs = nowMs - 2 * 86_400_000L,
            approvedAtMs = nowMs - 2 * 86_400_000L, result = "Booked", cancelled = false, calledAtMs = nowMs - 86_400_000L,
            bookedMin = 19 * 60 + 30, bookedName = "Alex",
        )
        val withCards = LiveCallLine(FieldReader.scanning(ops), store, configured = { true }, nowMs = { nowMs }, bookings = { listOf(card) })
        val talker = CallAssistant(ops, FieldReader.scanning(ops), household = { "hh" }, now = { nowMs }, liveLine = withCards)
        // Live conversation off: the business still talks to Obi, because Booking calls is on (absent = on).
        set(WorkFields.CALL_ASSISTANT, FieldValue.Bool(true))
        assertIs<VoiceReply.AskWho>(talker.handle("twilio", VoiceEvent.Incoming("BB0", "+447700900123")))
        val reply = talker.handle("twilio", VoiceEvent.Incoming("BB1", "+441632960111")) as VoiceReply.LiveStream
        val t = withCards.claim(reply.ticket, "BB1")!!
        assertEquals("bc1", t.callbackFor)
        assertTrue("Rosa's Kitchen" in t.instruction && "booked for Saturday 7 November at 19:30, for 4 people, under the name Alex" in t.instruction, t.instruction)
        assertTrue("don't agree to anything" in t.instruction)
        val meter = LiveCallRules.Meter(LiveCallRules.month(nowMs), 4, 1)
        talker.liveEnded(t, LiveCallEnd(true, 60, 20, LiveCallRules.message(mapOf("who" to "Rosa's Kitchen", "about" to "the booking", "message" to "Can it be 20:00?")), ""), meter)
        val id = CallAssistantRules.heldId("twilio", "BB1")
        assertEquals(FieldValue.Text("bc1"), field(id, HeldMessageFields.BOOKING_ID))
        assertEquals(FieldValue.Text("Rosa's Kitchen"), field(id, HeldMessageFields.BOOKING_BUSINESS))
        val reader = FieldReader.scanning(ops)
        assertEquals(FieldValue.Int64(nowMs), reader.latest("hh", EntityTypes.BOOKING_CALL, "bc1", os.meka.core.domain.BookingCallFields.CALLED_BACK_AT))
        assertEquals(FieldValue.Text(id), reader.latest("hh", EntityTypes.BOOKING_CALL, "bc1", os.meka.core.domain.BookingCallFields.CALLED_BACK_HELD))
        // A judged scam from that number is never linked.
        val scam = t.copy(callId = "BB2")
        talker.liveEnded(scam, LiveCallEnd(true, 60, 20, LiveCallRules.message(mapOf("who" to "Rosa's", "about" to "pay a deposit", "scam" to "true")), ""), meter)
        assertNull(field(CallAssistantRules.heldId("twilio", "BB2"), HeldMessageFields.BOOKING_ID))
        // Booking calls off: the business hears the script like anyone else.
        set(WorkFields.CALL_BOOKING, FieldValue.Bool(false))
        assertIs<VoiceReply.AskWho>(talker.handle("twilio", VoiceEvent.Incoming("BB3", "+441632960111")))
        // The scripted message from that number still names the booking.
        set(WorkFields.CALL_BOOKING, FieldValue.Bool(true))
        talker.handle("twilio", VoiceEvent.Recorded("BB4", "+441632960111", 12))
        assertEquals(FieldValue.Text("Rosa's Kitchen"), field(CallAssistantRules.heldId("twilio", "BB4"), HeldMessageFields.BOOKING_BUSINESS))
    }
}
