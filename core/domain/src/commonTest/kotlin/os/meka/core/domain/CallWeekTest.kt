package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call assistant completeness: the week ahead's weekly "calls taken" line. Invented names and numbers only. */
class CallWeekTest {
    // Sunday 11 October 2026, British Summer Time (UTC+1); the week ahead shows Mon 12 – Sun 18 Oct.
    private val offset = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(offset)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(at(day, 0), at(day + 1, 0), offset)
    private val now = at(sun, 18)

    private fun voice(id: String, at: Long, urgent: Boolean = false, seconds: Int = 24, ended: String? = null, app: CaptureApp = CaptureApp.PHONE) =
        HeldCall(
            CapturedItem(
                id = id, app = app, kind = CaptureKind.VOICE_MESSAGE, personName = "+441632960001",
                text = "Hello, this is a message for you, please call me back when you can, thanks", conversation = null,
                atMs = at, urgent = urgent, seconds = seconds, urgentBy = if (urgent) CallNoticeRules.BY_SPEECH else null, ended = ended,
            ),
            cleared = false,
        )

    private fun robot(id: String, at: Long) = HeldCall(
        CapturedItem(id, CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "+441632960002", null, null, at, seconds = 2, noTranscript = true),
        cleared = false,
    )

    private fun missed(id: String, at: Long) = HeldCall(CapturedItem(id, CaptureApp.PHONE, CaptureKind.MISSED_CALL, "Sam Taylor", null, null, at), cleared = false)

    @Test
    fun theLineCountsTheLastSevenDaysOnly() {
        val held = listOf(
            voice("a", at(sun, 9), urgent = true),
            voice("b", at(sun - 3, 14)),
            robot("c", at(sun - 6, 10)),
            missed("d", at(sun - 1, 11)),
            voice("old", at(sun - 7, 23)), // Sun 4 Oct: eight days ago
            voice("wa", at(sun, 8), app = CaptureApp.WHATSAPP), // a WhatsApp voice note isn't a call
        )
        assertEquals(
            "Calls · your call assistant took 3 in the last 7 days · 1 urgent · 1 likely automated · 1 missed at work",
            CallWeekRules.line(held, now, cal),
        )
    }

    @Test
    fun nothingTakenOrMissedMeansNoLine() {
        assertNull(CallWeekRules.line(emptyList(), now, cal))
        assertNull(CallWeekRules.line(listOf(voice("old", at(sun - 8, 9))), now, cal))
        // Only missed calls: said, with none taken.
        assertEquals("Calls · your call assistant took none in the last 7 days · 2 missed at work",
            CallWeekRules.line(listOf(missed("a", at(sun, 9)), missed("b", at(sun - 2, 9))), now, cal))
    }

    @Test
    fun aScamTheAssistantEndedIsCountedAndNeverUrgent() {
        val line = CallWeekRules.line(listOf(voice("s", at(sun, 9), urgent = true, ended = CallIntroRules.ENDED_SCAM)), now, cal)
        assertEquals("Calls · your call assistant took 1 in the last 7 days · 1 ended as a scam", line)
    }

    @Test
    fun theWeekAheadShowsAndReadsTheLineWithoutWhoRang() {
        val inputs = WeekAheadInputs(
            events = emptyList(),
            work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE),
            holidays = HolidayCalendar.NONE,
            calls = listOf(voice("a", at(sun, 9), urgent = true), voice("b", at(sun - 2, 9))),
        )
        val v = WeekAheadRules.view(inputs, now, cal, ::window)
        assertEquals("Calls · your call assistant took 2 in the last 7 days · 1 urgent", v.calls)
        assertTrue("Calls: your call assistant took 2 in the last 7 days, 1 urgent." in v.spoken, v.spoken)
        assertTrue("1632" !in v.spoken && "message for you" !in v.spoken)
        // No calls: no line, and the week ahead reads as before.
        assertNull(WeekAheadRules.view(inputs.copy(calls = emptyList()), now, cal, ::window).calls)
    }
}
