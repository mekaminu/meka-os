package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gift planner, slice 1: birthdays and anniversaries typed once, ideas against each, the order-by day and the heads-ups. */
class GiftsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val gFold = Gifts(fold.replica, { "g${n++}" }, { world.clock.nowMs }, cal)
    private val gMac = Gifts(mac.replica, { "g${n++}" }, { world.clock.nowMs }, cal)

    init { at(sat, 10 * 60) }

    private fun at(day: Long, minute: Int) { world.clock.nowMs = cal.toEpochMs(day, minute) }
    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun occ(person: String, m: Int, day: Int, year: Int = 0, kind: OccasionKind = OccasionKind.BIRTHDAY, delivery: Int = 3, sortedFor: Long = 0) =
        Occasion(person.ifEmpty { "wedding" }, person, kind, m, day, year, delivery, sortedFor, 0)

    @Test
    fun anOccasionIsReadFromOneLine() {
        assertEquals(OccasionEntry("Mum", OccasionKind.BIRTHDAY, 11, 3, 0, null), GiftRules.read("Mum's birthday 3 Nov", 2026))
        assertEquals(OccasionEntry("Jordan", OccasionKind.BIRTHDAY, 3, 14, 1988, null), GiftRules.read("Jordan birthday 14 March 1988", 2026))
        assertEquals(OccasionEntry("Dad", OccasionKind.BIRTHDAY, 11, 3, 0, null), GiftRules.read("3rd November: Dad", 2026))
        assertEquals(OccasionEntry("Theo", OccasionKind.BIRTHDAY, 6, 5, 2016, null), GiftRules.read("Theo 5/6/2016", 2026))
        assertEquals(OccasionEntry("Finn", OccasionKind.BIRTHDAY, 1, 9, 0, null), GiftRules.read("finn's bday jan 9th", 2026))
        assertEquals(OccasionEntry("", OccasionKind.ANNIVERSARY, 8, 20, 2015, null), GiftRules.read("Wedding anniversary 20 Aug 2015", 2026))
        assertEquals(OccasionEntry("Mum and Dad", OccasionKind.ANNIVERSARY, 5, 12, 0, null), GiftRules.read("Mum and Dad's anniversary 12 May", 2026))
        assertEquals(OccasionEntry("Uncle Tony", OccasionKind.BIRTHDAY, 11, 3, 0, 5), GiftRules.read("uncle tony birthday 3 Nov delivery 5 days", 2026))
        assertEquals(OccasionEntry("Mum", OccasionKind.BIRTHDAY, 2, 29, 0, null), GiftRules.read("Mum 29 Feb", 2026))
        // No day, no one for a birthday, a day the month never has, a year still to come: not read.
        assertNull(GiftRules.read("Mum's birthday", 2026))
        assertNull(GiftRules.read("birthday 3 Nov", 2026))
        assertNull(GiftRules.read("Mum 31 Feb", 2026))
        assertNull(GiftRules.read("Mum 3/13", 2026))
        assertEquals(0, GiftRules.read("Mum 3 Nov 2030", 2026)?.year)

        assertEquals("Wedding anniversary", GiftRules.title("", OccasionKind.ANNIVERSARY))
        assertEquals("Mum and Dad's anniversary", GiftRules.title("Mum and Dad", OccasionKind.ANNIVERSARY))
        assertEquals("James's birthday", GiftRules.title("James", OccasionKind.BIRTHDAY))
    }

    @Test
    fun theNextDayAndTheOrderByDay() {
        val mum = occ("Mum", 11, 3, 1966)
        assertEquals(d(11, 3), GiftRules.next(mum, sat))
        assertEquals(d(10, 30), GiftRules.orderBy(mum, d(11, 3))) // 3 days' delivery and a spare day
        assertEquals(d(11, 3, 2027), GiftRules.next(mum, d(11, 4)))
        assertEquals("turns 60", GiftRules.age(mum, d(11, 3)))
        assertEquals("11 years", GiftRules.age(occ("", 8, 20, 2015, OccasionKind.ANNIVERSARY), d(8, 20)))
        // 29 February falls on the 28th in other years.
        assertEquals(d(2, 28, 2027), GiftRules.next(occ("Leap", 2, 29), sat))
        assertEquals(d(2, 29, 2028), GiftRules.next(occ("Leap", 2, 29), d(3, 1, 2027)))
    }

    @Test
    fun twoWeeksAheadAndOnTheOrderByDayUntilSorted() {
        val mum = occ("Mum", 11, 3, 1966).copy(ideas = listOf(GiftIdea("i1", "Mum", "silk scarf", 1), GiftIdea("i2", "Mum", "garden voucher", 2)))
        assertTrue(GiftRules.notices(listOf(mum), cal.toEpochMs(sat, 600), cal).isEmpty())
        val n = GiftRules.notices(listOf(mum), cal.toEpochMs(d(10, 20), 8 * 60), cal)
        assertEquals(
            listOf(
                Triple("Mum's birthday in 2 weeks · Tue 3 Nov", "Ideas: silk scarf, garden voucher · order by Fri 30 Oct", cal.toEpochMs(d(10, 20), 9 * 60)),
                Triple("Order Mum's gift today", "Birthday Tue 3 Nov · 3 days' delivery · Ideas: silk scarf, garden voucher", cal.toEpochMs(d(10, 30), 9 * 60)),
            ),
            n.map { Triple(it.title, it.text, it.atMs) },
        )
        assertTrue(n.all { it.source == NoticeSource.GIFTS })
        // Without ideas the heads-up says so.
        assertEquals("No gift ideas yet · add some in Gifts · order by Fri 30 Oct",
            GiftRules.notices(listOf(mum.copy(ideas = emptyList())), cal.toEpochMs(d(10, 20), 8 * 60), cal).first().text)
        // Sorted: nothing.
        assertTrue(GiftRules.notices(listOf(mum.copy(sortedForDay = d(11, 3))), cal.toEpochMs(d(10, 20), 8 * 60), cal).isEmpty())
        // Added after its order-by day: nothing posts; Today's line says so.
        assertTrue(GiftRules.notices(listOf(mum), cal.toEpochMs(d(11, 1), 8 * 60), cal).isEmpty())

        assertNull(GiftRules.todayLine(listOf(mum), d(10, 29)))
        assertEquals(GiftLine("Order Mum's gift today · birthday Tue 3 Nov", "Order Mum's gift today · birthday Tue 3 Nov.", true), GiftRules.todayLine(listOf(mum), d(10, 30)))
        assertEquals("Mum's gift not sorted yet · birthday Tue 3 Nov", GiftRules.todayLine(listOf(mum), d(10, 31))?.text)
        assertNull(GiftRules.todayLine(listOf(mum.copy(sortedForDay = d(11, 3))), d(10, 31)))
        assertEquals("Mum's birthday tomorrow", GiftRules.todayLine(listOf(mum), d(11, 2))?.text)
        assertEquals("Mum's birthday today · turns 60", GiftRules.todayLine(listOf(mum), d(11, 3))?.text)
        assertEquals("Dad's birthday today · and 1 more", GiftRules.todayLine(listOf(mum, occ("Dad", 11, 3)), d(11, 3))?.text)

        // The week ahead: the order-by day (lit) and the day itself.
        assertEquals(listOf("Order Mum's gift · birthday Tue 3 Nov" to true), GiftRules.weekLines(listOf(mum), d(10, 30)))
        assertEquals(listOf("Mum's birthday · turns 60" to false), GiftRules.weekLines(listOf(mum), d(11, 3)))
        assertTrue(GiftRules.weekLines(listOf(mum), d(10, 31)).isEmpty())
    }

    @Test
    fun typedOnTheFoldWithIdeasAndSortedOnTheMac() {
        assertEquals(GiftRules.EMPTY_LINE, gFold.view().summary)
        assertEquals("Added Mum's birthday · Tue 3 Nov · in 24 days · order by Fri 30 Oct", gFold.add("Mum's birthday 3 Nov").line)
        assertEquals("Added Wedding anniversary · Fri 20 Aug · in 314 days · 12 years · order by Mon 16 Aug",
            gFold.add("Wedding anniversary 20 Aug 2015").line)
        assertTrue(gFold.add("the birthdays").refused)
        // The same person again updates it, keeping the year it had.
        assertEquals("Updated Mum's birthday · Tue 3 Nov · in 24 days · order by Wed 28 Oct", gFold.add("Mum birthday 3 Nov delivery 5 days").line)
        val mumId = gFold.view().rows.first().id
        assertTrue(gFold.addIdea(mumId, "Silk scarf"))
        assertTrue(gFold.addIdea(mumId, "silk scarf")) // already there: one idea
        assertFalse(gFold.addIdea(mumId, "  "))
        assertEquals("Next: Mum's birthday · Tue 3 Nov", gFold.view().summary)

        sync()
        val row = gMac.view().rows.first()
        assertEquals("Mum's birthday", row.title)
        assertEquals("Tue 3 Nov · in 24 days", row.line)
        assertEquals("Order by Wed 28 Oct · 5 days' delivery", row.note)
        assertFalse(row.noteLit)
        assertEquals(listOf("Silk scarf"), row.ideas.map { it.text })
        assertEquals(listOf("Mum's birthday", "Wedding anniversary"), gMac.view().rows.map { it.title })

        at(d(10, 28), 8 * 60)
        assertTrue(gMac.view().rows.first().noteLit)
        assertTrue(gMac.setSorted(mumId, true))
        sync()
        assertEquals("✓ Gift sorted", gFold.view().rows.first().note)
        assertNull(GiftRules.todayLine(gFold.items(), d(10, 28)))
        assertTrue(GiftRules.notices(gFold.items(), world.clock.nowMs, cal).isEmpty())

        // Next year it isn't sorted any more.
        at(d(11, 4), 8 * 60)
        assertFalse(gFold.view().rows.first { it.id == mumId }.sorted)

        assertTrue(gFold.removeIdea(gFold.view().rows.first { it.id == mumId }.ideas.single().id))
        assertTrue(gMac.remove(mumId))
        sync()
        assertEquals(listOf("Wedding anniversary"), gFold.view().rows.map { it.title })
    }

    @Test
    fun askAndTalkKnowTheGiftsAndNoteAnIdea() {
        assertTrue(GiftAskRules.askLines(gFold.view()).isEmpty())
        gFold.add("Mum's birthday 3 Nov 1966")
        gFold.add("Wedding anniversary 20 Aug 2015")
        gFold.add("Mum and Dad's anniversary 12 May")
        val mumId = gFold.items().first { it.person == "Mum" }.id
        gFold.addIdea(mumId, "Silk scarf")

        assertEquals(
            listOf(
                "Gifts · 3 birthdays and anniversaries",
                "Mum's birthday · Tue 3 Nov · in 24 days · turns 60 · order by Fri 30 Oct · 3 days' delivery · ideas: Silk scarf",
                "Mum and Dad's anniversary · Wed 12 May · in 214 days · order by Sat 8 May · 3 days' delivery · no gift ideas yet",
                "Wedding anniversary · Fri 20 Aug · in 314 days · 12 years · order by Mon 16 Aug · 3 days' delivery · no gift ideas yet",
            ),
            GiftAskRules.askLines(gFold.view()),
        )

        // Which occasion a model's words name: the birthday before an anniversary for the same person.
        val items = gFold.items()
        assertEquals("Mum's birthday", GiftAskRules.match(items, "Mum")?.let(GiftRules::title))
        assertEquals("Mum's birthday", GiftAskRules.match(items, "Mum's birthday")?.let(GiftRules::title))
        assertEquals("Wedding anniversary", GiftAskRules.match(items, "Wedding anniversary")?.let(GiftRules::title))
        assertEquals("Mum and Dad's anniversary", GiftAskRules.match(items, "mum and dad's anniversary")?.let(GiftRules::title))
        assertNull(GiftAskRules.match(items, "Jordan"))
        assertNull(GiftAskRules.match(items, "Mum's anniversary"))

        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), world.clock.nowMs, cal, gifts = GiftAskRules.askLines(gFold.view()))
        assertEquals(4, ctx.items.count { it.kind == AskItemKind.GIFTS })
        assertFalse(ctx.untrusted)
        val card = AskRules.card(AskRawAction("add_gift_idea", title = "Mum: garden voucher"), ctx, emptyMap(), world.clock.nowMs, cal, occasions = items)!!
        assertEquals(AskProposal.AddGiftIdea(mumId, "Mum's birthday", "garden voucher"), card.proposal)
        assertEquals("Gift idea for Mum's birthday · garden voucher", card.line)
        assertEquals("Note", card.button)
        assertEquals("Noted garden voucher for Mum's birthday", AskRules.doneLine(card.proposal, sat))
        assertEquals("note garden voucher as a gift idea for Mum's birthday", TalkRules.phrase(card.proposal, sat, past = false))
        assertEquals("noted garden voucher as a gift idea for Mum's birthday", TalkRules.phrase(card.proposal, sat, past = true))
        assertNull(TrustRules.kindOf(card.proposal))
        // No one Meka keeps, no colon, no idea, or one already noted: no card.
        for (t in listOf("Jordan: perfume", "Mum silk scarf", "Mum:  ", "Mum: silk SCARF")) {
            assertNull(AskRules.card(AskRawAction("add_gift_idea", title = t), ctx, emptyMap(), world.clock.nowMs, cal, occasions = items), t)
        }

        // The add the card makes: the new idea's id, never for one already there.
        val id = gFold.addIdeaFromAsk(mumId, "garden voucher")!!
        assertNull(gFold.addIdeaFromAsk(mumId, "Garden voucher"))
        assertEquals(listOf("Silk scarf", "garden voucher"), gFold.items().first { it.id == mumId }.ideas.map { it.text })
        assertTrue(gFold.removeIdea(id))
        assertNull(gFold.addIdeaFromAsk("gone", "anything"))
    }
}
