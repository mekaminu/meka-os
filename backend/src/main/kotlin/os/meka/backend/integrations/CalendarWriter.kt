package os.meka.backend.integrations

import os.meka.backend.Secrets
import os.meka.core.domain.CalendarEditRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventDraft
import os.meka.core.domain.EventEdit
import os.meka.core.domain.EventEditChange
import os.meka.core.domain.EventEditFields
import os.meka.core.domain.EventEditKind
import os.meka.core.domain.EventEditStatus
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.sync.fv
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A household's entities of one type as the op log has them: per entity, each field's latest value (LWW by HLC). */
interface EntityReader {
    fun entities(householdId: String, entityType: String): Map<String, Map<String, FieldValue>>
    fun entity(householdId: String, entityType: String, entityId: String): Map<String, FieldValue>? = entities(householdId, entityType)[entityId]

    companion object {
        /** Scans the op log (tests and small stores); Postgres uses one indexed query ([os.meka.backend.PostgresOpStore.latestFields]). */
        fun scanning(ops: ServerOpStore) = object : EntityReader {
            override fun entities(householdId: String, entityType: String): Map<String, Map<String, FieldValue>> {
                val best = HashMap<String, HashMap<String, Op>>()
                var after = 0L
                while (true) {
                    val page = ops.after(householdId, after, 1_000)
                    if (page.isEmpty()) break
                    for (s in page) {
                        val op = s.op
                        if (op.entityType != entityType) continue
                        val fields = best.getOrPut(op.entityId) { HashMap() }
                        val prev = fields[op.field]
                        if (prev == null || op.hlc > prev.hlc) fields[op.field] = op
                    }
                    after = page.last().seq
                }
                return best.mapValues { (_, f) -> f.mapValues { it.value.value } }
            }
        }
    }
}

/**
 * Sends the calendar edits Meka made in MEKA (calendar editing, slice 2a; see [os.meka.core.domain.CalendarEdits]) to
 * Google or Outlook once their undo window is over, and writes the outcome back into the same `event_edit` entity.
 *
 * Before anything is sent: the account must still allow editing; a change or delete is checked against the provider's
 * own copy (a clash sends nothing and hands their version back), an event someone else organises is never touched, and
 * deleting one with guests needs Meka's confirmation. After a change the account is synced at once so every device
 * shows the event where it now is. Each account's edits go one at a time under the account's lock, and the outcome is
 * written once (fixed op ids), so two server tasks or a retry never send an edit twice; an add uses an id the provider
 * recognises on a retry.
 *
 * Provider trouble (5xx, timeouts) is retried with a growing pause, and given up after a day as FAILED.
 */
