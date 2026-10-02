package os.meka.core.sync

data class PushRequest(val householdId: String, val deviceId: String, val ops: List<Op>)

data class PushResponse(
    /** Ops now durably stored on the server (newly or previously). Safe to mark as pushed. */
    val acknowledged: List<String>,
    /** Ops the server will never accept, with reasons. The client quarantines these; they are not retried. */
    val rejected: Map<String, String>,
)

data class PullRequest(val householdId: String, val deviceId: String, val afterSeq: Long, val limit: Int = 500)

data class SequencedOp(val seq: Long, val op: Op)

data class PullResponse(val ops: List<SequencedOp>, val hasMore: Boolean) {
    val lastSeq: Long? get() = ops.lastOrNull()?.seq
}

/** Transport between a device and the sync service. Implementations: Ktor HTTP client, in-memory for tests. */
interface SyncTransport {
    suspend fun push(request: PushRequest): PushResponse
    suspend fun pull(request: PullRequest): PullResponse

    /**
     * Long-poll: returns as soon as the server has ops after [PullRequest.afterSeq] (true), or false when the server's
     * wait window ends with nothing new. Lets an open app see the other device's edits within about a second.
     * Returns null when the transport cannot wait; callers then fall back to periodic sync.
     */
    suspend fun awaitChanges(request: PullRequest): Boolean? = null
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Server-side durable op store. The backend implements this on Postgres with `op_id` UNIQUE. */
interface ServerOpStore {
    fun <T> transaction(block: () -> T): T
    fun find(householdId: String, opId: String): Op?
    /** Appends and returns the assigned, strictly increasing per-household sequence. */
    fun append(op: Op): Long
    fun after(householdId: String, afterSeq: Long, limit: Int): List<SequencedOp>
    fun isDeviceAuthorised(householdId: String, deviceId: String): Boolean
}

/**
 * Reference sync service logic, shared by the Ktor backend and tests. It is a durable, deduplicating
 * op relay; merge happens on each replica with the same [Merge] code.
 */
class SyncService(private val store: ServerOpStore) {

    fun push(req: PushRequest): PushResponse {
        require(store.isDeviceAuthorised(req.householdId, req.deviceId)) { "device not authorised for household" }
        return store.transaction {
            val ack = mutableListOf<String>()
            val rejected = linkedMapOf<String, String>()
            for (op in req.ops) {
                val invalid = op.validationError()
                when {
                    op.householdId != req.householdId -> rejected[op.opId] = "household mismatch"
                    op.deviceId != req.deviceId -> rejected[op.opId] = "op authored by another device"
                    invalid != null -> rejected[op.opId] = invalid
                    else -> {
                        val existing = store.find(req.householdId, op.opId)
                        when {
                            existing == null -> { store.append(op); ack += op.opId }
                            existing == op -> ack += op.opId // retry of an already stored op: idempotent
                            else -> rejected[op.opId] = "opId reused with different content"
                        }
                    }
                }
            }
            PushResponse(ack, rejected)
        }
    }

    fun pull(req: PullRequest): PullResponse {
        require(store.isDeviceAuthorised(req.householdId, req.deviceId)) { "device not authorised for household" }
        val limit = req.limit.coerceIn(1, 1000)
        val page = store.after(req.householdId, req.afterSeq, limit + 1)
        return PullResponse(page.take(limit), hasMore = page.size > limit)
    }
}

class InMemoryServerOpStore(private val authorised: Set<Pair<String, String>>? = null) : ServerOpStore {
    private val log = mutableListOf<SequencedOp>()
    private val index = HashMap<Pair<String, String>, Op>()
    private val seqByHousehold = HashMap<String, Long>()

    override fun <T> transaction(block: () -> T): T = block()
    override fun find(householdId: String, opId: String) = index[householdId to opId]
    override fun append(op: Op): Long {
        val seq = (seqByHousehold[op.householdId] ?: 0L) + 1
        seqByHousehold[op.householdId] = seq
        log += SequencedOp(seq, op)
        index[op.householdId to op.opId] = op
        return seq
    }

    override fun after(householdId: String, afterSeq: Long, limit: Int) =
        log.asSequence().filter { it.op.householdId == householdId && it.seq > afterSeq }.take(limit).toList()

    override fun isDeviceAuthorised(householdId: String, deviceId: String) =
        authorised == null || (householdId to deviceId) in authorised

    val size: Int get() = log.size
}
