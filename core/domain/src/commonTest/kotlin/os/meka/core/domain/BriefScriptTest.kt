package os.meka.core.domain

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefScriptTest {
    @BeforeTest
    fun household() {
        // Rule 13, release 2: the children and clubs come from About you; these are invented.
        Household.useTyped("Max, U7\nFelix, U10", "OTFC Lindenbrook United, HRFC")
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val day = 20738L // Mon 12 Oct 2026
    private val dayMs = day * CivilDate.DAY_MS
    private val today = DayWindow(dayMs, dayMs + CivilDate.DAY_MS)
    private fun at(h: Int, m: Int = 0) = dayMs + (h * 60 + m) * 60_000L
    private fun minute(ms: Long) = ((ms - dayMs) / 60_000L).toInt()

    private fun task(id: String, title: String, due: Long? = null, scheduled: Long? = null, goal: String? = null, repeat: String? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, due, scheduled, null, 0, goal, null, 0L, null, false, recurrenceRule = repeat)

    private fun habit(title: String, done: Int, target: Int, pace: HabitPace, unit: String = "week", streak: Int = 0, doneToday: Boolean = false) =
        HabitItem("h-$title", title, target, HabitTiming.ANYTIME, 30, null, done, target, doneToday, pace, streak, unit,
            List(7) { false }, "", null, false)

    private fun weekday(story: BriefStory, headlines: List<BriefHeadline> = emptyList()) = MorningBriefView.EMPTY.copy(
        dateLabel = "Mon 12 Oct", workLine = "Work 09:00–17:30",
        weatherLine = "9–15°, light rain from 15:00 — take a coat",
        day = listOf(TomorrowRow("e-1", "09:30", "Standup", true, null)),
        headlines = headlines,
        story = story,
    )

    @Test
    fun aWeekdayIsToldInOrderWithTheTasksByNameAndWhy() {
        val (focus, smaller) = BriefScriptRules.focus(
            listOf(
                task("1", "Water the plants"),
                task("2", "Send the Q4 deck", goal = "g1"),
                task("3", "Renew passport", due = at(-30)),
                task("4", "Call the garage", due = at(15)),
                task("5", "File receipts"),
                task("6", "Empty dishwasher"),
            ),
            today, at(7), mapOf("g1" to "Q4 plan"), upNextId = null,
        ) { minute(it) }
        assertEquals(
            listOf(
                BriefFocus("Renew passport", "it's overdue", "3"),
                BriefFocus("Call the garage", "it's due at 3 o'clock", "4"),
                BriefFocus("Send the Q4 deck", "it moves your goal, Q4 plan", "2"),
            ),
            focus,
        )
        assertEquals(3, smaller)
        val story = BriefStory(
            epochDay = day, officeDay = true, workEndMin = 17 * 60 + 30,
            placesLine = "Lindenbrook 9° now · Canary Wharf 14°, light rain from 15:00 — take a coat",
            route = "Thameslink, Elizabeth line, good service",
            events = listOf(BriefEvent("Standup", 9 * 60 + 30, 9 * 60 + 45), BriefEvent("1:1 with Sam", 11 * 60, 11 * 60 + 30)),
            focus = focus, smallerCount = smaller, needsYou = 1,
            habits = BriefScriptRules.habits(listOf(habit("Gym", 1, 3, HabitPace.DUE), habit("Read", 5, 7, HabitPace.DONE_TODAY, doneToday = true))),
            weekGoals = "This week: 2 of 3 goals moving",
            family = listOf("Black bin and food caddy out tonight."),
        )
        val s = BriefScriptRules.script(
            weekday(story, listOf(
                BriefHeadline("n1", "Barça win again", null, "Mundo Deportivo · Barça · 2 h ago"),
                BriefHeadline("n2", "Rates held", null, "BBC News · UK"),
                BriefHeadline("n3", "Storm due", null, "BBC News · UK"),
                BriefHeadline("n4", "Fourth story", null, "BBC News · UK"),
            )),
        )
        val expectedStart = "Good morning, Meka. It's Monday 12 October, an office day, finishing at half past 5. " +
            "On the trains: Thameslink, Elizabeth line, good service. " +
            "Lindenbrook 9 degrees now, Canary Wharf 14 degrees, light rain from 15:00, take a coat. " +
            "First up, Standup at half past 9. Then 1:1 with Sam at 11 o'clock. Free after half past 11. " +
            "Three things to focus on. First, Renew passport, it's overdue. Then Call the garage, it's due at 3 o'clock. " +
            "And Send the Q4 deck, it moves your goal, Q4 plan. Plus 3 smaller ones. " +
            "One decision is waiting for you in Needs you. Today's habit: gym, 1 of 3 this week. " +
            "This week: 2 of 3 goals moving. Black bin and food caddy out tonight. " +
            "In the news. From Mundo Deportivo: Barça win again. From BBC News: Rates held. From BBC News: Storm due. "
        assertTrue(s.startsWith(expectedStart), s)
        assertFalse("Fourth story" in s, "three headlines only")
        assertTrue(s.endsWith(BriefScriptRules.oneLiner(story) + " " + BriefScriptRules.CLOSE), s)
        assertFalse("read" in s.substringBefore("In the news").lowercase().split(' '), "a habit done today isn't said")
    }

    @Test
    fun theWeekendIsLighterOneFocusNoCommute() {
        val story = BriefStory(
            epochDay = day + 5, weekend = true, officeDay = false,
            route = "Thameslink severe delays",
            focus = listOf(BriefFocus("Book the boiler service", "it's overdue"), BriefFocus("Send the Q4 deck", "it moves your goal, Q4 plan")),
            weekGoals = "This week: 2 of 3 goals moving",
        )
        val v = MorningBriefView.EMPTY.copy(dateLabel = "Sat 17 Oct", weatherLine = "12–16°, dry",
            day = listOf(TomorrowRow("e-1", "10:00", "OTFC U7s v Hilltop", true, null)),
            story = story.copy(events = listOf(BriefEvent("OTFC U7s v Hilltop", 600, 660))))
        val s = BriefScriptRules.script(v)
        assertTrue(s.startsWith("Good morning, Meka. It's Saturday 17 October, no work today. Today, 12 to 16 degrees, dry. " +
            "One thing in the calendar: Max's under-sevens play Hilltop at 10 o'clock."), s)
        assertTrue("One thing to focus on: Book the boiler service, it's overdue. Plus one smaller one." in s, s)
        assertFalse("trains" in s || "Send the Q4 deck" in s || "goals moving" in s, s)
    }

    @Test
    fun onlyNamesComeFromTheInputsAndAnEmptyMorningIsShort() {
        val s = BriefScriptRules.script(MorningBriefView.EMPTY.copy(dateLabel = "Mon 12 Oct", story = BriefStory(epochDay = day)))
        assertEquals("Good morning, Meka. It's Monday 12 October. Nothing's planned yet. " +
            BriefScriptRules.oneLiner(BriefStory(epochDay = day)) + " " + BriefScriptRules.CLOSE, s)
        // No task with a reason and no Up next: nothing is named, the rest is counted.
        val (focus, smaller) = BriefScriptRules.focus(listOf(task("1", "Tidy desk"), task("2", "Sort photos")), today, at(7), emptyMap(), null) { minute(it) }
        assertTrue(focus.isEmpty())
        assertEquals(2, smaller)
        // Up next is named when nothing is more pressing, and a repeating chore ranks after a one-off.
        val (up, _) = BriefScriptRules.focus(
            listOf(task("1", "Tidy desk", scheduled = at(10)), task("2", "Daily review", due = at(16), repeat = "FREQ=DAILY"), task("3", "Pay invoice", due = at(17))),
            today, at(7), emptyMap(), upNextId = "1",
        ) { minute(it) }
        assertEquals(listOf("Pay invoice", "Daily review", "Tidy desk"), up.map { it.title })
        assertEquals("it's up next at 10 o'clock", up.last().why)
    }

    @Test
    fun theDaysShapeAndClashesAreSaid() {
        assertEquals("A busy morning, then free from 12 o'clock",
            BriefScriptRules.shape(listOf(BriefEvent("a", 540, 600), BriefEvent("b", 600, 660), BriefEvent("c", 660, 720)), 1050))
        assertEquals("A clear morning, busy after lunch",
            BriefScriptRules.shape(listOf(BriefEvent("a", 780, 840), BriefEvent("b", 900, 960), BriefEvent("c", 960, 1020)), 1050))
        assertEquals("A full day of meetings", BriefScriptRules.shape((0..4).map { BriefEvent("e$it", 540 + it * 90, 600 + it * 90) }, 1050))
        assertNull(BriefScriptRules.shape(listOf(BriefEvent("late", 960, 1020)), 1050))
        assertEquals("Heads up: Standup and Dentist overlap at 10 o'clock",
            BriefScriptRules.clash(listOf(BriefEvent("Standup", 570, 630), BriefEvent("Dentist", 600, 660))))
        assertNull(BriefScriptRules.clash(listOf(BriefEvent("a", 540, 600), BriefEvent("b", 600, 660))))
        assertEquals("quarter to 10", BriefScriptRules.clock(9 * 60 + 45))
        assertEquals("5:40", BriefScriptRules.clock(17 * 60 + 40))
    }

    @Test
    fun theOneLinerNeverRepeatsWithinThirtyDaysAndIsTheSameOnBothDevices() {
        assertTrue(BriefScriptRules.lineCount > 30)
        val facts = listOf(
            BriefStory(),
            BriefStory(focus = listOf(BriefFocus("Send the Q4 deck", null)), goalTitle = "Q4 plan", habits = listOf("gym, 1 of 3 this week"), officeDay = true),
            BriefStory(weekend = true),
        )
        for (f in facts) for (start in listOf(day, day + 17)) {
            val lines = (0 until 31).map { BriefScriptRules.oneLiner(f.copy(epochDay = start + it)) }
            assertEquals(31, lines.toSet().size, lines.joinToString("\n"))
            assertTrue(lines.all { it.length <= 90 && !it.contains('"') }, lines.joinToString("\n"))
        }
        assertEquals(BriefScriptRules.oneLiner(BriefStory(epochDay = day)), BriefScriptRules.oneLiner(BriefStory(epochDay = day)))
        assertTrue("Send the Q4 deck" in BriefScriptRules.oneLiner(facts[1].copy(epochDay = BriefScriptRules.lineCount * 700L)))
    }

    // ---- Slice 3: Short · Standard · Full, and the card matching the script ----

    private fun busy(length: BriefLength) = BriefStory(
        epochDay = day, officeDay = true, workEndMin = 17 * 60 + 30, length = length,
        events = listOf(
            BriefEvent("Standup", 570, 585), BriefEvent("1:1 with Sam", 660, 690), BriefEvent("Design review", 720, 780),
            BriefEvent("Budget call", 840, 870),
        ),
        focus = listOf(
            BriefFocus("Renew passport", "it's overdue", "t1"), BriefFocus("Call the garage", "it's due at 3 o'clock", "t2"),
            BriefFocus("Send the Q4 deck", "it moves your goal, Q4 plan", "t3"), BriefFocus("Book flights", "it's up next", "t4"),
        ),
        smallerCount = 2, needsYou = 2,
        habits = listOf("gym, 1 of 3 this week", "reading, a 4-day streak"),
        weekGoals = "This week: 2 of 3 goals moving",
        family = listOf("Black bin out tonight.", "Date night at 8."),
    )

    private val news = (1..6).map { BriefHeadline("n$it", "Story $it", null, "BBC News · UK") }

    @Test
    fun shortStandardAndFullChangeHowMuchIsSaid() {
        val short = BriefScriptRules.script(weekday(busy(BriefLength.SHORT), news))
        assertTrue("One thing to focus on: Renew passport, it's overdue. Plus 5 smaller ones." in short, short)
        assertFalse("Call the garage" in short || "Then 1:1 with Sam" in short || "goals moving" in short, short)
        assertTrue("Today's habit: gym, 1 of 3 this week." in short && "Date night" !in short, short)
        assertTrue("Story 1" in short && "Story 2" !in short, "one headline: $short")
        assertTrue(BriefScriptRules.body(weekday(busy(BriefLength.SHORT), news)).joinToString(" ").length <= BriefLength.SHORT.maxChars)

        val standard = BriefScriptRules.script(weekday(busy(BriefLength.STANDARD), news))
        assertTrue("Three things to focus on." in standard && "Book flights" !in standard && "Plus 3 smaller ones." in standard, standard)
        assertTrue("Story 3" in standard && "Story 4" !in standard, standard)

        val full = BriefScriptRules.script(weekday(busy(BriefLength.FULL), news))
        assertTrue("Then Send the Q4 deck, it moves your goal, Q4 plan. And Book flights, it's up next. Plus 2 smaller ones." in full, full)
        assertTrue("Then 1:1 with Sam at 11 o'clock, Design review at 12 o'clock and Budget call at 2 o'clock." in full, full)
        assertTrue("Story 5" in full && "Story 6" !in full, full)
        assertTrue(short.length < standard.length && standard.length < full.length)
        // The weekend says two at most in Full, one otherwise.
        assertEquals(2, BriefLength.FULL.focusFor(weekend = true))
        assertEquals(BriefLength.STANDARD, BriefLength.of(null))
        assertEquals(BriefLength.FULL, BriefLength.of(" Full "))
        assertEquals(BriefLength.STANDARD, BriefLength.of("long"))
    }

    @Test
    fun theCardNamesTheSameFocusWithWhyAndTheOneLiner() {
        val v = weekday(busy(BriefLength.STANDARD), news)
        assertEquals(listOf("Renew passport", "Call the garage", "Send the Q4 deck"), v.focusLines.map { it.title })
        assertEquals(listOf("Overdue", "Due at 3 o'clock", "Moves your goal, Q4 plan"), v.focusLines.map { it.caption })
        assertEquals(listOf(true, false, false), v.focusLines.map { it.lit })
        assertEquals(listOf("t1", "t2", "t3"), v.focusLines.map { it.taskId })
        assertEquals("Focus, Renew passport, Overdue", v.focusLines[0].spoken)
        assertEquals("+ 3 smaller ones on Today", v.focusMore)
        assertEquals("2 decisions waiting in Needs you", v.needsYouLine)
        assertEquals("Focus · Renew passport · overdue · + 2 more", v.focusCardLine)
        assertEquals(BriefScriptRules.oneLiner(v.story), v.oneLiner)
        assertTrue(BriefScriptRules.script(v).contains(v.oneLiner))
        assertEquals(listOf("Short" to false, "Standard" to true, "Full" to false), v.lengths.map { it.label to it.chosen })

        val short = weekday(busy(BriefLength.SHORT))
        assertEquals(listOf("Renew passport"), short.focusLines.map { it.title })
        assertEquals("+ 5 smaller ones on Today", short.focusMore)
        assertEquals("Focus · Renew passport · overdue", short.focusCardLine)

        val nothing = weekday(BriefStory(epochDay = day, smallerCount = 1))
        assertTrue(nothing.focusLines.isEmpty())
        assertNull(nothing.focusCardLine)
        assertNull(nothing.needsYouLine)
        assertEquals("Nothing urgent · 1 small one on Today", nothing.focusMore)
        assertEquals("Up next at 10 o'clock", BriefCardRules.caption("it's up next at 10 o'clock"))
        assertNull(BriefCardRules.caption(null))
    }
}
