package os.meka.core.domain

/*
 * Call assistant, completeness (build plan; non-AI, pure): **the week ahead's weekly "calls taken" line**. Under the
 * week's summary: "Calls · your call assistant took 4 in the last 7 days · 1 urgent · 2 likely automated · 1 missed at
 * work". Read from the held calls (synced, so the Fold and the Mac say the same); the calls the Fold alone screened stay
 * in Activity → Calls. Counts only: never who rang or what they said (the week ahead is read aloud).
 */
object CallWeekRules {
    const val DAYS = 7

    /** The held calls from the last [DAYS] days up to [nowMs] (today and the six days before it). */
    private fun recent(held: List<HeldCall>, nowMs: Long, cal: LocalCalendar): List<CapturedItem> {
        val today = cal.epochDayOf(nowMs)
        return held.map { it.item }.filter { it.app == CaptureApp.PHONE && it.atMs <= nowMs && cal.epochDayOf(it.atMs) in (today - DAYS + 1)..today }
    }

    /** The line, or null when the assistant took nothing and nothing was missed at work in the last [DAYS] days. */
    fun line(held: List<HeldCall>, nowMs: Long, cal: LocalCalendar): String? {
        val calls = recent(held, nowMs, cal)
        val taken = calls.filter { it.fromCallAssistant }
        val missed = calls.count { it.kind == CaptureKind.MISSED_CALL }
        if (taken.isEmpty() && missed == 0) return null
        val urgent = taken.count { CallNoticeRules.urgent(it) }
        val automated = taken.count { it.automated }
        val scams = taken.count { it.endedAsScam && !it.automated }
        val head = if (taken.isEmpty()) "Calls · your call assistant took none in the last $DAYS days"
        else "Calls · your call assistant took ${taken.size} in the last $DAYS days"
        return listOfNotNull(
            head,
            urgent.takeIf { it > 0 }?.let { "$it urgent" },
            automated.takeIf { it > 0 }?.let { "$it likely automated" },
            scams.takeIf { it > 0 }?.let { "$it ended as a scam" },
            missed.takeIf { it > 0 }?.let { "$it missed at work" },
        ).joinToString(" · ")
    }

    /** "Calls: your call assistant took 4 in the last 7 days, 1 urgent." for the week ahead read aloud. */
    fun spoken(line: String): String = line.replaceFirst("Calls · ", "Calls: ").replace(" · ", ", ") + "."
}
