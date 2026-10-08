package os.meka.android.designsystem

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import os.meka.core.domain.MotionChoice
import java.util.concurrent.ConcurrentHashMap

/**
 * Can't see the animations (Meka, 2026-10-08), (0b)/(0c): proves on a real emulator, in real time (no test clock),
 * that every kind of animation MEKA uses actually moves inside an activity composed through [mekaRecomposer] — with
 * the phone's animator scale at 1× and at 0 ("Remove animations"). Each probe is sampled three times while its
 * 2.4 s animation runs; the three values must differ (it moved) and the timed ones must not have landed yet (it
 * wasn't snapped to the end). The kinds: a tween (`animate*AsState`), a spring (`Animatable` in an effect), a
 * Transition (what `AnimatedVisibility` runs), an infinite transition, a frame loop (`withFrameMillis`, like the
 * ticker and the Day ring), a modifier-node animation (`animateContentSize`, which runs on the view's own coroutine
 * context, the prime suspect) and a lazy item placement (`animateItem`). The standard `setContent {}` at 1× is the
 * baseline. Runs in the `motion` workflow (Android emulator, API 34); logs every sample under the tag MekaMotion.
 */
class MotionClockTest {
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
    fun standardSetContentAnimatesAtNormalScale() = check(scale = "1", meka = false)

    @Test
    fun mekaRecomposerAnimatesAtNormalScale() = check(scale = "1", meka = true)

    @Test
    fun mekaRecomposerAnimatesWithRemoveAnimationsOn() = check(scale = "0", meka = true)

    private fun check(scale: String, meka: Boolean) {
        shell("settings put global animator_duration_scale $scale")
        SystemClock.sleep(300) // let the setting reach the process before the activity reads it
        val values = ConcurrentHashMap<String, Float>()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val content: @Composable () -> Unit = { MekaTheme { Probes(values) } }
                if (meka) activity.setContent(parent = activity.mekaRecomposer(), content = content)
                else activity.setContent(content = content)
            }
            // Wait for the first frames, then sample while the 2.4 s animations run.
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (values["started"] == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5)
            assertTrue("the probes never composed", values["started"] != null)
            val t0 = SystemClock.uptimeMillis()
            val samples = listOf(400L, 1000L, 1600L).map { at ->
                SystemClock.sleep((t0 + at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                HashMap(values)
            }
            val label = "scale=$scale ${if (meka) "mekaRecomposer" else "standard"}"
            PROBES.forEach { key -> Log.i("MekaMotion", "$label $key: ${samples.map { it[key] }}") }
            assertFalse("$label: MEKA's motion is reduced", values["reduced"] == 1f)
            val frozen = PROBES.filter { key ->
                val seen = samples.map { it[key] }
                seen.any { it == null } || seen.distinct().size < 3
            }
            assertTrue("$label: frozen ${frozen.map { k -> "$k=${samples.map { it[k] }}" }}", frozen.isEmpty())
            val landed = TIMED.filter { key -> samples.last()[key]!! >= 0.999f }
            assertTrue("$label: snapped to the end ${landed.map { k -> "$k=${samples.map { it[k] }}" }}", landed.isEmpty())
        } finally {
            scenario.close()
        }
    }

    private fun prefs() = context.getSharedPreferences("meka_ui", Context.MODE_PRIVATE)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private companion object {
        /** Probes that run 0 → 1 over MotionClockProbe.MS (so mid-way they're strictly between). */
        val TIMED = listOf("tween", "transition", "infinite", "contentSize")
        val PROBES = TIMED + listOf("spring", "frames", "lazyItem")
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun Probes(values: ConcurrentHashMap<String, Float>) {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        values["started"] = 1f
        go = true
        while (true) withFrameMillis { values["frames"] = (it - start).toFloat() }
    }
    val reduced = Meka.reducedMotion
    SideEffect { values["reduced"] = if (reduced) 1f else 0f }

    val tweened by animateFloatAsState(if (go) 1f else 0f, tween(MotionClockProbe.MS, easing = LinearEasing), label = "tween")
    val sprung = remember { Animatable(0f) }
    LaunchedEffect(go) { if (go) sprung.animateTo(1f, spring(dampingRatio = 1f, stiffness = 2f)) }
    val looping by rememberInfiniteTransition(label = "infinite")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(MotionClockProbe.MS, easing = LinearEasing), RepeatMode.Restart), label = "infinite")
    var order by remember { mutableStateOf(listOf("x", "a", "b")) }
    // "x" stays first, so the list keeps its scroll position and only "a" moves (down one row).
    LaunchedEffect(go) { if (go) order = listOf("x", "b", "a") }

    Column(
        Modifier.fillMaxWidth().drawBehind {
            values["tween"] = tweened
            values["spring"] = sprung.value
            values["infinite"] = looping
        },
    ) {
        AnimatedVisibility(go, enter = fadeIn(tween(MotionClockProbe.MS))) {
            val shown by transition.animateFloat({ tween(MotionClockProbe.MS, easing = LinearEasing) }, label = "shown") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            Box(Modifier.size(10.dp).drawBehind { values["transition"] = shown })
        }
        Box(
            // onSizeChanged sits outside animateContentSize, so it sees the animated size, not the target.
            Modifier.onSizeChanged { values["contentSize"] = (it.width - 10f * density()) / (300f * density()) }
                .animateContentSize(tween(MotionClockProbe.MS, easing = LinearEasing))
                .width(if (go) 310.dp else 10.dp).height(10.dp),
        )
        LazyColumn(Modifier.height(300.dp)) {
            items(order, key = { it }) { key ->
                Box(
                    Modifier.animateItem(fadeInSpec = null, placementSpec = tween(MotionClockProbe.MS, easing = LinearEasing), fadeOutSpec = null)
                        .fillMaxWidth().height(40.dp)
                        .onGloballyPositioned { if (key == "a") values["lazyItem"] = it.positionInRoot().y },
                )
            }
        }
    }
}

private object MotionClockProbe {
    const val MS = 2400
}

private fun density(): Float = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
