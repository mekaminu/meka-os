package os.meka.core.facade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import os.meka.core.domain.Lifecycle
import os.meka.core.domain.WaitingFollow

/**
 * Slice 2h: the calendar follow-ups waiting on this device ([WaitingFollow]) as a device-local value
 * (`ReplicaStore.localValue`), so closing the app before the calendar has the block doesn't drop them. Never synced.
 * Anything unreadable (an older or damaged value) decodes to nothing: the wait is a convenience, and the task's next
 * change follows as usual.
 */
internal object WaitingFollowCodec {
    const val KEY = "plan.waitingFollows"

    fun encode(follows: Collection<WaitingFollow>): String? =
        if (follows.isEmpty()) null
        else buildJsonArray {
            follows.sortedBy { it.taskId }.forEach { w ->
                add(buildJsonObject {
                    put("task", w.taskId)
                    put("present", w.present)
                    put("at", w.scheduledAtMs)
                    put("title", w.title)
                    put("lifecycle", w.lifecycle?.name)
                    put("since", w.sinceMs)
                })
            }
        }.toString()

    fun decode(text: String?): List<WaitingFollow> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element -> runCatching { one(element.jsonObject) }.getOrNull() }
            .associateBy { it.taskId }.values.toList()
    }

    private fun one(o: JsonObject): WaitingFollow? {
        val taskId = o.str("task")?.takeIf { it.isNotEmpty() } ?: return null
        val present = (o["present"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val since = (o["since"] as? JsonPrimitive)?.longOrNull ?: return null
        val at = o["at"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.longOrNull ?: return null }
        val lifecycle = o.str("lifecycle")?.let { name -> Lifecycle.entries.firstOrNull { it.name == name } ?: return null }
        return WaitingFollow(taskId, present, at, o.str("title").orEmpty(), lifecycle, since)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
}
