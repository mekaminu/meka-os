package os.meka.backend

import os.meka.core.wire.WireCodec
import os.meka.core.wire.WireCodec.AppRelease
import java.security.MessageDigest
import java.util.Base64
import javax.sql.DataSource

/** A release as stored: complete once every chunk arrived and the whole file matched its SHA-256. */
data class StoredRelease(val release: AppRelease, val complete: Boolean)

/** Where published app builds live (build plan M1: self-updating phone app). Per household and platform. */
interface ReleaseStore {
    fun find(householdId: String, platform: String, versionCode: Long): StoredRelease?
    fun begin(householdId: String, release: AppRelease, publishedBy: String)
    fun putChunk(householdId: String, platform: String, versionCode: Long, index: Int, data: ByteArray)
    fun chunksStored(householdId: String, platform: String, versionCode: Long): Int
    fun chunk(householdId: String, platform: String, versionCode: Long, index: Int): ByteArray?
    fun markComplete(householdId: String, platform: String, versionCode: Long)
    fun latestComplete(householdId: String, platform: String): AppRelease?
    fun discard(householdId: String, platform: String, versionCode: Long)
    /** Keeps the newest [keep] complete releases and anything newer still uploading; drops the rest. */
    fun prune(householdId: String, platform: String, keep: Int)
}

/**
 * Publishing and fetching app builds. Any device of the household with a registered signing key may publish (in
 * practice the Mac, which built the APK); the phone fetches. The server checks sizes and the whole-file SHA-256; the
 * phone checks the hash again, and Android refuses an update not signed with the installed app's key, so a build the
 * owner didn't sign can never be installed even if this server were compromised.
 */
class Releases(
    private val store: ReleaseStore,
    private val keep: Int = 2,
    /** Called once when a build becomes complete (not for a re-publish of the same build); failures are ignored. */
    private val onPublished: (who: DeviceIdentity, release: AppRelease) -> Unit = { _, _ -> },
) {
    sealed class Upload {
        data class Ack(val received: Int, val complete: Boolean) : Upload()
        /** The build number is already taken by different contents, or isn't newer than what's published. */
        data class Conflict(val reason: String) : Upload()
        data class Rejected(val reason: String) : Upload()
    }

    fun upload(who: DeviceIdentity, chunk: WireCodec.ReleaseChunk): Upload {
        val r = chunk.release
        val hh = who.householdId
        val bytes = runCatching { Base64.getDecoder().decode(chunk.dataB64) }.getOrNull()
        if (bytes == null || bytes.size != WireCodec.releaseChunkSize(r.sizeBytes, chunk.index)) return Upload.Rejected("chunk size")
        var existing = store.find(hh, r.platform, r.versionCode)
        if (existing != null && existing.release != r) {
            if (existing.complete) return Upload.Conflict("build ${r.versionCode} is already published with different contents")
            store.discard(hh, r.platform, r.versionCode) // an earlier, abandoned attempt at the same build
            existing = null
        }
        if (existing?.complete == true) return Upload.Ack(r.chunkCount, true) // re-publishing the same build
        val latest = store.latestComplete(hh, r.platform)
        if (latest != null && r.versionCode <= latest.versionCode) {
            return Upload.Conflict("build ${r.versionCode} isn't newer than the published build ${latest.versionCode}")
        }
        if (existing == null) store.begin(hh, r, who.deviceId)
        store.putChunk(hh, r.platform, r.versionCode, chunk.index, bytes)
        val stored = store.chunksStored(hh, r.platform, r.versionCode)
        if (stored < r.chunkCount) return Upload.Ack(stored, false)
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until r.chunkCount) md.update(store.chunk(hh, r.platform, r.versionCode, i) ?: return Upload.Ack(i, false))
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != r.sha256) {
            store.discard(hh, r.platform, r.versionCode)
            return Upload.Rejected("contents didn't match their hash")
        }
        store.markComplete(hh, r.platform, r.versionCode)
        store.prune(hh, r.platform, keep)
        runCatching { onPublished(who, r) } // the build is published either way
        return Upload.Ack(r.chunkCount, true)
    }

    fun latest(who: DeviceIdentity, platform: String): AppRelease? = store.latestComplete(who.householdId, platform)

    /** A chunk of a complete release only; null if there is no such release or chunk. */
    fun chunk(who: DeviceIdentity, ref: WireCodec.ChunkRef): ByteArray? {
        val r = store.find(who.householdId, ref.platform, ref.versionCode) ?: return null
        if (!r.complete || ref.index >= r.release.chunkCount) return null
        return store.chunk(who.householdId, ref.platform, ref.versionCode, ref.index)
    }
}

class InMemoryReleaseStore : ReleaseStore {
    private data class Key(val hh: String, val platform: String, val code: Long)
    private val releases = HashMap<Key, StoredRelease>()
    private val chunks = HashMap<Key, HashMap<Int, ByteArray>>()

