package os.meka.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every migration file is applied (the Google key, slice 3, 2026-10-17: V26, Google's voice meter, had been written but
 * never listed, so the meter's table didn't exist and a chosen Google voice always fell back to Polly).
 */
class MigrationsListTest {
    @Test
    fun everyMigrationFileIsListedOnceInOrder() {
        val dir = File("src/main/resources/db")
        val files = dir.listFiles { f -> f.name.matches(Regex("V\\d+__.+\\.sql")) }!!.map { it.name }
        val versions = files.map { it.removePrefix("V").substringBefore("__").toInt() }.sorted()
        assertEquals((1..versions.size).toList(), versions, "migration versions have a gap or a duplicate")
        assertEquals(versions.map { v -> "/db/" + files.single { it.startsWith("V${v}__") } }, Migrations.all.map { it.second })
        assertEquals(versions, Migrations.all.map { it.first })
        Migrations.all.forEach { (_, path) -> assertNotNull(Migrations::class.java.getResource(path), path) }
    }
}
