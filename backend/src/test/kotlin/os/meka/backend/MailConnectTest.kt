package os.meka.backend

import os.meka.backend.integrations.CalendarProvider
import os.meka.backend.integrations.GmailMail
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MailProvider
import os.meka.backend.integrations.MailboxCheck
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.OutlookMail
import os.meka.backend.integrations.ReconnectRequired
import os.meka.backend.integrations.RemoteEvent
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.sync.InMemoryServerOpStore
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Inbox autopilot, slice 1: connecting mail accounts on the server. */
class MailConnectTest {
    private var now = 1_790_985_600_000L

    private class FakeMail(override val id: String, override val oauth: String, override val requiredScopes: List<String>) : MailProvider {
        var granted: String? = null
        var email = "Alex@Example.com"
        var revoked = false
        var checks = 0
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) =
            "https://accounts.example/auth?state=$state&redirect=$redirectUri&client=${client.clientId}"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
            TokenSet("access-1", "refresh-$id", 3600, granted)
        override fun refresh(client: OAuthClient, refreshToken: String): TokenSet {
            check(refreshToken == "refresh-$id") { "decrypted the wrong token" }
            if (revoked) throw ReconnectRequired("invalid_grant")
            return TokenSet("access-2", null, 3600)
        }
        override fun accountEmail(accessToken: String) = email
        override fun inbox(accessToken: String): MailboxCheck { checks++; return MailboxCheck(120, 3) }
        val revokedTokens = mutableListOf<String>()
        override fun revoke(refreshToken: String): Boolean { revokedTokens += refreshToken; return true }
    }

    /** The calendar provider on the same Google client: it must never see a mail sign-in. */
    private class Calendar : CalendarProvider {
        override val id = "google"
        override val requiredScope = "calendar.readonly"
        override val writeScope = "calendar.events"
        var exchanged = 0
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) = "https://cal.example/?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean): TokenSet { exchanged++; return TokenSet("a", "r", 1, "calendar.readonly") }
        override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean) = TokenSet("a", null, 1)
        override fun accountEmail(accessToken: String) = "alex@example.com"
        override fun events(accessToken: String, fromMs: Long, toMs: Long) = emptyList<RemoteEvent>()
    }

    private class Cipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = (context.toString() + "|").toByteArray() + plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray {
            val prefix = (context.toString() + "|").toByteArray()
            check(cipher.copyOfRange(0, prefix.size).contentEquals(prefix)) { "wrong encryption context" }
            return cipher.copyOfRange(prefix.size, cipher.size).reversedArray()
        }
    }

    private val gmail = FakeMail("gmail", "google", listOf(GmailMail.MODIFY, GmailMail.SEND))
    private val outlook = FakeMail("outlook_mail", "microsoft", listOf("Mail.ReadWrite", "Mail.Send"))
    private val calendar = Calendar()
    private val store = InMemoryIntegrationStore()
    private val integrations = Integrations(
        store, InMemoryServerOpStore(), mapOf("google" to calendar), { p -> if (p == "google" || p == "microsoft") OAuthClient("cid-$p", "s") else null },
        Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail, "outlook_mail" to outlook),
    )

    private fun stateOf(url: String) = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
    private val gmailScopes = "openid email ${GmailMail.MODIFY} ${GmailMail.SEND}"

    private fun connectGmail(): Integrations.CallbackResult {
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "gmail")).url
        // Google's own client and the calendar's callback address: nothing new to register with Google.
        assertTrue(url.contains("client=cid-google"))
        assertTrue(url.contains("redirect=https://meka.example/v1/oauth/google/callback"))
        return integrations.callback("google", stateOf(url), "code", null)
    }

    @Test
    fun gmailConnectsThroughGooglesCallbackAndIsLabelledPersonalThenBusiness() {
        gmail.granted = gmailScopes
        val first = assertIs<Integrations.CallbackResult.Connected>(connectGmail())
        assertEquals("gmail", first.provider)
        assertEquals("alex@example.com", first.email)
        assertEquals(0, calendar.exchanged)
        gmail.email = "shop@example.com"
        assertIs<Integrations.CallbackResult.Connected>(connectGmail())
        val list = integrations.accounts("home")
        assertEquals(listOf("PERSONAL", "BUSINESS"), list.map { it.label })
        assertEquals(listOf(false, false), list.map { it.canEdit })
        assertTrue(connectedText(first).startsWith("Gmail (alex@example.com) is connected."))
        assertTrue(connectedText(first).contains("sends only when you tap Send"))
        // Connecting the same account again keeps its label.
        integrations.setMailLabel("home", "gmail", "Alex@Example.com", "BUSINESS")
        gmail.email = "alex@example.com"
        connectGmail()
        assertEquals("BUSINESS", integrations.accounts("home").first { it.email == "alex@example.com" }.label)
    }

    @Test
    fun aSignInWithoutEveryMailPermissionIsntConnected() {
        gmail.granted = "openid email ${GmailMail.MODIFY}" // send unticked
        val r = assertIs<Integrations.CallbackResult.Failed>(connectGmail())
        assertTrue(r.reason.contains("keep every permission ticked"))
        // A look-alike scope isn't the one.
        gmail.granted = "openid email https://www.googleapis.com/auth/gmail.readonly ${GmailMail.SEND}"
        assertIs<Integrations.CallbackResult.Failed>(connectGmail())
        assertTrue(integrations.accounts("home").isEmpty())
    }

    @Test
    fun aMailStateCantCompleteThroughTheOtherProvidersCallback() {
        outlook.granted = "Mail.ReadWrite Mail.Send"
        val url = assertIs<Integrations.StartResult.Url>(integrations.start("home", "outlook_mail")).url
        assertTrue(url.contains("redirect=https://meka.example/v1/oauth/microsoft/callback"))
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("google", stateOf(url), "code", null))
        // The state is single use: spent by the wrong callback, it can't be used again.
        assertIs<Integrations.CallbackResult.Failed>(integrations.callback("microsoft", stateOf(url), "code", null))
        val again = assertIs<Integrations.StartResult.Url>(integrations.start("home", "outlook_mail")).url
        val ok = assertIs<Integrations.CallbackResult.Connected>(integrations.callback("microsoft", stateOf(again), "code", null))
        assertEquals("OUTLOOK", integrations.accounts("home").single().label)
        assertEquals("outlook_mail", ok.provider)
    }

    @Test
    fun theCheckRefreshesReadsTheInboxCountsAndAnEndedSignInSaysReconnect() {
        gmail.granted = gmailScopes
        val id = assertIs<Integrations.CallbackResult.Connected>(connectGmail()).accountId
        now += 60_000
        integrations.syncAccount(id)
        assertEquals(1, gmail.checks)
        assertEquals(now, store.account(id)!!.lastSyncAtMs)
        gmail.revoked = true
        integrations.syncAccount(id)
        assertEquals("needs_reconnect", integrations.accounts("home").single().status)
    }

    @Test
    fun labelsAreOnlyMailLabelsOnMailAccounts() {
        gmail.granted = gmailScopes
        connectGmail()
        assertFalse(integrations.setMailLabel("home", "gmail", "alex@example.com", "WORK"))
        assertFalse(integrations.setMailLabel("home", "google", "alex@example.com", "BUSINESS"))
        assertFalse(integrations.setMailLabel("home", "gmail", "nobody@example.com", "BUSINESS"))
        assertFalse(integrations.setMailLabel("other", "gmail", "alex@example.com", "BUSINESS"))
        assertTrue(integrations.setMailLabel("home", "gmail", "ALEX@example.com", "BUSINESS"))
        assertEquals("BUSINESS", integrations.accounts("home").single().label)
    }

    @Test
    fun theRealProvidersAskForExactlyReadOrganiseAndSend() {
        val client = OAuthClient("cid", "secret")
        val g = GmailMail().authorizeUrl(client, "https://meka.example/v1/oauth/google/callback", "st", "ch")
        assertTrue(g.contains("gmail.modify") && g.contains("gmail.send") && g.contains("access_type=offline"))
        assertFalse(g.contains("mail.google.com")) // never full access (that one can delete for good)
        val o = OutlookMail().authorizeUrl(client, "https://meka.example/v1/oauth/microsoft/callback", "st", "ch")
        assertTrue(o.contains("Mail.ReadWrite") && o.contains("Mail.Send") && o.contains("offline_access"))
        assertTrue(o.contains("/consumers/"))
    }

    // ---- Slice 1b ----

    @Test
    fun disconnectForgetsTheSignInStopsTheCheckAndAComebackKeepsTheLabel() {
        gmail.granted = gmailScopes
        val id = assertIs<Integrations.CallbackResult.Connected>(connectGmail()).accountId
        integrations.setMailLabel("home", "gmail", "alex@example.com", "BUSINESS")
        assertFalse(integrations.disconnectMail("home", "google", "alex@example.com")) // not a mail provider
        assertFalse(integrations.disconnectMail("home", "gmail", "nobody@example.com"))
        assertFalse(integrations.disconnectMail("other", "gmail", "alex@example.com"))
        assertTrue(integrations.disconnectMail("home", "gmail", "Alex@Example.com"))
        assertEquals(listOf("refresh-gmail"), gmail.revokedTokens) // Google asked to withdraw it
        assertTrue(integrations.accounts("home").isEmpty())
        assertEquals(0, store.account(id)!!.refreshTokenEnc.size)
        assertTrue(store.syncableAccounts().none { it.id == id })
        assertFalse(integrations.disconnectMail("home", "gmail", "alex@example.com")) // already gone
        // A check already running can't bring it back.
        store.markSynced(id, now)
        store.markError(id, "error", "x")
        assertEquals("disconnected", store.account(id)!!.status)
        // Signing in again brings it back, label and all.
        connectGmail()
        val back = integrations.accounts("home").single()
        assertEquals("ok", back.status)
        assertEquals("BUSINESS", back.label)
    }

    @Test
    fun aGmailSignInRunsOutLikeGooglesAndDisconnectingClearsTheLine() {
        val hhStore = InMemoryIntegrationStore(listOf("home"))
        val withHome = Integrations(
            hhStore, InMemoryServerOpStore(), mapOf("google" to calendar), { p -> if (p == "google" || p == "microsoft") OAuthClient("cid-$p", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail, "outlook_mail" to outlook),
        )
        gmail.granted = gmailScopes
        val url = assertIs<Integrations.StartResult.Url>(withHome.start("home", "gmail")).url
        val id = assertIs<Integrations.CallbackResult.Connected>(withHome.callback("google", stateOf(url), "code", null)).accountId
        fun state() = hhStore.mirror("home", id)[os.meka.core.domain.SignInRules.entityId(id)]
            ?.fieldOps?.get(os.meka.core.domain.SignInFields.STATE)?.second
        withHome.noteSignIns()
        assertTrue(state() == null || state() == "s:OK")
        now += 6 * os.meka.core.domain.SignInRules.DAY_MS + 3_600_000L
        withHome.noteSignIns()
        assertEquals("s:ENDING", state())
        assertTrue(withHome.disconnectMail("home", "gmail", "alex@example.com"))
        assertEquals("s:OK", state())
    }

    @Test
    fun signingInAgainClearsTheExpiredLineAtOnce() {
        val hhStore = InMemoryIntegrationStore(listOf("home"))
        var nudged = 0
        val withHome = Integrations(
            hhStore, InMemoryServerOpStore(), mapOf("google" to calendar), { p -> if (p == "google" || p == "microsoft") OAuthClient("cid-$p", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail, "outlook_mail" to outlook),
            onChanged = { nudged++ },
        )
        gmail.granted = gmailScopes
        val url = assertIs<Integrations.StartResult.Url>(withHome.start("home", "gmail")).url
        val id = assertIs<Integrations.CallbackResult.Connected>(withHome.callback("google", stateOf(url), "code", null)).accountId
        fun state() = hhStore.mirror("home", id)[os.meka.core.domain.SignInRules.entityId(id)]
            ?.fieldOps?.get(os.meka.core.domain.SignInFields.STATE)?.second
        // Google refuses the sign-in: the next check marks it expired.
        hhStore.markError(id, "needs_reconnect", "invalid_grant")
        withHome.noteSignIns()
        assertEquals("s:EXPIRED", state())
        val before = nudged
        // Reconnect: the callback, then at once (not at the next check) the line turns fine and the devices hear of it.
        val again = assertIs<Integrations.StartResult.Url>(withHome.start("home", "gmail")).url
        assertEquals(id, assertIs<Integrations.CallbackResult.Connected>(withHome.callback("google", stateOf(again), "code", null)).accountId)
        withHome.noteConnected(id)
        assertEquals("s:OK", state())
        assertEquals(before + 1, nudged)
        // Nothing changed the second time: no nudge.
        withHome.noteConnected(id)
        assertEquals(before + 1, nudged)
    }
}
