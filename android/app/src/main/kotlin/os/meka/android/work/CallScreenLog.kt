package os.meka.android.work

import android.content.Context
import os.meka.android.MekaApplication
import os.meka.core.domain.CallDecision
import os.meka.core.domain.CallScreenLogRules
import os.meka.core.domain.CallScreenReport
import os.meka.core.domain.CallSignals
import os.meka.core.domain.ScreenedCallEntry
import java.security.SecureRandom

/**
 * The Fold's last screened calls (spam calls still ring, slice 2), kept in the phone's preferences and sent with the
 * notification trace ([os.meka.android.notify.NotifyTraceReporter]) so a build run can read, from deploy's
 * `publish-fold` job, whether a call that rang ever reached the screening service and why it was let through. Each
 * caller is a salted tag ([CallScreenLogRules.tag]); the salt is random, made on this phone and never sent, so the
 * report never carries a number.
 */
object CallScreenLog {
    private const val PREFS = "call_screening_log"
    private const val KEY_LOG = "log"
    private const val KEY_SALT = "salt"

    @Synchronized
    private fun salt(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_SALT, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        p.edit().putString(KEY_SALT, s).apply()
        return s
    }

    fun log(context: Context): List<ScreenedCallEntry> =
        CallScreenLogRules.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, null))

    /** Records one screened call; [decision] null = Navilife didn't answer Android in time, so it rang. */
    @Synchronized
    fun record(context: Context, number: String?, atMs: Long, decision: CallDecision?, signals: CallSignals?, verification: Int?, protection: Boolean) {
        val app = context.applicationContext
        val e = CallScreenLogRules.entry(atMs, CallScreenLogRules.tag(number, salt(app)), decision, signals, verification, protection)
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString(KEY_LOG, CallScreenLogRules.encode(CallScreenLogRules.add(log(app), e))).apply()
    }

    /** Whether a call from [number] since [sinceMs] rang because its network name claimed an official body. */
    fun claimedOfficial(context: Context, number: String, sinceMs: Long): Boolean {
        val app = context.applicationContext
        return CallScreenLogRules.claimedOfficial(log(app), CallScreenLogRules.tag(number, salt(app)), sinceMs)
    }

    /** What the report carries: the role, the switch, whether contacts and the call log can be read, the last calls. */
    fun report(app: MekaApplication): CallScreenReport = CallScreenReport(
        roleHeld = MekaCallScreeningService.roleHeld(app),
        protection = app.core.workMode.value.spamProtection,
        lookups = CallerLookup.allowed(app),
        calls = log(app),
    )
}
