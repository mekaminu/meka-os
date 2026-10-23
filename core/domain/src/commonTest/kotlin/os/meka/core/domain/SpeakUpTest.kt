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
        // Slice 5: Android Auto is the car, even with no headset listed (its speakers are the car's own).
        assertEquals(SpeakPlace.PRIVATE, SpeakUpRules.place(BargeInOutput.SPEAKER, false, 8 * 60, inCar = true))
        assertEquals(SpeakPlace.PRIVATE, SpeakUpRules.place(BargeInOutput.SPEAKER, true, 20 * 60, inCar = true))
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

    /** Slice 3: the evening heads-up for tomorrow stacking up, once, 19:30 to 22:00, never for another day. */
    @Test
    fun tomorrowStackingUpInTheEvening() {
        val day = cal.epochDayOf(now)
        val items = listOf(
            PressureItem(PressureKind.DUE, "Trip form", "t1"), PressureItem(PressureKind.BILL, "Council tax"),
            PressureItem(PressureKind.LATE, "Gym", "t2", evening = true),
        )
        val fix = PressureFix("t2", "Gym", day + 3, "Move “Gym” to Sat", earlier = false)
        val p = DayPressure(day + 1, "Tomorrow", false, items, "Tomorrow is stacking up: Trip form, Council tax and Gym",
            "Stacking up: Trip form, Council tax and Gym", "Tomorrow is stacking up: Trip form, Council tax and Gym.", listOf(fix))
        val n = SpeakUpRules.pressureNotice(p, now, cal)!!
        assertEquals("pressure:${day + 1}", n.key)
        assertEquals(NoticeSource.PRESSURE, n.source)
        assertEquals("Tomorrow is stacking up", n.title)
        assertEquals("Trip form, Council tax and Gym · Move “Gym” to Sat", n.text)
        assertEquals(cal.toEpochMs(day, SpeakUpRules.PRESSURE_AT_MIN), n.atMs)
        assertEquals(cal.toEpochMs(day, SpeakUpRules.PRESSURE_UNTIL_MIN), n.expiresAtMs)
        assertEquals(SpeakMoment.PRESSURE, n.speak)
        assertEquals("Alex, tomorrow is stacking up: Trip form, Council tax and Gym. Today has a way to lighten it.",
            SpeakUpRules.line(n.spoken!!, "Alex"))
        assertEquals("Tomorrow is stacking up: Trip form, Council tax and Gym.", SpeakUpRules.pressureWords(p.copy(fixes = emptyList())))
        // Only tomorrow's, and not once the evening is over.
        assertNull(SpeakUpRules.pressureNotice(p.copy(epochDay = day + 2), now, cal))
        assertNull(SpeakUpRules.pressureNotice(p, cal.toEpochMs(day, SpeakUpRules.PRESSURE_UNTIL_MIN), cal))
        assertNull(SpeakUpRules.pressureNotice(null, now, cal))
        // Its own switch, on to start, listed before the reply (off to start).
        assertTrue(SpeakUpSettings().isOn(SpeakMoment.PRESSURE))
        assertEquals(SpeakMoment.PRESSURE.label, SpeakUpRules.view(SpeakUpSettings(), 0, now, cal).sections[SpeakMoment.entries.size - 2].label)
    }

    /** Slice 4: a reply to an email he put on Waiting for is a heads-up; Ada says it only fresh and only if he turned it on. */
    @Test
    fun aReplyHeWasWaitingForIsANoticeAndSaidOnlyWhenOn() {
        val sentDay = cal.epochDayOf(now) - 5
        val base = MailRenewalRules.read("mf1", MailFollowUpRules.serverFields(MailFollowUp("Sam Taylor", "Reply about Quote"), "Personal", "Re: Quote for the kitchen", sentDay))!!
        val f = base.copy(state = MailFindState.ADDED, answeredAtMs = now - 10 * 60_000L, answeredWhy = MailFollowUpRules.ANSWERED_REPLIED)
        val w = WaitingItem(
            id = MailPromiseRules.waitingId("mf1"), title = "Reply about Quote for the kitchen", who = "Sam Taylor", notes = null,
            sinceMs = now - 86_400_000L * 5, lastChasedMs = null, chaseDay = null, state = DueState.NONE, meta = "", hasConflict = false,
        )
        val n = SpeakUpRules.replyNotices(listOf(f), listOf(w), now).single()
        assertEquals(NoticeSource.REPLIED, n.source)
        assertEquals("Sam Taylor replied", n.title)
        assertEquals("Quote for the kitchen · Personal · Got it in Time to chase if that settles it", n.text)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        assertEquals(SpeakMoment.REPLY, n.speak)
        // Their name keeps its capital after his ("Alex, Sam Taylor replied …").
        val on = SpeakUpSettings(choices = mapOf(SpeakMoment.REPLY to true))
        val pick = SpeakUpRules.pick(listOf(n), on, ctx, false, false, 0, now, "Alex") { false }
        assertEquals("Alex, Sam Taylor replied to your email about Quote for the kitchen.", pick!!.line)
        // Off to start: the notice posts, Ada stays quiet until he turns the moment on.
        assertFalse(SpeakUpSettings().isOn(SpeakMoment.REPLY))
        assertEquals(SpeakHold.OFF, check(n))
        assertNull(check(n, s = on))
        // An hour on it is a notification only; after 12 hours, nothing.
        val old = SpeakUpRules.replyNotices(listOf(f.copy(answeredAtMs = now - 2 * 3_600_000L)), listOf(w), now).single()
        assertNull(old.speak)
        assertTrue(SpeakUpRules.replyNotices(listOf(f.copy(answeredAtMs = now - 13 * 3_600_000L)), listOf(w), now).isEmpty())
        // Got it / Not yet since, his own "wrote again", a closed Waiting for item, or a find not added: none.
        assertTrue(SpeakUpRules.replyNotices(listOf(f.copy(chase = ChaseMarks(seenAtMs = now))), listOf(w), now).isEmpty())
        assertTrue(SpeakUpRules.replyNotices(listOf(f.copy(answeredWhy = MailFollowUpRules.ANSWERED_WROTE_AGAIN)), listOf(w), now).isEmpty())
        assertTrue(SpeakUpRules.replyNotices(listOf(f), emptyList(), now).isEmpty())
        assertTrue(SpeakUpRules.replyNotices(listOf(f.copy(state = MailFindState.OPEN)), listOf(w), now).isEmpty())
        // Listed last in Speaking up, after Tomorrow stacking up; a stretched day holds it like other mail.
        assertEquals(SpeakMoment.REPLY.label, SpeakUpRules.view(SpeakUpSettings(), 0, now, cal).sections[SpeakMoment.entries.size - 1].label)
        assertTrue(AlertHoldRules.holds(n))
    }

    /** Quality gate slice 55: the pane shows the intro said short; the full how-to stays for How it works. */
    @Test
    fun theIntroIsSaidShortWithTheFullHowToKept() {
        val v = SpeakUpRules.view(SpeakUpSettings(), 0, now, cal)
        assertEquals(SpeakUpRules.INTRO_SHORT, v.introShort)
        assertEquals(SpeakUpRules.INTRO, v.intro)
        assertTrue(v.introShort.length <= 100, "the short intro is ${v.introShort.length} characters")
        assertTrue(v.intro.length > v.introShort.length)
        assertTrue(MailAutopilotPanelRules.hasMore(v.introShort, v.intro))
    }
}
