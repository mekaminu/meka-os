package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import os.meka.backend.integrations.BbcNewsRss
import os.meka.backend.integrations.DvlaCarLookup
import os.meka.backend.integrations.MailDrafter
import os.meka.backend.integrations.FuelFinder
import os.meka.backend.integrations.CalendarWriter
import os.meka.backend.integrations.EntityReader
import os.meka.backend.integrations.PublicNewsFeeds
import os.meka.backend.integrations.NewsImages
import os.meka.backend.integrations.PostgresNewsImageStore
import os.meka.backend.integrations.EspnTeamFixtures
import os.meka.backend.integrations.UkTvListings
import os.meka.backend.integrations.GoogleCalendar
import os.meka.backend.integrations.GmailMail
import os.meka.backend.integrations.HttpOneClickUnsubscriber
import os.meka.backend.integrations.OutlookMail
import os.meka.core.domain.MailProviders
import os.meka.backend.integrations.GovUkBankHolidays
import os.meka.backend.integrations.HereWeather
import os.meka.backend.integrations.TflLineStatus
import os.meka.backend.integrations.GoogleRoutes
import os.meka.backend.integrations.OpenMeteoWeather
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.KmsTokenCipher
import os.meka.backend.integrations.CachingTokenCipher
import os.meka.backend.integrations.MicrosoftCalendar
import os.meka.backend.integrations.PostgresIntegrationStore
import os.meka.backend.integrations.PostgresMailStore
import os.meka.backend.integrations.SecretsManagerOAuthClients
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.CallCreditRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.WorkFields
import os.meka.core.sync.FieldValue
import os.meka.core.sync.ServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.wire.AskCodec
import os.meka.core.wire.GoalBreakdownCodec
import os.meka.core.wire.HandleThisCodec
import os.meka.core.wire.VoiceNoteCodec
import os.meka.core.wire.MessageRequestCodec
import os.meka.core.wire.BriefScriptCodec
import os.meka.core.wire.ChatSummaryCodec
import os.meka.core.wire.HelloOpenersCodec
import os.meka.core.wire.MailDraftCodec
import os.meka.core.wire.GroupDigestCodec
import os.meka.core.wire.HealthCodec
import os.meka.core.wire.HereCodec
import os.meka.core.wire.FuelCodec
import os.meka.core.wire.NumberLookupCodec
import os.meka.core.wire.TradingExportCodec
import os.meka.core.wire.MessageTriageCodec
import os.meka.core.wire.SpeechCodec
import os.meka.core.wire.TalkWarmCodec
import os.meka.core.wire.FoldPostureCodec
import os.meka.core.wire.ServerSpeedCodec
import os.meka.core.wire.NotifyDiagnosticsCodec
import os.meka.core.wire.UpdateFailureCodec
import os.meka.core.wire.VoiceMessageCodec
import os.meka.core.wire.WireCodec
import os.meka.core.wire.WireFormatException
import javax.sql.DataSource

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

class Unauthorised : RuntimeException()
class Forbidden : RuntimeException()

