package os.meka.core.sync

/** Materialised view of one entity: winning value per field. */
data class EntitySnapshot(
    val ref: EntityRef,
    val householdId: String,
    val fields: Map<String, FieldValue>,
    val updatedHlc: Hlc,
) {
    operator fun get(field: String): FieldValue = fields[field] ?: FieldValue.Null
    val deleted: Boolean get() = this[DELETED_FIELD] == FieldValue.Bool(true)
}

const val DELETED_FIELD = "deleted"

enum class ApplyResult { APPLIED, DUPLICATE, REJECTED_INTEGRITY, REJECTED_INVALID, REJECTED_CLOCK, REJECTED_HOUSEHOLD }

fun interface ReplicaListener {
    fun onChanged(refs: Set<EntityRef>)
}

/**
 * A device's replica of one household's life graph (ADR-003). Every write goes through [commitLocal];
 * every remote change goes through [applyRemote]. Both are idempotent and order-independent.
 */
class Replica(
    val householdId: String,
    val deviceId: String,
    private val clock: HlcClock,
    private val store: ReplicaStore,
    private val schema: SchemaRegistry,
    private val newOpId: () -> String,
) {
    private val listeners = mutableListOf<ReplicaListener>()

    init {
        clock.restore(store.clockHighWater())
    }

    fun addListener(l: ReplicaListener) { listeners += l }
    fun removeListener(l: ReplicaListener) { listeners -= l }

    /**
     * Commits local edits to one entity atomically. Each changed field becomes one op whose base is the
     * heads the user could see, so a local edit supersedes every head, which resolves any conflict on that field.
     * Fields whose value is unchanged (and not in conflict) produce no op.
     */
    fun commitLocal(entityType: String, entityId: String, changes: Map<String, FieldValue>): List<Op> {
        require(changes.isNotEmpty()) { "no changes" }
        val ops = store.transaction {
            val out = mutableListOf<Op>()
            for ((field, value) in changes) {
                val key = FieldKey(entityType, entityId, field)
                val state = store.fieldState(key)
                val heads = state.heads
                val policy = schema.policyFor(entityType, field)
                val current = Merge.winner(heads, policy)
                val inConflict = Merge.conflict(heads, policy) != null
                if (current != null && current.value == value && !inConflict && heads.size == 1) continue
                val op = Op(
                    opId = newOpId(),
                    householdId = householdId,
                    entityType = entityType,
                    entityId = entityId,
                    field = field,
                    value = value,
                    hlc = clock.now(),
                    baseOpIds = Merge.baseFor(heads),
                    deviceId = deviceId,
                )
                op.validationError()?.let { throw IllegalArgumentException("Invalid local op: $it") }
                store.appendOp(op, local = true)
                store.setFieldState(key, state.add(op))
                out += op
            }
            if (out.isNotEmpty()) store.setClockHighWater(clock.latest)
            out
        }
        if (ops.isNotEmpty()) notify(setOf(EntityRef(entityType, entityId)))
        return ops
    }

    fun applyRemote(op: Op): ApplyResult = applyRemoteBatch(listOf(op)).single()

    fun applyRemoteBatch(batch: List<Op>): List<ApplyResult> {
        val changed = mutableSetOf<EntityRef>()
        val results = store.transaction {
            batch.map { op ->
                val existing = store.op(op.opId)
                when {
                    existing != null -> if (existing == op) ApplyResult.DUPLICATE else ApplyResult.REJECTED_INTEGRITY
                    op.householdId != householdId -> ApplyResult.REJECTED_HOUSEHOLD
                    op.validationError() != null -> ApplyResult.REJECTED_INVALID
                    else -> try {
                        clock.receive(op.hlc)
                        store.appendOp(op, local = false)
                        val key = op.key
                        store.setFieldState(key, store.fieldState(key).add(op))
                        changed += EntityRef(op.entityType, op.entityId)
                        ApplyResult.APPLIED
                    } catch (e: ClockDriftException) {
                        ApplyResult.REJECTED_CLOCK
                    }
                }
            }.also { store.setClockHighWater(clock.latest) }
        }
        if (changed.isNotEmpty()) notify(changed)
        return results
    }

    fun entity(entityType: String, entityId: String): EntitySnapshot? {
        val ref = EntityRef(entityType, entityId)
        val fieldHeads = store.fieldHeads(ref)
        if (fieldHeads.isEmpty()) return null
        var updated = Hlc.ZERO
        val fields = fieldHeads.mapNotNull { (field, heads) ->
            val w = Merge.winner(heads, schema.policyFor(entityType, field)) ?: return@mapNotNull null
            heads.forEach { if (it.hlc > updated) updated = it.hlc }
            field to w.value
        }.toMap()
        return EntitySnapshot(ref, householdId, fields, updated)
    }

    /** Non-deleted entities of a type. */
    fun entities(entityType: String, includeDeleted: Boolean = false): List<EntitySnapshot> =
        store.entityIds(entityType).mapNotNull { entity(entityType, it) }.filter { includeDeleted || !it.deleted }

    /** Unresolved user-visible conflicts across the given entity types. */
    fun conflicts(vararg entityTypes: String): List<Conflict> =
        entityTypes.flatMap { t -> store.entityIds(t).flatMap { conflictsFor(t, it) } }

    fun conflictsFor(entityType: String, entityId: String): List<Conflict> =
        store.fieldHeads(EntityRef(entityType, entityId)).mapNotNull { (field, heads) ->
            Merge.conflict(heads, schema.policyFor(entityType, field))
        }

    fun pendingPushCount(): Int = store.pendingCount()
    internal fun pendingPush(limit: Int) = store.pendingPush(limit)
    internal fun markPushed(opIds: Collection<String>) = store.transaction { store.markPushed(opIds) }
    internal fun pullCursor() = store.pullCursor()
    internal fun setPullCursor(seq: Long) = store.transaction { store.setPullCursor(seq) }

    private fun notify(refs: Set<EntityRef>) = listeners.toList().forEach { it.onChanged(refs) }
}
