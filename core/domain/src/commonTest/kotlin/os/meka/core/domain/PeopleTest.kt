package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** People, slice 2: one row per person from promises both ways and birthdays, with the last contact MEKA has seen. */
class PeopleTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val today = CivilDate.toEpochDay(2026, 10, 12) // Mon 12 Oct 2026
    private val now = cal.toEpochMs(today, 15 * 60)

    private fun promise(id: String, title: String, to: String?, state: DueState = DueState.LATER, meta: String = "$to · by Thu 15 Oct") =
        PromiseItem(id, title, to, null, 0, null, state, meta, false)

    private fun waiting(id: String, title: String, who: String?, state: DueState = DueState.NONE, meta: String = "$who · since Sat 10 Oct") =
        WaitingItem(id, title, who, null, 0, null, null, state, meta, false)

    private fun birthday(person: String, m: Int, d: Int, year: Int = 0, kind: OccasionKind = OccasionKind.BIRTHDAY) =
        Occasion("o-$person", person, kind, m, d, year, 3, 0, 0)

    @Test
    fun onePersonPerNameWithWhatsOwedBothWaysAndTheirBirthday() {
        val v = PeopleRules.view(
            promises = listOf(promise("p1", "Fix the bike", "rex"), promise("p2", "Lift to football", "Rex", DueState.DUE, "Rex · due today")),
            waiting = listOf(waiting("w1", "Refund", "Argos"), waiting("w2", "Photos", "Mum", DueState.DUE, "Mum · chase today")),
            occasions = listOf(
                birthday("Mum", 11, 3, 1966), birthday("Rex", 10, 15, 2016),
                birthday("", 8, 20, kind = OccasionKind.ANNIVERSARY), birthday("Mum and Dad", 5, 12, kind = OccasionKind.ANNIVERSARY),
            ),
            contacts = emptyList(), today = today, nowMs = now, cal = cal,
        )
        assertEquals(listOf("Mum", "Rex", "Argos"), v.rows.map { it.name }) // lit first, then by name
        val rex = v.rows[1]
        assertEquals("You promised 2", rex.owedLine)
        assertEquals(listOf("Lift to football", "Fix the bike"), rex.promised.map { it.title }) // due first
        assertEquals("Birthday Thu 15 Oct · in 3 days · turns 10", rex.birthday)
        assertTrue(rex.lit)
        val mum = v.rows[0]
        assertEquals("Waiting for 1", mum.owedLine)
        assertEquals("Birthday Tue 3 Nov · in 22 days · turns 60", mum.birthday)
        assertTrue(mum.lit) // the chase is due, not the birthday
        val argos = v.rows[2]
        assertFalse(argos.lit)
        assertNull(argos.birthday)
        assertEquals("3 people · 1 promise due · 1 to chase", v.summary)
        assertTrue(rex.spoken.startsWith("Rex. You promised: Lift to football, Rex · due today."))
        // Anniversaries are nobody's row; a promise to no one is no row either.
        assertTrue(PeopleRules.view(listOf(promise("p", "Tidy", null)), emptyList(), listOf(birthday("", 8, 20, kind = OccasionKind.ANNIVERSARY)), emptyList(), today, now, cal).isEmpty)
        assertEquals(PeopleRules.EMPTY_LINE, PeopleView.EMPTY.summary)
    }

    @Test
    fun theBirthdayLineSaysTodayAndTomorrowAndIsLitWithinAWeek() {
        fun line(m: Int, d: Int) = PeopleRules.birthdayLine(birthday("Jo", m, d, 1990), today)
        assertEquals("Birthday today · turns 36", line(10, 12))
        assertEquals("Birthday tomorrow · turns 36", line(10, 13))
        assertEquals("Birthday Mon 11 Oct · in 364 days · turns 37", line(10, 11))
        fun lit(m: Int, d: Int) = PeopleRules.view(emptyList(), emptyList(), listOf(birthday("Jo", m, d)), emptyList(), today, now, cal).rows.single().lit
        assertTrue(lit(10, 19))
        assertFalse(lit(10, 20))
    }

    @Test
    fun theLastContactIsTheNewestMessageOrReplyAndOnlyJoinsSomeoneAlreadyListed() {
        val at = { day: Long, min: Int -> cal.toEpochMs(day, min) }
        val contacts = listOf(
            ContactSeen("Jeanette Akunebuni", at(today - 1, 18 * 60 + 40), CaptureApp.WHATSAPP, fromMe = false),
            ContactSeen("Jeanette Akunebuni", at(today, 13 * 60 + 2), CaptureApp.WHATSAPP, fromMe = true),
            ContactSeen("Rex", at(today - 3, 9 * 60), CaptureApp.PHONE, false, CaptureKind.MISSED_CALL),
            ContactSeen("Tunde", at(today, 14 * 60), CaptureApp.SMS, false), // no row: not listed
        )
        val v = PeopleRules.view(
            listOf(promise("p", "Book the table", "Jeanette"), promise("q", "Fix the bike", "Rex")), emptyList(), emptyList(),
            contacts, today, now, cal,
        )
        assertEquals(listOf("Jeanette", "Rex"), v.rows.map { it.name })
        assertEquals("You replied 13:02 · WhatsApp", v.rows[0].lastContact)
        assertEquals("Missed call Fri 9 Oct 09:00 · Phone", v.rows[1].lastContact)
        // A first name only one row has joins; two rows sharing it join neither.
        assertEquals("jeanette", PeopleRules.match("jeanette akunebuni", listOf("jeanette", "rex")))
        assertEquals("jeanette akunebuni", PeopleRules.match("jeanette", listOf("jeanette akunebuni")))
        assertNull(PeopleRules.match("jo", listOf("jo smith", "jo brown")))
        assertNull(PeopleRules.match("tunde", listOf("jeanette")))
    }

    @Test
    fun triageCardsAndHeldMessagesBecomeContacts() {
        val world = SyncWorld()
        val fold = world.device("android")
        world.clock.nowMs = now
        val cards = TriageCards(fold.replica, { world.clock.nowMs }, cal)
        fun item(id: String, who: String, group: String? = null) =
            CapturedItem(id, CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, "hi", group, now - hour)
        cards.save(item("m1", "Jeanette"), MessageTriage(TriageLane.NEEDS_REPLY, summary = "Asks about dinner", draft = "Yes!"))
        cards.save(item("m2", "Tunde", group = "Barça lads"), MessageTriage(TriageLane.FYI, summary = "Score"))
        cards.resolve("m1", TriageResolution.SENT)
        val seen = cards.contacts().sortedBy { it.atMs }
        assertEquals(listOf("Jeanette" to false, "Jeanette" to true), seen.map { it.person to it.fromMe })
        assertEquals(now, seen.last().atMs)

        val summary = AfterWorkSummaries.build(listOf(item("h1", "Mum"), item("h2", "Ada", group = "Family chat")), PeopleLists())
        assertEquals(listOf("Mum"), PeopleRules.heldContacts(summary).map { it.person })
    }

    @Test
    fun askAndTalkKnowThePeopleAndAddAPromise() {
        assertTrue(PeopleAskRules.askLines(PeopleView.EMPTY).isEmpty())
        val promised = listOf(
            promise("p1", "Fix the bike", "rex"), promise("p2", "Lift to football", "Rex", DueState.DUE, "Rex · due today"),
            promise("p3", "Tidy the shed", null, meta = "since Sat 10 Oct"),
        )
        val v = PeopleRules.view(
            promises = promised,
            waiting = listOf(waiting("w2", "Photos", "Mum", DueState.DUE, "Mum · chase today")),
            occasions = listOf(birthday("Mum", 11, 3, 1966)),
            contacts = listOf(ContactSeen("Mum", now - hour, CaptureApp.WHATSAPP, fromMe = false)),
            today = today, nowMs = now, cal = cal,
        )
        assertEquals(
            listOf(
                "People · 2 people · 1 promise due · 1 to chase",
                "Mum · waiting for 1 · birthday Tue 3 Nov · in 22 days · turns 60 · last message 14:00 · WhatsApp",
                "Mum · waiting for: Photos · chase today",
                "Rex · you promised 2",
                "Rex · you promised: Lift to football · due today",
                "Rex · you promised: Fix the bike · by Thu 15 Oct",
            ),
            PeopleAskRules.askLines(v),
        )
        assertEquals("by tomorrow", PeopleAskRules.bareMeta("rex · by tomorrow", "rex"))
        assertEquals("since today", PeopleAskRules.bareMeta("since today", "rex"))

        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), now, cal, people = PeopleAskRules.askLines(v))
        assertEquals(6, ctx.items.count { it.kind == AskItemKind.PEOPLE })
        assertFalse(ctx.untrusted)

        fun card(title: String?, date: String? = null) =
            AskRules.card(AskRawAction("add_promise", title = title, date = date), ctx, emptyMap(), now, cal, people = v, promised = promised)
        // A person the pane has is written as the pane writes them; a by-day reads from the date.
        val c = card("rex: sort his bike lights", "2026-10-13")!!
        assertEquals(AskProposal.AddPromise("Rex", "Sort his bike lights", today + 1), c.proposal)
        assertEquals("Promise to Rex · Sort his bike lights · by tomorrow", c.line)
        assertEquals("Add", c.button)
        assertEquals("Added your promise to Rex: Sort his bike lights · by tomorrow", AskRules.doneLine(c.proposal, today))
        assertEquals("add your promise to Rex: Sort his bike lights, by tomorrow", TalkRules.phrase(c.proposal, today, past = false))
        assertEquals("added your promise to Rex: Sort his bike lights, by tomorrow", TalkRules.phrase(c.proposal, today, past = true))
        assertNull(TrustRules.kindOf(c.proposal))
        // Someone new keeps the name as given; nobody named, no date.
        assertEquals(AskProposal.AddPromise("Jeanette", "The photos", null), card("Jeanette: the photos")!!.proposal)
        val nobody = card("Clear the gutters")!!
        assertEquals(AskProposal.AddPromise(null, "Clear the gutters", null), nobody.proposal)
        assertEquals("Promise · Clear the gutters", nobody.line)
        assertEquals("Added your promise: Clear the gutters", AskRules.doneLine(nobody.proposal, today))
        // Nothing promised, a bad or past date, or one already open to the same person: no card.
        for ((t, d) in listOf("Rex:  " to null, null to null, "Rex: fix the BIKE" to null, "Rex: a thing" to "2026-10-11", "Rex: a thing" to "soon")) {
            assertNull(card(t, d), "$t $d")
        }
    }

    @Test
    fun theFoldsListsGivePeopleARowAndJoinTheOnesThatExist() {
        val listed = ListedPeople(
            family = setOf("Jeanette", "mum", "07700 900123"),
            alwaysNotify = setOf("Mum", "Tunde"),
            watched = setOf("Coach Dave", "Tunde"),
        )
        val v = PeopleRules.view(
            promises = listOf(promise("p", "Book the table", "Jeanette Akunebuni")),
            waiting = emptyList(),
            occasions = listOf(birthday("Mum", 11, 3, 1966)),
            contacts = listOf(ContactSeen("Tunde", now - hour, CaptureApp.WHATSAPP, fromMe = false)),
            today = today, nowMs = now, cal = cal, listed = listed,
        )
        // A first name joins the promise's row; a number with no name is no row; the first list that has them names them.
        assertEquals(listOf("Coach Dave", "Jeanette Akunebuni", "Mum", "Tunde"), v.rows.map { it.name })
        assertEquals(listOf("Watched for requests", "Family", "Family", "Always notify"), v.rows.map { it.listed })
        val tunde = v.rows.last()
        assertNull(tunde.owedLine)
        assertFalse(tunde.lit)
        assertEquals("Last message 14:00 · WhatsApp", tunde.lastContact) // a listed person's contact now has a row to join
        assertEquals("Tunde. Always notify. Last message 14:00 · WhatsApp.", tunde.spoken)
        assertEquals("4 people", v.summary)
        assertEquals("Mum · family · birthday Tue 3 Nov · in 22 days · turns 60", PeopleAskRules.askLines(v)[4])
        // Without the lists (the Mac) only promises and birthdays make rows, as before.
        assertEquals(listOf("Jeanette Akunebuni", "Mum"), PeopleRules.view(
            listOf(promise("p", "Book the table", "Jeanette Akunebuni")), emptyList(), listOf(birthday("Mum", 11, 3, 1966)),
            emptyList(), today, now, cal,
        ).rows.map { it.name })
        assertTrue(PeopleRules.view(emptyList(), emptyList(), emptyList(), emptyList(), today, now, cal, ListedPeople(family = setOf("07700 900123"))).isEmpty)
    }
}
