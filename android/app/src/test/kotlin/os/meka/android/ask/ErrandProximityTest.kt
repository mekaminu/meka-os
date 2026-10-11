package os.meka.android.ask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errands by location, slice 2: "I'm here" asks the precise provider first and only uses a fix taken just now. */
class ErrandProximityTest {
    @Test
    fun imHereAsksGpsFirstThenFusedThenTheNetwork() {
        assertEquals("gps", ErrandProximity.pickProvider(listOf("network", "fused", "gps", "passive")))
        assertEquals("fused", ErrandProximity.pickProvider(listOf("network", "fused", "passive")))
        assertEquals("network", ErrandProximity.pickProvider(listOf("passive", "network")))
        assertNull(ErrandProximity.pickProvider(listOf("passive")))
    }

    @Test
    fun aFixThePhoneAlreadyHasStandsForHereOnlyWithinAMinute() {
        assertTrue(ErrandProximity.freshEnough(0))
        assertTrue(ErrandProximity.freshEnough(ErrandProximity.LAST_KNOWN_FRESH_MS))
        assertFalse(ErrandProximity.freshEnough(ErrandProximity.LAST_KNOWN_FRESH_MS + 1))
        assertFalse(ErrandProximity.freshEnough(-5))
    }

    @Test
    fun eachPlaceHasItsOwnNotification() {
        assertEquals(ErrandProximity.notificationId("tesco"), ErrandProximity.notificationId("tesco"))
        assertNotEquals(ErrandProximity.notificationId("tesco"), ErrandProximity.notificationId("boots"))
    }
}
