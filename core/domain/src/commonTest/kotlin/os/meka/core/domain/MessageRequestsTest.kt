package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests from people Meka watches become proposals (build plan V1, slice 1: the rules, no AI). */
class MessageRequestsTest {
    // Friday 9 October 2026, the message came at 14:02.
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val now = 14 * 60 + 2
    private fun day(m: Int, d: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, d)

    private fun msg(who: String, text: String?, group: String? = null, kind: CaptureKind = CaptureKind.MESSAGE) =
        CapturedItem("id-$who-$text", CaptureApp.WHATSAPP, kind, who, text, group, 0)

    @Test
    fun whenWordsResolveAgainstTheDayTheMessageCame() {
        fun r(w: String) = RequestDates.resolve(w, fri, now)
        assertEquals(fri + 1, r("tomorrow")?.day)
        assertEquals(fri, r("today")?.day)
        assertEquals(day(10, 15), r("Thursday")?.day)
        assertEquals(day(10, 15), r("on Thursday")?.day)
        assertEquals(day(10, 16), r("next Friday")?.day) // Friday of next week
        assertEquals(day(10, 15), r("on the 15th")?.day)
        assertEquals(day(10, 15), r("the 15th")?.day)
        assertEquals(day(11, 3), r("on the 3rd")?.day) // the 3rd has gone this month
        assertEquals(fri, r("the 9th")?.day) // today counts
        assertEquals(day(12, 31), RequestDates.resolve("the 31st", day(11, 5), 0)?.day) // November has no 31st
        assertEquals(day(10, 15), r("15 Oct")?.day)
        assertEquals(day(10, 15), r("15/10")?.day)
        val evening = r("Tue at 6pm")
        assertEquals(day(10, 13), evening?.day)
        assertEquals(18 * 60, evening?.minute)
        assertEquals(19 * 60, r("tonight")?.minute)
        // Words MEKA can't read whole are not a date.
        assertNull(r("Thursday after school"))
        assertNull(r("soon"))
        assertNull(r(""))
        assertNull(r("the 32nd"))
    }

    @Test
    fun onlyMessagesFromWatchedPeopleInOneToOneChatsAreRead() {
        val lists = PeopleLists(family = setOf("Mum"))
        val watching = setOf("Wife")
        assertTrue(MessageRequestRules.shouldRead(msg("  wife ", "can you get milk?"), lists, watching))
        assertTrue(MessageRequestRules.shouldRead(msg("Mum", "call me at 7"), lists, watching)) // family by default
        assertFalse(MessageRequestRules.shouldRead(msg("Tunde", "are you coming?"), lists, watching))
        assertFalse(MessageRequestRules.shouldRead(msg("Wife", "milk", group = "Family"), lists, watching))
        assertTrue(MessageRequestRules.shouldRead(msg("Wife", "milk", group = "Family"), lists, watching, groups = setOf("family")))
        assertFalse(MessageRequestRules.shouldRead(msg("Wife", null, kind = CaptureKind.MISSED_CALL), lists, watching))
        assertFalse(MessageRequestRules.shouldRead(msg("Wife", "  "), lists, watching))
    }

    @Test
    fun voiceNotesAndPhotosNeverGoToTheAi() {
        assertTrue(MessageRequestRules.isVoiceNote("🎤 Voice message (0:12)"))
        assertTrue(MessageRequestRules.isVoiceNote("Voice note"))
        assertFalse(MessageRequestRules.worthAsking("🎤 Voice message (0:12)"))
        assertFalse(MessageRequestRules.worthAsking("📷 Photo"))
        assertTrue(MessageRequestRules.worthAsking("Photo of the form is on the fridge, can you sign it tomorrow?"))
        assertTrue(MessageRequestRules.worthAsking("can you pick up the dry cleaning tomorrow?"))
        val p = MessageRequestRules.voiceNoteProposal("Wife")
        assertEquals(RequestKind.REMINDER, p.kind)
        assertEquals("Listen to Wife's voice note", p.title)
    }

