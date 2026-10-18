package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "What Navilife may do" completeness: the pause switch from Ask and Talk, and Today's line. */
class AutonomyAskTest {
    @Test
    fun hearsPauseResumeAndStatusButNotTheMusicOrATimer() {
        listOf(
            "Pause Navilife", "pause everything", "Navilife, pause.", "stop doing things on your own",
            "Stop acting on your own please", "don't do anything without asking", "ask me before everything",
            "hold off doing things automatically",
        ).forEach { assertEquals(AutonomyAsk.PAUSE, AutonomyAskRules.request(it), it) }
        listOf("Resume Navilife", "resume", "unpause", "carry on doing things on your own", "Resume everything")
            .forEach { assertEquals(AutonomyAsk.RESUME, AutonomyAskRules.request(it), it) }
        listOf("Is Navilife paused?", "are you paused", "is everything paused")
            .forEach { assertEquals(AutonomyAsk.STATUS, AutonomyAskRules.request(it), it) }
        listOf(
            "pause the music", "pause the timer", "stop", "stop the alarm", "pause", "resume the podcast",
            "add pause navilife to my list", "what is the pause switch", "restart my phone", "is the timer paused", "Navilife, pause the music",
            "",
        ).forEach { assertNull(AutonomyAskRules.request(it), it) }
    }

    @Test
    fun saysWhatChangedAndWhereItStands() {
        val paused = AutonomyView(true, "Paused since 21:40 · Navilife asks before everything", AutonomyRules.RESUME, emptyList())
        val running = AutonomyView(false, "Navilife does 2 things on its own · each with Undo and in Activity", AutonomyRules.PAUSE, emptyList())
        assertEquals(
            "Paused · Navilife asks before everything until you resume. Say “resume Navilife” or tap Resume on Today.",
            AutonomyAskRules.answer(AutonomyAsk.PAUSE, changed = true, view = paused),
        )
        assertEquals(
            "Navilife is already paused since 21:40 · it asks before everything.",
            AutonomyAskRules.answer(AutonomyAsk.PAUSE, changed = false, view = paused),
        )
        assertEquals(
            "Navilife is already paused · it asks before everything.",
            AutonomyAskRules.answer(AutonomyAsk.PAUSE, false, paused.copy(summary = "Paused · Navilife asks before everything")),
        )
        assertEquals(
            "Resumed · Navilife does 2 things on its own · each with Undo and in Activity.",
            AutonomyAskRules.answer(AutonomyAsk.RESUME, changed = true, view = running),
        )
        assertEquals(
            "Navilife isn't paused · Navilife does 2 things on its own · each with Undo and in Activity.",
            AutonomyAskRules.answer(AutonomyAsk.RESUME, changed = false, view = running),
        )
        assertEquals(
            "Yes · Paused since 21:40 · Navilife asks before everything. Say “resume Navilife” to carry on.",
            AutonomyAskRules.answer(AutonomyAsk.STATUS, false, paused),
        )
        assertEquals("No · Navilife does 2 things on its own · each with Undo and in Activity.", AutonomyAskRules.answer(AutonomyAsk.STATUS, false, running))
        assertEquals(HelpPlace.AUTONOMY, AutonomyAskRules.link.place)
    }

    @Test
    fun todayShowsTheLineOnlyWhilePaused() {
        assertEquals("Paused since 21:40 · Navilife asks before everything",
            AutonomyView(true, "Paused since 21:40 · Navilife asks before everything", AutonomyRules.RESUME, emptyList()).todayLine)
        assertNull(AutonomyView.EMPTY.todayLine)
    }
}
