package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests from people Meka watches, slice 2: the open cards as synced entities, so both apps show the same Needs you. */
class RequestCardsTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(day: Long, minute: Int) = cal.toEpochMs(day, minute)
    private fun cards(d: Device) = RequestCards(d.replica, { world.clock.nowMs }, cal)
    private val fold = cards(a)
    private val mac = cards(m)

    init { world.clock.nowMs = at(fri, 14 * 60 + 3) }

    private val msg = RequestMessage("wa-1", "Wife", "can you pick up the dry cleaning tomorrow?", at(fri, 14 * 60 + 2))
    private val pickUp = RequestProposal(RequestKind.TASK, "Pick up dry cleaning", fri + 1, null)
    private val wfh = RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", CivilDate.toEpochDay(2026, 10, 15), null)

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    @Test
    fun whatTheFoldReadShowsOnTheMacAsTheSameCard() {
        val made = fold.save(msg, listOf(pickUp, wfh))
        assertEquals(listOf("wa-1#0", "wa-1#1"), made.map { it.id })
        syncBoth()
        val onMac = mac.open()
        assertEquals(made, onMac)
        assertEquals("From Wife · 14:02", onMac[0].from)
        assertEquals("“can you pick up the dry cleaning tomorrow?”", onMac[0].quote)
        assertEquals("Add task: Pick up dry cleaning · Tomorrow", onMac[0].action)
        assertEquals("Sets Thu 15 Oct to Home and blocks the day", onMac[1].detail)
    }

    @Test
    fun aRepostOrTheSameRequestAgainMakesNoSecondCard() {
        assertEquals(1, fold.save(msg, listOf(pickUp)).size)
        assertTrue(fold.save(msg, listOf(pickUp)).isEmpty()) // WhatsApp re-posts the unread message
        val again = RequestMessage("wa-2", "Wife", "don't forget the dry cleaning tomorrow!", at(fri, 15 * 60))
        assertTrue(fold.save(again, listOf(pickUp.copy(title = "pick up dry-cleaning"))).isEmpty())
        assertEquals(1, fold.open().size)
    }

    @Test
    fun notATaskOnTheMacClearsBothBlanksTheTextAndNothingComesBack() {
        fold.save(msg, listOf(pickUp))
        syncBoth()
        assertTrue(mac.resolve("wa-1#0", RequestResolution.DECLINED))
        assertFalse(mac.resolve("wa-1#0", RequestResolution.DECLINED))
        syncBoth()
        assertTrue(fold.open().isEmpty())
        assertNull(fold.find("wa-1#0"))
        val stored = a.replica.entities(EntityTypes.REQUEST_CARD).single()
        assertNull(stored[RequestCardFields.TEXT].textOrNull)
        assertEquals("declined", stored[RequestCardFields.RESOLUTION].textOrNull)
        // The listener reads the same message again: a resolved card is never revived.
        assertTrue(fold.save(msg, listOf(pickUp)).isEmpty())
        assertTrue(fold.open().isEmpty())
    }

    @Test
    fun addOnBothDevicesAtOnceStillLeavesItResolved() {
        fold.save(msg, listOf(pickUp))
        syncBoth()
        assertTrue(fold.resolve("wa-1#0", RequestResolution.ADDED))
        assertTrue(mac.resolve("wa-1#0", RequestResolution.DECLINED))
        syncBoth()
        assertTrue(fold.open().isEmpty())
        assertTrue(mac.open().isEmpty())
    }

    @Test
    fun aCardLeavesOnceItsDayHasGoneOrAfterAWeek() {
        fold.save(msg, listOf(pickUp))
        fold.save(RequestMessage("wa-3", "Wife", "call your mum", at(fri, 14 * 60 + 5)), listOf(RequestProposal(RequestKind.REMINDER, "Call your mum", null, null)))
        assertEquals(2, fold.open().size)
        world.clock.nowMs = at(fri + 2, 9 * 60) // Sunday: the dry cleaning was for Saturday
        assertEquals(listOf("Remind me: Call your mum"), fold.open().map { it.action })
        world.clock.nowMs = at(fri + 8, 9 * 60)
        assertTrue(fold.open().isEmpty())
    }

    @Test
    fun theWhenLineFollowsTheDayItIsShown() {
        fold.save(msg, listOf(pickUp))
        world.clock.nowMs = at(fri + 1, 8 * 60)
        assertEquals("Add task: Pick up dry cleaning · Today", fold.open().single().action)
    }
}
