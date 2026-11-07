package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.AskProposal
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.BookingCalls
import os.meka.core.domain.BookingDialRules
import os.meka.core.domain.BookingLiveRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, slice 2b: the server rings an approved call card with Obi live on its brief. Invented data only. */
class BookingCallerTest {
    private val today = CivilDate.toEpochDay(2026, 11, 7) // a Saturday; London is on GMT in November
    private var nowMs = LocalCalendar.UTC.toEpochMs(today, 11 * 60)
    private val ops = InMemoryServerOpStore()
    private val store = InMemoryLiveCallStore()
    private var keyIn = true
    private val line = LiveCallLine(FieldReader.scanning(ops), store, configured = { keyIn }, nowMs = { nowMs })
    private val woken = mutableListOf<String>()

    private class Dialer : OutboundDialer {
        var on = true
        var refuse = false
        val calls = mutableListOf<List<String>>()
        override fun dialConfigured() = on
        override fun dial(to: String, from: String, twiml: String, statusUrl: String, ringSeconds: Int, limitSeconds: Int): String? {
            calls += listOf(to, from, twiml, statusUrl, ringSeconds.toString(), limitSeconds.toString())
            // Slice 3c: his listening leg is a call of its own.
            return if (refuse) null else if ("listen" in statusUrl) "CA" + "c".repeat(32) else "CA" + "b".repeat(32)
        }
    }

    private val dialer = Dialer()
    private val hub = BookingListen { nowMs }
    private val twilio = TwilioVoice(authToken = { null })
    private val caller = BookingCaller(
        ops, BankSync.scanning(ops), { "hh" }, line, dialer, twiml = { twilio.bookingTwiml("https://meka.example", it) },
        statusUrl = "https://meka.example/v1/voice/twilio/booking-status", now = { nowMs }, onWritten = { woken += it },
        listen = hub, listenTwiml = { "<Response>whisper</Response>" }, listenStatusUrl = "https://meka.example/v1/voice/twilio/booking-listen-status",
    )

    private val held = HashMap<Replica, InMemoryReplicaStore>()
    private var k = 0

    /** Meka's Fold after pulling everything the server holds. */
    private fun fold(): Replica {
        val id = k++
        var n = 0
        val s = InMemoryReplicaStore()
        val r = Replica("hh", "fold", HlcClock("fold", { nowMs }), s, MekaSchema) { "fold$id-${n++}" }
        r.applyRemoteBatch(ops.after("hh", 0, 100_000).map { it.op })
        held[r] = s
        return r
    }

    private fun push(r: Replica) = held.getValue(r).pendingPush(100_000).forEach { ops.append(it) }

    private val mine = listOf("07700 900123", "020 7946 0000", null, null)

