package os.meka.android.work

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.android.MekaApplication
import os.meka.core.domain.CallDecision
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.CallerNames
import os.meka.core.domain.CallSignals
import os.meka.core.domain.CallVerdict
import os.meka.core.domain.People
import os.meka.core.domain.UnknownCallRules

/**
 * The call assistant's screening on the Fold (build plan M1, Needs Meka #9). Android asks this service about every
 * incoming call once Meka has given MEKA the call-screening role in the Work screen.
 *
 * During work, with the assistant's switch on, a call from anyone but family, the always-notify list or a repeat
 * caller (second call within 3 minutes) is declined, which the network treats as busy: the carrier's "forward when
 * busy" sends it on (voicemail today, the assistant's number once it is set up). The call still shows in the call
 * log and as a missed call, so it lands in the after-work summary. Anything unexpected (MEKA not ready, slow, an
 * outgoing call) lets the call ring: screening must never lose a call.
 *
 * Spam protection (call assistant polish 8b; always on since "spam calls still ring", slice 1) runs on every call, any
 * time, whatever the assistant's switch says: a number on the synced block list is rejected silently, a number that
 * failed the network's caller check is blocked and put on the list (Undo in Activity), and with the switch on a
 * withheld number in quiet hours goes to the assistant. Emergency numbers, the household's and callers whose network
 * name claims an official body ring. Contacts and anyone Meka called in the last 90 days are never stopped; the phone
 * reads both itself (only with the contacts and call-log permissions Meka granted in Work mode) and sends neither.
 * After a call from a number nobody knows (rung, or sent to the assistant as likely spam), [UnknownCallNotice] posts a
 * quiet "Unknown caller · Block?" once the call is over (8b b).
 */
class MekaCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) { allow(details); return }
        val meka = application as MekaApplication
        scope.launch {
            val decision = withTimeoutOrNull(DECIDE_WITHIN_MS) {
                runCatching {
                    val ctx = this@MekaCallScreeningService
                    val number = details.handle?.schemeSpecificPart
                    val now = System.currentTimeMillis()
                    val recent = RecentDeclines.load(ctx)
                    val signals = CallSignals(
                        verificationFailed = details.callerNumberVerificationStatus == android.telecom.Connection.VERIFICATION_STATUS_FAILED,
                        knownContact = CallerLookup.isContact(ctx, number),
                        calledRecently = CallerLookup.calledRecently(ctx, number, now),
                        // The name the network sent (CNAP), read here only: a claimed police/bank/NHS/school rings
                        // and gets a heads-up afterwards instead of being auto-blocked (spam calls still ring, slice 1).
                        networkName = runCatching { details.callerDisplayName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
                    )
                    val d = meka.core.screenIncomingCall(number, meka.captures.lists.value, recent, signals)
                    RecentDeclines.save(ctx, CallScreeningRules.remember(recent, d, now))
                    Screened(d, number, signals, now)
                }.getOrNull()
            }
            when (decision?.decision?.verdict) {
                CallVerdict.DECLINE -> decline(details)
                CallVerdict.BLOCK -> block(details)
                else -> allow(details)
            }
            // Spam calls still ring, slice 2: every screened call goes in the diagnosis log (a salted tag, never the
            // number), so a build run can see why a call rang; then the report is sent when due.
            runCatching {
                val ctx = this@MekaCallScreeningService
                CallScreenLog.record(
                    ctx, details.handle?.schemeSpecificPart, decision?.atMs ?: System.currentTimeMillis(), decision?.decision, decision?.signals,
                    runCatching { details.callerNumberVerificationStatus }.getOrNull(), meka.core.workMode.value.spamProtection,
                )
                os.meka.android.notify.NotifyTraceReporter.poke(meka)
            }
            // Call notifications, slice 6: the call goes in Activity → Calls (this phone only, never sent).
            runCatching {
                val d = decision?.decision
                val sig = decision?.signals
                val known = sig != null && (sig.knownContact || sig.calledRecently || sig.household) ||
                    d?.reason == os.meka.core.domain.CallReason.FAMILY || d?.reason == os.meka.core.domain.CallReason.ALWAYS_RING
                PhoneCallLog.record(
                    this@MekaCallScreeningService,
                    os.meka.core.domain.PhoneCall(
                        atMs = decision?.atMs ?: System.currentTimeMillis(),
                        number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() },
                        verdict = d?.verdict ?: CallVerdict.RING,
                        reason = d?.reason,
                        known = known,
                    ),
                )
            }
            // Android has its answer; now, unhurried: a caller nobody knows gets "Unknown caller · Block?" afterwards,
            // and an auto-blocked one the quiet "Blocked … · it won't ring again" with Undo (spam calls still ring, slice 1).
            decision?.let { s ->
                val autoId = s.decision.autoBlockId
                if (autoId != null && s.number != null) runCatching { UnknownCallNotice.postAutoBlocked(this@MekaCallScreeningService, s.number, autoId, os.meka.core.domain.AutoBlockTrigger.NETWORK_CHECK, System.currentTimeMillis()) }
                else runCatching { watchUnknown(s) }
            }
        }
    }

    private class Screened(val decision: CallDecision, val number: String?, val signals: CallSignals, val atMs: Long)

    private fun watchUnknown(s: Screened) {
        val number = s.number ?: return
        val key = People.key(number)
        val own = CallerLookup.ownNumbers(this).any { People.key(it) == key }
        if (!UnknownCallRules.watch(s.decision, number, s.signals, own)) return
        UnknownCallNotice.schedule(
            this, number, s.atMs, toAssistant = s.decision.verdict == CallVerdict.DECLINE, networkName = s.signals.networkName,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun allow(details: Call.Details) = runCatching { respondToCall(details, CallResponse.Builder().build()) }

    private fun decline(details: Call.Details) = runCatching {
        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true) // busy: the carrier's "forward when busy" takes it from here
                .setSkipCallLog(false)
                .setSkipNotification(false) // the missed-call notification feeds the after-work summary
                .build(),
        )
    }

    /** On the block list: no ring and no notification; it stays in the call log (and Activity says so). */
    private fun block(details: Call.Details) = runCatching {
        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
                .build(),
        )
    }

    companion object {
        /** Android gives a screening service about five seconds; ring rather than risk it. */
        private const val DECIDE_WITHIN_MS = 3_000L

        fun roleHeld(context: Context): Boolean = runCatching {
            val rm = context.getSystemService(RoleManager::class.java)
            rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }.getOrDefault(false)

        fun roleRequest(context: Context) = runCatching {
            context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        }.getOrNull()
    }
}

