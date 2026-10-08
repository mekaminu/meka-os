package os.meka.backend.integrations

import os.meka.backend.Secrets
import os.meka.core.domain.BankHoliday
import os.meka.core.domain.BankHolidayFields
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.BankHolidays
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.HeadlineFields
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
    /** Public news sources mirrored for the morning brief (no sign-in; the apps choose which topics to show). */
    private val news: Map<String, NewsProvider> = emptyMap(),
    /** Public lists of days off mirrored for work mode (UK bank holidays; no sign-in). */
    private val holidays: Map<String, HolidayProvider> = emptyMap(),
    /** News pictures (images slice): fetched, shrunk and kept by the server; null leaves headlines without pictures. */
    private val images: NewsImages? = null,
    /**
     * Called after a calendar or fixtures poll wrote ops for a household (not for headlines), so push can wake its
     * devices: a moved event's reminders re-arm and a moved kick-off is announced within seconds of the poll.
     */
    private val onChanged: (householdId: String) -> Unit = {},
) {
    private val rng = SecureRandom()
    private val clock = HlcClock(SERVER_DEVICE, now)

    /** The calendar provider with this id (the writer's way to the same adapters). */
    fun provider(id: String): CalendarProvider? = providers[id]

    /** The calendar writer (calendar editing, slice 2a) over the same store, op log and adapters. */
    fun writer(reader: EntityReader, onWritten: (householdId: String) -> Unit = {}): CalendarWriter =
        CalendarWriter(store, ops, reader, this, now, onWritten)

    fun redirectUri(provider: String) = "${publicUrl.trimEnd('/')}/v1/oauth/$provider/callback"

    sealed class StartResult {
        data class Url(val url: String) : StartResult()
        object UnknownProvider : StartResult()
        /** The owner has not pasted this provider's OAuth credentials into Secrets Manager yet. */
        object NotConfigured : StartResult()
    }

    /** [editing]: Allow editing (calendar editing): the provider is asked for its write scope as well. */
    fun start(householdId: String, provider: String, editing: Boolean = false): StartResult {
        val p = providers[provider] ?: return StartResult.UnknownProvider
        val client = clients.get(provider) ?: return StartResult.NotConfigured
        val state = token(24)
        val verifier = token(48)
        store.transaction { store.saveState(state, PendingConnect(householdId, provider, verifier, now(), editing)) }
        return StartResult.Url(p.authorizeUrl(client, redirectUri(provider), state, challenge(verifier), editing))
    }

    sealed class CallbackResult {
        data class Connected(
            val provider: String, val email: String, val accountId: String,
            /** Editing was asked for ([editingAsked]) and granted ([canEdit]); asked but not granted stays read-only. */
            val canEdit: Boolean = false, val editingAsked: Boolean = false,
        ) : CallbackResult()
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
        val tokens = p.exchangeCode(client, redirectUri(provider), code, pending.codeVerifier, pending.editing)
        val refresh = tokens.refreshToken ?: return CallbackResult.Failed("The provider did not grant offline access. Try connecting again.")
        if (tokens.scope != null && !tokens.scope.contains(p.requiredScope, ignoreCase = true)) {
            return CallbackResult.Failed("Calendar access wasn't allowed. Connect again and keep the calendar permission ticked.")
        }
        // Editing only when it was asked for and the owner left the write permission ticked; never assumed.
        val canEdit = pending.editing && grants(tokens.scope, p.writeScope)
        val email = p.accountEmail(tokens.accessToken).lowercase()
        val enc = cipher.encrypt(refresh.toByteArray(), context(pending.householdId, provider))
        val id = store.transaction { store.upsertAccount(pending.householdId, provider, email, enc, canEdit) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
        return CallbackResult.Connected(provider, email, id, canEdit, pending.editing)
    }

    fun accounts(householdId: String): List<WireCodec.IntegrationAccount> =
        store.accounts(householdId).map { WireCodec.IntegrationAccount(it.provider, it.email, it.status, it.lastSyncAtMs, it.canEdit) }

    /**
     * Stop editing on one account (calendar editing): from now on the server only reads it. Giving a permission up
     * needs no consent; the provider's grant stays until the owner removes it there or reconnects read-only.
     * Returns false for an unknown account or a provider that can't edit.
     */
    fun stopEditing(householdId: String, provider: String, email: String): Boolean =
        provider in providers && store.transaction { store.stopEditing(householdId, provider, email.lowercase()) }

    /** Syncs every connected account; one account's failure never stops the others. */
    fun syncAll() {
        runCatching { ensureFeeds() }
        for (a in store.syncableAccounts()) runCatching { syncAccount(a.id) }
    }

    /** Every household follows the default feeds (owner's stated MVP need). Idempotent. */
    fun ensureFeeds() {
        val all = feeds.values.map { it.id to it.label } + news.values.map { it.id to it.source } + holidays.values.map { it.id to it.label }
        for (hh in store.households()) for ((id, label) in all) {
            if (store.accounts(hh).none { it.provider == id }) {
                store.transaction { store.upsertAccount(hh, id, label, ByteArray(0)) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
            }
        }
    }

    private val accountLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** One sync per account at a time in this process; [IntegrationStore.lockAccount] covers other processes. */
    fun syncAccount(accountId: String) = synchronized(accountLocks.computeIfAbsent(accountId) { Any() }) { syncLocked(accountId) }

    private fun syncLocked(accountId: String) {
        val a = store.account(accountId) ?: return
        feeds[a.provider]?.let { feed -> return syncFeed(a, feed) }
        news[a.provider]?.let { source -> return syncNews(a, source) }
        holidays[a.provider]?.let { list -> return syncHolidays(a, list) }
        val p = providers[a.provider] ?: return
        val client = clients.get(a.provider) ?: return
        val ctx = context(a.householdId, a.provider)
        try {
            val refresh = cipher.decrypt(a.refreshTokenEnc, ctx).decodeToString()
            val tokens = p.refresh(client, refresh, a.canEdit)
            if (tokens.refreshToken != null && tokens.refreshToken != refresh) {
                store.transaction { store.updateRefreshToken(a.id, cipher.encrypt(tokens.refreshToken.toByteArray(), ctx)) }
            }
            val from = now() - WINDOW_BACK_MS
            val to = now() + WINDOW_AHEAD_MS
            val events = p.events(tokens.accessToken, from, to)
            val wrote = apply(a, events, from, to)
            store.transaction { store.markSynced(a.id, now()) }
            if (wrote) runCatching { onChanged(a.householdId) }
        } catch (e: ReconnectRequired) {
            store.transaction { store.markError(a.id, "needs_reconnect", e.message ?: "reconnect") }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /**
     * A fresh access token for [a] (calendar editing: the writer asks with [editing], so Microsoft includes the write
     * scope); a rotated refresh token is stored. Throws [ReconnectRequired] when the sign-in is no longer good.
     */
    internal fun accessToken(a: AccountRow, editing: Boolean): String {
        val p = providers.getValue(a.provider)
        val client = clients.get(a.provider) ?: throw ReconnectRequired("provider not configured")
        val ctx = context(a.householdId, a.provider)
        val refresh = cipher.decrypt(a.refreshTokenEnc, ctx).decodeToString()
        val tokens = p.refresh(client, refresh, editing)
        if (tokens.refreshToken != null && tokens.refreshToken != refresh) {
            store.transaction { store.updateRefreshToken(a.id, cipher.encrypt(tokens.refreshToken.toByteArray(), ctx)) }
        }
        return tokens.accessToken
    }

    private fun syncFeed(a: AccountRow, feed: FeedProvider) {
        try {
            val from = now() - WINDOW_BACK_MS
            val to = now() + WINDOW_AHEAD_MS
            val wrote = apply(a, feed.events(from, to), from, to)
            store.transaction { store.markSynced(a.id, now()) }
            if (wrote) runCatching { onChanged(a.householdId) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /** Headlines change all day, but the brief is read once: refresh at most every [NEWS_PERIOD_MS]. */
    private fun syncNews(a: AccountRow, source: NewsProvider) {
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < NEWS_PERIOD_MS) return
        try {
            var failures = 0
            val fetched = source.topics.mapNotNull { t -> runCatching { t to source.headlines(t) }.getOrElse { failures++; null } }.toMap()
            // One topic's feed failing leaves its headlines as they were; only fail when nothing could be read.
            if (failures == source.topics.size) error("no news feed could be read")
            // Pictures first, outside the database transaction (fetching takes a while); a failure leaves them out.
            val shown = fetched.values.flatMap { items -> items.distinctBy { it.id }.take(source.slots) }
            val pictures = images?.let { im -> runCatching { im.prepare(shown.sortedByDescending { it.publishedMs }.mapNotNull { it.imageUrl }) }.getOrNull() }.orEmpty()
            applyNews(a, source, fetched, pictures)
            store.transaction { store.markSynced(a.id, now()) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /** The list changes a few times a year: refresh weekly (sooner after a failure, at the next poll). */
    private fun syncHolidays(a: AccountRow, list: HolidayProvider) {
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < HOLIDAYS_PERIOD_MS) return
        try {
            val wrote = applyHolidays(a, list.label, list.holidays())
            store.transaction { store.markSynced(a.id, now()) }
            // Work mode on every device should follow a changed list without waiting for their next sync.
            if (wrote) runCatching { onChanged(a.householdId) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /**
     * Writes the list (from the start of last year on, so it stays small) into the household's one
     * `context_mode/bank_holidays` entity, only when it changed. Returns whether anything was written.
     */
    internal fun applyHolidays(a: AccountRow, label: String, list: List<BankHoliday>): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val thisYear = CivilDate.fromEpochDay(now().floorDiv(CivilDate.DAY_MS)).year
            val from = CivilDate.toEpochDay(thisYear - 1, 1, 1)
            val kept = list.filter { it.epochDay >= from }
            val desired = linkedMapOf(
                BankHolidayFields.DATES to FieldValue.Text(BankHolidays.encode(kept)),
                BankHolidayFields.SOURCE to FieldValue.Text(label),
            )
            val prev = store.mirror(a.householdId, a.id)[BankHolidayStore.ENTITY_ID]
            write(a, BankHolidayStore.ENTITY_ID, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
        }
    }

    /**
     * Mirrors the newest [NEWS_SLOTS] headlines of each topic into fixed slots (one `headline` entity per topic and
     * slot), so the number of entities never grows. A headline still in the feed keeps its slot and is not
     * rewritten; new ones fill the slots that freed up; slots left over are marked removed.
     */
    internal fun applyNews(
        a: AccountRow, source: NewsProvider, byTopic: Map<String, List<RemoteHeadline>>,
        /** Picture address → the key [NewsImages] keeps it under. */
        pictures: Map<String, String> = emptyMap(),
    ) = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val mirror = store.mirror(a.householdId, a.id)
            for ((topic, items) in byTopic) {
                val n = source.slots
                val desired = items.distinctBy { it.id }.take(n)
                val slots = (0 until n).map { newsEntityId(a, topic, it) }
                fun urlIn(slot: String) = mirror[slot]?.takeUnless { it.removed }?.fieldOps?.get(HeadlineFields.URL)?.second?.removePrefix("s:")
                val assigned = arrayOfNulls<RemoteHeadline>(n)
                slots.forEachIndexed { i, slot -> assigned[i] = desired.firstOrNull { it.url == urlIn(slot) } }
                val rest = ArrayDeque(desired.filter { d -> assigned.none { it === d } })
                for (i in assigned.indices) if (assigned[i] == null) assigned[i] = rest.removeFirstOrNull()
                slots.forEachIndexed { i, slot ->
                    val h = assigned[i]
                    val prev = mirror[slot]
                    if (h == null) {
                        if (prev != null && !prev.removed) {
                            write(a, slot, prev.startMs, prev.endMs, true, prev, mapOf(HeadlineFields.REMOVED to FieldValue.Bool(true)), EntityTypes.HEADLINE)
                        }
                    } else {
                        val desiredFields = linkedMapOf(
                            HeadlineFields.TITLE to FieldValue.Text(h.title.take(MAX_HEADLINE)),
                            HeadlineFields.URL to FieldValue.Text(h.url),
                            HeadlineFields.SOURCE to FieldValue.Text((h.source ?: source.source).take(MAX_SOURCE)),
                            HeadlineFields.TOPIC to FieldValue.Text(topic),
                            HeadlineFields.PUBLISHED_AT to FieldValue.Int64(h.publishedMs),
                            HeadlineFields.REMOVED to FieldValue.Bool(false),
                            // The feed's own summary, plain text (news ticker, slice 1). Additive; never written as null
                            // for a slot that never had one.
                            HeadlineFields.SUMMARY to (h.summary?.take(MAX_SUMMARY)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                            // The picture's key on this server (images slice), never the publisher's address. Additive.
                            HeadlineFields.IMAGE to (h.imageUrl?.let(pictures::get)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                        )
                        write(a, slot, h.publishedMs, h.publishedMs, false, prev, desiredFields, EntityTypes.HEADLINE)
                    }
                }
            }
        }
    }

    /**
     * Writes ops only for real changes, chaining each field on the server's previous op so nothing conflicts.
     * Returns whether anything was written.
     */
    internal fun apply(a: AccountRow, events: List<RemoteEvent>, fromMs: Long, toMs: Long): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val mirror = store.mirror(a.householdId, a.id)
            val seen = HashSet<String>()
            var wrote = false
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
                    // Notes and the call link (calendar redesign, slice 3). Not written at all until an event has them.
                    EventFields.DESCRIPTION to (e.description?.take(MAX_NOTES)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    EventFields.JOIN_URL to (httpsOrNull(e.joinUrl)?.take(MAX_TEXT)?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    // The real event's page, to edit it there (calendar actions, slice 3). Additive like the above.
                    EventFields.WEB_URL to (httpsOrNull(e.webUrl)?.takeIf { it.length <= MAX_URL }?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    EventFields.REMOVED to FieldValue.Bool(false),
                )
                // A fixture whose kick-off moved while the old time was still ahead: remember the old time and when
                // the move was seen, so the apps can say "Kick-off moved" (push, slice 2). Additive; fixtures only.
                val prev = mirror[entityId]
                if (a.provider in feeds && prev != null && !prev.removed && !e.allDay && prev.startMs != e.startMs && prev.startMs > now()) {
                    desired[EventFields.MOVED_FROM] = FieldValue.Int64(prev.startMs)
                    desired[EventFields.MOVED_AT] = FieldValue.Int64(now())
                }
                wrote = write(a, entityId, e.startMs, e.endMs, false, prev, desired, remoteId = e.id) || wrote
            }
            // Anything we mirrored inside this window that the provider no longer reports was cancelled or deleted.
            // Providers return events that overlap the window, so judge removals by overlap too. All-day events near
            // the far edge are skipped: providers apply the window in the calendar's own time zone.
            for (m in mirror.values) {
                if (m.entityId in seen || m.removed) continue
                val overlaps = m.endMs > fromMs && m.startMs < toMs
                val nearFarEdge = m.allDay && m.startMs > toMs - 14 * 3_600_000L
                if (!overlaps || nearFarEdge) continue
                wrote = write(a, m.entityId, m.startMs, m.endMs, true, m, mapOf(EventFields.REMOVED to FieldValue.Bool(true))) || wrote
            }
            wrote
        }
    }

    /** Returns whether any op was appended. */
    private fun write(
        a: AccountRow, entityId: String, startMs: Long, endMs: Long, removed: Boolean, prev: MirrorRow?, desired: Map<String, FieldValue>,
        entityType: String = EntityTypes.EVENT,
        /** The provider's id (calendar events): kept on the mirror row so edits can find the real event. */
        remoteId: String? = prev?.remoteId,
    ): Boolean {
        val fieldOps = HashMap(prev?.fieldOps ?: emptyMap())
        var changed = prev == null || prev.removed != removed || prev.startMs != startMs || prev.endMs != endMs ||
            (remoteId != null && prev.remoteId != remoteId)
        var appended = false
        for ((field, value) in desired) {
            val key = valueKey(value)
            val last = fieldOps[field]
            if (last?.second == key) continue
            // A field that was never written reads as null already: don't write ops just to say so.
            if (last == null && value == FieldValue.Null) continue
            val op = Op(
                opId = "srv" + token(18).lowercase().filter(Char::isLetterOrDigit),
                householdId = a.householdId, entityType = entityType, entityId = entityId, field = field,
                value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = listOfNotNull(last?.first), deviceId = SERVER_DEVICE,
            )
            ops.append(op)
            fieldOps[field] = op.opId to key
            changed = true
            appended = true
        }
        val allDay = (desired[EventFields.ALL_DAY] as? FieldValue.Bool)?.value ?: prev?.allDay ?: false
        if (changed) store.putMirror(a.householdId, MirrorRow(entityId, a.id, startMs, removed, fieldOps, endMs, allDay, remoteId ?: prev?.remoteId))
        return appended
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(rng::nextBytes))

    companion object {
        const val SERVER_DEVICE = "server"
        const val STATE_TTL_MS = 15 * 60_000L
        const val WINDOW_BACK_MS = 24 * 3_600_000L
        const val WINDOW_AHEAD_MS = 30 * 24 * 3_600_000L
        private const val MAX_TEXT = 500
        private const val MAX_NOTES = 2_000
        // A cut link is a broken link: longer ones (none seen in practice) are left out rather than cut.
        private const val MAX_URL = 2_000
        private const val MAX_HEADLINE = 300
        private const val MAX_SOURCE = 80
        private const val MAX_SUMMARY = 500
        /** Headlines mirrored per topic by default (a provider may ask for more, see [NewsProvider.slots]). */
        const val NEWS_SLOTS = 4
        const val NEWS_PERIOD_MS = 60 * 60_000L
        const val HOLIDAYS_PERIOD_MS = 7 * 24 * 60 * 60_000L

        /** Stable per (account, topic, slot): a topic always uses the same few entities. */
        fun newsEntityId(a: AccountRow, topic: String, slot: Int): String = "hl" + Secrets.sha256Hex("${a.id}|$topic|$slot").take(30)

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
