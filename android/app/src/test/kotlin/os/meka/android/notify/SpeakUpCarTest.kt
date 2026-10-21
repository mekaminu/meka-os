package os.meka.android.notify

import android.content.res.Configuration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Speaking up slice 5: Android Auto's car mode counts as the car. */
class SpeakUpCarTest {
    @Test
    fun carModeIsTheCar() {
        assertTrue(SpeakUpVoice.isCarMode(Configuration.UI_MODE_TYPE_CAR))
        assertFalse(SpeakUpVoice.isCarMode(Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(SpeakUpVoice.isCarMode(Configuration.UI_MODE_TYPE_DESK))
        assertFalse(SpeakUpVoice.isCarMode(null))
    }

    @Test
    fun musicComesBackWithinHalfAMinute() {
        assertTrue(SpeakUpVoice.FOCUS_MAX_MS in 10_000L..30_000L)
    }
}
