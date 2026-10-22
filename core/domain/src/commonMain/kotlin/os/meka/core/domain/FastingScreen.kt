package os.meka.core.domain

/**
 * The Fasting screen rebuilt to the approved design (docs/design/fasting/fasting.dc.html, Meka 2026-10-21 "Looks
 * good"): what every part of it says, from [FastingView] and the clock. Pure, non-AI, nothing stored; the apps draw it
 * and tick the clock themselves. Tracking only: no food, calorie or weight advice. The stages are typical timings with
 * the strength of the evidence named on each, exactly as in the design (no "cell regeneration"; autophagy is early
 * evidence, mostly from animal studies), and the note under them sends fasts over 72 hours to the GP first.
 */
data class FastingStage(
    val id: String,
    /** "Switching to fat". */
    val name: String,
    /** "12–16h". */
    val whenLabel: String,
    val body: String,
    /** "Well established" · "Good evidence" · "Moderate evidence" · "Early evidence". */
    val evidence: String,
    val fromHours: Int,
    val toHours: Int,
)

/** How the evidence badge is coloured: gold for strong, plain for moderate, amber for early. */
enum class EvidenceTone { STRONG, MODERATE, EARLY }

/** One row of the stages list. */
data class FastingStageRow(
    val stage: FastingStage,
    /** The fast has reached it (its dot gold). */
    val reached: Boolean,
    /** The fast is in it now (its dot glows, its row lit). */
    val here: Boolean,
    val tone: EvidenceTone,
)

/** The "What's happening now" card. */
data class FastingNowCard(
    val stage: FastingStage,
    val tone: EvidenceTone,
    /** "Next: ketones rising in 2:30"; null past the last stage. */
    val next: String?,
)

/** A ring marker (4h, 8h, 12h…): where on the ring (0 to 1 of the way round) and whether the fast has passed it. */
data class FastingMark(val label: String, val fraction: Float, val passed: Boolean)

/** One of this week's seven small rings. */
data class FastingWeekRing(
    /** "Thu". */
    val day: String,
    /** How full, 0 to 1 (a fast past its goal is full). */
    val fill: Float,
    val onGoal: Boolean,
    val isToday: Boolean,
    /** "Thu, 16h 40m, on goal" for TalkBack and VoiceOver. */
    val spoken: String,
)

/** One History row: "Tue 20 → Wed 21" · "16h 40m" · "on goal" (or "6h short"). */
data class FastingHistoryRow(val id: String, val span: String, val length: String, val goal: String, val onGoal: Boolean)

data class FastingScreen(
    /** The plan chip at the top: "16:8". */
    val planChip: String,
    /** Over the time in the ring: "Fasting" · "Goal reached" · "Not fasting" (drawn upper case). */
    val topLine: String,
    /** The big time in the ring: "13:30" (hours:minutes in), or the plan's goal "16 h" when not fasting. */
    val bigTime: String,
    /** Under it: "Ends in 2:30 · 12:00" · "+0:40 past your goal" · "Next fast from 20:00". */
    val subLine: String,
    /** The ring's fill, 0 to 1. */
    val fraction: Float,
    /** The glowing tip shows only while a fast runs. */
    val showTip: Boolean,
    val marks: List<FastingMark>,
    /** The Started and Goal tiles ("Tue 20:00", "Wed 12:00 · 16h"); null when not fasting. */
    val startedTile: String?,
    val goalTile: String?,
    /** "Start fast" or "End fast". */
    val mainLabel: String,
    /** The card under the main button; null when not fasting. */
    val now: FastingNowCard?,
    val stages: List<FastingStageRow>,
    /** "4 of 6 on goal · 3-day streak"; null with no fast this week. */
    val weekLine: String?,
    val week: List<FastingWeekRing>,
    val history: List<FastingHistoryRow>,
    /**
     * This week's rings are drawn only once there's something in them (a fast this week, or one running): seven empty
     * rings read as broken (the quality gate's re-shot of Goals); otherwise [FastingScreenRules.WEEK_EMPTY].
     */
    val weekShown: Boolean = true,
    /** The fast just ended, while "Undo end" can still bring it back; null otherwise. */
    val undoEndId: String? = null,
    /** Goals and habits' small Fasting tile: "Fasting · 13:30" · "Goal reached · 16:40" · "Not fasting". */
    val tileTitle: String = "",
) {
    val isFasting: Boolean get() = startedTile != null
}

