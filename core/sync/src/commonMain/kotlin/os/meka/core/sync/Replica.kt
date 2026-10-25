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

/**
 * One field a local commit changed while [Replica.recording] was watching: what it was, what it became, and whether the
 * entity didn't exist before (Earned trust: an automatic action records exactly what it changed, for the activity log).
 */
data class RecordedChange(
    val entityType: String,
    val entityId: String,
    val field: String,
    val before: FieldValue,
    val after: FieldValue,
    val created: Boolean,
)

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

    private var recorder: MutableList<RecordedChange>? = null

    /**
     * Runs [block] and returns what it returned with every field its local commits changed, in order (remote changes
     * are never recorded). Not nested: one recording at a time on a replica.
     */
    /** True while [recording] runs (so an automatic action never starts inside another's recorded change). */
    val isRecording: Boolean get() = recorder != null

    fun <T> recording(block: () -> T): Pair<T, List<RecordedChange>> {
        check(recorder == null) { "already recording" }
        val out = mutableListOf<RecordedChange>()
        recorder = out
        try {
            return block() to out.toList()
        } finally {
            recorder = null
        }
    }

    /** While [batched] runs: the entities its commits changed, told to listeners once at its end. */
    private var batchRefs: MutableSet<EntityRef>? = null

    /**
     * Runs [block]'s local commits as one store transaction and tells listeners once, at the end, about every entity
     * they changed (Money manager, slice 1: a statement of 2,000 lines is one refresh, not 2,000). Nested calls join the
     * outer one. Each commit is still its own ops, exactly as [commitLocal] alone makes them.
     */
    fun <T> batched(block: () -> T): T {
        if (batchRefs != null) return block()
        val refs = mutableSetOf<EntityRef>()
        batchRefs = refs
        val result = try {
            store.transaction { block() }
        } catch (t: Throwable) {
            // The store rolled the whole batch back: nothing read inside it may be kept.
            forgetAll()
            throw t
        } finally {
            batchRefs = null
        }
        if (refs.isNotEmpty()) {
            val rec = recorder
            recorder = null
            try { notify(refs) } finally { recorder = rec }
        }
        return result
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
        val rec = recorder
        val ops = try { store.transaction {
            val out = mutableListOf<Op>()
            val created = rec != null && store.fieldHeads(EntityRef(entityType, entityId)).isEmpty()
            val recorded = mutableListOf<RecordedChange>()
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
                if (rec != null) recorded += RecordedChange(entityType, entityId, field, current?.value ?: FieldValue.Null, value, created)
            }
            if (out.isNotEmpty()) store.setClockHighWater(clock.latest)
            rec?.addAll(recorded)
            out
        } } finally {
            // Written, or rolled back half way: either way this type is read afresh next time.
            forget(entityType)
        }
        val batch = batchRefs
        if (ops.isNotEmpty() && batch != null) {
            batch += EntityRef(entityType, entityId)
        } else if (ops.isNotEmpty()) {
            // What listeners write in answer to this change is theirs, not part of the recorded action.
            recorder = null
            try { notify(setOf(EntityRef(entityType, entityId))) } finally { recorder = rec }
        }
        return ops
    }

    fun applyRemote(op: Op): ApplyResult = applyRemoteBatch(listOf(op)).single()

    fun applyRemoteBatch(batch: List<Op>): List<ApplyResult> {
        val changed = mutableSetOf<EntityRef>()
        val results = try { store.transaction {
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
        } } finally {
            batch.mapTo(HashSet()) { it.entityType }.forEach { forget(it) }
        }
        if (changed.isNotEmpty()) {
            notifyingRemote = true
            try { notify(changed) } finally { notifyingRemote = false }
        }
        return results
    }

    /**
     * True only while listeners are being told about other devices' changes ([applyRemoteBatch]), never for this
     * device's own commits (taps feel instant, 2026-10-25: a listener can put off a sync's page-by-page refreshes).
     */
    var notifyingRemote: Boolean = false
        private set

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

    /**
     * Taps feel instant (2026-10-25): each type's entities as last read, kept until a write to that type (local or
     * remote, or a rolled-back batch). The views read the same types many times a refresh (the 12,000 emails eight
     * times or so), and a tap on a task used to make every one of those reads merge every email's fields again. The
     * store is only ever written through this replica, so nothing else can change what is kept. Maps are replaced,
     * never changed in place, so a reader on another thread sees an old map or a new one, never a broken one.
     */
    private var kept: Map<String, KeptType> = emptyMap()

    private class KeptType(val all: List<EntitySnapshot>, val live: List<EntitySnapshot>) {
        var decoded: Map<String, Any?> = emptyMap()
    }

    private fun forget(entityType: String) {
        val k = kept
        if (entityType in k) kept = k - entityType
    }

    private fun forgetAll() { kept = emptyMap() }

    private fun keptType(entityType: String): KeptType {
        kept[entityType]?.let { return it }
        val all = store.entityIds(entityType).mapNotNull { entity(entityType, it) }
        val t = KeptType(all, all.filter { !it.deleted })
        kept = kept + (entityType to t)
        return t
    }

    /** Non-deleted entities of a type (the same list, unchanged, until a write to the type). */
    fun entities(entityType: String, includeDeleted: Boolean = false): List<EntitySnapshot> =
        keptType(entityType).let { if (includeDeleted) it.all else it.live }

    /**
     * [decode] of a type's non-deleted entities, kept with them under [key] until a write to the type, so the same
     * reading (emails into the mail list) is done once per change, not once per call. [decode] must depend on the
     * entities alone (no clock, no other type).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> decoded(entityType: String, key: String, decode: (List<EntitySnapshot>) -> T): T {
        val t = keptType(entityType)
        val d = t.decoded
        if (key in d) return d[key] as T
        val v = decode(t.live)
        // Only kept if the type wasn't written while decoding (a decode never writes, but be sure).
        if (kept[entityType] === t) t.decoded = d + (key to v)
        return v
    }

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
