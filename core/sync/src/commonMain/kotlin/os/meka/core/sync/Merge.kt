package os.meka.core.sync

/** How concurrent heads of one field are resolved (ADR-003). */
sealed class MergePolicy {
    /** Highest HLC wins silently. For low-stakes fields. */
    object Lww : MergePolicy()

    /** Highest HLC wins provisionally; differing concurrent heads are surfaced as a [Conflict]. */
    object UserVisible : MergePolicy()

    /** A head holding a terminal value beats concurrent non-terminal heads (e.g. DONE beats a concurrent reopen). */
    data class TerminalWins(val terminal: Set<FieldValue>) : MergePolicy()

    /** Bool(true) beats concurrent Bool(false) — used for `deleted` so delete beats a concurrent edit. */
    object TrueWins : MergePolicy()
}

/** Resolves which merge policy governs a field. Unknown fields default to [MergePolicy.UserVisible]. */
fun interface SchemaRegistry {
    fun policyFor(entityType: String, field: String): MergePolicy
}

private val byHlc = compareBy<Op> { it.hlc }.thenBy { it.opId }

/**
 * Per-field merge state (ADR-003): the current heads plus every op id that some op has declared it saw.
 * Heads = ops whose id is not superseded. Both sets are pure functions of the op set, so state is independent of
 * delivery order and duplicates.
 */
data class FieldState(val heads: List<Op>, val superseded: Set<String>) {
    fun add(incoming: Op): FieldState {
        if (heads.any { it.opId == incoming.opId }) return this
        val sup = if (incoming.baseOpIds.isEmpty()) superseded else superseded + incoming.baseOpIds
        val kept = heads.filter { it.opId !in sup }
        val newHeads = if (incoming.opId in sup) kept else (kept + incoming).sortedWith(byHlc)
        return FieldState(newHeads, sup)
    }

    companion object {
        val EMPTY = FieldState(emptyList(), emptySet())
    }
}

object Merge {
    fun winner(heads: List<Op>, policy: MergePolicy): Op? {
        if (heads.isEmpty()) return null
        return when (policy) {
            MergePolicy.Lww, MergePolicy.UserVisible -> heads.maxWithOrNull(byHlc)
            is MergePolicy.TerminalWins ->
                heads.filter { it.value in policy.terminal }.maxWithOrNull(byHlc) ?: heads.maxWithOrNull(byHlc)
            MergePolicy.TrueWins ->
                heads.filter { it.value == FieldValue.Bool(true) }.maxWithOrNull(byHlc) ?: heads.maxWithOrNull(byHlc)
        }
    }

    /** A user-visible conflict exists when a [MergePolicy.UserVisible] field has concurrent heads with different values. */
    fun conflict(heads: List<Op>, policy: MergePolicy): Conflict? {
        if (policy != MergePolicy.UserVisible) return null
        if (heads.map { it.value }.distinct().size < 2) return null
        val win = winner(heads, policy) ?: return null
        val first = heads.first()
        return Conflict(
            key = first.key,
            householdId = first.householdId,
            winning = win,
            competing = heads.filter { it.opId != win.opId },
        )
    }

    /** Base for a new local edit: every head the user could see, so the edit supersedes all of them. */
    fun baseFor(heads: List<Op>): List<String> = heads.map { it.opId }.sorted()
}

data class Conflict(
    val key: FieldKey,
    val householdId: String,
    val winning: Op,
    val competing: List<Op>,
) {
    val entityRef: EntityRef get() = EntityRef(key.entityType, key.entityId)
}
