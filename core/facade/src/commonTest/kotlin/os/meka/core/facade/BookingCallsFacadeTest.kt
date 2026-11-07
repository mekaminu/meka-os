package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskUndo
import os.meka.core.domain.BookingCallRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Obi on calls, slice 2a: the orb's book_call card makes a call card; the Mac edits it and taps Call now. */
class BookingCallsFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London
    private val server = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theOrbsCardMakesACallCardTheMacApproves() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val offer = fold.livePropose(mapOf("kind" to "book_call", "title" to "Table for 4 at Rosa's Kitchen", "date" to "2026-10-31", "time" to "19:00"))
        val card = assertNotNull(offer.card)
        assertIs<AskProposal.BookCall>(card.proposal)
        assertEquals(BookingCallRules.CARD_BUTTON, card.button)
        val done = fold.doAsk(card)
        val row = fold.workingOnView().calls.single()
        assertEquals("Rosa's Kitchen", row.business)
        assertEquals(BookingCallRules.NO_NUMBER, row.blocked)
        fold.syncNow(); mac.syncNow()
        val id = mac.bookingCallRows().single().id
        assertEquals(BookingCallRules.NO_NUMBER, mac.callNowBookingCall(id))
        assertTrue(mac.editBookingCall(id, null, "01234 567890", "a quiet table"))
        assertEquals(5, mac.setBookingCallPeople(id, 5).calls.single().peopleTile.toInt())
        assertEquals(BookingCallRules.APPROVED_LINE, mac.callNowBookingCall(id))
        mac.syncNow(); fold.syncNow()
        val approved = fold.bookingCallRows().single()
        assertFalse(approved.canCall)
        assertTrue(approved.canCancel)
        // Slice 3: Hang up is only for a call Obi is on, Add to calendar only for a booked card.
        assertEquals(os.meka.core.domain.BookingLiveRules.NOT_ON_CALL, fold.hangUpBookingCall(approved.id))
        assertEquals(BookingCallRules.GONE, fold.addBookingCallToCalendar(approved.id))
        assertTrue(approved.showBrief && !approved.live)
        // The Ask card's Undo still cancels it before anything rings.
        assertTrue(fold.undoAsk(assertIs<AskUndo.CancelBookingCall>(done.undo)))
        assertTrue(fold.bookingCallRows().isEmpty())
    }
}
