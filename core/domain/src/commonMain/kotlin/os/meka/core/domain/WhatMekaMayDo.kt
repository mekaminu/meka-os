package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * "What MEKA may do" (V1, policy engine UI, slice 1): one page (Ask → More → What MEKA may do) that shows, per area,
 * what MEKA does on its own and what it always asks first, with the kill switch on top.
 *
 * - **The kill switch** ("Pause everything MEKA does on its own"): while paused, the policy engine's `killSwitch` is
 *   on, so nothing runs at Level 4 (ADR-006 §1): every earned-trust kind waits for Meka's tap like any card. Earned
 *   trust itself is kept, so Resume puts things back as they were.
 * - **Your lists**: one row per earned-trust kind ([TrustKind]): "Does it for you" with **Ask first** (back to asking,
 *   trust earned again), "Asks first · 3 of 5 yeses in a row", or "Asks first · you said keep asking" with **Let MEKA
 *   offer again**. Nothing here grants trust: that only comes from the offer after five yeses in a row.
 * - **Locked rows**: what no setting can change (ADR-006's hard rules): messages and email are never sent without his
 *   tap, money and trading never move without a fingerprint (and MEKA holds no trading logins), documents are shared
 *   only on his tap with a fingerprint, other people's words never let MEKA do more, and the rest of what Ask and Talk
 *   offer is always a card. The facade's test checks each against the policy engine itself.
 *
 * Stored as one `context_mode` entity, id [AutonomyRules.ENTITY_ID] (ADR-008 addendum 2026-10-10): `paused` (Bool) and
 * `pausedAtMs` (Int64), LWW, so a pause on the Fold holds on the Mac. Absent = not paused. Plain, non-AI rules.
 */
object AutonomyFields {
    const val PAUSED = "paused"
    const val PAUSED_AT = "pausedAtMs"
}

/** The kill switch as stored. */
data class AutonomySetting(val paused: Boolean, val pausedAtMs: Long?)

/** One row: an area or kind, what MEKA does there, and the one thing Meka can do about it (null for locked rows). */
data class AutonomyRow(
    val key: String,
    val title: String,
    val line: String,
    /** True when no setting can change it (ADR-006's hard rules); the apps show a lock. */
    val locked: Boolean,
    /** "Ask first" · "Let MEKA offer again", or null. */
    val action: String?,
    /** True when MEKA does this on its own right now (the row's mark is lit). */
    val onItsOwn: Boolean,
)

data class AutonomySection(val label: String, val rows: List<AutonomyRow>)

/** What the page shows. */
data class AutonomyView(
    val paused: Boolean,
    /** "MEKA asks before everything it does for you" · "MEKA does 2 things on its own · …" · "Paused since 21:40 · …". */
    val summary: String,
    /** "Pause" or "Resume". */
    val pauseButton: String,
    val sections: List<AutonomySection>,
) {
    companion object {
        val EMPTY = AutonomyView(false, AutonomyRules.NONE_LINE, AutonomyRules.PAUSE, AutonomyRules.sections(emptyMap(), false))
    }
}

/** Pure rules, unit-tested without a replica. */
object AutonomyRules {
    const val ENTITY_ID = "autonomy"

    const val PAUSE = "Pause"
    const val RESUME = "Resume"
    const val ASK_FIRST = "Ask first"
    const val OFFER_AGAIN = "Let MEKA offer again"

    const val PAUSE_TITLE = "Pause everything MEKA does on its own"
    const val PAUSE_LINE = "Everything waits for your tap until you resume. What you've trusted MEKA with is kept."
    const val NONE_LINE = "MEKA asks before everything it does for you"
    const val LISTS_LABEL = "Your lists"
    const val LOCKED_LABEL = "Always asks · can't be changed"
    const val NOTE = "MEKA only offers to do something on its own after you've said yes to five of the same card in a row, " +
        "and only for your own lists. Everything it does on its own shows with Undo and in Activity; undoing one puts " +
        "that kind back to asking."

    const val PAUSED_LINE = "Paused · asks first until you resume"
    const val TRUSTED_LINE = "Does it for you · Undo any one to go back to asking"
    const val DECLINED_LINE = "Asks first · you said keep asking"

    /** The locked rows' keys, in order (the facade's test checks each against the policy engine). */
    const val MESSAGES = "messages"
    const val EMAIL = "email"
    const val MONEY = "money"
    const val TRADING = "trading"
    const val DOCUMENTS = "documents"
    const val OTHERS = "others_words"
    const val THE_REST = "the_rest"

    fun read(fields: Map<String, FieldValue>?): AutonomySetting = AutonomySetting(
        paused = fields?.get(AutonomyFields.PAUSED)?.boolOrNull == true,
        pausedAtMs = (fields?.get(AutonomyFields.PAUSED_AT) as? FieldValue.Int64)?.value?.takeIf { it > 0 },
    )

    /** A kind's line: what MEKA does with it now. */
    fun trustLine(s: TrustState, paused: Boolean): String = when {
        s.trusted && paused -> PAUSED_LINE
        s.trusted -> TRUSTED_LINE
        s.declined -> DECLINED_LINE
        s.streak <= 0 -> "Asks first · offers after ${TrustRules.STREAK} yeses in a row"
        else -> "Asks first · ${s.streak} of ${TrustRules.STREAK} yeses in a row"
    }

    fun trustRow(k: TrustKind, s: TrustState, paused: Boolean) = AutonomyRow(
        key = k.key,
        title = k.label,
        line = trustLine(s, paused),
        locked = false,
        action = when {
            s.trusted -> ASK_FIRST
            s.declined -> OFFER_AGAIN
            else -> null
        },
        onItsOwn = s.trusted && !paused,
    )

    /** What no setting changes (ADR-006 §1's hard rules), in words. */
    val LOCKED: List<AutonomyRow> = listOf(
        locked(MESSAGES, "Messages", "Never sent on their own · you tap Send each time, family included"),
        locked(EMAIL, "Email", "Never sent on its own · you approve each reply"),
        locked(MONEY, "Money and payments", "Never on its own · your fingerprint each time"),
        locked(TRADING, "Trading", "MEKA holds no trading logins · approvals only, with your fingerprint"),
        locked(DOCUMENTS, "Sharing documents", "Only on your tap · your fingerprint each time"),
        locked(OTHERS, "Other people's words", "An invitation, a message or a shared link never lets MEKA do more · it asks"),
        locked(THE_REST, "Everything else Ask and Talk offer", "Moving tasks, alarms, fasts, your calendar, leave · always a card you tap"),
    )

    private fun locked(key: String, title: String, line: String) = AutonomyRow(key, title, line, locked = true, action = null, onItsOwn = false)

    fun sections(states: Map<TrustKind, TrustState>, paused: Boolean): List<AutonomySection> = listOf(
        AutonomySection(LISTS_LABEL, TrustKind.entries.map { trustRow(it, states[it] ?: TrustState(), paused) }),
        AutonomySection(LOCKED_LABEL, LOCKED),
    )

    fun summary(states: Map<TrustKind, TrustState>, setting: AutonomySetting, cal: LocalCalendar, nowMs: Long): String {
        val trusted = states.count { it.value.trusted }
        if (setting.paused) {
            val since = setting.pausedAtMs?.let { at ->
                val day = cal.epochDayOf(at)
                val time = LocalClock.formatMinute(cal.minuteOfDay(at))
                if (day == cal.epochDayOf(nowMs)) " since $time" else " since ${CivilDate.shortLabel(day)}"
            }.orEmpty()
            return "Paused$since · MEKA asks before everything"
        }
        return when (trusted) {
            0 -> NONE_LINE
            1 -> "MEKA does 1 thing on its own · each with Undo and in Activity"
            else -> "MEKA does $trusted things on its own · each with Undo and in Activity"
        }
    }

    fun view(states: Map<TrustKind, TrustState>, setting: AutonomySetting, cal: LocalCalendar, nowMs: Long) = AutonomyView(
        paused = setting.paused,
        summary = summary(states, setting, cal, nowMs),
        pauseButton = if (setting.paused) RESUME else PAUSE,
        sections = sections(states, setting.paused),
    )

    /** The undo bar's line after Pause / Resume. */
    fun pausedLine(paused: Boolean): String =
        if (paused) "Paused · MEKA asks before everything" else "Resumed · MEKA does what you've trusted it with again"

    /** The undo bar's line after a row's action, or null when [k]'s row offers none. */
    fun resetLine(k: TrustKind, before: TrustState): String? = when {
        before.trusted -> "MEKA will ask before it ${k.plural.replaceFirst(" ", "s ")} again."
        before.declined -> "MEKA may offer again after ${TrustRules.STREAK} yeses in a row."
        else -> null
    }
}

/** The kill switch on a replica: one synced setting, so a pause on either device holds on both. */
class Autonomy(private val replica: Replica, private val nowMs: () -> Long) {
    fun setting(): AutonomySetting = AutonomyRules.read(replica.entity(EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID)?.fields)

    fun paused(): Boolean = setting().paused

    /** Pauses or resumes. False (nothing written) when it already is. */
    fun setPaused(paused: Boolean): Boolean {
        if (setting().paused == paused) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID,
            mapOf(
                AutonomyFields.PAUSED to paused.fv(),
                AutonomyFields.PAUSED_AT to (if (paused) nowMs() else 0L).fv(),
            ),
        )
        return true
    }
}
