package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Train-time queue (4) and Reading's commute slots: the train he's on, and Today's line while he rides it. */
class TrainRideTest {
    @BeforeTest
    fun route() {
        Household.set(Household.names.copy(route = RouteRules.parse(InventedRoute.TYPED), homeStation = "Oakfield",
            walkHomeMin = 10, walkWorkMin = 15))
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Friday 9 October 2026 (BST). Work 09:00 with a 1 h 20 commute: he usually leaves home at 07:40.
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0, day: Long = fri) = cal.toEpochMs(day, h * 60 + m)
    private val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)
    private val change = "Oakfield Junction"

    private val morning = listOf(
        TrainDeparture(7 * 60 + 30, 7 * 60 + 30, arrMin = 8 * 60 + 2),
        TrainDeparture(7 * 60 + 42, 7 * 60 + 44, platform = "3", arrMin = 8 * 60 + 14),
        TrainDeparture(7 * 60 + 54, 7 * 60 + 54, arrMin = 8 * 60 + 26),
    )

    private fun board(list: List<TrainDeparture>, checked: Long, dir: String = TrainBoard.AM) =
        TrainBoard(list, dir, checked, TrainBoard.OK, checked)

    private fun leave(list: List<TrainDeparture>, nowMs: Long) =
        RouteRules.leaveBy(board(list, nowMs), RouteRules.Commute.MORNING, office!!, nowMs, cal)

    @Test
    fun theLeaveBysTrainIsKeptAfterItLeavesTheBoardAndCountsDownToTheChange() {
        // 07:20: the leave-by picks the 07:42 (2 min late).
        var seen = TrainRideRules.merge(null, board(morning, at(7, 20)), leave(morning, at(7, 20)), at(7, 20), cal)
        assertEquals(7 * 60 + 42, seen!!.yours)
        assertNull(TrainRideRules.ride(seen, office, change, "Oakfield", at(7, 30), cal)) // still at home
        // 07:50: the 07:42 has gone from the board; the leave-by now names a later train, which mustn't take its place.
        val later = morning.drop(2)
        seen = TrainRideRules.merge(seen, board(later, at(7, 50)), leave(later, at(7, 50)), at(7, 50), cal)
        assertEquals(7 * 60 + 42, seen!!.yours)
        // Kept across a restart.
        seen = TrainRideRules.decode(TrainRideRules.encode(seen))
        val ride = TrainRideRules.ride(seen, office, change, "Oakfield", at(8, 0), cal)!!
        // Due 08:14, two minutes late: 16 minutes left.
        assertEquals(TrainRide(7 * 60 + 42, 16, change, true), ride)
        assertEquals("On the 07:42 · 16 min to Oakfield Junction", TrainRideRules.words(ride))
        assertNull(TrainRideRules.ride(seen, office, change, "Oakfield", at(8, 17), cal)) // arrived
        // Another day: nothing.
        assertNull(TrainRideRules.ride(seen, office, change, "Oakfield", at(8, 0, fri + 7), cal))
    }

    @Test
    fun withoutATimeToTheTrainItsTheTrainHeUsuallyTakesAndNeverACancelledOne() {
        val seen = TrainRideRules.merge(null, board(morning, at(7, 25)), null, at(7, 25), cal)
        // 07:50: the 07:30 left before his usual time (07:40) so isn't his; the 07:42 is.
        assertEquals(7 * 60 + 42, TrainRideRules.ride(seen, office, change, "Oakfield", at(7, 50), cal)!!.depMin)
        val gone = morning.toMutableList().also { it[1] = TrainDeparture(7 * 60 + 42, null, cancelled = true, arrMin = 8 * 60 + 14) }
        val s2 = TrainRideRules.merge(null, board(gone, at(7, 25)), null, at(7, 25), cal)
        assertNull(TrainRideRules.ride(s2, office, change, "Oakfield", at(7, 50), cal))
        // Not an office day: nothing.
        assertNull(TrainRideRules.ride(seen, null, change, "Oakfield", at(7, 50), cal))
        // A stale board adds nothing but keeps what was seen.
        assertEquals(seen, TrainRideRules.merge(seen, board(emptyList(), at(6)), null, at(7, 26), cal))
    }

    @Test
    fun todaysLineLeadsWithTheRideThenTheQueueAndTheBookOnTheGo() {
        val items = listOf(QueueItem("q1", "https://bbc.co.uk/news/1", "A story", QueueKind.ARTICLE, "bbc.co.uk", 1L, 0L))
        val book = ReadingItem("r1", "Dune", "Frank Herbert", ReadingKind.BOOK, "", ReadingUnit.PAGE, 120, 320,
            ReadingState.READING, 1L, 1L, 2L, 0L, false)
        val part = ReadingRules.trainPart(listOf(book))!!
        assertEquals("Dune at 37%", part.text)
        val ride = TrainRide(7 * 60 + 42, 16, change, true)
        val line = QueueRules.trainLine(items, office, at(8, 0), cal, ride, part)!!
        assertEquals("On the 07:42 · 16 min to Oakfield Junction · 1 article · Dune at 37%", line.text)
        assertEquals("On the 07:42 train, 16 minutes to Oakfield Junction. For the train: 1 article saved. Reading Dune, 37%", line.spoken)
        // Without a ride, during the commute: the queue and the book.
        assertEquals("For the train · 1 article · Dune at 37%", QueueRules.trainLine(items, office, at(7, 10), cal, null, part)!!.text)
        // The book alone still makes a line; the evening ride home past the commute's window too.
        val home = TrainRide(17 * 60 + 52, 40, "Oakfield", false)
        assertEquals("On the 17:52 · 40 min to Oakfield · Dune at 37%", QueueRules.trainLine(emptyList(), office, at(19, 30), cal, home, part)!!.text)
        assertNull(QueueRules.trainLine(emptyList(), office, at(7, 10), cal, ride, null))
        assertNull(QueueRules.trainLine(items, office, at(12), cal, null, part))
    }
}
