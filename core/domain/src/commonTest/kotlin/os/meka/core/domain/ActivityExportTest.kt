package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Activity log completeness (2026-10-18): Undo running out, no message words, Export. */
class ActivityExportTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val a = world.device("android")
    private val log = ActivityLog(a.replica, { "a${n++}" }, { world.clock.nowMs })
    private val tasks = Tasks(a.replica, { "t${n++}" }, { world.clock.nowMs })

    init { world.clock.nowMs = world.clock.nowMs.floorDiv(dayMs) * dayMs + 9 * 3_600_000L }

    @Test
    fun aChangeOlderThanThirtyDaysSaysItIsTooLateAndChangesNothing() {
        val t = tasks.create(NewTask("Dentist"))
        val id = log.act("Renamed", "test", "rule:x", null, listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "Dentist at 3".fv())))
        world.clock.nowMs += 29 * dayMs
        val row = log.view().days.flatMap { it.rows }.single { it.id == id }
        assertTrue(row.canUndo)
        assertNull(row.undoneLine)

        world.clock.nowMs += 2 * dayMs
        val item = log.items().single { it.id == id }
        assertTrue(ActivityRules.undoExpired(item, world.clock.nowMs))
        assertEquals(UndoOutcome.TOO_LATE, log.undo(id))
        assertEquals("Too late to undo · change it by hand", UndoOutcome.TOO_LATE.line)
        assertEquals("Dentist at 3", a.replica.entity(EntityTypes.TASK, t)?.get(ActionableFields.TITLE)?.textOrNull)
        assertNull(log.items().single { it.id == id }.undoneAtMs)
        // A reminder never offers Undo, so it never says it's too late either.
        log.recordPosted(listOf(Notice("k1", NoticeSource.TASK_REMINDER, NoticeTier.HEADS_UP, "Dentist", "15:00", world.clock.nowMs - 40 * dayMs, NoticeTarget.TODAY)))
        assertFalse(ActivityRules.undoExpired(log.items().single { it.kind == ActivityKind.REMINDED }, world.clock.nowMs))
    }

    @Test
    fun leavingNowsDraftAndMeetingPrepsSubjectsAreNeverKept() {
        val now = world.clock.nowMs
        log.recordPosted(listOf(
            Notice("leave", NoticeSource.LEAVING, NoticeTier.HEADS_UP, "Tell Jordan you're leaving?", "Leaving now, home about 18:40", now, NoticeTarget.TODAY),
            Notice("prep", NoticeSource.MEETING_PREP, NoticeTier.HEADS_UP, "Prep · Quote review at 14:00", "With Sam Taylor · last email Tue 13:02 · “Re: quote”", now, NoticeTarget.TODAY),
            Notice("bins", NoticeSource.BINS, NoticeTier.HEADS_UP, "Black bin out tonight", "Collected tomorrow", now, NoticeTarget.TODAY),
        ))
        val rows = log.view().days.flatMap { it.rows }.associateBy { it.summary }
        assertNull(rows.getValue("Reminded you: Tell Jordan you're leaving?").detail)
        assertNull(rows.getValue("Reminded you: Prep · Quote review at 14:00").detail)
        assertEquals("Collected tomorrow", rows.getValue("Reminded you: Black bin out tonight").detail)
        // An entry logged before this, with its words, shows and exports without them.
        a.replica.commitLocal(EntityTypes.AGENT_ACTION, "old", mapOf(
            ActivityFields.AT to now.fv(), ActivityFields.KIND to ActivityKind.REMINDED.name.fv(),
            ActivityFields.SUMMARY to "Reminded you: Tell Jordan you're leaving?".fv(), ActivityFields.DETAIL to "Leaving now, home about 18:40".fv(),
            ActivityFields.WHY to "Leaving now".fv(), ActivityFields.SOURCE to "notice:LEAVING".fv(),
        ))
        assertNull(log.items().single { it.id == "old" }.detail)
        val csv = ActivityExportRules.file(log.items(), world.clock.nowMs, LocalCalendar.UTC).csv!!
        assertFalse("Leaving now, home" in csv)
        assertFalse("Re: quote" in csv)
        assertTrue("Collected tomorrow" in csv)
    }

    @Test
    fun exportSavesEveryEntryOldestFirstWithTheUndoColumn() {
        assertEquals(ActivityExportRules.NOTHING, ActivityExportRules.file(emptyList(), world.clock.nowMs, LocalCalendar.UTC).line)
        val t = tasks.create(NewTask("Dentist"))
        log.recordPosted(listOf(Notice("k1", NoticeSource.TASK_REMINDER, NoticeTier.HEADS_UP, "Dentist, \"today\"", "15:00", world.clock.nowMs, NoticeTarget.TODAY)))
        world.clock.nowMs -= 45 * dayMs // an entry older than the screen's 30 days still exports
        val old = log.act("Renamed", "test", "rule:x", null, listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.TITLE, "X".fv())))
        world.clock.nowMs += 45 * dayMs
        val recent = log.act("Moved", "test", "rule:y", null, listOf(PlannedChange(EntityTypes.TASK, t, ActionableFields.NOTES, "n".fv())))
        log.undo(recent)

        assertTrue(log.view().days.flatMap { it.rows }.none { it.id == old })
        val f = ActivityExportRules.file(log.items(), world.clock.nowMs, LocalCalendar.UTC)
        assertEquals("3 entries · choose where to save it", f.line)
        assertTrue(f.fileName!!.startsWith("navilife-activity-") && f.fileName!!.endsWith(".csv"))
        val lines = f.csv!!.trimEnd().split("\r\n")
        assertEquals("Date,Time,Kind,What,Detail,Why,Undo", lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains(",Done for you,Renamed,,test,Too late to undo · change it by hand"))
        assertTrue(lines.any { it.contains("Reminder,\"Reminded you: Dentist, \"\"today\"\"\",15:00") })
        assertTrue(lines.any { it.contains(",Moved,,test,Undone ") })
        assertEquals("Kept in your data on your devices · this shows the last 30 days and Undo lasts 30 · Export saves all 3 entries",
            ActivityExportRules.keptLine(3))
    }
}
