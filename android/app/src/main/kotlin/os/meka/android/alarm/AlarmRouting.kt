package os.meka.android.alarm

import os.meka.core.domain.AlarmRing
import os.meka.core.domain.AlarmRules

/**
 * The Fold's side of the wake alarm (Alarms, slice 1) that can be tested without a phone: when the ringing service
 * rings, how loud, and when the dismiss slider counts.
 */
object AlarmRouting {
    const val CHANNEL = "alarm"
    /** Negative, so it never clashes with the governor's or the ongoing notifications' (positive) ids. */
    const val NOTIFICATION_ID = -7301
    /** Without "Alarms & reminders" allowed, the alarm uses a window this long (ADR-007) and the shutdown says so. */
    const val FALLBACK_WINDOW_MS = 5 * 60_000L
    /** The volume rises from [START_VOLUME] to full over this long. */
    const val RAMP_MS = 30_000L
    const val START_VOLUME = 0.05f
    /** An alarm delivered this much early (a windowed alarm, a clock nudge) still rings. */
    const val EARLY_TOLERANCE_MS = 60_000L
    /** How far along the track the slider must go to dismiss. */
    const val DISMISS_AT = 0.85f

    /** Gradual volume: quiet at first, rising on an ease-in curve to full at [RAMP_MS]. */
    fun volumeAt(elapsedMs: Long): Float {
        if (elapsedMs >= RAMP_MS) return 1f
        val t = (elapsedMs.toFloat() / RAMP_MS).coerceIn(0f, 1f)
        return START_VOLUME + (1f - START_VOLUME) * t * t
    }

    /** Whether [ring] (the core's next alarm) should be ringing now for an alarm that fired as [id] (null: any). */
    fun shouldRing(ring: AlarmRing?, id: String?, nowMs: Long): Boolean =
        ring != null && (id == null || ring.id == id) &&
            nowMs >= ring.ringAtMs - EARLY_TOLERANCE_MS && nowMs < ring.ringAtMs + AlarmRules.RING_FOR_MS

    fun dismissed(fraction: Float): Boolean = fraction >= DISMISS_AT

    /** "Wake up · 06:45". */
    fun title(ring: AlarmRing): String = "${ring.title} · ${ring.timeLabel}"

    /** What the alarm manager holds: changes only when the alarm or its time does. */
    fun signature(ring: AlarmRing?): String = ring?.let { "${it.id}@${it.ringAtMs}" } ?: ""
}
