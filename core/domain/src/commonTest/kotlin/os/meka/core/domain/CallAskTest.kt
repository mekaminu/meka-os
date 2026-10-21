package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call assistant completeness: Ask "any calls today?" answered from Activity → Calls. Invented names and numbers only. */
class CallAskTest {
    private val day = 86_400_000L
    private val t = 1_760_450_520_000L // 14:02 UTC
    private val cal = LocalCalendar.UTC
    private val stranger = "+441632960001"

    private fun voice(id: String, number: String, at: Long, summary: String?, urgent: Boolean = false) = CapturedItem(
        id = id, app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = number, text = "Hello, this is a message for Meka, please call me back when you can, thanks", conversation = null,
        atMs = at, urgent = urgent, seconds = 24, urgentBy = if (urgent) CallNoticeRules.BY_SPEECH else null, summary = summary,
    )

    private fun missed(id: String, who: String, at: Long) =
        CapturedItem(id, CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, at)

    private fun log(): CallLogView = CallLogRules.view(
        listOf(
            HeldCall(missed("c", "Sam Taylor", t - day), cleared = false),
            HeldCall(voice("b", "Oakfield Dental", t - 2 * 60 * 60_000, "Confirming Thursday 3pm"), cleared = false),
            HeldCall(voice("a", stranger, t, "Boiler engineer running late", urgent = true), cleared = false),
        ),
        BlockedCallersView.EMPTY, t + 60_000, cal,
    )

    @Test
    fun questionsAboutHisCallsAreRecognisedAndOthersAreNot() {
        listOf(
            "Any calls today?", "who rang yesterday", "Did the dentist call?", "has anyone called this week?",
            "How many calls did I get?", "any missed calls", "Did Sam ring me back?", "who's called", "calls from Sam?",
        ).forEach { assertTrue(CallAskRules.isAbout(it), it) }
        listOf(
            "Call Sam at 5", "remind me to call the dentist", "how do I set up the call assistant?", "did I call mum?",
            "block calls from unknown numbers", "what's on today?", "have you called the garage", "phone the school",
        ).forEach { assertFalse(CallAskRules.isAbout(it), it) }
    }

    @Test
    fun scopeAndNamedCaller() {
        assertEquals(CallAskScope.TODAY, CallAskRules.scope("any calls today?"))
        assertEquals(CallAskScope.YESTERDAY, CallAskRules.scope("who rang yesterday"))
        assertEquals(CallAskScope.WEEK, CallAskRules.scope("has anyone called?"))
        assertEquals(listOf("dentist"), CallAskRules.named("Did the dentist call?"))
        assertEquals(listOf("sam"), CallAskRules.named("calls from Sam this week?"))
        assertEquals(emptyList(), CallAskRules.named("has anyone called?"))
    }

    @Test
    fun todayIsSaidUrgentFirstWithoutTheContactsNote() {
        assertEquals(
            "2 calls today, 1 urgent. 01632 960001 at 14:02: boiler engineer running late, said it's urgent. " +
                "Oakfield Dental at 12:02: confirming Thursday 3pm.",
            CallAskRules.answer("Any calls today?", log()),
        )
    }

    @Test
    fun theWeekNamesTheDaysAndANamedCallerNarrowsIt() {
        val week = CallAskRules.answer("has anyone called?", log())!!
        assertTrue(week.startsWith("3 calls in the last 7 days, 1 urgent. Today, 01632 960001 at 14:02"), week)
        assertTrue("Yesterday, Sam Taylor at 14:02: rang, you missed it." in week, week)
        assertEquals("1 call from Sam Taylor yesterday. Sam Taylor at 14:02: rang, you missed it.", CallAskRules.answer("did Sam ring yesterday?", log()))
        assertEquals("1 call from Oakfield Dental today. Oakfield Dental at 12:02: confirming Thursday 3pm.", CallAskRules.answer("did Oakfield Dental call today?", log()))
    }

    @Test
    fun nothingFoundSaysSo() {
        assertEquals("No calls yesterday in your call log.", CallAskRules.answer("who rang yesterday?", CallLogView.EMPTY))
        assertTrue(CallAskRules.answer("did Jordan call?", log())!!.startsWith("No calls from Jordan in the last 7 days in your call log."))
        assertNull(CallAskRules.answer("what's on today?", log()))
    }

    @Test
    fun manyCallsAreCountedPastTheFirstFive() {
        val calls = (0 until 7).map { HeldCall(missed("m$it", "Alex", t - it * 60_000L), cleared = false) }
        val text = CallAskRules.answer("any calls today?", CallLogRules.view(calls, BlockedCallersView.EMPTY, t + 60_000, cal))!!
        assertTrue(text.startsWith("7 calls today."), text)
        assertTrue(text.endsWith("And 2 more in Activity → Calls."), text)
    }
}
