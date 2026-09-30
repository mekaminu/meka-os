package os.meka.core.sync

data class SyncReport(
    val pushed: Int,
    val rejected: Map<String, String>,
    val pulled: Int,
    val applyResults: Map<ApplyResult, Int>,
)

/** Observable sync status for the UI (quiet: shown only when it matters). */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Synced(val atMs: Long) : SyncStatus()
    data class Offline(val pending: Int, val nextAttemptAtMs: Long) : SyncStatus()
    data class Failing(val reason: String, val pending: Int) : SyncStatus()
}

/**
 * One sync round: push everything pending (in batches), then pull until caught up.
 * Every step is safe to repeat: pushes are idempotent on the server, pulls are idempotent on the replica,
 * and the cursor only advances after a page has been applied.
 */
class SyncClient(
    private val replica: Replica,
    private val transport: SyncTransport,
    private val batchSize: Int = 200,
    /** Called with ops the server permanently rejected; they are removed from the push queue. */
    private val onRejected: (Map<String, String>) -> Unit = {},
) {
    suspend fun syncOnce(): SyncReport {
        var pushed = 0
        val rejected = linkedMapOf<String, String>()
        while (true) {
            val batch = replica.pendingPush(batchSize)
            if (batch.isEmpty()) break
            val resp = transport.push(PushRequest(replica.householdId, replica.deviceId, batch))
            val done = resp.acknowledged + resp.rejected.keys
            if (done.isEmpty()) throw TransportException("server acknowledged nothing for a non-empty batch")
            replica.markPushed(done)
            pushed += resp.acknowledged.size
            rejected += resp.rejected
        }
        if (rejected.isNotEmpty()) onRejected(rejected)

        var pulled = 0
        val results = mutableMapOf<ApplyResult, Int>()
        while (true) {
            val page = transport.pull(PullRequest(replica.householdId, replica.deviceId, replica.pullCursor()))
            if (page.ops.isNotEmpty()) {
                replica.applyRemoteBatch(page.ops.map { it.op }).forEach { results[it] = (results[it] ?: 0) + 1 }
                replica.setPullCursor(page.lastSeq!!)
                pulled += page.ops.size
            }
            if (!page.hasMore) break
        }
        return SyncReport(pushed, rejected, pulled, results)
    }
}

/** Exponential backoff with full jitter, capped. [attempt] starts at 1. */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long = 2_000, capMs: Long = 15 * 60_000, random: (Long) -> Long): Long {
        require(attempt >= 1)
        val exp = if (attempt >= 30) capMs else minOf(capMs, baseMs shl (attempt - 1))
        return random(exp + 1)
    }
}
