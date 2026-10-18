package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Done for the day, the first train: tomorrow's usual train from the recent mornings' leave-by picks. */
class FirstTrainTest {
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun m(h: Int, min: Int) = h * 60 + min

    private fun seen(day: Long, yours: Int?, dep: TrainDeparture, dir: String = TrainBoard.AM) =
        TrainRideSeen(day, dir, listOf(dep), yours)

    @Test
    fun aMorningsTrainIsRecordedOnceAndReplacedTheSameDay() {
        var h = FirstTrainRules.record(emptyList(), seen(fri, m(7, 42), TrainDeparture(m(7, 42), m(7, 42))))
        assertEquals(listOf(MorningTrain(fri, m(7, 42), 0)), h)
        // Later the same morning it's 6 min late: the day's entry follows.
        h = FirstTrainRules.record(h, seen(fri, m(7, 42), TrainDeparture(m(7, 42), m(7, 48))))
        assertEquals(listOf(MorningTrain(fri, m(7, 42), 6)), h)
        // An evening board or no pick records nothing.
        assertEquals(h, FirstTrainRules.record(h, seen(fri, m(17, 52), TrainDeparture(m(17, 52), m(17, 52)), TrainBoard.PM)))
        assertEquals(h, FirstTrainRules.record(h, seen(fri + 1, null, TrainDeparture(m(7, 42), m(7, 42)))))
        // A cancelled pick, and the round trip through the device-local text.
        h = FirstTrainRules.record(h, seen(fri + 3, m(7, 30), TrainDeparture(m(7, 30), null, cancelled = true)))
        assertEquals(h, FirstTrainRules.decode(FirstTrainRules.encode(h)))
        assertEquals(MorningTrain.CANCELLED, h.last().late)
        assertEquals(emptyList(), FirstTrainRules.decode("junk;1:2"))
    }

    @Test
    fun tomorrowNamesTheUsualTrainWhenToLeaveAndHowItHasRun() {
        val h = (0..3).map { MorningTrain(fri - 7 + it, m(7, 42), 0) } + MorningTrain(fri - 3, m(7, 30), 0)
        // He leaves home at 07:40 with 10 min to the train: expects about 07:50; the 07:42 is the most common.
        val l = FirstTrainRules.line(h, fri + 1, leaveHomeMin = m(7, 40), walkMin = 10)!!
        assertEquals("First train · the 07:42 · leave by 07:32 · on time the last 4 times", l.text)
        assertEquals("Your first train tomorrow is usually the 07:42; leave by 07:32; it's been on time the last 4 times.", l.spoken)
        assertEquals(false, l.lit)
        // Often late: lit, and said.
        val late = (0..3).map { MorningTrain(fri - 7 + it, m(7, 42), if (it % 2 == 0) 8 else MorningTrain.CANCELLED) }
        val lit = FirstTrainRules.line(late, fri + 1, m(7, 40), 10)!!
        assertEquals("First train · the 07:42 · leave by 07:32 · late or cancelled 4 of the last 4 times", lit.text)
        assertTrue(lit.lit)
        // Without the time to the train: the train only.
        assertEquals("First train · the 07:42", FirstTrainRules.line(h.take(2), fri + 1, m(7, 40), null)!!.text)
    }

    @Test
    fun nothingOnADayOffFromOldMorningsOrFarFromTomorrowsTime() {
        val h = listOf(MorningTrain(fri - 1, m(7, 42), 0))
        assertNull(FirstTrainRules.line(h, fri + 1, leaveHomeMin = null, walkMin = 10))
        assertNull(FirstTrainRules.line(h, fri + 40, m(7, 40), 10))
        // Tomorrow's one-off commute leaves an hour earlier: the 07:42 isn't tomorrow's train.
        assertNull(FirstTrainRules.line(h, fri + 1, m(6, 40), 10))
        assertNull(FirstTrainRules.line(emptyList(), fri + 1, m(7, 40), 10))
    }
}