object FastingScreenRules {
    const val MORE_OPTIONS_PROMPT = "Longer fast or started earlier?"
    const val MORE_OPTIONS = "More options"
    const val STAGES_TITLE = "Stages · tap for more"
    const val NOW_TITLE = "What’s happening now"
    /** Under the stages, word for word from the design. */
    const val STAGES_NOTE = "Timings are typical, not exact: they vary with your last meal, activity and body. For fasts over 72 hours, or if you take medication or have a health condition, check with your GP first."
    /** Shown before a fast over [GP_NOTE_OVER_HOURS] can be started (More options). */
    const val GP_NOTE = "Fasts over 72 hours: check with your GP first, especially if you take medication or have a health condition."
    const val GP_NOTE_OVER_HOURS = 72
    /** Where this week's rings go before there's a fast in them. */
    const val WEEK_EMPTY = "Each day you fast this week fills a ring here."
    /** History with nothing in it yet (never a heading over an empty space). */
    const val HISTORY_EMPTY = "Every fast you finish is kept here."
    const val UNDO_END = "Undo end"
    /** The ring's markers for a daily fast. */
    val DAILY_MARKS = listOf(4, 8, 12)
    private const val AUTOPHAGY = "auto"
    private const val HOUR_MS = 3_600_000L

    val STAGES: List<FastingStage> = listOf(
        FastingStage("digest", "Digesting", "0–4h", "Your blood sugar and insulin rise after your last meal, then start to settle as digestion finishes.", "Well established", 0, 4),
        FastingStage("sugar", "Blood sugar settling", "4–12h", "Insulin falls and your body draws on glycogen, the sugar stored in your liver, to keep blood sugar steady.", "Well established", 4, 12),
        FastingStage("fat", "Switching to fat", "12–16h", "As glycogen runs low, your body burns more fat for fuel and ketone levels begin to rise. Timing varies a lot from person to person.", "Well established", 12, 16),
        FastingStage("ketones", "Ketones rising", "16–24h", "Ketones keep climbing and many people find hunger eases off. Keep drinking water.", "Good evidence", 16, 24),
        FastingStage("gh", "Growth hormone up", "24h+", "Studies show growth hormone rises during longer fasts, which helps preserve muscle while you burn fat.", "Moderate evidence", 24, 48),
        FastingStage(AUTOPHAGY, "Cell clean-up (autophagy)", "likely 24h+", "Fasting is thought to increase autophagy, the body recycling worn-out parts of cells. When it starts in people isn’t known: most of the evidence is from animal studies.", "Early evidence", 24, 72),
        FastingStage("long", "Deep ketosis", "48–72h", "Ketones are high and fat is the main fuel. Electrolytes (salt, potassium, magnesium) matter more now. Beyond 72 hours, only with your GP’s say-so.", "Good evidence", 48, 999),
    )

    fun tone(evidence: String): EvidenceTone = when (evidence) {
        "Well established", "Good evidence" -> EvidenceTone.STRONG
        "Early evidence" -> EvidenceTone.EARLY
        else -> EvidenceTone.MODERATE
    }

    /** "13:30": hours and minutes (hours can pass 24). */
    fun hm(ms: Long): String {
        val min = ms.coerceAtLeast(0) / 60_000L
        return "${min / 60}:${(min % 60).toString().padStart(2, '0')}"
    }

    /**
     * A countdown, "2:30": rounded up to the minute, so with 2 h 29 m 50 s to go it still says 2:30 (the time in plus
     * the time left always make the goal; it reads 0:00 only once the goal is reached).
     */
    fun left(ms: Long): String = hm((ms.coerceAtLeast(0) + 59_999L) / 60_000L * 60_000L)

