package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call notifications, slice 5: automated calls follow the spam rules, and a tap opens the call. Invented numbers only. */
class AutomatedCallTest {
    private val day = 86_400_000L
    private val t = 1_760_450_520_000L // 14:02 UTC
    private val cal = LocalCalendar.UTC
    private val a = "+441632960001"
    private val b = "+441632960002"
    private val keyA = BlockedCallerRules.keyOf(a)!!
    private val keyB = BlockedCallerRules.keyOf(b)!!

    private fun voice(id: String, text: String?, number: String = a, at: Long = t, seconds: Int? = 12, transcribing: Boolean = false) =
        HeldCall(
            CapturedItem(
                id = id, app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = number, text = text,
                conversation = null, atMs = at, seconds = seconds, transcribing = transcribing,
            ),
            cleared = false,
        )

    @Test
    fun onlySettledAutomatedMessagesFromRealNumbersAreLookedAtOncePerCaller() {
        val calls = listOf(
            voice("1", "6", at = t - 60_000),
            voice("2", "1", at = t),                                   // the same caller again: only the newest
            voice("3", "Hi, it's the garage about your car", number = b), // a real message: not automated
            voice("4", null, number = b, seconds = 20, transcribing = true), // words still coming: wait
            voice("5", "6", number = "999"),                           // an emergency number: never
            voice("6", "6", number = CallAssistantRules.WITHHELD_NAME), // withheld: no number to block
            voice("7", "6", number = "+441632960009", at = t - 8 * day), // older than a week
        )
        val pending = AutomatedCallRules.pending(calls, { 0L }, emptySet(), t + 60_000)
        assertEquals(listOf(AssistantHangup(keyA, a, t)), pending)
        // Looked at already (up to that message), or already blocked: nothing.
        assertEquals(emptyList(), AutomatedCallRules.pending(calls, { if (it == keyA) t else 0L }, emptySet(), t + 60_000))
        assertEquals(emptyList(), AutomatedCallRules.pending(calls, { 0L }, setOf(keyA), t + 60_000))
        // A short message (under 3 s) is automated even with words.
        val short = listOf(voice("8", "Hello there", number = b, seconds = 2))
        assertEquals(listOf(keyB), AutomatedCallRules.pending(short, { 0L }, emptySet(), t).map { it.key })
    }

    @Test
    fun aRealMessageThisWeekKeepsTheCallerOffTheList() {
        val calls = listOf(voice("1", "6"), voice("2", "Hi, it's the garage about your car", at = t - 2 * day))
        assertTrue(AutomatedCallRules.leftRealMessage(calls, keyA, t))
        assertFalse(AutomatedCallRules.leftRealMessage(listOf(voice("1", "6")), keyA, t))
        assertFalse(AutomatedCallRules.leftRealMessage(calls, keyB, t))
    }

    @Test
    fun theAutomaticBlockSaysWhatHappened() {
        val n = CallNoticeRules.autoBlockNotice(a, AutoBlockTrigger.AUTOMATED, t, cal)
        assertEquals(CallNoticeRules.AUTO_BLOCKED, n.text)
        assertTrue(n.big.startsWith("Sent to your call assistant · 14:02 · likely automated call, no real message"), n.big)
        assertEquals(CallNoticeRules.UNDO, n.undo)
        assertTrue(AutoBlockRules.detail(AutoBlockTrigger.AUTOMATED).startsWith("Reached your call assistant and left"))
    }

    @Test
    fun aTappedNotificationOpensItsCall() {
        assertEquals("calls:abc", CallLogRules.openCall("abc"))
        assertEquals("calls:", CallLogRules.openCall(null))
        assertEquals("abc", CallLogRules.callFromOpen("calls:abc"))
        assertEquals("", CallLogRules.callFromOpen("calls:"))
        assertNull(CallLogRules.callFromOpen("play:abc"))
        val view = CallLogRules.view(
            listOf(voice("x", "Hi, it's the garage about your car", at = t - 60_000), voice("y", "6", at = t), voice("z", "Hello it's Sam", number = b)),
            BlockedCallersView.EMPTY, t + 60_000, cal,
        )
        assertEquals("x", CallLogRules.target(view, "x", null))
        // Not listed (or no id): the caller's newest row; else nothing.
        assertEquals("y", CallLogRules.target(view, "gone", keyA))
        assertEquals("y", CallLogRules.target(view, "", keyA))
        assertNull(CallLogRules.target(view, "", BlockedCallerRules.keyOf("+441632960099")))
        assertNull(CallLogRules.target(view, null, null))
    }
}
