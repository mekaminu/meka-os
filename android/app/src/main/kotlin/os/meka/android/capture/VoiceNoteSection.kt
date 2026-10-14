package os.meka.android.capture

import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.VoiceNote
import os.meka.core.domain.VoiceNoteRules

/** Where a voice note being made is up to (voice notes into actions, slice 1). */
sealed interface VoiceNoteState {
    data object Idle : VoiceNoteState
    /** The microphone isn't allowed (asked, refused). */
    data object NeedsMic : VoiceNoteState
    /** The microphone couldn't be opened (another app has it). */
    data object CouldNotStart : VoiceNoteState
    data class Recording(val elapsedMs: Long, val level: Float, val paused: Boolean) : VoiceNoteState
    data object Finishing : VoiceNoteState
    /** Kept: [noWords] says why there are no words to read (null: there are). */
    data class Done(val note: VoiceNote, val noWords: String?) : VoiceNoteState
    data object TooShort : VoiceNoteState
    data object Cancelled : VoiceNoteState
}

/**
 * One voice note from the capture sheet: records it ([VoiceNoteRecorder]), then keeps it ([VoiceNoteStore]) on
 * [appScope], so a note whose sheet is closed mid-recording is still stopped and kept. The sheet only watches [state].
 */
class VoiceNoteSession(private val context: Context, private val appScope: CoroutineScope, private val now: () -> Long) {
    private val _state = MutableStateFlow<VoiceNoteState>(VoiceNoteState.Idle)
    val state: StateFlow<VoiceNoteState> = _state
    private var recorder: VoiceNoteRecorder? = null
    private var id: String? = null
    private var startedAt = 0L

    val recording: Boolean get() = _state.value is VoiceNoteState.Recording

    fun start() {
        if (_state.value is VoiceNoteState.Recording || _state.value is VoiceNoteState.Finishing) return
        if (!VoiceNoteRecorder.micAllowed(context)) { _state.value = VoiceNoteState.NeedsMic; return }
        startedAt = now()
        val noteId = VoiceNoteRules.id(startedAt)
        id = noteId
        val part = VoiceNoteStore.partFile(context, noteId)
        val r = VoiceNoteRecorder(context.applicationContext, part)
        recorder = r
        val started = r.start(object : VoiceNoteRecorder.Listener {
            override fun onProgress(elapsedMs: Long, level: Float) {
                if (_state.value is VoiceNoteState.Recording) _state.value = VoiceNoteState.Recording(elapsedMs, level, r.isPaused)
            }
            override fun onLimit() { _state.value = VoiceNoteState.Finishing }
            override fun onFinished(segments: List<String>, recognizerFailed: Boolean, recordingOk: Boolean, durationMs: Long) =
                finished(noteId, segments, recognizerFailed, recordingOk, durationMs)
        })
        _state.value = if (started) VoiceNoteState.Recording(0, 0f, false) else { part.delete(); VoiceNoteState.CouldNotStart }
    }

    fun pause() {
        val s = _state.value as? VoiceNoteState.Recording ?: return
        recorder?.pause(); _state.value = s.copy(paused = true, level = 0f)
    }

    fun resume() {
        val s = _state.value as? VoiceNoteState.Recording ?: return
        recorder?.resume(); _state.value = s.copy(paused = false)
    }

    /** Done: stop and keep it. */
    fun stop() {
        if (_state.value !is VoiceNoteState.Recording) return
        _state.value = VoiceNoteState.Finishing
        recorder?.stop()
    }

    /** Cancel: stop and throw it away. */
    fun cancel() {
        recorder?.cancel()
        recorder = null
        id?.let { VoiceNoteStore.partFile(context, it).delete() }
        _state.value = VoiceNoteState.Cancelled
    }

    private fun finished(noteId: String, segments: List<String>, recognizerFailed: Boolean, recordingOk: Boolean, durationMs: Long) {
        recorder = null
        if (!VoiceNoteRules.longEnough(durationMs)) {
            VoiceNoteStore.partFile(context, noteId).delete()
            _state.value = VoiceNoteState.TooShort
            return
        }
        val words = VoiceNoteRules.words(segments, recognizerFailed)
        val note = VoiceNote(noteId, startedAt, durationMs, words, VoiceNoteRules.transcript(segments))
        val oldAndroid = Build.VERSION.SDK_INT < 33
        appScope.launch {
            // A recording that couldn't be finished is dropped; the note (its words) is still kept.
            if (!recordingOk) VoiceNoteStore.partFile(context, noteId).delete()
            VoiceNoteStore.keep(context, note)
            _state.value = VoiceNoteState.Done(note, VoiceNoteRules.noWordsLine(words, oldAndroid))
        }
    }
}

