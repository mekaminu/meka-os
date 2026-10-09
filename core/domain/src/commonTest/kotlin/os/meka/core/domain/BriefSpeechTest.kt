package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefSpeechTest {
    private fun brief(
        day: List<TomorrowRow> = emptyList(),
        events: Int = 0,
        tasks: Int = 0,
        summary: String = "Nothing planned yet",
        workLine: String? = null,
        weather: String? = null,
        overdue: Int = 0,
        waiting: List<WaitingItem> = emptyList(),
        waitingLine: String? = null,
        attention: List<BriefLine> = emptyList(),
        habits: String? = null,
        fasting: String? = null,
        headlines: List<BriefHeadline> = emptyList(),
    ) = MorningBriefView.EMPTY.copy(
        dateLabel = "Fri 9 Oct", workLine = workLine, weatherLine = weather, day = day, eventCount = events, taskCount = tasks,
        daySummary = summary, overdueCount = overdue, waiting = waiting, waitingTotal = waiting.size, waitingLine = waitingLine,
        attention = attention, habitsLine = habits, fastingLine = fasting, headlines = headlines,
    )

    private fun waiting(id: String, title: String, who: String?, due: Boolean) =
        WaitingItem(id, title, who, null, 0L, null, null, if (due) DueState.DUE else DueState.LATER, "", false)

    @Test
    fun aFullMorningIsReadInThePanesOrderWithItsWordsWrittenOut() {
        val v = brief(
            workLine = "Work 09:00–17:30",
            weather = "9–15°, light rain from 15:00 — take a coat",
            day = listOf(
                TomorrowRow("e-1", "All day", "Bin day", true, null),
                TomorrowRow("e-2", "09:30", "Standup", true, null),
                TomorrowRow("t-3", "14:00", "Book dentist", false, "↻ Weekly"),
                TomorrowRow("e-4", "18:00", "Training", true, "SG18"),
                TomorrowRow("t-5", null, "Renew passport", false, "Overdue"),
            ),
            events = 3, tasks = 2, summary = "3 events · 2 tasks · first at 09:30", overdue = 1,
            waiting = listOf(waiting("w1", "the quote", "Ada", due = true), waiting("w2", "refund", null, due = false)),
            waitingLine = "Waiting on 2 things · 1 to chase today",
            attention = listOf(BriefLine("r-1", "Netflix", "Renews Mon 12 Oct")),
            habits = "1 habit behind · 2 to do today",
            fasting = "Fasting · since 20:05 yesterday · goal at 12:05",
            headlines = listOf(BriefHeadline("n1", "Barça win again", null, "Mundo Deportivo · Barça · 2 h ago")),
        )
        assertEquals(
            "Good morning, Meka. It's Friday 9 October. Work is from 9:00 to 17:30. " +
                "Today, 9 to 15 degrees, light rain from 15:00, take a coat. " +
                "You've got 3 events and 2 tasks, first at 9:30. Standup at 9:30, Book dentist at 14:00 and Training at 18:00. " +
                "All day: Bin day. 1 task is overdue. " +
                "You're waiting on 2 things, 1 to chase today. Chase Ada about the quote. " +
                "On your lists: Netflix. Habits: 1 habit behind, 2 to do today. " +
                "Fasting, since 20:05 yesterday, goal at 12:05. " +
                "In the news. From Mundo Deportivo: Barça win again. That's your morning.",
            BriefSpeech.script(v),
        )
    }

    @Test
    fun anEmptyMorningIsShortAndSaysNothingIsPlanned() {
        assertEquals(
            "Good morning, Meka. It's Friday 9 October. Nothing's planned yet. That's your morning.",
            BriefSpeech.script(brief()),
        )
        // No date yet (the view before the core answers): no "It's …".
        assertEquals("Good morning, Meka. Nothing's planned yet. That's your morning.", BriefSpeech.script(MorningBriefView.EMPTY))
    }

    @Test
    fun onlyTheFirstFourTimedThingsAreNamedAndSomethingRunningSaysUntil() {
        val rows = listOf(TomorrowRow("e-0", "Until 08:00", "Night shift", true, null)) +
            (1..5).map { TomorrowRow("e-$it", "1$it:00", "Thing $it", true, null) }
        val s = BriefSpeech.script(brief(day = rows, events = 6, summary = "6 events · first at 11:00"))
        assertTrue("Night shift until 8:00, Thing 1 at 11:00, Thing 2 at 12:00 and Thing 3 at 13:00, and 2 more things." in s, s)
        assertFalse("Thing 4" in s)
    }

    @Test
    fun timesLoseTheirLeadingZeroButNeverTheirMinutes() {
        assertEquals("9:30", BriefSpeech.spokenTimes("09:30"))
        assertEquals("12:05", BriefSpeech.spokenTimes("12:05"))
        assertEquals("from 0:15 to 10:05", BriefSpeech.spokenTimes("from 00:15 to 10:05"))
        assertEquals("Work 9:00–7:30", BriefSpeech.spokenTimes("Work 09:00–07:30"))
        assertEquals("-2 to 4 degrees, snow", BriefSpeech.clean("-2–4° · snow"))
        assertEquals("14 degrees, dry", BriefSpeech.clean("14° · dry"))
        assertEquals("Saturday 31 December", BriefSpeech.spokenDate("Sat 31 Dec"))
        assertEquals("Christmas Day, no work.", BriefSpeech.script(brief(workLine = "Christmas Day · no work")).split(". ")[2] + ".")
    }

    @Test
    fun aCrowdedMorningIsCutAtASentenceButTheNewsIsNever() {
        val rows = (1..40).map { TomorrowRow("t-$it", null, "Task number $it " + "x".repeat(40), false, null) }
        val attention = (1..40).map { BriefLine("r-$it", "Renewal " + "y".repeat(300), "") }
        val headlines = (1..5).map { BriefHeadline("n$it", "Story $it " + "A".repeat(150), null, "BBC News · World") }
        val s = BriefSpeech.script(brief(day = rows, tasks = 40, summary = "40 tasks", attention = attention, habits = "h".repeat(400), headlines = headlines))
        assertFalse("Habits" in s, "the day's part is cut at a sentence")
        val day = s.substringBefore(" In the news.")
        assertTrue(day.length + 1 + BriefSpeech.SIGN_OFF.length <= BriefSpeech.MAX_CHARS, "${day.length}")
        assertTrue(day.endsWith("."))
        assertTrue(s.endsWith(" " + BriefSpeech.SIGN_OFF))
        (1..5).forEach { assertTrue("From BBC News: Story $it " + "A".repeat(150) + "." in s, "story $it") }
        // Every piece it is cut into fits what the server will speak.
        assertTrue(SpeechRules.pieces(s).all { it.length <= SpeechRules.MAX_PIECE })
    }

    @Test
    fun everyHeadlineTheBriefShowsIsReadInItsOrder() {
        val shown = listOf(
            BriefHeadline("n1", "Flick names his side for Getafe", null, "BBC Sport · Barça · 1 h ago"),
            BriefHeadline("n2", "Yamal back in training", null, "Football España · Barça · 2 h ago"),
            BriefHeadline("n3", "Rates held at 4%", null, "BBC News · UK · 3 h ago"),
            BriefHeadline("n4", "New Fold leaks", null, "The Verge · Tech · 4 h ago"),
            BriefHeadline("n5", "Untitled source story", null, ""),
        )
        val s = BriefSpeech.script(brief(headlines = shown))
        assertTrue(
            s.endsWith(
                "In the news. From BBC Sport: Flick names his side for Getafe. From Football España: Yamal back in training. " +
                    "From BBC News: Rates held at 4%. From The Verge: New Fold leaks. Untitled source story. That's your morning.",
            ),
            s,
        )
        assertEquals(BriefSpeech.news(shown).drop(1).size, shown.size)
    }

    @Test
    fun anOverlongTitleIsShortenedAtAWord() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val line = BriefSpeech.news(listOf(BriefHeadline("n", long, null, "BBC News")))[1]
        assertTrue(line.length <= "From BBC News: ".length + BriefSpeech.MAX_HEADLINE_CHARS + 1, line)
        assertTrue(line.endsWith("."), line)
        assertFalse(line.contains("word80"))
    }
}
