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
}
