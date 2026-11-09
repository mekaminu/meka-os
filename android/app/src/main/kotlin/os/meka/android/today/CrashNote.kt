package os.meka.android.today

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import java.io.File

/** Today's "Navilife closed unexpectedly" line, for screen tests. */
const val CRASH_NOTE_TAG = "today-crash-note"

/**
 * What went wrong when Navilife last closed on its own (Meka 2026-11-09 19:16: the app closed on "Day list ›", and the
 * crash couldn't be seen from here). The app's own uncaught-exception handler writes the error and where it happened
 * into one small file on this phone ([install]), then lets Android close the app as before. On the next open Today
 * shows one quiet line, "Navilife closed unexpectedly at 19:16 · Copy what went wrong", so he can paste it to Claude;
 * Copy and Dismiss clear it. Nothing leaves the phone unless he copies it; it holds the error and code places only
 * (task ids at most, never the database).
 */
object CrashNote {
    private const val FILE = "last-crash.txt"

    /** At most this much of the trace is kept: the error, its causes and where they happened. */
    const val MAX_CHARS = 6_000

    const val COPY = "Copy what went wrong"
    const val DISMISS = "Dismiss"
    const val COPIED = "Copied · paste it to Claude"

    /** Chains the app's handler in front of Android's: write the note, then crash as before. Call once, first. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread.name, error, System.currentTimeMillis()) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes [error] as the last crash (overwriting any older one). */
    fun write(context: Context, threadName: String, error: Throwable, atMs: Long) {
        val trace = error.stackTraceToString()
        File(context.filesDir, FILE).writeText(text(threadName, trace, atMs))
    }

    /** The note's text: when (epoch ms) on the first line, then the thread and the trace, cut to [MAX_CHARS]. */
    internal fun text(threadName: String, trace: String, atMs: Long): String =
        "$atMs\nNavilife ${versionLine()} · thread $threadName\n${trace.take(MAX_CHARS)}"

    private fun versionLine(): String = runCatching { os.meka.android.BuildConfig.VERSION_NAME }.getOrDefault("?")

    /** The last crash: when, and what to copy; null when there isn't one. */
    fun read(context: Context): Pair<Long, String>? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val all = runCatching { f.readText() }.getOrNull() ?: return null
        val at = all.substringBefore('\n').toLongOrNull() ?: return null
        return at to all.substringAfter('\n')
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** "Navilife closed unexpectedly at 19:16" (today), or "… on Mon 9 Nov at 19:16". */
    fun line(atMs: Long, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val at = java.time.Instant.ofEpochMilli(atMs).atZone(zone)
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = String.format(java.util.Locale.UK, "%02d:%02d", at.hour, at.minute)
        return if (at.toLocalDate() == now.toLocalDate()) "Navilife closed unexpectedly at $time"
        else "Navilife closed unexpectedly on " +
            at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)) + " at $time"
    }
}

/**
 * Today's one quiet line after a crash: what happened and when, "Copy what went wrong" (to paste to Claude) and Dismiss.
 * It fades in with the header and fades out once copied or dismissed.
 */
@Composable
fun CrashNoteLine(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var note by remember { mutableStateOf(CrashNote.read(context)) }
    var copied by remember { mutableStateOf(false) }
    val reduced = Meka.reducedMotion
    AnimatedVisibility(note != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val n = note ?: return@AnimatedVisibility
        Column(modifier.fillMaxWidth().testTag(CRASH_NOTE_TAG)) {
            Text(CrashNote.line(n.first, System.currentTimeMillis()), style = MekaType.caption, color = Meka.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                Text(
                    if (copied) CrashNote.COPIED else CrashNote.COPY, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.testTag("$CRASH_NOTE_TAG-copy").clickable(role = Role.Button) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Navilife crash", n.second))
                        copied = true
                        CrashNote.clear(context)
                    }.minTouch().padding(vertical = MekaSpace.xxs),
                )
                Text(
                    CrashNote.DISMISS, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.testTag("$CRASH_NOTE_TAG-dismiss").clickable(role = Role.Button) {
                        CrashNote.clear(context)
                        note = null
                    }.minTouch().padding(vertical = MekaSpace.xxs),
                )
            }
        }
    }
}
