package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSpeedTest {
    private val now = 1_791_476_100_000L
    private val hour = 60 * 60_000L

    @Test
    fun dueOnceADay() {
        assertTrue(DeviceSpeedRules.due(null, now))
        assertFalse(DeviceSpeedRules.due(now - hour, now))
        assertFalse(DeviceSpeedRules.due(now - 19 * hour, now))
        assertTrue(DeviceSpeedRules.due(now - 20 * hour, now))
        assertTrue(DeviceSpeedRules.due(now + hour, now)) // the clock moved back
    }

    @Test
    fun theLastDaysTurnsBecomeCountsAveragesAndTheSlowest() {
        val s = DeviceSpeedRules.summary(
            listOf(
                TurnTrace(TurnKind.HELLO, now - hour, 300),
                TurnTrace(TurnKind.HELLO, now - hour, 600),
                TurnTrace(TurnKind.TALK, now - hour, 1_200),
                TurnTrace(TurnKind.TALK, now - hour, 2_400),
                TurnTrace(TurnKind.TALK, now - 25 * hour, 9_000),
                TurnTrace(TurnKind.TALK, now - hour, null),
            ),
            "phone", now,
        )!!
        assertEquals(DeviceSpeedSummary("phone", now, 2, 450, 600, 2, 1_800, 2_400), s)
        assertEquals(
            "Phone · 2 hellos avg 0.5 s, slowest 0.6 s (aim 0.6 s) · 2 answers avg 1.8 s, slowest 2.4 s (aim 1.5 s), over aim",
            DeviceSpeedRules.line(s),
        )
        assertTrue(DeviceSpeedRules.overAim(s))
        assertNull(DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.TALK, now - 25 * hour, 900)), "phone", now))
    }

    @Test
    fun oneKindAloneAndLabelsAreLettersOnly() {
        val s = DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.HELLO, now - hour, 400)), "Mac", now)!!
        assertEquals("Mac · 1 hello avg 0.4 s, slowest 0.4 s (aim 0.6 s)", DeviceSpeedRules.line(s))
        assertFalse(DeviceSpeedRules.overAim(s))
        assertEquals("Macscript", DeviceSpeedRules.label("Mac<script>1"))
        assertEquals("device", DeviceSpeedRules.label("123"))
    }

    @Test
    fun theDaysSlowestWorkAndFeelTimesRideAlong() {
        val work = listOf(
            SpeedEntry("Whole-app refresh", 640, 90, 40, 6),
            SpeedEntry("Money view", 1_800, 900, 12, 3),
            SpeedEntry("Fast thing", 80, 60, 200, 0),
            SpeedEntry("Mail · list", 300, 300, 1, 1),
            SpeedEntry("A tap waiting for the core", 150, 150, 2, 2),
            SpeedEntry("One piece of core work", 120, 50, 9, 1),
            SpeedEntry("Sixth <slow>", 110, 110, 1, 1),
            SpeedEntry("***", 5_000, 5_000, 1, 1),
        )
        val feel = listOf(
            FeelEntry(FeelMoment.COLD_START, 2_100, null, 900, 3, 1),
            FeelEntry(FeelMoment.TAB, 90, "Calendar", 40, 40, 0),
        )
        val s = DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.HELLO, now - hour, 400)), "phone", now, work, feel)!!
        // Slow ones only (over 100 ms), slowest first, five at most; a name with nothing printable is left out.
        assertEquals(
            listOf("Money view", "Whole-app refresh", "Mail · list", "A tap waiting for the core", "One piece of core work"),
            s.slow.map { it.name },
        )
        assertEquals(DeviceSlowWork("Money view", 1_800, 12, 3), s.slow.first())
        assertEquals(
            "Phone · Money view 1.8 s (12 times, 3 slow) · Whole-app refresh 640 ms (40 times, 6 slow) · Mail · list 300 ms · " +
                "A tap waiting for the core 150 ms (2 times, 2 slow) · One piece of core work 120 ms (9 times, 1 slow)",
            DeviceSpeedRules.workLine(s),
        )
        assertTrue(DeviceSpeedRules.workOver(s))
        assertEquals(
            "Phone: Cold start to Today · 2.1 s · aim 1.5 s · 3 times, 1 over; Tab switch · 90 ms (Calendar) · aim 150 ms · 40 times",
            DeviceSpeedRules.feelLine(s),
        )
        assertTrue(DeviceSpeedRules.feelOver(s))
        // Nothing slow and nothing timed: no lines.
        val quiet = DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.HELLO, now - hour, 400)), "Mac", now, work.take(0) + work[2])!!
        assertNull(DeviceSpeedRules.workLine(quiet))
        assertNull(DeviceSpeedRules.feelLine(quiet))
        assertFalse(DeviceSpeedRules.workOver(quiet))
        assertFalse(DeviceSpeedRules.feelOver(quiet))
        assertEquals("Sixth slow", DeviceSpeedRules.workName("Sixth <slow>"))
        assertNull(DeviceSpeedRules.workName("***"))
        // Slow work alone sends nothing: the summary goes with a turn.
        assertNull(DeviceSpeedRules.summary(emptyList(), "phone", now, work, feel))
    }

    @Test
    fun theDaysLiveTalksBecomeTheLiveLine() {
        val sp = BargeInOutput.SPEAKER
        val hp = BargeInOutput.HEADPHONES
        var talks = emptyList<LiveDayRules.Talk>()
        talks = LiveDayRules.added(talks, LiveDayRules.Talk(now - 25 * hour, sp, 9, 9, 9_000), now) // over a day ago: dropped
        assertTrue(talks.isEmpty())
        talks = LiveDayRules.added(talks, LiveDayRules.Talk(now - 3 * hour, sp, 1, 2, 1_200), now)
        talks = LiveDayRules.added(talks, LiveDayRules.Talk(now - 2 * hour, hp, 1, 0, 0), now)
        talks = LiveDayRules.added(talks, LiveDayRules.Talk(now - hour, sp, 0, 1, 300), now)
        assertEquals(talks, LiveDayRules.decode(LiveDayRules.encode(talks)))
        assertEquals(emptyList(), LiveDayRules.decode("x;1,LOUD,1,1,1;"))
        val day = LiveDayRules.day(talks, now)!!
        assertEquals(DeviceLiveDay(talks = 3, speakerTalks = 2, cutOffs = 2, speakerCutOffs = 1, waits = 3, longestWaitMs = 1_200), day)
        val s = DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.TALK, now - hour, 900)), "phone", now, live = day)!!
        assertEquals(
            "Phone · Live: 3 talks (2 on the speaker) · Google stopped Obi 2 times (1 on the speaker) · waited 3 times for the network, longest 1.2 s",
            DeviceSpeedRules.liveLine(s),
        )
        assertTrue(DeviceSpeedRules.liveOver(s))
        // A quiet day on the speaker: a notice.
        val quiet = s.copy(live = DeviceLiveDay(1, 1, 0, 0, 0, 0))
        assertEquals("Phone · Live: 1 talk on the speaker · Google never stopped Obi · no waits for the network", DeviceSpeedRules.liveLine(quiet))
        assertFalse(DeviceSpeedRules.liveOver(quiet))
        // Stopped only on headphones (he talked over Obi, as he may): no warning.
        val hpOnly = s.copy(live = DeviceLiveDay(2, 0, 2, 0, 0, 0))
        assertEquals("Phone · Live: 2 talks on headphones · Google stopped Obi 2 times · no waits for the network", DeviceSpeedRules.liveLine(hpOnly))
        assertFalse(DeviceSpeedRules.liveOver(hpOnly))
        // No Live talk: no line.
        assertNull(DeviceSpeedRules.liveLine(s.copy(live = null)))
        assertNull(LiveDayRules.day(talks, now + 2 * DeviceSpeedRules.WINDOW_MS))
    }
}
