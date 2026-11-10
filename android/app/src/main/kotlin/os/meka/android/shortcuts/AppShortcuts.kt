package os.meka.android.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import os.meka.android.MainActivity
import os.meka.android.R
import os.meka.core.domain.DayListRules

/**
 * The app icon's long-press menu (the Day list, slice 2, `docs/design/day-list/Today.dc.html`: "hold the app icon →
 * Add tasks"): **Add tasks** opens Today with today's Day list up and its add bar ready, so he can type the first task
 * at once and Enter for the next. Published as a dynamic shortcut at every start (so it always points at this build's
 * MainActivity, whatever the package's suffix), and the launcher can pin it to the home screen. It carries no data.
 */
object AppShortcuts {
    fun addTasksIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DAY_LIST_ADD)

    fun addTasks(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, DayListRules.SHORTCUT_ID)
            .setShortLabel(DayListRules.SHORTCUT)
            .setLongLabel(DayListRules.SHORTCUT_LONG)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add_tasks))
            .setIntent(addTasksIntent(context))
            .setRank(0)
            .build()

    /** Puts the shortcuts on the icon (once per start; a failure only means no shortcut, never a crash). */
    fun publish(context: Context) {
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, listOf(addTasks(context))) }
    }
}
