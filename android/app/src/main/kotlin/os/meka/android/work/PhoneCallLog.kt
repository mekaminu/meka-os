package os.meka.android.work

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.meka.core.domain.PhoneCall
import os.meka.core.domain.PhoneCallRules

/**
 * Call notifications, slice 6: the calls this phone's screening saw in the last 7 days (rang through, sent to the call
 * assistant, stopped before it rang), for Activity → Calls. Kept in Navilife's own preferences on this phone only —
 * never synced, sent, reported or logged (the diagnosis log, [CallScreenLog], carries salted tags instead).
 */
object PhoneCallLog {
    private const val PREFS = "phone_calls"
    private const val KEY = "calls"

    private val state = MutableStateFlow<List<PhoneCall>>(emptyList())
    @Volatile private var loaded = false

    /** The kept calls, newest first; loads once per process and updates live as calls are screened. */
    fun calls(context: Context): StateFlow<List<PhoneCall>> {
        load(context)
        return state.asStateFlow()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        state.value = runCatching {
            PhoneCallRules.decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        }.getOrDefault(emptyList())
        loaded = true
    }

    @Synchronized
    fun record(context: Context, call: PhoneCall) {
        load(context)
        val next = PhoneCallRules.add(state.value, call, System.currentTimeMillis())
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, PhoneCallRules.encode(next)).apply()
        state.value = next
    }
}