    /** "16h 40m", "45m", "16h" (no "16 h 00 m"); two days up "4d 18h". */
    fun length(ms: Long): String {
        val min = ms.coerceAtLeast(0) / 60_000L
        val h = min / 60; val m = min % 60
        return when {
            h >= 48 -> "${h / 24}d ${h % 24}h"
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m.toString().padStart(2, '0')}m"
        }
    }

    /** "on goal", "6h short", "40m short". */
    fun goalWord(plannedMs: Long, actualMs: Long): String =
        if (actualMs >= plannedMs) "on goal" else "${length(plannedMs - actualMs)} short"

    /** The stage a fast [hours] in is in (autophagy runs alongside, never "now"). */
    fun stageAt(hours: Double): FastingStage =
        STAGES.firstOrNull { it.id != AUTOPHAGY && hours >= it.fromHours && hours < it.toHours } ?: STAGES.first()

    /** "Next: ketones rising in 2:30"; null past the last stage. */
    fun nextLine(elapsedMs: Long): String? {
        val hours = elapsedMs / HOUR_MS.toDouble()
        val next = STAGES.firstOrNull { it.id != AUTOPHAGY && it.fromHours > hours } ?: return null
        return "Next: ${next.name.lowercase()} in ${left(next.fromHours * HOUR_MS - elapsedMs)}"
    }

    /** The ring's markers: 4h, 8h, 12h under a daily goal; each whole day under an extended one. */
    fun marks(targetHours: Int, elapsedMs: Long): List<FastingMark> {
        val goal = targetHours.coerceAtLeast(1)
        val at = if (goal > 24) (24 until goal step 24).toList() else DAILY_MARKS.filter { it < goal }
        return at.map { h -> FastingMark("${h}h", h.toFloat() / goal, elapsedMs >= h * HOUR_MS) }
    }

    fun screen(v: FastingView, nowMs: Long): FastingScreen {
        val cur = v.current
        val plan = FastingRules.planLabel(v.plan)
        val elapsed = cur?.let { nowMs - it.startedAtMs } ?: 0L
        val hours = elapsed / HOUR_MS.toDouble()
        val stages = STAGES.map { s ->
            val reached = cur != null && hours >= s.fromHours
            val here = reached && hours < s.toHours
            FastingStageRow(s, reached, here, tone(s.evidence))
        }
        val now = cur?.let {
            val s = stageAt(hours)
            FastingNowCard(s, tone(s.evidence), nextLine(elapsed))
        }
        val (top, sub) = when {
            cur == null -> "Not fasting" to "Next fast from ${FastingRules.hm(v.plan.eatingEndMin)}"
            nowMs >= cur.goalAtMs -> "Goal reached" to "+${hm(nowMs - cur.goalAtMs)} past your goal"
            cur.extended -> "Fasting" to "Ends in ${FastingRules.longDuration(cur.goalAtMs - nowMs)} · ${cur.goalWhen}"
            else -> "Fasting" to "Ends in ${left(cur.goalAtMs - nowMs)} · ${cur.goalWhen}"
        }
        return FastingScreen(
            planChip = plan,
            topLine = top,
            bigTime = if (cur == null) v.idleRingTop else hm(elapsed),
            subLine = sub,
            fraction = cur?.progress(nowMs) ?: 0f,
            showTip = cur != null && elapsed > 0,
            marks = marks(cur?.let { ((it.goalAtMs - it.startedAtMs + HOUR_MS - 1) / HOUR_MS).toInt() } ?: v.plan.targetHours, elapsed),
            startedTile = cur?.startedTile,
            goalTile = cur?.goalTile,
            mainLabel = if (cur == null) "Start fast" else "End fast",
            now = now,
            stages = stages,
            weekLine = weekLine(v),
            week = week(v, nowMs),
            history = v.history.fasts.map { f ->
                FastingHistoryRow(f.id, f.span, length(f.actualMs), goalWord(f.plannedMs, f.actualMs), f.reachedGoal)
            },
            weekShown = cur != null || v.week.any { it.hours > 0 },
            undoEndId = v.last?.takeIf { cur == null && it.canResume }?.id,
            tileTitle = if (cur == null) top else "$top · ${hm(elapsed)}",
        )
    }

    /** "4 of 6 on goal · 3-day streak": days with a fast this week, how many on goal, and the run of on-goal days to today. */
    fun weekLine(v: FastingView): String? {
        val days = v.week.filter { it.hours > 0 }
        if (days.isEmpty()) return null
        val on = days.count { it.reachedGoal }
        // The streak runs back from today (or yesterday, while today has nothing yet) through on-goal days.
        val back = v.week.reversed().let { if (it.firstOrNull()?.hours == 0.0) it.drop(1) else it }
        val streak = back.takeWhile { it.reachedGoal }.size
        return "$on of ${days.size} on goal" + if (streak >= 2) " · $streak-day streak" else ""
    }

    /** Seven small rings, oldest first; today's fills with a running fast. */
    fun week(v: FastingView, nowMs: Long): List<FastingWeekRing> = v.week.map { d ->
        val running = v.current?.takeIf { d.isToday }
        val fill = when {
            d.hours > 0 && d.goalHours > 0 -> (d.hours / d.goalHours).toFloat()
            running != null -> running.progress(nowMs)
            else -> 0f
        }.coerceIn(0f, 1f)
        val said = when {
            d.hours > 0 -> "${length((d.hours * HOUR_MS).toLong())}, ${if (d.reachedGoal) "on goal" else "short of the goal"}"
            running != null -> "fasting now"
            else -> "no fast"
        }
        FastingWeekRing(d.label, fill, d.reachedGoal, d.isToday, "${d.label}, $said")
    }
}
