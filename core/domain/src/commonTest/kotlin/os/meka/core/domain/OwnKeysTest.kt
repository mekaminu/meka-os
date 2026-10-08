package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnKeysTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val d = world.device("android")
    private val r = Renewals(d.replica, { "x" }, { world.clock.nowMs })

    private fun at(y: Int, m: Int, day: Int) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + 10 * 3_600_000L }

    private fun write(key: OwnKey) =
        d.replica.commitLocal(EntityTypes.OBLIGATION, key.id, OwnKeyRules.fields(key, world.clock.nowMs, LocalCalendar.UTC))

    @Test
    fun theOutlookSecretWaitsUnderLaterAndComesIntoNeedsYouFromTheTwentiethOfSeptember2028() {
        at(2026, 10, 8)
        write(OwnKeyRules.OUTLOOK)
        val item = r.items().single()
        assertEquals("keyoutlooksecret", item.id)
        assertEquals("Renew MEKA's Outlook sign-in secret", item.title)
        assertEquals("MEKA · Outlook calendar", item.subject)
        assertEquals(16, item.leadDays)
        assertEquals("expires 6 Oct 2028", item.meta)
        assertEquals("Renewed", item.doneLabel)
        assertEquals("Stop tracking", item.stopLabel)
        assertEquals("Every 2 years on 6 Oct", item.repeatLabel)
        assertEquals(listOf("keyoutlooksecret"), r.view().later.map { it.id })
        assertNull(r.view().dueLine)
        assertTrue(item.notes!!.contains("meka-os-dev/oauth/microsoft"))

        at(2028, 9, 19)
        assertEquals(RenewalState.LATER, r.items().single().state)
        at(2028, 9, 20)
        assertEquals(RenewalState.SOON, r.items().single().state)
        assertEquals("1 renewal due", r.view().dueLine)
        assertEquals("expires Fri 6 Oct", r.items().single().meta)
        at(2028, 10, 7)
        assertEquals(RenewalState.OVERDUE, r.items().single().state)
    }

    @Test
    fun renewedMovesItOnTwoYearsAndTheAiKeyOne() {
        at(2028, 9, 25)
        write(OwnKeyRules.OUTLOOK)
        r.done(OwnKeyRules.OUTLOOK.id)
        assertEquals(CivilDate.toEpochDay(2030, 10, 6), r.items().single().dueDay)
        assertEquals(RenewalState.LATER, r.items().single().state)

        at(2027, 9, 21)
        write(OwnKeyRules.AI)
        val ai = r.items().single { it.id == OwnKeyRules.AI.id }
        assertEquals(RenewalState.SOON, ai.state)
        assertEquals(17, ai.leadDays)
        r.done(ai.id)
        assertEquals(CivilDate.toEpochDay(2028, 10, 7), r.items().single { it.id == OwnKeyRules.AI.id }.dueDay)
    }

    @Test
    fun theyReachTheOtherDeviceAndMekasOwnDateWins() {
        at(2026, 10, 8)
        write(OwnKeyRules.OUTLOOK)
        val mac = world.device("mac")
        d.sync(); mac.sync()
        val onMac = Renewals(mac.replica, { "y" }, { world.clock.nowMs })
        onMac.setDue(OwnKeyRules.OUTLOOK.id, CivilDate.toEpochDay(2028, 9, 1))
        mac.sync(); d.sync()
        assertEquals(CivilDate.toEpochDay(2028, 9, 1), r.items().single().dueDay)
    }

    @Test
    fun everyKeyHasAWireSafeIdAndShowsBeforeItEnds() {
        assertEquals(OwnKeyRules.ALL.size, OwnKeyRules.ALL.map { it.id }.toSet().size)
        for (k in OwnKeyRules.ALL) {
            assertTrue(k.id.all { it in 'a'..'z' || it in '0'..'9' }, k.id)
            assertTrue(k.leadDays in 1..Renewals.MAX_LEAD_DAYS, k.id)
            assertEquals(OwnKeyRules.SOURCE, OwnKeyRules.fields(k, 0, LocalCalendar.UTC)[ActionableFields.PROVENANCE_SOURCE]?.let { (it as os.meka.core.sync.FieldValue.Text).value })
        }
    }
}
