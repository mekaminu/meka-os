package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renewals keep their email, slice 2: the approved card layout's words, tabs and the Done tab's Put it back. */
class RenewalBoardTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val d = world.device("android")
    private val r = Renewals(d.replica, { "R${n++}" }, { world.clock.nowMs })
    private val today = CivilDate.toEpochDay(2026, 10, 29)

    init { world.clock.nowMs = today * dayMs + 10 * 3_600_000L }

    @Test
    fun theTilesCountTheNextMonthWhatToCancelAndTheYear() {
        r.add("Streaming", ObligationKind.SUBSCRIPTION, today + 27, RenewalRepeat.YEARLY, cost = "24", cancelByDaysBefore = 3)
        r.add("Car insurance", ObligationKind.INSURANCE, today + 14, RenewalRepeat.YEARLY, cost = "412", cancelByDaysBefore = 7)
        r.add("MOT", ObligationKind.MOT, today + 66, RenewalRepeat.YEARLY, cost = "54.85")
        val b = RenewalBoardRules.board(r.view(), today, RenewalTab.COMING_UP)
        assertEquals(listOf("Next 30 days" to "2", "To cancel" to "2", "A year" to "£491"), b.tiles.map { it.label to it.value })
        assertTrue(b.tiles[1].warn)
        assertFalse(b.tiles[0].warn)
        assertEquals(listOf("Car insurance", "Streaming", "MOT"), b.cards.map { it.title })
        assertNull(b.empty)
        assertEquals(RenewalBoardRules.FOOTER, b.footer)
    }

    @Test
    fun aCardHasItsDateTileCostAndCancelBadge() {
        r.add("Streaming", ObligationKind.SUBSCRIPTION, CivilDate.toEpochDay(2026, 11, 25), RenewalRepeat.YEARLY, cost = "24", cancelByDaysBefore = 3)
        val c = RenewalBoardRules.board(r.view(), today, RenewalTab.ALL).cards.single()
        assertEquals("NOV", c.month)
        assertEquals("25", c.day)
        assertEquals("Subscription", c.sub)
        assertEquals("£24/yr", c.cost)
        assertEquals("cancel in 24 days", c.badge)
        assertTrue(c.warn)
        assertEquals(
            listOf("Renews" to "Wed 25 Nov", "Cancel by" to "Sun 22 Nov", "Repeats" to "Yearly"),
            c.tiles.map { it.label to it.value },
        )
        assertFalse(c.closed)
    }

    @Test
    fun badgesSayOverdueTodayAndInDays() {
        val mot = item(today + 67)
        assertEquals("in 67 days" to false, RenewalBoardRules.badge(mot, today))
        assertEquals("tomorrow" to true, RenewalBoardRules.badge(mot.copy(dueDay = today + 1, state = RenewalState.SOON), today))
        assertEquals("overdue 3 days" to true, RenewalBoardRules.badge(mot.copy(dueDay = today - 3, state = RenewalState.OVERDUE), today))
        assertEquals("cancel today" to true, RenewalBoardRules.badge(mot.copy(cancelByDay = today), today))
        assertEquals("cancelled today" to false, RenewalBoardRules.badge(mot.copy(closedDay = today, closedHow = "Cancelled"), today))
    }

    @Test
    fun shortCostsKeepToTheCard() {
        assertEquals("£9.99/mo", RenewalBoardRules.costShort(999, RenewalRepeat.MONTHLY))
        assertEquals("£30/3 mo", RenewalBoardRules.costShort(3000, RenewalRepeat.QUARTERLY))
        assertEquals("£1,240.50", RenewalBoardRules.costShort(124050, RenewalRepeat.NONE))
    }

    @Test
    fun cancelledGoesToDoneAndPutItBackReturnsIt() {
        val id = r.add("Streaming", ObligationKind.SUBSCRIPTION, today + 20, RenewalRepeat.MONTHLY, cost = "9.99")
        val one = r.add("Passport photos", ObligationKind.OTHER, today + 5)
        r.stop(id)
        r.done(one)
        val done = RenewalBoardRules.board(r.view(), today, RenewalTab.DONE)
        assertEquals(setOf("Streaming", "Passport photos"), done.cards.map { it.title }.toSet())
        val s = done.cards.first { it.title == "Streaming" }
        assertTrue(s.closed)
        assertEquals("cancelled today", s.badge)
        assertEquals("done today", done.cards.first { it.title == "Passport photos" }.badge)
        assertTrue(RenewalBoardRules.board(r.view(), today, RenewalTab.ALL).cards.isEmpty())

        assertTrue(r.reopen(id))
        assertFalse(r.reopen(id))
        assertEquals(listOf("Streaming"), RenewalBoardRules.board(r.view(), today, RenewalTab.ALL).cards.map { it.title })

        world.clock.advance(91 * dayMs)
        assertEquals(
            "Nothing renewed off the list or cancelled in the last three months.",
            RenewalBoardRules.board(r.view(), today + 91, RenewalTab.DONE).empty,
        )
    }

    @Test
    fun theEditSheetSaysWhatEachRowIsNow() {
        val id = r.add("Car insurance", ObligationKind.INSURANCE, CivilDate.toEpochDay(2026, 11, 12), RenewalRepeat.YEARLY, cost = "412", cancelByDaysBefore = 7)
        r.setLead(id, 10)
        val rows = RenewalBoardRules.editRows(r.items().single(), today)
        assertEquals(
            listOf(
                "Renews" to "Thu 12 Nov", "Repeats" to "Every year", "Remind me" to "10 days before",
                "Cancel by" to "A week before", "Kind" to "Insurance", "Cost" to "£412 a year", "Pay link" to "Not set",
            ),
            rows.map { it.label to it.value },
        )
        assertEquals(RenewalEditKey.entries.toList(), rows.map { it.key })
    }

    @Test
    fun anEmptyComingUpPointsAtAll() {
        r.add("Passport", ObligationKind.LICENCE, today + 400)
        assertEquals("Nothing in the next three months. All shows the rest.", RenewalBoardRules.board(r.view(), today, RenewalTab.COMING_UP).empty)
        assertNull(RenewalBoardRules.board(RenewalsView.EMPTY, today, RenewalTab.COMING_UP).empty)
    }

    private fun item(due: Long) = RenewalItem(
        id = "x", title = "MOT", kind = ObligationKind.MOT, subject = null, notes = null, dueDay = due, cancelByDay = null,
        leadDays = 28, costPence = null, repeats = RenewalRepeat.YEARLY, repeatLabel = "Every year", state = RenewalState.LATER,
        meta = "", doneLabel = "MOT done", stopLabel = "Stop tracking", hasConflict = false,
    )
}
