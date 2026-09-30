package os.meka.core.sync

/**
 * Persistence for a device replica. Implementations: [InMemoryReplicaStore] (tests, previews) and the
 * SQLDelight/SQLCipher store in `core/data`. Both must pass `ReplicaStoreContract`.
 *
 * All methods are called inside [transaction]; implementations must make a transaction atomic.
 */
interface ReplicaStore {
    fun <T> transaction(block: () -> T): T

    fun op(opId: String): Op?
    /** Appends to the op log. [local] ops are queued for push until acknowledged. */
    fun appendOp(op: Op, local: Boolean)

    fun fieldState(key: FieldKey): FieldState
    fun setFieldState(key: FieldKey, state: FieldState)
    /** All fields with heads for an entity. */
    fun fieldHeads(ref: EntityRef): Map<String, List<Op>>
    fun entityIds(entityType: String): List<String>

    fun pendingPush(limit: Int): List<Op>
    fun markPushed(opIds: Collection<String>)
    fun pendingCount(): Int

    fun pullCursor(): Long
    fun setPullCursor(seq: Long)

    fun clockHighWater(): Hlc
    fun setClockHighWater(hlc: Hlc)
}

class InMemoryReplicaStore : ReplicaStore {
    private val ops = LinkedHashMap<String, Op>()
    private val pending = LinkedHashSet<String>()
    private val heads = HashMap<FieldKey, FieldState>()
    private val byEntity = HashMap<EntityRef, MutableSet<String>>()
    private var cursor = 0L
    private var highWater = Hlc.ZERO

    // Snapshot-based rollback keeps transaction semantics honest in tests.
    override fun <T> transaction(block: () -> T): T {
        val snapOps = LinkedHashMap(ops)
        val snapPending = LinkedHashSet(pending)
        val snapHeads = HashMap(heads)
        val snapEntity = byEntity.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        val snapCursor = cursor
        val snapHw = highWater
        try {
            return block()
        } catch (t: Throwable) {
            ops.clear(); ops.putAll(snapOps)
            pending.clear(); pending.addAll(snapPending)
            heads.clear(); heads.putAll(snapHeads)
            byEntity.clear(); byEntity.putAll(snapEntity)
            cursor = snapCursor
            highWater = snapHw
            throw t
        }
    }

    override fun op(opId: String) = ops[opId]
    override fun appendOp(op: Op, local: Boolean) {
        ops[op.opId] = op
        if (local) pending += op.opId
    }

    override fun fieldState(key: FieldKey) = heads[key] ?: FieldState.EMPTY
    override fun setFieldState(key: FieldKey, state: FieldState) {
        this.heads[key] = state
        byEntity.getOrPut(EntityRef(key.entityType, key.entityId)) { mutableSetOf() } += key.field
    }

    override fun fieldHeads(ref: EntityRef): Map<String, List<Op>> =
        byEntity[ref].orEmpty().associateWith { heads[FieldKey(ref.entityType, ref.entityId, it)]?.heads.orEmpty() }

    override fun entityIds(entityType: String) =
        byEntity.keys.filter { it.entityType == entityType }.map { it.entityId }.sorted()

    override fun pendingPush(limit: Int) = pending.asSequence().take(limit).map { ops.getValue(it) }.toList()
    override fun markPushed(opIds: Collection<String>) { pending.removeAll(opIds.toSet()) }
    override fun pendingCount() = pending.size

    override fun pullCursor() = cursor
    override fun setPullCursor(seq: Long) { cursor = seq }

    override fun clockHighWater() = highWater
    override fun setClockHighWater(hlc: Hlc) { highWater = hlc }

    /** Test helper: total ops in the log. */
    val opCount: Int get() = ops.size
}
