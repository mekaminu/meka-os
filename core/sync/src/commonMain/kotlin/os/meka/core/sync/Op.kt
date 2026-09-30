package os.meka.core.sync

/** A field value. Deliberately small: structured data is modelled as entities, not blobs. */
sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class Int64(val value: Long) : FieldValue()
    data class Bool(val value: Boolean) : FieldValue()
    object Null : FieldValue() {
        override fun toString() = "Null"
    }

    val textOrNull: String? get() = (this as? Text)?.value
    val longOrNull: Long? get() = (this as? Int64)?.value
    val boolOrNull: Boolean? get() = (this as? Bool)?.value
}

fun String?.fv(): FieldValue = if (this == null) FieldValue.Null else FieldValue.Text(this)
fun Long?.fv(): FieldValue = if (this == null) FieldValue.Null else FieldValue.Int64(this)
fun Int?.fv(): FieldValue = if (this == null) FieldValue.Null else FieldValue.Int64(this.toLong())
fun Boolean.fv(): FieldValue = FieldValue.Bool(this)

/**
 * An immutable change to one field of one entity (ADR-003).
 *
 * @property opId globally unique id created by the authoring device; the idempotency key.
 * @property baseOpIds the heads of this field the author saw when editing (exact causality; empty for a new field).
 *   Using op ids rather than a timestamp means an unrelated, later-timestamped edit can never masquerade as
 *   "I saw your change", so concurrent edits are always detected.
 */
data class Op(
    val opId: String,
    val householdId: String,
    val entityType: String,
    val entityId: String,
    val field: String,
    val value: FieldValue,
    val hlc: Hlc,
    val baseOpIds: List<String>,
    val deviceId: String,
    val schemaVersion: Int = 1,
) {
    fun sameField(other: Op): Boolean =
        entityType == other.entityType && entityId == other.entityId && field == other.field

    val key: FieldKey get() = FieldKey(entityType, entityId, this.field)

    /** Structural validation applied to every op, local or remote. Returns a reason or null. */
    fun validationError(): String? = when {
        opId.isBlank() -> "blank opId"
        householdId.isBlank() -> "blank householdId"
        entityType.isBlank() || entityId.isBlank() -> "blank entity reference"
        field.isBlank() -> "blank field"
        opId in baseOpIds -> "op cannot supersede itself"
        baseOpIds.size > MAX_BASE -> "too many base ops"
        hlc.node.isBlank() -> "hlc without node"
        (value as? FieldValue.Text)?.value?.let { it.length > MAX_TEXT } == true -> "text value exceeds $MAX_TEXT chars"
        else -> null
    }

    companion object {
        const val MAX_TEXT = 20_000
        const val MAX_BASE = 64
    }
}

data class FieldKey(val entityType: String, val entityId: String, val field: String)

data class EntityRef(val entityType: String, val entityId: String)
