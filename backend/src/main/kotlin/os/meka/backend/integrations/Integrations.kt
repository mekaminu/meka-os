package os.meka.backend.integrations

import os.meka.backend.OwnKeyReminders
import os.meka.backend.Secrets
import os.meka.core.domain.BankHoliday
import os.meka.core.domain.GuestMail
import os.meka.core.domain.MailSortFacts
import os.meka.core.domain.MailSortRules
import os.meka.core.domain.MailSortReason
import os.meka.core.domain.MailSorted
import os.meka.core.domain.MailReceipt
import os.meka.core.domain.MailReceiptRules
import os.meka.core.domain.MailReturnRules
import os.meka.core.domain.MailBillRules
import os.meka.core.domain.MailRenewalRules
import os.meka.core.domain.MailPromiseRules
import os.meka.core.domain.SchoolMailRules
import os.meka.core.domain.MailFollowUpRules
import os.meka.core.domain.MailSentFacts
import os.meka.core.domain.MailFindFields
import os.meka.core.domain.ParcelRules
import os.meka.core.domain.MailWarrantyRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MailBacklogFacts
import os.meka.core.domain.MailBacklog
import os.meka.core.domain.MailBacklogFields
import os.meka.core.domain.MailBacklogKind
import os.meka.core.domain.MailBacklogRules
import os.meka.core.domain.MailTidyFacts
import os.meka.core.domain.MailSenderRule
import os.meka.core.domain.MailSenderRuleRules
import os.meka.core.domain.MailTidyRules
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailCheckRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.MailTidiedEmail
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailJunkClearRules
import os.meka.core.domain.MailUnreadRules
import os.meka.core.domain.MailMarkReadRules
import os.meka.core.domain.MailJunkFacts
import os.meka.core.domain.MailJunkRules
import os.meka.core.domain.MailTidyBatchFields
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailSender
import os.meka.core.domain.MailUnsubscribeRules
import os.meka.core.domain.MailUnopenedRules
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailAutoDraftRules
import os.meka.core.domain.MailDraftRules
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.MailItem
import os.meka.core.domain.UnsubscribeMethod
import os.meka.core.domain.UnsubscribePlan
import os.meka.core.domain.UnsubscribeState
import os.meka.core.domain.AboutYou
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
import os.meka.core.sync.DELETED_FIELD
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
    /** Drafted replies come to him: a draft for each email that needs a reply (AI off, tests: null drafts nothing). */
    private val mailDrafter: MailDrafter? = null,
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
                wouldTidy = if (sorted != null) tidyCounts(householdId, it.id, keepAddresses(householdId)) else null,
                // Inbox fixes, slice 6: why a mail check failed, for the card (only a known word; an old exception name isn't sent).
                why = if (it.provider in mail && it.status == "error") MailCheckRules.read(it.lastError) else null)
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
            .getOrDefault(emptySet()) + knownPeople(householdId)

    /** Inbox fixes, slice 4: each mail account's people he has written to ([readCorrespondents]); memory only, never logged. */
    private val correspondents = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val correspondentsReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Inbox fixes, slice 4: everyone the household's mail accounts have written to (empty until their sent mail is read). */
    internal fun knownPeople(householdId: String): Set<String> =
        runCatching { store.accounts(householdId).flatMap { correspondents[it.id].orEmpty() }.toSet() }.getOrDefault(emptySet())

    /**
     * Inbox fixes, slice 4 (Meka, 2026-10-15: "the sort calls almost everything needs you"; never-touch for people he's
     * written to): once a day, the To and Cc of the account's newest [CORRESPONDENTS_LIMIT] sent emails of the last
     * [CORRESPONDENTS_WINDOW_MS] ([MailProvider.sent]: headers only, never a body), kept in memory (a restart reads them
     * again). Their mail is never tidied, rescued from Junk, and sorted Needs you without the AI's look
     * ([MailSortRules.known]). A failed read is tried again in an hour.
     */
    internal fun readCorrespondents(a: AccountRow, m: MailProvider, token: String) {
        val nowMs = now()
        correspondentsReadAt[a.id]?.let { if (nowMs - it < CORRESPONDENTS_EVERY_MS) return }
        correspondentsReadAt[a.id] = nowMs - CORRESPONDENTS_EVERY_MS + CORRESPONDENTS_RETRY_MS
        val sent = m.sent(token, nowMs - CORRESPONDENTS_WINDOW_MS, CORRESPONDENTS_LIMIT) ?: return
        val own = store.accounts(a.householdId).map { it.email.lowercase() }.toSet() + a.email.lowercase()
        correspondents[a.id] = MailSortRules.correspondents(sent.flatMap { it.addresses }, own)
        correspondentsReadAt[a.id] = nowMs
    }

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
     *
     * [deleteData] (Inbox autopilot, completeness: Delete mail data per account): everything the server published from
     * this account's mail — its "From your mail" cards, tidies, newsletter senders (with his rules for them), the
     * backlog clean-up plan and parcels — is deleted from both apps as well. What he keeps on his own lists (Renewals,
     * Waiting for, tasks) stays; the mailbox is untouched.
     */
    fun disconnectMail(householdId: String, provider: String, email: String, deleteData: Boolean = false): Boolean {
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
        if (done && deleteData) runCatching { deleteMailData(a) }
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
        // Inbox fixes, slice 6: what this check did, for the report (the card reads the stored reason).
        val check = mailChecks.computeIfAbsent(a.id) { MailCheckState() }.also { it.lastCheckAtMs = now(); it.stages.clear(); it.autopilot = null }
        var signingIn = true
        try {
            val refresh = cipher.decrypt(a.refreshTokenEnc, ctx).decodeToString()
            val tokens = m.refresh(client, refresh)
            if (tokens.refreshToken != null && tokens.refreshToken != refresh) {
                store.transaction { store.updateRefreshToken(a.id, cipher.encrypt(tokens.refreshToken.toByteArray(), ctx)) }
            }
            signingIn = false
            val box = m.inbox(tokens.accessToken)
            check.inboxTotal = box.messages; check.inboxUnread = box.unread
            mailStore?.let { syncHeaders(a, m, tokens.accessToken, it) }
            store.transaction { store.markSynced(a.id, now()) }
            check.lastOkAtMs = now()
        } catch (e: ReconnectRequired) {
            store.transaction { store.markError(a.id, "needs_reconnect", e.message ?: "reconnect") }
        } catch (e: Exception) {
            store.transaction { store.markError(a.id, "error", mailWhy(e, signingIn)) }
            throw e
        }
    }

    /** Inbox fixes, slice 6: each mail account's last check, in memory (a restart starts it again; never an address). */
    internal class MailCheckState {
        @Volatile var lastCheckAtMs: Long? = null
        @Volatile var lastOkAtMs: Long? = null
        @Volatile var inboxTotal: Long? = null
        @Volatile var inboxUnread: Long? = null
        @Volatile var autopilot: String? = null
        val stages = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private val mailChecks = java.util.concurrent.ConcurrentHashMap<String, MailCheckState>()

    /** A stage of a mail check that never fails the check; a failure is kept for the report as its [MailCheckRules.WHY]. */
    private inline fun stage(a: AccountRow, name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { mailChecks[a.id]?.stages?.put(name, mailWhy(e, false)) }
    }

    /**
     * Inbox fixes, slice 6: the household's mail accounts as the publish job's "Mail autopilot" notices sees them —
     * label, state and reason, last check, inbox counts, Autopilot's mode and what it did, the sort and the dry run,
     * and any stage that failed. Never an address.
     */
    fun mailReport(householdId: String): List<MailCheckRules.Report> {
        val rows = store.accounts(householdId).filter { it.provider in mail && it.status != DISCONNECTED }
        if (rows.isEmpty()) return emptyList()
        val setting = runCatching { MailAutopilotRules.read(reader.entity(householdId, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID)) }.getOrNull()
        val paused = runCatching { AutonomyRules.read(reader.entity(householdId, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused }.getOrNull()
        val listed = runCatching { accounts(householdId) }.getOrNull().orEmpty()
        return rows.map { a ->
            val c = mailChecks[a.id]
            val wire = listed.firstOrNull { it.provider == a.provider && it.email == a.email }
            MailCheckRules.Report(
                label = MailLabel.of(a.label)?.label ?: "Unlabelled", provider = a.provider, status = a.status, why = a.lastError,
                lastCheckAtMs = c?.lastCheckAtMs, lastOkAtMs = a.lastSyncAtMs, inboxTotal = c?.inboxTotal, inboxUnread = c?.inboxUnread,
                dryRunEndsAtMs = a.grantedAtMs?.plus(MailAutopilotRules.DRY_RUN_MS), autopilotOn = setting?.on, paused = paused,
                autopilot = c?.autopilot, sorted = wire?.sorted, wouldTidy = wire?.wouldTidy, stageFailures = c?.stages?.toMap().orEmpty(),
            )
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
        // Inbox fixes, slice 1: newest first (so new mail is sorted and tidied while an old listing is still being read),
        // and a refusal part-way (Gmail's 403/429 rate limits, a 5xx) only ends this check's reading: the rest of the
        // check (sorting, Autopilot, the cards) still runs and the account isn't shown as unreachable.
        for (id in ms.pending(a.id, HEADERS_PER_CHECK)) {
            val h = try {
                m.header(token, id)
            } catch (e: ReconnectRequired) {
                throw e
            } catch (e: Exception) {
                mailChecks[a.id]?.stages?.put("headers", mailWhy(e, false))
                break
            }
            if (h == null) ms.remove(a.id, id) else ms.put(a.householdId, a.id, h)
        }
        ms.prune(a.id, since)
        // Slice 6a: Done and Undo asked on the "From your mail" cards, before sorting (an email put back is sorted again now).
        stage(a, "mailNeedsAsks") { mailNeedsAsks(a, m, token, ms) }
        // Follow-up radar, slice 2: chases of his own unanswered emails, sent only after his Send.
        stage(a, "followUpChases") { followUpChases(a, m, token) }
        // Inbox fixes, slice 1: Outlook's emails that only look like a person's have their own list and bulk headers read.
        stage(a, "bulkLook") { bulkLook(a, m, token, ms) }
        // Inbox fixes, slice 4: the people he has written to (once a day), so their mail is his before it's sorted.
        stage(a, "readCorrespondents") { readCorrespondents(a, m, token) }
        sortHeaders(a, ms)
        // Slice 5a: the newsletter and promo senders (before Autopilot moves their newest email) and any Unsubscribe asked.
        stage(a, "mailSenders") { mailSenders(a, m, token, ms) }
        // Slice 3b: Autopilot (only once it's on and the dry-run day is over); a failure there never fails the check.
        stage(a, "autopilot") { autopilot(a, m, token, ms) }
        // Slice 3c: the daily Junk review (same switch and dry-run day as Autopilot).
        stage(a, "junkReview") { junkReview(a, m, token, ms) }
        // Slice 8a: the backlog clean-up's plan (read-only: nothing in the mailbox moves).
        stage(a, "backlog") { backlog(a, m, token, ms) }
        // Inbox fixes, slice 7: the inbox's unread count and how many of those Navilife can tidy, for Today's line.
        stage(a, "unreadCounts") { unreadCounts(a, ms) }
        // Inbox fixes, slice 8: "Mark N read" (only after his tap on the card).
        stage(a, "markInboxRead") { markInboxRead(a, m, token, ms) }
        // Inbox fixes, slice 3: "Empty Junk" (counted for its card; cleared out only after his Approve).
        stage(a, "junkClearOut") { junkClearOut(a, m, token, ms) }
        // Slice 6a: the emails that need Meka, as cards in Needs you.
        stage(a, "publishMailNeeds") { publishMailNeeds(a, ms) }
        // Drafted replies come to him: a draft on the card of each new email that asks him something (sent only on his tap).
        stage(a, "autoDrafts") { autoDrafts(a) }
        // Follow-up radar, slice 1: his own emails with no reply, a few times a day (on his tap in the apps).
        stage(a, "followUps") { followUps(a, m, token, ms) }
        // Slice 3g: parcels arriving, from the shipping emails (whether or not Autopilot has tidied them).
        stage(a, "parcels") { parcels(a, ms) }
        // Slice 3h: bills due, from the bill emails (whether or not Autopilot has tidied them).
        stage(a, "bills") { bills(a, ms) }
        // Promises, receipts and renewals, slice 1: renewals to add, from the renewal emails (on his tap in the apps).
        stage(a, "renewals") { renewals(a, ms) }
        // Slice 2: promises made to him, to add to Waiting for (on his tap in the apps).
        stage(a, "promises") { promises(a, ms) }
        // School and family logistics, slice 1: the school's dates, payments, forms and trips (on his tap in the apps).
        stage(a, "schoolMail") { schoolMail(a, ms) }
    }

    /** Follow-up radar: when each account's sent mail was last read (in memory; a restart just reads it again). */
    private val followUpsReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Follow-up radar, slice 1: at most every [FOLLOW_UPS_EVERY_MS], the account's sent mail of the last
     * [MailFollowUpRules.WINDOW_DAYS] days ([MailProvider.sent]: recipients, subject, preview, time; never a body). The
     * newest email he sent in each conversation that [MailFollowUpRules.read] says asks someone something, sent at
     * least [MailFollowUpRules.MIN_DAYS] day ago, with no email in that conversation from anyone else since, becomes one
     * `mail_find` (kind FOLLOW_UP), written once (slice 3: the apps show it once that person's wait has passed, 3 days
     * unless he chose his own); its mirror row keeps when it was sent and the conversation (server
     * only). Each later check marks an open one answered when someone replies in the conversation or he writes in it
     * again, and the apps drop it. Nothing goes on Waiting for and nothing is sent here: the apps' Add does the first.
     */
    internal fun followUps(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val nowMs = now()
        followUpsReadAt[a.id]?.let { if (nowMs - it < FOLLOW_UPS_EVERY_MS) return }
        val sent = m.sent(token, nowMs - MailFollowUpRules.WINDOW_MS, FOLLOW_UPS_LIMIT) ?: return
        followUpsReadAt[a.id] = nowMs
        val own = store.accounts(a.householdId).map { it.email.lowercase() }.toSet() + a.email.lowercase()
        val inbound = ms.messages(a.id).filter { it.threadId != null }.groupBy({ it.threadId!! }, { it.fromAddress to it.receivedAtMs })
        fun thread(s: MailSent) = s.threadId ?: s.id
        val latest = sent.groupBy(::thread).mapValues { (_, v) -> v.maxBy { it.sentAtMs } }
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        // Open ones answered since: they replied, or he wrote again in the conversation.
        for ((id, row) in mirror) {
            val t = row.remoteId?.let { followUpThread(it) } ?: continue
            if (row.removed) continue
            // Once they replied it stands; after "wrote again" (his own chase among them) a reply still counts.
            val wroteAgain = row.fieldOps[MailFindFields.ANSWERED_WHY]?.second == valueKey(FieldValue.Text(MailFollowUpRules.ANSWERED_WROTE_AGAIN))
            if (MailFindFields.ANSWERED_AT in row.fieldOps && !wroteAgain) continue
            // Deleted with Delete mail data: left deleted (a write would bring it back).
            if (row.fieldOps[DELETED_FIELD]?.second == valueKey(FieldValue.Bool(true))) continue
            val why = when {
                MailFollowUpRules.replied(row.startMs, inbound[t].orEmpty(), own) -> MailFollowUpRules.ANSWERED_REPLIED
                wroteAgain -> continue
                (latest[t]?.sentAtMs ?: 0L) > row.startMs + 1_000L -> MailFollowUpRules.ANSWERED_WROTE_AGAIN
                else -> continue
            }
            writeFollowUp(a, id, MailFollowUpRules.answeredFields(nowMs, why), row.startMs, row.remoteId)
        }
        val today = LONDON.epochDayOf(nowMs)
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        for (s in latest.values.sortedBy { it.sentAtMs }) {
            val id = MailFollowUpRules.findId(a.id, s.id)
            if (mirror.containsKey(id)) continue
            val day = LONDON.epochDayOf(s.sentAtMs)
            if (!MailFollowUpRules.due(day, today)) continue
            if (MailFollowUpRules.replied(s.sentAtMs, inbound[thread(s)].orEmpty(), own)) continue
            val facts = MailSentFacts(s.toName, s.toAddress, s.recipients, s.subject, s.preview, s.sentAtMs)
            val fu = MailFollowUpRules.read(facts, own, MailSortRules.isAutomated(s.toAddress)) ?: continue
            writeFollowUp(a, id, MailFollowUpRules.serverFields(fu, account, s.subject, day), s.sentAtMs, followUpRemote(thread(s), s.id))
        }
    }

    /**
     * Follow-up radar, slice 2: the chases he sent from Time to chase on his own unanswered emails. Each one due (his
     * Send, Undo's hold over) is sent once from this account, in the conversation of the email he sent, to the people
     * it went to ([MailProvider.chaseSent]); the find records that it went, or why not (his words come back to him).
     * Never retried here and never sent without his Send.
     */
    internal fun followUpChases(a: AccountRow, m: MailProvider, token: String) {
        val hh = a.householdId
        val mirror = store.transaction { store.mirror(hh, a.id) }
        val due = reader.entities(hh, EntityTypes.MAIL_FIND).filterKeys { it in mirror }
            .mapNotNull { (id, f) -> MailRenewalRules.read(id, f) }
            .filter { it.kind == MailFollowUpRules.KIND_FOLLOW_UP && MailChaseRules.due(it.chase, now()) }
        if (due.isEmpty()) return
        val name = if (m.id == os.meka.core.domain.MailProviders.OUTLOOK) "Outlook" else "Gmail"
        for (f in due) {
            val row = mirror.getValue(f.id)
            val sentId = row.remoteId?.let { followUpMessage(it) }
            val why = if (sentId == null) MailChaseRules.WHY_GONE else try {
                when (m.chaseSent(token, sentId, f.chase.text!!)) {
                    MailReplyResult.SENT -> null
                    MailReplyResult.GONE -> MailChaseRules.WHY_GONE
                    MailReplyResult.FAILED -> MailNeedsRules.whyRefused(name)
                }
            } catch (e: ReconnectRequired) {
                "sign in to $name again"
            } catch (e: Exception) {
                MailNeedsRules.whyUnreachable(name)
            }
            writeFollowUp(a, f.id, if (why == null) MailChaseRules.sentFields(now()) else MailChaseRules.failedFields(now(), why), row.startMs, row.remoteId)
        }
    }

    /** As [writeMail], keeping when the email was sent and its conversation on the mirror row (never synced). */
    private fun writeFollowUp(a: AccountRow, id: String, fields: Map<String, FieldValue>, sentAtMs: Long, thread: String?) {
        val wrote = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                val prev = store.mirror(a.householdId, a.id)[id]
                write(a, id, sentAtMs, sentAtMs, false, prev, fields, EntityTypes.MAIL_FIND, remoteId = thread)
            }
        }
        if (wrote) runCatching { onChanged(a.householdId) }
    }

    /**
     * Promises, receipts and renewals from email, slice 2: the emails of the last [MailPromiseRules.WINDOW_DAYS] days
     * that the rules didn't sort as newsletters, promos or junk, and not from one of his own connected addresses, are
     * read by [MailPromiseRules.read] from the sender, subject and kept preview (a no-reply sender only for a refund);
     * each promise becomes one `mail_find` (kind PROMISE) under an id from the account and the email, written once (an
     * id already in the mirror is never written again, so his Add or Not needed stands). Nothing goes on Waiting for
     * here: the apps' Add does that.
     */
    internal fun promises(a: AccountRow, ms: MailStore) {
        val nowMs = now()
        val own = store.accounts(a.householdId).map { it.email.lowercase() }.toSet() + a.email.lowercase()
        val candidates = ms.messages(a.id).filter { h ->
            nowMs - h.receivedAtMs <= MailPromiseRules.WINDOW_MS && h.receivedAtMs <= nowMs + 60_000L &&
                (h.fromAddress?.lowercase() ?: "") !in own &&
                MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId)).sort
                    .let { it != MailSort.NEWSLETTER && it != MailSort.PROMO && it != MailSort.JUNK }
        }
        if (candidates.isEmpty()) return
        val previews = ms.previews(a.id, candidates.map { it.id })
        val reads = candidates.mapNotNull { h ->
            val day = LONDON.epochDayOf(h.receivedAtMs)
            MailPromiseRules.read(h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview, day, MailSortRules.isAutomated(h.fromAddress))
                ?.let { Triple(h, day, it) }
        }
        if (reads.isEmpty()) return
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        for ((h, day, p) in reads.sortedBy { it.first.receivedAtMs }) {
            val id = MailPromiseRules.findId(a.id, h.id)
            if (mirror.containsKey(id)) continue
            writeMail(a, id, MailPromiseRules.serverFields(p, account, h.subject, day), EntityTypes.MAIL_FIND)
        }
    }

    /** The senders he marked as the school in About you ([SchoolMailRules.senders]); empty when none or unreadable. */
    private fun schoolSenders(householdId: String): Set<String> = runCatching {
        SchoolMailRules.senders(AboutYouRules.read(reader.entity(householdId, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)))
    }.getOrDefault(emptySet())

    /**
     * School and family logistics from email, slice 1: the emails of the last [SchoolMailRules.WINDOW_DAYS] days from a
     * sender he marked as the school in About you ([SchoolMailRules.senders]: the school's website's domain and the
     * School's email line; none marked = nothing read) are read by [SchoolMailRules.read] from the subject and kept
     * preview, with About you's children's names; each day off, payment, form, trip or event still to come becomes one
     * `mail_find` (kind SCHOOL) under an id from the account, the thing and its day, written once (an id already in the
     * mirror is never written again, so his Add or Not needed stands). Nothing goes into School here: the apps' Add
     * does that. Newsletter-style sorting doesn't apply: the school's messaging services send as lists.
     */
    internal fun schoolMail(a: AccountRow, ms: MailStore) {
        val profile = AboutYouRules.read(reader.entity(a.householdId, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID))
        val senders = SchoolMailRules.senders(profile)
        if (senders.isEmpty()) return
        val nowMs = now()
        val candidates = ms.messages(a.id).filter { h ->
            nowMs - h.receivedAtMs <= SchoolMailRules.WINDOW_MS && h.receivedAtMs <= nowMs + 60_000L &&
                SchoolMailRules.fromSchool(h.fromAddress, senders)
        }
        if (candidates.isEmpty()) return
        val previews = ms.previews(a.id, candidates.map { it.id })
        val today = LONDON.epochDayOf(nowMs)
        val children = profile.children.map { it.name }
        val reads = candidates.mapNotNull { h ->
            val day = LONDON.epochDayOf(h.receivedAtMs)
            SchoolMailRules.read(h.subject, previews[h.id] ?: h.preview, day, children)
                ?.takeIf { it.endDay >= today }?.let { Triple(h, day, it) }
        }
        if (reads.isEmpty()) return
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        val written = mutableSetOf<String>()
        for ((h, day, sf) in reads.sortedBy { it.first.receivedAtMs }) {
            val id = SchoolMailRules.findId(a.id, sf)
            if (id in written || mirror.containsKey(id)) continue
            val school = os.meka.core.domain.MailReceiptRules.merchant(h.fromName, h.fromAddress)
            writeMail(a, id, SchoolMailRules.serverFields(sf, school, account, h.subject, day), EntityTypes.MAIL_FIND)
            written += id
        }
    }

    /**
     * Promises, receipts and renewals from email, slice 1: the emails of the last [MailRenewalRules.WINDOW_DAYS] days
     * that the rules didn't sort as newsletters, promos or junk are read by [MailRenewalRules.read] from the sender,
     * subject and kept preview; each renewal still to come becomes one `mail_find` (kind RENEWAL) under an id from the
     * account, company and renewal day, written once (an id already in the mirror is never written again, so his Add or
     * Not needed stands), and none when the radar already has that company near that day. Nothing goes on the radar
     * here: the apps' Add does that.
     */
    internal fun renewals(a: AccountRow, ms: MailStore) {
        val nowMs = now()
        val candidates = ms.messages(a.id).filter { h ->
            nowMs - h.receivedAtMs <= MailRenewalRules.WINDOW_MS && h.receivedAtMs <= nowMs + 60_000L &&
                MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId)).sort
                    .let { it != MailSort.NEWSLETTER && it != MailSort.PROMO && it != MailSort.JUNK }
        }
        if (candidates.isEmpty()) return
        val previews = ms.previews(a.id, candidates.map { it.id })
        val today = LONDON.epochDayOf(nowMs)
        val reads = candidates.mapNotNull { h ->
            val day = LONDON.epochDayOf(h.receivedAtMs)
            MailRenewalRules.read(h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview, day)
                ?.takeIf { it.renewDay >= today }?.let { Triple(h, day, it) }
        }
        if (reads.isEmpty()) return
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        val radar = reader.entities(a.householdId, EntityTypes.OBLIGATION).values
        val written = mutableSetOf<String>()
        for ((h, day, r) in reads.sortedBy { it.first.receivedAtMs }) {
            val id = MailRenewalRules.findId(a.id, h.id, r)
            if (id in written || mirror.containsKey(id) || MailRenewalRules.alreadyOnRadar(r.merchant, r.renewDay, radar, LONDON)) continue
            writeMail(a, id, MailRenewalRules.serverFields(r, account, h.subject, day), EntityTypes.MAIL_FIND)
            written += id
        }
    }

    /**
     * Inbox autopilot, slice 3h (Bills due, slice 2): the emails of the last [MailBillRules.WINDOW_DAYS] days that the
     * rules didn't sort as newsletters, promos or junk are read by [MailBillRules.read] from the sender, subject and kept
     * preview; each bill due today or later becomes one `obligation` (kind BILL) on the Renewals radar under an id from
     * the account, payee and due day, written once (an id already in the mirror is never written again, so his Done or
     * Stop tracking stands), and none when the radar already has that payee's bill near that day. Never a pay link.
     */
    internal fun bills(a: AccountRow, ms: MailStore) {
        val nowMs = now()
        // School logistics: the school's payments are School's dates (with School's own reminder), never a second bill.
        val school = schoolSenders(a.householdId)
        val candidates = ms.messages(a.id).filter { h ->
            nowMs - h.receivedAtMs <= MailBillRules.WINDOW_MS && h.receivedAtMs <= nowMs + 60_000L &&
                !SchoolMailRules.fromSchool(h.fromAddress, school) &&
                MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId)).sort
                    .let { it != MailSort.NEWSLETTER && it != MailSort.PROMO && it != MailSort.JUNK }
        }
        if (candidates.isEmpty()) return
        val previews = ms.previews(a.id, candidates.map { it.id })
        val today = LONDON.epochDayOf(nowMs)
        val reads = candidates.mapNotNull { h ->
            val day = LONDON.epochDayOf(h.receivedAtMs)
            MailBillRules.read(h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview, day)
                ?.takeIf { it.dueDay >= today }?.let { Triple(h, day, it) }
        }
        if (reads.isEmpty()) return
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        val radar = reader.entities(a.householdId, EntityTypes.OBLIGATION).values
        val written = mutableSetOf<String>()
        for ((h, day, bill) in reads.sortedBy { it.first.receivedAtMs }) {
            val id = MailBillRules.obligationId(a.id, h.id, bill)
            if (id in written || mirror.containsKey(id) || MailBillRules.alreadyOnRadar(bill, radar, LONDON)) continue
            writeMail(a, id, MailBillRules.fields(bill, account, day, nowMs, LONDON), EntityTypes.OBLIGATION)
            written += id
        }
    }

    /**
     * Inbox autopilot, slice 3g: the order and carrier emails of the last [ParcelRules.WINDOW_DAYS] days (sorted as
     * receipts or notifications by the rules; never newsletters, promos or junk) are read by [ParcelRules.read] from
     * the sender, subject and kept preview, folded into parcels ([ParcelRules.fold]) and written as one `parcel` entity
     * each (days in Europe/London). Rewritten every check; unchanged fields write nothing.
     */
    internal fun parcels(a: AccountRow, ms: MailStore) {
        val nowMs = now()
        val candidates = ms.messages(a.id).filter { h ->
            nowMs - h.receivedAtMs <= ParcelRules.WINDOW_MS && h.receivedAtMs <= nowMs + 60_000L &&
                MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId)).sort
                    .let { it == MailSort.RECEIPT || it == MailSort.NOTIFICATION }
        }
        if (candidates.isEmpty()) return
        val previews = ms.previews(a.id, candidates.map { it.id })
        val reads = candidates.mapNotNull { h ->
            ParcelRules.read(h.id, h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview, h.receivedAtMs, LONDON.epochDayOf(h.receivedAtMs))
        }
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        for (p in ParcelRules.fold(reads)) writeMail(a, ParcelRules.entityId(a.id, p.key), ParcelRules.fields(p, account), EntityTypes.PARCEL)
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
        // Inbox fixes, slice 2: a plan made by older rules (30 days, the old sort) that he hasn't approved and nothing has
        // moved under is started again, so the plan he approves is what today's rules would do.
        // Decided once per rules version: the server's own record says whether it already wrote this version.
        val entityId0 = MailBacklogRules.entityId(a.id)
        val wroteVersion = store.mirror(a.householdId, a.id)[entityId0]?.fieldOps?.get(MailBacklogFields.PLAN_VERSION)?.second ==
            valueKey(FieldValue.Int64(MailBacklogRules.PLAN_VERSION.toLong()))
        if (!wroteVersion && ms.backlogScan(a.id) != null) {
            val before = runCatching { reader.entity(a.householdId, EntityTypes.MAIL_BACKLOG, entityId0) }.getOrNull()
                ?.let { MailBacklogRules.read(entityId0, it) }
            if (MailBacklogRules.needsReplan(before) && ms.tidied(a.id, MailBacklogRules.tidyBatchId(a.id)).isEmpty()) {
                ms.resetBacklog(a.id)
                backlogBulk.remove(a.id)
            }
        }
        val scan = ms.backlogScan(a.id) ?: BacklogScan(
            beforeMs = minOf(nowMs, (a.grantedAtMs ?: nowMs) + MailAutopilotRules.DRY_RUN_MS), startedMs = nowMs,
            nextPage = null, listed = false, readyMs = null,
        )
        var s = scan
        val keepBase = keepAddresses(a.householdId)
        // Completeness: a sender Meka keeps in the inbox is kept by the clean-up too (read once a check).
        val keptIds = runCatching { reader.entities(a.householdId, EntityTypes.MAIL_SENDER) }.getOrDefault(emptyMap())
            .filter { (_, f) -> MailSenderRuleRules.read(f) == MailSenderRule.KEEP }.keys
        // Inbox fixes, slice 2: Outlook's listing has no list headers, so (like new mail's [bulkLook]) a person-looking
        // email's own headers are read, once per sender (a newsletter's every email carries the same), up to
        // [BACKLOG_SENDER_LOOKS_PER_CHECK] senders a check; a refusal ends this check's reads.
        val senders = backlogBulk.computeIfAbsent(a.id) { java.util.concurrent.ConcurrentHashMap() }
        var looks = 0
        var refused = false
        fun withBulk(h: MailHeader): MailHeader {
            if (m.id != MailProviders.OUTLOOK || refused) return h
            if (!h.listUnsubscribe.isNullOrBlank() || !h.listId.isNullOrBlank() || MailSortRules.BULK_LABEL in h.labels) return h
            if (!MailSortRules.wantsHeaderLook(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId), h.inInbox)) return h
            val key = h.fromAddress?.lowercase() ?: return h
            val got = senders[key]?.orElse(null) ?: run {
                if (senders.containsKey(key) || looks >= BACKLOG_SENDER_LOOKS_PER_CHECK) return h
                looks++
                val read = try {
                    m.bulkHeaders(token, h.id)
                } catch (e: ReconnectRequired) {
                    throw e
                } catch (e: Exception) {
                    refused = true
                    return h
                }
                senders[key] = java.util.Optional.ofNullable(read)
                read
            } ?: return h
            if (got.listUnsubscribe.isNullOrBlank() && got.listId.isNullOrBlank() && !got.bulk) return h
            return h.copy(
                listUnsubscribe = got.listUnsubscribe, listId = got.listId,
                labels = if (got.bulk && MailSortRules.BULK_LABEL !in h.labels) h.labels + MailSortRules.BULK_LABEL else h.labels,
            )
        }
        fun planned(raw: MailHeader): BacklogItem {
            val h = withBulk(raw)
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
            // Inbox fixes, slice 1: new mail's headers come first; the backlog reads only once they're all in.
            val budget = if (ms.pendingCount(a.id) == 0) BACKLOG_HEADERS_PER_CHECK else 0
            val read = ms.backlogPending(a.id, budget).mapNotNull { id ->
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
     * Inbox fixes, slice 7: Today's unread line. The provider's own unread count for the inbox (read at the start of
     * this check) and how many unread emails still in the inbox the tidy rules would move ([MailTidyRules.plan], as
     * the dry run counts them), written on the account's `mail_backlog` entity when [MailUnreadRules.writeDue] says the
     * change is worth a sync. Counts only.
     */
    internal fun unreadCounts(a: AccountRow, ms: MailStore) {
        val unread = mailChecks[a.id]?.inboxUnread?.toInt() ?: return
        val tidy = unreadTidyKind(a, ms).size.coerceAtMost(unread)
        val entityId = MailBacklogRules.entityId(a.id)
        val prev = runCatching { reader.entity(a.householdId, EntityTypes.MAIL_BACKLOG, entityId) }.getOrNull()
        if (!MailUnreadRules.writeDueFields(prev, unread, tidy, now())) return
        writeMail(a, entityId, MailUnreadRules.fields(unread, tidy, now()), EntityTypes.MAIL_BACKLOG)
    }

    /** Slice 7/8: the unread emails still in the inbox that the tidy rules would move (newest first), headers only. */
    private fun unreadTidyKind(a: AccountRow, ms: MailStore): List<MailHeader> {
        val keep = keepAddresses(a.householdId)
        val never = ms.neverTidied(a.id)
        val inbox = ms.inboxSorted(a.id).filter { (h, _) -> h.inInbox && h.unread && h.id !in never }
        val rules = senderRules(a.householdId, a.id, inbox.map { it.first.fromAddress })
        return inbox.filter { (h, s) ->
            MailTidyRules.plan(MailTidyFacts(h.fromAddress, h.inInbox, s, keep, rule = h.fromAddress?.lowercase()?.let { rules[it] })) != MailTidy.KEEP
        }.map { it.first }
    }

    /**
     * Inbox fixes, slice 8: **Mark N read**. Once Meka tapped Mark read on the account's card (and while Navilife isn't
     * paused), each check marks up to [MailMarkReadRules.PER_CHECK_GMAIL] (Outlook [MailMarkReadRules.PER_CHECK_OUTLOOK])
     * of the unread tidy-kind emails still in the inbox read, by the same rules Today's line counts them with (so people,
     * his partner, banks, schools and anything he said to keep are never touched); nothing moves. A check that finds none
     * left finishes the run. Counts only on the entity.
     */
    internal fun markInboxRead(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val hh = a.householdId
        val entityId = MailBacklogRules.entityId(a.id)
        val b = reader.entity(hh, EntityTypes.MAIL_BACKLOG, entityId)?.let { MailBacklogRules.read(entityId, it) } ?: return
        val paused = AutonomyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused
        if (!MailMarkReadRules.shouldMark(b, paused)) return
        val perCheck = if (m.id == MailProviders.OUTLOOK) MailMarkReadRules.PER_CHECK_OUTLOOK else MailMarkReadRules.PER_CHECK_GMAIL
        val ids = unreadTidyKind(a, ms).take(perCheck).map { it.id }
        if (ids.isEmpty()) {
            writeMail(a, entityId, MailMarkReadRules.progressFields(b.markedRead, now()), EntityTypes.MAIL_BACKLOG)
            return
        }
        val done = m.markReadDone(token, ids)
        if (done.isEmpty()) return
        ms.markSeen(a.id, done)
        writeMail(a, entityId, MailMarkReadRules.progressFields(b.markedRead + done.size, null), EntityTypes.MAIL_BACKLOG)
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
            var interrupted = false
            if (chunk.isNotEmpty()) {
                val (back, cut) = m.untidyKept(token, chunk.associate { it.movedId to it.tidy })
                interrupted = cut
                if (markingRead(a.householdId)) markRead(m, token, chunk.filter { it.tidy.marksRead }.mapNotNull { back[it.movedId] }, false)
                // Interrupted: only what came back is done; the rest of the chunk is put back next check.
                val handled = if (cut) chunk.filter { it.movedId in back } else chunk
                ms.neverTidy(a.id, handled.map { it.id } + handled.map { it.movedId } + back.values)
                putBack += back.size
            }
            writeMail(a, entityId, MailBacklogRules.progressFields(moved.size, plan.applyStartedAtMs, plan.finishedAtMs, putBack), EntityTypes.MAIL_BACKLOG)
            if (!interrupted && left.size <= chunk.size) {
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
            val (got, interrupted) = m.tidyKept(token, chunk.associate { it.id to it.kind.tidy })
            val newly = chunk.mapNotNull { item -> got[item.id]?.let { movedId -> MailMoved(item.id, movedId, item.kind.tidy) } }
            ms.recordTidy(a.id, batchId, newly)
            // Inbox fixes, slice 1: the clean-up marks what it moved read too (his switch).
            if (markingRead(hh)) markRead(m, token, newly.filter { it.tidy.marksRead }.map { it.movedId }, true)
            all = moved + newly
            // Not moved: gone or out of the inbox since the plan → not the backlog's any more; otherwise tried next check.
            // Interrupted by a provider failure: the rest are simply tried next check (no header reads now).
            if (!interrupted) chunk.filter { it.id !in got }.forEach { item ->
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
        val rules = all.filter { MailSenderRuleRules.ruleDue(it) || MailSenderRuleRules.clearDue(it) }
        if (asked.isEmpty() && chases.isEmpty() && rules.isEmpty()) return
        val byItem = ms.messages(a.id).associateBy { MailNeedsRules.itemId(a.id, it.id) }
        if (rules.isNotEmpty()) cardRules(a, rules, byItem)
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
                val moved = m.tidyKept(token, mapOf(h.id to MailTidy.ARCHIVE)).first[h.id] ?: continue
                ms.recordTidy(a.id, item.id, listOf(MailMoved(h.id, moved, MailTidy.ARCHIVE)))
                ms.put(hh, a.id, h.copy(inInbox = false))
                writeMail(a, item.id, MailNeedsRules.archivedFields(now()), EntityTypes.MAIL_ITEM)
            } else {
                val items = ms.tidied(a.id, item.id)
                if (items.isNotEmpty()) {
                    val back = m.untidyKept(token, items.associate { it.movedId to it.tidy }).first
                    ms.neverTidy(a.id, items.map { it.id } + items.map { it.movedId } + back.values)
                    // Gmail keeps the id: back in the inbox now, sorted again this check (Outlook's new id comes with its feed).
                    items.forEach { t -> byItem[item.id]?.takeIf { back[t.movedId] == t.id }?.let { h -> ms.put(hh, a.id, h.copy(inInbox = true)) } }
                }
                writeMail(a, item.id, MailNeedsRules.putBackFields(now()), EntityTypes.MAIL_ITEM)
            }
        }
    }

    /**
     * Slice 10b: **Always tidy** on a card. The sender (by the email's address, which stays here) gets the always-tidy
     * rule on its `mail_sender` entity — made from the card when it has none (a person, not a newsletter) — unless he
     * set a rule on it since, and never for a sender Autopilot must never tidy (a bank, school, public body, his
     * partner): those are only archived, like a Done. After an Undo the rule comes off again while it's still the
     * card's. Each once; the email itself is archived by the Done that came with it.
     */
    private fun cardRules(a: AccountRow, items: List<MailItem>, byItem: Map<String, MailHeader>) {
        val hh = a.householdId
        val senders = runCatching { reader.entities(hh, EntityTypes.MAIL_SENDER) }.getOrDefault(emptyMap())
        val keep = keepAddresses(hh)
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        for (item in items) {
            val asked = item.tidyAskedAtMs ?: continue
            val h = byItem[item.id]
            val address = h?.fromAddress?.lowercase()?.takeIf { it.isNotEmpty() }
            val id = address?.let { MailUnsubscribeRules.senderId(a.id, it) }
            if (MailSenderRuleRules.ruleDue(item)) {
                if (h != null && id != null && !MailTidyRules.protectedSender(address, keep)) {
                    MailSenderRuleRules.senderFieldsForCard(senders[id], account, h.fromName, address, h.receivedAtMs, asked)
                        ?.let { writeSender(a, id, it, rewrite = RULE_FIELDS) }
                }
                writeMail(a, item.id, MailSenderRuleRules.ruleWrittenFields(now()), EntityTypes.MAIL_ITEM)
            } else {
                if (id != null) MailSenderRuleRules.clearFieldsForCard(senders[id], asked, now())?.let { writeSender(a, id, it, rewrite = RULE_FIELDS) }
                writeMail(a, item.id, MailSenderRuleRules.ruleClearedFields(now()), EntityTypes.MAIL_ITEM)
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
        val reasons = ms.inboxSorted(a.id).associate { (h, s) -> h.id to s.reason }
        val shown = HashSet<String>()
        for (h in needs) {
            val id = MailNeedsRules.itemId(a.id, h.id)
            shown += id
            val preview = previews[h.id] ?: h.preview
            // Drafted replies: whether a person asks him something, and what, in their words (headers and preview only).
            val asks = MailAutoDraftRules.needsReply(reasons[h.id], h.fromAddress, h.subject, preview)
            writeMail(a, id, MailNeedsRules.serverFields(
                account, h.fromName, h.fromAddress, h.subject, preview, h.receivedAtMs, threads[h.threadId ?: h.id] ?: 1,
                threadKey = MailNeedsRules.threadKey(a.id, h.threadId ?: h.id),
            ) + MailAutoDraftRules.serverFields(asks, if (asks) MailAutoDraftRules.wants(h.subject, preview) else null), EntityTypes.MAIL_ITEM)
        }
        goneMailNeeds(a, shown)
    }

    /**
     * Drafted replies come to him ([MailAutoDraftRules]): each email of this account on the cards that needs a reply
     * and has no draft yet gets one from MEKA's AI ([mailDrafter], the `mail.draft` call: the account's label, the
     * sender's name, the subject, the preview and up to three of his own sent replies for his tone), at most
     * [MailAutoDraftRules.PER_CHECK] a check and [MailAutoDraftRules.DAILY_CAP] a day for the household. A checked draft
     * ([MailDraftRules.check]) is written on the email for his card; anything else is recorded as tried and never asked
     * again. AI off or over budget: nothing is written and the next check asks again. **Nothing is sent here**: the
     * draft goes only when he taps Send on the card (the reply path in [mailNeedsAsks]).
     */
    internal fun autoDrafts(a: AccountRow) {
        val drafter = mailDrafter ?: return
        val hh = a.householdId
        val nowMs = now()
        val mine = store.transaction { store.mirror(hh, a.id) }.keys
        val all = reader.entities(hh, EntityTypes.MAIL_ITEM).mapNotNull { (id, f) -> MailNeedsRules.read(id, f) }
        val due = MailAutoDraftRules.toDraft(all.filter { it.id in mine }, all, nowMs, calendar)
        if (due.isEmpty()) return
        val codec = os.meka.core.wire.MailDraftCodec
        val today = calendar.epochDayOf(nowMs)
        val readable = "${CivilDate.longLabel(today)} ${CivilDate.fromEpochDay(today).year} · ${os.meka.core.domain.LocalClock.formatMinute(calendar.minuteOfDay(nowMs))}"
        val examples = MailDraftRules.examples(all).map { it.take(codec.MAX_EXAMPLE) }.take(codec.MAX_EXAMPLES)
        for (item in due) {
            val request = os.meka.core.wire.MailDraftCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today), now = readable.take(os.meka.core.wire.MessageRequestCodec.MAX_NOW),
                account = item.account.take(codec.MAX_ACCOUNT), business = item.account == MailLabel.BUSINESS.label,
                from = item.from.take(codec.MAX_FROM), subject = item.subject.take(codec.MAX_SUBJECT),
                preview = item.preview?.take(codec.MAX_PREVIEW), examples = examples,
            )
            val answer = runCatching { drafter.draft(request) }.getOrNull()
            when (answer?.state) {
                null -> return // unreachable: try again at the next check
                os.meka.core.wire.AskCodec.Response.OFF, os.meka.core.wire.AskCodec.Response.OVER -> return
                os.meka.core.wire.AskCodec.Response.ANSWERED -> {
                    val text = MailDraftRules.check(answer?.draft)
                    writeMail(a, item.id, if (text != null) MailAutoDraftRules.draftedFields(text, nowMs) else MailAutoDraftRules.triedFields(nowMs), EntityTypes.MAIL_ITEM)
                }
                else -> writeMail(a, item.id, MailAutoDraftRules.triedFields(nowMs), EntityTypes.MAIL_ITEM)
            }
        }
    }

    /**
     * Delete mail data: every entity of [MAIL_DATA_TYPES] this account published is deleted (the synced `deleted`
     * field, so both apps drop it). Writing to the same id later (he connects it again) brings it back ([write]).
     */
    private fun deleteMailData(a: AccountRow) {
        val mine = store.transaction { store.mirror(a.householdId, a.id) }
        val gone = mapOf(DELETED_FIELD to FieldValue.Bool(true))
        for (type in MAIL_DATA_TYPES) {
            val ids = runCatching { reader.entities(a.householdId, type) }.getOrDefault(emptyMap()).keys.filter { it in mine }
            for (id in ids) writeMail(a, id, gone, type)
        }
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
        val check = mailChecks[a.id]
        val since = MailAutopilotRules.since(setting, paused, a.grantedAtMs, now())
            ?: return run { check?.autopilot = "not tidying" }
        val never = ms.neverTidied(a.id)
        val candidates = ms.inboxSorted(a.id).filter { (h, _) -> h.receivedAtMs >= since && h.id !in never }
        if (candidates.isEmpty()) return run { check?.autopilot = "no new sorted mail since it started" }
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
        if (plan.isEmpty()) return run { check?.autopilot = "${candidates.size} new, nothing to tidy" }
        val at = now()
        val batchId = MailAutopilotRules.batchId(a.id, at)
        // Completeness: a provider failure part-way still records (with Undo) what moved; the rest are tried next check.
        val moved = m.tidyKept(token, plan.associate { (h, t) -> h.id to t }).first
        val done = plan.filter { (h, _) -> h.id in moved }
        check?.autopilot = "planned ${plan.size}, moved ${done.size}"
        if (done.isEmpty()) return
        ms.recordTidy(a.id, batchId, done.map { (h, t) -> MailMoved(h.id, moved.getValue(h.id), t) })
        // Inbox fixes, slice 1: what was tidied is marked read too (his switch), best effort.
        if (setting.markRead) markRead(m, token, done.filter { (h, t) -> t.marksRead && h.unread }.map { (h, _) -> moved.getValue(h.id) }, true)
        // Out of the inbox now, so the next check doesn't plan them again before the provider's feed says so.
        done.forEach { (h, _) -> ms.put(hh, a.id, h.copy(inInbox = false)) }
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        // Slice 3d: what the receipts cost and who from, read from the subject and the kept preview (never the body).
        val receiptHeaders = done.filter { it.second == MailTidy.RECEIPT }.map { it.first }
        val previews = if (receiptHeaders.isEmpty()) emptyMap() else ms.previews(a.id, receiptHeaders.map { it.id })
        val receipts = receiptHeaders.map { h -> MailReceiptRules.read(h.fromName, h.fromAddress, h.subject, previews[h.id] ?: h.preview) }
        // Slice 3e: a receipt that says how long there is to send it back puts a one-off reminder on the Renewals radar.
        val returns = receiptHeaders.zip(receipts).count { (h, r) -> returnReminder(a, account, h, r, previews[h.id] ?: h.preview) }
        // Slice 3f: an order at or above his Warranty reminders threshold puts the warranty's end on the Renewals radar.
        val level = if (receiptHeaders.isEmpty()) null else MailWarrantyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, MailWarrantyRules.ENTITY_ID))
        val warranties = if (level == null) 0 else receiptHeaders.zip(receipts).count { (h, r) -> warrantyReminder(a, account, h, r, previews[h.id] ?: h.preview, level) }
        // "Archived for you" shows which emails: the batch lists them (sender's name and domain, subject; never the address).
        val sorts = candidates.associate { (h, s) -> h.id to s.sort }
        val pence = receiptHeaders.zip(receipts).associate { (h, r) -> h.id to r.pence }
        val emails = tidiedEmails(a, done) { h, t -> (if (t == MailTidy.ARCHIVE || t == MailTidy.TRASH) sorts[h.id] else null) to pence[h.id] }
        writeTidy(a, batchId, MailAutopilotRules.batchFields(at, account, done.groupingBy { it.second }.eachCount(), receipts, returns, warranties) + emails)
        // Slice 5b: each unsubscribed sender's row in Mail says how many later emails went to Trash.
        done.filter { it.second == MailTidy.TRASH }.mapNotNull { (h, _) -> senderOf(h) }.groupBy { it.id }.forEach { (id, ss) ->
            writeSender(a, id, MailUnsubscribeRules.trashedFields(ss.first().trashed, ss.size, at))
        }
    }

    /**
     * Inbox autopilot, slice 3e: when a tidied receipt's subject or kept preview names a return window
     * ([MailReturnRules.lastDay]), a one-off `obligation` "Last day to return · Amazon order" is written once under an id
     * from the account and the email; one already written (even if he edited, finished or removed it since) is never
     * written again. Days are Europe/London's. Returns whether it wrote one.
     */
    private fun returnReminder(a: AccountRow, account: String, h: MailHeader, r: MailReceipt, preview: String?): Boolean {
        val receivedDay = LONDON.epochDayOf(h.receivedAtMs)
        val ret = MailReturnRules.read(r, h.subject, preview, receivedDay) ?: return false
        if (ret.lastDay < LONDON.epochDayOf(now())) return false
        val id = MailReturnRules.obligationId(a.id, h.id)
        if (store.transaction { store.mirror(a.householdId, a.id) }.containsKey(id)) return false
        writeMail(a, id, MailReturnRules.fields(ret, account, receivedDay, now(), LONDON), EntityTypes.OBLIGATION)
        return true
    }

    /**
     * Inbox autopilot, slice 3f: when a tidied receipt's price is at or above [level]'s threshold, a one-off `obligation`
     * (kind WARRANTY) "Warranty ends · Currys order" on the day the warranty ends ([MailWarrantyRules.read]: what the
     * subject or kept preview says, else a year), written once under an id from the account and the email, like 3e's.
     */
    private fun warrantyReminder(
        a: AccountRow, account: String, h: MailHeader, r: MailReceipt, preview: String?, level: os.meka.core.domain.MailWarrantyLevel,
    ): Boolean {
        val receivedDay = LONDON.epochDayOf(h.receivedAtMs)
        val w = MailWarrantyRules.read(r, h.subject, preview, receivedDay, level) ?: return false
        if (w.endDay < LONDON.epochDayOf(now())) return false
        val id = MailWarrantyRules.obligationId(a.id, h.id)
        if (store.transaction { store.mirror(a.householdId, a.id) }.containsKey(id)) return false
        writeMail(a, id, MailWarrantyRules.fields(w, account, receivedDay, now(), LONDON), EntityTypes.OBLIGATION)
        return true
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
                // Inbox fixes, slice 3: Junk older than two days goes to Trash.
                receivedAtMs = h.receivedAtMs, nowMs = nowMs,
            )))
            if (t == MailTidy.KEEP) null else h to t
        }.take(MailJunkRules.MAX_PER_REVIEW)
        junkReviewedAt[a.id] = nowMs
        if (plan.isEmpty()) return
        val batchId = MailJunkRules.batchId(a.id, nowMs)
        // Completeness: a provider failure part-way still records (with Undo) what moved; the rest are tried next check.
        val moved = m.tidyKept(token, plan.associate { (h, t) -> h.id to t }).first
        val done = plan.filter { (h, _) -> h.id in moved }
        if (done.isEmpty()) return
        ms.recordTidy(a.id, batchId, done.map { (h, t) -> MailMoved(h.id, moved.getValue(h.id), t) })
        if (setting.markRead) markRead(m, token, done.filter { (h, t) -> t.marksRead && h.unread }.map { (h, _) -> moved.getValue(h.id) }, true)
        // A rescued email is Meka's to deal with: Autopilot never archives it.
        val rescued = done.filter { it.second == MailTidy.RESCUE }
        if (rescued.isNotEmpty()) ms.neverTidy(a.id, rescued.map { it.first.id } + rescued.map { moved.getValue(it.first.id) })
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        writeTidy(a, batchId, MailJunkRules.batchFields(nowMs, account, done.groupingBy { it.second }.eachCount()) + tidiedEmails(a, done))
    }

    /** "Archived for you" shows which emails, slice 1: the [MailTidyBatchFields.EMAILS] field for what a tidy moved. */
    private fun tidiedEmails(
        a: AccountRow, done: List<Pair<MailHeader, MailTidy>>,
        extra: (MailHeader, MailTidy) -> Pair<MailSort?, Long?> = { _, _ -> null to null },
    ): Map<String, FieldValue> = MailTidiedRules.fields(done.map { (h, t) ->
        val (sort, pence) = extra(h, t)
        MailTidiedEmail(
            key = MailTidiedRules.key(a.id, h.id), tidy = t, sort = sort, receivedAtMs = h.receivedAtMs,
            from = MailReceiptRules.merchant(h.fromName, h.fromAddress) ?: "Unknown sender",
            domain = MailSortRules.domain(h.fromAddress), subject = h.subject?.takeIf { it.isNotBlank() } ?: MailTidiedRules.NO_SUBJECT,
            pence = pence,
        )
    })

    /**
     * Inbox fixes, slice 3: **Empty Junk**. Until Meka approves, the Junk folder is counted now and then
     * ([MailJunkClearRules.countDue]) onto the account's `mail_backlog` entity, which Needs you shows as a card. After his
     * Approve (and while Navilife isn't paused and no Undo was asked), each check reads up to a couple of hundred of the
     * folder's emails older than [MailJunkRules.CLEAR_AFTER_MS] (headers only) and moves each by
     * [MailJunkRules.verdict] (Trash, or rescued for someone he knows; banks, public bodies and schools left), recorded
     * under one `mail_tidy` batch ([MailJunkClearRules.batchId]) whose Undo puts it all back in Junk. A check that finds
     * nothing more to move finishes it. Can't tell which threads he wrote in: nothing moves this check.
     */
    internal fun junkClearOut(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        val hh = a.householdId
        val nowMs = now()
        val entityId = MailBacklogRules.entityId(a.id)
        val b = reader.entity(hh, EntityTypes.MAIL_BACKLOG, entityId)?.let { MailBacklogRules.read(entityId, it) } ?: return
        if (MailJunkClearRules.countDue(b, nowMs)) {
            val count = m.junkCount(token) ?: return
            // Inbox fixes, slice 9: look at the folder's older mail so the card can say exactly what stays (best effort).
            val look = if (count > 0) runCatching { junkLook(a, m, token, ms, nowMs) }.getOrElse { if (it is ReconnectRequired) throw it else null } else null
            writeMail(a, entityId, MailJunkClearRules.countFields(count, nowMs) + (look ?: emptyMap()), EntityTypes.MAIL_BACKLOG)
            return
        }
        val batchId = MailJunkClearRules.batchId(a.id)
        val batch = reader.entity(hh, EntityTypes.MAIL_TIDY, batchId)?.let { MailAutopilotRules.readBatch(batchId, it) }
        val paused = AutonomyRules.read(reader.entity(hh, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)).paused
        if (!MailJunkClearRules.shouldClear(b, paused, batch)) return
        val perCheck = if (m.id == MailProviders.OUTLOOK) MailJunkClearRules.PER_CHECK_OUTLOOK else MailJunkClearRules.PER_CHECK_GMAIL
        val never = ms.neverTidied(a.id)
        val listed = m.junkOlder(token, nowMs - MailJunkRules.CLEAR_AFTER_MS, perCheck) ?: return
        val junk = listed.filter { it.id !in never }
        val before = ms.tidied(a.id, batchId)
        val account = MailLabel.of(a.label)?.label ?: "Mail"
        val plan = junkTidies(a, m, token, ms, junk, nowMs).filter { it.second != MailTidy.KEEP }
        if (plan.isEmpty()) {
            // Nothing more it may move (what's left is for him to check, or too new): finished.
            writeMail(a, entityId, MailJunkClearRules.progressFields(before.size, nowMs), EntityTypes.MAIL_BACKLOG)
            return
        }
        val (moved, _) = m.tidyKept(token, plan.associate { (h, t) -> h.id to t })
        val done = plan.filter { (h, _) -> h.id in moved }
        if (done.isEmpty()) return
        ms.recordTidy(a.id, batchId, done.map { (h, t) -> MailMoved(h.id, moved.getValue(h.id), t) })
        if (markingRead(hh)) markRead(m, token, done.filter { (h, t) -> t.marksRead && h.unread }.map { (h, _) -> moved.getValue(h.id) }, true)
        val rescued = done.filter { it.second == MailTidy.RESCUE }
        if (rescued.isNotEmpty()) ms.neverTidy(a.id, rescued.map { it.first.id } + rescued.map { moved.getValue(it.first.id) })
        val all = ms.tidied(a.id, batchId)
        writeTidy(a, batchId, MailJunkClearRules.batchFields(batch?.atMs ?: nowMs, account, all.groupingBy { it.tidy }.eachCount()))
        writeMail(a, entityId, MailJunkClearRules.progressFields(all.size, null), EntityTypes.MAIL_BACKLOG)
    }

    /**
     * Where each of [junk] would go by [MailJunkRules.verdict] (KEEP: stays for him to check). Reads which threads he
     * wrote in once; the people he knows are his keep list and the senders the sort calls his.
     */
    private fun junkTidies(a: AccountRow, m: MailProvider, token: String, ms: MailStore, junk: List<MailHeader>, nowMs: Long): List<Pair<MailHeader, MailTidy>> {
        if (junk.isEmpty()) return emptyList()
        val oldest = junk.minOf { it.receivedAtMs }
        val replied = m.repliedThreads(token, oldest - MAIL_WINDOW_MS)
        val keep = keepAddresses(a.householdId)
        val known = ms.inboxSorted(a.id)
            .filter { (h, s) -> h.inInbox && (s.sort == MailSort.NEEDS_YOU || s.sort == MailSort.FYI) }
            .mapNotNull { it.first.fromAddress?.lowercase() }.toSet()
        return junk.map { h ->
            h to MailJunkRules.tidyOf(MailJunkRules.verdict(MailJunkFacts(
                h.fromAddress, h.fromName, h.subject, keep,
                replied = h.threadId?.let { it in replied } == true,
                knownSender = h.fromAddress?.lowercase()?.let { it in known } == true,
                receivedAtMs = h.receivedAtMs, nowMs = nowMs,
            )))
        }
    }

    /**
     * Inbox fixes, slice 9: the Empty Junk card's exact counts. Pages through the folder's mail older than
     * [MailJunkRules.CLEAR_AFTER_MS] (headers only, at most [MailJunkClearRules.LOOK_MAX_GMAIL] / `LOOK_MAX_OUTLOOK`) and
     * counts where each would go; nothing in the mailbox moves. Null when this provider can't list it.
     */
    private fun junkLook(a: AccountRow, m: MailProvider, token: String, ms: MailStore, nowMs: Long): Map<String, FieldValue>? {
        val outlook = m.id == MailProviders.OUTLOOK
        val max = if (outlook) MailJunkClearRules.LOOK_MAX_OUTLOOK else MailJunkClearRules.LOOK_MAX_GMAIL
        val page = if (outlook) MailJunkClearRules.PER_CHECK_OUTLOOK else MailJunkClearRules.PER_CHECK_GMAIL
        val never = ms.neverTidied(a.id)
        val seen = LinkedHashMap<String, MailHeader>()
        var before = nowMs - MailJunkRules.CLEAR_AFTER_MS
        var full = false
        while (seen.size < max) {
            val got = m.junkOlder(token, before, minOf(page, max - seen.size)) ?: return null
            val fresh = got.filter { it.id !in seen }
            fresh.forEach { seen[it.id] = it }
            if (got.size < minOf(page, max - seen.size + fresh.size) || fresh.isEmpty()) { full = true; break }
            before = fresh.minOf { it.receivedAtMs }
        }
        val junk = seen.values.filter { it.id !in never }
        return MailJunkClearRules.lookFields(junkTidies(a, m, token, ms, junk, nowMs).map { it.second }, full)
    }

    /**
     * Inbox fixes, slice 2: what each Outlook sender's own headers said when the backlog read one of its emails (this
     * process), by account and sender address; empty for a sender with none that matter.
     */
    private val backlogBulk = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<MailBulkHeaders>>>()

    /** Inbox fixes, slice 1: his "Mark tidied mail as read" (on unless he turned it off). */
    private fun markingRead(householdId: String): Boolean =
        MailAutopilotRules.read(reader.entity(householdId, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID)).markRead

    /** Marks [ids] read (or unread for an Undo); best effort, a failure never undoes the move it follows. */
    private fun markRead(m: MailProvider, token: String, ids: List<String>, read: Boolean) {
        if (ids.isEmpty()) return
        runCatching { m.markRead(token, ids, read) }
    }

    /**
     * Inbox fixes, slice 1: what each Outlook email's own headers said (this process; a restart reads them once more),
     * by account and message id; null for one with none that matter. Kept so a later change feed (it was read, moved
     * back) that brings the bare headers again doesn't make a newsletter look like a person's email.
     */
    private val bulkLooked = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<MailBulkHeaders>>>()

    /**
     * Inbox fixes, slice 1 (Meka, 2026-10-15: Outlook showed "1,571 need you"): Outlook's change feed has no list
     * headers, so the rules called every unknown sender a person. Each check, up to [BULK_LOOKS_PER_CHECK] of the newest
     * inbox emails the rules can only call a person's have their internet headers read once ([MailProvider.bulkHeaders]);
     * one with List-Unsubscribe, List-Id or a bulk Precedence/Auto-Submitted is kept with them and sorted again (a
     * newsletter, a notification), the rest stay for the AI's look. Headers only; nothing in the mailbox changes. A
     * refusal part-way ends this check's reads.
     */
    internal fun bulkLook(a: AccountRow, m: MailProvider, token: String, ms: MailStore) {
        if (m.id != MailProviders.OUTLOOK) return
        val looked = bulkLooked.computeIfAbsent(a.id) { java.util.concurrent.ConcurrentHashMap() }
        fun keep(h: MailHeader, got: MailBulkHeaders) {
            if (got.listUnsubscribe.isNullOrBlank() && got.listId.isNullOrBlank() && !got.bulk) return
            ms.put(a.householdId, a.id, h.copy(
                listUnsubscribe = got.listUnsubscribe, oneClickUnsubscribe = got.oneClick, listId = got.listId,
                labels = if (got.bulk && MailSortRules.BULK_LABEL !in h.labels) h.labels + MailSortRules.BULK_LABEL else h.labels,
            ))
        }
        var reads = 0
        for (h in ms.messages(a.id)) {
            if (!h.listUnsubscribe.isNullOrBlank() || !h.listId.isNullOrBlank() || MailSortRules.BULK_LABEL in h.labels) continue
            if (!MailSortRules.wantsHeaderLook(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId), h.inInbox)) continue
            val known = looked[h.id]
            if (known != null) { if (known.isPresent) keep(h, known.get()); continue }
            if (reads >= BULK_LOOKS_PER_CHECK) continue
            reads++
            val got = try {
                m.bulkHeaders(token, h.id)
            } catch (e: ReconnectRequired) {
                throw e
            } catch (e: Exception) {
                break
            }
            looked[h.id] = java.util.Optional.ofNullable(got)
            got?.let { keep(h, it) }
        }
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
            // Completeness: an Undo a provider failure cut short protects what came back now (never tidied again) and
            // is tried again whole at the next check (putting back is idempotent; one already moved is skipped), and
            // only says it's done once a try got through.
            val (back, interrupted) = m.untidyKept(token, items.associate { it.movedId to it.tidy })
            val handled = if (interrupted) items.filter { it.movedId in back } else items
            ms.neverTidy(a.id, handled.map { it.id } + handled.map { it.movedId } + back.values)
            // Inbox fixes, slice 1: put back unread, as it came.
            if (markingRead(a.householdId)) markRead(m, token, items.filter { it.tidy.marksRead }.mapNotNull { back[it.movedId] }, false)
            if (interrupted) continue
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

    /**
     * [rewrite]: fields written even when the server's own last write had the same value (an app may have changed them
     * since — slice 10b's rule, which is his as much as the card's).
     */
    private fun writeSender(a: AccountRow, id: String, fields: Map<String, FieldValue>, rewrite: Set<String> = emptySet()) {
        val wrote = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                val prev = store.mirror(a.householdId, a.id)[id]?.let { p -> if (rewrite.isEmpty()) p else p.copy(fieldOps = p.fieldOps - rewrite) }
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
        val known = knownPeople(a.householdId)
        for (round in 0 until SORT_ROUNDS) {
            val batch = ms.unsorted(a.id, SORT_BATCH)
            if (batch.isEmpty()) break
            val sorts = batch.associate { h ->
                h.id to MailSortRules.known(MailSortRules.sort(MailSortFacts(h.fromAddress, h.fromName, h.subject, h.labels, h.listUnsubscribe, h.listId)), h.fromAddress, known)
            }
            ms.setSorts(a.id, sorts)
            // Slice 5a: who sends the newsletters and promos (oldest first, so the newest one's headers win).
            batch.asReversed().filter { h -> sorts[h.id]?.let { it.sure && (it.sort == MailSort.NEWSLETTER || it.sort == MailSort.PROMO) } == true }
                .forEach { h -> ms.noteSender(a.householdId, a.id, h) }
            if (batch.size < SORT_BATCH) break
        }
        askAi(a, ms, known)
    }

    /**
     * Slice 2b: one AI call per check for up to [AI_BATCH] unsure ones, newest first (words only: sender and subject).
     * The answer can only keep an email in Needs you or make it FYI ([MailSortRules.afterAi]); an email the answer
     * leaves out stays Needs you and isn't asked about again. AI off, over budget or failing: they wait, unchanged.
     */
    private fun askAi(a: AccountRow, ms: MailStore, known: Set<String>) {
        // Inbox fixes, slice 4: unsure ones from people he has written to (sorted before their sent mail was read) are his.
        if (known.isNotEmpty()) {
            val mine = ms.unsure(a.id, KNOWN_RESORT_LIMIT).filter { h -> h.fromAddress?.lowercase() in known }
            if (mine.isNotEmpty()) ms.setSorts(a.id, mine.associate { h -> h.id to MailSortRules.known(MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false), h.fromAddress, known) })
        }
        val ai = mailAi ?: return
        // Inbox fixes, slice 4: up to [AI_ROUNDS] calls a check while many wait (a big first listing is looked at in hours,
        // not a day); a call that's off, over budget or failing ends this check's asking.
        for (round in 0 until AI_ROUNDS) {
            val batch = ms.unsure(a.id, AI_BATCH)
            if (batch.isEmpty()) return
            val lanes = runCatching { ai.lanes(batch) }.getOrNull() ?: return
            ms.setSorts(a.id, batch.associate { h -> h.id to MailSortRules.afterAi(lanes[h.id]) })
            if (batch.size < AI_BATCH) return
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
     * The forecast moves through the day: refresh every [WEATHER_PERIOD_MS] (sooner after a failure, at the next poll).
     * Nobody is woken for it; the devices pick it up at their next sync, like headlines.
     */
    private fun syncWeather(a: AccountRow, source: WeatherProvider) {
        // The place setting: a changed name is followed at once, not at the next half hour.
        val wanted = wantedPlace(a.householdId)
        val mirror = store.transaction { store.mirror(a.householdId, a.id) }
        fun askedIn(id: String) = mirror[id]?.fieldOps?.get(WeatherFields.ASKED)?.second?.takeIf { it.startsWith("s:") }?.removePrefix("s:")
        val asked = askedIn(WeatherStore.ENTITY_ID)
        // Work (Places item 2): forecast when the provider does and the household names work (the setting or About
        // you); a new work place, or none yet, is followed at once.
        val wantedWork = wantedWorkPlace(a.householdId)
        val workDue = source.forecastsWork && wantedWork != null &&
            (mirror[WeatherStore.WORK_ENTITY_ID] == null || askedIn(WeatherStore.WORK_ENTITY_ID) != wantedWork)
        val last = a.lastSyncAtMs
        if (wanted == asked && !workDue && a.status == "ok" && last != null && now() - last < WEATHER_PERIOD_MS) return
        try {
            // A name that can't be found forecasts home and says so (the apps light the line); it's not a fault.
            val at = wanted?.let { source.locate(it) }
            applyWeather(a, source.forecast(at ?: source.home), asked = wanted, found = wanted == null || at != null)
            // Work's forecast never holds up home's: a failure is tried again at the next poll.
            // A work place that can't be found has no forecast of its own (rule 13: no default work place in the code).
            if (source.forecastsWork && wantedWork != null) runCatching {
                val workAt = source.locate(wantedWork)
                applyWeather(a, workAt?.let { source.forecast(it) } ?: WeatherForecast.EMPTY, asked = wantedWork, found = workAt != null,
                    entityId = WeatherStore.WORK_ENTITY_ID)
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
     * devices, so Today's line and Ask follow within seconds; the checked time alone wakes nobody. Rule 13, release 2
     * slice 6: the lines are the household's own Train route from About you; with none, TfL isn't asked.
     */
    private fun syncLines(a: AccountRow, source: LineStatusProvider) {
        val hour = java.time.Instant.ofEpochMilli(now()).atZone(zone).hour
        if (hour < LINES_FROM_HOUR || hour >= LINES_TO_HOUR) return
        val last = a.lastSyncAtMs
        if (a.status == "ok" && last != null && now() - last < LINES_PERIOD_MS) return
        val lines = routeLines(a.householdId)
        if (lines.isEmpty()) return
        try {
            val changed = applyLines(a, source.statuses(lines))
            store.transaction { store.markSynced(a.id, now()) }
            // In the evening, tomorrow morning's plans too (the commute counts everywhere, slice 7); a failure there
            // leaves the last plans and the feed's status alone.
            val planned = hour >= PLANNED_FROM_HOUR && runCatching { syncPlanned(a, source, lines) }.getOrDefault(false)
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

    /** The TfL ids of the household's Train route (About you); empty without one. */
    internal fun routeLines(hh: String): List<String> = runCatching {
        RouteRules.lines(RouteRules.parse(AboutYouRules.read(reader.entity(hh, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)).route))
    }.getOrDefault(emptyList())

    /** When each line-status feed last read tomorrow's plans (in memory: a restart just reads them again). */
    private val plannedAt = HashMap<String, Long>()

    /**
     * Tomorrow 06:00–10:00 London ([PLANNED_FROM_MIN]–[PLANNED_TO_MIN]): what TfL has planned for the route's lines
     * then, at most every [PLANNED_PERIOD_MS], into the same entity's [LineStatusFields.PLANNED] with its day and the
     * rounded read time. Returns whether the plans changed (so the devices are woken for the wake alarm's offer).
     */
    private fun syncPlanned(a: AccountRow, source: LineStatusProvider, lines: List<String>): Boolean {
        val last = synchronized(plannedAt) { plannedAt[a.id] }
        if (last != null && now() - last < PLANNED_PERIOD_MS) return false
        synchronized(plannedAt) { plannedAt[a.id] = now() }
        val tomorrow = java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().plusDays(1)
        val from = tomorrow.atStartOfDay(zone).plusMinutes(PLANNED_FROM_MIN.toLong()).toInstant().toEpochMilli()
        val to = tomorrow.atStartOfDay(zone).plusMinutes(PLANNED_TO_MIN.toLong()).toInstant().toEpochMilli()
        val states = source.planned(lines, from, to)
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
        // Rule 13, release 2: the household's clubs come from its About you (never the code, never another household's).
        val clubs = runCatching {
            AboutYouRules.clubList(AboutYouRules.read(reader.entity(hh, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)).clubs).map { it.short }
        }.getOrDefault(emptyList())
        val due = TravelRules.due(events, hidden, known, now(), calendar, clubs)
        if (due.isEmpty()) return false
        // Drives start from the household's own home (About you's or the place set), never the generic weather place.
        val home = weather.values.firstOrNull()?.let { w -> wantedPlace(hh)?.let { runCatching { w.locate(it) }.getOrNull() } }
            ?: error("no home")
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
        val work = if (!source.forecastsWork) null else wantedWorkPlace(householdId)?.let { runCatching { source.locate(it) }.getOrNull() }
        return listOfNotNull(home, work)
    }

    /**
     * Where the household's home forecast is for: its place setting ([WeatherPlaceStore]), else its About you home
     * (rule 13, release 2 slice 3: never a place in the code), normalised; null for neither (the provider's generic
     * place).
     */
    internal fun wantedPlace(householdId: String): String? = WeatherPlaceRules.effective(
        runCatching { reader.entity(householdId, EntityTypes.CONTEXT_MODE, WeatherPlaceStore.ENTITY_ID) }.getOrNull()
            ?.get(WeatherPlaceFields.NAME)?.let { (it as? FieldValue.Text)?.value },
        aboutYou(householdId).home,
    )

    /** Where the household's work is: its work place setting ([WorkPlaceStore]), else its About you work; null for none. */
    internal fun wantedWorkPlace(householdId: String): String? = PlacesRules.effectiveWork(
        runCatching { reader.entity(householdId, EntityTypes.CONTEXT_MODE, WorkPlaceStore.ENTITY_ID) }.getOrNull()
            ?.get(WorkPlaceFields.NAME)?.let { (it as? FieldValue.Text)?.value },
        aboutYou(householdId).work,
    )

    /** The household's own About you (empty when it can't be read). */
    private fun aboutYou(householdId: String): AboutYou = runCatching {
        AboutYouRules.read(reader.entity(householdId, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID))
    }.getOrDefault(AboutYou.EMPTY)

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
    internal fun apply(a: AccountRow, events: List<RemoteEvent>, fromMs: Long, toMs: Long): Boolean {
        // Read before the write transaction (the mail headers are another store's).
        val guestMail = guestMail(a, events)
        return applyLocked(a, events, fromMs, toMs, guestMail)
    }

    private fun applyLocked(
        a: AccountRow, events: List<RemoteEvent>, fromMs: Long, toMs: Long, guestMail: Map<String, List<GuestMail>>,
    ): Boolean = store.transaction {
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
                    // Meeting prep, slice 1: the other people on it, names only. Additive like the above.
                    EventFields.GUESTS to (e.guests.takeIf { it.isNotEmpty() }?.joinToString("\n")?.let { FieldValue.Text(it) } ?: FieldValue.Null),
                    // Meeting prep, slice 4: each guest's newest email in his mailboxes, for meetings in the next day
                    // and a half (cleared after). Additive like the above.
                    EventFields.GUEST_MAIL to (guestMail[e.id]?.let { FieldValue.Text(GuestMail.format(it)) } ?: FieldValue.Null),
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

    /**
     * Meeting prep, slice 4: for each of [events] with guests starting within [GUEST_MAIL_AHEAD_MS] (and not yet
     * [GUEST_MAIL_AFTER_MS] past its start), each guest's newest email in the household's mail accounts (the headers the
     * server keeps, 60 days), looked up by the guest's address, which stays here. Event id → the guests that have one,
     * in the event's order. Empty without mail accounts or on any failure: prep then shows what the apps hold.
     */
    internal fun guestMail(a: AccountRow, events: List<RemoteEvent>): Map<String, List<GuestMail>> {
        val ms = mailStore ?: return emptyMap()
        if (a.provider in feeds) return emptyMap()
        val t = now()
        val soon = events.filter { e ->
            !e.allDay && e.guestAddresses.isNotEmpty() && e.startMs <= t + GUEST_MAIL_AHEAD_MS && e.startMs + GUEST_MAIL_AFTER_MS > t
        }
        if (soon.isEmpty()) return emptyMap()
        return runCatching {
            val boxes = store.accounts(a.householdId).filter { it.provider in mail && it.status != "needs_reconnect" }
            if (boxes.isEmpty()) return@runCatching emptyMap()
            val addresses = soon.flatMap { it.guestAddresses.values }.distinct()
            val newest = HashMap<String, MailHeader>()
            for (box in boxes) {
                ms.lastFrom(box.id, addresses, t).forEach { (address, h) ->
                    val had = newest[address]
                    if (had == null || h.receivedAtMs > had.receivedAtMs) newest[address] = h
                }
            }
            soon.associate { e ->
                e.id to e.guests.mapNotNull { name ->
                    val h = e.guestAddresses[name]?.let { newest[it] } ?: return@mapNotNull null
                    GuestMail(name, h.receivedAtMs, h.subject.orEmpty().trim().take(EventFields.MAX_GUEST_MAIL_SUBJECT))
                }
            }.filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    /** Returns whether any op was appended. */
    private fun write(
        a: AccountRow, entityId: String, startMs: Long, endMs: Long, removed: Boolean, prev: MirrorRow?, asked: Map<String, FieldValue>,
        entityType: String = EntityTypes.EVENT,
        /** The provider's id (calendar events): kept on the mirror row so edits can find the real event. */
        remoteId: String? = prev?.remoteId,
    ): Boolean {
        val fieldOps = HashMap(prev?.fieldOps ?: emptyMap())
        // Inbox fixes, slice 5: every tidy and sender carries its account's key (a hash, never the address), so Mail
        // shows each account only its own even when two accounts share a label.
        val keyed = if ((entityType == EntityTypes.MAIL_TIDY || entityType == EntityTypes.MAIL_SENDER) && asked.isNotEmpty() &&
            asked[DELETED_FIELD] != FieldValue.Bool(true)
        ) asked + (MailTidyBatchFields.ACCOUNT_KEY to FieldValue.Text(MailAccountRules.key(a.provider, a.email))) else asked
        // Delete mail data: an entity the server deleted comes back when it's written again.
        val desired = if (prev?.fieldOps?.get(DELETED_FIELD)?.second == valueKey(FieldValue.Bool(true)) && DELETED_FIELD !in keyed)
            keyed + (DELETED_FIELD to FieldValue.Bool(false)) else keyed
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
        /** Slice 3e: Meka's days (a return-by date is a London day; its reminder sits at 09:00 London on it). */
        private val LONDON: LocalCalendar = object : LocalCalendar {
            private val zone = java.time.ZoneId.of("Europe/London")
            override fun epochDayOf(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()
            override fun minuteOfDay(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().toSecondOfDay() / 60
            override fun toEpochMs(epochDay: Long, minuteOfDay: Int) =
                java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
        }
        /** Slice 10b: the sender's rule fields, rewritten whatever the server wrote last. */
        private val RULE_FIELDS = setOf(os.meka.core.domain.MailSenderFields.RULE, os.meka.core.domain.MailSenderFields.RULE_AT)
        const val SERVER_DEVICE = "server"
        /** Delete mail data: what the server publishes from one mail account (Renewals, Waiting for and tasks are his). */
        val MAIL_DATA_TYPES = listOf(EntityTypes.MAIL_ITEM, EntityTypes.MAIL_TIDY, EntityTypes.MAIL_SENDER, EntityTypes.MAIL_BACKLOG, EntityTypes.PARCEL, EntityTypes.MAIL_FIND)
        const val STATE_TTL_MS = 15 * 60_000L
        const val WINDOW_BACK_MS = 24 * 3_600_000L
        /** Inbox autopilot, slice 1c: the mail MEKA keeps headers for (Meka: the last 60 days). */
        const val MAIL_WINDOW_MS = 60 * 24 * 3_600_000L
        /** Follow-up radar: sent mail is read at most this often per account (four times a day). */
        const val FOLLOW_UPS_EVERY_MS = 6 * 3_600_000L
        /** Follow-up radar: the newest this many sent emails are read each time. */
        const val FOLLOW_UPS_LIMIT = 60
        /** Follow-up radar: the mirror row's remoteId prefix holding the conversation. */
        const val FOLLOW_UP_THREAD = "followup:"
        /** Between the conversation and the sent email's id on a follow-up's mirror row (neither holds it). */
        const val FOLLOW_UP_SEP = '|'

        /** A follow-up's mirror row: "followup:<conversation>|<the sent email's id>". */
        fun followUpRemote(thread: String, messageId: String): String = "$FOLLOW_UP_THREAD$thread$FOLLOW_UP_SEP$messageId"

        /** The conversation (rows from before slice 2 hold only that). */
        fun followUpThread(remote: String): String? =
            remote.takeIf { it.startsWith(FOLLOW_UP_THREAD) }?.removePrefix(FOLLOW_UP_THREAD)?.substringBefore(FOLLOW_UP_SEP)

        /** The sent email's id; null on a row from before slice 2. */
        fun followUpMessage(remote: String): String? =
            remote.takeIf { it.startsWith(FOLLOW_UP_THREAD) }?.substringAfter(FOLLOW_UP_SEP, "")?.takeIf { it.isNotEmpty() }
        /** Slice 2a: headers sorted per round (rules only, cheap; a big first listing takes a few rounds of one check). */
        const val SORT_BATCH = 500
        const val SORT_ROUNDS = 20
        /** Slice 5a: the senders published per account (the busiest), the ones read for an Unsubscribe, Outlook header reads a check. */
        const val SENDERS_SHOWN = 30
        const val SENDERS_READ = 200
        const val SENDER_HEADER_CHECKS = 10
        /** Slice 2b: the most unsure emails one AI call looks at (one call per account per check). */
        const val AI_BATCH = 25
        /** Inbox fixes, slice 4: the most AI calls one check makes for one account (100 emails). */
        const val AI_ROUNDS = 4
        /** Inbox fixes, slice 4: the people he has written to: read once a day (again in an hour after a failure). */
        const val CORRESPONDENTS_EVERY_MS = 24 * 3_600_000L
        const val CORRESPONDENTS_RETRY_MS = 3_600_000L
        const val CORRESPONDENTS_WINDOW_MS = 365 * 24 * 3_600_000L
        const val CORRESPONDENTS_LIMIT = 200
        /** Inbox fixes, slice 4: unsure emails looked over a check for a sender he has written to. */
        const val KNOWN_RESORT_LIMIT = 5_000
        /** Gmail headers read per 5-minute check (one call each; Gmail allows far more): a big first listing fills in over a few checks. */
        const val HEADERS_PER_CHECK = 300
        /** Inbox fixes, slice 1: Outlook emails whose own headers are read a check ([bulkLook]). */
        const val BULK_LOOKS_PER_CHECK = 40
        /** Slice 8a: backlog headers read per check (Gmail), and the next page is listed only while fewer than this wait. */
        const val BACKLOG_HEADERS_PER_CHECK = 300
        const val BACKLOG_LIST_BELOW = 500
        /** Inbox fixes, slice 2: Outlook senders whose headers the backlog reads per check (a page is 100 emails). */
        const val BACKLOG_SENDER_LOOKS_PER_CHECK = 100
        const val WINDOW_AHEAD_MS = 30 * 24 * 3_600_000L
        /** Meeting prep, slice 4: guests' newest emails are written for meetings starting within this… */
        const val GUEST_MAIL_AHEAD_MS = 36 * 3_600_000L
        /** …and until this long after the start (prep shows until 10 min after). */
        const val GUEST_MAIL_AFTER_MS = 15 * 60_000L
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

/**
 * Inbox fixes, slice 6: a mail check's failure as a [MailCheckRules.WHY] word, from the provider's status where it
 * answered ("provider HTTP 429" in the message chain, a 403 refusal), a timeout or a lost connection, or the sign-in
 * refresh ([duringSignIn]). Never the provider's own words.
 */
internal fun mailWhy(e: Throwable, duringSignIn: Boolean): String {
    var status: Int? = null
    var network = false
    var reason: String? = null
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 6) {
        if (t is WriteRefused) {
            status = status ?: 403
            // Inbox fixes, slice 7: the provider's reason word, when Http.call kept a known one ("… [accessNotConfigured]").
            reason = reason ?: Regex("""\[([A-Za-z_]+)]""").find(t.message.orEmpty())?.groupValues?.get(1)?.takeIf { it in MailCheckRules.KNOWN_REASONS }
        }
        if (t is java.io.IOException) network = true
        Regex("""HTTP (\d{3})""").find(t.message.orEmpty())?.let { status = status ?: it.groupValues[1].toInt() }
        t = t.cause
    }
    return MailCheckRules.classify(status, network && status == null, duringSignIn, reason)
}
