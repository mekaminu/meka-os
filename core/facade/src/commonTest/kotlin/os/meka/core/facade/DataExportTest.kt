package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataExportTest {
    // 2026-10-07 00:30 in London is 2026-10-06 23:30 UTC: the file is named for the local day.
    private val now = 1_791_329_400_000L

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun isoIsUtcToTheSecond() {
        assertEquals("1970-01-01T00:00:00Z", DataExportCodec.isoUtc(0))
        assertEquals("2026-10-06T23:30:00Z", DataExportCodec.isoUtc(now))
        assertEquals("2026-10-06T23:30:00Z", DataExportCodec.isoUtc(now + 999))
    }

    @Test
    fun exportIsReadableJsonWithEverythingOnTheDevice() = runTest {
        val c = core()
        val id = c.addTask("Book dentist")
        c.addStep(id, "Find the number")
        val gone = c.addTask("Mistake")
        c.delete(gone)

        val file = c.exportAll()
        assertEquals("meka-export-2026-10-07.json", file.fileName)
        assertTrue(file.summary.partsLine.startsWith("1 task · 1 step"), file.summary.partsLine)
        assertEquals(file.summary, c.exportSummary())

        val root = Json.parseToJsonElement(file.json).jsonObject
        assertEquals("meka-os-export", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        assertEquals("2026-10-06T23:30:00Z", root.getValue("exportedAt").jsonPrimitive.content)
        assertEquals(1, root.getValue("counts").jsonObject.getValue("task").jsonPrimitive.int)
        assertTrue(root.getValue("documents").jsonArray.isEmpty())

        val tasks = root.getValue("entities").jsonObject.getValue("task").jsonArray
        assertEquals(1, tasks.size, "deleted tasks are left out")
        val t = tasks.single().jsonObject
        assertEquals(id, t.getValue("id").jsonPrimitive.content)
        assertFalse("conflicts" in t)
        val fields = t.getValue("fields").jsonObject
        assertEquals("Book dentist", fields.getValue("title").jsonPrimitive.content)
        // Milliseconds keep their type, with a readable copy beside them.
        assertEquals(now, fields.getValue("createdAtMs").jsonPrimitive.long)
        assertEquals("2026-10-06T23:30:00Z", fields.getValue("createdAt").jsonPrimitive.content)
        assertEquals(c.exportAll().json, file.json, "the same data exports byte for byte the same")
    }

    @Test
    fun nullsStayNull() = runTest {
        val c = core()
        val id = c.addTask("Someday maybe")
        c.schedule(id, now + 86_400_000L)
        c.schedule(id, null)
        val fields = Json.parseToJsonElement(c.exportAll().json).jsonObject.getValue("entities").jsonObject
            .getValue("task").jsonArray.single().jsonObject.getValue("fields").jsonObject
        assertEquals(JsonNull, fields.getValue("scheduledAtMs"))
        assertFalse("scheduledAt" in fields, "no readable copy of a time that isn't set")
    }
}
