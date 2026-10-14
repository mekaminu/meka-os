package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call notifications, slice 6: calls the phone screened in Activity → Calls. Invented numbers only. */
class PhoneCallLogTest {
    private val t = 1_760_450_520_000L // 14:02 UTC
    private val cal = LocalCalendar.UTC
    private val stranger = "+441632960001"
    private val other = "+441632960002"
    private val friend = "+441632960003"

    private fun phone(at: Long, number: String?, verdict: CallVerdict, reason: CallReason?, known: Boolean = false) =
        PhoneCall(at, number, verdict, reason, known)

    @Test
    fun strangersCallsThatRangOrWereStoppedAreListedButKnownCallersThatRangAreNot() {
        val calls = listOf(
            phone(t, stranger, CallVerdict.RING, CallReason.OFF_WORK),
            phone(t - 60 * 60_000, other, CallVerdict.BLOCK, CallReason.BLOCKED),
            phone(t - 2 * 60 * 60_000, friend, CallVerdict.RING, CallReason.OFF_WORK, known = true),
            phone(t - 3 * 60 * 60_000, null, CallVerdict.RING, CallReason.OFFICIAL_CLAIM),
        )
        val blocked = BlockedCallersView(
            listOf(BlockedCallerRow(BlockedCallerRules.keyOf(other)!!, other, "Blocked 9 Oct · you blocked it")), "1 number",
        )
        val view = CallLogRules.view(emptyList(), blocked, t + 60_000, cal, phone = calls)
        val rows = view.days.single().rows
        assertEquals(3, rows.size)
        assertEquals("3 calls in the last 7 days · 1 blocked", view.line)

        val rang = rows[0]
        assertTrue(CallLogRules.isPhone(rang.id))
        assertEquals("01632 960001 · not in your contacts", rang.who)
        assertEquals(CallLogRules.RANG_THROUGH, rang.line)
        assertEquals(CallLogRules.CHIP_NOT_BLOCKED, rang.chip)
        assertEquals(listOf("Rang · 14:02", CallLogRules.RANG_THROUGH), rang.detail.timeline)
        assertEquals(CallNoticeRules.NOT_BLOCKED, rang.detail.blockStatus)
        assertEquals(CallNoticeRules.BLOCK, rang.detail.block)
        assertEquals("Rang · today", rang.detail.blockWhy)
        assertNull(rang.detail.listen)
        assertNull(rang.detail.words)

        val stopped = rows[1]
        assertEquals("Stopped before it rang · on your block list", stopped.line)
        assertEquals(CallLogRules.CHIP_BLOCKED, stopped.chip)
        assertEquals(listOf("Called · 13:02", "Stopped before it rang · on your block list", CallLogRules.NO_RING), stopped.detail.timeline)
        assertEquals(CallNoticeRules.UNBLOCK, stopped.detail.unblock)
        assertNull(stopped.detail.block)

        val official = rows[2]
        assertEquals(CallAssistantRules.WITHHELD_NAME, official.who)
        assertEquals("Rang through · claims to be an official body", official.line)
        assertEquals(CallLogRules.CHIP_WITHHELD, official.chip)
        assertNull(official.detail.callBack)
    }

    @Test
    fun aScreenedCallAHeldMessageCoversIsListedOnce() {
        val declined = phone(t, stranger, CallVerdict.DECLINE, CallReason.AT_WORK)
        val noMessage = phone(t - 30 * 60_000, other, CallVerdict.DECLINE, CallReason.LIKELY_SPAM)
        val msg = CapturedItem(
            id = "m", app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = stranger, text = "Hello",
            conversation = null, atMs = t + 40_000, seconds = 12,
        )
        val view = CallLogRules.view(listOf(HeldCall(msg, false)), BlockedCallersView.EMPTY, t + 60_000, cal, phone = listOf(declined, noMessage))
        val rows = view.days.single().rows
        assertEquals(listOf("m", false), listOf(rows[0].id, CallLogRules.isPhone(rows[0].id)))
        assertEquals(2, rows.size)
        assertEquals("Sent to your call assistant · left no message", rows[1].line)
        assertEquals(
            listOf("Rang · 13:32", "Sent to your call assistant · failed the network's caller check", CallLogRules.NO_MESSAGE),
            rows[1].detail.timeline,
        )
        assertEquals("Sent to your call assistant · today", rows[1].detail.blockWhy)
    }

    @Test
    fun theReasonItRangIsSaidAndOldCallsDropOff() {
        val slow = phone(t, stranger, CallVerdict.RING, null)
        assertEquals(listOf("Rang · 14:02", CallLogRules.RANG_THROUGH, CallLogRules.TOO_SLOW), CallLogRules.phoneTimeline(slow, cal))
        assertEquals("Called again within 3 minutes", CallLogRules.rangWhy(CallReason.REPEAT))
        val old = phone(t - 8 * 86_400_000L, other, CallVerdict.BLOCK, CallReason.BLOCKED)
        assertTrue(CallLogRules.view(emptyList(), BlockedCallersView.EMPTY, t, cal, phone = listOf(old)).isEmpty)
        assertEquals(listOf(slow), PhoneCallRules.add(listOf(old), slow, t + 1))
    }

    @Test
    fun theListRoundTripsAndSkipsBadLines() {
        val list = listOf(
            phone(t, stranger, CallVerdict.RING, null),
            phone(t - 1_000, null, CallVerdict.BLOCK, CallReason.LIKELY_SPAM, known = false),
            phone(t - 2_000, friend, CallVerdict.RING, CallReason.FAMILY, known = true),
        )
        val text = PhoneCallRules.encode(list)
        assertEquals(list, PhoneCallRules.decode(text + "\nnonsense\n0\tRING\t-\t0\t-"))
        assertTrue(PhoneCallRules.decode(null).isEmpty())
    }
}
