package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Lists (build plan M1): Waiting for (with chase dates), Someday (with kinds) and Decisions (with review dates).
 *
 * - Waiting for is a `commitment` with direction OWED_TO_ME (ADR-008). Its chase date is `followUpAtMs`.
 * - Someday is a Task with lifecycle SOMEDAY and a [SomedayKind]; it never consumes planning capacity.
 * - A Decision is a `decision` entity; its review date is `reviewAtMs`.
 *
 * Chase and review dates are days, not times: they are stored as 09:00 local on that day and read back as the local
 * day, so they survive a clock change and mean the same day on the Fold and the Mac. No AI here: nothing is chased,
 * decided or promoted automatically; MEKA only says what is due.
 */

/** Where a dated list item stands today. */
enum class DueState { NONE, LATER, DUE }

data class WaitingItem(
    val id: String,
    val title: String,
    /** Who or what it's from ("Ada", "the council"). */
    val who: String?,
    val notes: String?,
    val sinceMs: Long,
    val lastChasedMs: Long?,
    /** Local epoch day to chase on; null for no chase date. */
    val chaseDay: Long?,
    val state: DueState,
    /** "Ada · since Mon 5 Oct · chase Thu 8 Oct", "Ada · chase today", "Ada · chase was due Mon 5 Oct". */
    val meta: String,
    val hasConflict: Boolean,
)

data class SomedayGroup(val kind: SomedayKind, val label: String, val items: List<Task>)

data class DecisionItem(
    val id: String,
    val statement: String,
    val rationale: String?,
    val decidedAtMs: Long,
    val reviewDay: Long?,
    val status: DecisionStatus,
    val supersedesId: String?,
    val state: DueState,
    /** "Decided Mon 5 Oct · review 5 Jan", "Review due", "Revisiting". */
    val meta: String,
    val hasConflict: Boolean,
)

/** A preset for a chase or review date: [days] from today, or null for "no date". */
data class DayChoice(val label: String, val days: Int?)

/** Everything the Lists screen shows, computed for one local day. */
data class ListsView(
    /** Open waiting-for items: due chases first (oldest first), then by chase day, then undated (newest first). */
    val waiting: List<WaitingItem>,
    /** Someday items grouped by kind in a fixed order; empty groups are left out. */
    val someday: List<SomedayGroup>,
    /** Active and revisiting decisions: due reviews and revisits first, then newest. Superseded ones are kept, not shown. */
    val decisions: List<DecisionItem>,
    /** Renewals and bills (the radar): what needs doing, what's coming up, and what repeating costs add up to. */
    val renewals: RenewalsView = RenewalsView.EMPTY,
    /** The shared shopping list (family sharing, slice 1): to buy in the order added, then what was got this week. */
    val shopping: ShoppingView = ShoppingView.EMPTY,
) {
    val chaseDue: Int get() = waiting.count { it.state == DueState.DUE }
    val reviewsDue: Int get() = decisions.count { it.state == DueState.DUE }
    val renewalsDue: Int get() = renewals.dueCount
    val dueCount: Int get() = chaseDue + reviewsDue + renewalsDue

    /** "2 to chase · 1 decision to review · 1 renewal due"; null when nothing is due. */
    val dueLine: String? get() = listOfNotNull(
        chaseDue.takeIf { it > 0 }?.let { "$it to chase" },
        reviewsDue.takeIf { it > 0 }?.let { "$it ${if (it == 1) "decision" else "decisions"} to review" },
        renewals.dueLine,
    ).joinToString(" · ").ifEmpty { null }

    val somedayCount: Int get() = someday.sumOf { it.items.size }

    companion object {
        val EMPTY = ListsView(emptyList(), emptyList(), emptyList())
    }
}

object ListRules {
    /** Chase presets offered when adding or after chasing. */
    val CHASE_CHOICES = listOf(
        DayChoice("Tomorrow", 1), DayChoice("In 3 days", 3), DayChoice("Next week", 7),
        DayChoice("In 2 weeks", 14), DayChoice("No date", null),
    )

    /** Review presets for a decision. */
    val REVIEW_CHOICES = listOf(
        DayChoice("In a month", 30), DayChoice("In 3 months", 91), DayChoice("In 6 months", 182),
        DayChoice("In a year", 365), DayChoice("No review", null),
    )

