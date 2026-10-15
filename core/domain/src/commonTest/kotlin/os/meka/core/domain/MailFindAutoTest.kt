package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Promises, receipts and renewals from email, slice 3: which finds add themselves, and the row on What Navilife may do. */
class MailFindAutoTest {
    private val cal = LocalCalendar.UTC
    /** Fri 16 Oct 2026. */
    private val today = CivilDate.toEpochDay(2026, 10, 16)
    private val now = cal.toEpochMs(today, 9 * 60)

    private fun renewal(id: String, merchant: String?, day: Long, state: MailFindState = MailFindState.OPEN, autoAtMs: Long? = null) = MailFind(
        id = id, kind = MailRenewalRules.KIND_RENEWAL, account = "Personal", merchant = merchant, renewDay = day, pence = 1099,
        obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.MONTHLY, receivedDay = today, subject = "Renews soon",
        state = state, decidedAtMs = 0L, autoAtMs = autoAtMs,
    )

    private fun promise(id: String, who: String?, what: String?, day: Long) = MailFind(
        id = id, kind = MailPromiseRules.KIND_PROMISE, account = "Personal", merchant = who, renewDay = day, pence = null,
        obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = today, subject = "Re: contract",
        state = MailFindState.OPEN, decidedAtMs = 0L, what = what, byStated = true,
    )

    @Test
    fun onlyNamedOpenUnmarkedFindsStillToComeAreAddedSoonestFirst() {
        val finds = listOf(
            renewal("r1", "Streamly", today + 18),
            renewal("r2", null, today + 5), // no company: waits for his tap
            renewal("r3", "Musicbox", today - 1), // already renewed
            renewal("r4", "Gymco", today + 3, autoAtMs = now - 1), // added on its own before (and undone)
            renewal("r5", "Newsly", today + 2, state = MailFindState.DISMISSED),
            promise("p1", "Sam Taylor", "Signed contract", today + 4),
            promise("p2", "Ann Lee", null, today + 1), // nothing said: waits
            MailFind(
                id = "f1", kind = MailFollowUpRules.KIND_FOLLOW_UP, account = "Personal", merchant = "Ann Lee", renewDay = today,
                pence = null, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = today,
                subject = null, state = MailFindState.OPEN, decidedAtMs = 0L,
            ), // his own email with no reply: never on its own
        )
        assertEquals(listOf("p1", "r1"), MailFindAutoRules.due(finds, emptyList(), now, cal, emptyList(), 0).map { it.id })
    }

    @Test
    fun alreadyOnTheRadarOrWaitingForIsLeftAloneAndADayHoldsAtMostTen() {
        val radar = listOf<Map<String, FieldValue>>(
            mapOf(
                ActionableFields.TITLE to "Streamly".fv(),
                ActionableFields.DUE_AT to cal.toEpochMs(today + 20, 540).fv(),
                ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ),
        )
        val finds = listOf(renewal("r1", "Streamly", today + 18))
        assertTrue(MailFindAutoRules.due(finds, radar, now, cal, emptyList(), 0).isEmpty())

        val many = (1..12).map { renewal("r$it", "Company $it", today + it) }
        assertEquals(10, MailFindAutoRules.due(many, emptyList(), now, cal, emptyList(), 0).size)
        assertEquals(3, MailFindAutoRules.due(many, emptyList(), now, cal, emptyList(), 7).size)
        assertTrue(MailFindAutoRules.due(many, emptyList(), now, cal, emptyList(), 10).isEmpty())
        val marked = many.take(4).map { it.copy(autoAtMs = now - 60_000) } + listOf(renewal("y", "Old", today + 9, autoAtMs = now - 86_400_000L))
        assertEquals(4, MailFindAutoRules.addedToday(marked, now, cal))
    }

    @Test
    fun theWordsAndOneEntryPerFind() {
        assertEquals("Added Streamly to Renewals · renews Tue 3 Nov", MailFindAutoRules.summary(renewal("r1", "Streamly", CivilDate.toEpochDay(2026, 11, 3)), today))
        assertEquals(
            "Added to Waiting for: Signed contract from Sam Taylor · chase Wed 21 Oct",
            MailFindAutoRules.summary(promise("p1", "Sam Taylor", "Signed contract", CivilDate.toEpochDay(2026, 10, 20)), today),
        )
        assertEquals(MailFindAutoRules.activityId("r1"), MailFindAutoRules.activityId("r1"))
        assertNotEquals(MailFindAutoRules.activityId("r1"), MailFindAutoRules.activityId("r2"))
        val notes = MailFindAutoRules.obligationFields(renewal("r1", "Streamly", today + 18), now, cal)[ActionableFields.NOTES]!!.textOrNull!!
        assertTrue("added for you" in notes)
        assertTrue("never pays or cancels" in notes)
        assertTrue("added for you" in MailFindAutoRules.promiseNote(promise("p1", "Sam", "Quote", today)))
    }

    @Test
    fun theRowIsOffUntilTurnedOnAndPausingHoldsIt() {
        assertFalse(MailFindAutoRules.read(null))
        assertTrue(MailFindAutoRules.read(MailFindAutoRules.switchFields(true, now)))
        val off = MailFindAutoRules.row(on = false, paused = false)
        assertEquals(MailFindAutoRules.OFF_LINE, off.line)
        assertEquals(MailFindAutoRules.TURN_ON, off.action)
        assertFalse(off.onItsOwn)
        val on = MailFindAutoRules.row(on = true, paused = false)
        assertTrue(on.onItsOwn)
        assertEquals(MailFindAutoRules.TURN_OFF, on.action)
        assertEquals(AutonomyRules.PAUSED_LINE, MailFindAutoRules.row(on = true, paused = true).line)
        val view = AutonomyRules.view(emptyMap(), AutonomySetting(false, null), cal, now, MailAutopilotSetting(on = true, changedAtMs = null), true)
        assertEquals(listOf(AutonomyRules.MAIL_AUTOPILOT, MailFindAutoRules.ENTITY_ID), view.sections.single { it.label == AutonomyRules.MAIL_LABEL }.rows.map { it.key })
        assertEquals("Navilife does 1 thing on its own · each with Undo and in Activity", view.summary)
    }
}
