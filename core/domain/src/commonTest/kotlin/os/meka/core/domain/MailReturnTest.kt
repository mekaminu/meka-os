package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailReturnTest {
    /** Wed 14 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 14)

    @Test
    fun anExplicitLastDayAfterAReturnPhrase() {
        assertEquals(CivilDate.toEpochDay(2026, 11, 12), MailReturnRules.lastDay("Order confirmed · return by 12 Nov", null, received))
        assertEquals(CivilDate.toEpochDay(2026, 11, 30), MailReturnRules.lastDay("Your order", "Returns accepted until 30 November 2026.", received))
        assertEquals(CivilDate.toEpochDay(2026, 10, 16), MailReturnRules.lastDay("Your order", "Return window closes on Friday", received))
        assertEquals(CivilDate.toEpochDay(2026, 11, 13), MailReturnRules.lastDay("Receipt", "Last day to return is 13/11/2026", received))
        // A January date in an autumn email is next year's.
        assertEquals(CivilDate.toEpochDay(2027, 1, 31), MailReturnRules.lastDay("Christmas order", "Returnable until 31st January", received))
    }

    @Test
    fun aWindowInDaysCountsFromTheEmail() {
        assertEquals(received + 30, MailReturnRules.lastDay("Order confirmed", "Total £40 · 30-day returns", received))
        assertEquals(received + 28, MailReturnRules.lastDay("Thanks for your order", "Free returns within 28 days", received))
        assertEquals(received + 14, MailReturnRules.lastDay("Your order", "You have 14 days to return any item", received))
        // An explicit date wins over a window.
        assertEquals(CivilDate.toEpochDay(2026, 11, 1), MailReturnRules.lastDay("30 day returns", "Return by 1 Nov", received))
    }

    @Test
    fun nothingWhenItIsUnsure() {
        assertNull(MailReturnRules.lastDay("Your order has shipped", "Arriving Friday", received))
        assertNull(MailReturnRules.lastDay("Your refund", "We've refunded £20 · 30-day returns", received))
        assertNull(MailReturnRules.lastDay("Order", "1-day delivery · 2 day returns", received))
        assertNull(MailReturnRules.lastDay("Order", "Return by 1 Oct 2025", received)) // before the email
        assertNull(MailReturnRules.lastDay("Order", "999 day returns", received)) // too far
        assertNull(MailReturnRules.lastDay(null, null, received))
    }

    @Test
    fun theReminderIsAOneOffOnTheRadar() {
        val r = MailReturnRules.read(MailReceipt(4000, "Amazon"), "Order", "30-day returns", received)!!
        val f = MailReturnRules.fields(r, "Personal", received, 1L, LocalCalendar.UTC)
        assertEquals(FieldValue.Text("Last day to return · Amazon order"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Text(ObligationKind.OTHER.name), f[ObligationFields.KIND])
        assertEquals(FieldValue.Int64(LocalCalendar.UTC.toEpochMs(received + 30, Lists.DATE_MINUTE)), f[ActionableFields.DUE_AT])
        assertEquals(FieldValue.Int64(4000), f[ObligationFields.COST_MINOR])
        assertEquals(FieldValue.Null, f[ObligationFields.RECURRENCE])
        assertEquals(FieldValue.Text(Trust.UNTRUSTED_EXTERNAL.name), f[ActionableFields.PROVENANCE_TRUST])
        assertTrue((f[ActionableFields.NOTES] as FieldValue.Text).value.contains("Personal mail on Wed 14 Oct"))
        assertEquals("Last day to return · your order", MailReturnRules.title(null))
        assertEquals(MailReturnRules.obligationId("a", "m"), MailReturnRules.obligationId("a", "m"))
    }

    @Test
    fun theTidyLineSaysWhereTheyWent() {
        val (_, detail) = MailAutopilotRules.lines(mapOf(MailTidy.RECEIPT to 2), "Personal", listOf(MailReceipt(1000, "Shop")), returns = 1)
        assertTrue(detail.endsWith("· 1 return date → Renewals"), detail)
        assertEquals(" · 2 return dates → Renewals", MailReturnRules.suffix(2))
        assertEquals("", MailReturnRules.suffix(0))
    }

    @Test
    fun aReturnReminderReadsSentItBackAndKeepingIt() {
        assertTrue(MailReturnRules.isReturn("Last day to return · Amazon order"))
        assertTrue(MailReturnRules.isReturn("  last day to return · your order"))
        assertTrue(!MailReturnRules.isReturn("Warranty ends · Currys order"))
        assertEquals("Amazon", MailReturnRules.merchantOf("Last day to return · Amazon order"))
        assertNull(MailReturnRules.merchantOf("Last day to return · your order"))
        assertNull(MailReturnRules.merchantOf("Boiler service"))
        assertEquals("Refund · Amazon order · £40", MailReturnRules.refundTitle("Amazon", 4000))
        assertEquals("Refund · your order", MailReturnRules.refundTitle(null, null))
        assertEquals(MailReturnRules.refundWaitId("mr1"), MailReturnRules.refundWaitId("mr1"))
        assertTrue(MailReturnRules.refundWaitId("mr1") != MailReturnRules.refundWaitId("mr2"))

        val world = os.meka.core.testing.SyncWorld()
        var n = 0
        val r = Renewals(world.device("android").replica, { "R${n++}" }, { world.clock.nowMs })
        val today = world.clock.nowMs.floorDiv(CivilDate.DAY_MS)
        r.add("Last day to return · Amazon order", ObligationKind.OTHER, today + 10, cost = "40")
        r.add("Boiler service", ObligationKind.BOILER, today + 10, RenewalRepeat.YEARLY)
        val ret = r.items().single { it.title.startsWith("Last") }
        assertEquals(MailReturnRules.SENT_BACK_LABEL, ret.doneLabel)
        assertEquals(MailReturnRules.KEEPING_LABEL, ret.stopLabel)
        assertEquals("Serviced", r.items().single { it.title == "Boiler service" }.doneLabel)
    }

    @Test
    fun aHeadsUpTwoDaysBeforeTheLastDayAndOnIt() {
        val cal = LocalCalendar.UTC
        val last = CivilDate.toEpochDay(2026, 11, 13)
        val item = RenewalItem(
            id = "mr1", title = "Last day to return · Amazon order", kind = ObligationKind.OTHER, subject = null, notes = null,
            dueDay = last, cancelByDay = null, leadDays = 5, costPence = 4000, repeats = RenewalRepeat.NONE, repeatLabel = "",
            state = RenewalState.SOON, meta = "", doneLabel = MailReturnRules.SENT_BACK_LABEL, stopLabel = MailReturnRules.KEEPING_LABEL,
            hasConflict = false,
        )
        assertTrue(MailReturnRules.notices(listOf(item), cal.toEpochMs(last - 3, 600), cal).isEmpty())
        val before = MailReturnRules.notices(listOf(item), cal.toEpochMs(last - 2, 600), cal).single()
        assertEquals("Last day to return · Amazon order is Fri 13 Nov · £40", before.title)
        assertEquals("In 2 days · in Lists: Sent it back or Keeping it", before.text)
        assertEquals(NoticeSource.RETURNS, before.source)
        assertEquals(cal.toEpochMs(last - 2, 9 * 60), before.atMs)
        assertEquals(NoticeTarget.LISTS, before.target)
        val tomorrow = MailReturnRules.notices(listOf(item), cal.toEpochMs(last - 1, 600), cal).single()
        assertEquals(before.key, tomorrow.key) // the same heads-up, posted once
        val day = MailReturnRules.notices(listOf(item), cal.toEpochMs(last, 600), cal).single()
        assertEquals("Last day to return · Amazon order is today · £40", day.title)
        assertTrue(day.key != before.key)
        assertTrue(MailReturnRules.notices(listOf(item), cal.toEpochMs(last + 1, 600), cal).isEmpty())
        assertTrue(MailReturnRules.notices(listOf(item.copy(title = "Boiler service")), cal.toEpochMs(last, 600), cal).isEmpty())
    }
}
