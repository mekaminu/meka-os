package os.meka.backend

import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.ReconnectRequired
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.SignIn
import os.meka.core.domain.SignInRules
import os.meka.core.domain.SignInState
import os.meka.core.domain.SignInStore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reliability first, item 2: the server says when a calendar sign-in is about to end or has expired. */
class SignInsTest {
    private val day = 86_400_000L
    private var now = 1_790_985_600_000L // 2026-10-03T00:00Z

    private class Provider : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "https://www.googleapis.com/auth/calendar.events"
        var refused = false
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) =
            "https://accounts.example/auth?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) =
            TokenSet("access-1", "refresh-1", 3600, null)
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean): TokenSet {
            if (refused) throw ReconnectRequired("provider says invalid_grant")
            return TokenSet("access-2", null, 3600)
        }
        override fun accountEmail(accessToken: String) = "meka@gmail.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long) = emptyList<RemoteEvent>()
    }

    private class Cipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private val provider = Provider()
    private val store = InMemoryIntegrationStore(listOf("home"))
    private val ops = InMemoryServerOpStore()
    private val woken = mutableListOf<String>()
    private val integrations = Integrations(
        store, ops, mapOf("google" to provider), { OAuthClient("cid", "secret") }, Cipher(), "https://meka.example", { now },
        onChanged = { woken += it },
    )
    private var counter = 0

    private fun connect(): String {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        return assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", state, "code", null)).accountId
    }

    private fun device(): List<SignIn> {
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + (counter++) }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        return SignInStore(r).all()
    }

    private fun opCount() = ops.after("home", 0, 10_000).size

    @Test
    fun aSignInIsWarnedOnItsLastDayExpiredWhenRefusedAndFineAgainAfterReconnecting() {
        val signedIn = now
        val acc = connect()
        assertEquals(signedIn, store.account(acc)!!.grantedAtMs)
        integrations.syncAll()
        assertEquals(listOf(SignIn("google", "meka@gmail.com", SignInState.OK, null)), device())
        assertTrue(woken.isEmpty()) // fine is nothing to wake anyone for
        // Polls while nothing changes write nothing more.
        val ops0 = opCount()
        now += day; integrations.syncAll()
        assertEquals(ops0, opCount())

        // Day 6: the last day.
        now = signedIn + 6 * day + 60_000
        integrations.syncAll()
        assertEquals(SignIn("google", "meka@gmail.com", SignInState.ENDING, signedIn + 7 * day), device().single())
        assertEquals(listOf("home"), woken)
        val ops1 = opCount()
        now += 300_000; integrations.syncAll()
        assertEquals(ops1, opCount())

        // Google refuses it: expired, from when the server first saw it, and it stays at that time.
        provider.refused = true
        now = signedIn + 7 * day + 60_000
        integrations.syncAll()
        val seen = now
        assertEquals(SignIn("google", "meka@gmail.com", SignInState.EXPIRED, seen), device().single())
        assertEquals(listOf("home", "home"), woken)
        now += day; integrations.syncAll()
        assertEquals(seen, device().single().untilMs)

        // Reconnecting: fine again, and its 7 days start over.
        provider.refused = false
        connect()
        integrations.syncAll()
        assertEquals(SignIn("google", "meka@gmail.com", SignInState.OK, null), device().single())
        assertEquals(now, store.account(acc)!!.grantedAtMs)
        assertEquals(3, woken.size)
    }

    @Test
    fun anAccountSignedInBeforeTheTimeWasRecordedIsNeverWarnedButIsSaidWhenItExpires() {
        // As after the migration: no granted_at.
        store.upsertAccount("home", "google", "old@gmail.com", "refresh-1".toByteArray()) { "accold" }
        assertEquals(null, store.account("accold")!!.grantedAtMs)
        now += 6 * day + 3_600_000
        integrations.syncAll()
        assertEquals(SignInState.OK, device().single().state)
        provider.refused = true
        integrations.syncAll()
        val s = device().single()
        assertEquals(SignInState.EXPIRED, s.state)
        assertNotNull(s.untilMs)
        // Feeds and other households are never written as sign-ins.
        assertTrue(device().all { it.provider == "google" })
        assertEquals("sign_in.accold", SignInRules.entityId("accold"))
    }
}
