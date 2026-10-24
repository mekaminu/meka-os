package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi slice 4: every background job posts a card, and Obi only speaks when the governor allows; money and family break through. */
class ObiInterruptTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val now = cal.toEpochMs(20_000, 10 * 60)
    private val ctx = SpeakContext(SpeakPlace.PRIVATE)

    private fun notice(source: NoticeSource = NoticeSource.BINS, tier: NoticeTier = NoticeTier.HEADS_UP, actions: List<NoticeAction> = emptyList()) =
        Notice(key = "k", source = source, tier = tier, title = "T", text = "", atMs = now, target = NoticeTarget.TODAY, actions = actions)

    private fun hold(m: SpeakMoment, c: SpeakContext = ctx, quiet: Boolean = false, meeting: Boolean = false, today: Int = 0,
                     s: SpeakUpSettings = SpeakUpSettings(), said: Boolean = false) =
        SpeakUpRules.checkMoment(m, "words.", s, c, quiet, meeting, today, said, now)

    @Test
    fun everyCardSaysWhatItAsks() {
        assertEquals(ObiCardKind.NOTIFY, ObiInterruptRules.kindOf(notice()))
        assertEquals(ObiCardKind.NOTIFY, ObiInterruptRules.kindOf(notice(actions = listOf(NoticeAction.LISTEN))))
        assertEquals(ObiCardKind.NOTIFY, ObiInterruptRules.kindOf(notice(NoticeSource.BILLS, actions = listOf(NoticeAction.PAY))))
        assertEquals(ObiCardKind.ASK, ObiInterruptRules.kindOf(notice(NoticeSource.SESSION_ASK, actions = listOf(NoticeAction.WENT, NoticeAction.DIDNT_GO))))
        assertEquals(ObiCardKind.ASK, ObiInterruptRules.kindOf(notice(actions = listOf(NoticeAction.WAKE_EARLIER))))
        assertEquals(ObiCardKind.ASK, ObiInterruptRules.kindOf(notice(tier = NoticeTier.ACTION)))
        assertEquals(ObiCardKind.APPROVE, ObiInterruptRules.kindOf(notice(NoticeSource.APPROVAL, NoticeTier.CRITICAL)))
        assertEquals(ObiCardKind.APPROVE, ObiInterruptRules.kindOf(notice(NoticeSource.LEAVING,
            actions = listOf(NoticeAction.SEND_MESSAGE, NoticeAction.EDIT_MESSAGE, NoticeAction.COPY_MESSAGE))))
        assertEquals("Needs your yes", ObiInterruptRules.cardLine(notice(NoticeSource.APPROVAL)))
        // Every source's plain notice is a card of some kind.
        NoticeSource.entries.forEach { assertTrue(ObiInterruptRules.cardLine(notice(it)).isNotBlank()) }
    }

    @Test
    fun focusHoldsEverythingButMoneyAndFamily() {
        val focus = ctx.copy(focus = true)
        assertEquals(SpeakHold.FOCUS, hold(SpeakMoment.TRAIN, focus))
        assertEquals(SpeakHold.FOCUS, hold(SpeakMoment.LEAVE_NOW, focus))
        assertNull(hold(SpeakMoment.URGENT, focus))
        assertNull(hold(SpeakMoment.MONEY, focus))
        // Quiet hours, meetings and the day's cap: the same.
        for (m in listOf(SpeakMoment.URGENT, SpeakMoment.MONEY)) {
            assertNull(hold(m, quiet = true))
            assertNull(hold(m, meeting = true))
            assertNull(hold(m, today = 99))
        }
        assertEquals(SpeakHold.QUIET_HOURS, hold(SpeakMoment.TRAIN, quiet = true))
        assertEquals(SpeakHold.MEETING, hold(SpeakMoment.PRESSURE, meeting = true))
        assertEquals(SpeakHold.DAILY_CAP, hold(SpeakMoment.TRAIN, today = 6))
    }

    @Test
    fun nothingIsEverSaidOverACallAConversationOrTheSpeaker() {
        val allOn = SpeakUpSettings(choices = SpeakMoment.entries.associateWith { true })
        for (m in SpeakMoment.entries) {
            assertEquals(SpeakHold.NOT_PRIVATE, hold(m, SpeakContext(SpeakPlace.SPEAKER), s = allOn))
            assertEquals(SpeakHold.ON_CALL, hold(m, ctx.copy(onCall = true), s = allOn))
            assertEquals(SpeakHold.TALKING, hold(m, ctx.copy(talking = true), s = allOn))
            assertEquals(SpeakHold.MUTED, hold(m, s = allOn.copy(mutedUntilMs = now + 1)))
            assertEquals(SpeakHold.ALREADY_SAID, hold(m, s = allOn, said = true))
            assertEquals(SpeakHold.OFF, hold(m, s = SpeakUpSettings(choices = mapOf(m to false))))
        }
    }

    @Test
    fun aFocusBlockOnTheCalendar() {
        fun ev(title: String, allDay: Boolean = false, start: Long = now - 60_000, end: Long = now + 600_000) =
            CalendarEvent("e", title, start, end, allDay, null, "google", null, null)
        assertTrue(ObiInterruptRules.inFocus(listOf(ev("Focus time")), now))
        assertTrue(ObiInterruptRules.inFocus(listOf(ev("Deep work: report")), now))
        assertTrue(ObiInterruptRules.inFocus(listOf(ev("🎯 Focus")), now))
        assertTrue(ObiInterruptRules.inFocus(listOf(ev("Heads down")), now))
        assertFalse(ObiInterruptRules.inFocus(listOf(ev("Focusing on Sam")), now))
        assertFalse(ObiInterruptRules.inFocus(listOf(ev("Standup")), now))
        assertFalse(ObiInterruptRules.inFocus(listOf(ev("Focus", allDay = true)), now))
        assertFalse(ObiInterruptRules.inFocus(listOf(ev("Focus", start = now + 60_000)), now))
        assertFalse(ObiInterruptRules.inFocus(listOf(ev("Focus", end = now)), now))
    }

    @Test
    fun moneyThatCantWaitIsSaidWithNoAmounts() {
        val a = Approval("a1", ApprovalKind.PAYMENT, "Kestrel", "Pay £120 to Sam", listOf("£120"), now, now + 10 * 60_000,
            approved = false, declined = false, decidedAtMs = null, confirmedBy = null, decisionKey = null)
        val n = ApprovalRules.notices(listOf(a), cal).single()
        assertEquals(SpeakMoment.MONEY, n.speak)
        assertEquals("a payment approval from Kestrel runs out at 10:10. It's in Needs you.", n.spoken)
        assertFalse(n.spoken!!.contains("£"))
        // A share (no money) is never said; a trade is.
        assertNull(ApprovalRules.notices(listOf(a.copy(kind = ApprovalKind.SHARE)), cal).single().speak)
        assertEquals(SpeakMoment.MONEY, ApprovalRules.notices(listOf(a.copy(kind = ApprovalKind.TRADE)), cal).single().speak)
        // Only the last 15 minutes' notice speaks, not the first heads-up.
        val early = ApprovalRules.notices(listOf(a.copy(expiresAtMs = now + 60 * 60_000)), cal)
        assertEquals(listOf(null, SpeakMoment.MONEY), early.map { it.speak })
        assertEquals("your ETH long is very near liquidation. The figures are in Money.",
            ObiInterruptRules.liquidationWords("ETH", long = true, very = true))
        assertEquals("your BTC short is near liquidation. The figures are in Money.",
            ObiInterruptRules.liquidationWords("BTC", long = false, very = false))
        // Said even in quiet hours, with his name first.
        val pick = SpeakUpRules.pick(listOf(n), SpeakUpSettings(), ctx, quiet = true, meeting = false, spokenToday = 0,
            nowMs = now, address = "Alex", alreadySaid = { false })
        assertEquals("Alex, a payment approval from Kestrel runs out at 10:10. It's in Needs you.", pick?.line)
    }

    @Test
    fun speakingUpShowsWhatBreaksThrough() {
        val v = SpeakUpRules.view(SpeakUpSettings(), 0, now, cal)
        val money = v.sections.first { it.label == SpeakMoment.MONEY.label }
        assertEquals("On", money.status)
        assertTrue(ObiInterruptRules.BREAKS_THROUGH_LINE in money.steps)
        assertTrue(ObiInterruptRules.BREAKS_THROUGH_LINE in v.sections.first { it.label == SpeakMoment.URGENT.label }.steps)
        assertFalse(ObiInterruptRules.BREAKS_THROUGH_LINE in v.sections.first { it.label == SpeakMoment.TRAIN.label }.steps)
    }
}
