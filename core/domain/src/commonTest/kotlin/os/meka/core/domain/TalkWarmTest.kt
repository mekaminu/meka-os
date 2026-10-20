package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orb speed pass, slice 5: when the device and the server warm the AI and voice connections again. */
class TalkWarmTest {
    @Test
    fun theFirstIsAlwaysDue() {
        assertTrue(TalkWarmRules.due(null, 1_000L))
        assertTrue(TalkWarmRules.serverDue(null, 1_000L))
    }

    @Test
    fun theDeviceWaitsTwentySeconds() {
        assertFalse(TalkWarmRules.due(0L, TalkWarmRules.DEVICE_GAP_MS - 1))
        assertTrue(TalkWarmRules.due(0L, TalkWarmRules.DEVICE_GAP_MS))
    }

    @Test
    fun theServerWaitsFifteenSeconds() {
        assertFalse(TalkWarmRules.serverDue(0L, TalkWarmRules.SERVER_GAP_MS - 1))
        assertTrue(TalkWarmRules.serverDue(0L, TalkWarmRules.SERVER_GAP_MS))
    }

    @Test
    fun aClockJumpBackIsDue() {
        assertTrue(TalkWarmRules.due(50_000L, 10_000L))
        assertTrue(TalkWarmRules.serverDue(50_000L, 10_000L))
    }

    @Test
    fun theDeviceGapIsLongerThanTheServers() {
        assertTrue(TalkWarmRules.DEVICE_GAP_MS > TalkWarmRules.SERVER_GAP_MS)
    }
}
