package os.meka.android.ask

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.ArmedPlace
import os.meka.core.domain.ErrandNearNotice
import os.meka.core.domain.ErrandPlaceRules
import os.meka.core.domain.ErrandView
import os.meka.core.domain.PlaceSaved
import os.meka.core.domain.SavedPlace
import kotlin.coroutines.resume

/**
 * Errands by location, slice 2 (the Fold only): where each place is, and Android's own proximity alert near it.
 *
 * A place's spot is one precise fix taken when Meka taps "I'm here" in its section of Errands; it is kept in this
 * phone's app storage only, never synced and never sent. With the near-me switch on (off by default) and precise plus
 * "Allow all the time" location granted, every saved place with errands waiting is handed to [LocationManager]'s
 * proximity alert (Android's own; no Google library); Android wakes [ErrandNearReceiver] on arriving, which posts
 * "Near Tesco · milk, batteries" with Done. Nothing else in MEKA reads location for this.
 */
object ErrandProximity {
    private const val PREFS = "meka.errand-places"
    private const val KEY_ON = "on"
    private const val KEY_PLACES = "places"
    private const val KEY_ARMED = "armed"
    private const val KEY_REMINDED = "reminded"
    const val CHANNEL = "errands_near"

    /** How long one precise fix may take ("I'm here"); GPS outdoors usually needs a few seconds. */
    const val FIX_TIMEOUT_MS = 20_000L

