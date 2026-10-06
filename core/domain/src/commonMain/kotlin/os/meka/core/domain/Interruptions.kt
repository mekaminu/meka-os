package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Interruptions, the north-star metric MEKA can count first (ADR-013): notifications this device actually posted at
 * Critical, Needs a decision or Heads-up. Digests (and heads-ups folded into one) never interrupt, so they don't count.
 * Lower is better.
 *
 * Counts only, never text: one `interruption_day` entity per device per local day, id `<deviceId>.d<epochDay>`,
 * fields `day`, `device`, `critical`, `action`, `headsUp`. Only the device named in the id writes it, so every field is
 * plain LWW and two devices never conflict; the review adds them up across devices. When counting began (the first
 * time any device could post) is the `context_mode` entity `interruptions`, field `countingSince` (a local epoch day,
 * written once), so a week before then says "counted from …" instead of a misleading 0.
 */
object InterruptionFields {
    const val DAY = "day"
    const val DEVICE = "device"
    const val CRITICAL = "critical"
    const val ACTION = "action"
    const val HEADS_UP = "headsUp"
    /** On the `context_mode` entity [Interruptions.ENTITY_ID]. */
    const val COUNTING_SINCE = "countingSince"
}

/** One week's interruptions, added up across devices. */
data class InterruptionWeek(
    val critical: Int,
    val action: Int,
    val headsUp: Int,
    /** The week before's total, when that whole week was counted. */
    val before: Int?,
) {
    val total: Int get() = critical + action + headsUp
}

/** Pure rules, unit-tested without a replica. */
object InterruptionRules {
    fun entityId(deviceId: String, day: Long): String = "$deviceId.d$day"

    /** What counts from what a device posted: only the interrupting tiers. */
    fun counts(posted: List<Notice>): Triple<Int, Int, Int> = Triple(
        posted.count { it.tier == NoticeTier.CRITICAL },
        posted.count { it.tier == NoticeTier.ACTION },
        posted.count { it.tier == NoticeTier.HEADS_UP },
    )

    /**
     * The review's metric for the week starting [weekStart]. [since] is when counting began (null: never yet).
     * "7" with "5 heads-ups · 2 needed a decision · 3 fewer than the week before".
     */
    fun metric(weekStart: Long, since: Long?, week: InterruptionWeek): NorthStarMetric {
        val label = "Interruptions"
        if (since == null) return NorthStarMetric(KEY, label, null, "Counted once MEKA can notify you on a device")
        if (since > weekStart + 6) return NorthStarMetric(KEY, label, null, "Counted from ${CivilDate.shortLabel(since)}")
        val parts = listOfNotNull(
            week.critical.takeIf { it > 0 }?.let { "$it critical" },
            week.action.takeIf { it > 0 }?.let { "$it needed a decision" },
            week.headsUp.takeIf { it > 0 }?.let { ShutdownRules.count(it, "heads-up") },
        ).ifEmpty { listOf("None reached you") }
        val compared = when {
            since > weekStart -> "counting since ${CivilDate.shortLabel(since)}"
            week.before == null -> null
            week.total < week.before -> "${week.before - week.total} fewer than the week before"
            week.total > week.before -> "${week.total - week.before} more than the week before"
            week.total == 0 -> null
            else -> "the same as the week before"
        }
        return NorthStarMetric(KEY, label, week.total.toString(), (parts + listOfNotNull(compared)).joinToString(" · "))
    }

    const val KEY = "interruptions"
}

class Interruptions(private val replica: Replica, private val calendar: LocalCalendar = LocalCalendar.UTC) {
    fun countingSince(): Long? =
        replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(InterruptionFields.COUNTING_SINCE)?.longOrNull

    /**
     * What this device just posted, called by the platform each time it could post (even with nothing), so counting
     * starts the first time a device can notify you. Writes nothing when nothing interrupting was posted and counting
     * has already started.
     */
    fun record(posted: List<Notice>, nowMs: Long) {
        val day = calendar.epochDayOf(nowMs)
        if (countingSince() == null) {
            replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(InterruptionFields.COUNTING_SINCE to day.fv()))
        }
        val (c, a, h) = InterruptionRules.counts(posted)
        if (c + a + h == 0) return
        val id = InterruptionRules.entityId(replica.deviceId, day)
        val s = replica.entity(EntityTypes.INTERRUPTION_DAY, id)
        fun cur(f: String) = s?.get(f)?.longOrNull ?: 0L
        replica.commitLocal(
            EntityTypes.INTERRUPTION_DAY, id,
            buildMap {
                if (s == null) {
                    put(InterruptionFields.DAY, day.fv())
                    put(InterruptionFields.DEVICE, replica.deviceId.fv())
                }
                if (c > 0) put(InterruptionFields.CRITICAL, (cur(InterruptionFields.CRITICAL) + c).fv())
                if (a > 0) put(InterruptionFields.ACTION, (cur(InterruptionFields.ACTION) + a).fv())
                if (h > 0) put(InterruptionFields.HEADS_UP, (cur(InterruptionFields.HEADS_UP) + h).fv())
            },
        )
    }

    /** The week starting [weekStart] (a Monday), all devices. */
    fun week(weekStart: Long): InterruptionWeek {
        val byDay = replica.entities(EntityTypes.INTERRUPTION_DAY).mapNotNull { s ->
            val d = s[InterruptionFields.DAY].longOrNull ?: return@mapNotNull null
            Triple(d, s[InterruptionFields.CRITICAL].longOrNull ?: 0L, s[InterruptionFields.ACTION].longOrNull ?: 0L) to
                (s[InterruptionFields.HEADS_UP].longOrNull ?: 0L)
        }
        fun sum(from: Long): Triple<Int, Int, Int> {
            val inWeek = byDay.filter { it.first.first in from..(from + 6) }
            return Triple(
                inWeek.sumOf { it.first.second }.toInt(),
                inWeek.sumOf { it.first.third }.toInt(),
                inWeek.sumOf { it.second }.toInt(),
            )
        }
        val (c, a, h) = sum(weekStart)
        val since = countingSince()
        val before = if (since != null && since <= weekStart - 7) sum(weekStart - 7).let { it.first + it.second + it.third } else null
        return InterruptionWeek(c, a, h, before)
    }

    fun metric(weekStart: Long): NorthStarMetric = InterruptionRules.metric(weekStart, countingSince(), week(weekStart))

    companion object {
        const val ENTITY_ID = "interruptions"
    }
}