/** Wires the sync API. Pure function of its dependencies so tests run it against in-memory stores. */
fun Application.mekaSync(
    opStore: ServerOpStore,
    devices: DeviceRegistry,
    enrolToken: String? = null,
    /** Long-poll window. Under CloudFront's 30 s origin timeout and the ALB's 60 s idle timeout. */
    waitMs: Long = 20_000,
    waitCheckMs: Long = 1_000,
    /** Connected calendars (ADR-008); null disables the integration routes (tests, or an unconfigured server). */
    integrations: Integrations? = null,
    /** Runs work off the request (the first calendar sync right after connecting). */
    background: (() -> Unit) -> Unit = { Thread.ofVirtual().start(it) },
    verifier: RequestVerifier = RequestVerifier(),
    /** Published app builds (self-updating phone app); null disables the release routes. */
    releases: Releases? = null,
    /** Push wake-ups (build plan M1: push via Firebase); null disables the push route and wake-ups. */
    push: Push? = null,
    /** The release-only publisher's public key (GitHub builds the phone app); null refuses every publisher request. */
    publisher: PublisherKeySource? = null,
    /** The call assistant's phone-service webhooks (build plan M1); null leaves them out. */
    voice: VoiceRoutes? = null,
    /** News pictures the server made (news, images slice); null leaves the route out. */
    newsImages: NewsImages? = null,
    /** Sends calendar edits (calendar editing); a push carrying one pokes it so the edit goes out within seconds. */
    calendarWriter: CalendarWriter? = null,
    /** The AI layer: the key's health check and the monthly budget (V1); null leaves the route out. */
    ai: AiLayer? = null,
    /** MEKA's voice (Amazon Polly, V1); null leaves the speech routes out. */
    speech: SpeechService? = null,
    /** "Where I am now" (Places item 3): the forecast at a rounded point; null leaves the route out. */
    here: HereWeather? = integrations?.hereWeather(),
    /** Cheapest fuel nearby (Car and parking, slice 6): Fuel Finder's prices at a rounded point; null leaves the route out. */
    fuel: FuelFinder? = null,
    /** Spam-call lookup (2026-10-17): what the web says about an unknown caller's number; null leaves the route out. */
    numberLookup: NumberLookup? = null,
    /** The companion, slice 2: web results for Ask's questions about something current; null answers without. */
    liveFacts: LiveFacts? = null,
    /** Trading tracker, slice 8: a tax year's trades from Hyperliquid's public data for the export; null leaves the route out. */
    tradingExports: TradingExports? = null,
    /** The family page (family sharing with his wife, slice 2); null leaves its routes out. */
    family: FamilyShare? = null,
    /** Linking a watch without the enrolment code (Galaxy Watch, slice 1); null leaves its routes out. */
    deviceLink: DeviceLink? = DeviceLink(devices, verifier),
    /** Clean-ups (2026-10-19): a new phone or Mac waits for Meka's Allow on a connected one; null keeps the code's old path. */
    enrolApproval: EnrolApproval? = enrolApprovalFor(devices, opStore, push),
    /** Phone update failures reported by the Fold (build plan: phone updates sometimes fail to install); null leaves the routes out. */
    updateFailures: UpdateFailures? = null,
    /** The Fold's posture changes (bedside clock, slice 2); null leaves the routes out. */
    foldPostures: FoldPostures? = null,
    /** The server's timing of each answer (talking to Ada like a conversation, slice 2); null keeps none and leaves its route out. */
    talkSpeed: TalkSpeed? = null,
    /** The phone's notification trace (notifications never seen, slice 2); null leaves the routes out. */
    notifyReports: NotifyReports? = null,
    /** Approval requests from outside MEKA (Kestrel's trades; build plan V1); null leaves the routes out. */
    approvals: ApprovalRoutes? = null,
    /** The recovery key (build plan V1 "Recovery key"; ADR-005 amendment 2026-10-12); null leaves its routes out. */
    recovery: Recovery? = null,
    /** The Vault's encrypted copies (Vault slice 2b): sealed bytes only; null leaves the routes out. */
    vaultFiles: VaultFiles? = null,
) {
    val sync = SyncService(opStore)

    install(StatusPages) {
        exception<Unauthorised> { call, _ -> call.respondText("unauthorised", status = HttpStatusCode.Unauthorized) }
        exception<WireFormatException> { call, e -> call.respondText("bad request: ${e.message}", status = HttpStatusCode.BadRequest) }
        exception<Forbidden> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
        // Any other validation failure while decoding is the client's fault and must not be retried forever.
        exception<IllegalArgumentException> { call, _ -> call.respondText("bad request", status = HttpStatusCode.BadRequest) }
        exception<Throwable> { call, e ->
            // Never echo internals or payloads; log the class only (ADR-013: no personal data in telemetry).
            call.application.environment.log.error("unhandled ${e::class.simpleName}")
            call.respondText("internal error", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        // Public pages the OAuth consent screens link to (Google requires a home page and privacy policy to publish).
        get("/") {
            call.respondText(resultPage("Navilife", "A private, single-household personal operating system. It is not a public service and has no sign-ups. Caller-number checks and live answers use the Brave Search API (search.brave.com)."), ContentType.Text.Html)
        }
        get("/privacy") { call.respondText(resultPage("Navilife privacy policy", PRIVACY_TEXT), ContentType.Text.Html) }
        // Enable Banking's production application form asks for a terms URL too (2026-10-17).
        get("/terms") {
            call.respondText(resultPage("Navilife terms", "Navilife is a private, single-household app used only by its owner for his own accounts. It is not offered to the public, has no sign-ups and no other users. Bank data is read-only and is never shared or sold."), ContentType.Text.Html)
        }

        // One-time device enrolment (ADR-005 M0). Disabled unless MEKA_ENROL_TOKEN is configured.
        post("/v1/enrol") {
            if (call.isPublisher()) throw Forbidden()
            call.requireEnrolCode(enrolToken)
            val req = WireCodec.decodeEnrolRequest(call.boundedBody())
            // Clean-ups (2026-10-19): a new phone or Mac waits for Allow on one already connected and in use.
            if (enrolApproval != null && withContext(Dispatchers.IO) { enrolApproval.needed(req) }) {
                val waiting = withContext(Dispatchers.IO) { enrolApproval.ask(req) }
                if (waiting == null) {
                    call.respondText(os.meka.core.wire.DeviceLinkCodec.encodeError(os.meka.core.wire.DeviceLinkCodec.ERR_BUSY), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                } else {
                    call.application.environment.log.info("device asked to join") // no identifiers in logs
                    call.respondText(os.meka.core.wire.DeviceJoinCodec.encodeWaiting(waiting), ContentType.Application.Json, HttpStatusCode.Accepted)
                }
                return@post
            }
            when (val r = withContext(Dispatchers.IO) { devices.enrolWithCode(req.householdId, req.deviceId, req.name) }) {
                is EnrolOutcome.Enrolled -> {
                    call.application.environment.log.info("device enrolled") // no identifiers in logs
                    call.respondText(WireCodec.encodeEnrolResponse(r.secret), ContentType.Application.Json)
                }
                is EnrolOutcome.Refused -> {
                    call.application.environment.log.warn("enrolment refused: ${r.reason}")
                    call.respondText(r.reason, status = HttpStatusCode.Forbidden)
                }
            }
        }

        if (enrolApproval != null) {
            // The asking device looks for Meka's answer with the same enrolment code; its secret comes once.
            post(os.meka.core.wire.DeviceJoinCodec.STATUS_PATH) {
                if (call.isPublisher()) throw Forbidden()
                call.requireEnrolCode(enrolToken)
                val r = withContext(Dispatchers.IO) { enrolApproval.status(call.boundedBody()) }
                if (r.body.contains("\"enrolled\"")) call.application.environment.log.info("device allowed and enrolled") // no identifiers in logs
                call.linkReply(r)
            }
            // Allow or Not this one, from a keyed phone or Mac of the household (Needs you's card).
            post(os.meka.core.wire.DeviceJoinCodec.ANSWER_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { enrolApproval.answer(who, body) })
            }
        }

        // Registers the device's hardware-bound signing key (ADR-005). The request must be signed with that very key
        // (proof of possession). After this, the device's unsigned requests are refused. Re-enrolment clears it.
        post("/v1/devices/key") {
            val body = call.boundedBody()
            val who = call.bearer(devices)
            val pub = WireCodec.decodeDeviceKey(body)
            if (!verifier.isP256(pub)) throw WireFormatException("not a P-256 key")
            val h = call.request.headers
            if (!verifier.verify(pub, "POST", call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])) throw Unauthorised()
            val stored = withContext(Dispatchers.IO) { devices.registerKey(who, pub) }
            if (!stored) throw Forbidden()
            call.application.environment.log.info("device key registered")
            call.respondText(WireCodec.encodeDeviceKey(pub), ContentType.Application.Json)
        }

        if (deviceLink != null) {
            // A watch asks for a code and later picks up its secret, both signed with its own new key (no bearer yet).
            post("/v1/link/start") {
                if (call.isPublisher()) throw Forbidden()
                val body = call.boundedBody()
                val h = call.request.headers
                call.linkReply(deviceLink.start(call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"]))
            }
            post("/v1/link/status") {
                if (call.isPublisher()) throw Forbidden()
                val body = call.boundedBody()
                val h = call.request.headers
                val r = deviceLink.status(call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])
                if (r.body.contains("\"linked\"")) call.application.environment.log.info("watch linked") // no identifiers in logs
                call.linkReply(r)
            }
            // Meka's side, from a keyed device: type the watch's code, list the watches, unlink one (Settings → Watch).
            post("/v1/devices/link") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { deviceLink.approve(who, body) })
            }
            post("/v1/devices/watches") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { deviceLink.watches(who) })
            }
            post("/v1/devices/unlink") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { deviceLink.unlink(who, body) })
            }
        }

        if (vaultFiles != null) {
            // The Vault's copies (slice 2b): sealed on the phone or Mac with the household's Vault key, so only
            // ciphertext arrives; kept and handed back by the copy's random id within the device's household. Keyed
            // phones and Macs only (never a watch or the publisher). Nothing about a copy is logged.
            post(os.meka.core.wire.VaultFileCodec.PUT_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { vaultFiles.put(who, body) })
            }
            post(os.meka.core.wire.VaultFileCodec.GET_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { vaultFiles.get(who, body) })
            }
            post(os.meka.core.wire.VaultFileCodec.DELETE_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { vaultFiles.delete(who, body) })
            }
        }

        if (recovery != null) {
            // A keyed phone or Mac saves a new key's verifier and reads the key's state (Settings → Recovery key).
            post("/v1/recovery/set") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val r = withContext(Dispatchers.IO) { recovery.set(who, body) }
                if (r.status == 200) call.application.environment.log.info("recovery key made") // no identifiers in logs
                call.linkReply(r)
            }
            post("/v1/recovery/status") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.linkReply(withContext(Dispatchers.IO) { recovery.status(who) })
            }
            // A new phone or Mac with the 24 words connects, signed with its own new key (no bearer yet).
            post("/v1/recovery/enrol") {
                if (call.isPublisher()) throw Forbidden()
                val body = call.boundedBody()
                val h = call.request.headers
                val r = withContext(Dispatchers.IO) {
                    recovery.enrol(call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])
                }
                if (r.status == 200) call.application.environment.log.info("device recovered") else call.application.environment.log.warn("recovery refused: ${r.status}")
                call.linkReply(r)
            }
        }

        if (family != null) {
            // his wife's page (family sharing, slice 2): static, self-contained, no personal data in it.
            get(FamilyShare.PAGE_PATH) { call.familyAsset("index.html", ContentType.Text.Html) }
            get("${FamilyShare.PAGE_PATH}/app.js") { call.familyAsset("app.js", ContentType.Application.JavaScript) }
            get("${FamilyShare.PAGE_PATH}/app.css") { call.familyAsset("app.css", ContentType.Text.CSS) }

            // First open: her browser registers its own key with the invite's token (signed with that key).
            post("${FamilyShare.PAGE_PATH}/v1/claim") {
                if (call.isPublisher()) throw Forbidden()
                val body = call.boundedBody()
                val h = call.request.headers
                val r = withContext(Dispatchers.IO) {
                    family.claim(call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])
                }
                if (r.status == 200) call.application.environment.log.info("family page joined") // no identifiers in logs
                call.familyReply(r)
            }
            post("${FamilyShare.PAGE_PATH}/v1/shopping") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.shopping(guest) })
            }
            post("${FamilyShare.PAGE_PATH}/v1/shopping/add") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.add(guest, body) })
            }
            post("${FamilyShare.PAGE_PATH}/v1/shopping/got") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.got(guest, body) })
            }
            post("${FamilyShare.PAGE_PATH}/v1/shopping/putback") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.putBack(guest, body) })
            }
            // Meal plan slice 3: the week's dinners and the favourites; she adds favourites.
            post("${FamilyShare.PAGE_PATH}/v1/meals") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.meals(guest) })
            }
            // Emergency card slice 1: read-only.
            post("${FamilyShare.PAGE_PATH}/v1/emergency") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.emergency(guest) })
            }
            post("${FamilyShare.PAGE_PATH}/v1/meals/add") {
                val body = call.boundedBody()
                val guest = call.familyGuest(family, body)
                call.familyReply(withContext(Dispatchers.IO) { family.addMeal(guest, body) })
            }

            // Meka's side, from a keyed device: make, list and revoke invites (the apps' Settings → Family, slice 4).
            post("/v1/family/invites/create") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.familyReply(withContext(Dispatchers.IO) { family.invite(who.householdId, body) })
            }
            post("/v1/family/invites/list") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.familyReply(withContext(Dispatchers.IO) { family.list(who.householdId) })
            }
            post("/v1/family/invites/revoke") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                call.familyReply(withContext(Dispatchers.IO) { family.revoke(who.householdId, body) })
            }
        }

        post("/v1/sync/push") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePushRequest(body)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.push(req) } // blocking JDBC off the request threads
            // Wake the household's other devices so they pull now (coalesced; sent off the request).
            if (push != null && resp.acknowledged.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { push.changed(who) } }
            if (calendarWriter != null && req.ops.any { it.entityType == EntityTypes.EVENT_EDIT }) calendarWriter.poke()
            // Delete recording in a call's detail, or a Done from an app older than 2026-10-20 (which blanked the
            // words): those recordings are deleted now. A newer Done keeps them for Activity → Calls (polish 8c, Meka 17:34).
            if (voice != null) {
                val dismissed = req.ops.filter {
                    it.entityType == EntityTypes.HELD_MESSAGE && it.value == FieldValue.Bool(true) && it.opId in resp.acknowledged &&
                        (it.field == HeldMessageFields.CLEARED || it.field == HeldMessageFields.RECORDING_DELETED)
                }.map { it.entityId }.distinct()
                if (dismissed.isNotEmpty()) background { voice.assistant.forgetGoneRecordings(who.householdId, dismissed) }
                // "Keep callers' recordings" changed: what is now too old goes at once, not at the next hourly sweep.
                if (req.ops.any { it.entityType == EntityTypes.CONTEXT_MODE && it.field == WorkFields.RECORDING_DAYS && it.opId in resp.acknowledged }) {
                    background { runCatching { voice.assistant.sweepRecordings(who.householdId) } }
                }
            }
            call.respondText(WireCodec.encodePushResponse(resp), ContentType.Application.Json)
        }

        post("/v1/sync/pull") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePullRequest(body)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.pull(req) }
            call.respondText(WireCodec.encodePullResponse(resp), ContentType.Application.Json)
        }

        if (integrations != null) {
            // Starts connecting an account: returns the provider's sign-in URL for the app to open in a browser.
            post("/v1/integrations/{provider}/connect") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val provider = call.parameters["provider"].orEmpty()
                // Allow editing (calendar editing) asks for the write permission too; an empty body is read-only.
                val editing = WireCodec.decodeConnectRequest(body)
                when (val r = withContext(Dispatchers.IO) { integrations.start(who.householdId, provider, editing) }) {
                    is Integrations.StartResult.Url -> call.respondText(WireCodec.encodeConnectUrl(r.url), ContentType.Application.Json)
                    Integrations.StartResult.UnknownProvider -> call.respondText("unknown provider", status = HttpStatusCode.NotFound)
                    Integrations.StartResult.NotConfigured -> call.respondText("not configured", status = HttpStatusCode.Conflict)
                }
            }

            // Stop editing on one account (calendar editing). Only giving editing up: allowing it needs the provider's consent.
            post("/v1/integrations/{provider}/editing") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val change = WireCodec.decodeEditingChange(body)
                if (change.editing) call.respondText("allow editing through connect", status = HttpStatusCode.BadRequest)
                else {
                    val provider = call.parameters["provider"].orEmpty()
                    val found = withContext(Dispatchers.IO) { integrations.stopEditing(who.householdId, provider, change.email) }
                    if (!found) call.respondText("no such account", status = HttpStatusCode.NotFound)
                    else call.respondText(WireCodec.encodeAccounts(withContext(Dispatchers.IO) { integrations.accounts(who.householdId) }), ContentType.Application.Json)
                }
            }

            // Names what a mail account is for (Inbox autopilot, slice 1): Personal · Business · Outlook.
            post("/v1/integrations/{provider}/label") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val change = WireCodec.decodeMailLabelChange(body)
                val provider = call.parameters["provider"].orEmpty()
                val found = withContext(Dispatchers.IO) { integrations.setMailLabel(who.householdId, provider, change.email, change.label) }
                if (!found) call.respondText("no such mail account", status = HttpStatusCode.NotFound)
                else call.respondText(WireCodec.encodeAccounts(withContext(Dispatchers.IO) { integrations.accounts(who.householdId) }), ContentType.Application.Json)
            }

            // Disconnects a mail account (Inbox autopilot, slice 1b): the server forgets its sign-in; the mailbox is untouched.
            post("/v1/integrations/{provider}/disconnect") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val req = WireCodec.decodeMailDisconnect(body)
                val provider = call.parameters["provider"].orEmpty()
                val found = withContext(Dispatchers.IO) { integrations.disconnectMail(who.householdId, provider, req.email, req.deleteData) }
                if (!found) call.respondText("no such mail account", status = HttpStatusCode.NotFound)
                else call.respondText(WireCodec.encodeAccounts(withContext(Dispatchers.IO) { integrations.accounts(who.householdId) }), ContentType.Application.Json)
            }

            post("/v1/integrations/list") {
                val who = call.device(devices, verifier, call.boundedBody(), requireKey = true)
                val list = withContext(Dispatchers.IO) { integrations.accounts(who.householdId) }
                call.respondText(WireCodec.encodeAccounts(list), ContentType.Application.Json)
            }

            // Read the full email inside Navilife (2026-10-20): one email by Navilife's own handle, read from the provider
            // now and handed back made safe. Keyed devices only, never the publisher; nothing of it is kept or logged.
            post(os.meka.core.wire.MailMessageCodec.PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
                val request = os.meka.core.wire.MailMessageCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { integrations.readMailMessage(who.householdId, request) }
                call.respondText(os.meka.core.wire.MailMessageCodec.encodeResponse(answer), ContentType.Application.Json)
            }

            // Read the full email, slice 2: one attachment's bytes, fetched from the provider at his tap by the email's
            // handle and the file's place and name. Keyed devices only, never the publisher; nothing kept or logged.
            post(os.meka.core.wire.MailMessageCodec.ATTACHMENT_PATH) {
                val codec = os.meka.core.wire.MailMessageCodec
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
                val request = codec.decodeAttachmentRequest(body)
                val got = withContext(Dispatchers.IO) { integrations.readMailAttachment(who.householdId, request) }
                call.response.header("Cache-Control", "no-store")
                val bytes = got.bytes
                when {
                    got.state == os.meka.core.domain.MailMessageAnswer.OK && bytes != null ->
                        call.respondBytes(bytes, ContentType.parse(codec.ATTACHMENT_TYPE))
                    got.state == os.meka.core.domain.MailMessageAnswer.GONE || got.state == os.meka.core.domain.MailMessageAnswer.UNKNOWN ->
                        call.respondText("gone", status = HttpStatusCode.fromValue(codec.STATUS_GONE))
                    got.state == os.meka.core.domain.MailMessageAnswer.RECONNECT ->
                        call.respondText("reconnect", status = HttpStatusCode.fromValue(codec.STATUS_RECONNECT))
                    got.state == os.meka.core.domain.MailAttachmentRules.TOO_BIG ->
                        call.respondText("too big", status = HttpStatusCode.fromValue(codec.STATUS_TOO_BIG))
                    got.state == os.meka.core.domain.MailAttachmentRules.REFUSED ->
                        call.respondText("refused", status = HttpStatusCode.fromValue(codec.STATUS_REFUSED))
                    else -> call.respondText("failed", status = HttpStatusCode.fromValue(codec.STATUS_FAILED))
                }
            }

            // The provider redirects the owner's browser here. Authenticated by the single-use state, not a device.
            get("/v1/oauth/{provider}/callback") {
                val provider = call.parameters["provider"].orEmpty()
                val q = call.request.queryParameters
                val result = withContext(Dispatchers.IO) {
                    runCatching { integrations.callback(provider, q["state"], q["code"], q["error"]) }
                        .getOrElse { Integrations.CallbackResult.Failed("The provider did not complete sign-in. Please try again.") }
                }
                val page = when (result) {
                    is Integrations.CallbackResult.Connected -> {
                        background { runCatching { integrations.syncAccount(result.accountId) } }
                        resultPage("Connected", connectedText(result))
                    }
                    is Integrations.CallbackResult.Failed -> resultPage("Not connected", result.reason)
                }
                call.respondText(page, ContentType.Text.Html)
            }
        }

        if (releases != null) {
            // Self-updating phone app: the newest published build, its chunks, and publishing one (from the Mac, or
            // from GitHub's release-only publisher, which may call only latest and upload). Signed requests only.
            post("/v1/releases/latest") {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                val platform = WireCodec.decodePlatform(body)
                val latest = withContext(Dispatchers.IO) { releases.latest(who, platform) }
                call.respondText(WireCodec.encodeRelease(latest), ContentType.Application.Json)
            }

            post("/v1/releases/chunk") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val ref = WireCodec.decodeChunkRef(body)
                val bytes = withContext(Dispatchers.IO) { releases.chunk(who, ref) }
                    ?: return@post call.respondText("no such build", status = HttpStatusCode.NotFound)
                call.respondText(WireCodec.encodeChunkData(java.util.Base64.getEncoder().encodeToString(bytes)), ContentType.Application.Json)
            }

            post("/v1/releases/upload") {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                val chunk = WireCodec.decodeReleaseChunk(body)
                when (val r = withContext(Dispatchers.IO) { releases.upload(who, chunk) }) {
                    is Releases.Upload.Ack -> {
                        if (r.complete) call.application.environment.log.info(if (who.deviceId == ReleasePublisher.ID) "app build published by GitHub" else "app build published") // no identifiers
                        call.respondText(WireCodec.encodeUploadAck(WireCodec.UploadAck(r.received, r.complete)), ContentType.Application.Json)
                    }
                    is Releases.Upload.Conflict -> call.respondText(r.reason, status = HttpStatusCode.Conflict)
                    is Releases.Upload.Rejected -> call.respondText(r.reason, status = HttpStatusCode.BadRequest)
                }
            }
        }

        if (releases != null && updateFailures != null) {
            // Why a phone update didn't go in: the Fold reports one (keyed devices only; the publisher is refused), and
            // the publisher (or a keyed device) reads the newest few so the publish job can print them for a build run.
            post(UpdateFailureCodec.REPORT_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val report = UpdateFailureCodec.decodeReport(body)
                withContext(Dispatchers.IO) { updateFailures.report(who, report) }
                call.application.environment.log.info("phone update failure reported") // no identifiers
                call.respondText("{}", ContentType.Application.Json)
            }

            post(UpdateFailureCodec.LIST_PATH) {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                UpdateFailureCodec.decodeListRequest(body)
                val list = withContext(Dispatchers.IO) { updateFailures.recent(who) }
                call.respondText(UpdateFailureCodec.encodeList(list), ContentType.Application.Json)
            }
        }

        if (foldPostures != null) {
            // What the Fold reports about its hinge: the Fold sends its last changes (keyed devices only; the publisher
            // is refused), and the publisher (or a keyed device) reads the newest report for a build run.
            post(FoldPostureCodec.REPORT_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val report = FoldPostureCodec.decodeReport(body)
                withContext(Dispatchers.IO) { foldPostures.report(who, report) }
                call.respondText("{}", ContentType.Application.Json)
            }

            post(FoldPostureCodec.LATEST_PATH) {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                FoldPostureCodec.decodeLatestRequest(body)
                val latest = withContext(Dispatchers.IO) { foldPostures.latest(who) }
                call.respondText(FoldPostureCodec.encodeLatest(latest), ContentType.Application.Json)
            }
        }

        if (notifyReports != null) {
            // Notifications never seen (slice 2): the phone sends its trace (keyed devices only; the publisher is
            // refused), and the publisher (or a keyed device) reads the newest report for a build run.
            post(NotifyDiagnosticsCodec.REPORT_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val report = NotifyDiagnosticsCodec.decodeReport(body)
                withContext(Dispatchers.IO) { notifyReports.report(who, report) }
                call.respondText("{}", ContentType.Application.Json)
            }

            post(NotifyDiagnosticsCodec.LATEST_PATH) {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                NotifyDiagnosticsCodec.decodeEmptyRequest(body)
                val latest = withContext(Dispatchers.IO) { notifyReports.latest(who) }
                call.respondText(NotifyDiagnosticsCodec.encodeLatest(latest), ContentType.Application.Json)
            }
        }

        // Send a test (notifications slice 2): a test push to the very device that asked, answered with what Firebase
        // said. Keyed devices only; the publisher is refused (it has no push address).
        post(NotifyDiagnosticsCodec.TEST_PATH) {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body, requireKey = true)
            NotifyDiagnosticsCodec.decodeEmptyRequest(body)
            val word = if (push == null) NotifyDiagnosticsCodec.OFF else {
                withContext(Dispatchers.IO) { push.test(who) }?.let { NotifyReports.word(it) } ?: NotifyDiagnosticsCodec.NO_ADDRESS
            }
            call.application.environment.log.info("push test: $word") // no identifiers
            call.respondText(NotifyDiagnosticsCodec.encodeTestAnswer(word), ContentType.Application.Json)
        }

        if (newsImages != null) {
            // A news picture by the server's key (never an address). Keyed devices only; the publisher is refused.
            post("/v1/news/image") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val key = WireCodec.decodeNewsImageRef(body)
                val bytes = withContext(Dispatchers.IO) { newsImages.get(key) }
                    ?: return@post call.respondText("no such picture", status = HttpStatusCode.NotFound)
                call.respondText(WireCodec.encodeChunkData(java.util.Base64.getEncoder().encodeToString(bytes)), ContentType.Application.Json)
            }
        }

        if (ai != null) {
            // Whether MEKA's AI is on (key set and accepted), never the key. Keyed devices only; the publisher is refused.
            post("/v1/ai/status") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val status = withContext(Dispatchers.IO) { ai.statusJson() }
                call.respondText(status.toString(), ContentType.Application.Json)
            }
            // Ask MEKA: a question with the device's picture of today; words and proposed actions back. Nothing is kept.
            // Voice completeness, slice 3: a spoken answer brings its first piece's clip in the chosen voice.
            val askService = AskService(ai.provider, speech?.let { sp ->
                { text: String, voice: String? ->
                    val said = sp.say(SpeechCodec.Request(text, voice))
                    said.mp3?.takeIf { said.response.state == SpeechCodec.Response.SPOKEN }?.let { AskService.FirstClip(it, said.response.voice) }
                }
            }, budgetLevel = { ai.budget?.state()?.level }, live = liveFacts?.let { lf -> { q: String -> lf.look(q) } })
            post("/v1/ai/ask") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val request = AskCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { askService.ask(request) { t -> talkSpeed?.record(who.householdId, t) } }
                call.respondText(AskCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // The companion, slice 9: the same question, answered in lines as they're ready: the first sentence said
            // while the model is still writing, then the whole answer. Nothing is kept or logged.
            post(AskCodec.STREAM_PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val request = AskCodec.decodeRequest(body)
                val lines = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                call.respondTextWriter(ContentType("application", "x-ndjson")) {
                    kotlinx.coroutines.coroutineScope {
                        launch(Dispatchers.IO) {
                            val answer = try {
                                askService.askStream(request, timed = { t -> talkSpeed?.record(who.householdId, t) }, next = { n -> lines.trySend(AskCodec.encodeStreamNext(n)) }) { first -> lines.trySend(AskCodec.encodeStreamFirst(first)) }
                            } catch (e: Exception) {
                                AskCodec.Response(AskCodec.Response.FAILED, reason = "Navilife's AI couldn't answer")
                            }
                            lines.trySend(AskCodec.encodeResponse(answer))
                            lines.close()
                        }
                        for (line in lines) {
                            write(line)
                            write("\n")
                            flush()
                        }
                    }
                }
            }
            // Requests from people Meka watches: one message's text, the sender's label and the time; proposals back.
            // Nothing is kept or logged.
            val requestService = MessageRequestService(ai.provider)
            post("/v1/ai/message-request") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = MessageRequestCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { requestService.read(request) }
                call.respondText(MessageRequestCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // "Handle this": one piece of text Meka shared with MEKA (and its subject); proposals back. Nothing is kept or
            // logged.
            val handleService = HandleThisService(ai.provider)
            post("/v1/ai/handle-this") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = HandleThisCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { handleService.read(request) }
                call.respondText(MessageRequestCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // Voice notes into actions, slice 2: one voice note's words (never its recording); a tidy note, the people it
            // mentions and proposals back. Nothing is kept or logged.
            val voiceNoteService = VoiceNoteService(ai.provider)
            post("/v1/ai/voice-note") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = VoiceNoteCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { voiceNoteService.tidy(request) }
                call.respondText(VoiceNoteCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // "Break it down": one goal in Meka's own words; proposed milestones and first steps back. Nothing is kept or
            // logged.
            val breakdownService = GoalBreakdownService(ai.provider)
            post("/v1/ai/goal-breakdown") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = GoalBreakdownCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { breakdownService.breakDown(request) }
                call.respondText(GoalBreakdownCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // The morning brief written by the AI: the brief's facts out, the spoken text back. Nothing is kept or logged.
            val briefScriptService = BriefScriptService(ai.provider)
            post("/v1/ai/brief-script") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = BriefScriptCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { briefScriptService.write(request) }
                call.respondText(BriefScriptCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // The messages assistant: one message (1:1, or a group message naming Meka); a lane, gist, draft or proposals
            // back. Nothing is kept or logged.
            val triageService = MessageTriageService(ai.provider)
            post("/v1/ai/message-triage") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = MessageTriageCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { triageService.triage(request) }
                call.respondText(MessageTriageCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // The group digest's gist: the busy groups' latest lines at a digest time; a gist per group and asks back.
            // Nothing is kept or logged.
            val digestService = GroupDigestService(ai.provider)
            post("/v1/ai/group-digest") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = GroupDigestCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { digestService.digest(request) }
                call.respondText(GroupDigestCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // A busy chat's summary (messages grouped per chat, slice 3c): the busy chats' names and MEKA's gists of their
            // messages; one line per chat back. Nothing is kept or logged.
            val chatSummaryService = ChatSummaryService(ai.provider)
            post("/v1/ai/chat-summary") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = ChatSummaryCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { chatSummaryService.summarise(request) }
                call.respondText(ChatSummaryCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // Fresh hello openers (the orb says hello like a person, slice 4): how many per part of the day out, nothing
            // about him; openers with "{n}" for his name back, checked on the device. Nothing is kept or logged.
            val helloOpenersService = HelloOpenersService(ai.provider)
            post(HelloOpenersCodec.PATH) {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = HelloOpenersCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { helloOpenersService.write(request) }
                call.respondText(HelloOpenersCodec.encodeResponse(answer), ContentType.Application.Json)
            }
            // A reply drafted by the AI (Inbox autopilot, slice 6b-2b): one email as its card shows it and a few of his own
            // earlier replies out, the draft back for his editor (sent only on his tap). Nothing is kept or logged.
            val mailDraftService = MailDraftService(ai.provider)
            post("/v1/ai/mail-draft") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = MailDraftCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { mailDraftService.draft(request) }
                call.respondText(MailDraftCodec.encodeResponse(answer), ContentType.Application.Json)
            }
        }

        if (speech != null) {
            // MEKA's voice: the British voices this server can speak with and the month's characters. Keyed devices only.
            post("/v1/speech/voices") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                SpeechCodec.decodeVoicesRequest(body)
                val voices = withContext(Dispatchers.IO) { speech.voices() }
                call.respondText(SpeechCodec.encodeVoices(voices), ContentType.Application.Json)
            }
            // A piece of MEKA's own reply said aloud: raw MP3 when the device asks for it (voice slice 2), else base64 in
            // JSON; refusals are always JSON. The text is neither stored nor logged; only counted.
            post("/v1/speech/speak") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = SpeechCodec.decodeRequest(body)
                val said = withContext(Dispatchers.IO) { speech.say(request) }
                val mp3 = said.mp3
                if (mp3 != null && request.binary) {
                    said.response.voice?.let { call.response.header(SpeechCodec.HEADER_VOICE, it) }
                    said.response.engine?.let { call.response.header(SpeechCodec.HEADER_ENGINE, it) }
                    call.respondBytes(mp3, ContentType.parse(SpeechCodec.AUDIO_TYPE))
                } else {
                    val spoken = mp3?.let { said.response.copy(audio = java.util.Base64.getEncoder().encodeToString(it)) } ?: said.response
                    call.respondText(SpeechCodec.encodeResponse(spoken), ContentType.Application.Json)
                }
            }
        }

        if (talkSpeed != null) {
            // Talking to Ada like a conversation, slice 2: the household's last day of answer timings (times only), for
            // Speed on the devices and the deploy report (the publisher may read it too).
            post(ServerSpeedCodec.PATH) {
                val body = call.boundedBody()
                val who = call.releaseCaller(devices, verifier, body, publisher)
                ServerSpeedCodec.decodeRequest(body)
                val turns = withContext(Dispatchers.IO) { talkSpeed.recent(who) }
                call.respondText(ServerSpeedCodec.encodeResponse(turns), ContentType.Application.Json)
            }
        }

        if (ai?.provider != null || speech != null) {
            // The orb speed pass, slice 5: "he's about to talk" (the orb tapped, Talk opened, listening started): the
            // server opens its connections to the AI and to Navilife's voice with free requests, off the request path.
            // The request carries nothing but the version; nothing is kept or logged. Keyed devices only.
            val talkWarm = TalkWarm(ai?.provider?.let { p -> { p.touch() } }, speech?.let { sp -> { sp.touch() } })
            post("/v1/talk/warm") {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                TalkWarmCodec.decodeRequest(body)
                call.respondText(TalkWarmCodec.encodeResponse(talkWarm.request()), ContentType.Application.Json)
            }
        }

        if (here != null) {
            // "Where I am now": a rounded point (about 1 km; anything finer is refused) in, that point's forecast and
            // whether it is away from home and work out. Keyed devices only. The point is neither stored nor logged.
            post("/v1/weather/here") {
                val body = call.boundedBody()
                val caller = call.device(devices, verifier, body, requireKey = true)
                val request = HereCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { here.at(caller.householdId, request) }
                call.respondText(HereCodec.encodeResponse(answer), ContentType.Application.Json)
            }
        }

        if (fuel != null) {
            // Cheapest fuel nearby: a rounded point (about 1 km; anything finer is refused) and a fuel in, the cheapest
            // forecourts near it out, from the server's copy of Fuel Finder. Keyed devices only. The point is neither
            // stored nor logged.
            post(FuelCodec.PATH) {
                val body = call.boundedBody()
                call.device(devices, verifier, body, requireKey = true)
                val request = FuelCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { fuel.near(request) }
                call.respondText(FuelCodec.encodeResponse(answer), ContentType.Application.Json)
            }
        }

        if (numberLookup != null) {
            // Spam-call lookup: one number in (a caller the Fold doesn't know), what the web's report sites say about it
            // out, from the 30-day cache or one search. Keyed devices only, never the publisher. The number is never logged.
            post(NumberLookupCodec.PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
                val request = NumberLookupCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { numberLookup.lookUp(who.householdId, request.number, request.waitMs) }
                call.respondText(NumberLookupCodec.encodeResponse(answer), ContentType.Application.Json)
            }
        }

        if (tradingExports != null) {
            // Trading tracker, slice 8: a tax year's perp orders and daily funding for every followed account, read from
            // Hyperliquid's public data (public address only) in the background and kept in memory a few minutes.
            // Keyed devices only, never the publisher. No address or figure is logged.
            post(TradingExportCodec.PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
                val request = TradingExportCodec.decodeRequest(body)
                val answer = withContext(Dispatchers.IO) { tradingExports.export(who.householdId, request) }
                call.respondText(TradingExportCodec.encodeResponse(answer), ContentType.Application.Json)
            }
        }

        if (push != null) {
            // A device's push address (an FCM token), or an empty token to stop waking it. Keyed devices only.
            post("/v1/push/token") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val t = WireCodec.decodePushToken(body)
                withContext(Dispatchers.IO) { push.register(who, t) }
                call.application.environment.log.info(if (t.token.isEmpty()) "push address removed" else "push address registered") // no identifiers
                call.respondText(WireCodec.encodePushToken(t.copy(token = "")), ContentType.Application.Json)
            }
        }

        if (approvals != null) {
            // Approval requests from an asker outside MEKA (Kestrel): known by its public key alone, signing this exact
            // request; it may call only these two routes, for the server's one household. Answers stay in the apps.
            for (path in ApprovalDesk.ROUTES) post(path) {
                val body = call.boundedBody()
                val asker = call.asker(approvals, verifier, body)
                val household = withContext(Dispatchers.IO) { devices.soleHousehold() } ?: throw Forbidden()
                val reply = withContext(Dispatchers.IO) {
                    when (path) {
                        ApprovalDesk.REQUEST_PATH -> approvals.desk.request(household, asker, body)
                        ApprovalDesk.STATUS_PATH -> approvals.desk.status(household, asker, body)
                        else -> approvals.desk.keys(household)
                    }
                }
                when (reply) {
                    is ApprovalDesk.Reply.Ok -> call.respondText(reply.json, ContentType.Application.Json)
                    is ApprovalDesk.Reply.Refused -> call.respondText(reply.reason, status = HttpStatusCode.fromValue(reply.status))
                }
            }
        }

        if (voice != null) {
            // The call assistant's phone service (Twilio): form posts signed by the service over the exact public URL.
            // No device or publisher may call these; an unsigned or wrongly signed request is refused.
            post("/v1/voice/{provider}/{step}") {
                if (call.isPublisher()) throw Forbidden()
                val body = call.boundedBody()
                val provider = voice.providers[call.parameters["provider"].orEmpty()]
                    ?: return@post call.respondText("unknown provider", status = HttpStatusCode.NotFound)
                val form = runCatching { parseForm(body) }.getOrElse { throw WireFormatException("bad form") }
                val url = voice.publicUrl.trimEnd('/') + call.request.path()
                if (!provider.verify(url, form) { call.request.header(it) }) throw Forbidden()
                val event = provider.parse(call.parameters["step"].orEmpty(), form)
                    ?: return@post call.respondText("unknown step", status = HttpStatusCode.NotFound)
                val reply = withContext(Dispatchers.IO) { voice.assistant.handle(provider.id, event) }
                // Transcribed (or failed): keep the recording in MEKA's own bucket (polish 8c), then delete the phone
                // service's copy, off the request (in that order, so it is fetched before it goes).
                if (event is VoiceEvent.Transcribed) event.recording?.let { rec ->
                    val log = call.application.environment.log
                    background {
                        val kept = runCatching { voice.assistant.keepRecording(provider, event) }
                            .onFailure { log.warn("recording not kept: ${it::class.simpleName}") } // no identifiers
                            .getOrDefault(false)
                        // Amazon Transcribe on the kept recording, Twilio's words the fallback (8d i).
                        runCatching { voice.assistant.transcribeKept(provider, event, kept) }
                            .onFailure { log.warn("transcript not settled: ${it::class.simpleName}") }
                        // "Don't keep": the recording was held only for Transcribe.
                        runCatching { voice.assistant.releaseUnkept(provider, event) }
                        runCatching { provider.deleteRecording(rec) }
                    }
                }
                if (event is VoiceEvent.Recorded && reply is VoiceReply.AskUrgent) call.application.environment.log.info("voice message taken") // no identifiers
                val callVoice = if (reply == VoiceReply.Done || reply == VoiceReply.Busy) CallVoice.DEFAULT else withContext(Dispatchers.IO) { voice.assistant.voice() }
                val clips = voice.clips
                val (type, text) = if (clips == null) provider.render(reply, voice.publicUrl, callVoice)
                else provider.render(reply, voice.publicUrl, callVoice) { g, line -> clips.path(g, line) }
                call.respondText(text, ContentType.parse(type))
            }
            // The call assistant's lines in a chosen Google voice (natural voice, slice 3), fetched by the phone service
            // for <Play>. Only clips this server made of its fixed lines, under an unguessable key; anything else is 404.
            get("${CallClips.PATH}{file}") {
                val mp3 = voice.clips?.audio(call.parameters["file"].orEmpty())
                    ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
                call.respondBytes(mp3, ContentType.parse("audio/mpeg"))
            }
        }

        if (voice != null) {
            // A caller's recording (polish 8c): raw MP3 for a keyed device of the household, only while the message is
            // in the summary and under 30 days old; 404 otherwise. The id is the only thing sent; nothing is logged.
            post(VoiceMessageCodec.PATH) {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val id = VoiceMessageCodec.decodeRequest(body)
                val audio = withContext(Dispatchers.IO) { voice.assistant.recording(who.householdId, id) }
                    ?: return@post call.respondText("no recording", status = HttpStatusCode.NotFound)
                call.response.header("Cache-Control", "no-store")
                call.respondBytes(audio, ContentType.parse(VoiceMessageCodec.AUDIO_TYPE))
            }
        }

        // The Health screen's server half (Reliability first, item 3): what only the server knows, for the signed-in
        // device. Never a secret, token or address; keyed devices only, the publisher is refused.
        post("/v1/health/household") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body, requireKey = true)
            HealthCodec.decodeRequest(body)
            val pushState = when {
                push == null -> HealthCodec.Response.PUSH_OFF
                withContext(Dispatchers.IO) { push.hasAddress(who) } -> HealthCodec.Response.PUSH_ON
                else -> HealthCodec.Response.PUSH_MISSING
            }
            val macs = withContext(Dispatchers.IO) { runCatching { devices.macs(who.householdId) }.getOrNull() }
            val lastPush = push?.lastResult(who)
            val health = HealthCodec.Response(
                pushState, calls = voice != null, speech = speech != null, atMs = System.currentTimeMillis(), macs = macs,
                lastPushAtMs = lastPush?.first, lastPushResult = lastPush?.second?.let { NotifyReports.word(it) },
                speechGoogle = speech?.let { withContext(Dispatchers.IO) { it.googleState() } },
                speechGoogleFailedAtMs = speech?.googleFailedAtMs,
                speechGooglePollyForMs = speech?.googleSwitchedForMs(),
                speechGooglePollyNow = speech?.googleSwitched() == true,
            )
            call.respondText(HealthCodec.encodeResponse(health), ContentType.Application.Json)
        }

        // Long-poll for an open app: answers as soon as the household has ops after the cursor, else empty after
        // [waitMs]. Checks the database each [waitCheckMs] (an indexed query), so it stays correct with several tasks.
        post("/v1/sync/wait") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePullRequest(body).copy(limit = 1)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val deadline = System.currentTimeMillis() + waitMs
            var page = withContext(Dispatchers.IO) { sync.pull(req) }
            while (page.ops.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(waitCheckMs)
                page = withContext(Dispatchers.IO) { sync.pull(req) }
            }
            call.respondText(WireCodec.encodePullResponse(page), ContentType.Application.Json)
        }
    }
}

