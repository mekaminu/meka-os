package os.meka.android.ask

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.CarBluetoothRules
import os.meka.core.domain.CarLink
import os.meka.core.domain.PairedDevice
import os.meka.core.domain.ParkedNote
import os.meka.core.domain.ParkingRules

/**
 * Car and parking, slice 3 (the Fold only): each car's Bluetooth device saves the spot when it disconnects.
 *
 * The link (car → paired device) is kept in this phone's own storage only ([CarBluetoothRules]); Android tells
 * [CarBluetoothReceiver] when any device connects or disconnects (ACL broadcasts, which need Nearby devices). A linked
 * car's disconnect after a drive saves the spot by itself when location may be read in the background ("Allow all the
 * time", the same grant Errands near me uses), else posts "Parked the Golf? · tap to save where it is", whose tap opens
 * the Car pane saving the spot. A reconnect later clears the spot. Nothing is synced or sent.
 */
object CarBluetooth {
    private const val PREFS = "meka.car-bluetooth"
    private const val KEY_LINKS = "links"
    const val CHANNEL = "car_parked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun links(context: Context): Map<String, CarLink> = CarBluetoothRules.decode(prefs(context).getString(KEY_LINKS, null))

    @Synchronized
    fun store(context: Context, links: Map<String, CarLink>) {
        prefs(context).edit().putString(KEY_LINKS, CarBluetoothRules.encode(links.values)).apply()
    }

    /** Nearby devices (BLUETOOTH_CONNECT): needed to list paired devices and to hear them connect. */
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // isEnabled needs none from Android 12; caught either way
    fun bluetoothOn(context: Context): Boolean =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    /** "Allow all the time" location: the spot can be saved without a tap. */
    fun always(context: Context): Boolean = ErrandProximity.always(context) && ParkingStore.precise(context)

    /** The phone's paired devices (empty without the permission or with Bluetooth off). */
    @SuppressLint("MissingPermission") // allowed() checked; a late revoke throws, caught
    fun paired(context: Context): List<PairedDevice> {
        if (!allowed(context)) return emptyList()
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { PairedDevice(it.address, it.name.orEmpty()) }
        }.getOrDefault(emptyList())
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, CarBluetoothRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CarBluetoothRules.CHANNEL_ABOUT
            },
        )
    }

    fun notificationId(carId: String): Int = ("car-parked:$carId").hashCode()

    /** The tap: the Car pane, saving [carId]'s spot ([save]) or with its bay field to fill (after a quiet save). */
    private fun openIntent(context: Context, carId: String, save: Boolean): PendingIntent = PendingIntent.getActivity(
        context, notificationId(carId) + if (save) 1 else 0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, (if (save) MainActivity.OPEN_CAR_PARK_PREFIX else MainActivity.OPEN_CAR_PREFIX) + carId)
            .putExtra(MainActivity.EXTRA_CANCEL, notificationId(carId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun post(context: Context, carId: String, note: ParkedNote, save: Boolean): Boolean {
        if (!WorkAlerts.canPost(context)) return false
        ensureChannel(context)
        val tap = openIntent(context, carId, save)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("Navilife · parked").build(),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .addAction(0, note.action, tap)
            .setAutoCancel(true)
        return try { os.meka.android.notify.MekaPost.notify(context, notificationId(carId), b.build()); true } catch (e: SecurityException) { false }
    }

    fun cancel(context: Context, carId: String) = NotificationManagerCompat.from(context).cancel(notificationId(carId))
}

/** The parked notification's tap: the Car pane for [carId], saving its spot when [save]. */
data class CarOpen(val carId: String, val save: Boolean)

/**
 * Android's Bluetooth connect/disconnect broadcasts (protected: only the system sends them). Only a device linked to a
 * car does anything; every other device is ignored at once.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission") // the address needs no permission; these broadcasts only come with Nearby devices
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice =
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val app = context.applicationContext as? MekaApplication ?: return
        val links = CarBluetooth.links(app)
        if (links.values.none { CarBluetoothRules.sameAddress(it.address, address) }) return
        val now = System.currentTimeMillis()
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val spots = ParkingStore.spots(app)
                val r = CarBluetoothRules.onConnect(links, spots, address, now)
                CarBluetooth.store(app, r.links)
                r.droveOff?.let { car ->
                    ParkingStore.store(app, ParkingRules.droveOff(spots, car))
                    CarBluetooth.cancel(app, car)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val r = CarBluetoothRules.onDisconnect(links, address, now)
                CarBluetooth.store(app, r.links)
                val car = r.parkedCar ?: return
                val title = ParkingRules.titleOf(car, app.core.carsView.value)
                if (!CarBluetooth.always(app) || !ParkingStore.locationOn(app)) {
                    CarBluetooth.post(app, car, CarBluetoothRules.askNote(title), save = true)
                    return
                }
                // He is standing by the car now: a few seconds of fixes, the best saved (a background broadcast may
                // run for up to a minute; this listens at most 15 s).
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        val saved = runCatching { ParkingStore.parkHere(app, car, title, CarBluetoothRules.QUIET_FIX_MS) }.getOrNull()
                        if (saved?.saved == true) CarBluetooth.post(app, car, CarBluetoothRules.savedNote(saved), save = false)
                        else CarBluetooth.post(app, car, CarBluetoothRules.failedNote(title), save = true)
                    } finally { pending.finish() }
                }
            }
        }
    }
}