/** The last few declined callers (numbers' keys and times only), so a second call within 3 minutes rings. */
private object RecentDeclines {
    private const val PREFS = "call_screening"
    private const val KEY = "recent"

    fun load(context: Context) = CallScreeningRules.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, recent: List<os.meka.core.domain.ScreenedCall>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, CallScreeningRules.encode(recent)).apply()
    }
}

/**
 * What the phone knows about a caller, read on the phone only and only with the permission Meka granted: whether the
 * number is a contact, and whether Meka called it in the last 90 days. Without the permission: false (no protection
 * beyond the family and always-notify lists).
 */
internal object CallerLookup {
    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun isContact(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || !granted(context, Manifest.permission.READ_CONTACTS)) return false
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun calledRecently(context: Context, number: String?, nowMs: Long): Boolean {
        if (number.isNullOrBlank() || !granted(context, Manifest.permission.READ_CALL_LOG)) return false
        val key = os.meka.core.domain.People.key(number)
        if (!key.startsWith("tel:")) return false
        val since = nowMs - CallScreeningRules.CALLED_RECENTLY_DAYS * 86_400_000L
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), since.toString()), null,
            )?.use { c ->
                var found = false
                while (!found && c.moveToNext()) found = c.getString(0)?.let { os.meka.core.domain.People.key(it) } == key
                found
            } == true
        }.getOrDefault(false)
    }

    /** A number's name in the phone's contacts, or null (no permission, not a contact). Read here only, never sent. */
    fun contactName(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The phone's own numbers, from the owner's own contact card (Contacts → My profile), so a test call to the
     * assistant reads "You · test call" (polish 5). Empty without the contacts permission or with no number there.
     */
    fun ownNumbers(context: Context): Set<String> {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return emptySet()
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE), null,
            )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { add(it) } } }
        }.getOrNull().orEmpty()
    }

    /**
     * How callers known only by number are named on this phone (polish 3 and 5): its contacts and own numbers. Each
     * number is looked up once per [CallerNames]; build one per summary.
     */
    fun names(context: Context): CallerNames {
        val app = context.applicationContext
        if (!granted(app, Manifest.permission.READ_CONTACTS)) return CallerNames.NONE
        val cache = HashMap<String, String?>()
        return CallerNames({ n -> cache.getOrPut(os.meka.core.domain.People.key(n)) { contactName(app, n) } }, ownNumbers(app))
    }

    /** Whether Meka has let MEKA read contacts and the call log (Work mode → Call assistant → Recognise callers). */
    fun allowed(context: Context) =
        granted(context, Manifest.permission.READ_CONTACTS) && granted(context, Manifest.permission.READ_CALL_LOG)

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)

    /**
     * Spam calls still ring, slice 5: after Allow, if Android refused without asking (after two "Don't allow"s it
     * stops showing the dialog, and then gives no rationale either), open Navilife's app page so Permissions →
     * Contacts and Call logs can be turned on there. A refusal he just made in the dialog is left alone.
     */
    fun openAppSettingsIfRefused(context: Context) {
        if (allowed(context)) return
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstNotNullOfOrNull { it as? android.app.Activity } ?: return
        val missing = PERMISSIONS.filterNot { granted(context, it) }
        if (missing.any { activity.shouldShowRequestPermissionRationale(it) }) return
        runCatching {
            activity.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}