private const val PRIVACY_TEXT =
    "Navilife is a private app used only by its owner's household. When you connect a Google or Microsoft account, " +
        "Navilife reads your calendar events so they can appear in your own Navilife apps. It changes your calendar only " +
        "if you choose Allow editing for that account, and then only to add, move, edit or delete events you ask it to " +
        "from your own Navilife apps; Stop editing turns that off at once. Event details are " +
        "stored encrypted in the owner's own cloud account and on the owner's devices, are never sold or shared with " +
        "anyone, and are not used for advertising or to train AI models. Sign-in tokens are encrypted with a key only " +
        "this service can use. You can disconnect at any time from your Google or Microsoft account settings, after " +
        "which no further data is read. Use of information received from Google APIs adheres to the Google API Services " +
        "User Data Policy, including the Limited Use requirements."

/** What the browser says after a sign-in completed (calendar editing: whether editing is now on). */
internal fun connectedText(r: Integrations.CallbackResult.Connected): String {
    val who = "${providerName(r.provider)} (${r.email})"
    return when {
        MailProviders.isMail(r.provider) ->
            "$who is connected. Navilife reads and tidies it once sorting arrives in an update, and sends only when you tap Send. You can close this page."
        r.canEdit -> "$who is connected, and Navilife may now add and change events in it when you do so in the app. You can close this page."
        r.editingAsked -> "$who is connected, but read-only: editing wasn't allowed. To allow it, tap Allow editing again and keep the calendar permission ticked. You can close this page."
        else -> "$who is connected. Your calendar will appear in Navilife within a minute. You can close this page."
    }
}

