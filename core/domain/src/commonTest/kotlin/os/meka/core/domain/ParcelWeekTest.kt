package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deliveries, slice 2: parcels in the week ahead (lit on an office day) and the out-for-delivery heads-up. */
class ParcelWeekTest {
    // Sunday 11 October 2026, British Summer Time (UTC+1).
    private val offset = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(offset)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private val mon = sun + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(at(day, 0), at(day + 1, 0), offset)

    private fun parcel(id: String, day: Long, state: ParcelState = ParcelState.COMING, window: String? = null, carrier: String? = null, merchant: String? = "Shop", lastAt: Long = 0L) =
        Parcel(id, "Personal", state, day, window, carrier, merchant, lastAt)

    @Test
    fun theWeekLineForADay() {
        assertEquals(
            ParcelWeekLine("Parcel · Shop · 10:15–11:15", "Parcel arriving from Shop, between 10:15 and 11:15", false),
            ParcelRules.weekLine(listOf(parcel("a", mon, window = "10:15–11:15")), mon, officeDay = false),
        )
        val office = ParcelRules.weekLine(listOf(parcel("a", mon, carrier = "DPD", merchant = null)), mon, officeDay = true)!!
        assertEquals("Parcel · DPD · you're at the office", office.text)
        assertEquals("Parcel arriving from DPD, while you're at the office", office.spoken)
        assertTrue(office.lit)
        val three = listOf(parcel("a", mon, merchant = "Alpha"), parcel("b", mon, merchant = "Beta"), parcel("c", mon, merchant = "Gamma"),
            parcel("d", mon, ParcelState.DELIVERED, merchant = "Delta"), parcel("e", mon + 1, merchant = "Later"))
        assertEquals("3 parcels · Alpha, Beta + 1", ParcelRules.weekLine(three, mon, officeDay = false)!!.text)
        assertEquals("Parcel out for delivery · Shop", ParcelRules.weekLine(listOf(parcel("a", mon, ParcelState.OUT_FOR_DELIVERY)), mon, false)!!.text)
        // Delivered or another day: nothing.
        assertNull(ParcelRules.weekLine(listOf(parcel("d", mon, ParcelState.DELIVERED)), mon, false))
        assertNull(ParcelRules.weekLine(listOf(parcel("a", mon + 1)), mon, false))
    }

    @Test
    fun theWeekAheadShowsEachParcelOnItsDayLitOnAnOfficeDay() {
        val inputs = WeekAheadInputs(
            events = emptyList(),
            work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE, homeDays = setOf(mon + 2)),
            holidays = HolidayCalendar.NONE,
            parcels = listOf(parcel("a", mon + 1, merchant = "Office"), parcel("b", mon + 2, merchant = "Home"), parcel("c", mon + 3, ParcelState.DELIVERED)),
        )
        val v = WeekAheadRules.view(inputs, at(sun, 18), cal, ::window)
        val tue = v.days.single { it.epochDay == mon + 1 }.lines.single { it.kind == WeekAheadKind.PARCEL }
        assertEquals("Parcel · Office · you're at the office", tue.text)
        assertTrue(tue.lit)
        val wed = v.days.single { it.epochDay == mon + 2 }.lines.single { it.kind == WeekAheadKind.PARCEL }
        assertEquals("Parcel · Home", wed.text)
        assertEquals(false, wed.lit)
        assertTrue(v.days.single { it.epochDay == mon + 3 }.lines.none { it.kind == WeekAheadKind.PARCEL })
        assertTrue("Parcel arriving from Home" in v.spoken)
    }

    @Test
    fun aHeadsUpWhenOneIsOutForDelivery() {
        val now = at(mon, 9, 30)
        val out = parcel("a", mon, ParcelState.OUT_FOR_DELIVERY, window = "10:15–11:15", carrier = "DPD", lastAt = at(mon, 7, 5))
        val n = ParcelRules.notices(listOf(out, parcel("b", mon), parcel("c", mon + 1, ParcelState.OUT_FOR_DELIVERY)), now, cal).single()
        assertEquals("Parcel out for delivery · Shop", n.title)
        assertEquals("Between 10:15 and 11:15 · with DPD", n.text)
        assertEquals(NoticeSource.PARCELS, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.TODAY, n.target)
        assertEquals(at(mon, 7, 5), n.atMs)
        assertEquals(at(mon + 1, 0), n.expiresAtMs)
        // From the carrier itself with no window: "Arriving today"; an email from yesterday is due from midnight.
        val carrierOnly = parcel("d", mon, ParcelState.OUT_FOR_DELIVERY, carrier = "Evri", merchant = null, lastAt = at(sun, 22))
        val m = ParcelRules.notices(listOf(carrierOnly), now, cal).single()
        assertEquals("Parcel out for delivery · Evri", m.title)
        assertEquals("Arriving today", m.text)
        assertEquals(at(mon, 0), m.atMs)
        // Delivered: no heads-up.
        assertTrue(ParcelRules.notices(listOf(out.copy(state = ParcelState.DELIVERED)), now, cal).isEmpty())
        // Through the collector too.
        val all = NoticeSources.collect(ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, TodayProjection.project(emptyList(), now, CalendarAgenda.window(mon, cal), emptyList(), cal), now, cal, parcels = listOf(out))
        assertEquals(1, all.count { it.source == NoticeSource.PARCELS })
    }
}
