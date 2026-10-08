package os.meka.android.alarm

import os.meka.core.domain.AlarmRing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmRoutingTest {
    private val ring = AlarmRing("wake.d20735", 1_000_000L, "06:45", "Wake up", "Standup at 08:00", false, null)

    @Test
    fun theVolumeRisesGentlyToFullOverHalfAMinute() {
        assertEquals(AlarmRouting.START_VOLUME, AlarmRouting.volumeAt(0))
        assertTrue(AlarmRouting.volumeAt(10_000) < 0.2f)
        assertTrue(AlarmRouting.volumeAt(20_000) > AlarmRouting.volumeAt(10_000))
        assertEquals(1f, AlarmRouting.volumeAt(30_000))
        assertEquals(1f, AlarmRouting.volumeAt(90_000))
    }

    @Test
    fun itRingsOnlyForThatAlarmFromItsTimeForTenMinutes() {
        assertTrue(AlarmRouting.shouldRing(ring, ring.id, 1_000_000L))
        assertTrue(AlarmRouting.shouldRing(ring, null, 1_000_000L - 30_000L))
        assertFalse(AlarmRouting.shouldRing(ring, ring.id, 1_000_000L - 120_000L))
        assertFalse(AlarmRouting.shouldRing(ring, ring.id, 1_000_000L + 10 * 60_000L))
        assertFalse(AlarmRouting.shouldRing(ring, "wake.d20736", 1_000_000L))
        assertFalse(AlarmRouting.shouldRing(null, ring.id, 1_000_000L))
    }

    @Test
    fun theSliderDismissesNearTheEndAndTheSignatureFollowsTheTime() {
        assertFalse(AlarmRouting.dismissed(0.5f))
        assertTrue(AlarmRouting.dismissed(0.9f))
        assertEquals("Wake up · 06:45", AlarmRouting.title(ring))
        assertEquals("wake.d20735@1000000", AlarmRouting.signature(ring))
        assertEquals("", AlarmRouting.signature(null))
        assertTrue(AlarmRouting.NOTIFICATION_ID < 0)
    }
}
