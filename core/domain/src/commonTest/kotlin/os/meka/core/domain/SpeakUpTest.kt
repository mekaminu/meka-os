package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speaking up, slice 1: which moments Ada says out loud, where, and never when. */
class SpeakUpTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val now = cal.toEpochMs(20_000, 7 * 60 + 20)
    private val ctx = SpeakContext(SpeakPlace.PRIVATE)
    private fun train(key: String = "trainlive:1") = Notice(
        key = key, source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP, title = "South Rail 07:42 cancelled", text = "",
        atMs = now, target = NoticeTarget.TODAY, speak = SpeakMoment.TRAIN, spoken = "your 07:42 is cancelled.",
    )
    private val silent = Notice(
        key = "bins:1", source = NoticeSource.BINS, tier = NoticeTier.HEADS_UP, title = "Bins out", text = "",
        atMs = now, target = NoticeTarget.TODAY,
    )

    private fun check(
        n: Notice = train(), s: SpeakUpSettings = SpeakUpSettings(), c: SpeakContext = ctx, quiet: Boolean = false,
        meeting: Boolean = false, today: Int = 0, said: Boolean = false,
    ) = SpeakUpRules.check(n, s, c, quiet, meeting, today, said, now)

    @Test
    fun eachMomentsRule() {
        // Cancelled, or 10+ late, is said with the next good train and when to leave; 5 late stays a silent notice.
        val good = TrainDeparture(depMin = 7 * 60 + 54, expMin = 7 * 60 + 54, platform = "3")
        assertEquals("your 07:42 is cancelled. The 07:54 is on time, platform 3. Leave by 07:44.",
            SpeakUpRules.trainWords(TrainDeparture(7 * 60 + 42, null, cancelled = true), good, 7 * 60 + 44))
        assertEquals("your 07:42 is running 12 minutes late.",
            SpeakUpRules.trainWords(TrainDeparture(7 * 60 + 42, 7 * 60 + 54), null, null))
        assertNull(SpeakUpRules.trainWords(TrainDeparture(7 * 60 + 42, 7 * 60 + 47), good, null))
        assertEquals("it's time to leave for Dentist. It starts at 14:00.", SpeakUpRules.leaveWords("Dentist", "14:00"))
        // Starting with his name, or "Sir" when he chose it; capitalised with neither.
        assertEquals("Alex, your 07:42 is cancelled.", SpeakUpRules.line("your 07:42 is cancelled.", SpeakUpRules.address("Alex Smith", false)))
        assertEquals("Sir, your 07:42 is cancelled.", SpeakUpRules.line("your 07:42 is cancelled.", SpeakUpRules.address("Alex", true)))
        assertEquals("Your 07:42 is cancelled.", SpeakUpRules.line("your 07:42 is cancelled.", SpeakUpRules.address(null, false)))
        assertEquals(SpeakHold.OFF, check(silent))
        assertNull(check())
        assertEquals(SpeakHold.OFF, check(s = SpeakUpSettings(choices = mapOf(SpeakMoment.TRAIN to false))))
    }

    @Test
    fun theNeverList() {
        assertEquals(SpeakHold.QUIET_HOURS, check(quiet = true))
        assertEquals(SpeakHold.MEETING, check(meeting = true))
        assertEquals(SpeakHold.ON_CALL, check(c = ctx.copy(onCall = true)))
        assertEquals(SpeakHold.TALKING, check(c = ctx.copy(talking = true)))
        assertEquals(SpeakHold.ALREADY_SAID, check(said = true))
        val meeting = CalendarEvent("e", "Standup", now - 60_000, now + 600_000, false, null, "google", null, null, guests = listOf("Sam"))
        assertTrue(SpeakUpRules.inMeeting(listOf(meeting), now))
        assertFalse(SpeakUpRules.inMeeting(listOf(meeting.copy(guests = emptyList())), now)) // a block of his own
        assertFalse(SpeakUpRules.inMeeting(listOf(meeting.copy(allDay = true)), now))
    }

    @Test
    fun theDailyCapAndLess() {
        assertNull(check(today = 5))
        assertEquals(SpeakHold.DAILY_CAP, check(today = 6))
        assertEquals(SpeakHold.DAILY_CAP, check(s = SpeakUpSettings(often = SpeakOften.LESS), today = 3))
    }

    @Test
    fun muteForTwoHours() {
        assertEquals(SpeakHold.MUTED, check(s = SpeakUpSettings(mutedUntilMs = now + 1)))
        assertNull(check(s = SpeakUpSettings(mutedUntilMs = now)))
        assertTrue(SpeakUpRules.isMute("Shh"))
        assertTrue(SpeakUpRules.isMute("shhh"))
        assertTrue(SpeakUpRules.isMute("not now, Ada"))
        assertTrue(SpeakUpRules.isMute("Quiet please"))
        assertFalse(SpeakUpRules.isMute("not now but remind me later"))
        assertFalse(SpeakUpRules.isMute("what's on now"))
        val v = SpeakUpRules.view(SpeakUpSettings(mutedUntilMs = now + SpeakUpRules.MUTE_MS), 2, now, cal)
        assertEquals("Muted till 09:20", v.sections.last().status)
        assertEquals(SpeakUpRules.UNMUTE_ACTION, v.sections.last().action)
        assertEquals("Said today: 2 of 6", v.sections.first { it.label == SpeakUpRules.OFTEN_LABEL }.steps.single())
    }

    @Test
    fun privacyRouting() {
        // No speaker without earbuds unless at home on the stand in the evening; the Mac only while in use.
        assertEquals(SpeakPlace.PRIVATE, SpeakUpRules.place(BargeInOutput.HEADPHONES, false, 8 * 60))
        assertEquals(SpeakPlace.SPEAKER, SpeakUpRules.place(BargeInOutput.SPEAKER, false, 20 * 60))
        assertEquals(SpeakPlace.SPEAKER, SpeakUpRules.place(BargeInOutput.SPEAKER, true, 8 * 60))
        assertEquals(SpeakPlace.BEDSIDE, SpeakUpRules.place(BargeInOutput.SPEAKER, true, 20 * 60))
        assertEquals(SpeakPlace.MAC, SpeakUpRules.macPlace(30_000))
        assertEquals(SpeakPlace.SPEAKER, SpeakUpRules.macPlace(10 * 60_000))
        assertEquals(SpeakHold.NOT_PRIVATE, check(c = SpeakContext(SpeakPlace.SPEAKER)))
        // One at a time: the train before time to leave, whatever order they posted in.
        val leave = train("event:1").copy(speak = SpeakMoment.LEAVE_NOW, spoken = SpeakUpRules.leaveWords("Gym", "18:00"))
        val pick = SpeakUpRules.pick(listOf(leave, silent, train()), SpeakUpSettings(), ctx, false, false, 0, now, "Alex") { false }
        assertEquals("trainlive:1", pick?.key)
        assertEquals("Alex, your 07:42 is cancelled.", pick?.line)
        assertEquals("event:1", SpeakUpRules.pick(listOf(leave, train()), SpeakUpSettings(), ctx, false, false, 0, now, null) { it == "trainlive:1" }?.key)
        // The choices round-trip.
        val c = mapOf(SpeakMoment.LEAVE_NOW to false)
        assertEquals(c, SpeakUpRules.decodeChoices(SpeakUpRules.encodeChoices(c)))
    }
}
