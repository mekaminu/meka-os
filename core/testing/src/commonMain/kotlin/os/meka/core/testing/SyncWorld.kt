package os.meka.core.testing

import os.meka.core.domain.IdGenerator
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.Tasks
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.Replica
import os.meka.core.sync.SyncClient
import os.meka.core.sync.SyncReport
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random

/** Runs a suspend block that never actually suspends (in-memory transports). Keeps tests dependency-free. */
fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "block suspended; runSuspend only supports non-suspending transports" }.getOrThrow()
}

class ManualClock(var nowMs: Long = 1_790_000_000_000L) {
    fun advance(ms: Long) { nowMs += ms }
}

/**
 * In-memory transport to a [SyncService] with fault injection:
 * - [online] = false fails every call before reaching the server.
 * - [dropResponses] > 0 lets the server process the request, then throws: the classic "did my write land?" case.
 */
class FaultyTransport(private val service: SyncService) : SyncTransport {
    var online = true
    var dropResponses = 0
    var pushCalls = 0
        private set

    override suspend fun push(request: PushRequest): PushResponse {
        pushCalls++
        if (!online) throw TransportException("offline")
        val resp = service.push(request)
        if (dropResponses > 0) { dropResponses--; throw TransportException("connection reset after server commit") }
        return resp
    }

    override suspend fun pull(request: PullRequest): PullResponse {
        if (!online) throw TransportException("offline")
        return service.pull(request)
    }
}

class Device(
    val name: String,
    val replica: Replica,
    val store: InMemoryReplicaStore,
    val transport: FaultyTransport,
    val tasks: Tasks,
    pullPageSize: Int,
) {
    private val client = SyncClient(replica, transport, batchSize = pullPageSize)

    fun sync(): SyncReport = runSuspend { client.syncOnce() }

    /** Retries like the platform scheduler would, until success or [maxAttempts]. */
    fun syncWithRetry(maxAttempts: Int = 10): SyncReport {
        var last: Throwable? = null
        repeat(maxAttempts) {
            try { return sync() } catch (e: TransportException) { last = e }
        }
        throw AssertionError("sync did not succeed in $maxAttempts attempts", last)
    }

    fun goOffline() { transport.online = false }
    fun goOnline() { transport.online = true }
}

/** A household with a sync server and any number of devices sharing a manual clock (skew configurable per device). */
class SyncWorld(seed: Int = 42, val householdId: String = "hh1") {
    val clock = ManualClock()
    val random = Random(seed)
    val serverStore = InMemoryServerOpStore()
    val service = SyncService(serverStore)
    private val ids = IdGenerator(random)

    fun device(name: String, skewMs: Long = 0, batchSize: Int = 200): Device {
        val store = InMemoryReplicaStore()
        val hlc = HlcClock(name, { clock.nowMs + skewMs })
        val replica = Replica(householdId, name, hlc, store, MekaSchema, ids::next)
        val transport = FaultyTransport(service)
        val tasks = Tasks(replica, ids::next) { clock.nowMs + skewMs }
        return Device(name, replica, store, transport, tasks, batchSize)
    }
}