class CalendarWriter internal constructor(
    private val store: IntegrationStore,
    private val ops: ServerOpStore,
    private val reader: EntityReader,
    private val integrations: Integrations,
    private val now: () -> Long = System::currentTimeMillis,
    /** Outcomes were written for a household: wake its devices. */
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val retryAt = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val poked = LinkedBlockingQueue<Unit>()

    internal data class Outcome(
        val status: EventEditStatus,
        val detail: String? = null,
        val theirs: EventDraft? = null,
        val guests: Int? = null,
    )

    /** A device pushed an edit: look now rather than at the next minute. */
    fun poke() { poked.offer(Unit) }

    /** Runs the writer: a sweep whenever poked, when the next edit falls due, and at least once a minute. */
    fun start(firstAfterMs: Long = 20_000) {
        Thread.ofVirtual().name("calendar-writer").start {
            Thread.sleep(firstAfterMs)
            while (true) {
                val next = runCatching { sweep() }.getOrNull()
                val wait = next?.let { (it - now()).coerceIn(250, IDLE_MS) } ?: IDLE_MS
                poked.poll(wait, TimeUnit.MILLISECONDS)
                poked.clear()
            }
        }
    }

    /** Sends every due edit; returns when the next one falls due (null when none is waiting). */
    fun sweep(): Long? {
        var next: Long? = null
        for (hh in store.households()) runCatching { sweep(hh) }.getOrNull()?.let { n -> next = next?.let { minOf(it, n) } ?: n }
        return next
    }

    fun sweep(householdId: String): Long? {
        val t = now()
        val edits = reader.entities(householdId, EntityTypes.EVENT_EDIT)
            .mapNotNull { (id, f) -> CalendarEditRules.from(id) { f[it] ?: FieldValue.Null } }
            .filter { it.status == null && !it.undone }
            .sortedBy { it.createdAtMs }
        var next: Long? = null
        var wrote = false
        val synced = LinkedHashSet<String>()
        for (e in edits) {
            val dueAt = maxOf(e.sendAfterMs + CalendarEditRules.SERVER_GRACE_MS, retryAt[e.id]?.first ?: 0L)
            if (t < dueAt) { next = next?.let { minOf(it, dueAt) } ?: dueAt; continue }
            val r = runCatching { handle(householdId, e) }.getOrNull()
            if (r != null && r.waiting) {
                // Completeness slice 5: the sign-in lapsed. Look again each minute (no call to the provider while the
                // account still needs reconnecting), so it goes within a minute of Meka reconnecting.
                retryAt[e.id] = (t + RECONNECT_CHECK_MS) to 0
                next = next?.let { minOf(it, t + RECONNECT_CHECK_MS) } ?: (t + RECONNECT_CHECK_MS)
                if (r.wrote) wrote = true
                continue
            }
            if (r == null) {
                // Try again later, waiting longer each time (30 s, 1 min, 2 min … 30 min).
                val tries = (retryAt[e.id]?.second ?: 0) + 1
                val at = t + minOf(RETRY_MS shl minOf(tries - 1, 6), MAX_RETRY_MS)
                retryAt[e.id] = at to tries
                next = next?.let { minOf(it, at) } ?: at
                continue
            }
            retryAt.remove(e.id)
            if (r.wrote) wrote = true
            r.syncAccount?.let { synced += it }
        }
        // The change is in the provider now: mirror it back at once so every device shows it.
        for (id in synced) runCatching { integrations.syncAccount(id) }
        if (wrote) runCatching { onWritten(householdId) }
        return next
    }

    /** [waiting]: the account's sign-in has lapsed and the edit waits for Reconnect (completeness slice 5). */
    internal data class Handled(val wrote: Boolean, val syncAccount: String?, val waiting: Boolean = false)

    /**
     * Sends one due edit and writes its outcome. Returns whether an outcome was written and which account to sync
     * after; throws when it should be tried again later.
     */
    internal fun handle(householdId: String, e: EventEdit): Handled {
        val name = CalendarEditRules.providerName(e.provider)
        val a = store.accounts(householdId).firstOrNull { it.provider == e.provider && it.email.equals(e.account, ignoreCase = true) }
        val refusal = when {
            e.provider !in CalendarEditRules.WRITABLE || integrations.provider(e.provider) == null -> "Navilife can't change this calendar"
            a == null -> "${e.account} isn't connected to Navilife any more"
            !a.canEdit -> "Editing isn't allowed for ${a.email} · Allow editing in Calendars"
            else -> null
        }
        if (refusal != null || a == null) {
            return Handled(ops.transaction { answer(householdId, e.id, Outcome(EventEditStatus.REFUSED, refusal)) }, null)
        }
        // Completeness slice 5: a lapsed sign-in keeps the edit (and every one after it) waiting, without a call.
        if (a.status == NEEDS_RECONNECT) return waitForReconnect(householdId, e)
        var lapsed = false
        val handled = store.transaction {
            ops.transaction {
                store.lockAccount(a.id)
                // Another task (or an Undo that just arrived) may have settled it meanwhile.
                val fresh = reader.entity(householdId, EntityTypes.EVENT_EDIT, e.id)
                    ?.let { f -> CalendarEditRules.from(e.id) { f[it] ?: FieldValue.Null } }
                if (fresh == null || fresh.status != null || fresh.undone) return@transaction Handled(false, null)
                val outcome = try {
                    send(householdId, a, fresh)
                } catch (x: ReconnectRequired) {
                    lapsed = true
                    return@transaction Handled(false, null, waiting = true)
                } catch (x: WriteRefused) {
                    // Completeness slice 3: a 403 on a calendar shared to view only says so, not "allow editing".
                    Outcome(EventEditStatus.REFUSED, refusedLine(name, x.message))
                } catch (x: Exception) {
                    if (!CalendarEditRules.givenUp(fresh, now())) throw x
                    Outcome(EventEditStatus.FAILED, "$name couldn't be reached for a day")
                }
                val wrote = answer(householdId, e.id, outcome)
                Handled(wrote, a.id.takeIf { outcome.status == EventEditStatus.DONE })
            }
        }
        if (!lapsed) return handled
        // Calendars shows Reconnect from this, and the edits after this one wait without asking the provider.
        runCatching { store.transaction { store.markError(a.id, NEEDS_RECONNECT, "calendar edit: sign-in lapsed") } }
        return waitForReconnect(householdId, e)
    }

    /**
     * Completeness slice 5: notes once on the edit that it waits for the account to be reconnected (the apps say
     * "Waiting to move “Dentist” in Google · reconnect Google in Calendars and it goes then"); after a week of waiting
     * it fails with that reason instead.
     */
    private fun waitForReconnect(householdId: String, e: EventEdit): Handled = ops.transaction {
        val fresh = reader.entity(householdId, EntityTypes.EVENT_EDIT, e.id)
            ?.let { f -> CalendarEditRules.from(e.id) { f[it] ?: FieldValue.Null } } ?: e
        if (fresh.status != null || fresh.undone) return@transaction Handled(false, null)
        if (fresh.waitingReconnectAtMs != null && CalendarEditRules.reconnectGivenUp(fresh, now())) {
            return@transaction Handled(answer(householdId, e.id, Outcome(EventEditStatus.FAILED, CalendarEditRules.reconnectFailed(e.provider))), null)
        }
        if (fresh.waitingReconnectAtMs != null) return@transaction Handled(false, null, waiting = true)
        val opId = opId(e.id, EventEditFields.WAITING_RECONNECT)
        if (ops.find(householdId, opId) != null) return@transaction Handled(false, null, waiting = true)
        ops.append(
            Op(
                opId = opId, householdId = householdId, entityType = EntityTypes.EVENT_EDIT, entityId = e.id,
                field = EventEditFields.WAITING_RECONNECT, value = now().fv(), hlc = synchronized(clock) { clock.now() },
                baseOpIds = emptyList(), deviceId = Integrations.SERVER_DEVICE,
            ),
        )
        Handled(true, null, waiting = true)
    }

    /** Checks the edit against the provider's copy and sends it. Throws to be tried again later. */
    internal fun send(householdId: String, a: AccountRow, e: EventEdit): Outcome {
        val p = integrations.provider(a.provider)!!
        val name = CalendarEditRules.providerName(a.provider)
        if (e.kind == EventEditKind.ADD) {
            val d = e.draft?.let(CalendarEditRules::clean) ?: return Outcome(EventEditStatus.REFUSED, "There was nothing to add")
            CalendarEditRules.problem(d, e.createdAtMs)?.let { return Outcome(EventEditStatus.REFUSED, it) }
            p.createEvent(integrations.accessToken(a, editing = true), d, idemKey(e.id))
            return Outcome(EventEditStatus.DONE)
        }
        val base = e.base ?: return Outcome(EventEditStatus.REFUSED, "Navilife didn't know what it was before")
        val row = e.eventId?.let { store.mirror(householdId, a.id)[it] }
            ?: return Outcome(EventEditStatus.REFUSED, "It isn't in $name any more")
        // Mirrored before the provider's ids were kept: the next calendar poll fills it in.
        val remoteId = row.remoteId ?: error("not mirrored with its id yet")
        val token = integrations.accessToken(a, editing = true)
        val theirs = p.event(token, remoteId)
            ?: return if (e.kind == EventEditKind.DELETE) Outcome(EventEditStatus.DONE, "It was already gone")
            else Outcome(EventEditStatus.REFUSED, "It was deleted in $name meanwhile")
        if (!theirs.organisedByMe) {
            return Outcome(EventEditStatus.REFUSED, "Someone else organises it · Navilife only changes your own events", guests = theirs.guests)
        }
        if (CalendarEditRules.clashes(e.kind, e.changes, base, theirs.event)) {
            return Outcome(EventEditStatus.CLASH, theirs = theirs.event, guests = theirs.guests)
        }
        if (e.every || e.following) return sendSeries(p, token, name, a, e, theirs, base)
        val notify = theirs.guests > 0
        if (e.kind == EventEditKind.DELETE) {
            if (theirs.guests > 0 && !e.guestsOk) {
                return Outcome(EventEditStatus.REFUSED, CalendarEditRules.cancelsFor(theirs.guests), guests = theirs.guests)
            }
            p.deleteEvent(token, remoteId, notify)
            return Outcome(EventEditStatus.DONE, guests = theirs.guests)
        }
        val changes = e.changes.filterTo(LinkedHashSet()) { it != EventEditChange.NOTES || CalendarEditRules.canChangeNotes(a.provider) }
        val draft = e.draft ?: return Outcome(EventEditStatus.REFUSED, "There was nothing to change")
        if (changes.isEmpty()) return Outcome(EventEditStatus.REFUSED, "There was nothing to change")
        val merged = CalendarEditRules.clean(CalendarEditRules.merged(base, draft, changes))
        CalendarEditRules.problem(merged, e.createdAtMs)?.let { return Outcome(EventEditStatus.REFUSED, it) }
        p.updateEvent(token, remoteId, merged, changes, notify)
        return Outcome(EventEditStatus.DONE, guests = theirs.guests)
    }

    /**
     * Completeness slice 1: a change or delete for every event of the series [instance] is one of. The event itself was
     * checked against what MEKA saw (no clash); the series must still be Meka's own, its guests confirmed for a delete,
     * and a new time keeps each event's day ([CalendarEditRules.everyProblem], checked again here).
     */
    private fun sendSeries(p: CalendarProvider, token: String, name: String, a: AccountRow, e: EventEdit, instance: RemoteCopy, base: EventDraft): Outcome {
        val seriesId = instance.seriesId ?: return Outcome(EventEditStatus.REFUSED, "It doesn't repeat in $name any more")
        val series = p.event(token, seriesId) ?: return Outcome(EventEditStatus.REFUSED, "The series isn't in $name any more")
        if (!series.organisedByMe) {
            return Outcome(EventEditStatus.REFUSED, "Someone else organises it · Navilife only changes your own events", guests = series.guests)
        }
        val notify = series.guests > 0
        // Completeness slice 2: this and following. From the series' first event on it is every event.
        val from = instance.originalStartMs ?: base.startAtMs
        val split = e.following && from > series.event.startAtMs
        if (e.following) {
            val why = when {
                a.provider != "google" -> "This and following is changed in $name itself · or choose This event or Every event"
                series.event.allDay || base.allDay -> "An all-day series · change it in $name, or choose This event"
                // A new series would send its guests a new invitation.
                split && e.kind == EventEditKind.CHANGE && series.guests > 0 ->
                    "Its guests would get a new invitation · change it in $name, or choose This event or Every event"
                else -> null
            }
            if (why != null) return Outcome(EventEditStatus.REFUSED, why, guests = series.guests)
        }
        if (e.kind == EventEditKind.DELETE) {
            if (series.guests > 0 && !e.guestsOk) {
                return Outcome(EventEditStatus.REFUSED, CalendarEditRules.cancelsFor(series.guests), guests = series.guests)
            }
            if (split) {
                p.splitSeries(token, seriesId, from, null, emptySet(), notify, idemKey(e.id))?.let { return Outcome(EventEditStatus.REFUSED, it) }
                return Outcome(EventEditStatus.DONE, guests = series.guests)
            }
            p.deleteEvent(token, seriesId, notify)
            return Outcome(EventEditStatus.DONE, guests = series.guests)
        }
        val changes = e.changes.filterTo(LinkedHashSet()) { it != EventEditChange.NOTES || CalendarEditRules.canChangeNotes(a.provider) }
        val draft = e.draft ?: return Outcome(EventEditStatus.REFUSED, "There was nothing to change")
        if (changes.isEmpty()) return Outcome(EventEditStatus.REFUSED, "There was nothing to change")
        val merged = CalendarEditRules.clean(CalendarEditRules.merged(base, draft, changes))
        CalendarEditRules.problem(merged, e.createdAtMs)?.let { return Outcome(EventEditStatus.REFUSED, it) }
        if (EventEditChange.TIME in changes) {
            val why = when {
                a.provider != "google" -> "Change every event's time in $name · or choose This event"
                base.allDay || merged.allDay || series.event.allDay -> "An all-day series keeps its days · change it in $name, or choose This event"
                // Each event keeps its own day: a new time of day only (a day apart in UTC is never that).
                Math.abs(merged.startAtMs - base.startAtMs) >= DAY_MS ->
                    if (e.following) "Each keeps its own day · choose This event to move just this one"
                    else "Every event keeps its own day · choose This event to move just this one"
                else -> null
            }
            if (why != null) return Outcome(EventEditStatus.REFUSED, why)
        }
        if (split) {
            p.splitSeries(token, seriesId, from, merged, changes, notify, idemKey(e.id))?.let { return Outcome(EventEditStatus.REFUSED, it) }
            return Outcome(EventEditStatus.DONE, guests = series.guests)
        }
        p.updateSeries(token, seriesId, merged, changes, notify)
        return Outcome(EventEditStatus.DONE, guests = series.guests)
    }

    /** Writes the outcome once (fixed op ids per edit and field). Returns whether anything was written. */
    private fun answer(householdId: String, id: String, o: Outcome): Boolean {
        val fields = linkedMapOf(
            EventEditFields.STATUS to o.status.name.fv(),
            EventEditFields.STATUS_AT to now().fv(),
        )
        o.detail?.let { fields[EventEditFields.DETAIL] = it.take(300).fv() }
        o.guests?.let { fields[EventEditFields.GUESTS] = it.fv() }
        o.theirs?.let { t ->
            fields[EventEditFields.THEIR_TITLE] = t.title.take(CalendarEditRules.MAX_TITLE).fv()
            fields[EventEditFields.THEIR_START] = t.startAtMs.fv()
            fields[EventEditFields.THEIR_END] = t.endAtMs.fv()
            fields[EventEditFields.THEIR_ALL_DAY] = t.allDay.fv()
            // "" when they have none, so a device can tell "none" from a clash written before these were kept.
            fields[EventEditFields.THEIR_LOCATION] = (t.location?.trim()?.take(CalendarEditRules.MAX_LOCATION) ?: "").fv()
            fields[EventEditFields.THEIR_NOTES] = (t.notes?.trim()?.take(CalendarEditRules.MAX_NOTES) ?: "").fv()
        }
        var appended = false
        for ((field, value) in fields) {
            val opId = opId(id, field)
            if (ops.find(householdId, opId) != null) continue
            ops.append(
                Op(
                    opId = opId, householdId = householdId, entityType = EntityTypes.EVENT_EDIT, entityId = id, field = field,
                    value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = emptyList(), deviceId = Integrations.SERVER_DEVICE,
                ),
            )
            appended = true
        }
        return appended
    }

    companion object {
        const val IDLE_MS = 60_000L
        /** Completeness slice 5: how often an edit waiting on a lapsed sign-in looks again (no provider call). */
        const val RECONNECT_CHECK_MS = 60_000L
        private const val NEEDS_RECONNECT = "needs_reconnect"
        const val RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30 * 60_000L
        private const val DAY_MS = 86_400_000L

        fun opId(editId: String, field: String) =
            "srvedit" + Secrets.sha256Hex(editId).take(24) + field.lowercase().filter(Char::isLetterOrDigit)

        /** The provider's idempotency key for an add: lowercase hex, the same on every retry. */
        fun idemKey(editId: String) = Secrets.sha256Hex("event_edit|$editId").take(32)

        /**
         * Completeness slice 3: what Meka reads when [name] refuses an edit. A 403 for a missing permission (or the
         * API being off) asks him to allow editing again; any other refusal is the calendar itself (one shared with
         * him to view only, or an event he can't change), so it says that instead.
         */
        fun refusedLine(name: String, message: String?): String {
            val reason = message?.substringAfter('[', "")?.substringBefore(']')?.takeIf { it.isNotEmpty() }
            val scope = reason != null && (reason in os.meka.core.domain.MailCheckRules.NO_PERMISSION_REASONS ||
                reason in os.meka.core.domain.MailCheckRules.API_OFF_REASONS)
            return if (message?.startsWith("provider refused the change") == true && !scope) {
                "$name won't let Navilife change this · if the calendar is shared with you to view only, its owner changes it"
            } else "$name refused the change · try Allow editing again in Calendars"
        }
    }
}
