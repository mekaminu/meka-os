package os.meka.android.work

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.VoiceRecordingRules
import os.meka.core.domain.VoiceWaveformRules

/**
 * ▶ Play under a voice message the server kept (call assistant polish 8c). The recording is fetched from MEKA's server
 * over a signed request when Play is pressed, played from memory (never written to the phone's storage) and let go
 * when it ends, on Stop, or when the card folds away. A brass bar fills as it plays, with "0:12 / 0:40".
 *
 * Slice 7 of call notifications: the bar is the recording's waveform ([VoiceWaveformRules], read from the MP3's own
 * frames in memory), brass up to where it has got to. Tap or drag along it to scrub: the line shows where the finger
 * is and letting go plays from there (from the top if let go at the end); the bytes stay in memory while the card is
 * open so a scrub after the end doesn't fetch again. TalkBack reads "Voice message, 0:12 of 0:40" and can set it.
 */
@Composable
internal fun VoiceMessagePlayer(
    id: String,
    fetch: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    /** Start playing as soon as it appears (▶ Play on the urgent alert), once; [onAutoStarted] then clears the ask. */
    autoStart: Boolean = false,
    onAutoStarted: () -> Unit = {},
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    var state by remember(id) { mutableStateOf(PlayerState.IDLE) }
    var position by remember(id) { mutableLongStateOf(0L) }
    var duration by remember(id) { mutableLongStateOf(0L) }
    val holder = remember(id) { PlayerHolder() }
    DisposableEffect(id) { onDispose { holder.release() } }
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    var wave by remember(id) { mutableStateOf<List<Float>>(emptyList()) }
    // While a finger is on the waveform: where it is (0..1), else -1.
    var scrub by remember(id) { mutableFloatStateOf(-1f) }

    // While playing: read the position a few times a second for the bar and the line.
    LaunchedEffect(state) {
        while (state == PlayerState.PLAYING) {
            holder.player?.let { p -> runCatching { position = p.currentPosition.toLong(); duration = p.duration.toLong().coerceAtLeast(0) } }
            delay(200)
        }
    }

    fun stop() {
        holder.release()
        state = PlayerState.IDLE
        position = 0
    }

    fun play(audio: ByteArray, fromMs: Long) {
        val mp = MediaPlayer()
        holder.player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(MemorySource(audio))
            mp.setOnPreparedListener {
                duration = it.duration.toLong().coerceAtLeast(0)
                if (fromMs > 0) it.seekTo(fromMs.toInt())
                position = fromMs
                it.start()
                state = PlayerState.PLAYING
            }
            mp.setOnCompletionListener { position = duration; holder.release(); state = PlayerState.IDLE }
            mp.setOnErrorListener { _, _, _ -> holder.release(); state = PlayerState.FAILED; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            holder.release()
            state = PlayerState.FAILED
        }
    }

    fun start(fromMs: Long = 0L) {
        position = fromMs
        state = PlayerState.LOADING
        bytes?.let { play(it, fromMs); return }
        scope.launch {
            val fetched = runCatching { fetch(id) }.getOrNull()
            if (fetched == null || state != PlayerState.LOADING) {
                if (state == PlayerState.LOADING) state = PlayerState.FAILED
                return@launch
            }
            bytes = fetched
            play(fetched, fromMs)
            wave = withContext(Dispatchers.Default) { runCatching { VoiceWaveformRules.of(fetched) }.getOrDefault(emptyList()) }
        }
    }

    /** A tap, a let-go drag or TalkBack's "set" at [f] of the way: jump there while playing, else play from there. */
    fun seek(f: Float) {
        val p = holder.player
        if (state == PlayerState.PLAYING && p != null) {
            val at = VoiceRecordingRules.seekMs(f, duration)
            runCatching { p.seekTo(at.toInt()) }
            position = at
        } else if (state != PlayerState.LOADING) {
            start(VoiceRecordingRules.startFromMs(f, duration))
        }
    }

    LaunchedEffect(id, autoStart) {
        if (autoStart && state == PlayerState.IDLE) {
            haptics.light()
            start()
            onAutoStarted()
        }
    }

    val fraction by animateFloatAsState(
        VoiceRecordingRules.fraction(position, duration),
        animationSpec = if (reduced) tween(0) else tween(220),
        label = "voice-bar",
    )
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xxs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            val playing = state == PlayerState.PLAYING || state == PlayerState.LOADING
            Crossfade(playing, animationSpec = MekaMotion.appear(reduced), label = "voice-play") { on ->
                Text(
                    if (on) VoiceRecordingRules.STOP else VoiceRecordingRules.PLAY,
                    style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        if (on) { haptics.tick(); stop() } else { haptics.light(); start() }
                    }.padding(vertical = MekaSpace.xs)
                        .semantics { contentDescription = if (on) "Stop the voice message" else "Play the voice message" },
                )
            }
            val line = when (state) {
                PlayerState.LOADING -> VoiceRecordingRules.LOADING
                PlayerState.FAILED -> VoiceRecordingRules.FAILED
                else -> when {
                    scrub >= 0f -> VoiceRecordingRules.progressLine(VoiceRecordingRules.seekMs(scrub, duration), duration)
                    position > 0 || duration > 0 -> VoiceRecordingRules.progressLine(position, duration)
                    else -> null
                }
            }
            // While scrubbing the time follows the finger without a fade.
            Crossfade(line, animationSpec = if (scrub >= 0f) tween(0) else MekaMotion.appear(reduced), label = "voice-line") { l ->
                if (l != null) Text(l, style = MekaType.caption, color = if (state == PlayerState.FAILED) Meka.colors.critical else Meka.colors.textTertiary)
            }
        }
        if (state == PlayerState.PLAYING || position > 0) {
            if (wave.isNotEmpty()) {
                VoiceWaveform(
                    bars = wave,
                    played = if (scrub >= 0f) scrub else fraction,
                    spoken = VoiceRecordingRules.spoken(if (scrub >= 0f) VoiceRecordingRules.seekMs(scrub, duration) else position, duration),
                    onScrub = { f ->
                        if (scrub < 0f) haptics.tick()
                        scrub = f
                    },
                    onSeek = { f ->
                        haptics.light()
                        scrub = -1f
                        seek(f)
                    },
                )
            } else {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
                }
            }
        }
    }
}

