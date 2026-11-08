package os.meka.core.domain

import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, the number looked up: the web's numbers for a business, offered on the card, never rung by themselves. Invented data. */
class BookingNumberRulesTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 11, 7)
    private val now = cal.toEpochMs(today, 11 * 60)
    private val mine = listOf("07700 900123", "+44 20 7946 0000")

    @Test
    fun theQueryIsTheNameAloneAndTheTokensTellBusinessesApart() {
        assertEquals("\"Rosa's Kitchen\" phone number", BookingNumberRules.query("Rosa's Kitchen"))
        assertEquals("\"Rosa Kitchen\" phone number", BookingNumberRules.query("Rosa \"Kitchen\""))
        assertEquals(setOf("rosas", "rosa"), BookingNumberRules.tokens("Rosa's Kitchen"))
        assertEquals(setOf("oakfield"), BookingNumberRules.tokens("The Oakfield Barbers"))
        assertTrue(BookingNumberRules.tokens("The Bar").isEmpty())
    }

    @Test
    fun numbersAreFoundAsWritten() {
        assertEquals(
            listOf("020 7946 0018", "+44 113 496 0000", "+44 1632 960123", "(01632) 960 456"),
            BookingNumberRules.numbersIn("Call 020 7946 0018 or +44 113 496 0000, +44 (0)1632 960123; (01632) 960 456. Open 12–22."),
        )
        // Part of a longer run of digits (an order number): skipped.
        assertTrue(BookingNumberRules.numbersIn("Ref 98020794600181").isEmpty())
        assertEquals("rosaskitchen.example", BookingNumberRules.host("https://www.RosasKitchen.example/contact?x=1"))
    }

    @Test
    fun theBestNumberComesFromAResultThatNamesTheBusiness() {
        val hits = listOf(
            WebHit("https://directory.example/leeds", "Restaurants in town", "Call 0113 496 0999 for listings"),
            WebHit("https://www.rosaskitchen.example/", "Rosa's Kitchen · Italian", "Book on 0113 496 0123. Open daily."),
            WebHit("https://reviews.example/rosas", "Rosa's Kitchen reviews", "Phone 0113 496 0123 · mobile 07700 900555"),
            // A personal number on a page naming her: never offered.
            WebHit("https://social.example/rosa", "Rosa's page", "Text me on 07700 900123 or ring 020 7946 0000"),
            // Premium rate and emergency: never.
            WebHit("https://rosas.example/premium", "Rosa's Kitchen", "0909 123 4567 or 999"),
        )
        val f = BookingNumberRules.best("Rosa's Kitchen", hits, mine)!!
        assertEquals("+441134960123", f.e164)
        assertEquals("rosaskitchen.example", f.from)
        // Nothing naming the business: no number, however often one comes up.
        assertNull(BookingNumberRules.best("Rosa's Kitchen", hits.take(1) + hits.take(1), mine))
        assertNull(BookingNumberRules.best("Rosa's Kitchen", hits.drop(3), mine))
        // A landline wins a tie with a mobile.
        val tie = listOf(WebHit("https://oakfield.example", "Oakfield Barbers", "07700 900777 or 01632 960111"))
        assertEquals("+441632960111", BookingNumberRules.best("Oakfield Barbers", tie, mine)!!.e164)
    }

    private fun store(): Pair<BookingCalls, os.meka.core.sync.Replica> {
        val replica = os.meka.core.testing.SyncWorld().device("android").replica
        var n = 0
        return BookingCalls(replica, { "call-${++n}" }, { now }) to replica
    }

    @Test
    fun theFoundNumberIsOfferedAndUsedOnlyByHisTap() {
        val (calls, replica) = store()
        val id = calls.draft(BookingCallRules.proposal("Table for 4 at Rosa's Kitchen", today + 7, 19 * 60, null, today, 11 * 60)!!)!!
        var c = calls.all().single()
        assertTrue(BookingNumberRules.wanted(c, now))
        var r = BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single()
        assertEquals(BookingNumberRules.LOOKING, r.numberLine)
        assertFalse(r.canUseFound || r.canCall)
        assertEquals(BookingNumberRules.NOTHING_TO_USE, calls.useFound(id, mine))
        // A stale draft isn't looked up.
        assertFalse(BookingNumberRules.wanted(c, now + BookingNumberRules.FRESH_MS + 1))

        // The server's answer.
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.LOOKUP to BookingNumberRules.FOUND.fv(), BookingCallFields.LOOKED_UP_FOR to "Rosa's Kitchen".fv(),
            BookingCallFields.FOUND_NUMBER to "+441134960123".fv(), BookingCallFields.FOUND_FROM to "rosaskitchen.example".fv(),
        ))
        c = calls.all().single()
        assertFalse(BookingNumberRules.wanted(c, now))
        r = BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single()
        assertEquals("Obi found 01134 960123 · on rosaskitchen.example · check it's theirs", r.numberLine)
        assertTrue(r.canUseFound)
        assertFalse(r.canCall)
        assertEquals(BookingNumberRules.PICK, r.blocked)
        assertEquals(BookingNumberRules.PICK, calls.approve(id, mine, cal))

        assertEquals(BookingNumberRules.USED, calls.useFound(id, mine))
        assertEquals(BookingCallState.DRAFT, calls.all().single().state) // nothing rings by itself
        r = BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single()
        assertEquals("01134 960123 · Obi found it", r.numberLine)
        assertTrue(r.canCall && !r.canUseFound)
        assertEquals(BookingCallRules.APPROVED_LINE, calls.approve(id, mine, cal))
    }

    @Test
    fun aRenamedCardIsLookedUpAgainAndNothingFoundSaysSo() {
        val (calls, replica) = store()
        val id = calls.draft(BookingCallRules.proposal("Haircut with Oakfield Barbers", today + 2, null, null, today, 11 * 60)!!)!!
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.LOOKUP to BookingNumberRules.NOT_FOUND.fv(), BookingCallFields.LOOKED_UP_FOR to "Oakfield Barbers".fv(),
        ))
        assertEquals(BookingNumberRules.NONE, BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single().numberLine)
        // An old find for another name is never offered, and the new name is looked up.
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.LOOKUP to BookingNumberRules.FOUND.fv(), BookingCallFields.FOUND_NUMBER to "+441632960111".fv(),
        ))
        assertTrue(calls.edit(id, "Oakfield Cuts", null, null))
        val c = calls.all().single()
        assertTrue(BookingNumberRules.wanted(c, now))
        assertNull(BookingNumberRules.usable(c, mine))
        assertEquals(BookingNumberRules.LOOKING, BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single().numberLine)
        // A found number that's his own is never offered.
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.LOOKED_UP_FOR to "Oakfield Cuts".fv(), BookingCallFields.FOUND_NUMBER to "+447700900123".fv(),
        ))
        assertNull(BookingNumberRules.usable(calls.all().single(), mine))
        replica.commitLocal(EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.LOOKUP to BookingNumberRules.LOOKUP_FAILED.fv()))
        assertEquals(BookingNumberRules.FAILED, BookingCallRules.rows(calls.all(), mine, emptyList(), now, cal).single().numberLine)
    }
}
