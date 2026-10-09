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
import os.meka.core.domain.GroupDigestRules
import os.meka.core.domain.GroupMode
import os.meka.core.domain.MessageTriageRules
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.RequestWatch
import os.meka.core.domain.RequestWatchRules
import os.meka.core.domain.TriageSettings
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the notification listener held during work mode, plus the owner's family and always-notify lists, who MEKA
 * reads for requests and which messages it already read for them.
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

    /** Who MEKA reads for requests all day (Work mode → People → Watch for requests from); stays on this phone. */
    private val _watch = MutableStateFlow(RequestWatch())
    val watch: StateFlow<RequestWatch> = _watch.asStateFlow()

    /** Message ids already read for requests (id → when), so WhatsApp's re-posts never cost a second AI call. */
    private var requestSeen: Map<String, Long> = emptyMap()

    /** Message ids the messages assistant already triaged (id → when), for the same reason. */
    private var triageSeen: Map<String, Long> = emptyMap()

    /**
     * Busy groups' chatter for the digest (messages assistant, slice 2): kept here only, sealed, for a week; never
     * synced and never sent to the AI as it is (the digest's cards are built from it on the phone).
     */
    private val _digest = MutableStateFlow<List<CapturedItem>>(emptyList())
    val digest: StateFlow<List<CapturedItem>> = _digest.asStateFlow()

    /**
     * The messages assistant's settings (slice 4): each group's mode switched on its digest card (Digest unless
     * changed); the never-to-AI list arrives with slice 5's screen. Stays on this phone.
     */
    private val _triageSettings = MutableStateFlow(TriageSettings())
    val triageSettings: StateFlow<TriageSettings> = _triageSettings.asStateFlow()

    /** When Meka last caught up with each digest group (group key → when), so the digest shows only what's new. */
    private val _digestSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val digestSeen: StateFlow<Map<String, Long>> = _digestSeen.asStateFlow()

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

    fun setLists(lists: PeopleLists) = synchronized(lock) { _lists.value = lists.pruned(); save() }

    fun setWatch(watch: RequestWatch) = synchronized(lock) { _watch.value = watch; save() }

    fun requestSeen(): Set<String> = synchronized(lock) { requestSeen.keys }

    /** Marks [ids] read for requests (the old ones are forgotten after a week). */
    fun markRequestSeen(ids: Collection<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val now = nowMs()
        requestSeen = RequestWatchRules.pruneSeen(requestSeen + ids.associateWith { now }, now)
        save()
    }

    fun triageSeen(): Set<String> = synchronized(lock) { triageSeen.keys }

    /** Marks [ids] triaged (the old ones are forgotten after a week). */
    fun markTriageSeen(ids: Collection<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val now = nowMs()
        triageSeen = RequestWatchRules.pruneSeen(triageSeen + ids.associateWith { now }, now)
        save()
    }

    /** Keeps a Digest group's [items] for the digest (each once; a week at most). */
    fun keepForDigest(items: List<CapturedItem>) = synchronized(lock) {
        if (items.isEmpty()) return@synchronized
        _digest.value = MessageTriageRules.keepForDigest(_digest.value, items, nowMs())
        save()
    }

    /** The ids kept for the digest (to tell whether a notification's messages are all safely kept). */
    fun digestKept(): Set<String> = synchronized(lock) { _digest.value.mapTo(HashSet()) { it.id } }

    /** Sets [group]'s mode from its digest card (Digest · Normal · Ignore). */
    fun setGroupMode(group: String, mode: GroupMode) = synchronized(lock) {
        _triageSettings.value = _triageSettings.value.copy(groupModes = GroupDigestRules.setMode(_triageSettings.value.groupModes, group, mode))
        save()
    }

    /** Caught up with [groupKeys] now: their cards leave until something new comes. */
    fun caughtUp(groupKeys: Collection<String>) = synchronized(lock) {
        if (groupKeys.isEmpty()) return@synchronized
        _digestSeen.value = GroupDigestRules.caughtUp(_digestSeen.value, groupKeys, nowMs())
        save()
    }

    /** Takes back a "Caught up" (the undo bar): [before] as it was. */
    fun restoreDigestSeen(before: Map<String, Long>) = synchronized(lock) { _digestSeen.value = before; save() }

    private fun load() {
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) { return }
        val json = try { JSONObject(String(open(bytes), Charsets.UTF_8)) } catch (e: Exception) {
            // Unreadable (key lost with a Keystore wipe, or a torn write): start empty rather than crash at work.
            return
        }
        val cutoff = nowMs() - RETENTION_MS
        _items.value = json.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { readItem(a.getJSONObject(it)) } }
            .orEmpty().filter { it.atMs >= cutoff }
        val numbers = json.optJSONObject("numbers")?.let { o -> o.keys().asSequence().associateWith { o.optJSONArray(it).strings() } }.orEmpty()
        _lists.value = PeopleLists(
            family = json.optJSONArray("family").strings(),
            alwaysNotify = json.optJSONArray("alwaysNotify").strings(),
            numbers = numbers,
        )
        _watch.value = RequestWatch(
            people = json.optJSONArray("watchPeople").strings(),
            groups = json.optJSONArray("watchGroups").strings(),
        )
        requestSeen = RequestWatchRules.pruneSeen(
            json.optJSONObject("requestSeen")?.let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }.orEmpty(),
            nowMs(),
        )
        triageSeen = RequestWatchRules.pruneSeen(
            json.optJSONObject("triageSeen")?.let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }.orEmpty(),
            nowMs(),
        )
        _digest.value = MessageTriageRules.keepForDigest(
            emptyList(),
            json.optJSONArray("digest")?.let { a -> (0 until a.length()).mapNotNull { readItem(a.getJSONObject(it)) } }.orEmpty(),
            nowMs(),
        )
        _triageSettings.value = TriageSettings(
            groupModes = json.optJSONObject("groupModes")?.let { o -> o.keys().asSequence().associateWith { GroupMode.of(o.optString(it)) } }.orEmpty(),
        )
        _digestSeen.value = GroupDigestRules.caughtUp(
            json.optJSONObject("digestSeen")?.let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }.orEmpty(),
            emptyList(),
            nowMs(),
        )
    }

    private fun save() {
        val json = JSONObject()
            .put("v", 1)
            .put("items", JSONArray().also { a -> _items.value.forEach { a.put(writeItem(it)) } })
            .put("family", JSONArray(_lists.value.family.sorted()))
            .put("alwaysNotify", JSONArray(_lists.value.alwaysNotify.sorted()))
            .put("numbers", JSONObject().also { o -> _lists.value.numbers.forEach { (name, nums) -> o.put(name, JSONArray(nums.sorted())) } })
            .put("watchPeople", JSONArray(_watch.value.people.sorted()))
            .put("watchGroups", JSONArray(_watch.value.groups.sorted()))
            .put("requestSeen", JSONObject().also { o -> requestSeen.forEach { (id, at) -> o.put(id, at) } })
            .put("triageSeen", JSONObject().also { o -> triageSeen.forEach { (id, at) -> o.put(id, at) } })
            .put("digest", JSONArray().also { a -> _digest.value.forEach { a.put(writeItem(it)) } })
            .put("groupModes", JSONObject().also { o -> _triageSettings.value.groupModes.forEach { (g, m) -> o.put(g, m.wire) } })
            .put("digestSeen", JSONObject().also { o -> _digestSeen.value.forEach { (g, at) -> o.put(g, at) } })
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
