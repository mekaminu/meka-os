package os.meka.android.work

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import os.meka.core.domain.Capture
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.PeopleLists
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the notification listener held during work mode, plus the owner's family and always-notify lists.
 *
 * This file is the Fold's own copy (it spots WhatsApp's re-posts and keeps the lists, which stay on the phone); the
 * summary both apps show is synced separately ([os.meka.core.domain.HeldMessages], Needs Meka #10). The file is sealed with a
 * non-exportable Android Keystore AES-GCM key (ADR-002's pattern) and written atomically. Items older than
 * [RETENTION_MS] are dropped on load, so nothing lingers if the summary is never cleared.
 */
class CaptureStore(context: Context, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val lock = Any()

    private val _items = MutableStateFlow<List<CapturedItem>>(emptyList())
    val items: StateFlow<List<CapturedItem>> = _items.asStateFlow()

    private val _lists = MutableStateFlow(PeopleLists())
    val lists: StateFlow<PeopleLists> = _lists.asStateFlow()

    init {
        synchronized(lock) { load() }
    }

    /** Adds captured items; returns only the ones not seen before (for break-through alerts). */
    fun add(incoming: List<CapturedItem>): List<CapturedItem> = synchronized(lock) {
        val (all, fresh) = Capture.merge(_items.value, incoming)
        if (fresh.isNotEmpty()) { _items.value = all; save() }
        fresh
    }

    /** The owner has read the summary. Only MEKA's copy goes; WhatsApp and Messages are untouched. */
    fun clear() = synchronized(lock) { _items.value = emptyList(); save() }

    fun setLists(lists: PeopleLists) = synchronized(lock) { _lists.value = lists; save() }

    private fun load() {
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) { return }
        val json = try { JSONObject(String(open(bytes), Charsets.UTF_8)) } catch (e: Exception) {
            // Unreadable (key lost with a Keystore wipe, or a torn write): start empty rather than crash at work.
            return
        }
        val cutoff = nowMs() - RETENTION_MS
        _items.value = json.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { readItem(a.getJSONObject(it)) } }
            .orEmpty().filter { it.atMs >= cutoff }
        _lists.value = PeopleLists(
            family = json.optJSONArray("family").strings(),
            alwaysNotify = json.optJSONArray("alwaysNotify").strings(),
        )
    }

    private fun save() {
        val json = JSONObject()
            .put("v", 1)
            .put("items", JSONArray().also { a -> _items.value.forEach { a.put(writeItem(it)) } })
            .put("family", JSONArray(_lists.value.family.sorted()))
            .put("alwaysNotify", JSONArray(_lists.value.alwaysNotify.sorted()))
        val sealed = seal(json.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(sealed); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }

    private fun writeItem(i: CapturedItem) = JSONObject()
        .put("id", i.id).put("app", i.app.name).put("kind", i.kind.name).put("person", i.personName)
        .put("text", i.text ?: JSONObject.NULL).put("conversation", i.conversation ?: JSONObject.NULL).put("at", i.atMs)

    private fun readItem(o: JSONObject): CapturedItem? = try {
        CapturedItem(
            id = o.getString("id"),
            app = CaptureApp.valueOf(o.getString("app")),
            kind = CaptureKind.valueOf(o.getString("kind")),
            personName = o.getString("person"),
            text = if (o.isNull("text")) null else o.getString("text"),
            conversation = if (o.isNull("conversation")) null else o.getString("conversation"),
            atMs = o.getLong("at"),
        )
    } catch (e: Exception) { null }

    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

    // ---- Sealing ----

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

    private fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        private const val FILE_NAME = "work-captures.sealed"
        private const val ALIAS = "meka.capture.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
