package os.meka.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.core.domain.PhoneCalendarRow
import os.meka.core.domain.PhoneCalendarRules
import os.meka.core.facade.MekaCore

/**
 * The Week board (j) (Meka 2026-10-29 02:12: the study sessions in his phone's calendar showed nowhere): the phone's own
 * calendars (Samsung's "My calendar" and the like; Google's are skipped, the server reads those) are read through
 * Android's calendar provider once he allows Calendar access, from a week back to five weeks ahead, and handed to the
 * core ([MekaCore.setPhoneCalendars]), which keeps them in memory on this phone only: never stored, synced or sent.
 * Read at process start, whenever the app comes to the screen, and a moment after the phone's calendar changes.
 * Navilife never writes to them.
 */
object PhoneCalendars {
    private const val SETTLE_MS = 1_500L
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Reads now (empty when not allowed) and, once allowed, follows the phone's calendar changes. Never throws. */
    fun refresh(context: Context, core: MekaCore, scope: CoroutineScope) {
        val app = context.applicationContext
        if (granted(app) && observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    pending?.cancel()
                    pending = scope.launch { delay(SETTLE_MS); read(app, core) }
                }
            }
            runCatching { app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o); observer = o }
        }
        scope.launch { read(app, core) }
    }

    private suspend fun read(context: Context, core: MekaCore) {
        val (rows, calendars) = if (!granted(context)) emptyList<PhoneCalendarRow>() to emptyList() else withContext(Dispatchers.IO) {
            runCatching { query(context) }.getOrElse { emptyList<PhoneCalendarRow>() to emptyList() }
        }
        runCatching { core.setPhoneCalendars(rows, calendars) }
    }

    private fun query(context: Context): Pair<List<PhoneCalendarRow>, List<Pair<String?, String?>>> {
        val resolver = context.contentResolver
        val calendars = mutableListOf<Pair<String?, String?>>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null,
        )?.use { c -> while (c.moveToNext()) calendars += c.getString(0) to c.getString(1) }

        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - PhoneCalendarRules.DAYS_BACK * day)
            ContentUris.appendId(it, now + PhoneCalendarRules.DAYS_AHEAD * day)
        }.build()
        val rows = mutableListOf<PhoneCalendarRow>()
        resolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.EVENT_TIMEZONE, CalendarContract.Instances.STATUS,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < PhoneCalendarRules.MAX_EVENTS * 2) {
                if (!c.isNull(9) && c.getInt(9) == CalendarContract.Instances.STATUS_CANCELED) continue
                if (!PhoneCalendarRules.reads(c.getString(2))) continue
                rows += PhoneCalendarRow(
                    eventId = c.getLong(0), calendarName = c.getString(1), accountType = c.getString(2), title = c.getString(3),
                    beginMs = c.getLong(4), endMs = c.getLong(5), allDay = c.getInt(6) == 1, location = c.getString(7),
                    timeZone = c.getString(8),
                )
            }
        }
        return rows to calendars
    }
}