    const val DEFAULT_CHASE_DAYS = 3

    /** Kinds in the order the Someday list groups them (and the pickers offer them). */
    val SOMEDAY_KINDS: List<SomedayKind> = enumValues<SomedayKind>().toList()

    fun kindLabel(k: SomedayKind): String = when (k) {
        SomedayKind.IDEA -> "Ideas"
        SomedayKind.PURCHASE -> "To buy"
        SomedayKind.PROJECT -> "Projects"
        SomedayKind.TRIP -> "Trips"
        SomedayKind.BOOK -> "To read"
        SomedayKind.RESEARCH -> "To look into"
        SomedayKind.APPLICATION -> "Applications"
        SomedayKind.HOME_IMPROVEMENT -> "Home"
        SomedayKind.OTHER -> "Other"
    }

    fun dueState(day: Long?, today: Long): DueState = when {
        day == null -> DueState.NONE
        day <= today -> DueState.DUE
        else -> DueState.LATER
    }

    /** "today", "tomorrow", else "Thu 8 Oct". */
    fun dayWord(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    fun waitingMeta(who: String?, sinceDay: Long, chaseDay: Long?, today: Long): String {
        val parts = mutableListOf<String>()
        who?.takeIf { it.isNotBlank() }?.let { parts += it }
        when {
            chaseDay == null -> parts += if (sinceDay < today) "since ${CivilDate.shortLabel(sinceDay)}" else "since today"
            chaseDay < today -> parts += "chase was due ${CivilDate.shortLabel(chaseDay)}"
            chaseDay == today -> parts += "chase today"
            else -> {
                if (sinceDay < today) parts += "since ${CivilDate.shortLabel(sinceDay)}"
                parts += "chase ${dayWord(chaseDay, today)}"
            }
        }
        return parts.joinToString(" · ")
    }

    fun decisionMeta(decidedDay: Long, reviewDay: Long?, status: DecisionStatus, today: Long): String = when {
        status == DecisionStatus.REVISITING -> "Revisiting"
        status == DecisionStatus.SUPERSEDED -> "Replaced"
        reviewDay != null && reviewDay <= today -> "Review due"
        reviewDay != null -> "Decided ${CivilDate.shortLabel(decidedDay)} · review ${CivilDate.shortLabel(reviewDay)}"
        else -> "Decided ${CivilDate.shortLabel(decidedDay)}"
    }
}

/**
 * Lists commands and the [view] projection over a [Replica]. Every write is an op, so it is offline-first and synced.
 */
class Lists(
    private val replica: Replica,
    private val ids: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today(): Long = calendar.epochDayOf(nowMs())
    private fun dayMs(day: Long): Long = calendar.toEpochMs(day, DATE_MINUTE)
    private fun daysFromToday(days: Int?): FieldValue = days?.let { dayMs(today() + it).fv() } ?: FieldValue.Null

    // ---- Waiting for ----

    fun addWaiting(title: String, who: String? = null, chaseInDays: Int? = ListRules.DEFAULT_CHASE_DAYS): String {
        val t = cleanTitle(title, "Say what you're waiting for")
        checkDays(chaseInDays)
        val id = ids()
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to t.fv(),
            CommitmentFields.DIRECTION to CommitmentDirection.OWED_TO_ME.name.fv(),
            ActionableFields.LIFECYCLE to Lifecycle.WAITING.name.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        who?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[CommitmentFields.COUNTERPARTY_LABEL] = it.take(MAX_TITLE).fv() }
        if (chaseInDays != null) fields[CommitmentFields.FOLLOW_UP_AT] = daysFromToday(chaseInDays)
        replica.commitLocal(EntityTypes.COMMITMENT, id, fields)
        return id
    }

    /** "Chased": records the chase now and sets the next chase [againInDays] from today (null: no next date). */
    fun chased(id: String, againInDays: Int? = ListRules.DEFAULT_CHASE_DAYS) {
        requireWaiting(id)
        checkDays(againInDays)
        replica.commitLocal(
            EntityTypes.COMMITMENT, id,
            mapOf(CommitmentFields.LAST_INTERACTION_AT to nowMs().fv(), CommitmentFields.FOLLOW_UP_AT to daysFromToday(againInDays)),
        )
    }

