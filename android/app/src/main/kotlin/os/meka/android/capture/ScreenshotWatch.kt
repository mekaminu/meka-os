package os.meka.android.capture

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.ScreenshotCandidate
import os.meka.core.domain.ScreenshotNotice
import os.meka.core.domain.ScreenshotRules
import java.util.concurrent.TimeUnit

/**
 * Screenshots and photos that do something, slice 2 (the Fold): **new screenshots, noticed**. Off until he turns it on
 * under the capture sheet's Photo of a letter, which is when photo access is asked for. While on, WorkManager wakes
 * [ScreenshotWorker] whenever the phone's picture library changes (a content-URI trigger, so nothing runs in between);
 * it reads each new screenshot from the last few minutes on the phone ([PictureReader], ML Kit) and, when it looks like
 * something Navilife can act on ([ScreenshotRules.kind]), posts one quiet notification whose tap opens the capture sheet
 * reading that screenshot. The words and the picture stay on the phone; only his tap on Handle this sends the words.
 * The switch and the last screenshot looked at live in this phone's own storage (never synced).
 */
object ScreenshotWatch {
    private const val PREFS = "meka.screenshots"
    private const val KEY_ON = "on"
    private const val KEY_SINCE = "since"
    private const val WORK = "screenshot-watch"
    const val CHANNEL = "screenshots"
    /** On the capture sheet's intent: the screenshot notification to take down once it's opened. */
    const val EXTRA_NOTICE = "os.meka.capture.screenshotNotice"

    enum class Access { FULL, PARTIAL, NONE }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    private fun since(context: Context): Long = prefs(context).getLong(KEY_SINCE, System.currentTimeMillis())

    /** The permissions to ask for: Android 13+ photos (and 14+'s "Select photos"), else storage. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun access(context: Context): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        Build.VERSION.SDK_INT < 33 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }

    /** Turns it on from now (screenshots already taken aren't offered) or off; on starts the watch. */
    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).putLong(KEY_SINCE, System.currentTimeMillis()).apply()
        if (on) schedule(context, again = false) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** At process start: keeps the watch armed while it's on and photos may be read. */
    fun start(context: Context) {
        if (on(context) && access(context) == Access.FULL) schedule(context, again = false)
    }

    /**
     * Arms the one-off job that runs when the picture library changes. A content-URI trigger fires once, so each run
     * arms the next ([again]: appended after the running one, which then finishes).
     */
    fun schedule(context: Context, again: Boolean) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(15, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<ScreenshotWorker>().setConstraints(constraints).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK, if (again) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request,
            )
        }
    }

    /** The library's pictures added since [sinceMs] (a little slack for its whole-second dates), newest first. */
    private fun recent(context: Context, sinceMs: Long): List<Pair<ScreenshotCandidate, Uri>> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME,
        )
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DATE_ADDED} >= ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(((sinceMs / 1000) - 2).toString()))
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 20)
        }
        val out = ArrayList<Pair<ScreenshotCandidate, Uri>>()
        runCatching {
            context.contentResolver.query(uri, cols, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(cols[0])
                val added = c.getColumnIndexOrThrow(cols[1])
                val taken = c.getColumnIndexOrThrow(cols[2])
                val path = c.getColumnIndexOrThrow(cols[3])
                val name = c.getColumnIndexOrThrow(cols[4])
                while (c.moveToNext()) {
                    val rowId = c.getLong(id)
                    val takenMs = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
                    val shot = ScreenshotRules.isScreenshot(c.getString(path), c.getString(name))
                    out += ScreenshotCandidate(rowId.toString(), takenMs, shot) to ContentUris.withAppendedId(uri, rowId)
                }
            }
        }
        return out
    }

    /** One look: the new screenshots read on the phone and offered. Returns how many were offered. */
    suspend fun look(context: Context): Int {
        val now = System.currentTimeMillis()
        val since = since(context)
        val all = withContext(Dispatchers.IO) { recent(context, since) }
        val candidates = all.map { it.first }
        val picked = ScreenshotRules.pick(candidates, since, now)
        // Moved on before reading, so a picture that can't be read is never tried again.
        prefs(context).edit().putLong(KEY_SINCE, ScreenshotRules.nextSince(candidates, since, now)).apply()
        var offered = 0
        for (c in picked.reversed()) {
            val uri = all.first { it.first.id == c.id }.second
            val read = try { PictureReader.read(context, uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val words = (read as? PictureRead.Words)?.text ?: continue
            val notice = ScreenshotRules.noticeFor(words) ?: continue
            if (post(context, c.id, uri, notice)) offered++
        }
        return offered
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ScreenshotRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ScreenshotRules.CHANNEL_ABOUT
            },
        )
    }

    private fun noticeId(id: String): Int = "screenshot:$id".hashCode()

    /** The capture sheet reading this screenshot, as if it had been shared to Navilife. */
    private fun open(context: Context, id: String, uri: Uri): PendingIntent = PendingIntent.getActivity(
        context, noticeId(id),
        Intent(context, CaptureActivity::class.java)
            .setAction(CaptureRequests.ACTION_SEND)
            .setType("image/*")
            .putExtra(CaptureRequests.EXTRA_STREAM, uri)
            .putExtra(EXTRA_NOTICE, noticeId(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: String, uri: Uri, notice: ScreenshotNotice): Boolean {
        if (!WorkAlerts.canPost(context)) return false
        ensureChannel(context)
        val tap = open(context, id, uri)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(os.meka.android.R.drawable.ic_stat_meka)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(os.meka.android.R.drawable.ic_stat_meka)
                    .setContentTitle(ScreenshotRules.PUBLIC_TITLE).build(),
            )
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tap)
            .addAction(0, notice.action, tap)
            .setAutoCancel(true)
            .setTimeoutAfter(ScreenshotRules.WINDOW_MS * 6)
            .build()
        return try { os.meka.android.notify.MekaPost.notify(context, noticeId(id), n); true } catch (e: SecurityException) { false }
    }

    /** The capture sheet opened from a screenshot notification takes it down. */
    fun opened(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_NOTICE, 0) ?: 0
        if (id != 0) NotificationManagerCompat.from(context).cancel(id)
    }

    fun appPage(context: Context) = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Runs when the picture library changes: one look, then the next trigger is armed. */
class ScreenshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ScreenshotWatch.on(app) || ScreenshotWatch.access(app) != ScreenshotWatch.Access.FULL) return Result.success()
        try {
            runCatching { ScreenshotWatch.look(app) }
        } finally {
            ScreenshotWatch.schedule(app, again = true)
        }
        return Result.success()
    }
}

/**
 * The capture sheet's switch, under Photo of a letter: "🖼 Notice new screenshots · …" (off), "🖼 Noticing new
 * screenshots · … · Turn off" (on, in brass), or why it can't ("can see only the photos you picked", "can't see your
 * photos"). A tap turns it on (asking for photo access the first time; once Android stops asking, the app page opens)
 * or off. The line cross-fades as it changes (reduced motion: the same cross-fade, shorter).
 */
@Composable
fun ScreenshotSwitchLine(onLine: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    var on by remember { mutableStateOf(ScreenshotWatch.on(context) && ScreenshotWatch.access(context) == ScreenshotWatch.Access.FULL) }
    var refused by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (ScreenshotWatch.access(context)) {
            ScreenshotWatch.Access.FULL -> {
                ScreenshotWatch.set(context, true)
                on = true; refused = null
                haptics.light()
                onLine(ScreenshotRules.TURNED_ON)
            }
            ScreenshotWatch.Access.PARTIAL -> { refused = ScreenshotRules.PARTIAL_LINE; haptics.reject() }
            ScreenshotWatch.Access.NONE -> { refused = ScreenshotRules.DENIED_LINE; haptics.reject() }
        }
    }
    val line = if (on) ScreenshotRules.ON_LINE else refused ?: ScreenshotRules.OFF_LINE
    Crossfade(line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "screenshot-switch") { l ->
        Text(
            l, style = MekaType.caption, color = if (on) Meka.colors.accent else Meka.colors.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Switch) {
                    if (on) {
                        ScreenshotWatch.set(context, false)
                        on = false
                        haptics.tick()
                        onLine(ScreenshotRules.TURNED_OFF)
                        return@clickable
                    }
                    when (ScreenshotWatch.access(context)) {
                        ScreenshotWatch.Access.FULL -> {
                            ScreenshotWatch.set(context, true)
                            on = true; refused = null
                            haptics.light()
                            onLine(ScreenshotRules.TURNED_ON)
                        }
                        else -> {
                            val activity = context as? Activity
                            val canAsk = refused == null || (activity != null &&
                                ScreenshotWatch.permissions().any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
                            if (canAsk) ask.launch(ScreenshotWatch.permissions()) else ScreenshotWatch.appPage(context)
                        }
                    }
                }
                .semantics { contentDescription = ScreenshotRules.SWITCH_LABEL + (if (on) " · on · " else " · off · ") + ScreenshotRules.SWITCH_HELP }
                .padding(vertical = MekaSpace.xs),
        )
    }
}
