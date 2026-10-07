package os.meka.android.work

import android.app.role.RoleManager
import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.android.MekaApplication
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.CallVerdict

/**
 * The call assistant's screening on the Fold (build plan M1, Needs Meka #9). Android asks this service about every
 * incoming call once Meka has given MEKA the call-screening role in the Work screen.
 *
 * During work, with the assistant's switch on, a call from anyone but family, the always-notify list or a repeat
 * caller (second call within 3 minutes) is declined, which the network treats as busy: the carrier's "forward when
 * busy" sends it on (voicemail today, the assistant's number once it is set up). The call still shows in the call
 * log and as a missed call, so it lands in the after-work summary. Anything unexpected (MEKA not ready, slow, an
 * outgoing call) lets the call ring: screening must never lose a call.
 */
class MekaCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) { allow(details); return }
        val meka = application as MekaApplication
        scope.launch {
            val decision = withTimeoutOrNull(DECIDE_WITHIN_MS) {
                runCatching {
                    val work = meka.core.currentWorkMode()
                    val number = details.handle?.schemeSpecificPart
                    val now = System.currentTimeMillis()
                    val recent = RecentDeclines.load(this@MekaCallScreeningService)
                    val d = CallScreeningRules.decide(work.callAssistant, work.atWork, number, meka.captures.lists.value, recent, now)
                    if (work.callAssistant && work.atWork) RecentDeclines.save(this@MekaCallScreeningService, CallScreeningRules.remember(recent, d, now))
                    d
                }.getOrNull()
            }
            if (decision?.verdict == CallVerdict.DECLINE) decline(details) else allow(details)
        }
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
