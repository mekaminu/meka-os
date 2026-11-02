package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 3 slice 3: errands he asks Obi for, the device's side and the shared rules ([ObiJobRules]). */
class ObiJobsTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val jFold = ObiJobs(fold.replica, { "j${n++}" }, { world.clock.nowMs })
    private val jMac = ObiJobs(mac.replica, { "m${n++}" }, { world.clock.nowMs })
    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun anAskIsKeptInHisWordsAndSyncs() {
        assertEquals(ObiJobRules.BLANK, jFold.ask("   ").line)
        val a = jFold.ask("  find someone   to service the boiler. ")
        assertEquals("Obi is on it · Find someone to service the boiler", a.line)
        sync()
        val j = jMac.all().single()
        assertEquals(a.id, j.id)
        assertEquals("Find someone to service the boiler", j.ask)
        assertEquals(ObiJobState.ASKED, j.state)
        val row = ObiJobRules.rows(jMac.all(), world.clock.nowMs, LocalCalendar.UTC).single()
        assertEquals("Starting · Obi is planning it", row.status)
        assertEquals("Not started yet", row.did)
        assertTrue(row.canStop)
    }

    @Test
    fun atMostFiveOpenAtOnce() {
        repeat(ObiJobRules.MAX_OPEN) { assertNotNull(jFold.ask("Errand $it").id) }
        val refused = jFold.ask("One more")
        assertNull(refused.id)
        assertEquals(ObiJobRules.TOO_MANY, refused.line)
        assertTrue(jFold.stop(jFold.all().first().id))
        assertNotNull(jFold.ask("One more").id)
    }

    @Test
    fun stopOnEitherDeviceStandsAndFinishesIt() {
        val id = jFold.ask("Plan half term").id!!
        sync()
        assertTrue(jMac.stop(id))
        assertFalse(jMac.stop(id))
        sync()
        val j = jFold.all().single()
        assertTrue(j.finished)
        assertEquals(ObiJobState.STOPPED, j.state)
        // A later server write of "working" doesn't bring it back: Stop wins over state.
        fold.replica.commitLocal(EntityTypes.OBI_JOB, id, mapOf(ObiJobFields.STATE to FieldValue.Text("working")))
        assertTrue(jFold.all().single().finished)
        assertEquals("Stopped", ObiJobRules.rows(jFold.all(), world.clock.nowMs, LocalCalendar.UTC).single().status)
        assertFalse(ObiJobRules.rows(jFold.all(), world.clock.nowMs, LocalCalendar.UTC).single().canStop)
    }

    @Test
    fun heAnswersOnEitherDeviceOnlyWhenObiNeedsHim() {
        // Part 3 slice 5: Answer and Carry on.
        val id = jFold.ask("Find someone to service the boiler").id!!
        assertFalse(jFold.answer(id, "Leeds")) // not waiting for him yet
        // The server asks him something.
        fold.replica.commitLocal(EntityTypes.OBI_JOB, id, mapOf(
            ObiJobFields.STATE to FieldValue.Text(ObiJobState.NEEDS_YOU.code), ObiJobFields.NEED to FieldValue.Text("Which area?"),
        ))
        sync()
        var row = ObiJobRules.rows(jMac.all(), world.clock.nowMs, LocalCalendar.UTC).single()
        assertTrue(row.canAnswer)
        assertFalse(row.canCarryOn)
        assertFalse(jMac.answer(id, "   "))
        assertTrue(jMac.answer(id, "  somewhere   in Leeds "))
        assertFalse(jMac.answer(id, "or York")) // once until the server reads it
        sync()
        val j = jFold.all().single()
        assertEquals("Somewhere in Leeds", j.answer)
        assertTrue(j.answerWaiting)
        row = ObiJobRules.rows(jFold.all(), world.clock.nowMs, LocalCalendar.UTC).single()
        assertEquals(ObiJobRules.ANSWER_STATUS, row.status)
        assertNull(row.need)
        assertFalse(row.canAnswer)
        assertEquals("Somewhere in Leeds", row.youSaid)
        assertTrue(ObiJobRules.askLines(listOf(row), 3).single().contains("he said: Somewhere in Leeds"))
        // Read by the server: the row shows the answer from what he's heard.
        fold.replica.commitLocal(EntityTypes.OBI_JOB, id, mapOf(
            ObiJobFields.ANSWER_TAKEN to FieldValue.Int64(j.answeredAtMs!!), ObiJobFields.STATE to FieldValue.Text(ObiJobState.WORKING.code),
            ObiJobFields.NEED to FieldValue.Null, ObiJobFields.HEARD to FieldValue.Text(ObiJobRules.encodeHeard(listOf(ObiHeard("Which area?", "Somewhere in Leeds")))),
        ))
        row = ObiJobRules.rows(jFold.all(), world.clock.nowMs, LocalCalendar.UTC).single()
        assertFalse(row.status == ObiJobRules.ANSWER_STATUS)
        assertEquals("Somewhere in Leeds", row.youSaid)
        // At the cap, Carry on.
        fold.replica.commitLocal(EntityTypes.OBI_JOB, id, mapOf(
            ObiJobFields.STATE to FieldValue.Text(ObiJobState.NEEDS_YOU.code), ObiJobFields.NEED to FieldValue.Text(ObiJobRules.CAP_NEED),
            ObiJobFields.TOTAL_STEPS to FieldValue.Int64(ObiJobRules.TOTAL_STEP_CAP.toLong()),
        ))
        assertTrue(ObiJobRules.rows(jFold.all(), world.clock.nowMs, LocalCalendar.UTC).single().canCarryOn)
        world.clock.nowMs += 1_000
        assertTrue(jFold.carryOn(id))
        assertEquals(ObiJobRules.CARRY_ON, jFold.all().single().answer)
        assertTrue(ObiJobRules.isCarryOn(ObiJobRules.CARRY_ON))
        assertFalse(ObiJobRules.isCarryOn("Carry on but look in York"))
    }

    @Test
    fun heardRoundTrips() {
        val h = listOf(ObiHeard("Which area?", "Leeds\tor York"), ObiHeard("", "Shorter"))
        assertEquals(listOf(ObiHeard("Which area?", "Leeds or York"), ObiHeard("", "Shorter")), ObiJobRules.decodeHeard(ObiJobRules.encodeHeard(h)))
        val many = (1..10).map { ObiHeard("q$it", "a$it") }
        assertEquals(ObiJobRules.MAX_HEARD, ObiJobRules.decodeHeard(ObiJobRules.encodeHeard(many)).size)
    }

    @Test
    fun stepsRoundTripAndPlansAreChecked() {
        val steps = listOf(
            ObiStep(ObiStepKind.SEARCH, "Find engineers\tnear home", "Gas Safe boiler service", done = true),
            ObiStep(ObiStepKind.WORK, "Compare what I found"),
        )
        val back = ObiJobRules.decodeSteps(ObiJobRules.encodeSteps(steps))
        assertEquals(listOf("Find engineers near home", "Compare what I found"), back.map { it.title })
        assertEquals(listOf(true, false), back.map { it.done })
        assertEquals("Gas Safe boiler service", back[0].query)
        // A search with no query goes; a plan of only searches gets a step that reports back.
        val checked = ObiJobRules.checkedPlan(listOf(ObiStep(ObiStepKind.SEARCH, "Look", ""), ObiStep(ObiStepKind.SEARCH, "Search", "fence quotes")))
        assertEquals(listOf(ObiStepKind.SEARCH, ObiStepKind.WORK), checked.map { it.kind })
        assertEquals(ObiJobRules.MAX_PLAN, ObiJobRules.checkedPlan((1..10).map { ObiStep(ObiStepKind.WORK, "Step $it") }).size)
    }

    @Test
    fun theListPutsWhatNeedsHimFirst() {
        val now = world.clock.nowMs
        fun job(id: String, state: ObiJobState, need: String? = null, askedAt: Long = now, steps: List<ObiStep> = emptyList(), found: String? = null) =
            ObiJob(id, "Job $id", askedAt, state, false, steps, emptyList(), found, need, null, now, null, null, 0, 0, null)
        val rows = ObiJobRules.rows(
            listOf(
                job("a", ObiJobState.DONE, found = "Three engineers nearby"),
                job("b", ObiJobState.WORKING, steps = listOf(ObiStep(ObiStepKind.SEARCH, "Search", "q", true), ObiStep(ObiStepKind.WORK, "Think"))),
                job("c", ObiJobState.NEEDS_YOU, need = "Which day suits you?"),
            ),
            now, LocalCalendar.UTC,
        )
        assertEquals(listOf("c", "b", "a"), rows.map { it.id })
        assertEquals("Needs you", rows[0].status)
        assertEquals("Which day suits you?", rows[0].need)
        assertEquals("Working · step 2 of 2", rows[1].status)
        assertEquals("Done so far: Search", rows[1].did)
        assertEquals("Done today", rows[2].status)
        assertEquals("Three engineers nearby", rows[2].did)
    }
}
