package os.meka.backend.integrations

import os.meka.backend.OwnKeyReminders
import os.meka.backend.Secrets
import os.meka.core.domain.BankHoliday
import os.meka.core.domain.MailSortFacts
import os.meka.core.domain.MailSortRules
import os.meka.core.domain.MailReceiptRules
import os.meka.core.domain.MailBacklogFacts
import os.meka.core.domain.MailBacklog
import os.meka.core.domain.MailBacklogKind
import os.meka.core.domain.MailBacklogRules
import os.meka.core.domain.MailTidyFacts
import os.meka.core.domain.MailSenderRule
import os.meka.core.domain.MailSenderRuleRules
import os.meka.core.domain.MailTidyRules
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.MailJunkFacts
import os.meka.core.domain.MailJunkRules
import os.meka.core.domain.MailTidyBatchFields
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailSender
import os.meka.core.domain.MailUnsubscribeRules
import os.meka.core.domain.MailUnopenedRules
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.UnsubscribeMethod
import os.meka.core.domain.UnsubscribePlan
import os.meka.core.domain.UnsubscribeState
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.BankHolidayFields
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.BankHolidays
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.HeadlineFields
import os.meka.core.domain.LineState
import os.meka.core.domain.LineStatusCodec
import os.meka.core.domain.LineStatusFields
import os.meka.core.domain.LineStatusStore
import os.meka.core.domain.RouteRules
import os.meka.core.domain.SignInFields
import os.meka.core.domain.SignInRules
import os.meka.core.domain.SignInState
import os.meka.core.domain.PlacesRules
import os.meka.core.domain.WeatherCodec
import os.meka.core.domain.WeatherFields
import os.meka.core.domain.WeatherForecast
import os.meka.core.domain.WeatherPlaceFields
import os.meka.core.domain.WeatherPlaceRules
import os.meka.core.domain.WeatherPlaceStore
import os.meka.core.domain.WeatherStore
import os.meka.core.domain.WorkPlaceFields
import os.meka.core.domain.WorkPlaceStore
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.EventMarkFields
import os.meka.core.domain.TravelRules
import os.meka.core.domain.CarCheck
import os.meka.core.domain.CarCheckFields
import os.meka.core.domain.CarCheckRules
import os.meka.core.domain.CarFields
import os.meka.core.domain.CarRules
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailLabel
import os.meka.core.domain.MailProviders
import os.meka.core.sync.EntityRef
import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.Hlc
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
    /** A public forecast for home, mirrored for Today, the brief and Ask (weather item; no sign-in, no key). */
    private val weather: Map<String, WeatherProvider> = emptyMap(),
    /** News pictures (images slice): fetched, shrunk and kept by the server; null leaves headlines without pictures. */
    private val images: NewsImages? = null,
    /**
     * Called after a calendar or fixtures poll wrote ops for a household (not for headlines), so push can wake its
     * devices: a moved event's reminders re-arm and a moved kick-off is announced within seconds of the poll.
     */
    private val onChanged: (householdId: String) -> Unit = {},
    /** MEKA's own keys that run out, put on each household's renewals radar once (Outlook calendar item). */
    private val ownKeys: OwnKeyReminders? = null,
    /** Reads what the devices wrote (the Weather place setting); Postgres passes its indexed reader. */
    private val reader: EntityReader = EntityReader.scanning(ops),
    /** The status of the train lines on Meka's route (Places item 4; TfL, no sign-in, no key). */
    private val lines: Map<String, LineStatusProvider> = emptyMap(),
    /** Where "the day" is for the line status poll (it rests overnight). */
    private val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/London"),
    /** Drive times to the kids' football (weekend football, slice 2b; Google Routes, off until its key is pasted). */
    private val travel: Map<String, TravelProvider> = emptyMap(),
    /** The local calendar the football's evening before and kick-off are judged in. */
    private val calendar: os.meka.core.domain.LocalCalendar = os.meka.backend.LondonCalendar,
    /** The car's MOT and tax from the DVLA (car on autopilot, slice 2; off until the DVLA keys are pasted). */
    private val cars: Map<String, CarLookupProvider> = emptyMap(),
    /**
     * Mail accounts (Inbox autopilot, slice 1): Gmail and Outlook, each signed in through its OAuth provider's client
     * and callback. Slice 1 connects and checks the sign-in; nothing is read, moved or sent yet.
     */
    private val mail: Map<String, MailProvider> = emptyMap(),
    /** Slice 1c: each mail account's headers (last 60 days), kept current from the provider's change feed; null skips it. */
    private val mailStore: MailStore? = null,
    /** Slice 2b: the AI's look at the unsure ones (sender and subject only); null (AI off, tests) leaves them unsure. */
    private val mailAi: MailAiSorter? = null,
    /** Slice 5a: RFC 8058 one-click unsubscribe (an https POST, only on Meka's tap); [OneClickUnsubscriber.NONE] in tests. */
    private val unsubscriber: OneClickUnsubscriber = OneClickUnsubscriber.NONE,
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
        mail[provider]?.let { m -> return startMail(householdId, m) }
        val p = providers[provider] ?: return StartResult.UnknownProvider
        val client = clients.get(provider) ?: return StartResult.NotConfigured
        val state = token(24)
        val verifier = token(48)
        store.transaction { store.saveState(state, PendingConnect(householdId, provider, verifier, now(), editing)) }
        return StartResult.Url(p.authorizeUrl(client, redirectUri(provider), state, challenge(verifier), editing))
    }

    /** A mail account's sign-in: its OAuth provider's client and callback, the mail permissions only. */
    private fun startMail(householdId: String, m: MailProvider): StartResult {
        val client = clients.get(m.oauth) ?: return StartResult.NotConfigured
        val state = token(24)
        val verifier = token(48)
        store.transaction { store.saveState(state, PendingConnect(householdId, m.id, verifier, now())) }
        return StartResult.Url(m.authorizeUrl(client, redirectUri(m.oauth), state, challenge(verifier)))
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
            ?: return CallbackResult.Failed("This sign-in link has expired or was already used. Start again from Navilife.")
        // A mail sign-in comes back through its OAuth provider's callback (google, microsoft).
        val mailP = mail[pending.provider]
        if ((mailP?.oauth ?: pending.provider) != provider || now() - pending.createdAtMs > STATE_TTL_MS) {
            return CallbackResult.Failed("This sign-in link has expired. Start again from Navilife.")
        }
        if (error != null || code.isNullOrBlank()) return CallbackResult.Failed("Sign-in was cancelled.")
        if (mailP != null) return mailCallback(mailP, pending, code)
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
        val id = store.transaction { store.upsertAccount(pending.householdId, provider, email, enc, canEdit, grantedAtMs = now()) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
        return CallbackResult.Connected(provider, email, id, canEdit, pending.editing)
    }

    /** Completes a mail sign-in: every mail permission must have been granted; the first one is labelled for Meka. */
    private fun mailCallback(m: MailProvider, pending: PendingConnect, code: String): CallbackResult {
        val client = clients.get(m.oauth) ?: return CallbackResult.Failed("This provider is not set up on the server yet.")
        val tokens = m.exchangeCode(client, redirectUri(m.oauth), code, pending.codeVerifier)
        val refresh = tokens.refreshToken ?: return CallbackResult.Failed("The provider did not grant offline access. Try connecting again.")
        if (tokens.scope != null && m.requiredScopes.any { !grants(tokens.scope, it) }) {
            return CallbackResult.Failed(
                "Mail access wasn't fully allowed. Connect again and keep every permission ticked: Navilife reads and tidies your mail and sends only when you tap Send.",
            )
        }
        val email = m.accountEmail(tokens.accessToken).lowercase()
        val enc = cipher.encrypt(refresh.toByteArray(), context(pending.householdId, m.id))
        val id = store.transaction {
            val accountId = store.upsertAccount(pending.householdId, m.id, email, enc, false, grantedAtMs = now()) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) }
            if (store.account(accountId)?.label == null) {
                val taken = store.accounts(pending.householdId).filter { MailProviders.isMail(it.provider) }.mapNotNull { MailLabel.of(it.label) }
                store.setLabel(pending.householdId, m.id, email, MailAccountRules.defaultLabel(m.id, taken).name)
            }
            accountId
        }
        return CallbackResult.Connected(m.id, email, id)
    }

    fun accounts(householdId: String): List<WireCodec.IntegrationAccount> =
        store.accounts(householdId).filter { it.status != DISCONNECTED }.map {
            val sorted = if (it.provider in mail) sortedCounts(it.id) else null
            WireCodec.IntegrationAccount(it.provider, it.email, it.status, it.lastSyncAtMs, it.canEdit, it.label, sorted = sorted,
                wouldTidy = if (sorted != null) tidyCounts(householdId, it.id, keepAddresses(householdId)) else null)
        }

    /**
     * Slice 3a: Autopilot's dry run — what [MailTidyRules] would do with each sorted email still in the inbox, counted
     * per [MailTidy] name (KEEP included). Nothing in the mailbox moves; null if the store can't be read.
     */
    private fun tidyCounts(householdId: String, accountId: String, keep: Set<String>): Map<String, Int>? {
        val ms = mailStore ?: return null
        return runCatching {
            val inbox = ms.inboxSorted(accountId)
            val rules = senderRules(householdId, accountId, inbox.map { it.first.fromAddress })
            inbox.groupingBy { (h, s) ->
                MailTidyRules.plan(MailTidyFacts(h.fromAddress, h.inInbox, s, keep, rule = h.fromAddress?.lowercase()?.let { rules[it] })).name
            }.eachCount()
        }.getOrNull()
    }

    /**
     * Completeness: Meka's own rule for each of these senders of one account ([MailSenderRule], set in Mail on the
     * sender's synced `mail_sender` entity, whose id is the account and the address), by lower-cased address; senders
     * without a rule are left out. Never logged.
     */
    internal fun senderRules(householdId: String, accountId: String, addresses: Collection<String?>): Map<String, MailSenderRule> {
        val senders = runCatching { reader.entities(householdId, EntityTypes.MAIL_SENDER) }.getOrDefault(emptyMap())
        if (senders.isEmpty()) return emptyMap()
        return addresses.mapNotNull { it?.lowercase()?.takeIf { a -> a.isNotEmpty() } }.distinct().mapNotNull { address ->
            val id = MailUnsubscribeRules.senderId(accountId, address)
            MailSenderRuleRules.read(senders[id])?.let { address to it }
        }.toMap()
    }

    /**
     * Slice 3b-2b: the addresses Autopilot never tidies, from the household's About you (the partner's email); empty
     * when there's none or the profile can't be read. Never logged.
     */
    internal fun keepAddresses(householdId: String): Set<String> =
        runCatching { AboutYouRules.keepAddresses(reader.entity(householdId, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)) }
            .getOrDefault(emptySet())

    /**
     * Slice 2a: a mail account's inbox counts per sort, or null while there's nothing sorted yet and its first
     * listing is still being read (so Mail doesn't say "Inbox clear" before MEKA has looked).
     */
    private fun sortedCounts(accountId: String): Map<String, Int>? {
        val ms = mailStore ?: return null
        return runCatching {
            val counts = ms.sortCounts(accountId)
            if (counts.isEmpty() && (ms.cursor(accountId) == null || ms.pendingCount(accountId) > 0)) null else counts
        }.getOrNull()
    }

    /** Names what a mail account is for (Personal · Business · Outlook). False for an unknown account, provider or label. */
    fun setMailLabel(householdId: String, provider: String, email: String, label: String): Boolean =
        provider in mail && MailLabel.of(label) != null && store.transaction { store.setLabel(householdId, provider, email.lowercase(), label) }

    /**
     * Disconnects a mail account (Inbox autopilot, slice 1b): asks the provider to withdraw MEKA's permission (best
     * effort; Google only), wipes the stored sign-in and marks the account disconnected, so nothing reads it any more
     * and Today's sign-in line for it goes (its sign-in entity turns OK). Nothing in the mailbox is touched. Signing in
     * again brings it back with its label. False for an unknown or non-mail account.
     */
    fun disconnectMail(householdId: String, provider: String, email: String): Boolean {
        val m = mail[provider] ?: return false
        val a = store.accounts(householdId).firstOrNull { it.provider == provider && it.email == email.lowercase() && it.status != DISCONNECTED }
            ?: return false
        runCatching {
            val refresh = cipher.decrypt(a.refreshTokenEnc, context(a.householdId, a.provider)).decodeToString()
            m.revoke(refresh)
        }
        val done = store.transaction { store.disconnect(householdId, provider, a.email) }
        // Slice 6a: its "From your mail" cards go from Needs you.
        if (done) runCatching { goneMailNeeds(a, emptySet()) }
        // Slice 1c: the headers MEKA kept for it go too; signing in again lists the mail afresh.
        if (done) runCatching { mailStore?.forget(a.id) }
        if (done) {
            store.account(a.id)?.let { runCatching { if (applySignIn(it)) onChanged(householdId) } }
        }
        return done
    }

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
        runCatching { noteSignIns() }
    }

    /**
     * Reliability first, item 2: writes each signed-in calendar account's state into its own
     * `context_mode/sign_in.<key>` entity ([SignInRules]): fine, ending within the day (a Google sign-in made while
     * MEKA's Google app is in Testing lasts 7 days) or expired (the provider refused it: `needs_reconnect`). Only
     * changes are written; a changed state wakes the household's devices so Today's line and the heads-up follow.
     */
    fun noteSignIns() {
        for (hh in store.households()) {
            var changed = false
            for (a in store.accounts(hh)) {
                // Calendars and (Inbox autopilot, slice 1b) mail accounts: a Gmail sign-in runs out like a Google one.
                if (a.provider !in providers && a.provider !in mail) continue
                runCatching { if (applySignIn(a)) changed = true }
            }
            if (changed) runCatching { onChanged(hh) }
        }
    }

    /** Writes one account's sign-in state when it changed; returns whether the state itself changed. */
    internal fun applySignIn(a: AccountRow): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val entityId = SignInRules.entityId(a.id)
            val prev = store.mirror(a.householdId, a.id)[entityId]
            val prevState = prev?.fieldOps?.get(SignInFields.STATE)?.second?.removePrefix("s:")
            val seenExpired = if (prevState == SignInState.EXPIRED.name) {
                prev?.fieldOps?.get(SignInFields.UNTIL)?.second?.takeIf { it.startsWith("i:") }?.removePrefix("i:")?.toLongOrNull()
            } else null
            val (state, until) = SignInRules.state(a.provider, a.status, a.grantedAtMs, now(), seenExpired)
            val desired = linkedMapOf(
                SignInFields.PROVIDER to FieldValue.Text(a.provider),
                SignInFields.ACCOUNT to FieldValue.Text(a.email),
                SignInFields.STATE to FieldValue.Text(state.name),
                SignInFields.UNTIL to (until?.let { FieldValue.Int64(it) } ?: FieldValue.Null),
                SignInFields.EDIT to FieldValue.Bool(a.canEdit),
            )
            write(a, entityId, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
            prevState != null && prevState != state.name || prevState == null && state != SignInState.OK
        }
    }

    /** Every household follows the default feeds (owner's stated MVP need). Idempotent. */
    fun ensureFeeds() {
        val all = feeds.values.map { it.id to it.label } + news.values.map { it.id to it.source } + holidays.values.map { it.id to it.label } +
            weather.values.map { it.id to it.label } + lines.values.map { it.id to it.label } + travel.values.map { it.id to it.label } +
            cars.values.map { it.id to it.label }
        for (hh in store.households()) for ((id, label) in all) {
            if (store.accounts(hh).none { it.provider == id }) {
                store.transaction { store.upsertAccount(hh, id, label, ByteArray(0)) { "acc" + token(12).lowercase().filter(Char::isLetterOrDigit) } }
            }
        }
        ownKeys?.let { k -> for (hh in store.households()) runCatching { k.ensure(hh) } }
    }

    private val accountLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** One sync per account at a time in this process; [IntegrationStore.lockAccount] covers other processes. */
    fun syncAccount(accountId: String) = synchronized(accountLocks.computeIfAbsent(accountId) { Any() }) { syncLocked(accountId) }

    private fun syncLocked(accountId: String) {
        val a = store.account(accountId) ?: return
        feeds[a.provider]?.let { feed -> return syncFeed(a, feed) }
        news[a.provider]?.let { source -> return syncNews(a, source) }
        holidays[a.provider]?.let { list -> return syncHolidays(a, list) }
        weather[a.provider]?.let { source -> return syncWeather(a, source) }
        lines[a.provider]?.let { source -> return syncLines(a, source) }
        travel[a.provider]?.let { source -> return syncTravel(a, source) }
        cars[a.provider]?.let { source -> return syncCar(a, source) }
        mail[a.provider]?.let { m -> return syncMail(a, m) }
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
     * A mail account's check (slice 1): the sign-in still works and the inbox can be read (its counts only). A
     * revoked or expired sign-in turns the account to needs_reconnect, which Mail shows with Reconnect.
     */
    private fun syncMail(a: AccountRow, m: MailProvider) {
        val client = clients.get(m.oauth) ?: return
        val ctx = context(a.householdId, a.provider)
        try {
            val refresh = cipher.decrypt(a.refreshTokenEnc, ctx).decodeToString()
            val tokens = m.refresh(client, refresh)
            if (tokens.refreshToken != null && tokens.refreshToken != refresh) {
                store.transaction { store.updateRefreshToken(a.id, cipher.encrypt(tokens.refreshToken.toByteArray(), ctx)) }
            }
            m.inbox(tokens.accessToken)
            mailStore?.let { syncHeaders(a, m, tokens.accessToken, it) }
            store.transaction { store.markSynced(a.id, now()) }
        } catch (e: ReconnectRequired) {
            store.transaction { store.markError(a.id, "needs_reconnect", e.message ?: "reconnect") }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /**
     * Slice 1c: the account's change feed since the stored cursor (from scratch: the last [MAIL_WINDOW_MS]), then up to
     * [HEADERS_PER_CHECK] pending headers read, then anything older than the window dropped. A cursor the provider has
     * forgotten starts the account again. Headers only; nothing in the mailbox changes.
     */
    private fun syncHeaders(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val since = now() - MAIL_WINDOW_MS
        val changes = try {
            m.changes(token, ms.cursor(a.id), since)
        } catch (e: MailCursorExpired) {
            ms.forget(a.id)
            m.changes(token, null, since)
        }
        ms.apply(a.householdId, a.id, changes)
        for (id in ms.pending(a.id, HEADERS_PER_CHECK)) {
            val h = m.header(token, id)
            if (h == null) ms.remove(a.id, id) else ms.put(a.householdId, a.id, h)
        }
        ms.prune(a.id, since)
        // Slice 6a: Done and Undo asked on the "From your mail" cards, before sorting (an email put back is sorted again now).
        runCatching { mailNeedsAsks(a, m, token, ms) }
        sortHeaders(a, ms)
        // Slice 5a: the newsletter and promo senders (before Autopilot moves their newest email) and any Unsubscribe asked.
        runCatching { mailSenders(a, m, token, ms) }
        // Slice 3b: Autopilot (only once it's on and the dry-run day is over); a failure there never fails the check.
        runCatching { autopilot(a, m, token, ms) }
        // Slice 3c: the daily Junk review (same switch and dry-run day as Autopilot).
        runCatching { junkReview(a, m, token, ms) }
        // Slice 8a: the backlog clean-up's plan (read-only: nothing in the mailbox moves).
        runCatching { backlog(a, m, token, ms) }
        // Slice 6a: the emails that need Meka, as cards in Needs you.
        runCatching { publishMailNeeds(a, ms) }
    }

    /**
     * Inbox autopilot, slice 8a: the backlog clean-up's plan, once per account. The mail already in the inbox from before
     * Autopilot's start (the account's dry-run day's end, or now if sooner) is listed a page a check and its headers read
     * [BACKLOG_HEADERS_PER_CHECK] a check (Gmail; Outlook's pages carry them); each email is planned by
     * [MailBacklogRules.plan] and kept as ids, sender and date. Once all are read, the threads Meka wrote in are taken
     * out (kept) and the plan is ready. Each check publishes the counts as the account's `mail_backlog` entity. Nothing
     * in the mailbox changes: applying the plan waits for Approve (slice 8b).
     */
    internal fun backlog(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val nowMs = now()
        val scan = ms.backlogScan(a.id) ?: BacklogScan(
            beforeMs = minOf(nowMs, (a.grantedAtMs ?: nowMs) + MailAutopilotRules.DRY_RUN_MS), startedMs = nowMs,
            nextPage = null, listed = false, readyMs = null,
        )
        var s = scan
        val keepBase = keepAddresses(a.householdId)
        // Completeness: a sender Meka keeps in the inbox is kept by the clean-up too (read once a check).
        val keptIds = runCatching { reader.entities(a.householdId, EntityTypes.MAIL_SENDER) }.getOrDefault(emptyMap())
            .filter { (_, f) -> MailSenderRuleRules.read(f) == MailSenderRule.KEEP }.keys
        fun planned(h: MailHeader): BacklogItem {
            val sorted = MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId))
            val kept = h.fromAddress?.lowercase()?.takeIf { MailUnsubscribeRules.senderId(a.id, it) in keptIds }
            val keep = if (kept != null) keepBase + kept else keepBase
            val kind = MailBacklogRules.plan(MailBacklogFacts(h.fromAddress, h.inInbox, sorted, h.receivedAtMs, keep), nowMs)
            return BacklogItem(h.id, h.threadId, h.fromAddress, h.fromName, h.receivedAtMs, kind)
        }
        if (s.readyMs == null) {
            if (!s.listed && ms.backlogPending(a.id, BACKLOG_LIST_BELOW).size < BACKLOG_LIST_BELOW) {
                val page = m.backlog(token, s.beforeMs, s.nextPage) ?: return
                ms.addBacklogIds(a.id, page.ids)
                ms.putBacklog(a.id, page.headers.map(::planned))
                s = s.copy(nextPage = page.next, listed = page.next == null)
            }
            val read = ms.backlogPending(a.id, BACKLOG_HEADERS_PER_CHECK).mapNotNull { id ->
                val h = m.header(token, id)
                // Gone, or out of the inbox since it was listed: not the backlog's any more.
                if (h == null || !h.inInbox) { ms.removeBacklog(a.id, id); null } else planned(h)
            }
            ms.putBacklog(a.id, read)
            if (s.listed && ms.backlogPending(a.id, 1).isEmpty()) {
                // Every older email read: the threads he wrote in are kept. Can't tell (the provider failed): try next check.
                val items = ms.backlogItems(a.id)
                val oldest = items.minOfOrNull { it.receivedAtMs } ?: s.beforeMs
                val replied = runCatching { m.repliedThreads(token, oldest) }.getOrNull()
                if (replied != null) {
                    ms.putBacklog(a.id, items.filter { it.kind != MailBacklogKind.KEEP && it.threadId != null && it.threadId in replied }
                        .map { it.copy(kind = MailBacklogKind.KEEP) })
                    s = s.copy(readyMs = nowMs)
                }
            }
            ms.setBacklogScan(a.id, s)
        }
        val items = ms.backlogItems(a.id)
        val counts = items.groupingBy { it.kind }.eachCount()
        val top = MailBacklogRules.topSenders(items.filter { it.kind != MailBacklogKind.KEEP && it.kind != MailBacklogKind.JUNK }.map { it.fromName to it.fromAddress })
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val entityId = MailBacklogRules.entityId(a.id)
        writeMail(a, entityId, MailBacklogRules.fields(account, s.startedMs, items.size, counts, top, s.readyMs), EntityTypes.MAIL_BACKLOG)
        // Slice 8b: once Meka approved the final plan, apply it (or put it back after an Undo).
        val plan = reader.entity(a.householdId, EntityTypes.MAIL_BACKLOG, entityId)?.let { MailBacklogRules.read(entityId, it) } ?: return
        if (plan.approved || plan.applied > 0) applyBacklog(a, m, token, ms, plan, items, account)
    }

    /**
     * Inbox autopilot, slice 8b: the approved clean-up. Each check moves up to [MailBacklogRules.APPLY_PER_CHECK_GMAIL]
     * (Outlook [MailBacklogRules.APPLY_PER_CHECK_OUTLOOK]) of the planned emails not moved yet, recorded under the
     * account's one clean-up tidy ([MailBacklogRules.tidyBatchId]) so its Undo finds them all; the tidy's counts grow as
     * it goes. MEKA's kill switch stops it; an Undo stops it and puts back [MailBacklogRules.UNDO_PER_CHECK] a check
     * until every moved email is back in the inbox (and never tidied again). Nothing moves without the approval.
     */
    private fun applyBacklog(a: AccountRow, m: MailProvider, token: String, ms: MailStore, plan: MailBacklog, items: List<BacklogItem>, account: String) {
        val hh = a.householdId
        val batchId = MailBacklogRules.tidyBatchId(a.id)
        val batch = reader.entity(hh, EntityTypes.MAIL_TIDY, batchId)?.let { MailAutopilotRules.readBatch(batchId, it) }
        val entityId = plan.id
        val moved = ms.tidied(a.id, batchId)
        if (batch != null && batch.undoneAtMs == null && MailAutopilotRules.undoDue(batch, now())) {
            val never = ms.neverTidied(a.id)
            val left = moved.filter { it.id !in never }
            val chunk = left.take(MailBacklogRules.UNDO_PER_CHECK)
            var putBack = plan.putBack
            if (chunk.isNotEmpty()) {
                val back = m.untidy(token, chunk.associate { it.movedId to it.tidy })
                ms.neverTidy(a.id, chunk.map { it.id } + chunk.map { it.movedId } + back.values)
                putBack += back.size
            }
            writeMail(a, entityId, MailBacklogRules.progressFields(moved.size, plan.applyStartedAtMs, plan.finishedAtMs, putBack), EntityTypes.MAIL_BACKLOG)
            if (left.size <= chunk.size) {
                writeTidy(a, batchId, mapOf(
                    MailTidyBatchFields.UNDONE_AT to FieldValue.Int64(now()),
                    MailTidyBatchFields.UNDO_NOTE to FieldValue.Text(MailAutopilotRules.undoneNote(putBack, moved.size)),
                ))
            }
            return
        }
        val paused = AutonomyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused
        if (!MailBacklogRules.shouldApply(plan, paused, batch)) return
        val done = moved.map { it.id }.toSet()
        val perCheck = if (m.id == MailProviders.OUTLOOK) MailBacklogRules.APPLY_PER_CHECK_OUTLOOK else MailBacklogRules.APPLY_PER_CHECK_GMAIL
        // Completeness: a sender Meka chose to keep in the inbox after the plan was made is still left alone.
        val keptNow = senderRules(hh, a.id, items.map { it.fromAddress }).filterValues { it == MailSenderRule.KEEP }.keys
        val next = items.filter { it.kind != MailBacklogKind.KEEP && it.id !in done && it.fromAddress?.lowercase() !in keptNow }
        val chunk = next.take(perCheck)
        val nowMs = now()
        val startedAt = plan.applyStartedAtMs ?: nowMs
        var all = moved
        if (chunk.isNotEmpty()) {
            val got = m.tidy(token, chunk.associate { it.id to it.kind.tidy })
            val newly = chunk.mapNotNull { item -> got[item.id]?.let { movedId -> MailMoved(item.id, movedId, item.kind.tidy) } }
            ms.recordTidy(a.id, batchId, newly)
            all = moved + newly
            // Not moved: gone or out of the inbox since the plan → not the backlog's any more; otherwise tried next check.
            chunk.filter { it.id !in got }.forEach { item ->
                val h = runCatching { m.header(token, item.id) }.getOrElse { return@forEach }
                if (h == null || !h.inInbox) ms.removeBacklog(a.id, item.id)
            }
        }
        val doneIds = all.map { it.id }.toSet()
        val finished = ms.backlogItems(a.id).none { it.kind != MailBacklogKind.KEEP && it.id !in doneIds && it.fromAddress?.lowercase() !in keptNow }
        if (all.isNotEmpty()) {
            writeTidy(a, batchId, MailBacklogRules.batchFields(startedAt, account, all.groupingBy { it.tidy }.eachCount()))
        }
        writeMail(a, entityId, MailBacklogRules.progressFields(all.size, startedAt, if (finished) nowMs else null, plan.putBack), EntityTypes.MAIL_BACKLOG)
    }

    /**
     * Inbox autopilot, slice 6a: what Meka asked on the "From your mail" cards. **Done** archives the email (Gmail: out
     * of the inbox; Outlook: to Archive), recorded like a tidy under the card's id so an Undo can find it (Outlook's move
     * gives it a new id); **Undo** after that puts it back in the inbox, and Autopilot never tidies it after. Each once.
     */
    internal fun mailNeedsAsks(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val hh = a.householdId
        val mine = store.transaction { store.mirror(hh, a.id) }.keys
        val all = reader.entities(hh, EntityTypes.MAIL_ITEM).filterKeys { it in mine }
            .mapNotNull { (id, f) -> MailNeedsRules.read(id, f) }
        val chases = all.filter { MailChaseRules.due(it, now()) }
        val asked = all.filter { MailNeedsRules.archiveDue(it) || MailNeedsRules.putBackDue(it) || MailNeedsRules.replyDue(it, now()) }
        if (asked.isEmpty() && chases.isEmpty()) return
        val byItem = ms.messages(a.id).associateBy { MailNeedsRules.itemId(a.id, it.id) }
        for (item in chases) {
            // Slice 6b-3: his chase of a Waiting for item, from this account, in the email's thread (the email stays
            // archived). Outlook gave the archived email a new id: that one. Sent once; a failure goes back to him.
            val target = ms.tidied(a.id, item.id).lastOrNull()?.movedId ?: byItem[item.id]?.id
            val why = replyMail(m, token, target, item.chaseText!!)?.let { if (it == MailNeedsRules.WHY_GONE) MailChaseRules.WHY_GONE else it }
            writeMail(a, item.id, if (why == null) MailChaseRules.sentFields(now()) else MailChaseRules.failedFields(now(), why), EntityTypes.MAIL_ITEM)
        }
        for (asking in asked) {
            var item = asking
            if (MailNeedsRules.replyDue(item, now())) {
                // Slice 6b-2a: his reply, from this account, before the email is archived. Sent once: a failure goes
                // back to him (the card returns with his words), never retried here.
                val why = replyMail(m, token, byItem[item.id]?.id, item.replyText!!)
                val fields = if (why == null) MailNeedsRules.replySentFields(now()) else MailNeedsRules.replyFailedFields(now(), why)
                writeMail(a, item.id, fields, EntityTypes.MAIL_ITEM)
                if (why != null) continue
                item = item.copy(replySentAtMs = now())
            }
            if (MailNeedsRules.archiveDue(item)) {
                val h = byItem[item.id]
                if (h == null || !h.inInbox) {
                    // Already out of the inbox (he archived it himself): nothing to move.
                    writeMail(a, item.id, MailNeedsRules.archivedFields(now()), EntityTypes.MAIL_ITEM)
                    continue
                }
                val moved = m.tidy(token, mapOf(h.id to MailTidy.ARCHIVE))[h.id] ?: continue
                ms.recordTidy(a.id, item.id, listOf(MailMoved(h.id, moved, MailTidy.ARCHIVE)))
                ms.put(hh, a.id, h.copy(inInbox = false))
                writeMail(a, item.id, MailNeedsRules.archivedFields(now()), EntityTypes.MAIL_ITEM)
            } else {
                val items = ms.tidied(a.id, item.id)
                if (items.isNotEmpty()) {
                    val back = m.untidy(token, items.associate { it.movedId to it.tidy })
                    ms.neverTidy(a.id, items.map { it.id } + items.map { it.movedId } + back.values)
                    // Gmail keeps the id: back in the inbox now, sorted again this check (Outlook's new id comes with its feed).
                    items.forEach { t -> byItem[item.id]?.takeIf { back[t.movedId] == t.id }?.let { h -> ms.put(hh, a.id, h.copy(inInbox = true)) } }
                }
                writeMail(a, item.id, MailNeedsRules.putBackFields(now()), EntityTypes.MAIL_ITEM)
            }
        }
    }

    /** Sends one reply; null when the provider took it, else why not in a few words (never the reply itself). */
    private fun replyMail(m: MailProvider, token: String, messageId: String?, text: String): String? {
        val name = if (m.id == os.meka.core.domain.MailProviders.OUTLOOK) "Outlook" else "Gmail"
        if (messageId == null) return MailNeedsRules.WHY_GONE
        return try {
            when (m.reply(token, messageId, text)) {
                MailReplyResult.SENT -> null
                MailReplyResult.GONE -> MailNeedsRules.WHY_GONE
                MailReplyResult.FAILED -> MailNeedsRules.whyRefused(name)
            }
        } catch (e: ReconnectRequired) {
            "sign in to $name again"
        } catch (e: Exception) {
            MailNeedsRules.whyUnreachable(name)
        }
    }

    /**
     * Inbox autopilot, slice 6a: each email sorted as Needs you that is still in the inbox and came in the last
     * [MailNeedsRules.WINDOW_MS] (at most [MailNeedsRules.MAX_PER_ACCOUNT], newest first) published as a `mail_item`:
     * the account's label, the sender's name, the subject, the provider's short preview, when, and how many emails of
     * its thread MEKA holds (slice 6c: and the thread's hashed key, so the apps link its emails) — never the address or the body. Ones published before that left get [MailItemFields.GONE_AT].
     */
    internal fun publishMailNeeds(a: AccountRow, ms: MailStore) {
        val nowMs = now()
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val needs = ms.inboxSorted(a.id)
            .filter { (h, s) -> s.sort == MailSort.NEEDS_YOU && h.inInbox && nowMs - h.receivedAtMs <= MailNeedsRules.WINDOW_MS }
            .map { it.first }
            .sortedByDescending { it.receivedAtMs }
            .take(MailNeedsRules.MAX_PER_ACCOUNT)
        val threads = if (needs.isEmpty()) emptyMap() else ms.messages(a.id).groupingBy { it.threadId ?: it.id }.eachCount()
        val previews = ms.previews(a.id, needs.map { it.id })
        val shown = HashSet<String>()
        for (h in needs) {
            val id = MailNeedsRules.itemId(a.id, h.id)
            shown += id
            writeMail(a, id, MailNeedsRules.serverFields(
                account, h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview, h.receivedAtMs, threads[h.threadId ?: h.id] ?: 1,
                threadKey = MailNeedsRules.threadKey(a.id, h.threadId ?: h.id),
            ), EntityTypes.MAIL_ITEM)
        }
        goneMailNeeds(a, shown)
    }

    /** Every card this account published that isn't in [shown] is marked gone (once). */
    private fun goneMailNeeds(a: AccountRow, shown: Set<String>) {
        val mine = store.transaction { store.mirror(a.householdId, a.id) }.keys
        reader.entities(a.householdId, EntityTypes.MAIL_ITEM)
            .filterKeys { it in mine && it !in shown }
            .mapNotNull { (id, f) -> MailNeedsRules.read(id, f) }
            .filter { it.goneAtMs == null }
            .forEach { writeMail(a, it.id, MailNeedsRules.goneFields(now()), EntityTypes.MAIL_ITEM) }
    }

    /**
     * Inbox autopilot, slice 3b: first any Undo an app asked for (every email of that tidy back in the inbox, its label
     * off, never tidied again), then — when [MailAutopilotRules.since] says it may — the sure newsletters, receipts,
     * notifications and promos that arrived since then are archived with their labels, at most
     * [MailAutopilotRules.MAX_PER_CHECK] a check, never a thread Meka wrote in, never one an Undo put back. Each tidy
     * is one `mail_tidy` entity the apps show with Undo; which emails it moved stays on the server (ids only).
     */
    internal fun autopilot(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val hh = a.householdId
        undoTidies(a, m, token, ms)
        val setting = MailAutopilotRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID))
        val paused = AutonomyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused
        val since = MailAutopilotRules.since(setting, paused, a.grantedAtMs, now()) ?: return
        val never = ms.neverTidied(a.id)
        val candidates = ms.inboxSorted(a.id).filter { (h, _) -> h.receivedAtMs >= since && h.id !in never }
        if (candidates.isEmpty()) return
        // Can't tell which threads he wrote in: nothing is tidied this check.
        val replied = m.repliedThreads(token, since - MAIL_WINDOW_MS)
        val keep = keepAddresses(hh)
        // Slice 5b: the senders he unsubscribed from (read from their synced entities; the id is the account + address).
        val senders = reader.entities(hh, EntityTypes.MAIL_SENDER)
        fun senderOf(h: MailHeader): MailSender? {
            val address = h.fromAddress ?: return null
            val id = MailUnsubscribeRules.senderId(a.id, address)
            return senders[id]?.let { MailUnsubscribeRules.read(id, it) }
        }
        val plan = candidates.mapNotNull { (h, s) ->
            val t = MailTidyRules.plan(MailTidyFacts(
                h.fromAddress, h.inInbox, s, keep,
                replied = h.threadId?.let { it in replied } == true,
                unsubscribed = MailUnsubscribeRules.trashes(senderOf(h), h.receivedAtMs),
                rule = h.fromAddress?.let { MailSenderRuleRules.read(senders[MailUnsubscribeRules.senderId(a.id, it)]) },
            ))
            if (t == MailTidy.KEEP) null else h to t
        }.take(MailAutopilotRules.MAX_PER_CHECK)
        if (plan.isEmpty()) return
        val at = now()
        val batchId = MailAutopilotRules.batchId(a.id, at)
        val moved = m.tidy(token, plan.associate { (h, t) -> h.id to t })
        val done = plan.filter { (h, _) -> h.id in moved }
        if (done.isEmpty()) return
        ms.recordTidy(a.id, batchId, done.map { (h, t) -> MailMoved(h.id, moved.getValue(h.id), t) })
        // Out of the inbox now, so the next check doesn't plan them again before the provider's feed says so.
        done.forEach { (h, _) -> ms.put(hh, a.id, h.copy(inInbox = false)) }
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        // Slice 3d: what the receipts cost and who from, read from the subject and the kept preview (never the body).
        val receiptHeaders = done.filter { it.second == MailTidy.RECEIPT }.map { it.first }
        val previews = if (receiptHeaders.isEmpty()) emptyMap() else ms.previews(a.id, receiptHeaders.map { it.id })
        val receipts = receiptHeaders.map { h -> MailReceiptRules.read(h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview) }
        writeTidy(a, batchId, MailAutopilotRules.batchFields(at, account, done.groupingBy { it.second }.eachCount(), receipts))
        // Slice 5b: each unsubscribed sender's row in Mail says how many later emails went to Trash.
        done.filter { it.second == MailTidy.TRASH }.mapNotNull { (h, _) -> senderOf(h) }.groupBy { it.id }.forEach { (id, ss) ->
            writeSender(a, id, MailUnsubscribeRules.trashedFields(ss.first().trashed, ss.size, at))
        }
    }

    /** Slice 3c: when each account's Junk was last looked at (this process; a restart looks again, which moves nothing twice). */
    private val junkReviewedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Inbox autopilot, slice 3c: once a day, while Autopilot may act ([MailAutopilotRules.since]), the Junk folder's last
     * [MailJunkRules.WINDOW_MS] is looked at (headers only): obvious scams go to Trash, an email from his partner, a
     * thread he wrote in or an address that already writes to him goes back to the inbox, flagged ([MailJunkRules.verdict]);
     * everything else stays. One `mail_tidy` batch with Undo (back to Junk); a rescued email is never tidied after, and
     * one an Undo returned is never reviewed again. Can't tell which threads he wrote in: nothing moves this check.
     */
    internal fun junkReview(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val hh = a.householdId
        val nowMs = now()
        if (!MailJunkRules.due(junkReviewedAt[a.id], nowMs)) return
        val setting = MailAutopilotRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID))
        val paused = AutonomyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused
        val since = MailAutopilotRules.since(setting, paused, a.grantedAtMs, nowMs) ?: return
        // Autopilot's start only gates the review: Junk is a folder he never sorts, so its last few days are looked at.
        val from = nowMs - MailJunkRules.WINDOW_MS
        val never = ms.neverTidied(a.id)
        val junk = (m.junk(token, from, MailJunkRules.MAX_PER_REVIEW) ?: return).filter { it.id !in never }
        if (junk.isEmpty()) { junkReviewedAt[a.id] = nowMs; return }
        val replied = m.repliedThreads(token, from - MAIL_WINDOW_MS)
        val keep = keepAddresses(hh)
        val known = ms.inboxSorted(a.id)
            .filter { (h, s) -> h.inInbox && (s.sort == MailSort.NEEDS_YOU || s.sort == MailSort.FYI) }
            .mapNotNull { it.first.fromAddress?.lowercase() }.toSet()
        val plan = junk.mapNotNull { h ->
            val t = MailJunkRules.tidyOf(MailJunkRules.verdict(MailJunkFacts(
                h.fromAddress, h.fromName, h.subject, keep,
                replied = h.threadId?.let { it in replied } == true,
                knownSender = h.fromAddress?.lowercase()?.let { it in known } == true,
            )))
            if (t == MailTidy.KEEP) null else h to t
        }.take(MailJunkRules.MAX_PER_REVIEW)
        junkReviewedAt[a.id] = nowMs
        if (plan.isEmpty()) return
        val batchId = MailJunkRules.batchId(a.id, nowMs)
        val moved = m.tidy(token, plan.associate { (h, t) -> h.id to t })
        val done = plan.filter { (h, _) -> h.id in moved }
        if (done.isEmpty()) return
        ms.recordTidy(a.id, batchId, done.map { (h, t) -> MailMoved(h.id, moved.getValue(h.id), t) })
        // A rescued email is Meka's to deal with: Autopilot never archives it.
        val rescued = done.filter { it.second == MailTidy.RESCUE }
        if (rescued.isNotEmpty()) ms.neverTidy(a.id, rescued.map { it.first.id } + rescued.map { moved.getValue(it.first.id) })
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        writeTidy(a, batchId, MailJunkRules.batchFields(nowMs, account, done.groupingBy { it.second }.eachCount()))
    }

    /** Puts back every tidy of this account an app asked to undo (within its 30 days), once. */
    private fun undoTidies(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val due = reader.entities(a.householdId, EntityTypes.MAIL_TIDY)
            .mapNotNull { (id, f) -> MailAutopilotRules.readBatch(id, f) }
            // Slice 8b: the backlog clean-up's tidy is put back a few hundred a check by [applyBacklog].
            .filter { MailAutopilotRules.undoDue(it, now()) && !it.backlog }
        for (b in due) {
            val items = ms.tidied(a.id, b.id)
            if (items.isEmpty()) continue // another account's tidy
            val back = m.untidy(token, items.associate { it.movedId to it.tidy })
            ms.neverTidy(a.id, items.map { it.id } + items.map { it.movedId } + back.values)
            writeTidy(a, b.id, mapOf(
                MailTidyBatchFields.UNDONE_AT to FieldValue.Int64(now()),
                MailTidyBatchFields.UNDO_NOTE to FieldValue.Text(MailAutopilotRules.undoneNote(back.size, items.size, if (b.junk) "in Junk" else "in the inbox")),
            ))
        }
    }

    /**
     * Inbox autopilot, slice 5a: the account's busiest newsletter and promo senders published as `mail_sender` entities
     * (name, domain, how they can be unsubscribed, their last email and count; never an email id or the header), and
     * any Unsubscribe an app asked for done now. Outlook's list headers are read from each sender's newest email, a few
     * per check, before Autopilot moves it.
     */
    internal fun mailSenders(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val outlook = m.id == MailProviders.OUTLOOK
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val published = reader.entities(a.householdId, EntityTypes.MAIL_SENDER)
        var checks = 0
        val records = ms.senders(a.id, SENDERS_READ).map { r ->
            if (!outlook || r.headersChecked || r.lastMessageId == null || checks >= SENDER_HEADER_CHECKS) return@map r
            checks++
            val got = runCatching { m.listHeaders(token, r.lastMessageId) }.getOrNull() ?: return@map r
            ms.setSenderHeaders(a.id, r.address, got.first, got.second)
            r.copy(listUnsubscribe = got.first, oneClick = got.second, headersChecked = true)
        }
        // Slice 5c: each sender's emails in the last 30 days and how many were opened (the weekly "not opened" offer).
        val nowMs = now()
        val opens = runCatching { ms.openStats(a.id, nowMs - MailUnopenedRules.WINDOW_MS) }.getOrDefault(emptyMap())
        records.forEachIndexed { i, r ->
            val id = MailUnsubscribeRules.senderId(a.id, r.address)
            val plan = MailUnsubscribeRules.plan(r.listUnsubscribe, r.oneClick, check = outlook && !r.headersChecked)
            val existing = published[id]
            val s = existing?.let { MailUnsubscribeRules.read(id, it) }
            if (i < SENDERS_SHOWN && (plan.method != UnsubscribeMethod.NONE || existing != null)) {
                val o = opens[r.address.lowercase()]
                val unopened = MailUnopenedRules.serverFields(s, o?.total ?: 0, o?.opened ?: 0, nowMs).orEmpty()
                writeSender(a, id, MailUnsubscribeRules.senderFields(
                    account, MailUnsubscribeRules.displayName(r.name, r.address), MailSortRules.domain(r.address), plan.method, r.lastAtMs, r.seen,
                ) + unopened)
            }
            s ?: return@forEachIndexed
            if (MailUnsubscribeRules.due(s)) unsubscribe(a, m, token, id, plan)
        }
    }

    /**
     * One Unsubscribe Meka asked for: one-click first, else the list's unsubscribe email from this account, else the
     * sender's page handed back to open. The outcome goes on the sender's entity (Mail and Activity show it).
     */
    private fun unsubscribe(a: AccountRow, m: MailProvider, token: String, id: String, plan: UnsubscribePlan) {
        val url = plan.url
        val mailto = plan.mailto
        val oneClick = plan.method == UnsubscribeMethod.ONE_CLICK && url != null && runCatching { unsubscriber.post(url) }.getOrDefault(false)
        var unreachable = false
        val sent = !oneClick && mailto != null && try {
            m.sendUnsubscribe(token, mailto)
        } catch (e: Exception) {
            unreachable = true
            false
        }
        val state = when {
            oneClick -> UnsubscribeState.DONE
            sent -> UnsubscribeState.SENT
            plan.fallbackUrl != null -> UnsubscribeState.OPEN_LINK
            plan.method == UnsubscribeMethod.CHECK || unreachable -> UnsubscribeState.RETRY
            else -> UnsubscribeState.FAILED
        }
        writeSender(a, id, MailUnsubscribeRules.outcomeFields(state, now(), if (state == UnsubscribeState.OPEN_LINK) plan.fallbackUrl else null))
    }

    private fun writeSender(a: AccountRow, id: String, fields: Map<String, FieldValue>) {
        val wrote = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                val prev = store.mirror(a.householdId, a.id)[id]
                write(a, id, 0, 0, false, prev, fields, EntityTypes.MAIL_SENDER)
            }
        }
        if (wrote) runCatching { onChanged(a.householdId) }
    }

    private fun writeMail(a: AccountRow, id: String, fields: Map<String, FieldValue>, type: String) {
        val wrote = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                val prev = store.mirror(a.householdId, a.id)[id]
                write(a, id, 0, 0, false, prev, fields, type)
            }
        }
        if (wrote) runCatching { onChanged(a.householdId) }
    }

    private fun writeTidy(a: AccountRow, batchId: String, fields: Map<String, FieldValue>) {
        val wrote = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                val prev = store.mirror(a.householdId, a.id)[batchId]
                write(a, batchId, 0, 0, false, prev, fields, EntityTypes.MAIL_TIDY)
            }
        }
        if (wrote) runCatching { onChanged(a.householdId) }
    }

    /**
     * Slice 2a: every header not sorted yet gets its bucket from [MailSortRules] (headers only, nothing sent anywhere,
     * nothing in the mailbox changes). Unsure ones (a person seems to have written) then get the AI's look ([askAi]).
     */
    private fun sortHeaders(a: AccountRow, ms: MailStore) {
        for (round in 0 until SORT_ROUNDS) {
            val batch = ms.unsorted(a.id, SORT_BATCH)
            if (batch.isEmpty()) break
            val sorts = batch.associate { h ->
                h.id to MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId))
            }
            ms.setSorts(a.id, sorts)
            // Slice 5a: who sends the newsletters and promos (oldest first, so the newest one's headers win).
            batch.asReversed().filter { h -> sorts[h.id]?.let { it.sure && (it.sort == MailSort.NEWSLETTER || it.sort == MailSort.PROMO) } == true }
                .forEach { h -> ms.noteSender(a.householdId, a.id, h) }
            if (batch.size < SORT_BATCH) break
        }
        askAi(a, ms)
    }

    /**
     * Slice 2b: one AI call per check for up to [AI_BATCH] unsure ones, newest first (words only: sender and subject).
     * The answer can only keep an email in Needs you or make it FYI ([MailSortRules.afterAi]); an email the answer
     * leaves out stays Needs you and isn't asked about again. AI off, over budget or failing: they wait, unchanged.
     */
    private fun askAi(a: AccountRow, ms: MailStore) {
        val ai = mailAi ?: return
        val batch = ms.unsure(a.id, AI_BATCH)
        if (batch.isEmpty()) return
        val lanes = runCatching { ai.lanes(batch) }.getOrNull() ?: return
        ms.setSorts(a.id, batch.associate { h -> h.id to MailSortRules.afterAi(lanes[h.id]) })
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
     * The forecast moves through the day: refresh every [WEATHER_PERIOD_MS] (sooner after a failure, at the next poll).
     * Nobody is woken for it; the devices pick it up at their next sync, like headlines.
     */
    private fun syncWeather(a: AccountRow, source: WeatherProvider) {
        // The place setting: a changed name is followed at once, not at the next half hour.
        val wanted = wantedPlace(a.householdId)
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        fun askedIn(id: String) = mirror[id]?.fieldOps?.get(WeatherFields.ASKED)?.second?.takeIf { it.startsWith("s:") }?.removePrefix("s:")
        val asked = askedIn(WeatherStore.ENTITY_ID)
        // Work (Places item 2): forecast when the provider knows work; a new work place, or none yet, is followed at once.
        val wantedWork = wantedWorkPlace(a.householdId)
        val workDue = source.work != null && (mirror[WeatherStore.WORK_ENTITY_ID] == null || askedIn(WeatherStore.WORK_ENTITY_ID) != wantedWork)
        val last = a.lastSyncAtMs
        if (wanted == asked && !workDue && a.status == "ok" && last != null && now() - last < WEATHER_PERIOD_MS) return
        try {
            // A name that can't be found forecasts home and says so (the apps light the line); it's not a fault.
            val at = wanted?.let { source.locate(it) }
            applyWeather(a, source.forecast(at ?: source.home), asked = wanted, found = wanted == null || at != null)
            // Work's forecast never holds up home's: a failure is tried again at the next poll.
            source.work?.let { default ->
                runCatching {
                    val workAt = wantedWork?.let { source.locate(it) }
                    applyWeather(a, source.forecast(workAt ?: default), asked = wantedWork, found = wantedWork == null || workAt != null,
                        entityId = WeatherStore.WORK_ENTITY_ID)
                }
            }
            store.transaction { store.markSynced(a.id, now()) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /**
     * The route's line status (Places item 4): every [LINES_PERIOD_MS] from [LINES_FROM_HOUR] until [LINES_TO_HOUR]
     * London time (overnight nobody commutes; the first poll of the morning catches up). A changed status wakes the
     * devices, so Today's line and Ask follow within seconds; the checked time alone wakes nobody.
     */
    private fun syncLines(a: AccountRow, source: LineStatusProvider) {
        val hour = java.time.Instant.ofEpochMilli(now()).atZone(zone).hour
        if (hour < LINES_FROM_HOUR || hour >= LINES_TO_HOUR) return
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < LINES_PERIOD_MS) return
        try {
            val changed = applyLines(a, source.statuses())
            store.transaction { store.markSynced(a.id, now()) }
            // In the evening, tomorrow morning's plans too (the commute counts everywhere, slice 7); a failure there
            // leaves the last plans and the feed's status alone.
            val planned = hour >= PLANNED_FROM_HOUR && runCatching { syncPlanned(a, source) }.getOrDefault(false)
            if (changed || planned) runCatching { onChanged(a.householdId) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /**
     * Writes the status into the household's one `context_mode/line_status` entity: the lines when they changed, the
     * checked time rounded to [RouteRules.CHECKED_STEP_MS] (at most two ops an hour while nothing changes). Returns
     * whether the lines changed.
     */
    internal fun applyLines(a: AccountRow, states: List<LineState>): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val prev = store.mirror(a.householdId, a.id)[LineStatusStore.ENTITY_ID]
            val encoded = LineStatusCodec.encode(states)
            val linesChanged = prev?.fieldOps?.get(LineStatusFields.LINES)?.second != valueKey(FieldValue.Text(encoded))
            val desired = linkedMapOf(
                LineStatusFields.LINES to FieldValue.Text(encoded),
                LineStatusFields.CHECKED to FieldValue.Int64(RouteRules.checkedStep(now())),
            )
            write(a, LineStatusStore.ENTITY_ID, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
            linesChanged
        }
    }

    /** When each line-status feed last read tomorrow's plans (in memory: a restart just reads them again). */
    private val plannedAt = HashMap<String, Long>()

    /**
     * Tomorrow 06:00–10:00 London ([PLANNED_FROM_MIN]–[PLANNED_TO_MIN]): what TfL has planned for the route's lines
     * then, at most every [PLANNED_PERIOD_MS], into the same entity's [LineStatusFields.PLANNED] with its day and the
     * rounded read time. Returns whether the plans changed (so the devices are woken for the wake alarm's offer).
     */
    private fun syncPlanned(a: AccountRow, source: LineStatusProvider): Boolean {
        val last = synchronized(plannedAt) { plannedAt[a.id] }
        if (last != null && now() - last < PLANNED_PERIOD_MS) return false
        synchronized(plannedAt) { plannedAt[a.id] = now() }
        val tomorrow = java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().plusDays(1)
        val from = tomorrow.atStartOfDay(zone).plusMinutes(PLANNED_FROM_MIN.toLong()).toInstant().toEpochMilli()
        val to = tomorrow.atStartOfDay(zone).plusMinutes(PLANNED_TO_MIN.toLong()).toInstant().toEpochMilli()
        val states = source.planned(from, to)
        if (states.isEmpty()) return false
        return applyPlanned(a, tomorrow.toEpochDay(), states)
    }

    internal fun applyPlanned(a: AccountRow, day: Long, states: List<LineState>): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val prev = store.mirror(a.householdId, a.id)[LineStatusStore.ENTITY_ID]
            val encoded = LineStatusCodec.encode(states)
            val changed = prev?.fieldOps?.get(LineStatusFields.PLANNED)?.second != valueKey(FieldValue.Text(encoded)) ||
                prev?.fieldOps?.get(LineStatusFields.PLANNED_DAY)?.second != valueKey(FieldValue.Int64(day))
            val desired = linkedMapOf(
                LineStatusFields.PLANNED to FieldValue.Text(encoded),
                LineStatusFields.PLANNED_DAY to FieldValue.Int64(day),
                LineStatusFields.PLANNED_CHECKED to FieldValue.Int64(RouteRules.checkedStep(now())),
            )
            write(a, LineStatusStore.ENTITY_ID, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
            changed
        }
    }

    /** Lookups made today (London day → count), so a bug can never run through Google's daily cap. */
    private var travelDay: Pair<Long, Int> = 0L to 0

    /**
     * Drive times to the kids' football (weekend football, slice 2b): every [TRAVEL_PERIOD_MS], the club fixtures
     * [TravelRules.due] for a lookup (first sight, the evening before, an hour before leaving) are asked about, at most
     * [TRAVEL_PER_PASS] a pass and [TravelRules.MAX_PER_DAY] a day, from home's coordinates as the weather knows them
     * to the fixture's place. Each answer goes into the fixture's `travel_time` entity and wakes the devices. Without
     * the key the feed is [TravelRules.STATUS_OFF] and nothing is read or sent. One failed lookup never stops the others.
     */
    private fun syncTravel(a: AccountRow, source: TravelProvider) {
        if (!source.configured()) {
            if (a.status != TravelRules.STATUS_OFF) store.transaction { store.markError(a.id, TravelRules.STATUS_OFF, "no key") }
            return
        }
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < TRAVEL_PERIOD_MS) return
        try {
            val wrote = travelPass(a, source)
            store.transaction { store.markSynced(a.id, now()) }
            if (wrote) runCatching { onChanged(a.householdId) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /** One pass for [a]'s household; returns whether a drive was written. Throws only when every lookup failed. */
    internal fun travelPass(a: AccountRow, source: TravelProvider): Boolean {
        val hh = a.householdId
        val events = reader.entities(hh, EntityTypes.EVENT).mapNotNull { (id, f) ->
            CalendarEvent.from(EntitySnapshot(EntityRef(EntityTypes.EVENT, id), hh, f, Hlc.ZERO))
        }
        val hidden = reader.entities(hh, EntityTypes.EVENT_MARK).filter { (_, f) -> (f[EventMarkFields.HIDDEN] as? FieldValue.Bool)?.value == true }.keys
        val known = reader.entities(hh, EntityTypes.TRAVEL_TIME).mapNotNull { (id, f) -> TravelRules.read(id, f) }.associateBy { it.eventId }
        val due = TravelRules.due(events, hidden, known, now(), calendar)
        if (due.isEmpty()) return false
        val home = weather.values.firstOrNull()?.let { knownPlaces(hh, it).firstOrNull() } ?: error("no home")
        var wrote = false
        var failed = 0
        var tried = 0
        for (l in due.take(TRAVEL_PER_PASS)) {
            if (!takeTravelLookup()) break
            tried++
            val drive = runCatching { source.driveMinutes(home.latitude, home.longitude, l.place, maxOf(l.departAtMs, now() + 60_000L)) }
                .getOrElse { failed++; null } ?: continue
            if (applyTravel(a, l, drive)) wrote = true
        }
        if (tried > 0 && failed == tried) error("no drive time could be read")
        return wrote
    }

    private fun takeTravelLookup(): Boolean {
        synchronized(this) {
            val day = calendar.epochDayOf(now())
            val (d, n) = travelDay
            val count = if (d == day) n else 0
            if (count >= TravelRules.MAX_PER_DAY) return false
            travelDay = day to count + 1
            return true
        }
    }

    /** When each car feed last asked the DVLA (account id → ms), and lookups made today (London day → count). */
    private val carAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var carDay: Pair<Long, Int> = 0L to 0

    /**
     * The car's MOT and tax (car on autopilot, slice 2): every [CAR_PERIOD_MS] the stored registration
     * (`context_mode/car`) is compared with the last answer (`context_mode/car_check`); when the registration changed or
     * the answer is a day old ([CarCheckRules.due]) the DVLA is asked (only the registration is sent) and the answer
     * written into `car_check`, waking the devices when more than the checked time changed. A failed look is tried
     * again after [CarCheckRules.RETRY_MS]; at most [CAR_MAX_PER_DAY] looks a day. Without the keys the feed is
     * [CarCheckRules.STATUS_OFF] and nothing is read or sent.
     */
    private fun syncCar(a: AccountRow, source: CarLookupProvider) {
        if (!runCatching { source.configured() }.getOrDefault(false)) {
            if (a.status != CarCheckRules.STATUS_OFF) store.transaction { store.markError(a.id, CarCheckRules.STATUS_OFF, "no keys") }
            return
        }
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < CAR_PERIOD_MS) return
        val asked = carAsked[a.id]
        if (a.status == "error" && asked != null && now() - asked < CarCheckRules.RETRY_MS) return
        try {
            val wrote = carPass(a, source)
            store.transaction { store.markSynced(a.id, now()) }
            if (wrote) runCatching { onChanged(a.householdId) }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", e::class.simpleName ?: "error") }
            throw e
        }
    }

    /** One look for [a]'s household; returns whether the answer changed (beyond the checked time). */
    internal fun carPass(a: AccountRow, source: CarLookupProvider): Boolean {
        val hh = a.householdId
        val reg = (reader.entity(hh, EntityTypes.CONTEXT_MODE, CarRules.ENTITY_ID)?.get(CarFields.REGISTRATION) as? FieldValue.Text)?.value
            ?.takeIf { CarRules.registrationError(it) == null }?.let { CarRules.normalise(it) }
        val current = CarCheckRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, CarCheckRules.ENTITY_ID))
        if (!CarCheckRules.due(reg, current, now())) return false
        if (!takeCarLookup()) return false
        carAsked[a.id] = now()
        val answer = source.lookup(reg!!, now())
        return applyCar(a, answer)
    }

    private fun takeCarLookup(): Boolean {
        synchronized(this) {
            val day = calendar.epochDayOf(now())
            val (d, n) = carDay
            val count = if (d == day) n else 0
            if (count >= CAR_MAX_PER_DAY) return false
            carDay = day to count + 1
            return true
        }
    }

    /** Writes the DVLA's answer into `context_mode/car_check`; returns whether more than the checked time changed. */
    internal fun applyCar(a: AccountRow, answer: CarCheck): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val prev = store.mirror(a.householdId, a.id)[CarCheckRules.ENTITY_ID]
            val desired = CarCheckRules.fields(answer)
            val changed = desired.any { (field, value) ->
                field != CarCheckFields.CHECKED && prev?.fieldOps?.get(field)?.second != valueKey(value) &&
                    !(value == FieldValue.Null && prev?.fieldOps?.get(field) == null)
            }
            write(a, CarCheckRules.ENTITY_ID, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
            changed
        }
    }

    /** Writes one fixture's drive into its `travel_time` entity (the checked time always moves, so it isn't asked again). */
    internal fun applyTravel(a: AccountRow, l: TravelRules.Lookup, driveMin: Int): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val id = TravelRules.entityId(l.eventId)
            val prev = store.mirror(a.householdId, a.id)[id]
            write(a, id, 0, 0, false, prev, TravelRules.fields(l, driveMin, now()), EntityTypes.TRAVEL_TIME)
        }
    }

    /**
     * "Where I am now" (Places item 3): the forecast at a device's rounded point, judged against the places this server
     * forecasts for the household (home, or the town Meka set; work). Null when no weather provider is configured.
     */
    fun hereWeather(): HereWeather? {
        val source = weather.values.firstOrNull() ?: return null
        return HereWeather(source) { householdId -> knownPlaces(householdId, source) }
    }

    /** Home's and work's coordinates as they are forecast (a set name only when it was found; the geocoder caches). */
    internal fun knownPlaces(householdId: String, source: WeatherProvider): List<WeatherLocation> {
        val home = wantedPlace(householdId)?.let { runCatching { source.locate(it) }.getOrNull() } ?: source.home
        val work = source.work?.let { default -> wantedWorkPlace(householdId)?.let { runCatching { source.locate(it) }.getOrNull() } ?: default }
        return listOfNotNull(home, work)
    }

    /** The household's place setting ([WeatherPlaceStore]), normalised; null for home. */
    internal fun wantedPlace(householdId: String): String? =
        runCatching { reader.entity(householdId, EntityTypes.CONTEXT_MODE, WeatherPlaceStore.ENTITY_ID) }.getOrNull()
            ?.get(WeatherPlaceFields.NAME)?.let { (it as? FieldValue.Text)?.value }
            ?.let(WeatherPlaceRules::normalize)?.takeUnless(WeatherPlaceRules::isHome)

    /** The household's work place setting ([WorkPlaceStore]), normalised; null for the default (Canary Wharf). */
    internal fun wantedWorkPlace(householdId: String): String? =
        runCatching { reader.entity(householdId, EntityTypes.CONTEXT_MODE, WorkPlaceStore.ENTITY_ID) }.getOrNull()
            ?.get(WorkPlaceFields.NAME)?.let { (it as? FieldValue.Text)?.value }
            ?.let(WeatherPlaceRules::normalize)?.takeUnless(PlacesRules::isDefaultWork)

    /**
     * Writes the forecast into the household's one `context_mode/weather` entity (or [entityId]: work's, Places item 2), field by field and only what changed
     * (readings are rounded, so a small wobble writes nothing). Returns whether anything was written.
     */
    internal fun applyWeather(
        a: AccountRow,
        f: WeatherForecast,
        asked: String? = null,
        found: Boolean = true,
        entityId: String = WeatherStore.ENTITY_ID,
    ): Boolean = store.transaction {
        ops.transaction {
            store.lockAccount(a.id)
            val desired = linkedMapOf(
                WeatherFields.HOURS to FieldValue.Text(WeatherCodec.encodeHours(f.hours)),
                WeatherFields.DAYS to FieldValue.Text(WeatherCodec.encodeDays(f.days)),
                WeatherFields.PLACE to FieldValue.Text(WeatherCodec.place(f.place)),
                // The place setting (additive): what was asked for and whether it was found; both null for home, so
                // nothing extra is written until a place is set (a never-written field already reads as null).
                WeatherFields.ASKED to (asked?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                WeatherFields.FOUND to (if (asked == null) FieldValue.Null else FieldValue.Bool(found)),
            )
            val prev = store.mirror(a.householdId, a.id)[entityId]
            write(a, entityId, 0, 0, false, prev, desired, EntityTypes.CONTEXT_MODE)
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
                            // The story's language when it isn't English (Fold review 2026-10-09 07:26). Additive.
                            HeadlineFields.LANG to (h.lang?.let { FieldValue.Text(it.take(8)) } ?: FieldValue.Null),
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
        /** Inbox autopilot, slice 1c: the mail MEKA keeps headers for (Meka: the last 60 days). */
        const val MAIL_WINDOW_MS = 60 * 24 * 3_600_000L
        /** Slice 2a: headers sorted per round (rules only, cheap; a big first listing takes a few rounds of one check). */
        const val SORT_BATCH = 500
        const val SORT_ROUNDS = 20
        /** Slice 5a: the senders published per account (the busiest), the ones read for an Unsubscribe, Outlook header reads a check. */
        const val SENDERS_SHOWN = 30
        const val SENDERS_READ = 200
        const val SENDER_HEADER_CHECKS = 10
        /** Slice 2b: the most unsure emails one AI call looks at (one call per account per check). */
        const val AI_BATCH = 25
        /** Gmail headers read per 5-minute check (one call each; Gmail allows far more): a big first listing fills in over a few checks. */
        const val HEADERS_PER_CHECK = 300
        /** Slice 8a: backlog headers read per check (Gmail), and the next page is listed only while fewer than this wait. */
        const val BACKLOG_HEADERS_PER_CHECK = 300
        const val BACKLOG_LIST_BELOW = 500
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
        const val WEATHER_PERIOD_MS = 30 * 60_000L
        /** The line status: each calendar poll (every 5 minutes) between 05:00 and 23:00 London time. */
        const val LINES_PERIOD_MS = 4 * 60_000L
        const val LINES_FROM_HOUR = 5
        const val LINES_TO_HOUR = 23
        /** From 17:00 London the line feed also reads tomorrow morning's plans, at most every half hour. */
        const val PLANNED_FROM_HOUR = 17
        const val PLANNED_PERIOD_MS = 30 * 60_000L
        const val PLANNED_FROM_MIN = 6 * 60
        const val PLANNED_TO_MIN = 10 * 60
        /** The car: a look at the registration every 10 minutes (the DVLA is asked only when [CarCheckRules.due]). */
        const val CAR_PERIOD_MS = 10 * 60_000L
        const val CAR_MAX_PER_DAY = 20
        /** Drive times: a look every 10 minutes (only due fixtures are asked about), at most 3 lookups a pass. */
        const val TRAVEL_PERIOD_MS = 10 * 60_000L
        const val TRAVEL_PER_PASS = 3

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
