package os.meka.android.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.meka.core.domain.VoiceNote
import os.meka.core.domain.VoiceNoteRules
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Voice notes into actions, slice 1 (the Fold only): the notes and their recordings, kept in this phone's own storage
 * (files/voice-notes/, never backed up or moved to another phone: data_extraction_rules.xml) and encrypted with a
 * non-exportable Keystore AES-GCM key, so the files are unreadable outside Navilife on this phone. Nothing here is
 * synced or sent; the core only says what to keep, show and delete ([VoiceNoteRules]).
 *
 * The index (one line per note, [VoiceNoteRules.encode]) is one encrypted file; each recording is "<id>.m4a.enc". A
 * note just recorded is written as a plain ".part" file inside the folder while the microphone runs and encrypted the
 * moment it stops; [sweep] deletes notes past 30 days, recordings no note owns and any leftover ".part".
 */
object VoiceNoteStore {
    private const val DIR = "voice-notes"
    private const val ALIAS = "meka.voicenotes.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV = 12

    private val lock = Mutex()
    private val _notes = MutableStateFlow<List<VoiceNote>?>(null)

    /** The notes, newest first (null until first read). */
    val notes: StateFlow<List<VoiceNote>?> = _notes

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Where a recording in progress is written (plain, inside the private folder, until [keep]). */
    fun partFile(context: Context, id: String): File = File(dir(context), "$id.part")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, IV))) }
        return c.doFinal(blob, IV, blob.size - IV)
    }

    /** Writes [bytes] to [file] by way of a temporary file, so a crash never leaves half a file. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun readIndex(context: Context): List<VoiceNote> {
        val f = File(dir(context), VoiceNoteRules.INDEX_FILE)
        if (!f.exists()) return emptyList()
        return runCatching { VoiceNoteRules.decode(decrypt(f.readBytes()).decodeToString()) }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, notes: List<VoiceNote>) {
        writeAtomically(File(dir(context), VoiceNoteRules.INDEX_FILE), encrypt(VoiceNoteRules.encode(notes).encodeToByteArray()))
        _notes.value = VoiceNoteRules.ordered(notes)
    }

    /** Reads the notes (and publishes them to [notes]). Never throws. */
    suspend fun load(context: Context): List<VoiceNote> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex(context).also { _notes.value = it } }
    }

    /**
     * Keeps a finished recording: encrypts [id]'s ".part" file into its recording and adds [note] to the index.
     * False when the recording couldn't be kept (the note is still added, without a recording to play).
     */
    suspend fun keep(context: Context, note: VoiceNote): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val part = partFile(context, note.id)
            val ok = runCatching {
                if (part.exists() && part.length() > 0) {
                    writeAtomically(File(dir(context), VoiceNoteRules.fileName(note.id)), encrypt(part.readBytes()))
                    true
                } else false
            }.getOrDefault(false)
            part.delete()
            runCatching { writeIndex(context, VoiceNoteRules.upsert(readIndex(context), note)) }
            ok
        }
    }

    /** Changes one note in the index (kept, cards offered). Never throws. */
    suspend fun update(context: Context, id: String, change: (VoiceNote) -> VoiceNote) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val all = readIndex(context)
                val n = all.firstOrNull { it.id == id } ?: return@runCatching
                writeIndex(context, VoiceNoteRules.upsert(all, change(n)))
            }
        }
    }

    /**
     * Takes [id] out of the index; its recording stays until the next [sweep], so [restore] (Undo) brings it back
     * whole. Returns the note taken out.
     */
    suspend fun remove(context: Context, id: String): VoiceNote? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val all = readIndex(context)
                val n = all.firstOrNull { it.id == id } ?: return@runCatching null
                writeIndex(context, all.filter { it.id != id })
                n
            }.getOrNull()
        }
    }

    /** Puts a removed note back (its recording is still there unless a sweep ran). */
    suspend fun restore(context: Context, note: VoiceNote) = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { writeIndex(context, VoiceNoteRules.upsert(readIndex(context), note)) } }
    }

    /**
     * Deletes the notes past [VoiceNoteRules.KEEP_DAYS] days (unless kept), the recordings no note owns and leftover
     * ".part" files. Not while a recording runs ([recording]: its ".part" is kept). Never throws.
     */
    suspend fun sweep(context: Context, nowMs: Long, recording: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val all = readIndex(context)
                val gone = VoiceNoteRules.expired(all, nowMs).map { it.id }.toSet()
                val left = all.filter { it.id !in gone }
                if (gone.isNotEmpty()) writeIndex(context, left) else _notes.value = left
                val d = dir(context)
                val names = d.list()?.toList().orEmpty()
                VoiceNoteRules.orphans(names, left)
                    .filter { recording == null || it != "$recording.part" }
                    .forEach { File(d, it).delete() }
            }
        }
    }

    /** Whether [id] still has a recording to play. */
    fun hasRecording(context: Context, id: String): Boolean = File(dir(context), VoiceNoteRules.fileName(id)).exists()

    /**
     * [id]'s recording decrypted into the app's cache for playing (deleted by [clearPlayback] and on the next play);
     * null when there's none or it can't be read.
     */
    suspend fun playable(context: Context, id: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(dir(context), VoiceNoteRules.fileName(id))
            if (!src.exists()) return@runCatching null
            clearPlayback(context)
            File(context.cacheDir, "voice-note-play.m4a").apply { writeBytes(decrypt(src.readBytes())) }
        }.getOrNull()
    }

    /** Deletes the decrypted copy made for playing. */
    fun clearPlayback(context: Context) {
        runCatching { File(context.cacheDir, "voice-note-play.m4a").delete() }
    }
}
