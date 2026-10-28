package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money's top as the mockup: the Updated pill, the line under the title only when it says something, the chips' place. */
class MoneyHeaderTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val today = CivilDate.toEpochDay(2026, 10, 28)
    private val now = cal.toEpochMs(today, 9 * 60)
    private val banks = listOf(MoneyAccount("l1", "Oakbank Current", MoneyBank.SOURCE, now - 3_600_000, bankStatus = MoneyBank.OK))
    private fun sync(lastOk: Long?) = MoneyBankSync(MoneyBank.OK, lastOk, lastOk, 1, 40)

    @Test
    fun thePillSaysWhenTheBanksLastSynced() {
        assertNull(MoneyHeader.pill(null))
        val v = MoneyBank.view(sync(cal.toEpochMs(today, 6 * 60 + 10)), banks, 0, null, now, cal)
        assertEquals("Updated 06:10", MoneyHeader.pill(v))
        assertEquals("Updated 06:10, refresh", MoneyHeader.spoken(MoneyHeader.pill(v)!!))
        val yesterday = MoneyBank.view(sync(cal.toEpochMs(today - 1, 22 * 60)), banks, 0, null, now, cal)
        assertEquals("Updated Tue 27 Oct 22:00", MoneyHeader.pill(yesterday))
        val justNow = MoneyBank.view(sync(now - 10_000), banks, 0, null, now, cal)
        assertEquals("Updated just now", MoneyHeader.pill(justNow))
        // Never synced yet: nothing to say when.
        assertNull(MoneyHeader.pill(MoneyBank.view(sync(null), banks, 0, null, now, cal)))
    }

    @Test
    fun aRefreshHeAskedForSaysUpdatingUntilTheServerRuns() {
        val v = MoneyBank.view(sync(cal.toEpochMs(today, 6 * 60)), banks, 0, askedAtMs = now - 5_000, nowMs = now, cal = cal)
        assertEquals(MoneyHeader.UPDATING, MoneyHeader.pill(v))
    }

    @Test
    fun theLineUnderTheTitleOnlyShowsWhenLockedOrEmpty() {
        assertTrue(MoneyHeader.showsSummary(MoneyView.LOCKED))
        val empty = MoneyView(false, MoneyRules.EMPTY_SUMMARY, MoneyRules.EMPTY, null, emptyList(), earlier = false, later = false)
        assertTrue(MoneyHeader.showsSummary(empty))
        val full = MoneyView(false, "2 accounts · 23 transactions", null, null, emptyList(), earlier = false, later = false)
        assertFalse(MoneyHeader.showsSummary(full))
    }

    @Test
    fun theChipsTakeTheLabelsPlaceOnlyWithNeedsAndWants() {
        assertFalse(MoneyHeader.chipsInHead(null))
        val centre = MoneyRingCentre("Spent so far", "£10", null, MoneyTone.QUIET, "Spent so far £10")
        val plain = MoneySpendingView(centre, emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertFalse(MoneyHeader.chipsInHead(plain))
        assertTrue(MoneyHeader.chipsInHead(plain.copy(needs = MoneyNeedsView(emptyList(), emptyList()))))
    }
}
