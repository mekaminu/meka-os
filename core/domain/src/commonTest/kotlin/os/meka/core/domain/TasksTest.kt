package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {
    private val d = SyncWorld().device("android")

    @Test
    fun createValidatesTitle() {
        assertFailsWith<ValidationException> { d.tasks.create(NewTask("   ")) }
        assertEquals("Trimmed", d.tasks.get(d.tasks.create(NewTask("  Trimmed  ")))!!.title)
    }

    @Test
    fun unchangedEditWritesNoOps() {
        val id = d.tasks.create(NewTask("Same"))
        val before = d.store.opCount
        d.tasks.edit(id, TaskEdit(title = "Same"))
        assertEquals(before, d.store.opCount)
    }

    @Test
    fun somedayDefaultsToIdeaAndDoesNotConsumeCapacity() {
        val id = d.tasks.create(NewTask("Learn piano", lifecycle = Lifecycle.SOMEDAY))
        val t = d.tasks.get(id)!!
        assertEquals(SomedayKind.IDEA, t.somedayKind)
        assertTrue(!t.lifecycle.consumesCapacity)
    }

    @Test
    fun completedAtOnlyWhileDone() {
        val id = d.tasks.create(NewTask("x"))
        d.tasks.complete(id)
        assertTrue(d.tasks.get(id)!!.completedAtMs != null)
        d.tasks.reopen(id)
        assertNull(d.tasks.get(id)!!.completedAtMs)
    }

    @Test
    fun deleteAndRestore() {
        val id = d.tasks.create(NewTask("x"))
        d.tasks.delete(id)
        assertNull(d.tasks.get(id))
        assertFailsWith<ValidationException> { d.tasks.complete(id) }
        d.tasks.restore(id)
        assertEquals("x", d.tasks.get(id)!!.title)
    }

    @Test
    fun invalidEstimateRejected() {
        assertFailsWith<ValidationException> { d.tasks.create(NewTask("x", estimateMinutes = 0)) }
    }
}
