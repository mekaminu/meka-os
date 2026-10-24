package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 2: the Board view on both apps — drag a card to another day or within one, with Undo, synced. */
class WeekBoardFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private val service = SyncService(InMemoryServerOpStore())

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = FaultyTransport(service),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aCardDraggedAboveAnotherAndToTomorrowSyncsToTheMacAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        now += 60_000L
        val gate = fold.addTask("Fix the gate")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        assertEquals(listOf(mot, gate), fold.weekBoard.value.day(today)!!.cards.map { it.id })

        assertEquals("Moved “Fix the gate” up", fold.moveOnBoard(gate, today, 0))
        assertEquals(listOf(gate, mot), fold.weekBoard.value.day(today)!!.cards.map { it.id })

        assertEquals("Moved “Book MOT” to Tomorrow", fold.moveOnBoard(mot, today + 1, 0))
        assertEquals(listOf(mot), fold.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        assertTrue(fold.today.value.yourDay.none { it.id == mot } && fold.today.value.upNext?.id != mot)

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(gate), mac.weekBoard.value.day(today)!!.cards.map { it.id })
        assertEquals(listOf(mot), mac.weekBoard.value.day(today + 1)!!.cards.map { it.id })

        assertTrue(fold.undoBoardMove())
        assertEquals(setOf(gate, mot), fold.weekBoard.value.day(today)!!.cards.map { it.id }.toSet())
        assertFalse(fold.undoBoardMove())
    }

    @Test
    fun tiredLightensTomorrowWithOneUndoAndTheFeelingStaysOnThisDevice() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val tomorrow = today + 1
        val ids = listOf("Book MOT", "Fix the gate", "Order new bins").map { fold.addOnBoard(it, tomorrow)!! }
        assertNull(fold.feelingToday())

        val offer = fold.setFeeling(os.meka.core.domain.Feeling.TIRED)
        assertEquals(2, offer.moves.size)
        assertEquals("Lighten tomorrow", offer.lightenLabel)
        assertEquals(os.meka.core.domain.Feeling.TIRED, fold.feelingToday())

        val line = fold.lightenTomorrow(os.meka.core.domain.Feeling.TIRED)!!
        assertTrue(line.startsWith("Tomorrow's lighter · "), line)
        assertEquals(1, fold.weekBoard.value.day(tomorrow)!!.openCards.size)
        fold.syncNow(); mac.syncNow()
        assertEquals(1, mac.weekBoard.value.day(tomorrow)!!.openCards.size)
        // Never synced: the Mac doesn't know how he felt.
        assertNull(mac.feelingToday())

        assertTrue(fold.undoLighten())
        assertEquals(ids.toSet(), fold.weekBoard.value.day(tomorrow)!!.openCards.map { it.id }.toSet())
        assertFalse(fold.undoLighten())
        assertTrue(fold.setFeeling(os.meka.core.domain.Feeling.GOOD).moves.isEmpty())
    }

    @Test
    fun shatteredToAdaKeepsTheFeelingAndStretchedHoldsTheAlertsThatCanWait() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val tomorrow = fold.weekBoard.value.todayEpochDay + 1
        listOf("Book MOT", "Fix the gate").forEach { fold.addOnBoard(it, tomorrow)!! }

        val a = (fold.askMeka("I'm shattered") as os.meka.core.domain.AskOutcome.Answered).answer
        assertTrue(a.text.startsWith("Sorry to hear it. Let's go easy tomorrow: move "), a.text)
        assertEquals(os.meka.core.domain.FeelingAskRules.link, a.help)
        assertTrue(a.cards.isEmpty())
        assertEquals(os.meka.core.domain.Feeling.TIRED, fold.feelingToday())
        // The words alone move nothing.
        assertEquals(2, fold.weekBoard.value.day(tomorrow)!!.openCards.size)

        val s = (fold.askMeka("I've got too much on") as os.meka.core.domain.AskOutcome.Answered).answer
        assertTrue(s.text.contains("I can hold mail and call alerts that can wait till tomorrow"), s.text)
        val offer = fold.feelingOffer(os.meka.core.domain.Feeling.STRETCHED)
        assertTrue(offer.holdLabel!!.startsWith("Hold alerts till tomorrow "))
        assertFalse(fold.alertsHeldNow())
        assertNull(fold.alertHoldLine())

        assertTrue(fold.holdAlerts().startsWith("Mail and call alerts held till tomorrow "))
        assertTrue(fold.alertsHeldNow())
        assertNull(fold.feelingOffer(os.meka.core.domain.Feeling.STRETCHED).holdLabel)
        // This device only.
        fold.syncNow(); mac.syncNow()
        assertNull(mac.alertHoldLine())
        assertFalse(mac.alertsHeldNow())

        now += os.meka.core.domain.AlertHoldRules.HOLD_MS
        assertNull(fold.alertHoldLine())
        assertFalse(fold.alertsHeldNow())
        now -= os.meka.core.domain.AlertHoldRules.HOLD_MS
        fold.holdAlerts()
        assertEquals(os.meka.core.domain.AlertHoldRules.RESUMED, fold.resumeAlerts())
        assertFalse(fold.alertsHeldNow())
    }

    @Test
    fun todayFollowsTheOrderSetOnTheBoardOnBothDevices() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        now += 60_000L
        val gate = fold.addTask("Fix the gate")
        now += 60_000L
        val bins = fold.addTask("Order new bins")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        assertEquals(mot, fold.today.value.upNext?.id)

        fold.moveOnBoard(bins, today, 0)
        assertEquals(bins, fold.today.value.upNext?.id)
        assertEquals(listOf(mot, gate), fold.today.value.yourDay.map { it.id })

        fold.syncNow(); mac.syncNow()
        assertEquals(bins, mac.today.value.upNext?.id)
        assertEquals(listOf(mot, gate), mac.today.value.yourDay.map { it.id })
    }

    @Test
    fun anEstimateSetOnACardCountsOnItsDaySyncsAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val id = fold.addOnBoard("Book MOT", today + 2)!!
        assertEquals("“Book MOT” · about 30m", fold.setEstimate(id, 30))
        val day = fold.weekBoard.value.day(today + 2)!!
        assertEquals("30m", day.cards.single().estimate)
        assertEquals(30, day.capacity!!.plannedMin)

        fold.syncNow(); mac.syncNow()
        assertEquals("30m", mac.weekBoard.value.day(today + 2)!!.cards.single().estimate)

        assertEquals("“Book MOT” · about 1h", fold.setEstimate(id, 60))
        assertTrue(fold.undoEstimate())
        assertEquals("30m", fold.weekBoard.value.day(today + 2)!!.cards.single().estimate)
        assertFalse(fold.undoEstimate())
        assertEquals("“Book MOT” · no estimate", fold.setEstimate(id, null))
        assertNull(fold.weekBoard.value.day(today + 2)!!.cards.single().estimate)
    }

    @Test
    fun theKeyboardNudgesAndQuickAddLandOnTheirDay() = runTest {
        val c = core("mac")
        c.tick()
        val today = c.weekBoard.value.todayEpochDay
        val id = c.addOnBoard("Order the cake", today + 2)!!
        assertEquals(listOf(id), c.weekBoard.value.day(today + 2)!!.cards.map { it.id })
        assertEquals("Moved “Order the cake” to Tomorrow", c.nudgeOnBoard(id, -1, 0))
        assertEquals(listOf(id), c.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        c.nudgeOnBoard(id, -1, 0)
        assertNull(c.nudgeOnBoard(id, -1, 0), "never before today")
        assertTrue(c.weekBoard.value.day(today)!!.cards.any { it.id == id })
    }

    @Test
    fun aCardParkedInTheWeekLeavesTodaySyncsAndGoesToSomedayAndBack() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        fold.tick()
        val board = fold.weekBoard.value
        val today = board.todayEpochDay
        val monday = board.weeks[0].startEpochDay
        assertEquals(mot, fold.today.value.upNext?.id)

        assertEquals("Moved “Book MOT” to This week", fold.parkOnBoard(mot, monday, 0))
        assertEquals(listOf(mot), fold.weekBoard.value.weeks[0].lane.map { it.id })
        assertTrue(fold.weekBoard.value.day(today)!!.cards.none { it.id == mot })
        assertTrue(fold.today.value.upNext?.id != mot && fold.today.value.yourDay.none { it.id == mot })
        assertNull(fold.parkOnBoard(mot, monday, 0), "already there")

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(mot), mac.weekBoard.value.weeks[0].lane.map { it.id })

        assertEquals("Moved “Book MOT” to Someday", mac.somedayOnBoard(mot))
        assertEquals(listOf(mot), mac.weekBoard.value.someday.map { it.id })
        assertTrue(mac.weekBoard.value.weeks[0].lane.isEmpty())
        assertNull(mac.somedayOnBoard(mot), "already on Someday")

        assertEquals("Moved “Book MOT” to Tomorrow", mac.moveOnBoard(mot, today + 1, 0))
        assertEquals(listOf(mot), mac.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        assertTrue(mac.weekBoard.value.someday.isEmpty())
        assertTrue(mac.undoBoardMove())
        assertEquals(listOf(mot), mac.weekBoard.value.someday.map { it.id })
    }

    @Test
    fun anAreaPickedOnTheFoldSyncsAndADropOnHouseFilesItUnderSomeday() = runTest {
        val fold = core("android"); val mac = core("mac")
        val boiler = fold.addTask("Boiler service")
        val rates = fold.addTask("Update rates")
        fold.tick()
        val area = os.meka.core.domain.BoardAreaRules
        fun card(c: MekaCore, id: String) = c.weekBoard.value.find(id)!!.card
        assertEquals(os.meka.core.domain.BoardArea.HOUSE, area.of(card(fold, boiler)), "Navilife's guess")

        assertEquals("“Boiler service” · Work", fold.areaOnBoard(boiler, os.meka.core.domain.BoardArea.WORK))
        assertNull(fold.areaOnBoard(boiler, os.meka.core.domain.BoardArea.WORK), "already Work")
        fold.syncNow(); mac.syncNow()
        assertEquals(os.meka.core.domain.BoardArea.WORK, area.of(card(mac, boiler)))
        assertTrue(fold.undoBoardArea())
        assertEquals(os.meka.core.domain.BoardArea.HOUSE, area.of(card(fold, boiler)))
        assertFalse(fold.undoBoardArea())
        assertEquals("“Boiler service” · no area", fold.areaOnBoard(boiler, null))
        assertNull(area.of(card(fold, boiler)))

        // Dropped on Side hustle off today: on Someday, filed under Side hustle; Undo puts it back on today.
        val today = fold.weekBoard.value.todayEpochDay
        assertEquals("Moved “Update rates” to Side hustle", fold.listOnBoard(rates, os.meka.core.domain.BoardArea.SIDE_HUSTLE))
        val lists = os.meka.core.domain.BoardWeekLayoutRules.lists(fold.weekBoard.value.weeks[0], fold.weekBoard.value)
        assertEquals(listOf(rates), lists.single { it.area == os.meka.core.domain.BoardArea.SIDE_HUSTLE }.cards.map { it.id })
        assertNull(fold.listOnBoard(rates, os.meka.core.domain.BoardArea.SIDE_HUSTLE), "already there")
        assertNull(fold.listOnBoard(rates, os.meka.core.domain.BoardArea.MONEY), "Money has no list")
        assertTrue(fold.undoBoardList())
        assertTrue(fold.weekBoard.value.day(today)!!.cards.any { it.id == rates })
        assertNull(fold.weekBoard.value.find(rates)!!.card.task.area)
        assertFalse(fold.undoBoardList())

        // From Someday's list onto House, then back onto Someday (House comes off).
        fold.somedayOnBoard(rates)
        assertEquals("Moved “Update rates” to House", fold.listOnBoard(rates, os.meka.core.domain.BoardArea.HOUSE))
        assertEquals("Moved “Update rates” to Someday", fold.listOnBoard(rates, null))
        assertEquals(area.NONE, fold.weekBoard.value.find(rates)!!.card.task.area)
        assertNull(fold.listOnBoard(rates, null), "already on Someday")
    }

    private inner class EditingTransport(
        private val list: List<ConnectedAccount>,
        private val inner: FaultyTransport = FaultyTransport(service),
    ) : os.meka.core.sync.SyncTransport by inner, AccountsApi {
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = list
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = list
    }

    @Test
    fun blockTimeAddsTheTasksBlockToGoogleOnHisTapAndUndoTakesItBack() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val id = fold.addOnBoard("Book MOT", today + 2)!!

        // No calendar allows editing yet: the starts are shown, nothing can be added.
        val first = fold.blockOffer(id)!!
        assertFalse(first.canBlock)
        assertNull(fold.blockOnBoard(id, first.slots.first().startMs))

        fold.refreshCalendarAccounts()
        val offer = fold.blockOffer(id)!!
        assertEquals("Block time · 30m", offer.heading)
        assertEquals(listOf("09:00–09:30", "10:00–10:30", "11:00–11:30", "12:00–12:30"), offer.slots.map { it.label })
        assertEquals("Adds it to Google as busy time when you tap a start", offer.line)
        assertTrue(fold.calendarEditLines.value.isEmpty(), "nothing before his tap")

        assertEquals("Blocked “Book MOT” · Sat 10 Oct 10:00–10:30 in Google", fold.blockOnBoard(id, offer.slots[1].startMs))
        val card = fold.weekBoard.value.day(today + 2)!!.cards.single()
        assertEquals("10:00 · 30m", card.meta)
        assertEquals(listOf("Adding “Book MOT” to Google"), fold.calendarEditLines.value.map { it.text })
        assertTrue(fold.weekBoard.value.day(today + 2)!!.chips.isEmpty(), "the task stands for its block")
        assertTrue(fold.blockOffer(id)!!.moves)
        assertEquals("Moves its block in Google", fold.blockOffer(id)!!.line)

        assertTrue(fold.undoBlock())
        assertTrue(fold.calendarEditLines.value.isEmpty())
        assertEquals("", fold.weekBoard.value.day(today + 2)!!.cards.single().meta)
        assertFalse(fold.undoBlock())
    }

    @Test
    fun aCardDroppedOnAnotherDaysFreeTimeIsMovedAndBlockedThereWithUndo() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val sat = today + 2
        val id = fold.addOnBoard("Book MOT", today + 1)!!
        val free = fold.weekBoard.value.day(sat)!!.freeTimes.first()
        assertEquals("09:00–21:00", free.label)

        // No calendar allows editing yet: it says so and nothing moves.
        val none = fold.dropOnFreeTime(id, sat, free.startMs, 0.5f)!!
        assertFalse(none.done)
        assertEquals(os.meka.core.domain.BoardBlockRules.NO_ACCOUNT, none.line)
        assertEquals(listOf(id), fold.weekBoard.value.day(today + 1)!!.cards.map { it.id })

        fold.refreshCalendarAccounts()
        val drop = fold.dropOnFreeTime(id, sat, free.startMs, 0.5f)!!
        assertTrue(drop.done)
        assertEquals("Blocked “Book MOT” · Sat 10 Oct 14:45–15:15 in Google", drop.line)
        assertTrue(fold.weekBoard.value.day(today + 1)!!.cards.isEmpty())
        assertEquals("14:45 · 30m", fold.weekBoard.value.day(sat)!!.cards.single().meta)
        assertEquals(listOf("Adding “Book MOT” to Google"), fold.calendarEditLines.value.map { it.text })

        assertTrue(fold.undoBlock())
        assertTrue(fold.calendarEditLines.value.isEmpty())
        assertEquals(listOf(id), fold.weekBoard.value.day(today + 1)!!.cards.map { it.id })
        assertTrue(fold.weekBoard.value.day(sat)!!.cards.isEmpty())

        // From the week's lane: blocked on the day, and Undo parks it again.
        val monday = fold.weekBoard.value.weeks[0].startEpochDay
        fold.parkOnBoard(id, monday, 0)
        assertTrue(fold.dropOnFreeTime(id, sat, free.startMs, 0f)!!.done)
        assertEquals("09:00 · 30m", fold.weekBoard.value.day(sat)!!.cards.single().meta)
        assertTrue(fold.weekBoard.value.weeks[0].lane.isEmpty())
        assertTrue(fold.undoBlock())
        assertEquals(listOf(id), fold.weekBoard.value.weeks[0].lane.map { it.id })

        // Not a free time the day has, or a day gone by: nothing.
        assertNull(fold.dropOnFreeTime(id, sat, free.startMs + 60_000L, 0f))
        assertNull(fold.dropOnFreeTime(id, today - 1, free.startMs, 0f))
    }

    @Test
    fun goalsPickedOnTheBoardCountTheirTaggedTasksOnBothDevicesWithUndo() = runTest {
        val fold = core("android"); val mac = core("mac")
        val run = fold.addGoal("Run 5k", null, os.meka.core.domain.GoalHorizon.MONTH)
        val loft = fold.addGoal("Tidy the loft", null, os.meka.core.domain.GoalHorizon.WEEK)
        val shoes = fold.addTask("Buy running shoes")
        fold.tick()
        val week = fold.weekBoard.value.weeks[0].startEpochDay
        // This week's This-week goal shows with no pick, as on Today.
        assertEquals(listOf(loft), fold.weekBoard.value.weeks[0].goals.map { it.goalId })
        assertEquals(listOf(run), fold.boardGoalChoices(week).map { it.goalId })

        assertEquals("“Run 5k” is one of this week's goals", fold.pickBoardGoal(run, week))
        assertEquals(listOf(loft, run), fold.weekBoard.value.weeks[0].goals.map { it.goalId }, "the This-week goal is kept")
        assertTrue(fold.weekBoard.value.weeks[0].goals.all { it.picked })
        assertNull(fold.pickBoardGoal(run, week), "already there")

        assertEquals("“Buy running shoes” · for “Run 5k”", fold.tagOnBoard(shoes, run))
        val g = fold.weekBoard.value.weeks[0].goals.first { it.goalId == run }
        assertEquals("0 of 1 done", g.line)
        assertEquals("◎ Run 5k", fold.weekBoard.value.find(shoes)!!.card.goalTag)
        assertNull(fold.tagOnBoard(shoes, run), "no change")

        fold.complete(shoes)
        assertEquals("1 of 1 done", fold.weekBoard.value.weeks[0].goals.first { it.goalId == run }.line)

        fold.syncNow(); mac.syncNow()
        assertEquals("1 of 1 done", mac.weekBoard.value.weeks[0].goals.first { it.goalId == run }.line)

        // A new goal typed on next week's lane, and Undo takes it away again.
        val next = mac.weekBoard.value.weeks[1].startEpochDay
        assertEquals("“Garden ready” is one of next week's goals", mac.addBoardGoal("Garden ready", next))
        assertEquals(listOf("Garden ready"), mac.weekBoard.value.weeks[1].goals.map { it.title })
        assertTrue(mac.undoBoardGoal())
        assertTrue(mac.weekBoard.value.weeks[1].goals.isEmpty())
        assertTrue(mac.goalsView.value.goals.none { it.title == "Garden ready" })

        // Off this week, and back with Undo; the tag stays.
        assertEquals("“Run 5k” is off this week's goals", mac.unpickBoardGoal(run, week))
        assertEquals(listOf(loft), mac.weekBoard.value.weeks[0].goals.map { it.goalId })
        assertTrue(mac.undoBoardGoal())
        assertEquals(listOf(loft, run), mac.weekBoard.value.weeks[0].goals.map { it.goalId })
        assertFalse(mac.undoBoardGoal())

        val gate = mac.addTask("Fix the gate")
        assertEquals("“Fix the gate” · for “Tidy the loft”", mac.tagOnBoard(gate, loft))
        assertTrue(mac.undoTagOnBoard())
        assertNull(mac.weekBoard.value.find(gate)!!.card.goalTag)
        assertFalse(mac.undoTagOnBoard())
    }

    /** Item (10): a stacking-up evening on the Board, Today, the brief and the hello; its fix moves one with Undo. */
    @Test
    fun aStackingUpEveningShowsEverywhereAndItsFixMovesOneWithUndo() = runTest {
        val fold = core("android")
        fold.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Alex Smith")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val sat = today + 2
        val ids = listOf("Call the garage" to 19, "Wrap the present" to 20, "Pack the kit" to 21).map { (title, hour) ->
            fold.addOnBoard(title, sat)!!.also { fold.setWhen(it, sat, hour * 60) }
        }
        fold.tick()
        val p = fold.weekBoard.value.day(sat)!!.pressure!!
        assertEquals("Saturday evening is stacking up: Call the garage, Wrap the present and Pack the kit", p.line)
        assertEquals(p, fold.today.value.pressure)
        assertEquals(p.line, fold.briefView.value.pressureLine)
        val hello = fold.hello()
        assertTrue(hello.spoken.contains("Saturday evening is stacking up"), hello.spoken)

        val fix = p.fixes.single()
        assertEquals("Move “Pack the kit” to Sun", fix.label)
        assertEquals("Moved “Pack the kit” to Sun", fold.easePressure(fix.taskId, fix.toDay))
        assertNull(fold.weekBoard.value.day(sat)!!.pressure)
        assertNull(fold.today.value.pressure)
        assertEquals(listOf(ids[2]), fold.weekBoard.value.day(sat + 1)!!.cards.map { it.id })

        assertTrue(fold.undoBoardMove())
        assertEquals(p.line, fold.today.value.pressure?.line)
    }

    @Test
    fun aNeedsYouCardToADayLeavesTodayForThatDayAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        val tax = fold.addTask("Pay council tax")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        assertEquals("Tomorrow", fold.needsYouFeedDays().first().label)
        // A chip for today (left open past midnight) means tomorrow.
        assertEquals("Moved “Pay council tax” to tomorrow", fold.needsYouToDay(tax, today))
        assertEquals(listOf(tax), fold.weekBoard.value.day(today + 1)!!.openCards.map { it.id })
        assertTrue(fold.today.value.upNext?.id != tax && fold.today.value.yourDay.none { it.id == tax })
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(tax), mac.weekBoard.value.day(today + 1)!!.openCards.map { it.id })

        assertTrue(fold.undoBoardMove())
        assertEquals(tax, fold.today.value.upNext?.id)
        fold.complete(tax)
        assertEquals(os.meka.core.domain.BoardFeedRules.CANT_MOVE, fold.needsYouToDay(tax, today + 2))
        assertEquals(os.meka.core.domain.BoardFeedRules.CANT_MOVE, fold.needsYouToDay("nope", today + 2))
    }

    @Test
    fun aSchoolDateAndABirthdayGoToADayOnceEachAndUndo() = runTest {
        // Week board item (5) part 4: To a day… in School and Gifts.
        val R = os.meka.core.domain.BoardFeedRules
        val fold = core("android"); val mac = core("mac")
        fold.addSchool("Trip to the zoo 10 Oct")
        fold.addOccasion("Sam's birthday 12 Oct")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        val trip = fold.schoolView.value.dates.single()
        assertEquals(today + 2, trip.day)
        // Today up to the trip's Saturday.
        assertEquals(listOf("Today", "Tomorrow", "Sat"), fold.dateFeedDays(today + 2).map { it.label })
        val title = R.schoolTitle(trip.title, "", today + 2)
        assertEquals(R.dateLine(title, today + 1, today), fold.schoolDateToDay(trip.id, today + 1))
        val card = fold.weekBoard.value.day(today + 1)!!.openCards.single()
        assertEquals(title, card.task.title)
        // A second tap, or the Mac's after a sync, makes no other.
        assertEquals(R.DATE_ALREADY, fold.schoolDateToDay(trip.id, today + 2))
        fold.syncNow(); mac.syncNow()
        assertEquals(R.DATE_ALREADY, mac.schoolDateToDay(trip.id, today))
        assertEquals(listOf(card.id), mac.weekBoard.value.day(today + 1)!!.openCards.map { it.id })
        // Undo takes it off; another tap makes a fresh one, never after the date.
        assertTrue(fold.undoDateToDay())
        assertTrue(fold.weekBoard.value.day(today + 1)!!.openCards.isEmpty())
        assertEquals(R.dateLine(title, today + 2, today), fold.schoolDateToDay(trip.id, today + 6))
        assertEquals(listOf(title), fold.weekBoard.value.day(today + 2)!!.openCards.map { it.task.title })

        // A birthday: its gift as a task, the order-by day in its notes; nothing is ordered.
        val sam = fold.giftsView.value.rows.single()
        assertEquals(today + 4, sam.day)
        val gift = R.giftTitle(sam.title, today + 4)
        assertEquals(R.dateLine(gift, today + 1, today), fold.giftToDay(sam.id, today + 1))
        val g = fold.weekBoard.value.day(today + 1)!!.openCards.single()
        assertEquals(gift, g.task.title)
        assertTrue(g.task.notes.orEmpty().endsWith("From Gifts"))
        assertEquals(R.DATE_UNKNOWN, fold.giftToDay("nope", today + 1))
        assertEquals(R.DATE_UNKNOWN, fold.schoolDateToDay("nope", today + 1))
        assertFalse(mac.undoDateToDay())
    }

    @Test
    fun planYourWeekSpreadsWhatsLeftOnlyOnAcceptSyncsAndUndoes() = runTest {
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        val gate = fold.addTask("Fix the gate")
        fold.tick()
        val board = fold.weekBoard.value
        val today = board.todayEpochDay
        val monday = board.weeks[0].startEpochDay
        fold.parkOnBoard(mot, monday, 0)
        fold.parkOnBoard(gate, monday, 1)

        val plan = fold.planWeek()
        assertEquals("Plan this week", plan.title)
        assertEquals(setOf(mot, gate), plan.rows.map { it.taskId }.toSet())
        assertTrue(plan.rows.all { it.chosen != null && it.chosen!! >= today })
        // Nothing moves until he accepts.
        assertEquals(2, fold.weekBoard.value.weeks[0].lane.size)

        val chosen = plan.choose(gate, today + 1).choose(mot, null)
        assertEquals("Week planned · 1 task on 1 day", fold.acceptWeekPlan(chosen))
        assertEquals(listOf(gate), fold.weekBoard.value.day(today + 1)!!.openCards.map { it.id })
        assertEquals(listOf(mot), fold.weekBoard.value.weeks[0].lane.map { it.id })
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(gate), mac.weekBoard.value.day(today + 1)!!.openCards.map { it.id })

        assertTrue(fold.undoWeekPlan())
        assertEquals(setOf(mot, gate), fold.weekBoard.value.weeks[0].lane.map { it.id }.toSet())
        assertFalse(fold.undoWeekPlan())
        assertNull(fold.acceptWeekPlan(chosen.choose(gate, null)))
    }

    @Test
    fun sundaysPlanYourWeekCardOpensTheFlowAndLeavesEverywhereOncePlanned() = runTest {
        now = 1_792_953_000_000L // Sun 25 Oct 2026, 18:30 in London
        val fold = core("android"); val mac = core("mac")
        val mot = fold.addTask("Book MOT")
        fold.tick()
        val monday = fold.weekBoard.value.weeks[0].startEpochDay
        fold.parkOnBoard(mot, monday, 0)
        fold.tick()
        val card = fold.needsYouStack.value.cards.single { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK }
        assertEquals("Plan next week", card.title)
        assertEquals("Sunday evening · 1 task to place · about 5 minutes", card.why)
        fold.syncNow(); mac.syncNow(); mac.tick()
        assertTrue(mac.needsYouStack.value.cards.any { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK })

        // Typed, it's answered with the spread; nothing moves.
        val typed = (fold.askMeka("plan my week") as os.meka.core.domain.AskOutcome.Answered).answer
        assertTrue(typed.text.startsWith("Plan next week, "), typed.text)
        assertEquals(listOf(mot), fold.weekBoard.value.weeks[0].lane.map { it.id })

        // Accepted on the Fold: gone there and on the Mac; Undo brings it back.
        assertTrue(fold.acceptWeekPlan(fold.planWeek())!!.startsWith("Week planned · 1 task"))
        fold.tick()
        assertTrue(fold.needsYouStack.value.cards.none { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK })
        fold.syncNow(); mac.syncNow(); mac.tick()
        assertTrue(mac.needsYouStack.value.cards.none { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK })
        assertTrue(fold.undoWeekPlan())
        fold.tick()
        assertTrue(fold.needsYouStack.value.cards.any { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK })

        // Monday noon it has gone on its own.
        now += 17 * 3_600_000L + 31 * 60_000L
        fold.tick()
        assertTrue(fold.needsYouStack.value.cards.none { it.kind == os.meka.core.domain.DecisionKind.PLAN_WEEK })
    }


    @Test
    fun theTimelineShowsATaskPlannedAtATimeForItsEstimateOnBothApps() = runTest {
        val fold = core("android"); val mac = core("mac")
        val quote = fold.addTask("Send the quote")
        fold.tick()
        val today = fold.weekBoard.value.todayEpochDay
        fold.setWhen(quote, today, 19 * 60)
        fold.setEstimate(quote, 45)
        val tl = fold.boardTimeline(fold.weekBoard.value, today)!!
        assertEquals("Today", tl.title)
        assertEquals(listOf("Send the quote 19:00–19:45"), tl.blocks.map { "${it.title} ${it.label}" })
        assertEquals(17 * 60 + 15, tl.nowMinute)
        fold.syncNow(); mac.syncNow()
        val onMac = mac.boardTimeline(mac.weekBoard.value, today)!!
        assertEquals(tl.blocks.map { it.label }, onMac.blocks.map { it.label })
        // Tomorrow has nothing timed; a day off the board has no timeline.
        assertEquals(os.meka.core.domain.BoardTimelineRules.EMPTY, fold.boardTimeline(fold.weekBoard.value, today + 1)!!.emptyLine)
        assertNull(fold.boardTimeline(fold.weekBoard.value, today - 400))
    }
}
