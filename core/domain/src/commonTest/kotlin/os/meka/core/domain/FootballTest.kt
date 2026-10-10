package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekend football logistics, slice 1: the kit reminder the evening before a club fixture. */
class FootballTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    // Fixed +1 h (London in October before the clocks go back).
    private val cal = LocalCalendar.fixedOffset(hour)
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)
    private val eaFold = actions(fold)
    private val eaMac = actions(mac)

    init { world.clock.nowMs = at(thu, 12) }

    private fun event(
        id: String = "ev1",
        title: String = "BUFC U9s v Arlesey",
        start: Long = at(thu + 2, 10),
        calendarName: String? = "Personal",
        provider: String = "google",
        allDay: Boolean = false,
    ) = CalendarEvent(id, title, start, start + hour, allDay, "Bury Field", provider, "meka@gmail.com", calendarName)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun clubFixturesAreTheClubsNamedAsWholeWordsNeverBarcaOrLookAlikes() {
        assertEquals("BUFC", FootballRules.club(event()))
        assertEquals("SJFC", FootballRules.club(event(title = "Match", calendarName = "sjfc u11s")))
        assertEquals("SJFC", FootballRules.club(event(title = "SJFC: away at Potton")))
        assertNull(FootballRules.club(event(title = "BUFCX training")))
        assertNull(FootballRules.club(event(title = "Training", calendarName = "Club")))
        // The fixtures feed (Barça) never counts.
        assertNull(FootballRules.club(event(title = "BUFC v Barça", provider = "fixtures")))
    }

    @Test
    fun kitReminderPlansThePackingAt19TheEveningBeforeWithTheDefaultList() {
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        assertEquals(FootballRules.kitTaskId("ev1"), id)
        val t = assertNotNull(fold.tasks.get(id))
        assertEquals("Pack the kit for BUFC U9s v Arlesey", t.title)
        assertEquals(at(thu + 1, 19), t.scheduledAtMs)
        assertEquals(at(thu + 1, 19), t.remindAtMs)
        assertEquals(at(thu + 2, 10), t.dueAtMs)
        assertEquals("ev1", t.eventId)
        assertEquals(FootballRules.DEFAULT_KIT, t.checklist.map { it.text })

        val d = EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks())
        assertFalse(d.canKit)
        assertEquals(id, d.kitTaskId)
        assertEquals("Kit reminder tomorrow 19:00 · 0 of 5 packed", d.kitLine)
        // The kit task is not the event's prep task.
        assertNull(eaFold.marks().prepTasks["ev1"])
        // Further ahead it names the day.
        assertEquals("Kit reminder Fri 19:00 · 0 of 5 packed", EventDetails.build(e, at(thu - 1, 12), cal, eaFold.marks()).kitLine)
        assertEquals("Kit reminder tomorrow 19:00", FootballRules.addedLine(FootballRules.plan(e, at(thu, 9), cal), at(thu, 9), cal))

        // A second tap leaves the open one as it is.
        fold.tasks.setChecklistItemChecked(t.checklist.first().id, true)
        assertEquals(id, eaFold.addKit(e))
        assertEquals("Kit reminder tomorrow 19:00 · 1 of 5 packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
    }

    @Test
    fun onlyClubFixturesStillToComeOfferIt() {
        assertTrue(EventDetails.build(event(), world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertFalse(EventDetails.build(event(title = "Dentist"), world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertNull(eaFold.addKit(event(title = "Dentist")))
        val started = event(start = at(thu, 11))
        assertFalse(EventDetails.build(started, world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertNull(eaFold.addKit(started))
    }

    @Test
    fun tooLateForTheEveningBeforeItIsJustTheListDueAtKickOff() {
        world.clock.nowMs = at(thu + 1, 20)
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        val t = assertNotNull(fold.tasks.get(id))
        assertNull(t.scheduledAtMs)
        assertNull(t.remindAtMs)
        assertEquals(at(thu + 2, 10), t.dueAtMs)
        assertEquals("Kit list · 0 of 5 packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
        assertEquals("Kit list added", FootballRules.addedLine(FootballRules.plan(e, world.clock.nowMs, cal), world.clock.nowMs, cal))
        t.checklist.forEach { fold.tasks.setChecklistItemChecked(it.id, true) }
        assertEquals("Kit packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
    }

    @Test
    fun anAllDayTournamentIsRemindedTheEveningBeforeItsFirstDay() {
        val sat = thu + 2
        val e = event(title = "SJFC tournament", start = sat * CivilDate.DAY_MS, allDay = true)
            .copy(endAtMs = (sat + 1) * CivilDate.DAY_MS)
        val t = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(e))))
        assertEquals(at(thu + 1, 19), t.remindAtMs)
        assertEquals(at(sat, 9), t.dueAtMs)
    }

    @Test
    fun mekasOwnListCarriesOnToTheNextFixture() {
        val first = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(event()))))
        fold.tasks.deleteChecklistItem(first.checklist.last().id) // Coat
        fold.tasks.addChecklistItem(first.id, "Gloves")
        world.clock.nowMs += 1_000

        val next = event(id = "ev2", title = "BUFC U9s v Potton", start = at(thu + 9, 10))
        val t = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(next))))
        assertEquals(listOf("Boots", "Shin pads", "Kit and socks", "Water bottle", "Gloves"), t.checklist.map { it.text })
        assertEquals(listOf("Boots", "Shin pads", "Kit and socks", "Water bottle", "Gloves"), FootballRules.kitList(fold.tasks.all()))
    }

    @Test
    fun bothDevicesOfflineMakeOneKitTaskWithOneList() {
        eaFold.addKit(event())
        eaMac.addKit(event())
        sync()
        val kits = fold.tasks.all().filter { FootballRules.isKitTask(it) }
        assertEquals(1, kits.size)
        assertEquals(FootballRules.DEFAULT_KIT, kits.single().checklist.map { it.text })
        assertEquals(FootballRules.DEFAULT_KIT, assertNotNull(mac.tasks.get(kits.single().id)).checklist.map { it.text })
    }

    @Test
    fun deletedAndAddedAgainItComesBackWithTheListUnticked() {
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        fold.tasks.get(id)!!.checklist.forEach { fold.tasks.setChecklistItemChecked(it.id, true) }
        fold.tasks.delete(id)
        assertNull(EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
        assertTrue(EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).canKit)

        assertEquals(id, eaFold.addKit(e))
        val back = assertNotNull(fold.tasks.get(id))
        assertEquals(FootballRules.DEFAULT_KIT, back.checklist.map { it.text })
        assertTrue(back.checklist.none { it.checked })
        assertEquals(at(thu + 1, 19), back.remindAtMs)
    }

    // ---- Slice 2: the travel time Meka set for each ground ----

    @Test
    fun aGroundsKeyIgnoresCaseAndPunctuationAndALinkIsNoGround() {
        assertEquals("arlesey town fc hitchin rd", FootballRules.venueKey("  Arlesey Town FC,  Hitchin Rd. "))
        assertEquals(FootballRules.venueKey("arlesey town fc hitchin rd"), FootballRules.venueKey("Arlesey Town FC, Hitchin Rd"))
        assertEquals("camp nou", FootballRules.venueKey("Camp Nou!"))
        assertNull(FootballRules.venueKey("https://meet.google.com/abc"))
        assertNull(FootballRules.venueKey("  "))
        assertNull(FootballRules.venueKey(null))
        assertEquals(FootballRules.venueId("bury field"), FootballRules.venueId("bury field"))
    }

    @Test
    fun settingLeaveByOnAFixtureRemembersTheGroundAndTheNextFixtureThereOffersIt() {
        val first = event()
        eaFold.setLeaveBy(first.id, 25)
        assertTrue(eaFold.rememberVenue(first))
        assertFalse(eaFold.rememberVenue(first)) // nothing changed: nothing written
        val next = event(id = "ev2", title = "BUFC U9s v Potton", start = at(thu + 9, 10))
        val offer = assertNotNull(FootballRules.leaveOffer(next, eaFold.marks(), world.clock.nowMs, cal))
        assertEquals(25, offer.travelMin)
        assertFalse(offer.rings)
        assertEquals("Leave by 09:35 · as last time", offer.label)
        assertEquals("Leave by 09:35 · 25 min away", offer.line)
        // The detail carries it, and the first fixture (which has its own) offers nothing.
        val d = EventDetails.build(next, world.clock.nowMs, cal, eaFold.marks())
        assertEquals("Leave by 09:35 · as last time", d.leaveOfferLabel)
        assertEquals(25, d.leaveOfferMin)
        assertNull(FootballRules.leaveOffer(first, eaFold.marks(), world.clock.nowMs, cal))

        // The alarm switch is remembered too, and the Mac sees the ground after a sync.
        eaFold.setLeaveAlarm(first.id, true)
        assertTrue(eaFold.rememberVenue(first))
        sync()
        val onMac = assertNotNull(FootballRules.leaveOffer(next, eaMac.marks(), world.clock.nowMs, cal))
        assertTrue(onMac.rings)
        assertEquals("Leave by 09:35 · 25 min away · alarm", onMac.line)
    }

    @Test
    fun noOfferForAnotherGroundAPlainEventAnAllDayOneOrWhenLeavingHasGone() {
        val first = event()
        eaFold.setLeaveBy(first.id, 30)
        eaFold.rememberVenue(first)
        val marks = eaFold.marks()
        val now = world.clock.nowMs
        // Another ground.
        assertNull(FootballRules.leaveOffer(event(id = "a", start = at(thu + 9, 10)).copy(location = "Potton Rec"), marks, now, cal))
        // Not a club fixture: the dentist at Bury Field isn't remembered or offered.
        assertNull(FootballRules.leaveOffer(event(id = "b", title = "Dentist", start = at(thu + 9, 10)), marks, now, cal))
        assertFalse(eaFold.rememberVenue(event(id = "b", title = "Dentist")))
        // All day.
        assertNull(FootballRules.leaveOffer(event(id = "c", allDay = true, start = cal.toEpochMs(thu + 9, 0)), marks, now, cal))
        // Leaving would already be gone (kick-off in 20 min, 30 min away).
        assertNull(FootballRules.leaveOffer(event(id = "d", start = now + 20 * 60_000L), marks, now, cal))
        // A fixture with no travel time remembers nothing.
        assertFalse(eaFold.rememberVenue(event(id = "e")))
    }

    @Test
    fun u7FixturesAreRexsAndU10sLogansInTheKitTheLateDraftAndTheResultPrompt() {
        assertEquals(7, FootballRules.ageGroup("BUFC U7s v Arlesey"))
        assertEquals(10, FootballRules.ageGroup("SJFC Under-10s training"))
        assertEquals(10, FootballRules.ageGroup("u10 v Potton"))
        assertNull(FootballRules.ageGroup("BUFC v Arlesey"))
        assertEquals("Rex", FootballRules.child(event(title = "BUFC U7s v Arlesey")))
        assertEquals("Logan", FootballRules.child(event(title = "BUFC U10s v Potton")))
        assertNull(FootballRules.child(event()))
        assertNull(FootballRules.child(event(title = "U7s parents' evening", calendarName = "School")))
        assertEquals("under-sevens", FootballRules.spokenAgeGroup(7))
        assertEquals("under-sixes", FootballRules.spokenAgeGroup(6))
        assertEquals("under-twelves", FootballRules.spokenAgeGroup(12))
        assertEquals("Rex's under-sevens play Arlesey", FootballRules.spokenFixture("BUFC U7s v Arlesey"))
        assertEquals("Biggleswade United under-nines play Arlesey", FootballRules.spokenFixture("BUFC U9s v Arlesey"))
        assertEquals("Biggleswade United versus Arlesey", FootballRules.spokenFixture("BUFC v Arlesey"))
        assertNull(FootballRules.spokenFixture("Standup"))
        assertEquals("ten o'clock", FootballRules.spokenClock("10:00"))
        assertEquals("half past ten", FootballRules.spokenClock("10:30"))
        assertEquals("quarter past nine", FootballRules.spokenClock("09:15"))
        assertEquals("quarter to one", FootballRules.spokenClock("12:45"))
        assertEquals("six twenty", FootballRules.spokenClock("18:20"))
        assertEquals("nine oh five", FootballRules.spokenClock("09:05"))
        assertEquals("ten thirty-five", FootballRules.spokenClock("10:35"))
        assertNull(FootballRules.spokenClock("All day"))

        val rex = event(id = "r", title = "BUFC U7s v Arlesey")
        assertEquals("Pack Rex's kit for BUFC U7s v Arlesey", FootballRules.title(rex))
        assertEquals("Pack the kit for BUFC U9s v Arlesey", FootballRules.title(event()))
        assertEquals(
            "Hi, sorry, Rex and I are running about 10 min late for BUFC U7s v Arlesey. Should be there by 10:10.",
            FootballRules.lateDrafts(rex, at(thu + 2, 8), cal)[1].text,
        )
        val prompt = FootballRules.notices(listOf(rex), eaFold.marks(), at(thu + 2, 11, 20), cal).single()
        assertEquals("How did Rex's match go?", prompt.title)
        assertTrue(prompt.text.startsWith("BUFC U7s v Arlesey · "), prompt.text)
    }

    @Test
    fun runningLateIsDraftedAroundKickOffForMekaToSendHimself() {
        val e = event(start = at(thu + 2, 10))
        // Too early: nothing, nor on an event that isn't a club fixture.
        assertTrue(FootballRules.lateDrafts(e, at(thu + 2, 7, 59), cal).isEmpty())
        assertTrue(FootballRules.lateDrafts(event(title = "Dentist"), at(thu + 2, 9, 30), cal).isEmpty())
        // From two hours before kick-off: by kick-off plus the minutes.
        val before = FootballRules.lateDrafts(e, at(thu + 2, 8), cal)
        assertEquals(listOf(5, 10, 15, 20), before.map { it.minutes })
        assertEquals("10 min", before[1].label)
        assertEquals("Hi, sorry, running about 10 min late for BUFC U9s v Arlesey. Should be there by 10:10.", before[1].text)
        // After kick-off: now plus the minutes, rounded up to five.
        val after = FootballRules.lateDrafts(e, at(thu + 2, 10, 7), cal)
        assertEquals("Hi, sorry, running about 5 min late for BUFC U9s v Arlesey. Should be there by 10:15.", after[0].text)
        // Half an hour after kick-off it's gone.
        assertTrue(FootballRules.lateDrafts(e, at(thu + 2, 10, 31), cal).isEmpty())
        // The detail carries them.
        assertEquals(4, EventDetails.build(e, at(thu + 2, 9), cal, eaFold.marks()).lateDrafts.size)
        assertTrue(EventDetails.build(e, at(thu, 12), cal, eaFold.marks()).lateDrafts.isEmpty())
        // A long title is cut at a word.
        val long = FootballRules.lateDrafts(event(title = "BUFC U9s v Arlesey Town Youth in the county cup quarter final replay"), at(thu + 2, 9), cal)
        assertTrue(long[0].text.contains("for BUFC U9s v Arlesey Town Youth in the county cup quarter…. Should"), long[0].text)
    }

    @Test
    fun theResultIsOfferedAfterTheMatchKeptOnTheFixtureAndSyncedToTheMac() {
        val e = event(start = at(thu, 10)) // 10:00–11:00 today
        // Before the end: nothing to record.
        assertFalse(FootballRules.canRecord(e, null, at(thu, 10, 59), cal))
        assertFalse(EventDetails.build(e, at(thu, 10, 30), cal, eaFold.marks()).canResult)
        // Once over, for a week.
        assertTrue(FootballRules.canRecord(e, null, at(thu, 11), cal))
        assertTrue(FootballRules.canRecord(e, null, at(thu + 6, 23), cal))
        assertFalse(FootballRules.canRecord(e, null, at(thu + 7, 11), cal))
        // Never on an event that isn't a club fixture.
        assertFalse(FootballRules.canRecord(event(title = "Dentist", start = at(thu, 10)), null, at(thu, 12), cal))

        val r = assertNotNull(FootballRules.result(e, 3, 1, "  Leo 2,   Sam ", "Great save in the\n\n\n\nsecond half ", at(thu, 12)))
        assertEquals("Leo 2, Sam", r.scorers)
        assertEquals("Great save in the\n\nsecond half", r.note)
        assertNull(eaFold.setResult("ev1", r))
        val d = EventDetails.build(e, at(thu, 12), cal, eaFold.marks())
        assertTrue(d.canResult)
        assertTrue(d.resultScore)
        assertEquals("Won 3–1 · Leo 2, Sam", d.resultLine)
        assertEquals("Great save in the\n\nsecond half", d.resultNote)
        assertEquals(3, d.result?.forOrNone)
        assertEquals("Saved · Won 3–1 · Leo 2, Sam", FootballRules.savedLine(r))
        // Kept for good: still shown (and changeable) after the week.
        assertTrue(EventDetails.build(e, at(thu + 30, 12), cal, eaFold.marks()).canResult)

        // On the Mac after a sync; a change there wins (the latest), and the old one comes back with its undo.
        sync()
        assertEquals("Won 3–1 · Leo 2, Sam", FootballRules.resultLine(eaMac.marks().results["ev1"]))
        val draw = assertNotNull(FootballRules.result(e, 2, 2, "", "", at(thu, 13)))
        val before = eaMac.setResult("ev1", draw)
        assertEquals(r.copy(atMs = before!!.atMs), before)
        sync()
        assertEquals("Drew 2–2", FootballRules.resultLine(eaFold.marks().results["ev1"]))
        assertNull(eaFold.marks().results["ev1"]?.note)
        eaFold.setResult("ev1", before)
        assertEquals("Won 3–1 · Leo 2, Sam", FootballRules.resultLine(eaFold.marks().results["ev1"]))
        // Saving nothing clears it.
        assertNull(FootballRules.result(e, -1, -1, " ", "\n", at(thu, 14)))
        eaFold.setResult("ev1", null)
        assertNull(eaFold.marks().results["ev1"])
        assertEquals("Result cleared", FootballRules.savedLine(null))
    }

    @Test
    fun aScoreIsBothSidesTrainingKeepsANoteOnlyAndTheWordsReadNaturally() {
        val e = event(start = at(thu, 10))
        // One side missing: no score, the note stays.
        val noScore = assertNotNull(FootballRules.result(e, 2, -1, "", "Rained off at half time", at(thu, 12)))
        assertFalse(noScore.hasScore)
        assertNull(FootballRules.resultLine(noScore))
        assertEquals("Saved the note", FootballRules.savedLine(noScore))
        assertEquals("Lost 0–1", FootballRules.scoreLabel(FootballRules.result(e, 0, 1, "", "", 0)!!))
        assertEquals("Scorers · Leo", FootballRules.resultLine(FootballRules.result(e, -1, -1, "Leo", "", 0)))
        assertNull(FootballRules.result(e, 100, 1, "", "", 0))
        // Training: a note, never a score or scorers.
        val training = event(title = "SJFC training", start = at(thu, 10))
        assertTrue(FootballRules.isTraining(training))
        assertFalse(EventDetails.build(training, at(thu, 12), cal, eaFold.marks()).resultScore)
        val t = assertNotNull(FootballRules.result(training, 3, 1, "Leo", "Worked on passing", 0))
        assertFalse(t.hasScore)
        assertNull(t.scorers)
        assertEquals("Worked on passing", t.note)
        assertFalse(FootballRules.isTraining(e))
        // An all-day tournament is over at 17:00 on its first day.
        val cup = event(title = "SJFC cup day", allDay = true, start = CivilDate.toEpochDay(2026, 10, 10) * CivilDate.DAY_MS)
        assertEquals(at(thu + 2, 17), FootballRules.matchEndMs(cup, cal))
    }

    @Test
    fun aPromptAsksHowItWentAfterAMatchUntilSomethingIsKept() {
        val e = event(start = at(thu, 10))
        val n = FootballRules.notices(listOf(e), eaFold.marks(), at(thu, 9), cal).single()
        assertEquals("How did BUFC U9s v Arlesey go?", n.title)
        assertEquals(at(thu, 11, 15), n.atMs)
        assertEquals(at(thu, 23), n.expiresAtMs)
        assertEquals(NoticeSource.EVENT_REMINDER, n.source)
        // It goes once stale, once something is kept, for training, a hidden fixture or anything else.
        assertTrue(FootballRules.notices(listOf(e), eaFold.marks(), at(thu, 23), cal).isEmpty())
        assertTrue(FootballRules.notices(listOf(event(title = "SJFC training", start = at(thu, 10))), eaFold.marks(), at(thu, 12), cal).isEmpty())
        assertTrue(FootballRules.notices(listOf(event(title = "Dentist", start = at(thu, 10))), eaFold.marks(), at(thu, 12), cal).isEmpty())
        eaFold.hide("ev1")
        assertTrue(FootballRules.notices(listOf(e), eaFold.marks(), at(thu, 12), cal).isEmpty())
        eaFold.show("ev1")
        eaFold.setResult("ev1", FootballRules.result(e, 1, 0, "", "", at(thu, 12)))
        assertTrue(FootballRules.notices(listOf(e), eaFold.marks(), at(thu, 12), cal).isEmpty())
    }
}
