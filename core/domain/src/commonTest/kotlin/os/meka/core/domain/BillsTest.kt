package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillsTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "B${n++}"

    private val d = world.device("android")
    private val r = Renewals(d.replica, ::ids, { world.clock.nowMs })
    private val cal = LocalCalendar.UTC

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun at(y: Int, m: Int, day: Int, hour: Int = 10) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + hour * 3_600_000L }
    private fun notices() = BillRules.notices(r.items(), world.clock.nowMs, cal)

    @Test
    fun aBillRemindsThreeDaysBeforeWithTheAmountAndPayOpensTheSavedLink() {
        at(2026, 10, 12)
        val tax = r.add("Council tax", ObligationKind.BILL, today() + 3, RenewalRepeat.MONTHLY, cost = "180")
        // The pay link: cleaned to https, a typed non-address refused, blank clears it.
        assertFalse(r.setPayLink(tax, "not a link"))
        assertTrue(r.setPayLink(tax, "www.centralbedfordshire.gov.uk/council-tax"))
        val item = r.items().single()
        assertEquals("https://www.centralbedfordshire.gov.uk/council-tax", item.payLink)
        assertEquals("Pay on centralbedfordshire.gov.uk ↗", BillRules.payLabel(item.payLink!!))

        val n = notices().single()
        assertEquals("Council tax due Thu 15 Oct · £180", n.title)
        assertEquals("In 3 days · Pay opens centralbedfordshire.gov.uk", n.text)
        assertEquals(listOf(NoticeAction.PAY), n.actions)
        assertEquals("https://www.centralbedfordshire.gov.uk/council-tax", n.link)
        assertEquals(NoticeSource.BILLS, n.source)
        assertEquals(cal.toEpochMs(today(), BillRules.NOTICE_MIN), n.atMs)
        assertEquals("bill:$tax:${today() + 3}", n.key)

        // The day before, the same key (it posts once) with the words for tomorrow.
        at(2026, 10, 14)
        assertEquals("Council tax due tomorrow · £180" to "Tomorrow · Pay opens centralbedfordshire.gov.uk", notices().single().let { it.title to it.text })
        // Paid moves it to next month: nothing until three days before then.
        r.done(tax)
        assertTrue(notices().isEmpty())
        at(2026, 11, 12)
        assertEquals("bill:$tax:${CivilDate.toEpochDay(2026, 11, 15)}", notices().single().key)

        assertTrue(r.setPayLink(tax, " "))
        assertNull(r.items().single().payLink)
        val plain = notices().single()
        assertEquals("In 3 days · mark it Paid in Lists once it's done", plain.text)
        assertTrue(plain.actions.isEmpty())
        assertNull(plain.link)
    }

    @Test
    fun whatCountsAsABill() {
        at(2026, 10, 12)
        r.add("Water", ObligationKind.BILL, today() + 2, RenewalRepeat.MONTHLY)
        val netflix = r.add("Netflix", ObligationKind.SUBSCRIPTION, today() + 1, RenewalRepeat.MONTHLY, cost = "10.99")
        r.add("Spotify", ObligationKind.SUBSCRIPTION, today() + 1, RenewalRepeat.MONTHLY)
        r.add("Car tax", ObligationKind.CAR_TAX, today() + 2, RenewalRepeat.YEARLY)
        r.add("Broadband contract", ObligationKind.BILL, today() + 2)
        r.add("Electric", ObligationKind.BILL, today() + 10, RenewalRepeat.MONTHLY)
        // A subscription he pays by hand: a pay link makes it a bill.
        r.setPayLink(netflix, "netflix.com")
        assertEquals(listOf("Water due Wed 14 Oct", "Netflix due tomorrow · £10.99"), notices().map { it.title })
        assertEquals("Today", run { at(2026, 10, 14); notices().single { it.title.startsWith("Water") }.text.substringBefore(" ·") })
    }
}
