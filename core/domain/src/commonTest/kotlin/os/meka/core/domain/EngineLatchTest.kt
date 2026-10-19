package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The engine fallback latch (small refinement, Meka 2026-10-19). */
class EngineLatchTest {
    private val min = 60_000L

    @Test
    fun aFailureSwitchesForFifteenMinutesThenAProbe() {
        val l = EngineLatchRules.failed(EngineLatch(), 0, timeout = false)
        assertTrue(l.switched)
        assertTrue(EngineLatchRules.useFallback(l, 14 * min))
        assertFalse(EngineLatchRules.useFallback(l, 15 * min)) // the probe
        val ok = EngineLatchRules.worked(l, 16 * min)
        assertFalse(ok.switched)
        assertEquals(16 * min, EngineLatchRules.switchedForMs(ok, 99 * min))
        assertFalse(EngineLatchRules.useFallback(ok, 17 * min))
    }

    @Test
    fun aTalkSessionLongerThanTheHoldKeepsTheFallback() {
        val l = EngineLatchRules.failed(EngineLatch(), 0, timeout = false)
        assertTrue(EngineLatchRules.useFallback(l, 40 * min, sessionOpen = true))
        assertFalse(EngineLatchRules.useFallback(l, 40 * min, sessionOpen = false))
        // A session ending early still holds for 15 minutes.
        assertTrue(EngineLatchRules.useFallback(l, 3 * min, sessionOpen = false))
    }

    @Test
    fun twoTimeoutsInARowSwitchOneDoesNot() {
        val one = EngineLatchRules.failed(EngineLatch(), 0, timeout = true)
        assertFalse(one.switched)
        assertFalse(EngineLatchRules.useFallback(one, 1))
        // A success in between resets the count.
        val reset = EngineLatchRules.worked(one, 1)
        assertFalse(EngineLatchRules.failed(reset, 2, timeout = true).switched)
        assertTrue(EngineLatchRules.failed(one, 2, timeout = true).switched)
    }

    @Test
    fun aFailedProbeStartsTheHoldOverAndKeepsTheSwitchStart() {
        val l = EngineLatchRules.failed(EngineLatch(), 0, timeout = false)
        val again = EngineLatchRules.failed(l, 20 * min, timeout = true)
        assertTrue(EngineLatchRules.useFallback(again, 34 * min))
        assertFalse(EngineLatchRules.useFallback(again, 35 * min))
        assertEquals(30 * min, EngineLatchRules.switchedForMs(again, 30 * min))
        assertEquals(null, EngineLatchRules.switchedForMs(EngineLatch(), 5))
    }

    @Test
    fun theDeviceLatchIsSharedAndHoldsForTheSession() {
        val d = ListenLatch()
        assertFalse(d.resting(0))
        d.sessionOpen = true
        d.failed(0, timeout = false)
        assertTrue(d.resting(30 * min))
        d.sessionOpen = false
        assertFalse(d.resting(30 * min))
        d.worked(31 * min)
        assertFalse(d.latch.switched)
    }

    @Test
    fun durationsReadPlainly() {
        assertEquals("under a minute", EngineLatchRules.duration(30_000))
        assertEquals("15 min", EngineLatchRules.duration(15 * min))
        assertEquals("2 h", EngineLatchRules.duration(120 * min))
        assertEquals("1 h 10 min", EngineLatchRules.duration(70 * min))
    }

    @Test
    fun aRestingOnlineRecogniserListensOnTheDeviceWhenItCan() {
        assertEquals(ListenEngine.ON_DEVICE, ListenRules.engine(hasSignal = true, onlineAvailable = true, onDeviceAvailable = true, onlineResting = true))
        assertEquals(ListenEngine.ONLINE, ListenRules.engine(hasSignal = true, onlineAvailable = true, onDeviceAvailable = false, onlineResting = true))
        assertEquals(ListenEngine.ONLINE, ListenRules.engine(hasSignal = true, onlineAvailable = true, onDeviceAvailable = true))
        assertEquals("Listening · on this phone · Google's speech is resting", ListenRules.listeningLine(ListenEngine.ON_DEVICE, ListenRules.GOOGLE, resting = true))
        assertEquals("Listening · on this phone, no signal", ListenRules.listeningLine(ListenEngine.ON_DEVICE, ListenRules.GOOGLE))
    }
}
