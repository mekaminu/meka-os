package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, slice 3: the live view and the result card. Invented data only. */
class BookingLiveRulesTest {
    private val day = CivilDate.toEpochDay(2026, 11, 14) // a Saturday
    private val now = LocalCalendar.UTC.toEpochMs(day - 7, 11 * 60)

    private fun card(state: BookingCallState, vararg more: Pair<String, Any?>): BookingCall {
        val m = more.toMap()
        return BookingCall(
            id = "c1", business = "Rosa's Kitchen", number = "+441234567890", what = "Table for 4", day = day, fromMin = 19 * 60, toMin = 20 * 60,
            people = 4, note = null, state = state, askedAtMs = now - 60_000, approvedAtMs = now - 50_000, result = m["result"] as String?,
            cancelled = false, calledAtMs = now - 30_000, endedAtMs = m["ended"] as Long?, answeredAtMs = m["answered"] as Long?,
            seconds = m["seconds"] as Long?, words = m["words"] as String?, transcript = m["transcript"] as String?,
            bookedMin = m["bookedMin"] as Int?, bookedName = m["name"] as String?, hangUp = m["hangUp"] == true,
            calendarEditId = m["edit"] as String?,
        )
    }

    private fun row(c: BookingCall, at: Long = now) = BookingCallRules.rows(listOf(c), emptyList(), emptyList(), at, LocalCalendar.UTC).single()

    @Test
    fun fragmentsBecomeOneLinePerTurnAndTheLiveViewKeepsTheLastFour() {
        val w = BookingWords()
        assertEquals("", w.liveIfChanged()) // the first look, once live
        assertNull(w.liveIfChanged())
        w.add(true, "Hi, this is Obi,")
        w.add(true, " Alex's AI assistant.\n")
        w.add(false, "  Hello, Rosa's.")
        w.add(true, "Could I book a table?")
        assertEquals(
            listOf("Obi" to "Hi, this is Obi, Alex's AI assistant.", "Rosa's Kitchen" to "Hello, Rosa's.", "Obi" to "Could I book a table?"),
            BookingWords.decode(w.liveIfChanged(), "Rosa's Kitchen").map { it.who to it.text },
        )
        assertNull(w.liveIfChanged())
        repeat(6) { w.add(it % 2 == 0, "line $it") }
        val live = BookingWords.decode(w.liveIfChanged(), "Rosa's Kitchen")
        assertEquals(BookingLiveRules.LIVE_LINES, live.size)
        assertEquals("line 5", live.last().text)
        // "line 0" joins Obi's last line (the same speaker), the other five are lines of their own.
        assertEquals(8, BookingWords.decode(w.all(), "x").size)
    }

    @Test
    fun theWholeCallIsCappedAndMalformedLinesAreSkipped() {
        val w = BookingWords()
        repeat(400) { w.add(it % 2 == 0, "a".repeat(40)) }
        assertTrue(w.all().length <= BookingLiveRules.MAX_TRANSCRIPT + BookingLiveRules.MAX_LINES * 3)
        assertEquals(listOf("hi"), BookingWords.decode("O\thi\nX\tno\nbad\nT\t  ", "B").map { it.text })
    }

    @Test
    fun clockLengthAndTheAgreedTime() {
        assertEquals("01:12", BookingLiveRules.clock(0, 72_000))
        assertEquals("00:00", BookingLiveRules.clock(5_000, 0))
        assertEquals("2 min 40 s", BookingLiveRules.length(160))
        assertEquals("45 s", BookingLiveRules.length(45))
        assertEquals("3 min", BookingLiveRules.length(180))
        assertEquals(19 * 60 + 30, BookingLiveRules.agreedMinute("19:30", 19 * 60, 20 * 60))
        assertEquals(19 * 60 + 30, BookingLiveRules.agreedMinute("7:30 pm", 19 * 60, 20 * 60))
        assertEquals(20 * 60, BookingLiveRules.agreedMinute("8pm", 19 * 60, 20 * 60))
        // Outside the window, or not a time: not kept.
        assertNull(BookingLiveRules.agreedMinute("21:00", 19 * 60, 20 * 60))
        assertNull(BookingLiveRules.agreedMinute("half seven", 19 * 60, 20 * 60))
        assertNull(BookingLiveRules.agreedMinute("19", 19 * 60, 20 * 60))
        assertEquals(10 * 60, BookingLiveRules.agreedMinute("10:00", null, null))
        assertEquals("Alex", BookingLiveRules.name(" Alex "))
        assertNull(BookingLiveRules.name("<b>x</b>"))
    }