private fun providerName(p: String) = when (p) { "google" -> "Google"; "microsoft" -> "Microsoft"; else -> MailProviders.name(p) }

private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Plain, self-contained page shown in the browser after the provider's sign-in. */
private fun resultPage(title: String, message: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Navilife · ${html(title)}</title>
<style>:root{color-scheme:light dark}body{font:17px/1.5 -apple-system,system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;padding:24px;box-sizing:border-box}
main{max-width:30rem}h1{font-size:1.5rem;margin:0 0 .5rem}p{margin:0;opacity:.8}</style></head>
<body><main><h1>${html(title)}</h1><p>${html(message)}</p></main></body></html>"""

/** The family page's files, read once from the classpath, sent with a strict policy: only this server's own script. */
private val familyAssets = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

private suspend fun ApplicationCall.familyAsset(name: String, type: ContentType) {
    val bytes = familyAssets.getOrPut(name) { FamilyShare::class.java.getResource("/family/$name")!!.readBytes() }
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; manifest-src 'self'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
    response.header("Cache-Control", "no-cache")
    respondBytes(bytes, type)
}

private suspend fun ApplicationCall.familyReply(r: FamilyReply) {
    response.header("Cache-Control", "no-store")
    respondText(r.body, ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
}

private suspend fun ApplicationCall.linkReply(r: LinkReply) {
    response.header("Cache-Control", "no-store")
    respondText(r.body, ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
}

/** Her browser: `Authorization: Guest <invite id>` and a signature by the key it registered, over this exact request. */
private suspend fun ApplicationCall.familyGuest(family: FamilyShare, body: String): FamilyInvite {
    if (isPublisher()) throw Forbidden()
    val id = request.header("Authorization")?.takeIf { it.startsWith("Guest ") }?.removePrefix("Guest ")?.trim()
    val h = request.headers
    return withContext(Dispatchers.IO) {
        family.guest(id, request.httpMethod.value, request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])
    } ?: throw Unauthorised()
}

private fun ApplicationCall.isPublisher(): Boolean = request.header("Authorization")?.startsWith("${ReleasePublisher.AUTH_SCHEME} ") == true

/** The enrolment code in `Authorization: Enrol <code>`; refused when none is configured or it doesn't match. */
private fun ApplicationCall.requireEnrolCode(enrolToken: String?) {
    val token = enrolToken?.takeIf { it.length >= 32 } ?: throw Unauthorised()
    val auth = request.header("Authorization") ?: throw Unauthorised()
    if (!auth.startsWith("Enrol ") || !Secrets.constantTimeEquals(auth.removePrefix("Enrol ").trim(), token)) throw Unauthorised()
}

/** The enrol route's approval step, reading an ask's heads by index on Postgres. */
internal fun enrolApprovalFor(devices: DeviceRegistry, opStore: ServerOpStore, push: Push?): EnrolApproval {
    val onAsked: (String) -> Unit = { hh -> push?.urgent(hh) }
    val pg = opStore as? PostgresOpStore ?: return EnrolApproval(devices, opStore, onAsked = onAsked)
    return EnrolApproval(devices, opStore, onAsked = onAsked, heads = { hh, id ->
        pg.latestFieldOps(hh, os.meka.core.domain.EntityTypes.DEVICE_JOIN, id)[id].orEmpty()
    })
}

private fun ApplicationCall.bearer(devices: DeviceRegistry): DeviceIdentity {
    if (isPublisher()) throw Forbidden() // the release-only publisher may call nothing that takes a device
    if (request.header("Authorization")?.startsWith("${ApprovalDesk.AUTH_SCHEME} ") == true) throw Forbidden() // nor may an asker
    val auth = request.header("Authorization") ?: throw Unauthorised()
    if (!auth.startsWith("Bearer ")) throw Unauthorised()
    return devices.authenticate(auth.removePrefix("Bearer ").trim()) ?: throw Unauthorised()
}

/**
 * Authenticates a device: its bearer secret, plus — once it has registered a hardware key — a valid signature over
 * this exact request. [requireKey] refuses devices that have no key yet (routes that expose personal data).
 */
private suspend fun ApplicationCall.device(devices: DeviceRegistry, verifier: RequestVerifier, body: String, requireKey: Boolean = false): DeviceIdentity {
    val who = bearer(devices)
    val key = withContext(Dispatchers.IO) { devices.publicKey(who) }
    if (key == null) {
        if (requireKey) throw Forbidden()
        return who
    }
    val h = request.headers
    if (!verifier.verify(key, request.httpMethod.value, request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])) {
        throw Unauthorised()
    }
    return who
}

/**
 * A release route's caller: a keyed household device, or the release-only publisher (GitHub build). The publisher is
 * known by its public key alone (no bearer secret), must sign this exact request, may call only
 * [ReleasePublisher.ROUTES], and publishes for the server's one household.
 */
private suspend fun ApplicationCall.releaseCaller(devices: DeviceRegistry, verifier: RequestVerifier, body: String, publisher: PublisherKeySource?): DeviceIdentity {
    if (!isPublisher()) return device(devices, verifier, body, requireKey = true)
    val name = request.header("Authorization").orEmpty().removePrefix("${ReleasePublisher.AUTH_SCHEME} ").trim()
    if (name != ReleasePublisher.ID) throw Unauthorised()
    if (request.path() !in ReleasePublisher.ROUTES) throw Forbidden()
    publisher ?: throw Forbidden()
    val h = request.headers
    val method = request.httpMethod.value
    val path = request.path()
    val ok = withContext(Dispatchers.IO) {
        publisher.verifiedKey { key -> verifier.verify(key, method, path, body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"]) }
    }
    if (!(ok ?: throw Forbidden())) throw Unauthorised()
    val household = withContext(Dispatchers.IO) { devices.soleHousehold() } ?: throw Forbidden()
    return DeviceIdentity(household, ReleasePublisher.ID)
}

/** An approval asker (Kestrel): its name in `Authorization: Asker <id>` and a valid signature by its registered key. */
private suspend fun ApplicationCall.asker(routes: ApprovalRoutes, verifier: RequestVerifier, body: String): ApprovalAsker {
    val auth = request.header("Authorization") ?: throw Unauthorised()
    if (!auth.startsWith("${ApprovalDesk.AUTH_SCHEME} ")) throw Unauthorised()
    val asker = routes.askers[auth.removePrefix("${ApprovalDesk.AUTH_SCHEME} ").trim()] ?: throw Unauthorised()
    val h = request.headers
    val method = request.httpMethod.value
    val path = request.path()
    val ok = withContext(Dispatchers.IO) {
        asker.key.verifiedKey { key -> verifier.verify(key, method, path, body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"]) }
    }
    if (!(ok ?: throw Forbidden())) throw Unauthorised()
    return asker
}

/** Reads at most MAX_BODY_BYTES + 1 bytes, so chunked bodies cannot exhaust memory. */
private suspend fun ApplicationCall.boundedBody(): String {
    val len = request.header("Content-Length")?.toLongOrNull()
    if (len != null && len > MAX_BODY_BYTES) throw WireFormatException("body too large")
    val bytes = receiveChannel().readRemaining((MAX_BODY_BYTES + 1).toLong()).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw WireFormatException("body too large")
    return bytes.decodeToString()
}

fun dataSourceFromEnv(): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = System.getenv("MEKA_DB_URL") ?: error("MEKA_DB_URL not set")
        username = System.getenv("MEKA_DB_USER")
        password = System.getenv("MEKA_DB_PASSWORD")
        maximumPoolSize = 5
    },
)

fun main(args: Array<String>) {
    // The GitHub publish job's commands need no database (build plan: hands-free phone updates).
    if (args.firstOrNull() in ReleasePublisherCli.COMMANDS) kotlin.system.exitProcess(ReleasePublisherCli.run(args.toList()))
    val ds = dataSourceFromEnv()
    Migrations.apply(ds)
    when (args.firstOrNull()) {
        // Development enrolment (ADR-005). Prints the secret once; it is never stored in clear.
        "enrol-device" -> {
            val (hh, dev, name) = args.drop(1).let { a -> Triple(a[0], a[1], a.getOrElse(2) { a[1] }) }
            println(PostgresDeviceRegistry(ds).enrol(hh, dev, name))
        }
        else -> {
            val port = System.getenv("PORT")?.toInt() ?: 8080
            val enrolToken = System.getenv("MEKA_ENROL_TOKEN")
            val opStore = PostgresOpStore(ds)
            val push = pushFromEnv(ds)
            val newsImages = NewsImages(PostgresNewsImageStore(ds))
            val ai = aiFromEnv(ds)?.also { startAiCheck(it.health) }
            val integrations = integrationsFromEnv(opStore, onChanged = { hh -> push?.serverChanged(hh) }, images = newsImages, ai = ai)
            integrations?.let { startCalendarSync(it) }
            // Calendar edits Meka makes in MEKA go to Google/Outlook after their undo window (calendar editing).
            val calendarWriter = integrations?.writer(
                opStoreReader(opStore),
                onWritten = { hh -> push?.serverChanged(hh) },
            )?.also { it.start() }
            // The GitHub build's publishes show in Activity on every device (and wake them, so the update shows soon).
            val releaseActivity = ReleaseActivity(opStore, onWritten = { hh -> push?.serverChanged(hh) })
            val speech = speechFromEnv(ds, ai?.budget)?.also { startSpeechWarm(it) }
            // Car and parking, slice 6: off ("off" to the phone) until Meka pastes the Fuel Finder keys; one instance
            // answers the phones and the deploy run's "Fuel prices" notice.
            val fuelFinder = System.getenv("MEKA_CAR_FUEL_SECRET")?.takeIf { it.isNotBlank() }?.let { FuelFinder.fromSecret(it) }
            // Spam-call lookup (2026-10-17): unknown callers' numbers searched on the web; off until the Brave key is in.
            // Made before the call assistant, which asks it about the caller while they say who they are.
            val brave = System.getenv("MEKA_SEARCH_BRAVE_SECRET")?.takeIf { it.isNotBlank() }?.let { BraveSearch.fromSecret(it) }
            val numberLookup = brave?.let { NumberLookup(it, PostgresNumberLookupStore(ds)) }
            // The companion, slice 2: live world facts for Ask and Talk, through the same Brave key.
            val liveFacts = brave?.let { LiveFacts(it) }
            val voice = voiceFromEnv(opStore, PostgresDeviceRegistry(ds), push, speech, ai, numberLookup)
            voice?.let { startRecordingSweep(it.assistant) }
            voice?.let { startCallClipsWarm(it) }
            // The call assistant's low-balance guard: the phone service's credit, read a few times a day.
            voice?.providers?.values?.filterIsInstance<CreditSource>()?.firstOrNull()?.let { source ->
                val devices = PostgresDeviceRegistry(ds)
                startCreditWatch(
                    CallCreditWatch(
                        opStore, { hh -> opStore.latestFieldOps(hh, EntityTypes.CONTEXT_MODE, CallCreditRules.ENTITY_ID)[CallCreditRules.ENTITY_ID].orEmpty() },
                        household = { devices.soleHousehold() }, source = source, onWritten = { hh -> push?.serverChanged(hh) },
                    ),
                )
            }
            // Money's bank sync (Needs Meka #38): his banks through Lunch Flow, read-only; off until the key is in its secret.
            val bankSync = System.getenv("MEKA_MONEY_LUNCHFLOW_SECRET")?.takeIf { it.isNotBlank() }?.let { secret ->
                val devices = PostgresDeviceRegistry(ds)
                BankSync(
                    opStore, { hh, type -> opStore.latestFieldOps(hh, type) }, household = { devices.soleHousehold() },
                    feed = os.meka.backend.integrations.LunchFlowFeed.fromSecret(secret), onWritten = { hh -> push?.serverChanged(hh) },
                ).also { startBankSync(it) }
            }
            // Trading tracker: his Hyperliquid accounts by public address only, read-only (no key exists to hold).
            val tradingExports = if (System.getenv("MEKA_TRADING_TRACKER") == "off") null
                else TradingExports({ hh, type -> opStore.latestFieldOps(hh, type) }, os.meka.backend.integrations.HyperliquidInfo.network(),
                    rates = os.meka.backend.integrations.HmrcMonthlyRates.network())
            if (System.getenv("MEKA_TRADING_TRACKER") != "off") {
                val devices = PostgresDeviceRegistry(ds)
                startTradingSync(TradingSync(
                    opStore, { hh, type -> opStore.latestFieldOps(hh, type) }, household = { devices.soleHousehold() },
                    feed = os.meka.backend.integrations.HyperliquidInfo.network(), onWritten = { hh -> push?.serverChanged(hh) },
                    rate = os.meka.backend.integrations.EcbDollarRate.network(),
                ))
            }
            val verifier = RequestVerifier()
            // his wife's page (family sharing, slice 2; meal plan slice 3; emergency card slice 1): only the shopping list
            // and the dinners, written as server ops, and the emergency card, read only. FamilyShare only ever asks for its
            // SHARED_TYPES and writes its WRITABLE_TYPES.
            val family = FamilyShare(
                PostgresFamilyInviteStore(ds), opStore, { hh, type -> opStore.latestFieldOps(hh, type) }, verifier,
                onWritten = { hh -> push?.serverChanged(hh) },
            )
            embeddedServer(Netty, port = port) {
                mekaSync(
                    opStore, PostgresDeviceRegistry(ds), enrolToken, integrations = integrations,
                    releases = Releases(PostgresReleaseStore(ds), onPublished = { who, r -> releaseActivity.record(who, r) }), push = push,
                    publisher = System.getenv("MEKA_RELEASE_PUBLISHER_SECRET")?.takeIf { it.isNotBlank() }?.let { SecretsManagerPublisherKey(it) },
                    voice = voice, newsImages = newsImages, calendarWriter = calendarWriter, ai = ai, speech = speech,
                    verifier = verifier, family = family, updateFailures = UpdateFailures(PostgresUpdateFailureStore(ds)),
                    foldPostures = FoldPostures(PostgresFoldPostureStore(ds)), talkSpeed = TalkSpeed(PostgresTalkSpeedStore(ds)),
                    notifyReports = NotifyReports(PostgresNotifyReportStore(ds), push, profile = { hh ->
                        opStore.latestFields(hh, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)[AboutYouRules.ENTITY_ID]
                    }, assistantCalls = assistantCallsReader(opStore), mailChecks = { hh -> integrations?.mailReport(hh).orEmpty() },
                        bank = { hh ->
                            val f = opStore.latestFields(hh, EntityTypes.CONTEXT_MODE, os.meka.core.domain.MoneyBank.SYNC_ID)[os.meka.core.domain.MoneyBank.SYNC_ID]
                            os.meka.core.domain.MoneyBank.reportLine(f?.let { m -> os.meka.core.domain.MoneyBank.read { m[it] } }) { NotifyReports.utc(it) } +
                                (if (bankSync == null) " · server has no Lunch Flow secret set" else "")
                        },
                        trains = { hh -> integrations?.trainsReport(hh) ?: "off · integrations not running" },
                        travel = { hh -> integrations?.travelReport(hh) ?: "off · integrations not running" },
                        voice = { speech?.report() ?: "off · Navilife's voice isn't set up on this server" },
                        spam = { hh -> (numberLookup?.report(hh) ?: "off · the server has no Brave secret set") + (liveFacts?.let { " · " + it.report() } ?: "") },
                        kms = { os.meka.backend.integrations.KmsMeter.reportLine() },
                        fuel = { fuelFinder?.report() ?: "off · the server has no Fuel Finder secret set" }),
                    approvals = approvalsFromEnv(opStore, onWritten = { hh -> push?.serverChanged(hh) }),
                    recovery = Recovery(PostgresRecoveryKeyStore(ds), PostgresDeviceRegistry(ds), verifier),
                    fuel = fuelFinder,
                    numberLookup = numberLookup,
                    liveFacts = liveFacts,
                    tradingExports = tradingExports,
                    vaultFiles = VaultFiles.fromEnv(),
                )
            }.start(wait = true)
        }
    }
}

/** Null unless the deployment provides a KMS key and a public URL (local dev runs without integrations). */
fun integrationsFromEnv(opStore: PostgresOpStore, onChanged: (householdId: String) -> Unit = {}, images: NewsImages? = null, ai: AiLayer? = null): Integrations? {
    val key = System.getenv("MEKA_KMS_KEY_ID") ?: return null
    val publicUrl = System.getenv("MEKA_PUBLIC_URL") ?: return null
    val secrets = buildMap {
        System.getenv("MEKA_OAUTH_GOOGLE_SECRET")?.let { put("google", it) }
        System.getenv("MEKA_OAUTH_MICROSOFT_SECRET")?.let { put("microsoft", it) }
    }
    return Integrations(
        store = PostgresIntegrationStore(opStore), ops = opStore,
        providers = listOf(GoogleCalendar(), MicrosoftCalendar()).associateBy { it.id },
        clients = SecretsManagerOAuthClients(secrets), cipher = CachingTokenCipher(KmsTokenCipher(key)), publicUrl = publicUrl,
        feeds = listOf(EspnTeamFixtures(UkTvListings())).associateBy { it.id },
        news = listOf(BbcNewsRss(), PublicNewsFeeds()).associateBy { it.id },
        holidays = listOf(GovUkBankHolidays()).associateBy { it.id },
        weather = listOf(OpenMeteoWeather()).associateBy { it.id },
        images = images,
        onChanged = onChanged,
        ownKeys = OwnKeyReminders(opStore, onWritten = onChanged),
        reader = opStoreReader(opStore),
        lines = listOf(TflLineStatus()).associateBy { it.id },
        // KMS free tier (2026-10-18): access tokens are reused until near expiry, not refreshed every 5 minutes.
        reuseAccessTokens = true,
        // Live trains (Needs Meka #16): off (and nothing sent) until the rail key is in its secret.
        rail = System.getenv("MEKA_RAIL_DARWIN_SECRET")?.takeIf { it.isNotBlank() }?.let { os.meka.backend.integrations.DarwinBoard.fromSecret(it) },
        // Weekend football, slice 2b: off (and nothing sent) until Meka pastes the Google key into the secret.
        travel = System.getenv("MEKA_MAPS_GOOGLE_SECRET")?.takeIf { it.isNotBlank() }
            ?.let { listOf(GoogleRoutes.fromSecret(it)).associateBy { p -> p.id } }.orEmpty(),
        // Car on autopilot, slice 2: off (and nothing sent) until Meka pastes the DVLA keys into the secret.
        cars = System.getenv("MEKA_CAR_DVLA_SECRET")?.takeIf { it.isNotBlank() }
            ?.let { listOf(DvlaCarLookup.fromSecret(it)).associateBy { p -> p.id } }.orEmpty(),
        // Inbox autopilot, slice 1: mail signs in with the same Google and Microsoft OAuth clients as the calendars.
        mail = listOf(GmailMail(), OutlookMail()).associateBy { it.id },
        // Slice 1c: the last 60 days' headers per mail account (never a body).
        mailStore = PostgresMailStore(opStore),
        // Slice 2b: the unsure ones get one look from the small model (sender and subject only), metered as mail.sort.
        mailAi = ai?.provider?.let { MailSortAi(it) },
        // Slice 5a: RFC 8058 one-click unsubscribe, only when Meka taps Unsubscribe (public https hosts only).
        unsubscriber = HttpOneClickUnsubscriber(),
        // Drafted replies come to him: the same mail.draft call as Draft it, shown on his card, sent only on his tap.
        mailDrafter = ai?.provider?.let { p -> MailDrafter { r -> MailDraftService(p).draft(r) } },
    )
}

/**
 * The approval desk (Kestrel's trading approvals). Null unless the deployment names Kestrel's key secret; while it is
 * still `{}` every asker request is refused (no public key to check signatures with).
 */
fun approvalsFromEnv(opStore: PostgresOpStore, onWritten: (householdId: String) -> Unit = {}): ApprovalRoutes? {
    val secret = System.getenv("MEKA_APPROVALS_KESTREL_SECRET")?.takeIf { it.isNotBlank() } ?: return null
    val desk = ApprovalDesk(
        opStore, { hh -> opStore.latestFieldOps(hh, EntityTypes.APPROVAL) },
        readKeys = { hh -> opStore.latestFieldOps(hh, EntityTypes.APPROVAL_KEY) }, onWritten = onWritten,
    )
    return ApprovalRoutes(desk, listOf(ApprovalDesk.kestrel(SecretsManagerPublisherKey(secret))))
}

/** What the devices wrote, by one indexed query per type ([PostgresOpStore.latestFields]). */
fun opStoreReader(opStore: PostgresOpStore): EntityReader = object : EntityReader {
    override fun entities(householdId: String, entityType: String) = opStore.latestFields(householdId, entityType)
    override fun entity(householdId: String, entityType: String, entityId: String) =
        opStore.latestFields(householdId, entityType, entityId)[entityId]
}

/**
 * Call notifications, slice 1: the call assistant's last messages for the publish job (length, characters, urgency and
 * how it was answered, the kept recording's size), read from the op log and the recordings bucket. Never a number or a
 * word leaves: [os.meka.core.domain.AssistantCallReportRules.line] keeps only counts.
 */
fun assistantCallsReader(opStore: PostgresOpStore): (String) -> List<os.meka.core.domain.AssistantCallEntry> {
    val recordings = System.getenv("MEKA_BLOB_BUCKET")?.takeIf { it.isNotBlank() }?.let { S3RecordingStore(it) }
    return { hh ->
        fun detail(id: String): String? = listOf(true, false).firstNotNullOfOrNull { again ->
            val entry = os.meka.core.domain.CallAssistantRules.answerActivityId(id, again)
            (opStore.latestFields(hh, EntityTypes.AGENT_ACTION, entry)[entry]?.get(os.meka.core.domain.ActivityFields.DETAIL) as? FieldValue.Text)?.value
        }
        os.meka.core.domain.AssistantCallReportRules.entries(
            opStore.latestFields(hh, EntityTypes.HELD_MESSAGE),
            answerDetail = ::detail,
            bytes = { id -> os.meka.core.domain.VoiceRecordingRules.key(hh, id)?.let { k -> recordings?.get(k)?.size } },
        )
    }
}

/**
 * The call assistant's webhooks. Null unless the deployment names the Twilio secret and a public URL; while the secret
 * is still `{}` every webhook is refused (no auth token to check signatures with).
 */
fun voiceFromEnv(
    opStore: PostgresOpStore, devices: DeviceRegistry, push: Push?, speech: SpeechService? = null, ai: AiLayer? = null,
    numberLookup: NumberLookup? = null,
): VoiceRoutes? {
    val secret = System.getenv("MEKA_VOICE_TWILIO_SECRET")?.takeIf { it.isNotBlank() } ?: return null
    val publicUrl = System.getenv("MEKA_PUBLIC_URL")?.takeIf { it.isNotBlank() } ?: return null
    // Callers' recordings (polish 8c) in MEKA's own private bucket; none kept without one.
    val bucket = System.getenv("MEKA_BLOB_BUCKET")?.takeIf { it.isNotBlank() }
    val recordings = bucket?.let { S3RecordingStore(it) }
    val assistant = CallAssistant(
        ops = opStore, fields = FieldReader { hh, type, id, field -> opStore.latestValue(hh, type, id, field) }, household = { devices.soleHousehold() },
        speechVoices = { speech?.offered().orEmpty() },
        onWritten = { hh -> push?.serverChanged(hh) }, onUrgent = { hh -> push?.urgent(hh) },
        recordings = recordings,
        // Better transcripts (8d i): Amazon Transcribe in MEKA's own account on the kept recording, when switched on.
        transcriber = if (bucket != null && recordings != null && System.getenv("MEKA_TRANSCRIBE_ENGINE") == "transcribe") {
            BatchTranscriber(AwsTranscribeJobs(System.getenv("MEKA_KMS_KEY_ID")?.takeIf { it.isNotBlank() }), bucket, recordings)
        } else null,
        // Suspected spam (8b c): MEKA's AI reads a message's words (never the number); metered, off with the AI.
        scamCheck = ai?.provider?.let { CallScamCheck(it) },
        // The assistant talks to them (2026-10-17): the caller's number's web reputation while they say who they are.
        reputation = numberLookup?.let { lookup -> { hh, number, waitMs -> lookup.reputation(hh, number, waitMs) } },
    )
    // Callers hear a chosen Google voice (natural voice, slice 3): its lines made once, on Google's meter.
    val clips = speech?.let { sp -> CallClips(synth = { text, v -> sp.googleClip(text, v) }, store = recordings) }
    return VoiceRoutes(assistant, listOf(TwilioVoice.fromSecret(secret)), publicUrl, clips)
}

/**
 * Push wake-ups through FCM. Null when the deployment names no service-account secret (local dev). With the secret
 * still `{}` the routes work and tokens are kept, but nothing is sent until the owner pastes the key.
 */
fun pushFromEnv(ds: DataSource): Push? {
    val secret = System.getenv("MEKA_FCM_SECRET")?.takeIf { it.isNotBlank() } ?: return null
    return Push(PostgresPushTokenStore(ds), FcmSender.fromSecret(secret))
}

/** Checks the AI key shortly after start and logs only the state (never the key), so a deploy shows whether AI is on. */
fun startAiCheck(ai: AiHealth) {
    Thread.ofVirtual().name("ai-check").start {
        Thread.sleep(20_000)
        val s = runCatching { ai.status() }.getOrNull() ?: return@start
        System.err.println("ai: ${s.state.wire}" + (s.reason?.let { " ($it)" } ?: ""))
    }
}

/**
 * "Keep callers' recordings" (call assistant polish 8c): hourly, recordings older than Meka's choice are deleted from
 * MEKA's bucket ([CallAssistant.sweepRecordings]). Logs only a count.
 */
fun startRecordingSweep(assistant: CallAssistant, periodMs: Long = 60 * 60_000L) {
    Thread.ofVirtual().name("recording-sweep").start {
        Thread.sleep(60_000)
        while (true) {
            runCatching { assistant.sweepRecordings() }
                .onSuccess { n -> if (n > 0) System.err.println("recordings swept: $n") }
                .onFailure { System.err.println("recording sweep failed: ${it::class.simpleName}") }
            Thread.sleep(periodMs)
        }
    }
}

/** Calendar mirror cadence: every 5 minutes, first run shortly after start. Failures are recorded per account. */
fun startCalendarSync(integrations: Integrations, periodMs: Long = 5 * 60_000L) {
    Thread.ofVirtual().name("calendar-sync").start {
        Thread.sleep(30_000)
        while (true) {
            runCatching { integrations.syncAll() }
            Thread.sleep(periodMs)
        }
    }
}

object Migrations {
    /** Every migration, in order; MigrationsListTest checks each `db/V*.sql` file is here (V26 once wasn't). */
    internal val all = listOf(
        1 to "/db/V1__sync.sql", 2 to "/db/V2__integrations.sql", 3 to "/db/V3__device_keys.sql", 4 to "/db/V4__event_mirror_end.sql",
        5 to "/db/V5__app_release.sql", 6 to "/db/V6__push_token.sql", 7 to "/db/V7__news_image.sql",
        8 to "/db/V8__calendar_editing.sql", 9 to "/db/V9__event_edits.sql", 10 to "/db/V10__ai_usage.sql",
        11 to "/db/V11__speech_usage.sql", 12 to "/db/V12__sign_ins.sql", 13 to "/db/V13__family_invite.sql",
        14 to "/db/V14__ai_usage_day.sql", 15 to "/db/V15__app_update_failure.sql", 16 to "/db/V16__recovery_key.sql",
        17 to "/db/V17__fold_posture.sql", 18 to "/db/V18__notify_report.sql", 19 to "/db/V19__mail_account_label.sql",
        20 to "/db/V20__mail_message.sql",
        21 to "/db/V21__mail_sort.sql",
        22 to "/db/V22__mail_tidy.sql",
        23 to "/db/V23__mail_sender.sql",
        24 to "/db/V24__mail_preview.sql",
        25 to "/db/V25__mail_backlog.sql",
        26 to "/db/V26__speech_usage_google.sql",
        27 to "/db/V27__number_lookup.sql",
        28 to "/db/V28__talk_timing.sql",
    )

    fun apply(ds: DataSource) = ds.connection.use { c ->
        c.autoCommit = false
        c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())") }
        c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(4242)") } // one migrator at a time
        val applied = c.createStatement().use { st -> st.executeQuery("SELECT version FROM schema_migration").use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } }
        for ((v, path) in all) if (v !in applied) {
            val sql = Migrations::class.java.getResource(path)!!.readText()
            c.createStatement().use { it.execute(sql) }
            c.prepareStatement("INSERT INTO schema_migration(version) VALUES (?)").use { it.setInt(1, v); it.executeUpdate() }
        }
        c.commit()
    }
}
