package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call notifications, slice 4: Activity → Calls and the call detail. Invented numbers and names only. */
class CallLogTest {
    private val day = 86_400_000L
    private val t = 1_760_450_520_000L // 14:02 UTC
    private val cal = LocalCalendar.UTC
    private val stranger = "+441632960001"
    private val other = "+441632960002"

    private fun voice(
        id: String, text: String? = null, seconds: Int? = 24, urgent: Boolean = false, by: String? = null,
        number: String = stranger, at: Long = t, summary: String? = null, audio: Boolean = false,
    ) = CapturedItem(
        id = id, app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = number, text = text, conversation = null,
        atMs = at, urgent = urgent, seconds = seconds, urgentBy = by, summary = summary, hasAudio = audio,
    )

    private fun missed(id: String, who: String, at: Long) =
        CapturedItem(id, CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, at)

    @Test
    fun eachCallIsOneLineWithItsBlockChipGroupedByDay() {
        val dentist = voice("a", "Hi it's the dentist confirming Thursday at 3pm, it's urgent", urgent = true, by = CallNoticeRules.BY_SPEECH,
            summary = "Dentist confirming Thursday 3pm", audio = true)
        val robo = voice("b", "6", seconds = 2, urgent = true, by = CallNoticeRules.BY_KEYPAD, number = other, at = t - 60 * 60_000)
        val alex = missed("c", "Alex", t - day)
        val blocked = BlockedCallersView(
            listOf(BlockedCallerRow(BlockedCallerRules.keyOf(other)!!, other, "Blocked today · Hung up on the assistant")), "1 number",
        )
        val view = CallLogRules.view(
            listOf(HeldCall(alex, cleared = false), HeldCall(robo, cleared = false), HeldCall(dentist, cleared = false)),
            blocked, t + 60_000, cal,
        )
        assertEquals(listOf("Today", "Yesterday"), view.days.map { it.label })
        assertEquals("3 calls in the last 7 days · 1 blocked", view.line)
        val (first, second) = view.days[0].rows
        assertEquals("14:02", first.time)
        assertEquals("01632 960001 · not in your contacts", first.who)
        assertEquals("Dentist confirming Thursday 3pm · Said it's urgent", first.line)
        assertEquals(CallLogRules.CHIP_NOT_BLOCKED, first.chip)
        assertTrue(first.urgent)
        assertEquals(CallNoticeRules.AUTOMATED_LABEL, second.line)
        assertEquals(CallLogRules.CHIP_BLOCKED, second.chip)
        assertTrue(second.blocked && second.automated && !second.urgent)
        val yesterday = view.days[1].rows.single()
        assertEquals("Alex", yesterday.who)
        assertEquals(CallLogRules.MISSED_LINE, yesterday.line)
        assertEquals(CallLogRules.CHIP_NEVER, yesterday.chip)
        assertEquals(listOf("Rang · 14:02", "You missed it"), yesterday.detail.timeline)
        assertNull(yesterday.detail.words)
        assertNull(yesterday.detail.callBack)
        // Two days ago reads as a date.
        assertEquals("Sun 12 Oct", CallLogRules.dayLabel(cal.epochDayOf(t) - 2, cal.epochDayOf(t)))
    }