    @Test
    fun onTheCallTheCardIsTheLiveViewWithHangUp() {
        val words = "O\tHi, this is Obi.\nT\tHello?"
        val r = row(card(BookingCallState.CALLING, "answered" to now - 12_000, "words" to words))
        assertTrue(r.live && r.canHangUp && !r.showBrief)
        assertEquals(now - 12_000, r.liveSinceMs)
        assertEquals("Rosa's Kitchen · 00:12", BookingLiveRules.liveLine(r.business, r.liveSinceMs, now))
        assertEquals(2, r.liveWords.size)
        assertTrue(r.spoken.endsWith("Rosa's Kitchen: Hello?"))
        // Once he taps Hang up the card says so and Hang up goes.
        val h = row(card(BookingCallState.CALLING, "hangUp" to true))
        assertEquals(BookingLiveRules.HANGING_UP, h.status)
        assertFalse(h.canHangUp)
        assertEquals(now - 30_000, h.liveSinceMs) // ringing, not answered yet
        // A card waiting to ring still shows its brief.
        assertTrue(row(card(BookingCallState.APPROVED)).showBrief)
    }

    @Test
    fun aBookedCardIsTheResultCardWithCalendarAndWords() {
        val c = card(BookingCallState.BOOKED, "result" to "Booked · Sat 19:30 for 4 · ref RK204", "seconds" to 160L, "bookedMin" to 19 * 60 + 30,
            "name" to "Alex", "ended" to now, "transcript" to "O\tHi.\nT\tHello.")
        val r = row(c)
        assertFalse(r.live || r.showBrief)
        assertEquals("Rosa's Kitchen · 2 min 40 s", r.lengthLine)
        assertEquals(listOf("Sat 19:30", "4", "Alex"), r.resultTiles.map { it.value })
        assertEquals("In your calendar · Sat 14 Nov 19:30", r.calendarLine)
        assertTrue(r.canAddToCalendar && !r.addedToCalendar && r.canRead)
        val d = assertNotNull(BookingLiveRules.calendarDraft(c, LocalCalendar.UTC))
        assertEquals("Table for 4 at Rosa's Kitchen", d.title)
        assertEquals(LocalCalendar.UTC.toEpochMs(day, 19 * 60 + 30), d.startAtMs)
        assertEquals(90 * 60_000L, d.endAtMs - d.startAtMs)
        assertEquals("Booked by Obi on the phone · Ref RK204", d.notes)
        // Added: the card says so on both apps; after 7 days the words aren't shown and the server blanks them.
        assertTrue(row(c.copy(calendarEditId = "e1")).addedToCalendar)
        val later = now + BookingLiveRules.KEEP_MS + 1
        assertFalse(row(c, later).canRead)
        assertTrue(BookingLiveRules.shouldForget(c, later))
        assertFalse(BookingLiveRules.shouldForget(c, now))
        // Not booked: no tiles, no calendar.
        val nb = row(card(BookingCallState.NOT_BOOKED, "result" to "Not booked · full"))
        assertTrue(nb.resultTiles.isEmpty() && nb.calendarLine == null && !nb.canAddToCalendar)
        assertNull(BookingLiveRules.calendarDraft(card(BookingCallState.NOT_BOOKED), LocalCalendar.UTC))
    }