    @Synchronized override fun find(householdId: String, platform: String, versionCode: Long) = releases[Key(householdId, platform, versionCode)]
    @Synchronized override fun begin(householdId: String, release: AppRelease, publishedBy: String) {
        releases[Key(householdId, release.platform, release.versionCode)] = StoredRelease(release, false)
    }
    @Synchronized override fun putChunk(householdId: String, platform: String, versionCode: Long, index: Int, data: ByteArray) {
        chunks.getOrPut(Key(householdId, platform, versionCode)) { HashMap() }[index] = data
    }
    @Synchronized override fun chunksStored(householdId: String, platform: String, versionCode: Long) = chunks[Key(householdId, platform, versionCode)]?.size ?: 0
    @Synchronized override fun chunk(householdId: String, platform: String, versionCode: Long, index: Int) = chunks[Key(householdId, platform, versionCode)]?.get(index)
    @Synchronized override fun markComplete(householdId: String, platform: String, versionCode: Long) {
        val k = Key(householdId, platform, versionCode)
        releases[k]?.let { releases[k] = it.copy(complete = true) }
    }
    @Synchronized override fun latestComplete(householdId: String, platform: String) =
        releases.filter { it.key.hh == householdId && it.key.platform == platform && it.value.complete }.maxByOrNull { it.key.code }?.value?.release
    @Synchronized override fun discard(householdId: String, platform: String, versionCode: Long) {
        val k = Key(householdId, platform, versionCode)
        releases.remove(k); chunks.remove(k)
    }
    @Synchronized override fun prune(householdId: String, platform: String, keep: Int) {
        val mine = releases.keys.filter { it.hh == householdId && it.platform == platform }
        val kept = mine.filter { releases[it]!!.complete }.sortedByDescending { it.code }.take(keep)
        val floor = kept.minOfOrNull { it.code } ?: return
        mine.filter { it !in kept && (releases[it]!!.complete || it.code < floor) }.forEach { releases.remove(it); chunks.remove(it) }
    }
}

class PostgresReleaseStore(private val ds: DataSource) : ReleaseStore {
    override fun find(householdId: String, platform: String, versionCode: Long): StoredRelease? = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT version_name, sha256, size_bytes, chunk_count, completed_at IS NOT NULL FROM app_release WHERE household_id = ? AND platform = ? AND version_code = ?",
        ).use { st ->
            st.setString(1, householdId); st.setString(2, platform); st.setLong(3, versionCode)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else StoredRelease(AppRelease(platform, versionCode, rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)), rs.getBoolean(5))
            }
        }
    }

    override fun begin(householdId: String, release: AppRelease, publishedBy: String) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO app_release(household_id, platform, version_code, version_name, sha256, size_bytes, chunk_count, published_by)
               VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
        ).use {
            it.setString(1, householdId); it.setString(2, release.platform); it.setLong(3, release.versionCode)
            it.setString(4, release.versionName); it.setString(5, release.sha256); it.setLong(6, release.sizeBytes)
            it.setInt(7, release.chunkCount); it.setString(8, publishedBy)
            it.executeUpdate()
        }
        Unit
    }

    override fun putChunk(householdId: String, platform: String, versionCode: Long, index: Int, data: ByteArray) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO app_release_chunk(household_id, platform, version_code, idx, data) VALUES (?,?,?,?,?)
               ON CONFLICT (household_id, platform, version_code, idx) DO UPDATE SET data = EXCLUDED.data""",
        ).use {
            it.setString(1, householdId); it.setString(2, platform); it.setLong(3, versionCode); it.setInt(4, index); it.setBytes(5, data)
            it.executeUpdate()
        }
        Unit
    }

    override fun chunksStored(householdId: String, platform: String, versionCode: Long): Int = ds.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM app_release_chunk WHERE household_id = ? AND platform = ? AND version_code = ?").use { st ->
            st.setString(1, householdId); st.setString(2, platform); st.setLong(3, versionCode)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    override fun chunk(householdId: String, platform: String, versionCode: Long, index: Int): ByteArray? = ds.connection.use { c ->
        c.prepareStatement("SELECT data FROM app_release_chunk WHERE household_id = ? AND platform = ? AND version_code = ? AND idx = ?").use { st ->
            st.setString(1, householdId); st.setString(2, platform); st.setLong(3, versionCode); st.setInt(4, index)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    }

    override fun markComplete(householdId: String, platform: String, versionCode: Long) = ds.connection.use { c ->
        c.prepareStatement("UPDATE app_release SET completed_at = now() WHERE household_id = ? AND platform = ? AND version_code = ?").use {
            it.setString(1, householdId); it.setString(2, platform); it.setLong(3, versionCode); it.executeUpdate()
        }
        Unit
    }

    override fun latestComplete(householdId: String, platform: String): AppRelease? = ds.connection.use { c ->
        c.prepareStatement(
            """SELECT version_code, version_name, sha256, size_bytes, chunk_count FROM app_release
               WHERE household_id = ? AND platform = ? AND completed_at IS NOT NULL ORDER BY version_code DESC LIMIT 1""",
        ).use { st ->
            st.setString(1, householdId); st.setString(2, platform)
            st.executeQuery().use { rs ->
                if (rs.next()) AppRelease(platform, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5)) else null
            }
        }
    }

    override fun discard(householdId: String, platform: String, versionCode: Long) = ds.connection.use { c ->
        c.prepareStatement("DELETE FROM app_release WHERE household_id = ? AND platform = ? AND version_code = ?").use {
            it.setString(1, householdId); it.setString(2, platform); it.setLong(3, versionCode); it.executeUpdate()
        }
        Unit
    }

    override fun prune(householdId: String, platform: String, keep: Int) = ds.connection.use { c ->
        // Older complete builds beyond the newest [keep], and abandoned uploads older than the oldest kept build.
        c.prepareStatement(
            """WITH kept AS (
                 SELECT version_code FROM app_release WHERE household_id = ? AND platform = ? AND completed_at IS NOT NULL
                 ORDER BY version_code DESC LIMIT ?)
               DELETE FROM app_release WHERE household_id = ? AND platform = ? AND version_code NOT IN (SELECT version_code FROM kept)
                 AND (completed_at IS NOT NULL OR version_code < (SELECT min(version_code) FROM kept))""",
        ).use {
            it.setString(1, householdId); it.setString(2, platform); it.setInt(3, keep)
            it.setString(4, householdId); it.setString(5, platform)
            it.executeUpdate()
        }
        Unit
    }
}