    @Test
    fun theDetailHasTheTimelineWordsBlockLineAndButtons() {
        val dentist = voice("a", "Hi it's the dentist confirming Thursday at 3pm, it's urgent", urgent = true, by = CallNoticeRules.BY_SPEECH,
            summary = "Dentist confirming Thursday 3pm", audio = true)
        val d = CallLogRules.detail(dentist, cleared = false, BlockedCallersView.EMPTY, cal)
        assertEquals(listOf("Rang · 14:02", CallLogRules.ASSISTANT_ANSWERED, "Left a 0:24 message", CallNoticeRules.SAID_URGENT), d.timeline)
        assertEquals("Dentist confirming Thursday 3pm", d.summary)
        assertEquals("“Hi it's the dentist confirming Thursday at 3pm, it's urgent”", d.words)
        assertEquals(CallNoticeRules.NOT_BLOCKED, d.blockStatus)
        assertEquals("▶ Listen 0:24", d.listen)
        assertEquals(CallNoticeRules.CALL_BACK, d.callBack)
        assertEquals(CallNoticeRules.BLOCK, d.block)
        assertNull(d.unblock)
        assertNull(d.spam)

        // Flagged by the AI: the reason shows, with Not spam and Block.
        val key = BlockedCallerRules.keyOf(stranger)!!
        val flagged = BlockedCallersView(emptyList(), BlockedCallerRules.EMPTY_LINE,
            suspects = listOf(SuspectedCallerRow(key, stranger, "Flagged today · claimed to be a courier wanting a fee")))
        val f = CallLogRules.detail(dentist, cleared = false, flagged, cal)
        assertEquals("Flagged as likely spam · Flagged today · claimed to be a courier wanting a fee", f.spam)
        assertEquals(CallNoticeRules.NOT_SPAM, f.notSpam)
        assertEquals(CallLogRules.CHIP_FLAGGED, CallLogRules.chip(dentist, flagged).first)

        // Blocked: Unblock instead of Block, and the line says so.
        val listed = BlockedCallersView(listOf(BlockedCallerRow(key, stranger, "Blocked today · Hung up on the assistant")), "1 number")
        val b = CallLogRules.detail(dentist, cleared = false, listed, cal)
        assertTrue(b.blockStatus.startsWith(CallNoticeRules.BLOCKED))
        assertNull(b.block)
        assertEquals(CallNoticeRules.UNBLOCK, b.unblock)
        assertNull(b.spam)

        // An older Done (before 2026-10-20) blanked the words and deleted the recording: the detail says so.
        val c = CallLogRules.detail(dentist.copy(text = null, summary = null, hasAudio = false), cleared = true, BlockedCallersView.EMPTY, cal)
        assertEquals(CallLogRules.CLEARED_WORDS, c.words)
        assertNull(c.listen)
        assertNull(c.summary)

        // A robocall: keypad "urgent" with no words never reads as urgent; the timeline says why it didn't break through.
        val robo = voice("b", null, seconds = 10, urgent = true, by = CallNoticeRules.BY_KEYPAD).copy(noTranscript = true)
        val r = CallLogRules.detail(robo, cleared = false, BlockedCallersView.EMPTY, cal)
        assertEquals(listOf("Rang · 14:02", CallLogRules.ASSISTANT_ANSWERED, "Left a 0:10 message", CallLogRules.KEYPAD_URGENT, CallNoticeRules.AUTOMATED_LABEL), r.timeline)
        assertEquals(CallNoticeRules.KEYPAD_ONLY, r.words)
        assertNull(r.summary)
    }

    @Test
    fun aContactsNameIsUsedAndTheyAreNeverBlocked() {
        val names = CallerNames(contactName = { if (People.key(it) == People.key(stranger)) "Dentist" else null })
        val view = CallLogRules.view(listOf(HeldCall(voice("a", "Confirming Thursday at 3pm"), false)), BlockedCallersView.EMPTY, t, cal, names)
        val row = view.days.single().rows.single()
        assertEquals("Dentist", row.who)
        assertEquals(CallLogRules.CHIP_NEVER, row.chip)
        assertEquals(CallNoticeRules.NEVER_BLOCKED, row.detail.blockStatus)
        assertNull(row.detail.block)
        assertEquals(stranger, row.detail.number) // Call back still dials the number
    }

    @Test
    fun heldCallsKeepClearedOnesAndSyncToTheMac() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val t0 = 1_791_360_000_000L
        world.clock.nowMs = t0
        val fold = HeldMessages(a.replica) { world.clock.nowMs }
        val items = listOf(
            CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.MISSED_CALL, "Alex", null, t0 - 60_000, null),
                CaptureApp.PHONE, CaptureKind.MISSED_CALL, "Alex", null, null, t0 - 60_000),
            CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Sam", null, t0 - 30_000, "hi"),
                CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Sam", "hi", null, t0 - 30_000),
            CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, stranger, null, t0 - 10_000, "Call me back please"),
                CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, stranger, "Call me back please", null, t0 - 10_000),
        )
        fold.hold(items)
        world.clock.nowMs = t0 + 60_000
        assertEquals(3, fold.clear())
        val calls = fold.calls()
        assertEquals(listOf(CaptureKind.VOICE_MESSAGE, CaptureKind.MISSED_CALL), calls.map { it.item.kind }) // newest first, no WhatsApp
        assertTrue(calls.all { it.cleared && it.keptOnDone })
        assertTrue(fold.items().isEmpty())
        a.sync(); m.sync()
        val onMac = HeldMessages(m.replica) { world.clock.nowMs }.calls()
        assertEquals(2, onMac.size)
        val row = CallLogRules.view(onMac, BlockedCallersView.EMPTY, world.clock.nowMs, LocalCalendar.UTC).days.single().rows.first()
        // Done only cleared the card (2026-10-20): the words are still there on the Mac.
        assertEquals("“Call me back please”", row.detail.words)
        assertFalse(row.blocked)
        // A week later they're gone.
        world.clock.nowMs = t0 + HeldMessages.RETENTION_MS + 60_000
        assertTrue(fold.calls().isEmpty())
    }
}