    /** A fix the phone already has is used for "I'm here" only when this fresh (Meka is standing there now). */
    const val LAST_KNOWN_FRESH_MS = 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    fun precise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun always(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun saved(context: Context): Map<String, SavedPlace> = ErrandPlaceRules.decode(prefs(context).getString(KEY_PLACES, null))

    private fun store(context: Context, places: Map<String, SavedPlace>) {
        prefs(context).edit().putString(KEY_PLACES, ErrandPlaceRules.encode(places.values)).apply()
    }

    /** "I'm here" for [place]: one precise fix, saved when it is close enough. Never throws. */
    suspend fun saveHere(context: Context, place: String, view: ErrandView): PlaceSaved {
        val before = saved(context)
        if (!precise(context)) return PlaceSaved(before, ErrandPlaceRules.NO_PERMISSION, false)
        val fix = try { locate(context) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val r = ErrandPlaceRules.save(
            before, place, fix?.latitude, fix?.longitude,
            fix?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(), System.currentTimeMillis(),
        )
        if (r.saved) {
            store(context, r.places)
            arm(context, view)
        }
        return r
    }

    /** Forgets [key]'s spot (and stops watching it). */
    fun forget(context: Context, key: String, view: ErrandView) {
        store(context, saved(context) - key)
        arm(context, view)
    }

    /**
     * Brings Android's watched places in line with [view]: every saved place with errands waiting while the switch is
     * on and both permissions are given; none otherwise. Safe to call often (after any errand change, at start).
     */
    @SuppressLint("MissingPermission") // precise() is checked; a late revoke throws SecurityException, caught below
    fun arm(context: Context, view: ErrandView) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val p = prefs(context)
        val before = p.getStringSet(KEY_ARMED, emptySet()).orEmpty()
        val want: List<ArmedPlace> =
            if (isOn(context) && precise(context) && always(context)) ErrandPlaceRules.toArm(view, saved(context)) else emptyList()
        before.forEach { key -> runCatching { lm.removeProximityAlert(nearIntent(context, key)) } }
        val armed = want.filter { a ->
            try {
                lm.addProximityAlert(a.latitude, a.longitude, ErrandPlaceRules.RADIUS_M.toFloat(), -1L, nearIntent(context, a.key))
                true
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }.map { it.key }.toSet()
        p.edit().putStringSet(KEY_ARMED, armed).apply()
    }

    /**
     * What Android sends on arriving near [key]. Mutable because Android adds whether it is arriving or leaving
     * (LocationManager.KEY_PROXIMITY_ENTERING); explicit (this app's receiver only), so nothing else can fill it in.
     */
    private fun nearIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ErrandNearReceiver::class.java)
            .setAction(ErrandNearReceiver.ACTION_NEAR)
            .setData(Uri.fromParts("meka-errand", key, null))
            .putExtra(ErrandNearReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun lastReminded(context: Context): Map<String, Long> =
        ErrandPlaceRules.decodeReminded(prefs(context).getString(KEY_REMINDED, null))

    internal fun markReminded(context: Context, key: String, nowMs: Long) {
        val next = lastReminded(context) + (key to nowMs)
        prefs(context).edit().putString(KEY_REMINDED, ErrandPlaceRules.encodeReminded(next)).apply()
    }

    /** The provider for "I'm here": GPS first (precise), then fused, then the network's. Pure. */
    fun pickProvider(enabled: Collection<String>): String? =
        listOf(LocationManager.GPS_PROVIDER, FUSED, LocationManager.NETWORK_PROVIDER).firstOrNull { it in enabled }

    /** Whether a fix the phone already holds can stand for "here, now". Pure. */
    fun freshEnough(ageMs: Long): Boolean = ageMs in 0..LAST_KNOWN_FRESH_MS

    private const val FUSED = "fused" // LocationManager.FUSED_PROVIDER (API 31)

    @SuppressLint("MissingPermission") // checked by the caller (precise)
    private suspend fun locate(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        val provider = pickProvider(lm.getProviders(true)) ?: return null
        lm.getLastKnownLocation(provider)?.let { last ->
            val age = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
            if (freshEnough(age) && last.hasAccuracy() && last.accuracy <= ErrandPlaceRules.MAX_ACCURACY_M) return last
        }
        val request = LocationRequest.Builder(0L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, request, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ErrandPlaceRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ErrandPlaceRules.CHANNEL_ABOUT
            },
        )
    }

    fun notificationId(key: String): Int = ("errand-near:$key").hashCode()

    private fun openIntent(context: Context, key: String): PendingIntent = PendingIntent.getActivity(
        context, notificationId(key),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ERRANDS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(context: Context, action: String, key: String, ids: List<String>): PendingIntent = PendingIntent.getBroadcast(
        context, ("$key#$action").hashCode(),
        Intent(context, ErrandNearReceiver::class.java).setAction(action)
            .putExtra(ErrandNearReceiver.EXTRA_KEY, key)
            .putExtra(ErrandNearReceiver.EXTRA_IDS, ids.toTypedArray()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun builder(context: Context, title: String, text: String, key: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("MEKA · errands nearby").build(),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openIntent(context, key))
            .setAutoCancel(true)

    /** "Near Tesco" · "milk, batteries" with Done (ticks them all off). */
    @SuppressLint("MissingPermission") // WorkAlerts.canPost checks it; a late revoke is caught
    fun post(context: Context, n: ErrandNearNotice): Boolean {
        if (!WorkAlerts.canPost(context)) return false
        ensureChannel(context)
        val b = builder(context, n.title, n.text, n.key)
            .addAction(0, "Done", actionIntent(context, ErrandNearReceiver.ACTION_DONE, n.key, n.ids))
        return try { NotificationManagerCompat.from(context).notify(notificationId(n.key), b.build()); true } catch (e: SecurityException) { false }
    }

    /** After Done: "Done at Tesco · milk and batteries" with Undo for a few minutes, silent. */
    @SuppressLint("MissingPermission")
    fun postDone(context: Context, key: String, line: String, ids: List<String>) {
        if (!WorkAlerts.canPost(context)) return
        ensureChannel(context)
        val b = builder(context, line, "Ticked off in Errands", key)
            .setSilent(true)
            .setTimeoutAfter(ErrandPlaceRules.DONE_NOTE_MS)
            .addAction(0, "Undo", actionIntent(context, ErrandNearReceiver.ACTION_UNDO, key, ids))
        try { NotificationManagerCompat.from(context).notify(notificationId(key), b.build()) } catch (e: SecurityException) { }
    }

    fun cancel(context: Context, key: String) = NotificationManagerCompat.from(context).cancel(notificationId(key))
}

/** Android's proximity alert (arriving near a saved place), and Done / Undo on its notification. */
class ErrandNearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_NEAR -> {
                        // Leaving says nothing; arriving reminds once in a few hours, only while something waits there.
                        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return@launch
                        if (!ErrandProximity.isOn(app)) return@launch
                        runCatching { app.core.tick() }
                        val now = System.currentTimeMillis()
                        if (!ErrandPlaceRules.shouldRemind(ErrandProximity.lastReminded(app)[key], now)) return@launch
                        val n = ErrandPlaceRules.near(app.core.errandsView.value, key) ?: return@launch
                        if (ErrandProximity.post(app, n)) ErrandProximity.markReminded(app, key, now)
                    }
                    ACTION_DONE -> {
                        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty()
                        val view = app.core.errandsView.value
                        val group = view.places.firstOrNull { it.key == key }
                        val ticked = ids.filter { id -> runCatching { app.core.errandDone(id, true) }.getOrDefault(false) }
                        if (ticked.isEmpty()) { ErrandProximity.cancel(app, key); return@launch }
                        val titles = group?.rows?.filter { it.id in ticked }?.map { it.title }.orEmpty()
                        ErrandProximity.postDone(app, key, ErrandPlaceRules.doneLine(group?.place ?: key, titles), ticked)
                    }
                    ACTION_UNDO -> {
                        intent.getStringArrayExtra(EXTRA_IDS)?.forEach { id -> runCatching { app.core.errandDone(id, false) } }
                        ErrandProximity.cancel(app, key)
                    }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_NEAR = "os.meka.errands.NEAR"
        const val ACTION_DONE = "os.meka.errands.DONE"
        const val ACTION_UNDO = "os.meka.errands.UNDO"
        const val EXTRA_KEY = "os.meka.errands.key"
        const val EXTRA_IDS = "os.meka.errands.ids"
    }
}
