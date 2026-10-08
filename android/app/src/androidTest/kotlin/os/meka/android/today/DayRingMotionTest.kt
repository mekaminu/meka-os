package os.meka.android.today

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.MotionPrefs
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayRingRules
import os.meka.core.domain.MotionChoice
import os.meka.core.domain.MotionRules

/**
 * Can't see the animations (Meka, 2026-10-08), part 3: with the phone's "Remove animations" on (animator duration
 * scale 0, as Samsung's power saving and Developer options set it) and MEKA's Motion on Expressive, the Day ring still
 * plays its full opening: it is still drawing in partway through and lands only after at least 900 ms.
 * Runs on the Fold or an emulator (`./gradlew :android:app:connectedDebugAndroidTest`); CI compiles it.
 */
class DayRingMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var savedScale = "1"
    private var savedMotion: String? = null

    @Before
    fun removeAnimations() {
        savedScale = shell("settings get global animator_duration_scale").trim().takeUnless { it.isEmpty() || it == "null" } ?: "1"
        shell("settings put global animator_duration_scale 0")
        savedMotion = prefs().getString("motion", null)
        MotionPrefs.set(context, MotionChoice.EXPRESSIVE)
    }

    @After
    fun restore() {
        shell("settings put global animator_duration_scale $savedScale")
        prefs().edit().apply { if (savedMotion == null) remove("motion") else putString("motion", savedMotion) }.commit()
    }

    @Test
    fun expressivePlaysTheDayRingWithRemoveAnimationsOn() {
        assertEquals(0f, MotionPrefs.animatorScale(context))
        val reduced = MotionRules.reduced(MotionChoice.EXPRESSIVE, MotionPrefs.systemOff(context))
        assertFalse("Expressive must not be stilled by the phone's scale", reduced)
        val play = DayRingRules.play(lastFullEpochDay = null, todayEpochDay = 20_000, reduced = reduced)
        assertEquals(DayRingPlay.FULL, play)
        val ring = DayRing(
            listOf(DayArc("e-1", DayArcKind.EVENT, 9 * 60, 10 * 60, past = false), DayArc("t-1", DayArcKind.TASK, 14 * 60, 15 * 60, past = false)),
            nowMinute = 8 * 60, freeMinutes = 300, toDo = 3,
        )
        val total = MotionMath.dayRingTotalMs(ring.arcs.size, play, expressive = true)
        assertTrue("the opening runs at least 900 ms (was $total)", total >= 900)

        var played = false
        compose.mainClock.autoAdvance = false
        compose.setContent { MekaTheme { DayRingHero(ring, play, played = { played = true }) } }
        compose.mainClock.advanceTimeBy(300)
        assertFalse("the ring is still drawing in at 300 ms", played)
        compose.mainClock.advanceTimeBy(total + 200)
        assertTrue("the ring has landed", played)
    }

    private fun prefs() = context.getSharedPreferences("meka_ui", Context.MODE_PRIVATE)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
