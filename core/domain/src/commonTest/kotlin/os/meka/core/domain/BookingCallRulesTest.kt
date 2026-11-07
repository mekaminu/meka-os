package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.meka.core.sync.FieldValue

/** Obi on calls, slice 2a: the call card (Ask's book_call card, the card's words, the number rules, Call now). */
class BookingCallRulesTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 11, 7) // Saturday
    private val now = cal.toEpochMs(today, 11 * 60)
    private val saturday = today + 7

    @Test
    fun theModelsBookCallBecomesACardWithBusinessPartyAndWindow() {
        val p = BookingCallRules.proposal("Table for 4 at Rosa's Kitchen", saturday, 19 * 60, null, today, 11 * 60)!!
        assertEquals("Rosa's Kitchen", p.business)
        assertEquals("Table for 4", p.what)
        assertEquals(4, p.people)
        assertEquals(19 * 60 to 20 * 60, p.fromMin to p.toMin)
        assertEquals("Call card · Rosa's Kitchen · Table for 4 · Sat 14 Nov 19:00–20:00", BookingCallRules.cardLine(p))
        val words = BookingCallRules.proposal("book a table for four people at The Oak", saturday, 18 * 60 + 30, 90, today, 0)!!
        assertEquals(4, words.people)
        assertEquals("The Oak", words.business)
        assertEquals(20 * 60, words.toMin)
        val haircut = BookingCallRules.proposal("Haircut with Oakfield Barbers", today + 2, null, null, today, 0)!!
        assertNull(haircut.people)
        assertEquals("Any time", BookingCallRules.windowLabel(haircut.fromMin, haircut.toMin))
        // No business, no day, a day gone or a window already over today: no card.
        assertNull(BookingCallRules.proposal("Table for 4", saturday, 19 * 60, null, today, 0))
        assertNull(BookingCallRules.proposal("Table for 4 at Rosa's", null, 19 * 60, null, today, 0))
        assertNull(BookingCallRules.proposal("Table for 4 at Rosa's", today - 1, 19 * 60, null, today, 0))
        assertNull(BookingCallRules.proposal("Table for 2 at Rosa's", today, 9 * 60, 60, today, 11 * 60))
    }

    @Test
    fun theAskCardKeepsADraftAndNeverRingsByItself() {
        val ctx = AskContext.EMPTY
        val card = AskRules.card(AskRawAction("book_call", title = "Table for 4 at Rosa's Kitchen", date = "2026-11-14", time = "19:00"), ctx, emptyMap(), now, cal)!!
        val p = assertIs<AskProposal.BookCall>(card.proposal)
        assertEquals(BookingCallRules.CARD_BUTTON, card.button)
        assertNull(TrustRules.kindOf(p))
        // A bad time isn't guessed at.
        assertNull(AskRules.card(AskRawAction("book_call", title = "Table at Rosa's", date = "2026-11-14", time = "7pm"), ctx, emptyMap(), now, cal))
    }

    @Test
    fun onlyUkBusinessNumbersCanBeRung() {
        val mine = listOf("07700 900123", "+44 20 7946 0000", null)
        assertEquals(NumberCheck.Ok("+441234567890"), BookingCallRules.numberCheck("01234 567890", mine))
        assertEquals(NumberCheck.Ok("+441234567890"), BookingCallRules.numberCheck("+44 (0)1234 567890".replace("(0)", ""), mine))
        assertEquals(NumberCheck.Ok("+447700900999"), BookingCallRules.numberCheck("07700 900999", mine))
        assertEquals("01234 567890", BookingCallRules.pretty("+441234567890"))
        assertEquals("020 7946 0001", BookingCallRules.pretty("+442079460001"))
        fun refused(n: String?) = assertIs<NumberCheck.Refused>(BookingCallRules.numberCheck(n, mine), n.toString()).line
        assertEquals(BookingCallRules.NO_NUMBER, refused(null))
        assertTrue("emergency" in refused("999"))
        assertTrue("emergency" in refused("112"))
        assertTrue("emergency" in refused("111"))
        assertTrue("premium" in refused("0909 123 4567"))
        assertTrue("premium" in refused("0871 123 4567"))
        assertTrue("businesses only" in refused("070 1234 5678"))
        assertTrue("UK numbers only" in refused("+33 1 23 45 67 89"))
        assertTrue("personal" in refused("+447700900123"))
        assertTrue("personal" in refused("020 7946 0000"))
        assertTrue("phone number" in refused("call them"))
        assertTrue("area code" in refused("567890"))
    }

    private fun store(): Pair<BookingCalls, os.meka.core.sync.Replica> {
        val replica = os.meka.core.testing.SyncWorld().device("android").replica
        var n = 0
        return BookingCalls(replica, { "call-${++n}" }, { now }) to replica
    }

    @Test
    fun callNowNeedsANumberAndApprovesOnce() {
        val (calls, _) = store()
        val p = BookingCallRules.proposal("Table for 4 at Rosa's Kitchen", saturday, 19 * 60, null, today, 11 * 60)!!
        val id = calls.draft(p)!!
        var rows = BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal)
        assertEquals(BookingCallRules.NO_NUMBER, rows.single().blocked)
        assertFalse(rows.single().canCall)
        assertEquals(BookingCallRules.NO_NUMBER, calls.approve(id, emptyList(), cal))
        assertTrue(calls.setPeople(id, 5))
        assertFalse(calls.setPeople(id, 13))
        assertTrue(calls.edit(id, null, "01234 567890", "a window seat if they have one"))
        rows = BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal)
        val r = rows.single()
        assertTrue(r.canCall && r.needsYou)
        assertEquals("5", r.peopleTile)
        assertEquals("01234 567890 · you added it", r.numberLine)
        assertEquals("Sat 14 Nov", r.whenTile)
        assertEquals("19:00–20:00", r.windowTile)
        assertTrue(r.foot!!.startsWith("Your Saturday is free then · about 3 min · about "), r.foot)
        assertEquals(BookingCallRules.APPROVED_LINE, calls.approve(id, emptyList(), cal))
        val approved = calls.all().single()
        assertEquals(BookingCallState.APPROVED, approved.state)
        assertEquals("+441234567890", approved.number)
        // Approved: no second Call now, no edits; Cancel still works until Obi is on the call.
        assertEquals(BookingCallRules.GONE, calls.approve(id, emptyList(), cal))
        assertFalse(calls.edit(id, "Other", null, null))
        val after = BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal).single()
        assertFalse(after.canCall || after.needsYou)
        assertTrue(after.canCancel)
        assertTrue(calls.cancel(id))
        assertTrue(BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal).isEmpty())
    }

    @Test
    fun aPersonalNumberOrAClashIsSaidOnTheCard() {
        val (calls, _) = store()
        val id = calls.draft(BookingCallRules.proposal("Table for 2 at Rosa's", saturday, 19 * 60, null, today, 0)!!)!!
        calls.edit(id, null, "07700 900123", null)
        val clash = CalendarEvent("e1", "Football", cal.toEpochMs(saturday, 19 * 60 + 30), cal.toEpochMs(saturday, 21 * 60), false, null, "google", null, null)
        val r = BookingCallRules.rows(calls.all(), listOf("+447700900123"), listOf(clash), now, cal).single()
        assertTrue("personal" in r.blocked!!)
        assertTrue(r.foot!!.startsWith("Clashes with “Football” at 19:30"), r.foot)
        assertTrue("personal" in calls.approve(id, listOf("+447700900123"), cal))
    }

    @Test
    fun atMostThreeOpenCardsAndTheServersResultShows() {
        val (calls, replica) = store()
        val p = BookingCallRules.proposal("Table for 2 at Rosa's", saturday, 19 * 60, null, today, 0)!!
        val ids = List(3) { calls.draft(p)!! }
        assertNull(calls.draft(p))
        replica.commitLocal(EntityTypes.BOOKING_CALL, ids[0], mapOf(
            BookingCallFields.STATE to FieldValue.Text(BookingCallState.BOOKED.code),
            BookingCallFields.RESULT to FieldValue.Text("Booked for 19:30 under Alex"),
        ))
        val rows = BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal)
        val done = rows.last()
        assertEquals(BookingCallRules.BOOKED, done.status)
        assertEquals("Booked for 19:30 under Alex", done.result)
        assertNull(done.foot)
        assertFalse(done.canCancel)
        assertTrue(calls.draft(p) != null)
    }

    @Test
    fun obiIsWorkingOnCountsTheCards() {
        val (calls, _) = store()
        calls.draft(BookingCallRules.proposal("Table for 2 at Rosa's", saturday, 19 * 60, null, today, 0)!!)
        val v = WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, GiftsView.EMPTY, emptyList(), today,
            calls = BookingCallRules.rows(calls.all(), emptyList(), emptyList(), now, cal))
        assertEquals("Holding 1 call card for you · nothing needs you", v.summary)
        assertEquals(0, v.needCount)
    }
}