    /** Sets the chase date [days] from today, or clears it. */
    fun setChase(id: String, days: Int?) {
        requireWaiting(id)
        checkDays(days)
        replica.commitLocal(EntityTypes.COMMITMENT, id, mapOf(CommitmentFields.FOLLOW_UP_AT to daysFromToday(days)))
    }

    /** "Got it": the thing arrived. Done is terminal, so it wins over a chase made concurrently on another device. */
    fun received(id: String) {
        requireWaiting(id)
        replica.commitLocal(
            EntityTypes.COMMITMENT, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.DONE.name.fv(), ActionableFields.COMPLETED_AT to nowMs().fv()),
        )
    }

    fun editWaiting(id: String, title: String? = null, who: String? = null, notes: String? = null) {
        requireWaiting(id)
        val changes = linkedMapOf<String, FieldValue>()
        title?.let { changes[ActionableFields.TITLE] = cleanTitle(it, "Say what you're waiting for").fv() }
        who?.let { w -> changes[CommitmentFields.COUNTERPARTY_LABEL] = w.trim().takeIf { it.isNotEmpty() }?.take(MAX_TITLE)?.fv() ?: FieldValue.Null }
        notes?.let { n -> changes[ActionableFields.NOTES] = n.takeIf { it.isNotBlank() }?.fv() ?: FieldValue.Null }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.COMMITMENT, id, changes)
    }

    fun deleteWaiting(id: String) {
        requireWaiting(id)
        replica.commitLocal(EntityTypes.COMMITMENT, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    // ---- Someday ----

    fun addSomeday(title: String, kind: SomedayKind = SomedayKind.IDEA): String {
        val t = cleanTitle(title, "Someday items need a title")
        val id = ids()
        replica.commitLocal(
            EntityTypes.TASK, id,
            linkedMapOf<String, FieldValue>(
                ActionableFields.TITLE to t.fv(),
                ActionableFields.LIFECYCLE to Lifecycle.SOMEDAY.name.fv(),
                ActionableFields.SOMEDAY_KIND to kind.name.fv(),
                ActionableFields.CREATED_AT to nowMs().fv(),
                ActionableFields.PRIORITY to 0.fv(),
                ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
                ActionableFields.PROVENANCE_SOURCE to "user".fv(),
                ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
            ),
        )
        return id
    }

    /** Moves an open task to Someday (out of Today and the planner). A repeating task can't: it would keep coming back. */
    fun moveToSomeday(taskId: String, kind: SomedayKind = SomedayKind.IDEA) {
        val s = liveTask(taskId)
        val lifecycle = s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) }
        if (lifecycle?.isTerminal == true) throw ValidationException("Only an open task can go to Someday")
        if (s[TaskFields.RECURRENCE] is FieldValue.Text) throw ValidationException("Stop it repeating first")
        replica.commitLocal(
            EntityTypes.TASK, taskId,
            mapOf(
                ActionableFields.LIFECYCLE to Lifecycle.SOMEDAY.name.fv(),
                ActionableFields.SOMEDAY_KIND to kind.name.fv(),
                TaskFields.SCHEDULED_AT to FieldValue.Null,
            ),
        )
    }

    fun setSomedayKind(taskId: String, kind: SomedayKind) {
        requireSomeday(taskId)
        replica.commitLocal(EntityTypes.TASK, taskId, mapOf(ActionableFields.SOMEDAY_KIND to kind.name.fv()))
    }

    /** "Do it now": back to an active task, so it shows in Today and the planner can place it. */
    fun promote(taskId: String) {
        requireSomeday(taskId)
        replica.commitLocal(EntityTypes.TASK, taskId, mapOf(ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv()))
    }

    // ---- Decisions ----

    fun recordDecision(statement: String, rationale: String? = null, reviewInDays: Int? = null): String {
        checkDays(reviewInDays)
        return writeDecision(cleanTitle(statement, "Say what you decided"), rationale, reviewInDays, supersedes = null)
    }

    fun setReview(id: String, days: Int?) {
        requireDecision(id)
        checkDays(days)
        replica.commitLocal(EntityTypes.DECISION, id, mapOf(DecisionFields.REVIEW_AT to daysFromToday(days)))
    }

    /** "Still right": the review is done; the decision stands, reviewed again [againInDays] from today (or never). */
    fun keepDecision(id: String, againInDays: Int?) {
        requireDecision(id)
        checkDays(againInDays)
        replica.commitLocal(
            EntityTypes.DECISION, id,
            mapOf(DecisionFields.STATUS to DecisionStatus.ACTIVE.name.fv(), DecisionFields.REVIEW_AT to daysFromToday(againInDays)),
        )
    }

    /** "Revisit": the decision is open again until it is kept or replaced. */
    fun revisit(id: String) {
        requireDecision(id)
        replica.commitLocal(EntityTypes.DECISION, id, mapOf(DecisionFields.STATUS to DecisionStatus.REVISITING.name.fv()))
    }

    /** Replaces a decision with a new one; the old one is kept as SUPERSEDED (so it isn't re-made) and leaves the list. */
    fun replaceDecision(id: String, statement: String, rationale: String? = null, reviewInDays: Int? = null): String {
        requireDecision(id)
        checkDays(reviewInDays)
        val newId = writeDecision(cleanTitle(statement, "Say what you decided"), rationale, reviewInDays, supersedes = id)
        replica.commitLocal(EntityTypes.DECISION, id, mapOf(DecisionFields.STATUS to DecisionStatus.SUPERSEDED.name.fv()))
        return newId
    }

    fun editDecision(id: String, statement: String? = null, rationale: String? = null) {
        requireDecision(id)
        val changes = linkedMapOf<String, FieldValue>()
        statement?.let { changes[DecisionFields.STATEMENT] = cleanTitle(it, "Say what you decided").fv() }
        rationale?.let { r -> changes[DecisionFields.RATIONALE] = r.trim().takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.DECISION, id, changes)
    }

    fun deleteDecision(id: String) {
        requireDecision(id)
        replica.commitLocal(EntityTypes.DECISION, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    private fun writeDecision(statement: String, rationale: String?, reviewInDays: Int?, supersedes: String?): String {
        val id = ids()
        val fields = linkedMapOf<String, FieldValue>(
            DecisionFields.STATEMENT to statement.fv(),
            DecisionFields.DECIDED_AT to nowMs().fv(),
            DecisionFields.STATUS to DecisionStatus.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        rationale?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[DecisionFields.RATIONALE] = it.fv() }
        if (reviewInDays != null) fields[DecisionFields.REVIEW_AT] = daysFromToday(reviewInDays)
        supersedes?.let { fields[DecisionFields.SUPERSEDES] = it.fv() }
        replica.commitLocal(EntityTypes.DECISION, id, fields)
        return id
    }

    // ---- Reads ----

    fun waitingItems(): List<WaitingItem> {
        val today = today()
        return replica.entities(EntityTypes.COMMITMENT)
            .filter { it[CommitmentFields.DIRECTION].textOrNull == CommitmentDirection.OWED_TO_ME.name }
            .filter { lifecycleOf(it) == Lifecycle.WAITING }
            .map { s ->
                val since = s[ActionableFields.CREATED_AT].longOrNull ?: 0L
                val chase = s[CommitmentFields.FOLLOW_UP_AT].longOrNull?.let(calendar::epochDayOf)
                val who = s[CommitmentFields.COUNTERPARTY_LABEL].textOrNull
                WaitingItem(
                    id = s.ref.entityId,
                    title = s[ActionableFields.TITLE].textOrNull ?: "",
                    who = who,
                    notes = s[ActionableFields.NOTES].textOrNull,
                    sinceMs = since,
                    lastChasedMs = s[CommitmentFields.LAST_INTERACTION_AT].longOrNull,
                    chaseDay = chase,
                    state = ListRules.dueState(chase, today),
                    meta = ListRules.waitingMeta(who, calendar.epochDayOf(since), chase, today),
                    hasConflict = replica.conflictsFor(EntityTypes.COMMITMENT, s.ref.entityId).isNotEmpty(),
                )
            }
            .sortedWith(
                compareBy<WaitingItem> { if (it.state == DueState.DUE) 0 else if (it.chaseDay != null) 1 else 2 }
                    .thenBy { it.chaseDay ?: Long.MAX_VALUE }
                    .thenByDescending { it.sinceMs }
                    .thenBy { it.id },
            )
    }

    fun somedayGroups(tasks: List<Task>): List<SomedayGroup> =
        tasks.filter { it.lifecycle == Lifecycle.SOMEDAY }
            .groupBy { it.somedayKind ?: SomedayKind.IDEA }
            .let { byKind ->
                ListRules.SOMEDAY_KINDS.mapNotNull { k ->
                    byKind[k]?.sortedWith(compareByDescending<Task> { it.createdAtMs }.thenBy { it.id })
                        ?.let { SomedayGroup(k, ListRules.kindLabel(k), it) }
                }
            }

    fun decisionItems(includeSuperseded: Boolean = false): List<DecisionItem> {
        val today = today()
        return replica.entities(EntityTypes.DECISION).map { s ->
            val status = s[DecisionFields.STATUS].textOrNull?.let { enumOrNull<DecisionStatus>(it) } ?: DecisionStatus.ACTIVE
            val decided = s[DecisionFields.DECIDED_AT].longOrNull ?: s[ActionableFields.CREATED_AT].longOrNull ?: 0L
            val review = s[DecisionFields.REVIEW_AT].longOrNull?.let(calendar::epochDayOf)
            val state = when (status) {
                DecisionStatus.REVISITING -> DueState.DUE
                DecisionStatus.SUPERSEDED -> DueState.NONE
                DecisionStatus.ACTIVE -> ListRules.dueState(review, today)
            }
            DecisionItem(
                id = s.ref.entityId,
                statement = s[DecisionFields.STATEMENT].textOrNull ?: "",
                rationale = s[DecisionFields.RATIONALE].textOrNull,
                decidedAtMs = decided,
                reviewDay = review,
                status = status,
                supersedesId = s[DecisionFields.SUPERSEDES].textOrNull,
                state = state,
                meta = ListRules.decisionMeta(calendar.epochDayOf(decided), review, status, today),
                hasConflict = replica.conflictsFor(EntityTypes.DECISION, s.ref.entityId).isNotEmpty(),
            )
        }
            .filter { includeSuperseded || it.status != DecisionStatus.SUPERSEDED }
            .sortedWith(compareBy<DecisionItem> { if (it.state == DueState.DUE) 0 else 1 }.thenByDescending { it.decidedAtMs }.thenBy { it.id })
    }

    /** The whole Lists screen. [tasks] is the already-loaded task list (Someday lives there). */
    fun view(tasks: List<Task>, renewals: RenewalsView = RenewalsView.EMPTY, shopping: ShoppingView = ShoppingView.EMPTY): ListsView =
        ListsView(waitingItems(), somedayGroups(tasks), decisionItems(), renewals, shopping)

    // ---- Helpers ----

    private fun lifecycleOf(s: EntitySnapshot): Lifecycle? = s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) }

    private fun cleanTitle(text: String, emptyMessage: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException(emptyMessage)
        if (t.length > MAX_TITLE) throw ValidationException("That's too long for a title")
        return t
    }

    private fun checkDays(days: Int?) {
        if (days != null && days !in 0..MAX_DAYS) throw ValidationException("Pick a date within ten years")
    }

    private fun requireWaiting(id: String) {
        val s = replica.entity(EntityTypes.COMMITMENT, id)
        if (s == null || s.deleted || s[CommitmentFields.DIRECTION].textOrNull != CommitmentDirection.OWED_TO_ME.name) {
            throw ValidationException("That item isn't in Waiting for")
        }
    }

    private fun liveTask(id: String): EntitySnapshot {
        val s = replica.entity(EntityTypes.TASK, id)
        if (s == null || s.deleted) throw ValidationException("Task not found")
        return s
    }

    private fun requireSomeday(id: String) {
        if (lifecycleOf(liveTask(id)) != Lifecycle.SOMEDAY) throw ValidationException("That item isn't in Someday")
    }

    private fun requireDecision(id: String) {
        val s = replica.entity(EntityTypes.DECISION, id)
        if (s == null || s.deleted) throw ValidationException("Decision not found")
    }

    companion object {
        const val MAX_TITLE = 500
        const val MAX_DAYS = 3660
        /** Chase and review dates are stored as 09:00 local on their day. */
        const val DATE_MINUTE = 9 * 60
    }
}
