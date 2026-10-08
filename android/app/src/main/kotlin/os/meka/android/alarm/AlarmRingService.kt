package os.meka.android.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.core.domain.AlarmRing
import os.meka.core.domain.AlarmRules

/**
 * Rings the wake alarm (Alarms, slice 1): the alarm sound on the alarm stream (so Do Not Disturb's "Alarms" lets it
 * through), looping, its volume rising from 5 % to full over half a minute; a vibration pattern; and a full-screen
 * notification that opens [AlarmActivity] over the lock screen (with Snooze and Dismiss on the notification for when
 * the phone is in use). It stops when the alarm is answered here or on the Mac (it follows the core's next alarm),
 * or after 10 minutes. A foreground service of the media-playback type, started from the alarm itself.
 */
class AlarmRingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringing: AlarmRing? = null
    private var startedAt = 0L
    private var watch: Job? = null

    private val ramp = object : Runnable {
        override fun run() {
            val v = AlarmRouting.volumeAt(SystemClock.elapsedRealtime() - startedAt)
            runCatching { player?.setVolume(v, v) }
            if (v < 1f) handler.postDelayed(this, RAMP_STEP_MS)
        }
    }
    private val timeout = Runnable { stopRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MekaApplication
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent?.action == ACTION_SNOOZE) {
            if (id != null) answer { app.core.snoozeAlarm(id) }
            stopRinging()
            return START_NOT_STICKY
        }
        // Android wants the foreground notification promptly, before anything else.
        val ring = app.core.nextAlarm.value
        startForeground(AlarmRouting.NOTIFICATION_ID, notification(ring), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (ring == null || !AlarmRouting.shouldRing(ring, id, System.currentTimeMillis())) {
            stopRinging()
            return START_NOT_STICKY
        }
        if (ringing?.id == ring.id && ringing?.ringAtMs == ring.ringAtMs && player != null) return START_NOT_STICKY
        ringing = ring
        startedAt = SystemClock.elapsedRealtime()
        startSound()
        startVibration()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, AlarmRules.RING_FOR_MS)
        // Answered on the Mac (or snoozed / dismissed in the activity): the core's next alarm moves on, so stop.
        watch?.cancel()
        watch = scope.launch {
            app.core.nextAlarm.collect { r ->
                if (r == null || r.id != ring.id || r.ringAtMs != ring.ringAtMs) stopRinging()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                setVolume(AlarmRouting.START_VOLUME, AlarmRouting.START_VOLUME)
                prepare()
                start()
            }
        }.getOrNull()
        handler.removeCallbacks(ramp)
        handler.post(ramp)
    }

    private fun startVibration() {
        val v = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 800), 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    private fun release() {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(timeout)
        watch?.cancel()
        watch = null
        runCatching { player?.stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        ringing = null
    }

    private fun stopRinging() {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The core write outlives the service (it stops at once). */
    private fun answer(block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { runCatching { block() } }
    }

    private fun notification(ring: AlarmRing?): Notification {
        ensureChannel(this)
        val id = ring?.id
        val full = PendingIntent.getActivity(
            this, REQ_FULL, AlarmActivity.intent(this, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getService(
            this, REQ_SNOOZE, Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Dismiss opens the brief afterwards, so it goes through the activity (no notification trampolines).
        val dismiss = PendingIntent.getActivity(
            this, REQ_DISMISS, AlarmActivity.intent(this, id).setAction(AlarmActivity.ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AlarmRouting.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(ring?.let { AlarmRouting.title(it) } ?: AlarmRules.TITLE)
            .setContentText(ring?.line ?: "")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true) // the service plays the sound itself, rising
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Snooze ${AlarmRules.SNOOZE_MIN} min", snooze)
            .addAction(0, "Dismiss", dismiss)
            .build()
    }

    companion object {
        const val EXTRA_ID = "os.meka.alarm.id"
        const val ACTION_RING = "os.meka.alarm.RING"
        const val ACTION_SNOOZE = "os.meka.alarm.SNOOZE"
        private const val RAMP_STEP_MS = 500L
        private const val REQ_FULL = 1
        private const val REQ_SNOOZE = 2
        private const val REQ_DISMISS = 3

        fun ring(context: Context, id: String?) {
            val i = Intent(context, AlarmRingService::class.java).setAction(ACTION_RING).putExtra(EXTRA_ID, id)
            runCatching { context.startForegroundService(i) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmRingService::class.java)) }
        }

        /** One high-importance "Alarms" channel; silent, because the service plays and ramps the sound. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(AlarmRouting.CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(AlarmRouting.CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Your wake alarm, full screen"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }
}
