package os.meka.android.today

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.capture.ScreenshotWatch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CivilDate
import os.meka.core.domain.MemoryPhoto
import os.meka.core.domain.OnThisDayPick
import os.meka.core.domain.OnThisDayRules
import java.util.TimeZone

/**
 * Screenshots and photos that do something, slice 4 (the Fold): **On this day**. The switch, the photos he hid and
 * nothing else live in this phone's own storage (never synced); the library is read here and the picture is shown
 * from it — never copied, uploaded or sent. Uses the same photo access as Notice new screenshots.
 */
object OnThisDayStore {
    private const val PREFS = "meka.onthisday"
    private const val KEY_ON = "on"
    private const val KEY_OFFER_GONE = "offerGone"
    private const val KEY_HIDDEN = "hidden"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)
    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).putBoolean(KEY_OFFER_GONE, true).apply()
    }

    fun offerGone(context: Context): Boolean = prefs(context).getBoolean(KEY_OFFER_GONE, false)
    fun dismissOffer(context: Context) { prefs(context).edit().putBoolean(KEY_OFFER_GONE, true).apply() }

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
    fun hide(context: Context, id: String) {
        // Only the library's row id, so a photo he hid stays hidden; capped so the set can't grow without end.
        val next = (hidden(context) + id).toList().takeLast(500).toSet()
        prefs(context).edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    fun todayEpochDay(nowMs: Long = System.currentTimeMillis()): Long =
        Math.floorDiv(nowMs + TimeZone.getDefault().getOffset(nowMs), CivilDate.DAY_MS)

    /** This date's photos in each earlier year, from the phone's library (a day's slack each side for time zones). */
    suspend fun load(context: Context, todayEpochDay: Long): List<Pair<MemoryPhoto, Uri>> = withContext(Dispatchers.IO) {
        val days = OnThisDayRules.windows(todayEpochDay)
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val selection = days.joinToString(" OR ") { "($taken >= ? AND $taken < ?)" }
        val selectionArgs = days.flatMap {
            listOf(((it.epochDay - 1) * CivilDate.DAY_MS).toString(), ((it.epochDay + 2) * CivilDate.DAY_MS).toString())
        }.toTypedArray()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(taken))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, OnThisDayRules.QUERY_LIMIT)
        }
        val out = ArrayList<Pair<MemoryPhoto, Uri>>()
        try {
            context.contentResolver.query(uri, cols, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(cols[0])
                val at = c.getColumnIndexOrThrow(cols[1])
                val path = c.getColumnIndexOrThrow(cols[2])
                val name = c.getColumnIndexOrThrow(cols[3])
                val w = c.getColumnIndexOrThrow(cols[4])
                val h = c.getColumnIndexOrThrow(cols[5])
                while (c.moveToNext()) {
                    val rowId = c.getLong(id)
                    val ms = c.getLong(at).takeIf { it > 0 } ?: continue
                    val localDay = Math.floorDiv(ms + TimeZone.getDefault().getOffset(ms), CivilDate.DAY_MS)
                    out += MemoryPhoto(
                        rowId.toString(), ms, localDay, c.getString(path), c.getString(name), c.getInt(w), c.getInt(h),
                    ) to ContentUris.withAppendedId(uri, rowId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No access or the library is busy: nothing tonight.
        }
        out
    }

    /** A screen-sized preview from the library's own thumbnail cache; the picture never leaves the phone. */
    suspend fun preview(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.loadThumbnail(uri, Size(1080, 1080), null).asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun openInGallery(context: Context, uri: Uri) = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private sealed interface Memory {
    data object Loading : Memory
    data object None : Memory
    data class Shown(val pick: OnThisDayPick, val uri: Uri, val image: ImageBitmap?) : Memory
}

/**
 * The shutdown pane's ON THIS DAY section. Off: one quiet line offering it (Turn on asks for photo access; Not now puts
 * it away for good). On: the photo (tap → Gallery), "3 years ago · Sun 15 Oct 2023", Another · Not this one · Turn off
 * and "Picked on this phone · never uploaded". Motion: the photo cross-fades in (and to the next on Another, with a
 * tick haptic); turning on gives a light haptic; reduced motion: the same cross-fades, shorter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnThisDaySection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var on by remember { mutableStateOf(OnThisDayStore.on(context)) }
    var offerGone by remember { mutableStateOf(OnThisDayStore.offerGone(context)) }
    var access by remember { mutableStateOf(ScreenshotWatch.access(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var hiddenCount by remember { mutableIntStateOf(0) }
    var memory by remember { mutableStateOf<Memory>(Memory.Loading) }
    var photos by remember { mutableStateOf<List<Pair<MemoryPhoto, Uri>>?>(null) }
    val today = remember { OnThisDayStore.todayEpochDay() }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = ScreenshotWatch.access(context)
        if (access == ScreenshotWatch.Access.NONE) { refused = true; haptics.reject() } else {
            OnThisDayStore.set(context, true); on = true; offerGone = true
            haptics.light(); note = OnThisDayRules.TURNED_ON
        }
    }
    val turnOn: () -> Unit = {
        if (access != ScreenshotWatch.Access.NONE) {
            OnThisDayStore.set(context, true); on = true; offerGone = true
            haptics.light(); note = OnThisDayRules.TURNED_ON
        } else {
            val activity = context as? Activity
            val canAsk = !refused || (activity != null &&
                ScreenshotWatch.permissions().any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
            if (canAsk) ask.launch(ScreenshotWatch.permissions()) else ScreenshotWatch.appPage(context)
        }
    }

    LaunchedEffect(on, access) {
        if (on && access != ScreenshotWatch.Access.NONE) photos = OnThisDayStore.load(context, today)
    }
    LaunchedEffect(photos, index, hiddenCount) {
        val list = photos ?: return@LaunchedEffect
        val pick = OnThisDayRules.pick(list.map { it.first }, today, OnThisDayStore.hidden(context), index)
        if (pick == null) { memory = Memory.None; return@LaunchedEffect }
        val uri = list.first { it.first.id == pick.photo.id }.second
        val image = OnThisDayStore.preview(context, uri)
        memory = if (image == null) Memory.None else Memory.Shown(pick, uri, image)
    }

    Column(modifier.fillMaxWidth()) {
        when {
            !on && offerGone -> note?.let { Caption(it) }
            !on -> {
                Text(
                    if (refused) OnThisDayRules.DENIED else OnThisDayRules.OFFER,
                    style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Switch) { turnOn() }
                        .semantics { contentDescription = OnThisDayRules.OFFER_LABEL + " · off · " + OnThisDayRules.PRIVACY }
                        .padding(vertical = MekaSpace.xs),
                )
                Text(
                    OnThisDayRules.NOT_NOW, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.tick(); OnThisDayStore.dismissOffer(context); offerGone = true
                    }.minTouch(),
                )
            }
            access == ScreenshotWatch.Access.NONE -> {
                Text(
                    OnThisDayRules.DENIED, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.clickable(role = Role.Button) { turnOn() }.padding(vertical = MekaSpace.xs),
                )
            }
            else -> {
                SectionLabel(OnThisDayRules.LABEL)
                if (access == ScreenshotWatch.Access.PARTIAL) Caption(OnThisDayRules.PARTIAL)
                AnimatedContent(
                    memory,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    contentKey = { m -> (m as? Memory.Shown)?.pick?.photo?.id ?: m::class.simpleName },
                    label = "on-this-day",
                ) { m ->
                    when (m) {
                        Memory.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp))
                        Memory.None -> Caption(OnThisDayRules.NONE)
                        is Memory.Shown -> Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            m.image?.let { img ->
                                Image(
                                    img, contentDescription = null, contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                        .clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                                        .clickable(role = Role.Image) { OnThisDayStore.openInGallery(context, m.uri) }
                                        .semantics { contentDescription = OnThisDayRules.description(m.pick) },
                                )
                            }
                            Text(m.pick.line, style = MekaType.body, color = Meka.colors.textPrimary)
                            OnThisDayRules.countLine(m.pick)?.let { Caption(it) }
                        }
                    }
                }
                FlowRow(
                    Modifier.padding(top = MekaSpace.xs),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    val shown = memory as? Memory.Shown
                    if (shown != null && shown.pick.ofCount > 1) {
                        Chip(OnThisDayRules.ANOTHER) { haptics.tick(); index = shown.pick.index + 1 }
                    }
                    if (shown != null) {
                        Chip(OnThisDayRules.HIDE) {
                            haptics.tick()
                            OnThisDayStore.hide(context, shown.pick.photo.id)
                            note = OnThisDayRules.HIDDEN
                            index = shown.pick.index
                            hiddenCount++
                        }
                    }
                    Chip(OnThisDayRules.TURN_OFF) {
                        haptics.tick(); OnThisDayStore.set(context, false); on = false
                        memory = Memory.Loading; photos = null; note = OnThisDayRules.TURNED_OFF
                    }
                }
                Crossfade(note, animationSpec = MekaMotion.appear(reduced), label = "on-this-day-note") { n ->
                    Caption(n ?: OnThisDayRules.PRIVACY)
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xxs))
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
