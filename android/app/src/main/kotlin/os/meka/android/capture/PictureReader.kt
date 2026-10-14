package os.meka.android.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.core.domain.PictureTextRules

/**
 * Navilife's one way of reading words off a picture (screenshots and photos, slice 1; also the parking bay's sign):
 * ML Kit's bundled Latin text recognition, which runs on the phone (Needs Meka #23's yes). The picture never leaves the
 * phone and is never kept here: it's decoded into memory, read and dropped.
 */
object OnPhoneText {
    /** The words on [bitmap], or null when nothing could be read within [timeoutMs]. Never throws (except cancellation). */
    suspend fun read(bitmap: Bitmap, timeoutMs: Long = PictureTextRules.READ_MS): String? {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS,
        )
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(soft ?: bitmap, 0))
                        .addOnSuccessListener { t -> if (cont.isActive) cont.resumeWith(Result.success(t.text)) }
                        .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                        .addOnCanceledListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { recognizer.close() }
            if (soft != null && soft !== bitmap) soft.recycle()
        }
    }
}

/** What reading a picture came to; [Words] holds the tidied words ([PictureTextRules.tidy]). */
sealed interface PictureRead {
    data class Words(val text: String) : PictureRead
    data class NoWords(val line: String) : PictureRead
}

object PictureReader {
    private const val SCRATCH_DIR = "pictures"
    private const val SCRATCH = "letter.jpg"

    /**
     * Where the camera app writes a letter's photo (Photo of a letter): one scratch file in the cache, shared through
     * the FileProvider so the camera needs no storage access and nothing lands in the gallery. Read, then deleted.
     */
    fun cameraUri(context: Context): Uri {
        val dir = java.io.File(context.cacheDir, SCRATCH_DIR).apply { mkdirs() }
        val f = java.io.File(dir, SCRATCH)
        runCatching { f.delete() }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", f)
    }

    /** Deletes the camera's scratch photo (after reading, or when the camera was cancelled). Never throws. */
    fun forgetCameraPhoto(context: Context) {
        runCatching { java.io.File(java.io.File(context.cacheDir, SCRATCH_DIR), SCRATCH).delete() }
    }

    /**
     * Reads the picture at [uri] on the phone: refuses one over [PictureTextRules.MAX_BYTES], decodes it upright and
     * shrunk to [PictureTextRules.READ_MAX_PX], reads it with [OnPhoneText] and tidies the words. Never throws.
     */
    suspend fun read(context: Context, uri: Uri): PictureRead {
        val bitmap = withContext<Pair<Bitmap?, String?>>(Dispatchers.IO) {
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { n -> n >= 0 } }
            }.getOrNull()
            if (PictureTextRules.tooBig(size)) return@withContext null to PictureTextRules.TOO_BIG_LINE
            try {
                val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    val (w, h) = PictureTextRules.readSize(info.size.width, info.size.height)
                    d.setTargetSize(w, h)
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                b to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                null to PictureTextRules.TOO_BIG_LINE
            } catch (e: Exception) {
                null to PictureTextRules.COULD_NOT_OPEN_LINE
            }
        }
        val b = bitmap.first ?: return PictureRead.NoWords(bitmap.second ?: PictureTextRules.COULD_NOT_OPEN_LINE)
        val raw = (try { OnPhoneText.read(b) } finally { b.recycle() })
            ?: return PictureRead.NoWords(PictureTextRules.TOOK_TOO_LONG_LINE)
        val text = PictureTextRules.tidy(raw)
        return if (PictureTextRules.worthOffering(text)) PictureRead.Words(text) else PictureRead.NoWords(PictureTextRules.NOTHING_LINE)
    }
}
