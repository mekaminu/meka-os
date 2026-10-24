package os.meka.android.ask

import android.content.Context
import os.meka.core.domain.StageClose
import os.meka.core.domain.StageClosed
import os.meka.core.domain.StageRules

/**
 * Meka 2026-10-24 14:34: why the hello's stage last closed, kept on this phone only (the reason and the time, never
 * any words), for Health → Obi's voice ("Last conversation closed 14:34: no speech heard for a minute").
 */
object StageCloseLog {
    private const val PREFS = "obi_stage"
    private const val LAST = "last_close"

    fun record(context: Context, why: StageClose, atMs: Long = System.currentTimeMillis()) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(LAST, StageRules.encode(StageClosed(why, atMs))).apply()
        }
    }

    fun last(context: Context): StageClosed? = runCatching {
        StageRules.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null))
    }.getOrNull()

    /** The stage's "Open settings": Navilife's own page in Android's settings, where the microphone is allowed. */
    fun openMicSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