/**
 * The recording's [bars] (heights 0..1), brass up to [played] and faint after it, 28 dp tall in a 48 dp touch row.
 * The bars rise from the middle line on the expand spring when it first appears (shown at once with reduced motion).
 * A finger on it reports where it is with [onScrub] and, when lifted (or on a tap), [onSeek].
 */
@Composable
private fun VoiceWaveform(bars: List<Float>, played: Float, spoken: String, onScrub: (Float) -> Unit, onSeek: (Float) -> Unit) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    val rest = Meka.colors.textTertiary.copy(alpha = 0.35f)
    val rise = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) rise.animateTo(1f, MekaMotion.expand(false)) }
    val scrubNow by rememberUpdatedState(onScrub)
    val seekNow by rememberUpdatedState(onSeek)
    var width by remember { mutableFloatStateOf(1f) }
    fun at(x: Float) = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .semantics {
                contentDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo(played.coerceIn(0f, 1f), 0f..1f)
                setProgress { seekNow(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) { detectTapGestures { seekNow(at(it.x)) } }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { last = at(it.x); scrubNow(last) },
                    onDragEnd = { seekNow(last) },
                    onDragCancel = { seekNow(last) },
                ) { change, _ -> last = at(change.position.x); scrubNow(last) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val n = bars.size
            val slot = size.width / n
            val barW = (slot * 0.6f).coerceAtLeast(1.5f)
            val mid = size.height / 2
            bars.forEachIndexed { i, h ->
                val bh = (size.height * h * rise.value).coerceAtLeast(barW)
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(
                    color = if ((i + 0.5f) / n <= played) accent else rest,
                    topLeft = Offset(x, mid - bh / 2),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
    }
}

private enum class PlayerState { IDLE, LOADING, PLAYING, FAILED }

/** The one player for a message; released when it ends, on Stop, or when the card goes. */
private class PlayerHolder {
    var player: MediaPlayer? = null

    fun release() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }
}

/** The recording's bytes for [MediaPlayer], straight from memory (nothing written to storage). */
private class MemorySource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()
    override fun close() {}
}
