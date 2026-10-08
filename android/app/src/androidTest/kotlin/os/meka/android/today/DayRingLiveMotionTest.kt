package os.meka.android.today

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MotionPrefs
import os.meka.android.designsystem.mekaRecomposer
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.MotionChoice
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Living Today (slice 1), on a real emulator in real time: once the opening has landed, the Day ring's gold second
 * hand sweeps round (about 6° a second, smoothly: many frames, each a small step) and its brass edge breathes. Samples
 * the hand's angle and the glow that the live layer draws at 0, 0.6 and 1.2 s through MEKA's recomposer, at the
 * phone's 1× and with "Remove animations" on. Logs every sample under MekaMotion (the motion workflow's annotation).
 */
class DayRingLiveMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var savedScale = "1"
    private var savedMotion: String? = null

    @Before
    fun save() {
        savedScale = shell("settings get global animator_duration_scale").trim().takeUnless { it.isEmpty() || it == "null" } ?: "1"
        savedMotion = prefs().getString("motion", null)
        MotionPrefs.set(context, MotionChoice.EXPRESSIVE)
    }

    @After
    fun restore() {
        shell("settings put global animator_duration_scale $savedScale")
        prefs().edit().apply { if (savedMotion == null) remove("motion") else putString("motion", savedMotion) }.commit()
    }

    @Test
    fun theHandSweepsAndTheEdgeBreathesAtNormalScale() = check("1")

    @Test
    fun theHandSweepsAndTheEdgeBreathesWithRemoveAnimationsOn() = check("0")

    private fun check(scale: String) {
        shell("settings put global animator_duration_scale $scale")
        SystemClock.sleep(300)
        val ring = DayRing(listOf(DayArc("e-1", DayArcKind.EVENT, 9 * 60, 10 * 60, past = false)), nowMinute = 8 * 60, freeMinutes = 300, toDo = 3)
        val last = AtomicReference<Pair<Float, Float>?>(null)
        val frames = AtomicInteger(0)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent(parent = activity.mekaRecomposer()) {
                    MekaTheme {
                        DayRingHero(ring, DayRingPlay.STILL, played = {}, onLiveFrame = { hand, glow ->
                            last.set(hand to glow)
                            frames.incrementAndGet()
                        })
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (last.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5)
            assertTrue("the live layer never drew", last.get() != null)
            SystemClock.sleep(300) // past the hand's fade-in
            val f0 = frames.get()
            val t0 = SystemClock.uptimeMillis()
            val samples = listOf(0L, 600L, 1200L).map { at ->
                SystemClock.sleep((t0 + at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                last.get()!!
            }
            val drawn = frames.get() - f0
            Log.i("MekaMotion", "scale=$scale living ring hand: ${samples.map { it.first }} glow: ${samples.map { it.second }} frames in 1.2 s: $drawn")
            val steps = samples.zipWithNext { a, b -> ((b.first - a.first) % 360f + 360f) % 360f }
            assertTrue("scale=$scale: the hand should move ~3.6° each 0.6 s, moved $steps", steps.all { it in 2f..6f })
            assertTrue("scale=$scale: the edge should breathe, glow ${samples.map { it.second }}", samples.map { it.second }.distinct().size == 3)
            assertTrue("scale=$scale: a smooth sweep, not a tick ($drawn frames in 1.2 s)", drawn >= 30)
        } finally {
            scenario.close()
        }
    }

    private fun prefs() = context.getSharedPreferences("meka_ui", Context.MODE_PRIVATE)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
