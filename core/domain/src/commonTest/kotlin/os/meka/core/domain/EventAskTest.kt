package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, completeness slice 4: "move the dentist to 4pm" as a confirm card. */
class EventAskTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val day = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    private val canEdit: (String, String) -> Boolean = { p, a -> p == "google" && a == "alex@example.com" }

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun at(minute: Int) { world.clock.nowMs = today() * day + minute * 60_000L }
    private fun ms(d: Long, minute: Int) = d * day + minute * 60_000L
    private fun project(events: List<CalendarEvent>) =
        TodayProjection.project(fold.tasks.all(), world.clock.nowMs, DayWindow(today() * day, (today() + 1) * day)).copy(events = events)

    private fun event(
        id: String, title: String, d: Long, from: Int, to: Int, account: String = "alex@example.com",
        guests: List<String> = emptyList(), readOnly: Boolean = false, allDay: Boolean = false,
    ) = CalendarEvent(id, title, ms(d, from), ms(d, to), allDay, null, "google", account, "Personal", guests = guests, readOnly = readOnly)

    private fun movable(e: CalendarEvent) = EventAskRules.movable(e, world.clock.nowMs, cal, canEdit)

    @Test
    fun onlyEventsNavilifeMayMoveGetAHandle() {
        at(10 * 60)
        val dentist = event("g1", "Dentist", today(), 15 * 60, 16 * 60)
        val standup = event("g2", "Standup", today(), 9 * 60, 9 * 60 + 15)
        val meeting = event("g3", "Planning", today(), 14 * 60, 15 * 60, guests = listOf("sam@example.com"))
        val holiday = event("g4", "Bank holiday", today(), 12 * 60, 13 * 60, readOnly = true)
        val work = event("g5", "Gym", today(), 18 * 60, 19 * 60, account = "work@example.com")
        val allDay = event("g6", "Away", today(), 0, 24 * 60, allDay = true).copy(startAtMs = today() * day, endAtMs = (today() + 1) * day)
        val ctx = AskRules.context(project(listOf(standup, dentist, meeting, holiday, work, allDay)), world.clock.nowMs, cal, movable = ::movable)

        val events = ctx.items.filter { it.kind == AskItemKind.EVENT }
        assertEquals(listOf("", "e1", "", "", "", ""), events.map { it.ref })
        assertEquals(mapOf("e1" to AskEventRef("g1", "Dentist", today(), 15 * 60, 60, "google")), ctx.eventRefs)
        // The handle is all that's sent: no event id on the wire.
        assertFalse(ctx.items.any { "g1" in it.line || "g1" == it.ref })
        // Not moving anything: no event gets a handle (the default).
        assertTrue(AskRules.context(project(listOf(dentist)), world.clock.nowMs, cal).eventRefs.isEmpty())
    }

    @Test
    fun aMoveQuestionCarriesTheComingEventsItNames() {
        at(10 * 60)
        val friday = today() + 4
        val dentist = event("g1", "Dentist check-up", friday, 15 * 60, 15 * 60 + 30)
        val haircut = event("g2", "Haircut", today() + 2, 10 * 60, 10 * 60 + 45)
        val far = event("g3", "Dentist", today() + 30, 9 * 60, 10 * 60)
        val all = listOf(dentist, haircut, far)

        val ahead = EventAskRules.ahead("move the dentist to 4pm", all, world.clock.nowMs, cal, canEdit)
        assertEquals(listOf("g1"), ahead.map { it.id })
        assertEquals(listOf("g1"), EventAskRules.ahead("can you push my dentist's appointment to Friday at 4", all, world.clock.nowMs, cal, canEdit).map { it.id })
        // Not a move question, or no word in common: nothing extra goes.
        assertTrue(EventAskRules.ahead("when's the dentist?", all, world.clock.nowMs, cal, canEdit).isEmpty())
        assertTrue(EventAskRules.ahead("move it to 4pm", all, world.clock.nowMs, cal, canEdit).isEmpty())
        // The question still goes to the AI: the help index doesn't take it.
        assertFalse(HelpRules.lookup("move the dentist to 4pm")?.strong == true)
        assertFalse(HelpRules.lookup("push my haircut to Friday at 10")?.strong == true)

        val ctx = AskRules.context(project(emptyList()), world.clock.nowMs, cal, movable = ::movable, ahead = ahead)
        val line = ctx.items.single { it.kind == AskItemKind.EVENT }
        assertEquals("e1", line.ref)
        assertEquals("${CivilDate.shortLabel(friday)} 15:00–15:30 · Dentist check-up · Personal", line.line)
        assertTrue(ctx.untrusted)
    }

    @Test
    fun aMoveBecomesACardOnlyWhenItChecksOut() {
        at(10 * 60)
        val friday = today() + 4
        val ctx = AskRules.context(project(listOf(event("g1", "Dentist", today(), 15 * 60, 16 * 60))), world.clock.nowMs, cal,
            movable = ::movable, ahead = listOf(event("g2", "Haircut", friday, 10 * 60, 10 * 60 + 45)))
        fun card(a: AskRawAction) = AskRules.card(a, ctx, emptyMap(), world.clock.nowMs, cal)

        val move = card(AskRawAction("move_event", ref = "e1", time = "16:00"))!!
        assertEquals(AskProposal.MoveEvent("g1", "Dentist", today(), 16 * 60, "google"), move.proposal)
        assertEquals("Move “Dentist” to Today · 16:00 · Google", move.line)
        assertEquals("Move", move.button)
        val later = card(AskRawAction("move_event", ref = "e2", date = AskRules.isoDate(friday), time = "16:30"))!!
        assertEquals(AskProposal.MoveEvent("g2", "Haircut", friday, 16 * 60 + 30, "google"), later.proposal)
        // No handle MEKA sent, no time, a bad time, a time gone, the time it already has: no card.
        assertNull(card(AskRawAction("move_event", ref = "e9", time = "16:00")))
        assertNull(card(AskRawAction("move_event", ref = "t1", time = "16:00")))
        assertNull(card(AskRawAction("move_event", ref = "e1")))
        assertNull(card(AskRawAction("move_event", ref = "e1", time = "4pm")))
        assertNull(card(AskRawAction("move_event", ref = "e1", time = "09:00")))
        assertNull(card(AskRawAction("move_event", ref = "e1", time = "15:00")))
        assertNull(card(AskRawAction("move_event", ref = "e1", date = AskRules.isoDate(today() - 1), time = "16:00")))

        assertEquals("Moving “Dentist” to Today · 16:00 in Google", AskRules.doneLine(move.proposal, today()))
        assertEquals("Shall I move Dentist to today at 16:00 in Google?", TalkRules.spoken(AskAnswer("", listOf(move)), today()))
        // Never on its own: an event's title can be someone else's words.
        assertNull(TrustRules.kindOf(move.proposal))
    }

    @Test
    fun theMoveKeepsTheLengthLikeTheEditSheet() {
        at(10 * 60)
        val e = event("g1", "Dentist", today(), 15 * 60, 15 * 60 + 40)
        val p = AskProposal.MoveEvent("g1", "Dentist", today() + 1, 9 * 60, "google")
        val draft = EditEventRules.draft(EventAskRules.moved(EditEventRules.start(e, world.clock.nowMs, cal), p), e, world.clock.nowMs, cal)
        assertEquals(ms(today() + 1, 9 * 60), draft.startAtMs)
        assertEquals(ms(today() + 1, 9 * 60 + 40), draft.endAtMs)
        assertEquals("Dentist", draft.title)
        assertFalse(draft.allDay)
    }
}
