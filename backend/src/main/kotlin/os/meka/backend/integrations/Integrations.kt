package os.meka.backend.integrations

import os.meka.backend.Secrets
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.wire.WireCodec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Connected calendar accounts (ADR-008): the OAuth handshake, encrypted refresh tokens, and mirroring events into
 * the household's op log as server-authored ops, so they reach every device through ordinary sync.
 */
class Integrations(
    private val store: IntegrationStore,
    private val ops: ServerOpStore,
    private val providers: Map<String, CalendarProvider>,
    private val clients: OAuthClientSource,
    private val cipher: TokenCipher,
    private val publicUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Public feeds every household follows by default (e.g. FC Barcelona fixtures). No sign-in involved. */
    private val feeds: Map<String, FeedProvider> = emptyMap(),
) {
    private val rng = SecureRandom()
    private val clock = HlcClock(SERVER_DEVICE, now)

    fun redirectUri(provider: String) = "${publicUrl.trimEnd('/')}/v1/oauth/$provider/callback"

    sealed class StartResult {
        data class Url(val url: String) : StartResult()
        object UnknownProvider : StartResult()
        /** The owner has not pasted this provider's OAuth credentials into Secrets Manager yet. */
        object NotConfigured : StartResult()
    }

    fun start(householdId: String, provider: String): StartResult {
        val p = providers[provider] ?: return StartResult.UnknownProvider
        val client = clients.get(provider) ?: return StartResult.NotConfigured
        val state = token(24)
        val verifier = token(48)
        store.transaction { store.saveState(state, PendingConnect(householdId, provider, verifier, now())) }
        return StartResult.Url(p.authorizeUrl(client, redirectUri(provider), state, challenge(verifier)))
    }

    sealed class CallbackResult {
        data class Connected(val provider: String, val email: String, val accountId: String) : CallbackResult()
        data class Failed(val reason: String) : CallbackResult()
    }

    /** Completes the handshake. Never trusts anything but the single-use state it issued itself. */
    fun callback(provider: String, state: String?, code: String?, error: String?): CallbackResult {
        if (state.isNullOrBlank()) return CallbackResult.Failed("The sign-in link was incomplete.")
        val pending = store.transaction { store.takeState(state) }
            ?: return CallbackResult.Failed("This sign-in link has expired or was already used. Start again from MEKA OS.")
        if (pending.provider != provider || now() - pending.createdAtMs > STATE_TTL_MS) {
            return CallbackResult.Failed("This sign-in link has expired. Start again from MEKA OS.")
        }
        if (error != null || code.isNullOrBlank()) return CallbackResult.Failed("Sign-in was cancelled.")
        val p = providers.getValue(provider)
        val client = clients.get(provider) ?: return CallbackResult.Failed("This provider is not set up on the server yet.")
        val tokens = p.exchangeCode(client, redirectUri(provider), code, pending.codeVerifier)
        val refresh = tokens.refreshToken ?: return CallbackResult.Failed("The provider did not grant offline access. Try connecting again.")
        if (tokens.scope != null && !tokens.scope.contains(p.requiredScope, ignoreCase = true)) {
            return CallbackResult.Failed("Calendar access wasn't allowed. Connect again and keep the calendar permission ticked.")
        }
        val email = p.accountEmail(tokens.accessToken).lowercase()
        val enc = cipher.encrypt(refresh.toByteArray(), context(pending.householdId, provider))
        val id = store.transaction { store.upsertAccount(pending.householdId, provider, email, enc) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
        return CallbackResult.Connected(provider, email, id)
    }

    fun accounts(householdId: String): List<WireCodec.IntegrationAccount> =
        store.accounts(householdId).map { WireCodec.IntegrationAccount(it.provider, it.email, it.status, it.lastSyncAtMs) }

    /** Syncs every connected account; one account's failure never stops the others. */
    fun syncAll() {
        runCatching { ensureFeeds() }
        for (a in store.syncableAccounts()) runCatching { syncAccount(a.id) }
    }

    /** Every household follows the default feeds (owner's stated MVP need). Idempotent. */
    fun ensureFeeds() {
        for (hh in store.households()) for (f in feeds.values) {
            if (store.accounts(hh).none { it.provider == f.id }) {
                store.transaction { store.upsertAccount(hh, f.id, f.label, ByteArray(0)) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
            }
        }
    }

    private val accountLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** One sync per account at a time in this process; [IntegrationStore.lockAccount] covers other processes. */
    fun syncAccount(accountId: String) = synchronized(accountLocks.computeIfAbsent(accountId) { Any() }) { syncLocked(accountId) }

    private fun syncLocked(accountId: String) {
        val a = store.account(accountId) ?: return
        feeds[a.provider]?.let { feed -> return syncFeed(a, feed) }
        val p = providers[a.provider] ?: return
        val client = clients.get(a.provider) ?: return
        val ctx = context(a.householdId, a.provider)
        try {
            val refresh = cipher.decrypt(a.refreshTokenEnc, ctx).decodeToString()
            val tokens = p.refresh(client, refresh)
            if (tokens.refreshToken != null && tokens.refreshToken != refresh) {
                store.transaction { store.updateRefreshToken(a.id, cipher.encrypt(tokens.refreshToken.toByteArray(), ctx)) }
            }
            val from = now() - WINDOW_BACK_MS
            val to = now() + WINDOW_AHEAD_MS
            val events = p.events(tokens.accessToken, from, to)
            apply(a, events, from, to)
            store.transaction { store.markSynced(a.id, now()) }
        } catch (e: ReconnectRequired) {
            store.transaction { store.markError(a.id, "needs_reconnect", e.message ?: "reconnect") }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    private fun syncFeed(a: AccountRow, feed: FeedProvider) {
        try {
            val from = now() - WINDOW_BACK_MS
            val to = now() + WINDOW_AHEAD_MS
            apply(a, feed.events(from, to), from, to)
            store.transaction { store.markSynced(a.id, now()) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /** Writes ops only for real changes, chaining each field on the server's previous op so nothing conflicts. */
    internal fun apply(a: AccountRow, events: List<RemoteEvent>, fromMs: Long, toMs: Long) = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val mirror = store.mirror(a.householdId, a.id)
            val seen = HashSet<String>()
            for (e in events) {
                val entityId = entityId(a, e.id)
                if (!seen.add(entityId)) continue
                val desired = linkedMapOf(
                    EventFields.TITLE to FieldValue.Text(e.title.take(MAX_TEXT)),
                    EventFields.START_AT to FieldValue.Int64(e.startMs),
                    EventFields.END_AT to FieldValue.Int64(e.endMs),
                    EventFields.ALL_DAY to FieldValue.Bool(e.allDay),
                    EventFields.LOCATION to (e.location?.take(MAX_TEXT)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    EventFields.PROVIDER to FieldValue.Text(a.provider),
                    EventFields.ACCOUNT to FieldValue.Text(a.email),
                    EventFields.CALENDAR to (e.calendarName?.take(MAX_TEXT)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    EventFields.REMOVED to FieldValue.Bool(false),
                )
                write(a, entityId, e.startMs, e.endMs, false, mirror[entityId], desired)
            }
            // Anything we mirrored inside this window that the provider no longer reports was cancelled or deleted.
            // Providers return events that overlap the window, so judge removals by overlap too. All-day events near
            // the far edge are skipped: providers apply the window in the calendar's own time zone.
            for (m in mirror.values) {
                if (m.entityId in seen || m.removed) continue
                val overlaps = m.endMs > fromMs && m.startMs < toMs
                val nearFarEdge = m.allDay && m.startMs > toMs - 14 * 3_600_000L
                if (!overlaps || nearFarEdge) continue
                write(a, m.entityId, m.startMs, m.endMs, true, m, mapOf(EventFields.REMOVED to FieldValue.Bool(true)))
            }
        }
    }

    private fun write(a: AccountRow, entityId: String, startMs: Long, endMs: Long, removed: Boolean, prev: MirrorRow?, desired: Map<String, FieldValue>) {
        val fieldOps = HashMap(prev?.fieldOps ?: emptyMap())
        var changed = prev == null || prev.removed != removed || prev.startMs != startMs || prev.endMs != endMs
        for ((field, value) in desired) {
            val key = valueKey(value)
            val last = fieldOps[field]
            if (last?.second == key) continue
            val op = Op(
                opId = "srv" + token(18).lowercase().filter(Char::isLetterOrDigit),
                householdId = a.householdId, entityType = EntityTypes.EVENT, entityId = entityId, field = field,
                value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = listOfNotNull(last?.first), deviceId = SERVER_DEVICE,
            )
            ops.append(op)
            fieldOps[field] = op.opId to key
            changed = true
        }
        val allDay = (desired[EventFields.ALL_DAY] as? FieldValue.Bool)?.value ?: prev?.allDay ?: false
        if (changed) store.putMirror(a.householdId, MirrorRow(entityId, a.id, startMs, removed, fieldOps, endMs, allDay))
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(rng::nextBytes))

    companion object {
        const val SERVER_DEVICE = "server"
        const val STATE_TTL_MS = 15 * 60_000L
        const val WINDOW_BACK_MS = 24 * 3_600_000L
        const val WINDOW_AHEAD_MS = 30 * 24 * 3_600_000L
        private const val MAX_TEXT = 500

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        /** Stable per (account, provider event): the same event always maps to the same entity. */
        fun entityId(a: AccountRow, remoteId: String): String = "ev" + Secrets.sha256Hex("${a.id}|$remoteId").take(30)

        fun context(householdId: String, provider: String) = mapOf("household" to householdId, "provider" to provider, "purpose" to "oauth-refresh")

        internal fun valueKey(v: FieldValue): String = when (v) {
            is FieldValue.Text -> "s:" + v.value
            is FieldValue.Int64 -> "i:" + v.value
            is FieldValue.Bool -> "b:" + v.value
            FieldValue.Null -> "n"
        }
    }
}
