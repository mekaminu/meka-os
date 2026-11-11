package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recordings with both voices: when a live call is recorded, and what Obi says about it. */
class CallRecordingRulesTest {
    @Test
    fun aLiveCallIsRecordedOnlyWhenItCanBeKept() {
        assertTrue(CallRecordingRules.records(7, hasStore = true))
        assertFalse(CallRecordingRules.records(0, hasStore = true))
        assertFalse(CallRecordingRules.records(7, hasStore = false))
    }

    @Test
    fun obiSaysSoInHisOpening() {
        assertEquals("Hi, this is Obi, Meka's AI assistant. This call is recorded.", CallRecordingRules.opening(true))
        assertEquals(ObiCallRules.OPENING, CallRecordingRules.opening(false))
        val recorded = LiveCallRules.instruction(false, LiveCallRules.DEFAULT_KNOWS, "Alex", null, null, recorded = true)
        assertTrue("Start by saying exactly: \"${CallRecordingRules.opening(true)}\"" in recorded, recorded)
        val plain = LiveCallRules.instruction(false, LiveCallRules.DEFAULT_KNOWS, "Alex", null, null)
        assertFalse(CallRecordingRules.RECORDED_LINE in plain)
    }

    @Test
    fun onlyAFinishedRecordingIsKept() {
        assertTrue(CallRecordingRules.completed("completed"))
        assertTrue(CallRecordingRules.completed(" Completed "))
        assertFalse(CallRecordingRules.completed("absent"))
        assertFalse(CallRecordingRules.completed(null))
    }

    @Test
    fun theRecordingsNoteSaysLiveCallsAreRecorded() {
        assertTrue("live calls with both voices" in CallsSettingsRules.RECORDINGS_NOTE)
        assertTrue("Obi tells callers" in CallsSettingsRules.RECORDINGS_NOTE)
    }
}
