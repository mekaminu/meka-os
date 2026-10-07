package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv
import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private fun ids(): String = "a${n++}"
    private fun log(d: Device) = ActivityLog(d.replica, ::ids, { world.clock.nowMs })

    private val a = world.device("android")
    private val m = world.device("mac")
    private val la = log(a)
    private val lm = log(m)
    private val ta = Tasks(a.replica, { "t${n++}" }, { world.clock.nowMs })

    /** A Wednesday, 09:00 UTC. */
    private val wednesday: Long

    init {
        val today = world.clock.nowMs.floorDiv(dayMs)
        wednesday = today + (10 - CivilDate.isoDayOfWeek(today)) % 7 + 7
        world.clock.nowMs = wednesday * dayMs + 9 * hourMs
    }

    private fun notice(key: String, tier: NoticeTier = NoticeTier.HEADS_UP, source: NoticeSource = NoticeSource.RENEWAL_CANCEL_BY) =
        Notice(key, source, tier, "Cancel or keep Netflix?", "Cancel by tomorrow", world.clock.nowMs, NoticeTarget.LISTS)

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }
    private fun title(id: String, d: Device = a) = d.replica.entity(EntityTypes.TASK, id)?.get(ActionableFields.TITLE)?.textOrNull

    // ---- Encoding ----

    @Test
    fun changesRoundTripIncludingAwkwardText() {
        val changes = listOf(
            ActivityChange(EntityTypes.TASK, "t1", "title", "Tab\there\nnew line \\ slash".fv(), FieldValue.Null),
            ActivityChange(EntityTypes.TASK, "t1", "scheduledAtMs", 5L.fv(), (-7L).fv()),
            ActivityChange(EntityTypes.CHECKLIST_ITEM, "s.1", "checked", FieldValue.Bool(false), FieldValue.Bool(true)),
            ActivityChange(EntityTypes.TASK, "t2", "notes", "".fv(), "t:looks like a tag".fv()),
        )
        assertEquals(changes, ActivityRules.decodeChanges(ActivityRules.encodeChanges(changes)))
    }

    @Test
    fun unreadableChangesMeanNothingCanBeUndone() {
        assertTrue(ActivityRules.decodeChanges(null).isEmpty())
        assertTrue(ActivityRules.decodeChanges("task\tt1\ttitle\tt:a").isEmpty())
        assertTrue(ActivityRules.decodeChanges("task\tt1\ttitle\tx:a\t-").isEmpty())
        assertTrue(ActivityRules.decodeChanges("task\tt1\ttitle\tt:bad\\q\t-").isEmpty())
        // One bad line spoils the lot: half an undo is worse than none.
        assertTrue(ActivityRules.decodeChanges("task\tt1\ttitle\t-\t-\nbroken").isEmpty())
    }

    @Test
    fun idsAreStableAndShort() {
        assertEquals(ActivityRules.noticeId("brief:20000"), ActivityRules.noticeId("brief:20000"))
        assertTrue(ActivityRules.noticeId("brief:20000") != ActivityRules.noticeId("brief:20001"))
        assertEquals(17, ActivityRules.noticeId("x").length)
        assertEquals("cbf29ce484222325", ActivityRules.fnv64(""))
        assertEquals(ActivityRules.digestId(5, "Evening digest · 3 things"), ActivityRules.digestId(5, "Evening digest · 4 things"))
    }

    // ---- Reminders and digests ----

    @Test
    fun aPostedNoticeIsLoggedWithWhy() {
        la.recordPosted(listOf(notice("renewal:r1:cancel:1")))
        val item = la.items().single()
        assertEquals(ActivityKind.REMINDED, item.kind)
        assertEquals("Reminded you: Cancel or keep Netflix?", item.summary)
        assertEquals("Cancel by tomorrow", item.detail)
        assertEquals("Cancel-by dates · Heads-up", item.why)
        assertFalse(item.canUndo)

        val v = la.view()
        assertEquals("This week: 1 reminder", v.weekLine)
        assertEquals("Today", v.days.single().label)
        val row = v.days.single().rows.single()
        assertEquals("09:00", row.time)
        assertEquals("Why: Cancel-by dates · Heads-up", row.why)
        assertNull(row.undoneLine)
    }

    @Test
    fun digestsAndSilentTiersAreNotLoggedAsReminders() {
        la.recordPosted(listOf(notice("d", NoticeTier.DIGEST), notice("s", NoticeTier.SILENT)))
        assertTrue(la.items().isEmpty())
        assertTrue(la.view().isEmpty)
        assertEquals("", la.view().weekLine)
    }

    @Test
    fun theSameNoticeOnBothDevicesIsOneEntry() {
        la.recordPosted(listOf(notice("brief:1", source = NoticeSource.BRIEF)))
        syncBoth()
        world.clock.advance(60_000)
        lm.recordPosted(listOf(notice("brief:1", source = NoticeSource.BRIEF)))
        // Offline on both: each writes it, then they converge on one entry.
        la.recordPosted(listOf(notice("shutdown:1", source = NoticeSource.SHUTDOWN)))
        lm.recordPosted(listOf(notice("shutdown:1", source = NoticeSource.SHUTDOWN)))
        syncBoth()
        assertEquals(2, la.items().size)
        assertEquals(la.items().toSet(), lm.items().toSet())
        assertTrue(a.replica.conflicts(EntityTypes.AGENT_ACTION).isEmpty())
    }

    @Test
    fun aDigestIsLogged() {
        val d = Digest("Evening digest · 3 things", "1 renewal due · 2 to chase", listOf("a", "b"), 3, "Evening digest", NoticeTarget.LISTS)
        la.recordDigest(d)
        la.recordDigest(d.copy(title = "Evening digest · 4 things"))
        val item = la.items().single()
        assertEquals(ActivityKind.DIGEST, item.kind)
        assertEquals("Sent a digest: Evening digest · 3 things", item.summary)
        assertEquals("1 renewal due · 2 to chase", item.detail)
        assertEquals("This week: 1 digest", la.view().weekLine)
    }

    @Test
    fun theScreenShowsThirtyDaysByDayNewestFirst() {
        la.recordPosted(listOf(notice("old")))
        world.clock.advance(31 * dayMs)
        la.recordPosted(listOf(notice("yesterday")))
        world.clock.advance(dayMs)
        la.recordPosted(listOf(notice("one")))
        world.clock.advance(hourMs)
        la.recordPosted(listOf(notice("two")))
        val v = la.view()
        assertEquals(listOf("Today", "Yesterday"), v.days.map { it.label })
        assertEquals(listOf("10:00", "09:00"), v.days[0].rows.map { it.time })
        assertEquals(1, v.days[1].rows.size)
    }

    @Test
    fun theWeekLineCountsFromMonday() {
        world.clock.nowMs = (wednesday - 3) * dayMs + 9 * hourMs // Sunday before
        la.recordPosted(listOf(notice("sun")))
        world.clock.nowMs = wednesday * dayMs + 9 * hourMs
        la.recordPosted(listOf(notice("wed1"), notice("wed2", NoticeTier.CRITICAL)))
        assertEquals("This week: 2 reminders", la.view().weekLine)
    }

    // ---- Actions and undo ----

    @Test
    fun anActionIsRecordedAndUndone() {
        val t = ta.create(NewTask("Dentist"))
        val id = la.act(
            summary = "Moved Dentist to tomorrow", why = "Your rule: move what's left at 18:00", source = "rule:carry",
            level = "AUTO_UNDER_RULE",
            changes = listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "Dentist (moved)".fv())),
        )
        assertEquals("Dentist (moved)", title(t))
        val item = la.items().single { it.id == id }
        assertEquals(ActivityKind.CHANGED, item.kind)
        assertEquals("AUTO_UNDER_RULE", item.level)
        assertTrue(item.canUndo)
        assertEquals("This week: 1 change", la.view().weekLine)

        world.clock.advance(12 * 60_000)
        assertEquals(UndoOutcome.UNDONE, la.undo(id))
        assertEquals("Dentist", title(t))
        val row = la.view().days.single().rows.single()
        assertFalse(row.canUndo)
        assertEquals("Undone at 09:12", row.undoneLine)
        // Twice changes nothing more.
        assertEquals(UndoOutcome.UNDONE, la.undo(id))
        assertEquals("Dentist", title(t))
    }

    @Test
    fun undoLeavesWhatYouChangedSince() {
        val t = ta.create(NewTask("Dentist"))
        val id = la.act("Changed two things", "test", "rule:x", null, listOf(
            PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "Dentist!".fv()),
            PlannedChange(EntityTypes.TASK, t, ActionableFields.NOTES, "bring card".fv()),
        ))
        ta.edit(t, TaskEdit(title = "Dentist at 3"))
        assertEquals(UndoOutcome.PARTLY, la.undo(id))
        assertEquals("Dentist at 3", title(t))
        assertNull(a.replica.entity(EntityTypes.TASK, t)!![ActionableFields.NOTES].textOrNull)
        assertEquals("Undone at 09:00 · Partly undone: you've changed some of it since", la.view().days.single().rows.single().undoneLine)

        val id2 = la.act("Renamed", "test", "rule:x", null, listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "X".fv())))
        ta.edit(t, TaskEdit(title = "Y"))
        assertEquals(UndoOutcome.CHANGED_SINCE, la.undo(id2))
        assertEquals("Y", title(t))
    }

    @Test
    fun somethingMekaCreatedIsUndoneByDeletingIt() {
        val id = la.act("Added a task", "test", "rule:x", null, listOf(
            PlannedChange(EntityTypes.TASK, "new1", ActionableFields.TITLE, "Renew passport".fv()),
            PlannedChange(EntityTypes.TASK, "new1", ActionableFields.LIFECYCLE, Lifecycle.ACTIVE.name.fv()),
        ))
        assertEquals("Renew passport", title("new1"))
        assertFalse(a.replica.entity(EntityTypes.TASK, "new1")!!.deleted)
        assertEquals(UndoOutcome.UNDONE, la.undo(id))
        val s = a.replica.entity(EntityTypes.TASK, "new1")!!
        assertTrue(s.deleted)
        assertEquals("Renew passport", s[ActionableFields.TITLE].textOrNull)
    }

    @Test
    fun undoOnBothDevicesOfflineConverges() {
        val t = ta.create(NewTask("Dentist"))
        val id = la.act("Renamed", "test", "rule:x", null, listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "X".fv())))
        syncBoth()
        assertEquals("X", title(t, m))
        assertEquals(UndoOutcome.UNDONE, la.undo(id))
        world.clock.advance(1_000)
        assertEquals(UndoOutcome.UNDONE, lm.undo(id))
        syncBoth()
        assertEquals("Dentist", title(t))
        assertEquals("Dentist", title(t, m))
        assertTrue(a.replica.conflicts(EntityTypes.TASK, EntityTypes.AGENT_ACTION).isEmpty())
        assertFalse(lm.items().single().canUndo)
    }

    @Test
    fun remindersCantBeUndone() {
        la.recordPosted(listOf(notice("k")))
        assertEquals(UndoOutcome.NOT_UNDOABLE, la.undo(la.items().single().id))
        assertEquals(UndoOutcome.NOT_UNDOABLE, la.undo("nope"))
    }

    // ---- Phone builds published by GitHub ----

    @Test
    fun aGitHubBuildShowsOnBothDevicesWithNothingToUndo() {
        val build = AppUpdateRules.Build(412, "0.1.412", 24_300_000)
        val id = ActivityRules.releaseId("android", 412)
        assertEquals(id, ActivityRules.releaseId("android", 412))
        assertTrue(id != ActivityRules.releaseId("android", 413) && id != ActivityRules.releaseId("macos", 412))
        // The server writes it; here the Fold's replica stands in for the server's op, then it syncs to the Mac.
        a.replica.commitLocal(EntityTypes.AGENT_ACTION, id, ActivityRules.releaseFields("GitHub build", "release:github-build", build, world.clock.nowMs))
        syncBoth()
        for (l in listOf(la, lm)) {
            val view = l.view()
            assertEquals("This week: 1 phone build", view.weekLine)
            val row = view.days.single().rows.single()
            assertEquals(ActivityKind.PUBLISHED, row.kind)
            assertEquals("09:00", row.time)
            assertEquals("GitHub build published build 412", row.summary)
            assertEquals("MEKA 0.1.412 · 24.3 MB · install it from Today on the Fold", row.detail)
            assertEquals("Why: Hands-free phone updates · after a green CI run on main", row.why)
            assertFalse(row.canUndo)
            assertEquals(UndoOutcome.NOT_UNDOABLE, l.undo(id))
        }
    }

    @Test
    fun anEntryOfAKindThisAppDoesntKnowIsSkipped() {
        a.replica.commitLocal(
            EntityTypes.AGENT_ACTION, "x1",
            mapOf(ActivityFields.AT to world.clock.nowMs.fv(), ActivityFields.KIND to "FROM_THE_FUTURE".fv(), ActivityFields.SUMMARY to "?".fv()),
        )
        assertTrue(la.items().isEmpty())
        assertTrue(la.view().isEmpty)
    }
}
