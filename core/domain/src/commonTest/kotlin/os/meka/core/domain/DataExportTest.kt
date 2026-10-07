package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataExportTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")

    private fun export(dev: os.meka.core.testing.Device = a) = DataExport.collect(dev.replica, dev.name, world.clock.nowMs)

    @Test
    fun nothingYetSaysSo() {
        val s = DataExport.summary(export())
        assertEquals(0, s.total)
        assertEquals("Nothing to export yet", s.totalLine)
        assertEquals("", s.partsLine)
    }

    @Test
    fun exportsEveryEntityWithItsWinningFieldsAndLeavesOutDeletedOnes() {
        val keep = a.tasks.create(NewTask("Book dentist"))
        a.tasks.addChecklistItem(keep, "Find the number")
        val gone = a.tasks.create(NewTask("Mistake"))
        a.tasks.delete(gone)
        a.tasks.edit(keep, TaskEdit(notes = "Ask about Thursday"))

        val data = export()
        val tasks = data.entities.filter { it.type == EntityTypes.TASK }
        assertEquals(listOf(keep), tasks.map { it.id })
        val t = tasks.single()
        assertEquals(FieldValue.Text("Book dentist"), t.fields[ActionableFields.TITLE])
        assertEquals(FieldValue.Text("Ask about Thursday"), t.fields[ActionableFields.NOTES])
        assertEquals(t.fields.keys.sorted(), t.fields.keys.toList(), "fields in name order")
        assertTrue(t.conflicts.isEmpty())
        assertEquals(listOf(EntityTypes.TASK to 1, EntityTypes.CHECKLIST_ITEM to 1), data.counts)

        val s = DataExport.summary(data)
        assertEquals("2 items", s.totalLine)
        assertEquals("1 task · 1 step", s.partsLine)
    }

    @Test
    fun headlinesAreNotMekasDataAndStayOut() {
        a.replica.commitLocal(EntityTypes.HEADLINE, "world.1", mapOf("title" to FieldValue.Text("Story")))
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, "quiet", mapOf("quietHours" to FieldValue.Text("on;1320;420")))
        val data = export()
        assertEquals(listOf(EntityTypes.CONTEXT_MODE), data.entities.map { it.type })
        assertEquals("1 setting", DataExport.summary(data).partsLine)
    }

    @Test
    fun anUnchosenConflictKeepsBothValues() {
        val id = a.tasks.create(NewTask("Call Mum"))
        a.sync(); m.sync()
        a.goOffline(); m.goOffline()
        a.tasks.edit(id, TaskEdit(title = "Call Mum tonight"))
        world.clock.advance(1_000)
        m.tasks.edit(id, TaskEdit(title = "Call Mum Sunday"))
        a.goOnline(); m.goOnline()
        a.sync(); m.sync(); a.sync()

        val fromA = export(a).entities.single { it.id == id }
        val fromM = export(m).entities.single { it.id == id }
        val offered = fromA.conflicts.getValue(ActionableFields.TITLE)
        assertEquals(setOf(FieldValue.Text("Call Mum tonight"), FieldValue.Text("Call Mum Sunday")), offered.toSet())
        assertEquals(fromA.fields[ActionableFields.TITLE], offered.first(), "the winner comes first")
        assertEquals(fromA.fields, fromM.fields, "both devices export the same values")
        assertEquals(fromA.conflicts.mapValues { it.value.toSet() }, fromM.conflicts.mapValues { it.value.toSet() })
    }

    @Test
    fun orderIsStableByTypeThenId() {
        val g = Goals(a.replica, { "g${world.random.nextInt(1_000_000)}" }, { world.clock.nowMs })
        g.addHabit("Stretch", 7, HabitTiming.MORNING, 10)
        a.tasks.create(NewTask("B"))
        a.tasks.create(NewTask("A"))
        val e = export().entities
        assertEquals(e.sortedWith(compareBy({ DataExport.TYPES.indexOf(it.type) }, { it.id })), e)
        assertEquals(export(), export(), "the same data exports the same way")
    }

    @Test
    fun fileNameIsTheLocalDay() {
        val ms = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS + 2 * 3_600_000L
        assertEquals("meka-export-2026-10-07.json", DataExport.fileName(ms, LocalCalendar.UTC))
    }
}
