package os.meka.core.facade

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * News pictures kept in memory for the session (news, images slice): the most recently used [MAX_ENTRIES] (each at
 * most [MAX_BYTES], so at most about 3 MB), and keys the server had no picture for, not asked again for
 * [MISS_RETRY_MS] (the server may make it at its next refresh). Nothing is written to disk.
 */
class NewsImageCache {
    private val mutex = Mutex()
    private val hits = LinkedHashMap<String, ByteArray>()
    private val misses = HashMap<String, Long>()

    suspend fun get(key: String): ByteArray? = mutex.withLock {
        hits.remove(key)?.also { hits[key] = it } // most recently used last
    }

    suspend fun put(key: String, bytes: ByteArray) = mutex.withLock {
        hits.remove(key)
        hits[key] = bytes
        misses.remove(key)
        while (hits.size > MAX_ENTRIES) hits.remove(hits.keys.first())
    }

    suspend fun missed(key: String, nowMs: Long) = mutex.withLock {
        misses[key] = nowMs
        if (misses.size > MAX_ENTRIES * 2) misses.keys.toList().take(misses.size - MAX_ENTRIES).forEach(misses::remove)
    }

    suspend fun missedRecently(key: String, nowMs: Long): Boolean = mutex.withLock {
        val at = misses[key] ?: return@withLock false
        nowMs - at < MISS_RETRY_MS
    }

    suspend fun size(): Int = mutex.withLock { hits.size }

    companion object {
        const val MAX_ENTRIES = 100
        /** The server makes pictures of at most 30 KB; anything far bigger isn't one of them. */
        const val MAX_BYTES = 64 * 1024
        const val MISS_RETRY_MS = 10 * 60_000L
    }
}
