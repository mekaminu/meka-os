package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The family page's invites and reads on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresFamilyInviteStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun anInviteIsClaimedOnceRevokedForGoodAndListedNewestFirst() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household CASCADE") }
            PostgresDeviceRegistry(ds).enrol("hh-fam", "fold", "fold")
            val store = PostgresFamilyInviteStore(ds)
            val t0 = 1_791_540_000_000L
            store.create(FamilyInvite("hh-fam", "fam" + "1".repeat(20), "jeanette", t0), "a".repeat(64))
            store.create(FamilyInvite("hh-fam", "fam" + "2".repeat(20), "gran", t0 + 1_000), "b".repeat(64))
            val first = store.byToken("a".repeat(64))!!
            assertEquals(FamilyInvite.WAITING, first.state)
            assertTrue(store.claim(first.id, "KEY", t0 + 5_000))
            assertFalse(store.claim(first.id, "OTHER", t0 + 6_000))
            assertEquals("KEY", store.byId(first.id)!!.publicKey)
            assertEquals(t0 + 5_000, store.byId(first.id)!!.claimedAtMs)
            store.seen(first.id, t0 + 9_000)
            assertEquals(t0 + 9_000, store.byId(first.id)!!.lastSeenAtMs)
            assertEquals(listOf("gran", "jeanette"), store.list("hh-fam").map { it.name })
            assertFalse(store.revoke("someone-else", first.id, t0))
            assertTrue(store.revoke("hh-fam", first.id, t0 + 10_000))
            assertTrue(store.revoke("hh-fam", first.id, t0 + 20_000))
            assertEquals(t0 + 10_000, store.byId(first.id)!!.revokedAtMs)
            assertEquals(FamilyInvite.REVOKED, store.byId(first.id)!!.state)
            // A revoked invite that was never opened can't be opened.
            assertTrue(store.revoke("hh-fam", "fam" + "2".repeat(20), t0 + 11_000))
            assertFalse(store.claim("fam" + "2".repeat(20), "K", t0 + 12_000))
            assertNull(store.byToken("c".repeat(64)))

            // The page reads each item's latest op per field, with its id for the next write's base.
            val ops = PostgresOpStore(ds)
            fun op(id: String, field: String, v: FieldValue, wall: Long) =
                Op(id, "hh-fam", EntityTypes.SHOPPING_ITEM, "milk", field, v, Hlc(wall, 0, "fold"), emptyList(), "fold")
            ops.transaction {
                ops.append(op("o1", "title", FieldValue.Text("Milk"), 1))
                ops.append(op("o2", "got", FieldValue.Bool(false), 1))
                ops.append(op("o3", "got", FieldValue.Bool(true), 2))
            }
            val latest = ops.latestFieldOps("hh-fam", EntityTypes.SHOPPING_ITEM)
            assertEquals("o3", latest["milk"]!!["got"]!!.opId)
            assertEquals(FieldValue.Bool(true), ops.latestFields("hh-fam", EntityTypes.SHOPPING_ITEM)["milk"]!!["got"])
        }
    }
}