    @Test
    fun theModelsProposalsAreCheckedAndTheMessagesOwnWordsWin() {
        val raw = listOf(
            // The model got the date wrong; the message said Thursday.
            RawRequestProposal("task", "pick up dry cleaning", date = "2026-10-14", words = "Thursday"),
            RawRequestProposal("work_from_home", "WFH", date = "2026-10-15", time = "09:00"),
            RawRequestProposal("event", "Parents' evening", words = "Tue at 6pm"),
            RawRequestProposal("reminder", "Call your mum", time = "19:00"),
            RawRequestProposal("send_message", "Reply yes"), // not MEKA's
            RawRequestProposal("event", "Dinner"), // an event needs a day
            RawRequestProposal("work_from_home", null), // so does working from home
            RawRequestProposal("task", "  "), // a task needs a title
            RawRequestProposal("task", "Old thing", date = "2026-09-01"), // a past date is dropped, the task kept
            RawRequestProposal("task", "PICK UP DRY-CLEANING", words = "on Thursday"), // the same request twice
        )
        val got = MessageRequestRules.check(raw, fri, now)
        assertEquals(
            listOf(
                RequestProposal(RequestKind.TASK, "Pick up dry cleaning", day(10, 15), null),
                RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", day(10, 15), null),
                RequestProposal(RequestKind.EVENT, "Parents' evening", day(10, 13), 18 * 60),
                RequestProposal(RequestKind.REMINDER, "Call your mum", null, 19 * 60),
                RequestProposal(RequestKind.TASK, "Old thing", null, null),
            ),
            got,
        )
        assertEquals(null, MessageRequestRules.check(listOf(RawRequestProposal("task", "x", date = "2028-01-01")), fri, now).single().day)
        val long = MessageRequestRules.cleanTitle("word ".repeat(30))!!
        assertTrue(long.endsWith("…") && long.length <= MessageRequestRules.MAX_TITLE + 1 && long.startsWith("Word word"), long)
        assertEquals("Buy milk", MessageRequestRules.cleanTitle("\"buy\nmilk\""))
    }

    @Test
    fun aCardSaysWhoAskedWhatTheyWroteAndWhatAddWillDo() {
        val calendar = LocalCalendar.UTC
        val at = calendar.toEpochMs(fri, now)
        val m = RequestMessage("wa-1", "Wife", "can you pick up the dry cleaning tomorrow?", at)
        val proposals = listOf(
            RequestProposal(RequestKind.TASK, "Pick up dry cleaning", fri + 1, null),
            RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", day(10, 15), null),
        )
        val cards = MessageRequestRules.cards(m, proposals, emptyList(), fri, calendar)
        val task = cards[0]
        assertEquals("wa-1#0", task.id)
        assertEquals("From Wife · 14:02", task.from)
        assertEquals("“can you pick up the dry cleaning tomorrow?”", task.quote)
        assertEquals("Add task: Pick up dry cleaning · Tomorrow", task.action)
        assertEquals(listOf("Add", "Change", "Not a task"), listOf(task.addLabel, task.changeLabel, task.declineLabel))
        assertNull(task.detail)
        assertTrue(task.spoken.startsWith("Wife wrote “can you"))
        val wfh = cards[1]
        assertEquals("Work from home · Thu 15 Oct", wfh.action)
        assertEquals("Thu 15 Oct shows as work from home on both apps", wfh.detail)

        // Asked again (or WhatsApp re-posts it): no second card.
        val again = RequestMessage("wa-2", "wife", "don't forget the dry cleaning tomorrow!", at + 60_000)
        assertTrue(MessageRequestRules.cards(again, proposals.take(1), cards, fri, calendar).isEmpty())
        assertTrue(MessageRequestRules.cards(m, proposals, cards, fri, calendar).isEmpty())
        // Someone else asking the same thing is their own card.
        assertEquals(1, MessageRequestRules.cards(m.copy(id = "wa-3", personName = "Mum"), proposals.take(1), cards, fri, calendar).size)

        // A long message is shortened at a word.
        val long = m.copy(id = "wa-4", text = "word ".repeat(60))
        val quote = MessageRequestRules.cards(long, proposals.take(1).map { it.copy(title = "Other") }, emptyList(), fri, calendar).single().quote
        assertTrue(quote.endsWith("…”") && quote.length <= MessageRequestRules.QUOTE_CHARS + 3, quote)
    }

    @Test
    fun actionLinesReadForEveryKind() {
        assertEquals(
            "Add event: Parents' evening · Tue 13 Oct · 18:00",
            MessageRequestRules.actionLine(RequestProposal(RequestKind.EVENT, "Parents' evening", day(10, 13), 18 * 60), fri),
        )
        assertEquals("Remind me: Call your mum · Today · 19:00", MessageRequestRules.actionLine(RequestProposal(RequestKind.REMINDER, "Call your mum", null, 19 * 60), fri))
        assertEquals("Add task: Buy milk", MessageRequestRules.actionLine(RequestProposal(RequestKind.TASK, "Buy milk", null, null), fri))
    }
}
