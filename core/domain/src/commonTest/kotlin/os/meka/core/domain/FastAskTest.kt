package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AI layer completeness: his fast from Ask and Talk, by rules on the device. */
class FastAskTest {
    private val h = 3_600_000L
    private val start = 1_000_000_000_000L

    private fun running(hoursIn: Double, target: Int = 16, extended: Boolean = false): Pair<FastingView, Long> {
        val now = start + (hoursIn * h).toLong()
        val goal = start + target * h
        val cur = FastNow(
            "f1", start, target, goal, now >= goal, "Started 20:05 yesterday", "Goal $target h · at 12:05",
            extended = extended, title = if (extended) FastingRules.extendedTitle(target) else "Fasting · goal $target h",
            goalWhen = if (extended) "Sat 20:00" else "12:05",
        )
        return FastingView(FastingRules.DEFAULT_PLAN, cur, null, "Eating window opens at 12:00", emptyList(), null) to now
    }

    private val idle = FastingView(
        FastingRules.DEFAULT_PLAN, null,
        LastFast("f0", start, start + 16 * h, 16.3, true, "Last fast 16 h 20 m · goal reached", canResume = true),
        "Eating window open until 20:00", emptyList(), null,
    )

    @Test
    fun hearsEndResumeAndStatusButNotATimedChangeOrBreakfast() {
        listOf(
            "End my fast", "stop fasting", "Break my fast.", "I've broken my fast", "I broke my fast", "finish the fast",
            "I'm ending my fast now", "I'm done fasting", "I'm not fasting anymore", "Navilife, end my fast please", "end fast",
        ).forEach { assertEquals(FastAsk.END, FastAskRules.request(it, fasting = true), it) }
        listOf("Resume my fast", "restart my fast", "carry on with my fast", "I didn't mean to end my fast", "undo ending my fast", "start my fast again")
            .forEach { assertEquals(FastAsk.RESUME, FastAskRules.request(it, fasting = false), it) }
        listOf(
            "How long left on my fast?", "how much time is left on my fast", "how long have I been fasting?", "Am I fasting?",
            "when can I eat?", "When does my fast end?", "how's my fast going", "how long until I can eat", "how long to go on my fast",
        ).forEach { assertEquals(FastAsk.STATUS, FastAskRules.request(it, fasting = false), it) }
        assertEquals(FastAsk.STATUS, FastAskRules.request("How long left?", fasting = true))
        assertNull(FastAskRules.request("How long left?", fasting = false))
        listOf(
            "end my fast at 6pm", "end my fast tomorrow", "end my 16 hour fast", "start a 16 hour fast", "start my fast",
            "what's for breakfast", "breakfast", "stop the timer", "end the meeting", "remind me to end my fast", "fast",
            "how long left on the timer", "",
        ).forEach { assertNull(FastAskRules.request(it, fasting = true), it) }
    }

    @Test
    fun saysHowLongInAndToGo() {
        val (v, now) = running(14.5)
        assertEquals("Yes · 14 h 30 m in · 1 h 30 m to go to your 16 h goal, at 12:05.", FastAskRules.status(v, now))
        val (r, later) = running(17.0)
        assertEquals("Yes · 17 h 00 m in · you reached your 16 h goal at 12:05. Say “end my fast” when you eat.", FastAskRules.status(r, later))
    }

    @Test
    fun anExtendedFastCountsDays() {
        val (v, now) = running(62.0, target = 120, extended = true)
        assertEquals("Yes · 5-day fast · Day 3 of 5 · 2 d 14 h in · 2 d 10 h to go, until Sat 20:00.", FastAskRules.status(v, now))
    }

    @Test
    fun notFastingSaysTheLastOneAndTheWindow() {
        assertEquals("You're not fasting · last fast 16 h 20 m · goal reached · eating window open until 20:00.", FastAskRules.status(idle, start))
        assertEquals("You're not fasting · last fast 16 h 20 m · goal reached · eating window open until 20:00 · nothing to end.", FastAskRules.nothingToEnd(idle))
        assertEquals("You're not fasting.", FastAskRules.notFasting(FastingView.EMPTY))
    }

    @Test
    fun endedSaysWhatItCameToAndHowToTakeItBack() {
        assertEquals(
            "Ended your fast · 16 h 20 m · goal reached. Ended by mistake? Say “resume my fast” in the next 10 minutes.",
            FastAskRules.ended(idle),
        )
        val (v, now) = running(16.5)
        assertEquals("Your fast is running again · 16 h 30 m in, from 20:05 yesterday.", FastAskRules.resumed(v, now))
        assertEquals("You're still fasting · 16 h 30 m in · you reached your 16 h goal at 12:05. Say “end my fast” when you eat.", FastAskRules.cantResume(v, now))
        assertEquals(
            "Your last fast ended more than 10 minutes ago, so it can't be resumed · start a new one in Goals and habits, or say “start a 16-hour fast”.",
            FastAskRules.cantResume(idle.copy(last = idle.last!!.copy(canResume = false)), start),
        )
        assertEquals(HelpPlace.GOALS, FastAskRules.link.place)
    }
}