/**
 * The capture sheet while a voice note records: VOICE NOTE, a brass dot that pulses while recording (still when paused),
 * "Recording · 0:42" (the stop time near the end), a strip of thin brass bars showing the last couple of seconds of his
 * voice, and Pause/Resume (tick haptic) · Done (light haptic) · Cancel. Then "Turning it into words…" over the
 * thinking shimmer. The line is a polite live region. Reduced motion: the dot stays still; the bars still follow the
 * voice (they are what it hears, not decoration) but without easing.
 */
@Composable
internal fun VoiceNoteRecordingView(
    state: VoiceNoteState,
    levels: List<Float>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onAllowMic: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().testTag("voice-note"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("VOICE NOTE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(targetState = stepOf(state), animationSpec = MekaMotion.appear(reduced), label = "voice-note-step") { step ->
            when (step) {
                0 -> {
                    val rec = state as? VoiceNoteState.Recording ?: VoiceNoteState.Recording(0, 0f, false)
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            RecordingDot(paused = rec.paused)
                            Text(
                                VoiceNoteRules.recordingLine(rec.elapsedMs, rec.paused), style = MekaType.body,
                                color = if (rec.elapsedMs >= VoiceNoteRules.WARN_MS) Meka.colors.accent else Meka.colors.textPrimary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        LevelStrip(levels)
                        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                            Pill(if (rec.paused) VoiceNoteRules.RESUME else VoiceNoteRules.PAUSE, filled = false) { if (rec.paused) onResume() else onPause() }
                            Pill(VoiceNoteRules.STOP, filled = true, onClick = onDone)
                            Text(
                                VoiceNoteRules.CANCEL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onCancel)
                                    .semantics { contentDescription = "Cancel the voice note: nothing is kept" }
                                    .minTouch().padding(horizontal = MekaSpace.xs),
                            )
                        }
                    }
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(VoiceNoteRules.FINISHING, style = MekaType.body, color = Meka.colors.textSecondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    SkeletonRows(count = 2, rowHeight = 18.dp)
                }
                2 -> Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(VoiceNoteRules.NEEDS_MIC, style = MekaType.body, color = Meka.colors.textSecondary)
                    Pill("Allow", filled = true, onClick = onAllowMic)
                }
                else -> Text(
                    when (state) {
                        VoiceNoteState.TooShort -> VoiceNoteRules.TOO_SHORT
                        VoiceNoteState.CouldNotStart -> "The microphone is busy · close the other app using it and try again"
                        else -> ""
                    },
                    style = MekaType.body, color = Meka.colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun stepOf(state: VoiceNoteState): Int = when (state) {
    is VoiceNoteState.Recording, VoiceNoteState.Idle -> 0
    VoiceNoteState.Finishing -> 1
    VoiceNoteState.NeedsMic -> 2
    else -> 3
}

/** The brass dot: breathes (0.6 → 1, 0.9 s) while recording; still and dimmed when paused or under reduced motion. */
@Composable
private fun RecordingDot(paused: Boolean) {
    val reduced = Meka.reducedMotion
    val t = rememberInfiniteTransition(label = "voice-note-dot")
    val pulse = if (reduced || paused) 1f else t.animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "voice-note-dot-pulse",
    ).value
    Box(
        Modifier.size(12.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = if (paused) 0.4f else 1f }
            .clip(CircleShape).background(Meka.colors.accent)
            .semantics { contentDescription = if (paused) "Paused" else "Recording" },
    )
}

/** The last [levels] of his voice as thin brass bars, newest on the right. */
@Composable
private fun LevelStrip(levels: List<Float>) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = "Your voice's level" },
        horizontalArrangement = Arrangement.spacedBy(3.dp), // rhythm: ok (a glyph's own spacing)
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = List((LEVEL_BARS - levels.size).coerceAtLeast(0)) { 0f } + levels.takeLast(LEVEL_BARS)
        shown.forEach { l ->
            Box(Modifier.width(2.dp).height(24.dp).graphicsLayer { scaleY = 0.08f + 0.92f * l }
                .clip(RoundedCornerShape(1.dp)).background(Meka.colors.accent))
        }
    }
}

/** How many bars the strip shows (about two seconds at 20 a second would be too dense: one bar per tick, 40 kept). */
internal const val LEVEL_BARS = 40

@Composable
private fun Pill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = if (filled) Meka.colors.onAccent else Meka.colors.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .minTouch()
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** The voice note being made, kept outside the sheet so unfolding the Fold mid-note doesn't lose it. */
internal object VoiceNoteSessions {
    @Volatile var current: VoiceNoteSession? = null
}

/** Navilife's own app page in Android's settings (where Microphone is once Android stops asking). */
internal object HelpSettingsLink {
    fun appDetails(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
