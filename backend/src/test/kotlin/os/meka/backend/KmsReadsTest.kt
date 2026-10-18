package os.meka.backend

import os.meka.backend.integrations.CachingTokenCipher
import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.KmsMeter
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.SecretCache
import os.meka.backend.integrations.SecretsManagerOAuthClients
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.sync.InMemoryServerOpStore
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * KMS free tier (Meka, 2026-10-18: 18,309 of the 20,000 free requests by the 12th): secrets are read once and kept,
 * sign-in tokens decrypt once per task, access tokens are reused until near expiry, and the deploy report counts it.
 */
class KmsReadsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private var now = 1_792_300_000_000L

    private class Vault(var secrets: MutableMap<String, String?>) {
        var fetches = 0
        fun fetch(id: String): String? { fetches++; return secrets[id] ?: throw IllegalStateException("no such secret") }
    }

    @Test
    fun aSecretIsReadOnceAndKeptSixHoursAndAnUnsetOneIsReadAgainAfterFifteenMinutes() {
        val v = Vault(mutableMapOf("ai" to """{"api_key":"sk-ant-${"a".repeat(30)}"}""", "maps" to "{}"))
        val cache = SecretCache(fetch = v::fetch, now = { now })
        val ai = cache.reader("ai") { AnthropicKey.parse(it) }
        repeat(50) { assertEquals("sk-ant-${"a".repeat(30)}", ai()) }
        assertEquals(1, v.fetches)
        now += 5 * hour
        ai()
        assertEquals(1, v.fetches)
        now += hour + 1
        ai()
        assertEquals(2, v.fetches)

        // The CDK placeholder `{}`: unset, read again after 15 minutes, so a pasted key needs no restart.
        val maps = cache.reader("maps") { os.meka.backend.integrations.GoogleMapsKey.parse(it) }
        assertNull(maps()); assertNull(maps())
        assertEquals(3, v.fetches)
        v.secrets["maps"] = """{"api_key":"${"k".repeat(39)}"}"""
        now += 14 * minute
        assertNull(maps())
        assertEquals(3, v.fetches)
        now += 2 * minute
        assertEquals("k".repeat(39), maps())
        assertEquals(4, v.fetches)

        // A secret that can't be read at all is retried after 15 minutes too, never in a loop.
        val gone = cache.reader("gone") { it }
        assertNull(gone()); assertNull(gone())
        assertEquals(5, v.fetches)
    }

    @Test
    fun aRefusedKeyIsReadAgainAtOnceButAtMostEveryFifteenMinutes() {
        val v = Vault(mutableMapOf("ai" to """{"api_key":"sk-ant-${"a".repeat(30)}"}"""))
        val cache = SecretCache(fetch = v::fetch, now = { now })
        val ai = cache.reader("ai") { AnthropicKey.parse(it) }
        ai()
        v.secrets["ai"] = """{"api_key":"sk-ant-${"b".repeat(30)}"}"""
        assertTrue(ai.rejected())
        assertEquals("sk-ant-${"b".repeat(30)}", ai())
        assertEquals(2, v.fetches)
        assertFalse(ai.rejected()) // too soon: kept
        ai()
        assertEquals(2, v.fetches)
        now += 16 * minute
        assertTrue(ai.rejected())
        ai()
        assertEquals(3, v.fetches)
    }

    @Test
    fun theHealthCheckAsksForTheKeyAgainWhenAnthropicRefusesIt() {
        val v = Vault(mutableMapOf("ai" to """{"api_key":"sk-ant-${"a".repeat(30)}"}"""))
        val cache = SecretCache(fetch = v::fetch, now = { now })
        var code = 401
        val health = AiHealth(key = cache.reader("ai") { AnthropicKey.parse(it) }, http = { _, _ -> code }, nowMs = { now })
        assertEquals(AiStatus.State.FAILING, health.status().state)
        v.secrets["ai"] = """{"api_key":"sk-ant-${"b".repeat(30)}"}"""
        code = 200
        assertEquals(AiStatus.State.ON, health.status().state) // the new key, read at once
        assertEquals(2, v.fetches)
    }

    @Test
    fun oauthClientsComeThroughTheSameCache() {
        val v = Vault(mutableMapOf("g" to """{"client_id":"cid","client_secret":"sec"}""", "m" to """{"client_id":""}"""))
        val clients = SecretsManagerOAuthClients(mapOf("google" to "g", "microsoft" to "m"), SecretCache(fetch = v::fetch, now = { now }))
        repeat(20) { assertEquals(OAuthClient("cid", "sec"), clients.get("google")) }
        assertNull(clients.get("microsoft"))
        assertNull(clients.get("yahoo"))
        assertEquals(2, v.fetches)
    }

    /** Counts what reached "KMS". */
    private class CountingCipher : TokenCipher {
        var decrypts = 0
        var encrypts = 0
        override fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray {
            encrypts++; return (context.toString() + "|").toByteArray() + plain.reversedArray()
        }
        override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray {
            decrypts++
            val prefix = (context.toString() + "|").toByteArray()
            check(cipher.copyOfRange(0, prefix.size).contentEquals(prefix)) { "wrong encryption context" }
            return cipher.copyOfRange(prefix.size, cipher.size).reversedArray()
        }
    }

    @Test
    fun aTokenDecryptsThroughKmsOnceAndItsContextStillBindsIt() {
        val kms = CountingCipher()
        val cipher = CachingTokenCipher(kms)
        val ctx = mapOf("household" to "home", "provider" to "google")
        val enc = kms.encrypt("refresh-1".toByteArray(), ctx)
        repeat(10) { assertEquals("refresh-1", cipher.decrypt(enc, ctx).decodeToString()) }
        assertEquals(1, kms.decrypts)
        // A copied ciphertext under another household's context still goes to KMS, which refuses it.
        assertFailsWith<IllegalStateException> { cipher.decrypt(enc, mapOf("household" to "other", "provider" to "google")) }
        // What it encrypts it already knows: the rotated token never needs a decrypt.
        val rotated = cipher.encrypt("refresh-2".toByteArray(), ctx)
        assertEquals("refresh-2", cipher.decrypt(rotated, ctx).decodeToString())
        assertEquals(2, kms.decrypts)
        // Callers get copies: changing one can't change the kept token.
        cipher.decrypt(enc, ctx).fill(0)
        assertEquals("refresh-1", cipher.decrypt(enc, ctx).decodeToString())
    }

    @Test
    fun theDeployReportCountsTheReadsAndWarnsWhenTheyRunHigh() {
        assertEquals(
            "KMS-backed reads since start: 9 (secrets 6 · token decrypts 3 · token encrypts 0) · up 3 h 10 min",
            KmsMeter.reportLine(6, 3, 0, 190 * minute),
        )
        assertEquals("KMS-backed reads since start: 0 (secrets 0 · token decrypts 0 · token encrypts 0) · up 4 min", KmsMeter.reportLine(0, 0, 0, 4 * minute + 59_000))
        val ok = ReleasePublisherCli.kmsLine(KmsMeter.reportLine(6, 3, 0, 190 * minute))
        assertTrue(ok.startsWith("::notice title=AWS key reads::KMS-backed reads since start: 9"), ok)
        val high = ReleasePublisherCli.kmsLine(KmsMeter.reportLine(400, 90, 10, hour))
        assertTrue(high.startsWith("::warning title=AWS key reads::"), high)
        assertEquals("::notice title=AWS key reads::the server doesn't report it yet", ReleasePublisherCli.kmsLine(null))
    }

    @Test
    fun aRotatedPublisherKeyIsReadAgainWhenASignatureFails() {
        var stored = "old"
        var reads = 0
        val source = object : PublisherKeySource {
            var kept: String? = null
            override fun publicKey(): String? { if (kept == null) { reads++; kept = stored }; return kept }
            override fun rejected(): Boolean { kept = null; return true }
        }
        assertEquals(true, source.verifiedKey { it == "old" })
        stored = "new"
        assertEquals(true, source.verifiedKey { it == "new" }) // failed with the kept key, read again, verified
        assertEquals(2, reads)
        assertEquals(false, source.verifiedKey { false })
        val none = PublisherKeySource { null }
        assertNull(none.verifiedKey { true })
    }

    /** A calendar provider that rotates its refresh token on every refresh, like Microsoft. */
    private class RotatingProvider : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "https://www.googleapis.com/auth/calendar.events"
        var refreshed = 0
        var latest = "refresh-0"
        var failEvents = false
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) =
            "https://accounts.example/auth?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) =
            TokenSet("access-0", latest, 3600, "openid email calendar.readonly")
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean): TokenSet {
            check(refreshToken == latest) { "used an old refresh token" }
            refreshed++
            latest = "refresh-$refreshed"
            return TokenSet("access-$refreshed", latest, 3600)
        }
        override fun accountEmail(accessToken: String) = "alex@example.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent> {
            if (failEvents) throw IllegalStateException("401")
            return emptyList()
        }
    }

    @Test
    fun theFiveMinuteChecksReuseTheAccessTokenUntilNearItsExpiry() {
        val provider = RotatingProvider()
        val kms = CountingCipher()
        val store = InMemoryIntegrationStore()
        val integrations = Integrations(
            store, InMemoryServerOpStore(), mapOf("google" to provider), { OAuthClient("cid", "secret") }, CachingTokenCipher(kms),
            "https://meka.example", { now }, reuseAccessTokens = true,
        )
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "google")).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        val acc = assertIs<Integrations.CallbackResult.Connected>(integrations.callback("google", state, "code", null)).accountId

        // An hour of 5-minute checks: one refresh (and its rotated token stored once), no KMS decrypt at all.
        repeat(11) { integrations.syncAccount(acc); now += 5 * minute }
        assertEquals(1, provider.refreshed)
        assertEquals(0, kms.decrypts)
        assertEquals(2, kms.encrypts) // the sign-in, then the one rotation
        // Five minutes before the token runs out, the next check refreshes with the newest refresh token.
        now += 2 * minute
        integrations.syncAccount(acc)
        assertEquals(2, provider.refreshed)
        // Any failure forgets the access token, so the next check refreshes (a revoked sign-in shows at once).
        provider.failEvents = true
        runCatching { integrations.syncAccount(acc) }
        provider.failEvents = false
        integrations.syncAccount(acc)
        assertEquals(3, provider.refreshed)
        assertEquals("ok", store.account(acc)!!.status)
        assertEquals(0, kms.decrypts)
    }
}
