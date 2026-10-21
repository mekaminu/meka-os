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

    // ---- Slice 2 ----

    private val lists = PeopleLists(family = setOf("Sam"), alwaysNotify = setOf("Jo"))
    private fun msg(name: String = "Sam", text: String? = "Urgent, call me when you can", at: Long = now - 60_000, group: String? = null) =
        CapturedItem(id = "w1", app = CaptureApp.WHATSAPP, kind = CaptureKind.MESSAGE, personName = name, text = text,
            conversation = group, atMs = at)

    @Test
    fun urgentFromFamilyOrAlwaysNotify() {
        assertEquals("Sam says it's urgent on WhatsApp: “Urgent, call me when you can.”", SpeakUpRules.urgentWords(msg(), lists, now))
        assertEquals("Jo says it's urgent on WhatsApp: “Urgent, call me when you can.”", SpeakUpRules.urgentWords(msg("Jo"), lists, now))
        // A stranger saying urgent, a family message that isn't urgent, a group chat, a stale re-post: silent.
        assertNull(SpeakUpRules.urgentWords(msg("Pat"), lists, now))
        assertNull(SpeakUpRules.urgentWords(msg(text = "Running late"), lists, now))
        assertNull(SpeakUpRules.urgentWords(msg(group = "Family"), lists, now))
        assertNull(SpeakUpRules.urgentWords(msg(at = now - SpeakUpRules.URGENT_FRESH_MS - 1), lists, now))
        // A call-assistant message: the summary first, cut to 14 words.
        val v = CapturedItem(id = "h1", app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = "Sam",
            text = "it's Sam please ring me back it's urgent", conversation = null, atMs = now, urgent = true,
            summary = "Sam needs you to ring back about the boys' pickup today because the car has broken down on the way")
        assertEquals("Sam left your call assistant an urgent message: “Sam needs you to ring back about the boys' pickup today because the car…”",
            SpeakUpRules.urgentWords(v, lists, now))
        // His name first, the sender's name kept as it is; the same never-list and its own switch.
        val words = SpeakUpRules.urgentWords(msg(), lists, now)!!
        val pick = SpeakUpRules.pickOne("urgent:w1", SpeakMoment.URGENT, words, SpeakUpSettings(), ctx, false, false, 0, now, "Alex", false)
        assertEquals("Alex, Sam says it's urgent on WhatsApp: “Urgent, call me when you can.”", pick?.line)
        assertNull(SpeakUpRules.pickOne("urgent:w1", SpeakMoment.URGENT, words, SpeakUpSettings(), SpeakContext(SpeakPlace.SPEAKER), false, false, 0, now, "Alex", false))
        assertNull(SpeakUpRules.pickOne("urgent:w1", SpeakMoment.URGENT, words, SpeakUpSettings(), ctx, true, false, 0, now, "Alex", false))
        assertNull(SpeakUpRules.pickOne("urgent:w1", SpeakMoment.URGENT, words, SpeakUpSettings(), ctx, false, false, 0, now, "Alex", true))
        assertNull(SpeakUpRules.pickOne("urgent:w1", SpeakMoment.URGENT, words,
            SpeakUpSettings(choices = mapOf(SpeakMoment.URGENT to false)), ctx, false, false, 0, now, "Alex", false))
    }

    @Test
    fun shhInTalkMutesForTwoHours() {
        val step = TalkFlow.heard(TalkSession(TalkPhase.LISTENING), "shh")
        assertEquals(listOf(TalkEffect.MuteSpeakingUp, TalkEffect.Speak(SpeakUpRules.MUTED_REPLY)), step.effects)
        assertTrue(step.session.endAfterSpeaking)
        assertEquals(listOf(TalkEffect.End), TalkFlow.spoke(step.session).effects)
        // "Not now" turning down cards on offer, or answering the hello, stays that answer; "shh" still mutes there.
        assertTrue(SpeakUpRules.mutesInTalk("not now, Ada", answersSomething = false))
        assertFalse(SpeakUpRules.mutesInTalk("not now", answersSomething = true))
        assertTrue(SpeakUpRules.mutesInTalk("shh", answersSomething = true))
        val hello = TalkFlow.spoke(TalkFlow.greet("Morning. What should I call you?").session).session
        assertFalse(TalkEffect.MuteSpeakingUp in TalkFlow.heard(hello, "not now").effects)
        assertTrue(TalkEffect.MuteSpeakingUp in TalkFlow.heard(hello, "quiet please").effects)
    }
}