    @Test
    fun hangingUpBeforeAnythingIsBookedSaysSo() {
        val c = card(BookingCallState.CALLING)
        assertEquals(BookingCallState.NOT_BOOKED to BookingLiveRules.HUNG_UP_RESULT, BookingDialRules.result(true, null, c, hungUp = true))
        val o = BookingDialRules.outcome(mapOf("booked" to "true", "agreed" to "Sat 19:30", "time" to "19:30", "name" to "Alex"))
        assertEquals("19:30", o.time); assertEquals("Alex", o.name)
        assertEquals(BookingCallState.BOOKED, BookingDialRules.result(true, o, c, hungUp = true).first)
    }

    @Test
    fun hangUpIsOnlyForACallInProgress() {
        var n = 0
        val r = os.meka.core.sync.Replica("hh", "fold", os.meka.core.sync.HlcClock("fold", { now }), os.meka.core.sync.InMemoryReplicaStore(), MekaSchema) { "op${n++}" }
        val calls = BookingCalls(r, { "c1" }, { now })
        val id = calls.draft(AskProposal.BookCall("Rosa's Kitchen", "Table for 4", 4, day, 19 * 60, 20 * 60))!!
        assertEquals(BookingLiveRules.NOT_ON_CALL, calls.hangUp(id))
        r.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.STATE to os.meka.core.sync.FieldValue.Text(BookingCallState.CALLING.code)))
        assertEquals(BookingLiveRules.HUNG_UP_LINE, calls.hangUp(id))
        assertTrue(calls.all().single().hangUp)
    }

    @Test
    fun takeOverNeedsYourMobileAndACallInProgress() {
        val c = card(BookingCallState.CALLING)
        fun live(c: BookingCall, mobile: String?) = BookingCallRules.rows(listOf(c), emptyList(), emptyList(), now, LocalCalendar.UTC, mobile).single()
        assertFalse(live(c, null).canTakeOver)
        assertTrue(live(c, "07700 900123").canTakeOver)
        val asked = live(c.copy(takeOver = true), "07700 900123")
        assertFalse(asked.canTakeOver)
        assertEquals(BookingLiveRules.TAKING_OVER, asked.status)
        assertFalse(live(c.copy(hangUp = true), "07700 900123").canTakeOver)

        var n = 0
        val r = os.meka.core.sync.Replica("hh", "fold", os.meka.core.sync.HlcClock("fold", { now }), os.meka.core.sync.InMemoryReplicaStore(), MekaSchema) { "op${n++}" }
        val calls = BookingCalls(r, { "c1" }, { now })
        val id = calls.draft(AskProposal.BookCall("Rosa's Kitchen", "Table for 4", 4, day, 19 * 60, 20 * 60))!!
        assertEquals(BookingLiveRules.NOT_ON_CALL, calls.takeOver(id, "07700 900123"))
        r.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.STATE to os.meka.core.sync.FieldValue.Text(BookingCallState.CALLING.code)))
        assertEquals(BookingLiveRules.NO_MOBILE, calls.takeOver(id, null))
        assertFalse(calls.all().single().takeOver)
        assertEquals(BookingLiveRules.TAKE_OVER_LINE, calls.takeOver(id, "07700 900123"))
        assertTrue(calls.all().single().takeOver)
    }

    @Test
    fun aTakenCallSaysYouTookItAndHowLongEachOfYouTalked() {
        val o = BookingDialRules.outcome(mapOf("booked" to "true", "agreed" to "Sat 19:30 for 4", "reference" to "RK204"))
        val c = card(BookingCallState.CALLING)
        assertEquals(BookingCallState.TAKEN to BookingLiveRules.PUTTING_THROUGH, BookingDialRules.result(true, null, c, takenOver = true))
        val (state, line) = BookingDialRules.result(true, o, c, takenOver = true)
        assertEquals(BookingCallState.TAKEN, state)
        assertEquals("You took over · ringing your mobile · Obi had booked Sat 19:30 for 4 · ref RK204", line)
        // Hang up wins over Take over; a call that never went live is still a failed call.
        assertEquals(BookingCallState.NOT_BOOKED, BookingDialRules.result(true, null, c, hungUp = true, takenOver = true).first)
        assertEquals(BookingCallState.FAILED, BookingDialRules.result(false, null, c, takenOver = true).first)
        assertEquals("You took the call from Obi · you talked 3 min 10 s · Obi had booked Sat 19:30 for 4 · ref RK204", BookingLiveRules.takenLine(true, 190, line))
        assertEquals("You took the call from Obi", BookingLiveRules.takenLine(true, 0, BookingLiveRules.PUTTING_THROUGH))
        assertEquals(BookingLiveRules.TAKE_OVER_MISSED, BookingLiveRules.takenLine(false, 0, line))
        val r = row(card(BookingCallState.TAKEN, "seconds" to 70L, "result" to "You took the call from Obi", "ended" to now).copy(yourSeconds = 190))
        assertTrue(BookingCallState.TAKEN.finished && !r.live && !r.showBrief && !r.canTakeOver)
        assertEquals(BookingLiveRules.TAKEN, r.status)
        assertEquals("Rosa's Kitchen · Obi 1 min 10 s · you 3 min 10 s", r.lengthLine)
        assertTrue(r.resultTiles.isEmpty() && r.calendarLine == null)
        // His own leg is metered at his mobile's rate plus the business's line, by the minute.
        assertEquals(4 * (31_000L + 16_000L), BookingDialRules.handOverMicroUsd("+441234567890", 190))
    }

    @Test
    fun listenNeedsYourMobileAndShowsWhileYouListen() {
        val c = card(BookingCallState.CALLING)
        fun live(c: BookingCall, mobile: String?) = BookingCallRules.rows(listOf(c), emptyList(), emptyList(), now, LocalCalendar.UTC, mobile).single()
        assertFalse(live(c, null).canListen)
        assertTrue(live(c, "07700 900123").canListen)
        val asked = live(c.copy(listen = true), "07700 900123")
        assertFalse(asked.canListen)
        assertFalse(asked.listening)
        assertEquals(BookingLiveRules.RINGING_TO_LISTEN, asked.status)
        // Take over is still there while he listens.
        assertTrue(asked.canTakeOver)
        val on = live(c.copy(listen = true, listeningAtMs = now), "07700 900123")
        assertTrue(on.listening)
        assertEquals(BookingLiveRules.LISTENING, on.status)
        // His mobile hung up (or never took it): back to Obi is on the call, Listen not offered again.
        val over = live(c.copy(listen = true, listeningAtMs = -1), "07700 900123")
        assertFalse(over.listening || over.canListen)
        assertEquals(BookingCallRules.CALLING, over.status)
        assertFalse(live(c.copy(hangUp = true), "07700 900123").canListen)
        assertFalse(live(c.copy(takeOver = true), "07700 900123").canListen)
        assertFalse(live(c.copy(takeOver = true, listen = true, listeningAtMs = now), "07700 900123").listening)

        var n = 0
        val r = os.meka.core.sync.Replica("hh", "fold", os.meka.core.sync.HlcClock("fold", { now }), os.meka.core.sync.InMemoryReplicaStore(), MekaSchema) { "op${n++}" }
        val calls = BookingCalls(r, { "c1" }, { now })
        val id = calls.draft(AskProposal.BookCall("Rosa's Kitchen", "Table for 4", 4, day, 19 * 60, 20 * 60))!!
        assertEquals(BookingLiveRules.NOT_ON_CALL, calls.listen(id, "07700 900123"))
        r.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.STATE to os.meka.core.sync.FieldValue.Text(BookingCallState.CALLING.code)))
        assertEquals(BookingLiveRules.NO_MOBILE_LISTEN, calls.listen(id, null))
        assertFalse(calls.all().single().listen)
        assertEquals(BookingLiveRules.LISTEN_LINE, calls.listen(id, "07700 900123"))
        assertTrue(calls.all().single().listen)
        // His listening leg: his mobile's minute and its stream.
        assertEquals(2 * (31_000L + 4_000L), BookingDialRules.listenMicroUsd(61))
        assertEquals(0L, BookingDialRules.listenMicroUsd(0))
    }
}
