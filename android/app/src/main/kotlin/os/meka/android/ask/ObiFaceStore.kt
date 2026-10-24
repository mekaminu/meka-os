package os.meka.android.ask

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.meka.core.domain.FaceFileRules
import os.meka.core.facade.MekaCore
import os.meka.core.wire.FaceCodec
import java.io.File

/**
 * Obi's face, slice 3: the face's seven files kept in the app's own private storage (never in the repository, never
 * shared). At each app open [refresh] asks Navilife's server for its list when [FaceFileRules.due] (weekly; sooner while
 * nothing or only part is there), fetches only the files whose tag changed and lets go of any the server no longer has.
 * [ready] turns true only with all seven kept; [on] is the Face setting (on by default). Until both, the orb stays.
 */
object ObiFaceStore {
    private const val DIR = "obi-face"
    private const val INDEX = "index.txt"
    /** The last look at the server's list (Health's face line, Meka 2026-10-24 13:12). */
    private const val CHECK = "check.txt"
    private const val PREFS = "obi_face"
    private const val ON_KEY = "on"

    private val allowed = FaceCodec.FILES
    private val mutex = Mutex()
    private val _ready = MutableStateFlow(false)
    private val _on = MutableStateFlow(true)
    @Volatile private var loaded = false

    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** One kept file, or null for a name outside the seven or one not kept. */
    fun file(context: Context, name: String): File? =
        if (!FaceCodec.allowed(name)) null else File(dir(context), name).takeIf { it.isFile && it.length() > 0 }

    /** Reads the index and the setting once (cheap; called by whatever shows the face first). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON_KEY, true)
        _ready.value = readIndex(context).complete
    }

    /** Health → Obi's voice's face line: the setting, how many are kept, the last look. */
    fun health(context: Context): os.meka.core.domain.FaceHealth {
        load(context)
        val kept = readIndex(context).kept.size
        val check = runCatching { File(dir(context), CHECK).takeIf { it.isFile }?.readText() }.getOrNull()
        return os.meka.core.domain.FaceHealth(_on.value, kept, os.meka.core.domain.FaceHealthRules.decode(check))
    }

    private fun writeCheck(context: Context, check: os.meka.core.domain.FaceCheck) {
        runCatching {
            val d = dir(context).apply { mkdirs() }
            val tmp = File(d, "$CHECK.part")
            tmp.writeText(os.meka.core.domain.FaceHealthRules.encode(check))
            if (!tmp.renameTo(File(d, CHECK))) tmp.delete()
        }
    }

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON_KEY, on).apply()
        _on.value = on
    }

    /** The index, with any file that has gone missing from storage left out (so it's fetched again). */
    private fun readIndex(context: Context): FaceFileRules.Index {
        val text = runCatching { File(dir(context), INDEX).takeIf { it.isFile }?.readText() }.getOrNull()
        val index = FaceFileRules.decode(text, allowed)
        val kept = index.kept.filterKeys { file(context, it) != null }
        return index.copy(kept = kept, complete = FaceFileRules.ready(kept.keys, allowed))
    }

    private fun writeIndex(context: Context, index: FaceFileRules.Index) {
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, "$INDEX.part")
        tmp.writeText(FaceFileRules.encode(index))
        if (!tmp.renameTo(File(d, INDEX))) tmp.delete()
    }

    /**
     * Looks at the server's list when it's due, or now with [force] (Health's Look again); never throws (a failure keeps
     * what's kept and looks again later). Called at every return to the app (Meka 2026-10-24 13:12: it ran only when
     * the app was first opened, so a phone that kept Navilife in memory never looked again after he uploaded the files).
     */
    suspend fun refresh(context: Context, core: MekaCore, force: Boolean = false) {
        load(context)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { refreshLocked(context, core, force) }
            }
        }
    }

    private suspend fun refreshLocked(context: Context, core: MekaCore, force: Boolean) {
        val index = readIndex(context)
        val now = System.currentTimeMillis()
        if (!force && !FaceFileRules.due(index, now)) {
            _ready.value = index.complete
            return
        }
        val offered = core.faceManifest()
        if (offered == null) {
            writeCheck(context, os.meka.core.domain.FaceCheck(now, null))
            // With the face kept, the next open simply asks again; without it, not for another few hours.
            if (!index.complete) writeIndex(context, FaceFileRules.failed(index, now))
            _ready.value = index.complete
            return
        }
        val plan = FaceFileRules.plan(index, offered, allowed)
        val d = dir(context).apply { mkdirs() }
        plan.drop.forEach { File(d, it).delete() }
        val fetched = mutableMapOf<String, String>()
        for (name in plan.fetch) {
            val bytes = core.faceFile(name) ?: continue
            val tmp = File(d, "$name.part")
            tmp.writeBytes(bytes)
            val dest = File(d, name)
            dest.delete()
            if (tmp.renameTo(dest)) fetched[name] = offered.getValue(name) else tmp.delete()
        }
        writeCheck(context, os.meka.core.domain.FaceCheck(now, offered.keys.toList(), plan.fetch.filter { it !in fetched }))
        val next = FaceFileRules.after(index, plan, fetched, allowed, now)
        writeIndex(context, next)
        _ready.value = next.complete
    }
}
