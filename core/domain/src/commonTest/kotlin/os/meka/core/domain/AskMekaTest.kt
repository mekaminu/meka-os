package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ask MEKA, the device's side (build plan V1, AI layer slice 3): what goes with a question, and which proposals become cards. */
class AskMekaTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val day = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun at(minute: Int) { world.clock.nowMs = today() * day + minute * 60_000L }
    private fun ms(d: Long, minute: Int) = d * day + minute * 60_000L
    private fun window() = DayWindow(today() * day, (today() + 1) * day)
    private fun project(events: List<CalendarEvent> = emptyList()) =
        TodayProjection.project(fold.tasks.all(), world.clock.nowMs, window()).copy(events = events)
    private fun titles() = fold.tasks.all().associate { it.id to it.title }

    private fun event(title: String, from: Int, to: Int, allDay: Boolean = false) =
        CalendarEvent("e$title", title, ms(today(), from), ms(today(), to), allDay, null, "google", "meka@gmail.com", "Personal")

    @Test
    fun theContextNamesTasksByHandlesAndNeverSendsIds() {
        at(17 * 60 + 5)
        val dentist = fold.tasks.create(NewTask("Book   dentist", notes = "private note"))
        val cr = fold.tasks.create(NewTask("Create CR"))
        fold.tasks.setWhen(cr, today(), 18 * 60)
        val done = fold.tasks.create(NewTask("Ring the school"))
        fold.tasks.complete(done)
        val ctx = AskRules.context(project(listOf(event("Standup", 9 * 60, 9 * 60 + 15), event("Training", 19 * 60, 20 * 60))), world.clock.nowMs, cal)

        assertEquals(AskRules.isoDate(today()), ctx.dateIso)
        assertTrue(ctx.nowLine.endsWith("· 17:05"), ctx.nowLine)
        val lines = ctx.items.map { "${it.ref}|${it.kind.wire}|${it.line}" }
        assertTrue(lines.any { it.startsWith("t") && it.contains("|task|Create CR") && it.contains("planned 18:00") }, lines.toString())
        assertTrue(lines.any { it.contains("|task|Book dentist") && it.contains("anytime today") }, lines.toString())
        assertTrue("|done|Ring the school · done" in lines, lines.toString())
        assertTrue("|event|09:00–09:15 · Standup · Personal · over" in lines, lines.toString())
        assertTrue("|event|19:00–20:00 · Training · Personal" in lines, lines.toString())
        // Handles map back on the device; the payload carries no id and no notes.
        assertEquals(setOf(dentist, cr), ctx.taskIds.values.toSet())
        val sent = ctx.items.joinToString { it.line + it.ref }
        assertFalse(dentist in sent || cr in sent || "private note" in sent)
        // A calendar can hold other people's invitations: proposals then count as from untrusted content.
        assertTrue(ctx.untrusted)
        assertFalse(AskRules.context(project(), world.clock.nowMs, cal).untrusted)
    }

    @Test
    fun theContextIsCappedAndLinesAreShort() {
        at(9 * 60)
        repeat(70) { fold.tasks.create(NewTask("Task $it " + "x".repeat(200))) }
        val ctx = AskRules.context(project(), world.clock.nowMs, cal)
        assertEquals(AskRules.MAX_ITEMS, ctx.items.size)
        assertTrue(ctx.items.all { it.line.length <= AskRules.MAX_LINE })
        assertEquals(AskRules.MAX_ITEMS, ctx.taskIds.size)
    }

    @Test
    fun proposalsBecomeCardsOnlyWhenTheyCheckOut() {
        at(14 * 60)
        val id = fold.tasks.create(NewTask("Book dentist"))
        val ctx = AskRules.context(project(), world.clock.nowMs, cal)
        val ref = ctx.taskIds.entries.single { it.value == id }.key
        val tomorrow = AskRules.isoDate(today() + 1)
        fun card(a: AskRawAction) = AskRules.card(a, ctx, titles(), world.clock.nowMs, cal)

        assertEquals("Add “Call the dentist” · Tomorrow · 09:00", card(AskRawAction("add_task", title = " Call  the dentist ", date = tomorrow, time = "9:00"))!!.line)
        assertEquals("Add “Milk”", card(AskRawAction("add_task", title = "Milk"))!!.line)
        // A time alone means today, and one already gone today isn't offered.
        assertEquals("Add “Gym” · Today · 18:00", card(AskRawAction("add_task", title = "Gym", time = "18:00"))!!.line)
        assertNull(card(AskRawAction("add_task", title = "Gym", time = "09:00")))
        assertEquals("Tick off “Book dentist”", card(AskRawAction("complete_task", ref = ref))!!.line)
        val move = card(AskRawAction("move_task", ref = ref, date = tomorrow, time = "08:30"))!!
        assertEquals("Move “Book dentist” to Tomorrow · 08:30", move.line)
        assertEquals("Move", move.button)
        assertEquals(AskProposal.MoveTask(id, "Book dentist", today() + 1, 8 * 60 + 30), move.proposal)
        assertEquals("Start a 36 h fast", card(AskRawAction("start_fast", hours = 36))!!.line)
        assertEquals("Start a 5 days fast", card(AskRawAction("start_fast", hours = 120))!!.line)
        assertEquals("Timer · 1 h 30", card(AskRawAction("set_timer", minutes = 90))!!.line)
        assertEquals("Alarm · 06:30", card(AskRawAction("set_alarm", time = "06:30"))!!.line)

        // Refused: unknown kinds, handles it didn't send, bad or past days and times, empty or long titles, odd sizes.
        assertNull(card(AskRawAction("send_email", title = "Hi")))
        assertNull(card(AskRawAction("complete_task", ref = "t99")))
        assertNull(card(AskRawAction("complete_task", ref = id)))
        assertNull(card(AskRawAction("move_task", ref = ref)))
        assertNull(card(AskRawAction("move_task", ref = ref, date = AskRules.isoDate(today() - 1))))
        assertNull(card(AskRawAction("move_task", ref = ref, date = AskRules.isoDate(today() + 731))))
        assertNull(card(AskRawAction("add_task", title = "Milk", date = "tomorrow")))
        assertNull(card(AskRawAction("add_task", title = "Milk", date = tomorrow, time = "25:00")))
        assertNull(card(AskRawAction("add_task", title = "  ")))
        assertNull(card(AskRawAction("add_task", title = "x".repeat(121))))
        assertNull(card(AskRawAction("start_fast", hours = 4)))
        assertNull(card(AskRawAction("start_fast", hours = 241)))
        assertNull(card(AskRawAction("set_timer", minutes = 0)))
        assertNull(card(AskRawAction("set_alarm")))
    }

    @Test
    fun anAnswerShowsAtMostThreeCardsNoTwoAlike() {
        at(10 * 60)
        val ctx = AskRules.context(project(), world.clock.nowMs, cal)
        val a = AskRules.answer(
            "  Here you go.  ",
            listOf(
                AskRawAction("add_task", title = "Milk"), AskRawAction("add_task", title = "Milk"),
                AskRawAction("set_timer", minutes = 20), AskRawAction("bogus"),
                AskRawAction("set_alarm", time = "07:00"), AskRawAction("start_fast", hours = 24),
            ),
            ctx, titles(), world.clock.nowMs, cal,
        )
        assertEquals("Here you go.", a.text)
        assertEquals(listOf("Add “Milk”", "Timer · 20 min", "Alarm · 07:00"), a.cards.map { it.line })
        assertEquals("No answer", AskRules.answerText("  "))
        assertEquals(AskRules.MAX_ANSWER, AskRules.answerText("y".repeat(5000)).length)
    }

    @Test
    fun timersAndAlarmsAreSetLikeTypingThem() {
        at(14 * 60)
        assertEquals("timer 20 min", AskRules.captureLine(AskProposal.Timer(20)))
        assertEquals("timer 2 h", AskRules.captureLine(AskProposal.Timer(120)))
        assertEquals("alarm 06:30", AskRules.captureLine(AskProposal.Alarm(6 * 60 + 30)))
        assertNull(AskRules.captureLine(AskProposal.StartFast(24)))
        // Each line is one QuickAlarmRules reads as that very timer or alarm.
        for (m in listOf(1, 20, 90, 120, 1440)) {
            val q = QuickAlarmRules.parse(AskRules.captureLine(AskProposal.Timer(m)), world.clock.nowMs, cal)
            assertEquals(AlarmKind.TIMER, q?.kind, "timer $m")
            assertEquals(world.clock.nowMs + m * 60_000L, q?.atMs, "timer $m")
        }
        val alarm = QuickAlarmRules.parse(AskRules.captureLine(AskProposal.Alarm(6 * 60 + 30)), world.clock.nowMs, cal)
        assertEquals(ms(today() + 1, 6 * 60 + 30), alarm?.atMs)
    }

    @Test
    fun questionsAndDatesAreRead() {
        assertEquals("What's on today?", AskRules.question("  What's on\n today? "))
        assertNull(AskRules.question("   "))
        assertEquals(AskRules.MAX_QUESTION, AskRules.question("q".repeat(900))!!.length)
        assertEquals(CivilDate.toEpochDay(2026, 10, 9), AskRules.parseDay("2026-10-09"))
        assertNull(AskRules.parseDay("2026-02-30"))
        assertNull(AskRules.parseDay("9 Oct"))
        assertEquals("2026-10-09", AskRules.isoDate(CivilDate.toEpochDay(2026, 10, 9)))
        assertEquals(9 * 60, AskRules.parseTime("9:00"))
        assertNull(AskRules.parseTime("24:00"))
        assertEquals("1 h", AskRules.durationLabel(60))
    }

    @Test
    fun unavailableAndDoneLines() {
        assertEquals("MEKA's AI is off", AskRules.unavailableLine("off", "whatever"))
        assertEquals("This month's AI budget is used up · back on the 1st", AskRules.unavailableLine("over", null))
        assertEquals("Couldn't ask: Anthropic is busy", AskRules.unavailableLine("failed", " Anthropic is busy "))
        assertEquals("Couldn't ask just now", AskRules.unavailableLine("failed", null))
        val today = CivilDate.toEpochDay(2026, 10, 8)
        assertEquals("Added “Milk”", AskRules.doneLine(AskProposal.AddTask("Milk", null, null), today))
        assertEquals("Moved “CR” to Tomorrow · 09:00", AskRules.doneLine(AskProposal.MoveTask("x", "CR", today + 1, 540), today))
        assertEquals("Started a 2 days fast", AskRules.doneLine(AskProposal.StartFast(48), today))
        assertEquals("Alarm set · 06:30", AskRules.doneLine(AskProposal.Alarm(390), today))
    }
}
