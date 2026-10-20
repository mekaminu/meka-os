package os.meka.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.VaultFileOpened
import os.meka.core.domain.VaultFileRules
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Vault's copies on the Fold (Vault slice 2b): reading a picked photo or PDF ready to seal (a photo turned upright
 * and re-encoded as a JPEG of at most [VaultFileRules.PHOTO_LONG_SIDE] on its long side, smaller until it fits), and
 * showing an opened copy in the app only (a PDF's pages drawn by Android's PdfRenderer from a file in the app's private
 * no-backup folder, deleted at once). Nothing opened is handed to another app.
 */
object VaultCopies {
    /** A picked file ready for [os.meka.core.facade.MekaCore.addVaultCopy], or why it can't be. */
    data class Picked(val type: String?, val base64: String?, val name: String?, val problem: String?)

    /** The power-of-two sample size that keeps a [w]×[h] picture at least [target] on its long side (pure; tested). */
    fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    /** The scale that brings a [w]×[h] picture's long side to at most [target] (1 when it already fits). */
    fun scaleFor(w: Int, h: Int, target: Int): Float = minOf(1f, target.toFloat() / maxOf(w, h, 1))

    /** JPEG qualities tried in turn until the photo fits. */
    val QUALITIES = listOf(85, 72, 60, 48)

    suspend fun read(context: Context, uri: Uri): Picked = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val type = VaultFileRules.typeOf(cr.getType(uri), name) ?: return@withContext Picked(null, null, name, VaultFileRules.NOT_TYPE)
        val raw = runCatching {
            cr.openInputStream(uri)?.use { input ->
                // Read at most a little past the limit for a PDF; a photo is re-encoded, so a big one is fine.
                val cap = if (type == VaultFileRules.PDF) VaultFileRules.MAX_FILE_BYTES + 1 else 40_000_000L
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (total < cap) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext Picked(type, null, name, VaultFileRules.COULDNT_READ)
        if (type == VaultFileRules.PDF) {
            if (raw.size > VaultFileRules.MAX_FILE_BYTES) return@withContext Picked(type, null, name, VaultFileRules.TOO_BIG)
            return@withContext Picked(type, Base64.encodeToString(raw, Base64.NO_WRAP), name, null)
        }
        val jpeg = photo(raw) ?: return@withContext Picked(type, null, name, VaultFileRules.COULDNT_READ)
        if (jpeg.size > VaultFileRules.MAX_FILE_BYTES) return@withContext Picked(type, null, name, VaultFileRules.TOO_BIG)
        Picked(VaultFileRules.JPEG, Base64.encodeToString(jpeg, Base64.NO_WRAP), name, null)
    }

    /** The photo upright, its long side at most [VaultFileRules.PHOTO_LONG_SIDE], as the first JPEG quality that fits. */
    private fun photo(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = VaultFileRules.PHOTO_LONG_SIDE
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target) }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val rotation = runCatching {
            when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = scaleFor(decoded.width, decoded.height, target)
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val upright = if (scale < 1f || rotation != 0f) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            for (q in QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!upright.compress(Bitmap.CompressFormat.JPEG, q, out)) return null
                val bytes = out.toByteArray()
                if (bytes.size <= VaultFileRules.MAX_FILE_BYTES) return bytes
            }
            return null
        } finally {
            upright.recycle()
        }
    }

    /** An opened copy's pictures: the photo, or each PDF page drawn at [width] px (white behind the page). */
    suspend fun pages(context: Context, opened: VaultFileOpened, width: Int = 1_600): List<Bitmap> = withContext(Dispatchers.IO) {
        val bytes = opened.base64?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return@withContext emptyList()
        if (!opened.isPdf) {
            // A big photo (an email's attachment can be a full-size camera picture) is sampled down to about [width].
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width) }
            return@withContext listOfNotNull(runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull())
        }
        // PdfRenderer needs a file: the app's own no-backup folder, deleted as soon as it is open.
        val dir = File(context.noBackupFilesDir, "vault-open").apply { mkdirs() }
        val file = File.createTempFile("copy", ".pdf", dir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                file.delete()
                PdfRenderer(fd).use { r ->
                    (0 until minOf(r.pageCount, 20)).map { i ->
                        r.openPage(i).use { p ->
                            val h = (width.toFloat() * p.height / maxOf(p.width, 1)).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                                b.eraseColor(Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            file.delete()
        }
    }
}

/**
 * An opened copy over the Vault, in the app only: the document's name, its photo or PDF pages (scroll for more), and
 * Close. Motion: the sheet fades and scales in from 96% on the appear spring and the pages fade in once drawn;
 * reduced motion fades only.
 */
@Composable
fun VaultCopyViewer(opened: VaultFileOpened, onClose: () -> Unit) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    var pages by remember(opened) { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(opened) { pages = VaultCopies.pages(context, opened) }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visibleState = shown,
            enter = if (reduced) fadeIn() else fadeIn() + scaleIn(initialScale = 0.96f),
            exit = if (reduced) fadeOut() else fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            Box(
                Modifier.fillMaxSize().padding(MekaSpace.m).clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(MekaSpace.m),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    item {
                        Text(opened.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                        Text(
                            VaultFileRules.CLOSE, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClose)
                                .semantics { contentDescription = "Close ${opened.title}" }.minTouch().padding(MekaSpace.xs),
                        )
                    }
                    val p = pages
                    when {
                        p == null -> item { Text("Opening…", style = MekaType.caption, color = Meka.colors.textSecondary) }
                        p.isEmpty() -> item { Text(VaultFileRules.CHANGED, style = MekaType.caption, color = Meka.colors.accent) }
                        else -> items(p.size) { i ->
                            Image(
                                p[i].asImageBitmap(), contentDescription = "${opened.title}, page ${i + 1} of ${p.size}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)),
                                alignment = Alignment.TopCenter,
                            )
                        }
                    }
                }
            }
        }
    }
}
