package os.meka.android.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.mekaRecomposer
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AlarmRing
import os.meka.core.domain.AlarmRules
import kotlin.math.roundToInt

/**
 * The ringing wake alarm, full screen over the lock screen (Alarms, slice 1): the time large, what it's for, Snooze
 * (9 min) and a slider to dismiss whose brass thumb glows, breathing. Dismiss stops it on every device and then the
 * morning brief springs up in MEKA (after the phone is unlocked). Motion Off: the glow holds still, the thumb jumps.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as MekaApplication
        val firedId = intent?.getStringExtra(AlarmRingService.EXTRA_ID)
        if (intent?.action == ACTION_DISMISS) {
            // "Dismiss" on the notification: no screen, straight to the brief.
            val id = firedId ?: app.core.nextAlarm.value?.id
            if (id != null) dismiss(id) else finish()
            return
        }
        enableEdgeToEdge()
        setContent(parent = mekaRecomposer()) {
            MekaTheme {
                val ring by app.core.nextAlarm.collectAsState()
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
                // Snoozed or dismissed (here, on the notification or on the Mac), or rung out: the screen goes.
                LaunchedEffect(ring, now) {
                    if (!AlarmRouting.shouldRing(ring, firedId ?: ring?.id, System.currentTimeMillis())) finish()
                }
                ring?.let { r -> AlarmScreen(r, onSnooze = { snooze(r.id) }, onDismiss = { dismiss(r.id) }) }
            }
        }
    }

    private fun snooze(id: String) {
        val app = application as MekaApplication
        background { app.core.snoozeAlarm(id) }
        AlarmRingService.stop(this)
        finish()
    }

    private fun dismiss(id: String) {
        val app = application as MekaApplication
        // Only the wake alarm brings up the brief; a quick alarm or a timer just stops (Alarms, slice 2).
        val opensBrief = app.core.nextAlarm.value?.takeIf { it.id == id }?.opensBrief ?: true
        background { app.core.dismissAlarm(id) }
        AlarmRingService.stop(this)
        if (!opensBrief) {
            finish()
            return
        }
        val brief = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BRIEF)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            startActivity(brief)
            finish()
            return
        }
        // Ask to unlock (fingerprint), then the brief; cancelled, the alarm is still dismissed.
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { startActivity(brief); finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
    }

    /** The core write outlives the activity, which finishes at once. */
    private fun background(block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { runCatching { block() } }
    }

    companion object {
        const val ACTION_DISMISS = "os.meka.alarm.DISMISS"

        fun intent(context: Context, id: String?): Intent = Intent(context, AlarmActivity::class.java)
            .putExtra(AlarmRingService.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun AlarmScreen(ring: AlarmRing, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Column(
        Modifier.fillMaxSize().background(Meka.colors.background).safeDrawingPadding()
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(ring.title.uppercase(), style = MekaType.caption, color = Meka.colors.accent)
        Text(
            ring.timeLabel,
            style = MekaType.greeting.copy(fontSize = 96.sp, fontWeight = FontWeight(300), letterSpacing = (-0.03).em, fontFeatureSettings = "tnum", lineHeight = 104.sp),
            color = Meka.colors.textPrimary,
        )
        Text(ring.line, style = MekaType.body, color = Meka.colors.textSecondary)
        AnimatedContent(
            targetState = ring.snoozeLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "alarm-snooze-line",
        ) { line -> Text(line ?: "", style = MekaType.caption, color = Meka.colors.textTertiary) }
        Spacer(Modifier.weight(1f))
        Text(
            "Snooze ${AlarmRules.SNOOZE_MIN} min", style = MekaType.itemTitle, color = Meka.colors.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .clickable(role = Role.Button) { haptics.tick(); onSnooze() }
                .padding(horizontal = MekaSpace.xl, vertical = MekaSpace.m),
        )
        Spacer(Modifier.height(MekaSpace.xl))
        DismissSlider(onDismiss = { haptics.light(); onDismiss() })
    }
}

/**
 * Slide to dismiss: a pill track with a brass thumb that glows and breathes (the empty states' breath); dragging it
 * past [AlarmRouting.DISMISS_AT] of the way dismisses, short of that it springs back. The label fades as it goes.
 * Screen readers get a plain "Dismiss alarm" action.
 */
@Composable
private fun DismissSlider(onDismiss: () -> Unit) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    val thumb = 64.dp
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(thumb).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .semantics {
                contentDescription = "Slide to dismiss"
                onClick(label = "Dismiss alarm") { onDismiss(); true }
            },
    ) {
        val travel = with(LocalDensity.current) { (maxWidth - thumb).toPx() }.coerceAtLeast(1f)
        val fraction = (offset.value / travel).coerceIn(0f, 1f)
        Text(
            "Slide to dismiss", style = MekaType.itemMeta, color = Meka.colors.textSecondary.copy(alpha = 1f - fraction),
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.size(thumb)
                .pointerInput(travel) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val f = offset.value / travel
                            scope.launch {
                                if (AlarmRouting.dismissed(f) && !done) {
                                    done = true
                                    offset.animateTo(travel, MekaMotion.complete(reduced))
                                    onDismiss()
                                } else {
                                    offset.animateTo(0f, MekaMotion.expand(reduced))
                                }
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(0f, travel)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val b = MotionMath.breath(elapsed, reduced)
                val r = size.minDimension / 2
                // The glow breathes outward around the thumb, brighter as it nears the end.
                drawCircle(accent.copy(alpha = 0.22f * MotionMath.breathGlow(b) + 0.2f * fraction), radius = r * (0.9f + 0.1f * MotionMath.breathScale(b)))
                drawCircle(accent, radius = r * 0.78f)
            }
            Text("›", style = MekaType.itemTitle, color = Meka.colors.onAccent)
        }
    }
}