    /** About you on the Fold, and one approved card for Saturday 19:00–20:00 for 4 at a landline. */
    private fun approvedCard(number: String = "01234 567890"): String {
        val r = fold()
        r.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(
            AboutYouFields.SHOWN_NAME to "Alex Example".fv(), AboutYouFields.MOBILE to "07700 900123".fv(), AboutYouFields.ASSISTANT_NUMBER to "020 7946 0000".fv(),
        ))
        val calls = BookingCalls(r, { "card1" }, { nowMs })
        val id = calls.draft(AskProposal.BookCall("Rosa's Kitchen", "Table for 4", 4, today + 7, 19 * 60, 20 * 60))!!
        assertTrue(calls.edit(id, null, number, "a high chair"))
        assertEquals(BookingCallRules.APPROVED_LINE, calls.approve(id, mine, LocalCalendar.UTC))
        push(r)
        return id
    }

    private fun cardOnFold(id: String) = BookingCalls(fold(), { "x" }, { nowMs }).all().single { it.id == id }

    private class FakeModel : ModelDial, ModelLink {
        val sent = mutableListOf<String>()
        var onText: ((String) -> Unit)? = null
        var refuse = false
        override fun open(onText: (String) -> Unit, onClosed: () -> Unit): ModelLink? { if (refuse) return null; this.onText = onText; return this }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() {}
        fun say(json: String) = onText!!(json)
    }

    /** The phone service opens the stream of the call it made, with the ticket from the markup. */
    private fun stream(model: FakeModel, ended: MutableList<LiveCallEnd>): LiveCallSession {
        val twiml = dialer.calls.last()[2]
        val token = Regex("""name="ticket" value="([0-9a-f]{32})"""").find(twiml)!!.groupValues[1]
        val t = assertNotNull(line.claim(token, "CA" + "b".repeat(32)))
        assertTrue(t.booking)
        return LiveCallSession(t, "MZ1", model, "gemini-test-live", "Charon", {}, {}, { line.markLive(t.callId) }, { e ->
            ended += e
            line.ended(t.callId, e)
            caller.ended(t, e, line.record(t, e))
        }, nowMs = { nowMs }, listen = hub)
    }

    @Test
    fun anApprovedCardIsRungAndBookedWithObiOnTheBrief() {
        val id = approvedCard()
        assertTrue(caller.tick())
        // On the call before anything rang; from the assistant's number, to the business, ringing 40 s, ended by 5½ min.
        val (to, from, twiml) = dialer.calls.single()
        val status = dialer.calls.single()[3]; val ring = dialer.calls.single()[4]; val limit = dialer.calls.single()[5]
        assertEquals("+441234567890", to)
        assertEquals("+442079460000", from)
        assertTrue("<Connect><Stream url=\"wss://meka.example/v1/voice/twilio/stream\">" in twiml && "booking-ended" in twiml)
        assertTrue(status.endsWith("/booking-status"))
        assertEquals("40", ring); assertEquals("${BookingDialRules.LIMIT_SECONDS}", limit)
        assertEquals(BookingCallState.CALLING, cardOnFold(id).state)
        assertEquals(listOf("hh"), woken)
        // One call at a time: the next look rings nothing more.
        assertFalse(caller.tick())
        assertEquals(1, dialer.calls.size)

        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        val setup = Json.parseToJsonElement(model.sent.single()).jsonObject["setup"].toString()
        assertTrue(BookingDialRules.BOOKING_RESULT in setup && LiveCallRules.END_CALL in setup)
        assertFalse(LiveCallRules.TAKE_MESSAGE in setup)
        assertTrue("Alex's AI assistant" in setup && "a high chair" in setup && "020 7946 0000" in setup)
        assertFalse("07700" in setup)
        model.say("""{"setupComplete":{}}""")
        assertTrue(BookingDialRules.KICKOFF in model.sent.last())
        // Taking a message isn't one of his tools on a booking call.
        model.say("""{"toolCall":{"functionCalls":[{"id":"1","name":"take_message","args":{"who":"x","about":"y","urgent":false}}]}}""")
        assertTrue("Not available" in model.sent.last())
        model.say("""{"toolCall":{"functionCalls":[{"id":"2","name":"booking_result","args":{"booked":true,"agreed":"Sat 14 Nov 19:30 for 4 under Alex","reference":"RK204"}}]}}""")
        assertTrue("toolResponse" in model.sent.last())
        nowMs += 150_000
        s.fromCaller("""{"event":"stop"}""")
        assertTrue(ended.single().live)

        val c = cardOnFold(id)
        assertEquals(BookingCallState.BOOKED, c.state)
        assertEquals("Booked · Sat 14 Nov 19:30 for 4 under Alex · ref RK204", c.result)
        // The month's spend for both apps' meter, at the outbound rate.
        val meter = FieldReader.scanning(ops).latest("hh", EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, LiveCallRules.METER_CALLS)
        assertEquals(FieldValue.Int64(1), meter)
        assertTrue(store.monthMicroUsd(line.month()) >= BookingDialRules.costMicroUsd("+441234567890", 150, 0))
        // The stream's end step hangs up; the final status changes nothing.
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingEnded("CA" + "b".repeat(32))))
        assertEquals(VoiceReply.Done, caller.handle(VoiceEvent.BookingStatus("CA" + "b".repeat(32), "completed")))
        assertEquals(BookingCallState.BOOKED, cardOnFold(id).state)
        // The Fold shows it as the card's result.
        val row = BookingCallRules.rows(listOf(c), mine, emptyList(), nowMs, LocalCalendar.UTC).single()
        assertEquals(BookingCallRules.BOOKED, row.status)
        assertFalse(row.canCancel || row.canCall)
    }

    @Test
    fun theLiveWordsReachTheCardAndTheResultKeepsTimeNameAndWords() {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        assertTrue("\"time\"" in model.sent.single() && "\"name\"" in model.sent.single())
        assertNull(s.liveWords()) // not live yet
        model.say("""{"setupComplete":{}}""")
        // Live: the first look writes when Obi started talking (no words yet).
        caller.liveWords(s.ticket, assertNotNull(s.liveWords()))
        assertNotNull(cardOnFold(id).answeredAtMs)
        model.say("""{"serverContent":{"outputTranscription":{"text":"Hi, this is Obi,"}}}""")
        model.say("""{"serverContent":{"outputTranscription":{"text":" Alex's AI assistant."}}}""")
        model.say("""{"serverContent":{"inputTranscription":{"text":"Hello Rosa's, how can I help?"}}}""")
        val w = assertNotNull(s.liveWords())
        assertNull(s.liveWords()) // unchanged since
        caller.liveWords(s.ticket, w)
        val live = BookingCallRules.rows(listOf(cardOnFold(id)), mine, emptyList(), nowMs, LocalCalendar.UTC).single()
        assertTrue(live.live && live.canHangUp && !live.showBrief)
        assertEquals(listOf("Obi" to "Hi, this is Obi, Alex's AI assistant.", "Rosa's Kitchen" to "Hello Rosa's, how can I help?"),
            live.liveWords.map { it.who to it.text })
        model.say("""{"toolCall":{"functionCalls":[{"id":"2","name":"booking_result","args":{"booked":true,"agreed":"Sat 19:30 for 4","time":"19:30","name":"Alex"}}]}}""")
        nowMs += 160_000
        s.fromCaller("""{"event":"stop"}""")
        val c = cardOnFold(id)
        assertEquals(BookingCallState.BOOKED, c.state)
        assertEquals(19 * 60 + 30, c.bookedMin)
        assertEquals("Alex", c.bookedName)
        assertEquals(160L, c.seconds)
        assertNull(c.words)
        val row = BookingCallRules.rows(listOf(c), mine, emptyList(), nowMs, LocalCalendar.UTC).single()
        assertEquals("Rosa's Kitchen · 2 min 40 s", row.lengthLine)
        assertEquals(listOf("When" to "Sat 19:30", "People" to "4", "Name" to "Alex"), row.resultTiles.map { it.label to it.value })
        assertTrue(row.canAddToCalendar && row.canRead)
        assertEquals(2, row.transcript.size)
        // Seven days after the call the words are blanked; the result stays.
        nowMs += BookingLiveRules.KEEP_MS + 60_000
        assertTrue(caller.tick())
        assertNull(cardOnFold(id).transcript)
        assertEquals(BookingCallState.BOOKED, cardOnFold(id).state)
    }

    @Test
    fun hangUpFromTheFoldEndsTheCallWithAGoodbye() {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        model.say("""{"setupComplete":{}}""")
        assertFalse(caller.hangUpAsked(s.ticket))
        val r = fold()
        assertEquals(BookingLiveRules.HUNG_UP_LINE, BookingCalls(r, { "x" }, { nowMs }).hangUp(id))
        push(r)
        assertTrue(caller.hangUpAsked(s.ticket))
        s.hangUpAsked()
        assertTrue(BookingLiveRules.HUNG_UP_TURN in model.sent.last())
        // The goodbye is said, then the line closes; at the latest after the grace.
        nowMs += BookingLiveRules.HANG_UP_GRACE_MS + 1
        s.tick()
        assertTrue(ended.single().hungUp)
        assertEquals(BookingLiveRules.HUNG_UP_RESULT, cardOnFold(id).result)
        assertEquals(BookingCallState.NOT_BOOKED, cardOnFold(id).state)
    }

    /** Slice 3b: a live call he asked to take over from the Fold; returns the card and the session's end. */
    private fun takenOver(): Pair<String, LiveCallEnd> {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        model.say("""{"setupComplete":{}}""")
        assertFalse(caller.takeOverAsked(s.ticket))
        val r = fold()
        assertEquals(BookingLiveRules.TAKE_OVER_LINE, BookingCalls(r, { "x" }, { nowMs }).takeOver(id, "07700 900123"))
        push(r)
        assertTrue(caller.takeOverAsked(s.ticket))
        s.takeOverAsked()
        assertTrue(BookingLiveRules.TAKE_OVER_TURN in model.sent.last())
        nowMs += BookingLiveRules.HANG_UP_GRACE_MS + 1
        s.tick()
        return id to ended.single()
    }

    @Test
    fun takeOverRingsYourMobileWithAWhisperAndPutsTheBusinessThrough() {
        val (id, end) = takenOver()
        assertTrue(end.takenOver && !end.hungUp)
        assertEquals(BookingCallState.TAKEN, cardOnFold(id).state)
        assertEquals(BookingLiveRules.PUTTING_THROUGH, cardOnFold(id).result)
        val sid = "CA" + "b".repeat(32)
        // The stream's end rings his mobile from the assistant's number, with the whisper first.
        val ring = assertIs<VoiceReply.TakeOver>(caller.handle(VoiceEvent.BookingEnded(sid)))
        assertEquals("+447700900123", ring.mobile); assertEquals("+442079460000", ring.callerId)
        val (_, xml) = twilio.render(ring, "https://meka.example", CallVoice.DEFAULT) { _, _ -> null }
        assertTrue("booking-taken-dialed" in xml && "booking-whisper" in xml && "+447700900123" in xml)
        val whisper = assertIs<VoiceReply.Whisper>(caller.handle(VoiceEvent.BookingWhisper("CA" + "d".repeat(32), sid)))
        assertEquals(VoiceStep.BOOKING_WHISPER_ANSWERED, whisper.answerStep)
        // Another call's leg gets nothing; a key other than 1 hangs his leg up.
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingWhisper("CA" + "d".repeat(32), "CA" + "e".repeat(32))))
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingWhisperAnswered("CA" + "d".repeat(32), sid, "2")))
        assertEquals(VoiceReply.Connect, caller.handle(VoiceEvent.BookingWhisperAnswered("CA" + "d".repeat(32), sid, "1")))
        val callsBefore = store.monthCalls(line.month())
        val spentBefore = store.monthMicroUsd(line.month())
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingTakenDialed(sid, 190)))
        val c = cardOnFold(id)
        assertEquals(BookingCallState.TAKEN, c.state)
        assertEquals("You took the call from Obi · you talked 3 min 10 s", c.result)
        assertEquals(190L, c.yourSeconds)
        // His leg costs (on the meter) but isn't another call.
        assertEquals(callsBefore, store.monthCalls(line.month()))
        assertEquals(spentBefore + BookingDialRules.handOverMicroUsd("+441234567890", 190), store.monthMicroUsd(line.month()))
    }

    @Test
    fun aTakeOverYouDontPickUpTellsThemYoullCallBack() {
        val (id, _) = takenOver()
        val sid = "CA" + "b".repeat(32)
        assertIs<VoiceReply.TakeOver>(caller.handle(VoiceEvent.BookingEnded(sid)))
        assertEquals(VoiceReply.Goodbye(BookingLiveRules.TAKE_OVER_SORRY), caller.handle(VoiceEvent.BookingTakenDialed(sid, 0)))
        assertEquals(BookingCallState.NOT_BOOKED, cardOnFold(id).state)
        assertEquals(BookingLiveRules.TAKE_OVER_MISSED, cardOnFold(id).result)
    }

    @Test
    fun noAnswerAndBusyCloseTheCardAsNotBooked() {
        val id = approvedCard()
        caller.tick()
        assertIs<VoiceReply.Goodbye>(caller.handle(VoiceEvent.BookingEnded("CA" + "b".repeat(32))))
        caller.handle(VoiceEvent.BookingStatus("CA" + "b".repeat(32), "no-answer"))
        val c = cardOnFold(id)
        assertEquals(BookingCallState.NOT_BOOKED, c.state)
        assertEquals(BookingDialRules.NO_ANSWER, c.result)
        // An unknown call's status is ignored.
        assertEquals(VoiceReply.Done, caller.handle(VoiceEvent.BookingStatus("CA" + "c".repeat(32), "busy")))
    }

    @Test
    fun obiNotStartingClosesTheCardAndTheBusinessHearsWhy() {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel().also { it.refuse = true }; val ended = mutableListOf<LiveCallEnd>()
        stream(model, ended).start()
        assertFalse(ended.single().live)
        assertEquals(BookingDialRules.NEVER_LIVE, cardOnFold(id).result)
        assertEquals(VoiceReply.Goodbye(BookingDialRules.SORRY), caller.handle(VoiceEvent.BookingEnded("CA" + "b".repeat(32))))
        // Nothing was spent.
        assertEquals(0L, store.monthMicroUsd(line.month()))
    }

    @Test
    fun outsideHoursItWaitsAndNotSetUpRingsNothing() {
        nowMs = LocalCalendar.UTC.toEpochMs(today, 8 * 60)
        val id = approvedCard()
        assertFalse(caller.tick())
        assertEquals(BookingCallState.APPROVED, cardOnFold(id).state)
        nowMs = LocalCalendar.UTC.toEpochMs(today, 9 * 60)
        dialer.on = false
        assertFalse(caller.tick())
        keyIn = false; dialer.on = true
        assertFalse(caller.tick())
        assertTrue(dialer.calls.isEmpty())
        keyIn = true
        assertTrue(caller.tick())
        assertEquals(1, dialer.calls.size)
    }

    @Test
    fun aRefusedCallAPersonalNumberAndAStuckCallAreClosed() {
        val id = approvedCard()
        dialer.refuse = true
        caller.tick()
        assertEquals(BookingCallState.FAILED to BookingDialRules.DIAL_FAILED, cardOnFold(id).let { it.state to it.result })

        // His own mobile slipped onto a card (an edit on another device): the server never rings it.
        val r = fold()
        r.commitLocal(EntityTypes.BOOKING_CALL, "card2", BookingCallRules.draftFields(AskProposal.BookCall("Oak Barbers", "Haircut", null, today + 1, null, null), nowMs) + mapOf(
            BookingCallFields.NUMBER to "+447700900123".fv(), BookingCallFields.STATE to BookingCallState.APPROVED.code.fv(),
        ))
        push(r)
        dialer.refuse = false
        caller.tick()
        assertEquals(1, dialer.calls.size)
        assertTrue(cardOnFold("card2").result!!.startsWith("Not rung · That's a personal number"))

        // A card left on the call (a restart lost the call) is closed after a while.
        val r2 = fold()
        r2.commitLocal(EntityTypes.BOOKING_CALL, "card3", BookingCallRules.draftFields(AskProposal.BookCall("The Oak", "Table", 2, today + 1, null, null), nowMs) + mapOf(
            BookingCallFields.STATE to BookingCallState.CALLING.code.fv(), BookingCallFields.CALLED_AT to FieldValue.Int64(nowMs),
        ))
        push(r2)
        nowMs += BookingDialRules.STUCK_MS + 1
        caller.tick()
        assertEquals(BookingCallState.FAILED to BookingDialRules.STUCK, cardOnFold("card3").let { it.state to it.result })
    }

    @Test
    fun aCardCancelledBeforeTheLookNeverRings() {
        val r = fold()
        r.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.ASSISTANT_NUMBER to "020 7946 0000".fv()))
        val calls = BookingCalls(r, { "card1" }, { nowMs })
        val id = calls.draft(AskProposal.BookCall("Rosa's Kitchen", "Table for 4", 4, today + 7, 19 * 60, 20 * 60))!!
        calls.edit(id, null, "01234 567890", null)
        calls.approve(id, mine, LocalCalendar.UTC)
        assertTrue(calls.cancel(id))
        push(r)
        assertFalse(caller.tick())
        assertTrue(dialer.calls.isEmpty())
        assertNull(cardOnFold(id).result)
    }

    /** Slice 3c: a live call he asked to listen in on from the Fold, his mobile rung; the session and his leg's id. */
    private fun listenedTo(): Triple<String, LiveCallSession, MutableList<LiveCallEnd>> {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        model.say("""{"setupComplete":{}}""")
        assertFalse(caller.listenAsked(s.ticket))
        val r = fold()
        assertEquals(BookingLiveRules.LISTEN_LINE, BookingCalls(r, { "x" }, { nowMs }).listen(id, "07700 900123"))
        push(r)
        assertTrue(caller.listenAsked(s.ticket))
        caller.ringToListen(s.ticket)
        // Rung once, from the assistant's number, its end to the listening leg's own status step.
        assertFalse(caller.listenAsked(s.ticket))
        val leg = dialer.calls.last()
        assertEquals(listOf("+447700900123", "+442079460000", "<Response>whisper</Response>"), leg.take(3))
        assertTrue("booking-listen-status" in leg[3])
        assertEquals(BookingLiveRules.LISTEN_RING_SECONDS.toString(), leg[4])
        assertTrue(cardOnFold(id).listen && cardOnFold(id).listeningAtMs == null)
        return Triple(id, s, ended)
    }

    @Test
    fun listenRingsYourMobileAndYouHearBothSidesMuted() {
        val (id, s, ended) = listenedTo()
        val legSid = "CA" + "c".repeat(32)
        // A key other than 1, or another leg, hangs up; 1 starts the sound over the listening stream.
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingListenAnswered(legSid, "2")))
        assertEquals(VoiceReply.HangUp, caller.handle(VoiceEvent.BookingListenAnswered("CA" + "e".repeat(32), "1")))
        val go = assertIs<VoiceReply.ListenStream>(caller.handle(VoiceEvent.BookingListenAnswered(legSid, "1")))
        assertEquals(nowMs, cardOnFold(id).listeningAtMs)
        val (_, xml) = twilio.render(go, "https://meka.example", CallVoice.DEFAULT) { _, _ -> null }
        assertTrue("wss://meka.example/v1/voice/twilio/listen" in xml && go.ticket in xml && "<Hangup/>" in xml)
        val heard = mutableListOf<ByteArray>()
        var closed = false
        assertNotNull(hub.attach(go.ticket, { heard += it }, { closed = true }))
        assertNull(hub.attach(go.ticket, {}, {}))
        // The business's frames reach him as they're said.
        val frame = ByteArray(160) { 0x55 }
        s.fromCaller("""{"event":"media","media":{"track":"inbound","payload":"${java.util.Base64.getEncoder().encodeToString(frame)}"}}""")
        assertEquals(1, heard.size)
        assertEquals(160, heard.single().size)
        // Obi's part ends: his leg's stream closes; its minutes are on the meter but it isn't a call.
        s.hangUpAsked()
        nowMs += BookingLiveRules.HANG_UP_GRACE_MS + 1
        s.tick()
        assertTrue(closed && ended.single().hungUp)
        assertFalse(hub.active(s.ticket.callId))
        val callsBefore = store.monthCalls(line.month())
        val spentBefore = store.monthMicroUsd(line.month())
        assertEquals(VoiceReply.Done, caller.handle(VoiceEvent.BookingListenStatus(legSid, 65)))
        assertEquals(callsBefore, store.monthCalls(line.month()))
        assertEquals(spentBefore + BookingDialRules.listenMicroUsd(65), store.monthMicroUsd(line.month()))
        assertEquals(BookingCallState.NOT_BOOKED, cardOnFold(id).state)
    }

    @Test
    fun aListeningLegThatEndsWhileObiTalksPutsTheCardBack() {
        val (id, s, _) = listenedTo()
        val legSid = "CA" + "c".repeat(32)
        // He never pressed 1: the leg ends and the card says Obi is on the call again; nothing more is rung.
        assertEquals(VoiceReply.Done, caller.handle(VoiceEvent.BookingListenStatus(legSid, 0)))
        assertEquals(-1L, cardOnFold(id).listeningAtMs)
        assertFalse(caller.listenAsked(s.ticket))
        val rows = BookingCallRules.rows(listOf(cardOnFold(id)), emptyList(), emptyList(), nowMs, LocalCalendar.UTC, "07700 900123")
        assertEquals(BookingCallRules.CALLING, rows.single().status)
        assertFalse(rows.single().listening || rows.single().canListen)
    }

    @Test
    fun withoutYourMobileListenSaysSoOnTheCard() {
        val id = approvedCard()
        caller.tick()
        val model = FakeModel(); val ended = mutableListOf<LiveCallEnd>()
        val s = stream(model, ended)
        s.start()
        model.say("""{"setupComplete":{}}""")
        val r = fold()
        BookingCalls(r, { "x" }, { nowMs }).listen(id, "07700 900123")
        // His mobile taken out of About you before the server looks.
        r.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.MOBILE to "".fv()))
        push(r)
        val before = dialer.calls.size
        caller.ringToListen(s.ticket)
        assertEquals(before, dialer.calls.size)
        assertEquals(-1L, cardOnFold(id).listeningAtMs)
    }

    @Test
    fun theMixKeepsThePaceOfTheBusinessAndObiIsCutOffCleanly() {
        val mix = ListenMix()
        val silence = ByteArray(160) { 0xFF.toByte() }
        // Nothing from Obi: the business's frame as it is.
        assertTrue(mix.business(silence).contentEquals(silence))
        val obi = ByteArray(400) { 0x30 }
        mix.obi(obi)
        val out = mix.business(silence)
        assertEquals(160, out.size)
        assertTrue(CallAudio.muLawToPcm(out).contentEquals(CallAudio.muLawToPcm(obi.copyOf(160))))
        assertEquals(240, mix.queued())
        mix.clear()
        assertEquals(0, mix.queued())
        assertTrue(mix.business(silence).contentEquals(silence))
        // A runaway queue keeps only the newest.
        val small = ListenMix(maxQueued = 100)
        small.obi(ByteArray(80)); small.obi(ByteArray(80))
        assertEquals(100, small.queued())
    }
}
