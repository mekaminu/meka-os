package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, slice 2e: Plan my day's blocks in your calendar. */
class PlanToCalendarTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val world = SyncWorld()
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(h: Int, m: Int = 0) = thu * day + h * hour + m * 60_000L

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun edits(d: os.meka.core.testing.Device) =
        CalendarEdits(d.replica, { world.clock.nowMs }, { "pl${d.name.take(1)}${n++}" }) { p, a -> p == "google" && a == "meka@gmail.com" }
    private val eFold = edits(fold)
    private val eMac = edits(mac)

    init { world.clock.nowMs = at(10) }

    private val now get() = world.clock.nowMs
    private val google = EditAccount("google", "meka@gmail.com")
    private val outlook = EditAccount("microsoft", "meka@outlook.com")
    private fun task(id: String, title: String) = Task(id, title, null, Lifecycle.ACTIVE, null, null, 30, 0, null, null, 0, null, false)
    private val report = DayPlanner.Placement(task("t1", "Write report"), at(11), at(11, 30))
    private val dentist = CalendarEvent("ev2", "Dentist", at(14), at(15), false, null, "google", "meka@gmail.com", "meka@gmail.com")

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun made(r: EventEditResult) = assertIs<EventEditResult.Made>(r).id

    private val serverClock = HlcClock("server", { world.clock.nowMs })
    private var serverOps = 0
    private fun answer(id: String, status: String) {
        val fields = mapOf<String, FieldValue>(EventEditFields.STATUS to status.fv(), EventEditFields.STATUS_AT to now.fv())
        for ((f, v) in fields) world.serverStore.append(
            Op("srvplan$id${serverOps++}", world.householdId, EntityTypes.EVENT_EDIT, id, f, v, serverClock.now(), emptyList(), "server"),
        )
    }

    @Test
    fun offByDefaultSyncedAndOnlyOfferedWhereAnAccountCanEdit() {
        val fs = PlanCalendar(fold.replica)
        assertFalse(fs.on())
        assertEquals(PlanCalendarSetting.OFF, PlanCalendarRules.setting(false, emptyList(), null))
        val off = PlanCalendarRules.setting(false, listOf(google), null)
        assertTrue(off.available)
        assertEquals("Also add the blocks to Google", off.label)
        assertEquals("Apply plans the tasks in MEKA only", off.line)
        assertNull(off.accountLabel)

        fs.set(true)
        sync()
        assertTrue(PlanCalendar(mac.replica).on())
        val on = PlanCalendarRules.setting(true, listOf(google), null)
        assertEquals("Google · meka@gmail.com", on.accountLabel)
        assertEquals("Apply adds each task's block to meka@gmail.com too, so the time shows as busy", on.line)
        // On, but no account can edit any more: nothing offered, nothing sent.
        assertFalse(PlanCalendarRules.setting(true, emptyList(), null).available)
    }

    @Test
    fun theBlocksGoWhereAddEventRemembersElseTheFirstAccount() {
        assertEquals(google, PlanCalendarRules.target(listOf(google, outlook), null))
        assertEquals(outlook, PlanCalendarRules.target(listOf(google, outlook), outlook.key))
        assertEquals(google, PlanCalendarRules.target(listOf(google), outlook.key)) // remembered one stopped editing
        assertNull(PlanCalendarRules.target(emptyList(), null))
    }

    @Test
    fun aBlockIsAnOrdinaryAddCarryingItsTaskAndDoesntBecomeAddEventsDefault() {
        val d = PlanCalendarRules.draft(report)
        assertEquals(EventDraft("Write report", at(11), at(11, 30), false, notes = "Planned with MEKA"), d)
        val id = made(eFold.add("google", "meka@gmail.com", d, forTask = "t1"))
        val edit = eFold.edit(id)!!
        assertEquals("t1", edit.forTask)
        assertEquals(EventEditKind.ADD, edit.kind)
        assertNull(AddEventRules.lastUsedKey(eFold.all()))
        assertEquals("Adding 1 block to Google", PlanCalendarRules.line(1, "google"))
        assertEquals("Adding 3 blocks to Outlook", PlanCalendarRules.line(3, "microsoft"))
        // Activity says where it came from.
        val item = ActivityRules.calendarEditItem(edit, now, LocalCalendar.UTC)!!
        assertTrue(item.why.startsWith("Plan my day · Apply"), item.why)
    }

    @Test
    fun theBlockIsNeverShownBesideItsTaskOnEitherDeviceWhileSendingOrOnceInGoogle() {
        val id = made(eFold.add("google", "meka@gmail.com", PlanCalendarRules.draft(report), forTask = "t1"))
        val mirror = listOf(dentist)
        // On its way: the provisional event is left out.
        val shown = PlanCalendarRules.withoutTaskBlocks(PendingEditRules.apply(mirror, eFold.all(), now), eFold.all())
        assertEquals(listOf("ev2"), shown.map { it.id })
        sync()
        world.clock.nowMs += 10_000
        answer(id, "DONE")
        sync()
        // Google has it: the mirrored event it became is left out too (same account, title and time), on the Mac as well.
        val inGoogle = CalendarEvent("g9", "Write report", at(11), at(11, 30), false, null, "google", "meka@gmail.com", "meka@gmail.com")
        val later = PlanCalendarRules.withoutTaskBlocks(PendingEditRules.apply(mirror + inGoogle, eMac.all(), now), eMac.all())
        assertEquals(listOf("ev2"), later.map { it.id })
        // An event of the same name at another time, or on another account, is Meka's own and stays.
        val other = inGoogle.copy(id = "g10", startAtMs = at(16), endAtMs = at(16, 30))
        assertEquals(listOf("ev2", "g10"), PlanCalendarRules.withoutTaskBlocks(mirror + other, eMac.all()).map { it.id })
    }

    @Test
    fun undoneBlocksHideNothingAndOrdinaryAddsStayShown() {
        val id = made(eFold.add("google", "meka@gmail.com", PlanCalendarRules.draft(report), forTask = "t1"))
        assertTrue(eFold.undo(id))
        val same = CalendarEvent("g9", "Write report", at(11), at(11, 30), false, null, "google", "meka@gmail.com", "meka@gmail.com")
        assertEquals(listOf("g9"), PlanCalendarRules.withoutTaskBlocks(listOf(same), eFold.all()).map { it.id })

        val add = made(eFold.add("google", "meka@gmail.com", EventDraft("Lunch", at(12), at(13), false)))
        val shown = PlanCalendarRules.withoutTaskBlocks(PendingEditRules.apply(emptyList(), eFold.all(), now), eFold.all())
        assertEquals(listOf(PendingEditRules.PROVISIONAL_PREFIX + add), shown.map { it.id })
    }

    // ---- Slice 2f: the block keeps in step with its task ----

    private val inGoogle = CalendarEvent("g9", "Write report", at(11), at(11, 30), false, null, "google", "meka@gmail.com", "meka@gmail.com")
    private fun planned(at: Long?, lifecycle: Lifecycle = Lifecycle.ACTIVE) = task("t1", "Write report").copy(scheduledAtMs = at, lifecycle = lifecycle)

    /** A block added for t1 and answered DONE, with Google's copy in the mirror. */
    private fun blockInGoogle(): String {
        val id = made(eFold.add("google", "meka@gmail.com", PlanCalendarRules.draft(report), forTask = "t1"))
        world.clock.nowMs += 10_000
        sync(); answer(id, "DONE"); sync()
        return id
    }
    private fun blockNow(mirror: List<CalendarEvent>) = PlanCalendarRules.blockOf("t1", PendingEditRules.apply(mirror, eFold.all(), now), eFold.all())

    @Test
    fun aMovedTaskMovesItsBlockKeepingItsLengthAndDoneLeavesIt() {
        assertNull(PlanCalendarRules.blockOf("t1", listOf(inGoogle), eFold.all()))
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(15)), null, true))
        blockInGoogle()
        val block = blockNow(listOf(inGoogle))!!
        assertEquals("g9", block.event?.id)
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(11)), block, true))
        assertEquals(BlockStep.Move(inGoogle, at(15)), PlanCalendarRules.follow(planned(at(15)), block, true))
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(15), Lifecycle.DONE), block, true))
        assertEquals(EventDraft("Write report", at(15), at(15, 30), false, notes = null), PlanCalendarRules.moved(inGoogle, at(15)))

        // The move is an ordinary change made for the task: Google's event is then laid over at 15:00 and still hidden.
        made(eFold.change(inGoogle, PlanCalendarRules.moved(inGoogle, at(15)), forTask = "t1"))
        val moved = blockNow(listOf(inGoogle))!!
        assertEquals(EventEditKind.CHANGE, moved.latest.kind)
        assertEquals(at(15), moved.event?.startAtMs)
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(15)), moved, true))
        assertEquals(listOf("ev2"), PlanCalendarRules.withoutTaskBlocks(PendingEditRules.apply(listOf(dentist, inGoogle), eFold.all(), now), eFold.all()).map { it.id })
        // And once Google's copy shows the new time it is still the task's block (by its id), on the Mac too.
        sync()
        val polled = inGoogle.copy(startAtMs = at(15), endAtMs = at(15, 30))
        assertEquals(listOf("ev2"), PlanCalendarRules.withoutTaskBlocks(listOf(dentist, polled), eMac.all()).map { it.id })
        val item = ActivityRules.calendarEditItem(moved.latest, now, LocalCalendar.UTC)!!
        assertTrue(item.why.startsWith("Keeps a task's block in step"), item.why)
    }

    @Test
    fun somedaySkipDeleteOrNoTimeRemovesTheBlockAndPlanningAgainBringsItBack() {
        blockInGoogle()
        val block = blockNow(listOf(inGoogle))!!
        assertEquals(BlockStep.Remove(inGoogle), PlanCalendarRules.follow(null, block, true)) // deleted
        assertEquals(BlockStep.Remove(inGoogle), PlanCalendarRules.follow(planned(null), block, true))
        assertEquals(BlockStep.Remove(inGoogle), PlanCalendarRules.follow(planned(at(11), Lifecycle.SOMEDAY), block, true))
        assertEquals(BlockStep.Remove(inGoogle), PlanCalendarRules.follow(planned(at(11), Lifecycle.CANCELLED), block, true))

        made(eFold.delete(inGoogle, forTask = "t1"))
        val gone = blockNow(listOf(inGoogle))!!
        assertTrue(gone.removed)
        assertNull(gone.event)
        assertEquals(BlockStep.None, PlanCalendarRules.follow(null, gone, true))
        // Planned again (Undo after Delete, or a new time): it comes back with its length, only with the setting on.
        assertEquals(
            BlockStep.Add("google", "meka@gmail.com", EventDraft("Write report", at(16), at(16, 30), false, notes = "Planned with MEKA")),
            PlanCalendarRules.follow(planned(at(16)), gone, true),
        )
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(16)), gone, false))
    }

    @Test
    fun aBlockStillOnItsWayCantFollowAndAClashWaitsForMeka() {
        val id = made(eFold.add("google", "meka@gmail.com", PlanCalendarRules.draft(report), forTask = "t1"))
        val waiting = blockNow(emptyList())!!
        assertTrue(waiting.event!!.isProvisional)
        assertEquals(EventEditState.WAITING, waiting.latest.state(now))
        val step = PlanCalendarRules.follow(planned(at(15)), waiting, true)
        assertEquals(BlockStep.OnItsWay("The block is still on its way to Google · change it there once it shows"), step)
        // Answered, but the mirror hasn't caught up: still on its way.
        world.clock.nowMs += 10_000
        sync(); answer(id, "DONE"); sync()
        assertIs<BlockStep.OnItsWay>(PlanCalendarRules.follow(planned(at(15)), blockNow(emptyList()), true))
        // A follow-up that clashed (Meka changed the block in Google): nothing more until he chooses.
        val c = made(eFold.change(inGoogle, PlanCalendarRules.moved(inGoogle, at(15)), forTask = "t1"))
        world.clock.nowMs += 10_000
        sync(); answer(c, "CLASH"); sync()
        assertEquals(BlockStep.None, PlanCalendarRules.follow(planned(at(17)), blockNow(listOf(inGoogle)), true))
    }

    @Test
    fun refusedAndUndoneFollowUpsDontCount() {
        blockInGoogle()
        val c = made(eFold.change(inGoogle, PlanCalendarRules.moved(inGoogle, at(15)), forTask = "t1"))
        assertTrue(eFold.undo(c))
        assertEquals(EventEditKind.ADD, blockNow(listOf(inGoogle))!!.latest.kind)
        val d = made(eFold.delete(inGoogle, forTask = "t1"))
        world.clock.nowMs += 10_000
        sync(); answer(d, "REFUSED"); sync()
        val block = blockNow(listOf(inGoogle))!!
        assertEquals(EventEditKind.ADD, block.latest.kind)
        assertEquals("g9", block.event?.id)
    }
}
