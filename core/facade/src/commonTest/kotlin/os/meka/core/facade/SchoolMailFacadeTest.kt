package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.SchoolMailRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School and family logistics from email, slice 1: a school email's find reaches Needs you on both apps; Add puts its
 * line into School (once, on both, marked as from the school's email) and Undo takes it out; a line he typed himself
 * for the same thing hides the find. Invented school and children (rule 13).
 */
class SchoolMailFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvsm${k++}", "hh", EntityTypes.MAIL_FIND, id, f, v, clock.now(), emptyList(), "server"))
    }

    private val received = CivilDate.toEpochDay(2026, 10, 16)

    @Test
    fun addPutsItInSchoolOnBothAppsAndUndoTakesItOut() = runTest {
        val trip = SchoolMailRules.read("Trip payment", "Please pay £12 for the trip to Oakfield Farm by 20 Nov.", received)!!
        val inset = SchoolMailRules.read("INSET day Monday 2 November", null, received)!!
        val idt = SchoolMailRules.findId("acc1", trip)
        val idi = SchoolMailRules.findId("acc1", inset)
        serverWrite(idt, SchoolMailRules.serverFields(trip, "Oakfield Primary", "Personal", "Trip payment", received))
        serverWrite(idi, SchoolMailRules.serverFields(inset, "Oakfield Primary", "Personal", null, received))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = fold.mailFinds.value!!
        assertEquals(SchoolMailRules.TITLE, card.title)
        // Soonest first: the INSET day (Mon 2 Nov) before the payment (Fri 20 Nov).
        assertEquals(listOf(idi, idt), card.rows.map { it.id })
        assertTrue(card.rows.first { it.id == idt }.line.startsWith("Trip payment · £12 · due Fri 20 Nov"), card.rows.first { it.id == idt }.line)
        assertEquals(2, mac.mailFinds.value!!.rows.size)

        val added = fold.addMailFind(idt)
        assertTrue(added.startsWith("In School: Trip payment · due Fri 20 Nov"), added)
        assertEquals(listOf(idi), fold.mailFinds.value!!.rows.map { it.id })
        val row = fold.schoolView.value.dates.single()
        assertEquals("Trip payment · £12", row.title)
        assertEquals(SchoolMailRules.SOURCE_LINE, row.source)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(idi), mac.mailFinds.value!!.rows.map { it.id })
        assertEquals("Trip payment · £12", mac.schoolView.value.dates.single().title)

        // Undo: out of School, back in the card.
        fold.undoMailFind()
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertEquals(listOf(idi, idt), fold.mailFinds.value!!.rows.map { it.id })

        // A day off he typed himself for that day hides the INSET find; Add on the Mac then lands the trip once.
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        mac.addSchool("INSET 2 Nov")
        assertEquals(listOf(idt), mac.mailFinds.value!!.rows.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        mac.addMailFind(idt)
        fold.addMailFind(idt)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(1, fold.schoolView.value.dates.size)
        assertNull(fold.mailFinds.value)
    }
}
