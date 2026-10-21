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
}
