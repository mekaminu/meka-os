package os.meka.android.ask

import android.app.PendingIntent
import android.app.UiModeManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.RemoteViews
import os.meka.android.MainActivity
import os.meka.android.R
import os.meka.core.domain.TalkStart
import os.meka.core.domain.TalkStartRules

/**
 * Talk without tapping the mic (slice 2): where listening is clearly wanted. Opening MEKA from the launcher with
 * Bluetooth audio connected or in car mode, and the home screen's Talk widget, open Ask already listening (through
 * [MekaApplication.talkNow], as the side button does). The decision is the core's [TalkStartRules.onOpen].
 */
object TalkAutoListen {
    /** Output types that mean "something in your ears or the car's speakers", not the phone's own speaker. */
    val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** A launcher open: Android's MAIN with the LAUNCHER category (a notification or a widget brings its own intent). */
    fun fromLauncher(intent: Intent?): Boolean =
        intent != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            !intent.hasExtra(MainActivity.EXTRA_OPEN)

    /** Where this launcher open should start talking from, or null (most opens: Ask waits for the mic). */
    fun onOpen(context: Context, intent: Intent?): TalkStart? {
        val launcher = fromLauncher(intent)
        if (!launcher) return null
        return TalkStartRules.onOpen(launcher, bluetoothAudio(context), carMode(context))
    }

    private fun bluetoothAudio(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_TYPES }
    }.getOrDefault(false)

    private fun carMode(context: Context): Boolean = runCatching {
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }.getOrDefault(false)

    /** The Talk widget's tap: MEKA's main screen with "talk:WIDGET", the running one if there is one. */
    fun widgetIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, WIDGET_REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, TalkStartRules.openValue(TalkStart.WIDGET)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Clear of the capture widget's (1, 2), the data widgets' (300+) and the news widget's (400+, 410+). */
    const val WIDGET_REQUEST_CODE = 450
}

/**
 * The home screen's Talk widget (1×1, resizable): a brass mic and "Talk to MEKA". One tap opens MEKA on Ask already
 * listening. Plain RemoteViews with no data of its own, so it never needs refreshing. Motion: the launcher's own.
 */
class TalkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_talk).apply {
            setOnClickPendingIntent(R.id.widget_root, TalkAutoListen.widgetIntent(context))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
