package os.meka.backend

import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.OwnKeyRules
import os.meka.core.domain.RenewalState
import os.meka.core.domain.Renewals
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MEKA's own keys on the renewals radar (build plan: Outlook calendar — the client secret runs out on 2028-10-06). */
class OwnKeyRemindersTest {
    private val ops = InMemoryServerOpStore()
    private var nowMs = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private val woken = mutableListOf<String>()
    private val reminders = OwnKeyReminders(ops, { nowMs }, onWritten = { woken += it })

    private var n = 0
    /** A device after pulling everything the server holds. */
    private fun device(name: String = "fold"): Pair<InMemoryReplicaStore, Renewals> {
        val store = InMemoryReplicaStore()
        val replica = Replica("hh", name, HlcClock(name, { nowMs }), store, MekaSchema) { "$name${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return store to Renewals(replica, { "r${n++}" }, { nowMs })
    }

    @Test
    fun theKeysAreWrittenOnceAndShowOnTheRadarUntilTheirTime() {
        assertTrue(reminders.ensure("hh"))
        assertEquals(listOf("hh"), woken)
        val written = ops.after("hh", 0, 1000).map { it.op }
        assertTrue(written.all { it.entityType == EntityTypes.OBLIGATION && it.deviceId == "server" })
        assertEquals(setOf(OwnKeyRules.OUTLOOK.id, OwnKeyRules.AI.id), written.map { it.entityId }.toSet())

        val (_, renewals) = device()
        val view = renewals.view()
        assertEquals(listOf(OwnKeyRules.AI.id, OwnKeyRules.OUTLOOK.id), view.later.map { it.id })
        assertEquals("expires 6 Oct 2028", view.later.last().meta)
        assertEquals(null, view.dueLine)

        // Every later poll writes nothing.
        nowMs += 300_000
        assertFalse(reminders.ensure("hh"))
        assertEquals(written.size, ops.after("hh", 0, 1000).size)
        assertEquals(listOf("hh"), woken)

        nowMs = CivilDate.toEpochDay(2028, 9, 20) * CivilDate.DAY_MS + 12 * 3_600_000L
        assertEquals(RenewalState.SOON, device().second.items().single { it.id == OwnKeyRules.OUTLOOK.id }.state)
    }

    @Test
    fun mekasEditsAndADeleteStandAgainstLaterPolls() {
        reminders.ensure("hh")
        val (store, renewals) = device()
        nowMs += 60_000
        renewals.setDue(OwnKeyRules.OUTLOOK.id, CivilDate.toEpochDay(2028, 8, 1))
        renewals.delete(OwnKeyRules.AI.id)
        store.pendingPush(1000).forEach { ops.append(it) }

        nowMs += 300_000
        assertFalse(reminders.ensure("hh"))
        val (_, again) = device("mac")
        assertEquals(listOf(OwnKeyRules.OUTLOOK.id), again.items().map { it.id })
        assertEquals(CivilDate.toEpochDay(2028, 8, 1), again.items().single().dueDay)
    }

    @Test
    fun theFeedsPollPutsThemInEveryHousehold() {
        val cipher = object : TokenCipher {
            override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
            override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
        }
        val integrations = Integrations(
            InMemoryIntegrationStore(listOf("hh", "other")), ops, emptyMap(), { OAuthClient("cid", "secret") }, cipher,
            "https://meka.example", { nowMs }, ownKeys = reminders,
        )
        integrations.ensureFeeds()
        assertEquals(listOf("hh", "other"), woken)
        assertTrue(ops.after("hh", 0, 1000).isNotEmpty())
        assertEquals(ops.after("hh", 0, 1000).size, ops.after("other", 0, 1000).size)
    }
}
