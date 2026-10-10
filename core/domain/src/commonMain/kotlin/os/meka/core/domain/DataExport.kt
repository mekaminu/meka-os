package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/** One entity as it stands on this device: the winning value of every field, plus any unresolved choices. */
data class ExportedEntity(
    val type: String,
    val id: String,
    /** Winning value per field, by field name. */
    val fields: Map<String, FieldValue>,
    /** Fields two devices changed differently and nobody has chosen yet: every value still on offer, winner first. */
    val conflicts: Map<String, List<FieldValue>>,
    /** Wall time of the latest change to any field. */
    val updatedAtMs: Long,
)

/** Everything to put in an export file, in a fixed order (types as [DataExport.TYPES], ids sorted). */
data class ExportData(
    val householdId: String,
    val deviceId: String,
    val exportedAtMs: Long,
    val entities: List<ExportedEntity>,
) {
    /** Entities per type, only types that have some, in [DataExport.TYPES] order. */
    val counts: List<Pair<String, Int>>
        get() = DataExport.TYPES.mapNotNull { t -> entities.count { it.type == t }.takeIf { it > 0 }?.let { t to it } }
}

/** What the export holds, for the screen: "312 items" and "214 tasks · 48 calendar events · …". */
data class ExportSummary(val total: Int, val totalLine: String, val partsLine: String)

/**
 * Export everything (build plan M1, non-AI). The export is this device's replica as it stands: every entity Meka's
 * data is made of, with the winning value of each field and, where two devices disagree and nobody has chosen,
 * every value on offer, so nothing is lost. Deleted entities are left out. Headlines are left out too: they are BBC
 * content the server mirrors (ADR-009), not Meka's data. Documents join the export when the Vault lands (V2).
 */
object DataExport {
    /** Format name and version written into every file; bump the version only when a reader would misread it. */
    const val FORMAT = "meka-os-export"
    const val VERSION = 1

    /** What is exported, in file order. Never-renamed wire names (ADR-008). */
    val TYPES: List<String> = EntityTypes.ALL - EntityTypes.HEADLINE

    fun collect(replica: Replica, deviceId: String, nowMs: Long): ExportData {
        val out = mutableListOf<ExportedEntity>()
        for (type in TYPES) {
            for (snap in replica.entities(type)) {
                val conflicts = replica.conflictsFor(type, snap.ref.entityId)
                    .associate { c -> c.key.field to (listOf(c.winning) + c.competing).map { it.value }.distinct() }
                    .filterValues { it.size > 1 }
                out += ExportedEntity(
                    type = type,
                    id = snap.ref.entityId,
                    fields = snap.fields.sortedByKey(),
                    conflicts = conflicts.sortedByKey(),
                    updatedAtMs = snap.updatedHlc.wallMs,
                )
            }
        }
        return ExportData(replica.householdId, deviceId, nowMs, out.sortedWith(compareBy({ TYPES.indexOf(it.type) }, { it.id })))
    }

    /** How a type reads in the summary, singular and plural. */
    private val LABELS: Map<String, Pair<String, String>> = mapOf(
        EntityTypes.TASK to ("task" to "tasks"),
        EntityTypes.CHECKLIST_ITEM to ("step" to "steps"),
        EntityTypes.EVENT to ("calendar event" to "calendar events"),
        EntityTypes.COMMITMENT to ("waiting for" to "waiting for"),
        EntityTypes.DECISION to ("decision" to "decisions"),
        EntityTypes.OBLIGATION to ("renewal or bill" to "renewals and bills"),
        EntityTypes.GOAL to ("goal" to "goals"),
        EntityTypes.HABIT to ("habit" to "habits"),
        EntityTypes.HABIT_COMPLETION to ("habit tick" to "habit ticks"),
        EntityTypes.FAST to ("fast" to "fasts"),
        EntityTypes.SHOPPING_ITEM to ("shopping item" to "shopping items"),
        EntityTypes.SCHOOL_ITEM to ("school date" to "school dates"),
        EntityTypes.MEAL to ("favourite dinner" to "favourite dinners"),
        EntityTypes.MEAL_DAY to ("planned dinner" to "planned dinners"),
        EntityTypes.AGENT_ACTION to ("activity entry" to "activity entries"),
    )

    /** Summary order: what Meka made first, then the rest as "settings". */
    private val SUMMARY_ORDER = listOf(
        EntityTypes.TASK, EntityTypes.CHECKLIST_ITEM, EntityTypes.EVENT, EntityTypes.COMMITMENT, EntityTypes.DECISION,
        EntityTypes.OBLIGATION, EntityTypes.GOAL, EntityTypes.HABIT, EntityTypes.HABIT_COMPLETION, EntityTypes.FAST,
        EntityTypes.SHOPPING_ITEM, EntityTypes.SCHOOL_ITEM, EntityTypes.MEAL, EntityTypes.MEAL_DAY, EntityTypes.AGENT_ACTION,
    )

    fun summary(data: ExportData): ExportSummary {
        val counts = data.counts.toMap()
        val total = counts.values.sum()
        val parts = SUMMARY_ORDER.mapNotNull { t ->
            val n = counts[t] ?: return@mapNotNull null
            val (one, many) = LABELS.getValue(t)
            "$n ${if (n == 1) one else many}"
        }
        val other = counts.filterKeys { it !in SUMMARY_ORDER }.values.sum()
        val all = if (other > 0) parts + "$other ${if (other == 1) "setting" else "settings"}" else parts
        return ExportSummary(
            total = total,
            totalLine = when (total) { 0 -> "Nothing to export yet"; 1 -> "1 item"; else -> "$total items" },
            partsLine = all.joinToString(" · "),
        )
    }

    /** "meka-export-2026-10-07.json" for the local day of [nowMs]. */
    fun fileName(nowMs: Long, calendar: LocalCalendar): String {
        val d = CivilDate.fromEpochDay(calendar.epochDayOf(nowMs))
        return "meka-export-${d.year}-${d.month.pad()}-${d.day.pad()}.json"
    }

    private fun <V> Map<String, V>.sortedByKey(): Map<String, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

    private fun Int.pad() = toString().padStart(2, '0')
}
