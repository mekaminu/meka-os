package os.meka.android.work

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.VoiceRecordingRules

/**
 * ▶ Play under a voice message the server kept (call assistant polish 8c). The recording is fetched from MEKA's server
 * over a signed request when Play is pressed, played from memory (never written to the phone's storage) and let go
 * when it ends, on Stop, or when the card folds away. A brass bar fills as it plays, with "0:12 / 0:40".
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

    fun start() {
        position = 0
        state = PlayerState.LOADING
        scope.launch {
            val bytes = runCatching { fetch(id) }.getOrNull()
            if (bytes == null || state != PlayerState.LOADING) {
                if (state == PlayerState.LOADING) state = PlayerState.FAILED
                return@launch
            }
            val mp = MediaPlayer()
            holder.player = mp
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                mp.setDataSource(MemorySource(bytes))
                mp.setOnPreparedListener { duration = it.duration.toLong().coerceAtLeast(0); it.start(); state = PlayerState.PLAYING }
                mp.setOnCompletionListener { position = duration; holder.release(); state = PlayerState.IDLE }
                mp.setOnErrorListener { _, _, _ -> holder.release(); state = PlayerState.FAILED; true }
                mp.prepareAsync()
            } catch (e: Exception) {
                holder.release()
                state = PlayerState.FAILED
            }
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
                else -> if (position > 0 || duration > 0) VoiceRecordingRules.progressLine(position, duration) else null
            }
            Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "voice-line") { l ->
                if (l != null) Text(l, style = MekaType.caption, color = if (state == PlayerState.FAILED) Meka.colors.critical else Meka.colors.textTertiary)
            }
        }
        if (state == PlayerState.PLAYING || position > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
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
