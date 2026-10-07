package os.meka.core.facade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DataExport
import os.meka.core.domain.ExportData
import os.meka.core.domain.ExportSummary
import os.meka.core.sync.FieldValue

/** A finished export: the file name to suggest, its JSON and what it holds. */
class DataExportFile(val fileName: String, val json: String, val summary: ExportSummary)

/**
 * The export file (build plan M1). Plain, readable JSON a person or another program can use without MEKA:
 * values keep their type (text, whole numbers, true/false, null), and every time stored as milliseconds
 * (`…AtMs`) gets a readable UTC copy beside it (`…At`). Stable order (types, ids, field names), so two exports of
 * the same data are byte-for-byte equal.
 */
object DataExportCodec {
    private val json = Json { prettyPrint = true }

    fun encode(data: ExportData): String = json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("format", DataExport.FORMAT)
        put("version", DataExport.VERSION)
        put("exportedAt", isoUtc(data.exportedAtMs))
        put("exportedAtMs", data.exportedAtMs)
        put("householdId", data.householdId)
        put("deviceId", data.deviceId)
        put(
            "about",
            "Everything synced to this device when it was exported. Times ending in Ms are milliseconds since " +
                "1970-01-01 UTC, with a readable copy beside them. Days (day, occurrenceDay, deferredToDay) count days " +
                "since 1970-01-01. \"conflicts\" lists values two devices disagree on that haven't been chosen yet.",
        )
        putJsonObject("counts") { data.counts.forEach { (t, n) -> put(t, n) } }
        putJsonObject("entities") {
            for ((type, _) in data.counts) {
                putJsonArray(type) {
                    data.entities.filter { it.type == type }.forEach { e ->
                        add(buildJsonObject {
                            put("id", e.id)
                            put("updatedAt", isoUtc(e.updatedAtMs))
                            putJsonObject("fields") { putFields(e.fields) }
                            if (e.conflicts.isNotEmpty()) {
                                putJsonObject("conflicts") {
                                    e.conflicts.forEach { (f, vs) -> put(f, buildJsonArray { vs.forEach { add(value(it)) } }) }
                                }
                            }
                        })
                    }
                }
            }
        }
        // Vault documents join here in V2.
        put("documents", JsonArray(emptyList()))
    })

    private fun JsonObjectBuilder.putFields(fields: Map<String, FieldValue>) {
        for ((name, v) in fields) {
            put(name, value(v))
            val readable = name.removeSuffix("Ms")
            if (readable != name && readable.endsWith("At") && v is FieldValue.Int64 && readable !in fields) {
                put(readable, isoUtc(v.value))
            }
        }
    }

    private fun value(v: FieldValue): JsonElement = when (v) {
        is FieldValue.Text -> JsonPrimitive(v.value)
        is FieldValue.Int64 -> JsonPrimitive(v.value)
        is FieldValue.Bool -> JsonPrimitive(v.value)
        FieldValue.Null -> JsonNull
    }

    /** "2026-10-07T02:23:42Z" (second precision; milliseconds are in the `…Ms` value beside it). */
    fun isoUtc(ms: Long): String {
        val day = ms.floorDiv(CivilDate.DAY_MS)
        val sec = ms.mod(CivilDate.DAY_MS) / 1000
        val d = CivilDate.fromEpochDay(day)
        fun p(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(d.year.toLong(), 4)}-${p(d.month.toLong())}-${p(d.day.toLong())}T" +
            "${p(sec / 3600)}:${p(sec / 60 % 60)}:${p(sec % 60)}Z"
    }
}
