package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Ada plans with you, part 3 slice 3 (Meka 2026-10-21 19:54: "agents re agentic AI"): **errands he asks Obi for**,
 * "find someone to service the boiler", "plan half term", "get me three quotes for the fence". One synced entity type,
 * `obi_job` (ADR-008), a random id: Meka's words and his Stop are written on a device; everything else (the steps, what
 * Obi found, the one thing he needs from Meka, a draft) is written by the server as it works, one step at a time, so a
 * job survives a restart and shows on the Fold and the Mac through ordinary sync.
 *
 * Hard rules (ADR-006, the item's policy gate): the server has nothing that sends, books, buys or pays for a job. A
 * draft is text for Meka to read; it is only ever sent by Meka himself. Web results and his words are data, never
 * instructions. Each job has a daily and a total step cap and stops and asks when it reaches either.
 */
object ObiJobFields {
    /** His words, tidied, at most [ObiJobRules.MAX_ASK] characters (device). */
    const val ASK = "ask"
    const val ASKED_AT = "askedAtMs"
    /** He tapped Stop (device); a stopped job never takes another step. */
    const val STOPPED = "stopped"
    /** [ObiJobState.code] (server). */
    const val STATE = "state"
    /** The plan, one step a line: "x\tsearch\tFind boiler engineers near home\tGas Safe boiler service" ([ObiJobRules.encodeSteps]). */
    const val STEPS = "steps"
    /** What the web searches turned up, one "title — url" a line, at most [ObiJobRules.MAX_NOTES] (server; untrusted). */
    const val NOTES = "notes"
    /** What Obi found, a few plain sentences (server). */
    const val FOUND = "found"
    /** The one thing he needs from Meka, or absent (server). */
    const val NEED = "need"
    /** A message or email Obi drafted for Meka to send himself, or absent (server). Never sent by Navilife. */
    const val DRAFT = "draft"
    /** When the last step ran (server). */
    const val DID_AT = "didAtMs"
    /** Not before this time does the next step run (server). */
    const val NEXT_AT = "nextAtMs"
    /** The London day of [DAY_STEPS] (server). */
    const val STEP_DAY = "stepDay"
    const val DAY_STEPS = "daySteps"
    const val TOTAL_STEPS = "totalSteps"
    /** Why the last try didn't run, a few plain words ("Navilife's AI is off"), or absent (server). */
    const val WAITING = "waiting"
}

enum class ObiJobState(val code: String) {
    /** Written by the device with his words; the server hasn't planned it yet. */
    ASKED("asked"),
    WORKING("working"),
    /** Waits for him: [ObiJobFields.NEED] says what. No further step runs until he answers (a later slice). */
    NEEDS_YOU("needs_you"),
    DONE("done"),
    STOPPED("stopped"),
    ;

    companion object {
        fun of(code: String?): ObiJobState? = entries.firstOrNull { it.code == code }
    }
}

enum class ObiStepKind(val code: String) {
    /** A web search (Brave) for [ObiStep.query]. */
    SEARCH("search"),
    /** Think it through from what's been found so far: writes [ObiJobFields.FOUND]. */
    WORK("work"),
    /** Draft the message or email Meka might send: writes [ObiJobFields.DRAFT], then waits for him. */
    DRAFT("draft"),
    /** A question only Meka can answer: writes [ObiJobFields.NEED], then waits for him. */
    ASK("ask"),
    ;

    companion object {
        fun of(code: String?): ObiStepKind? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }
    }
}

data class ObiStep(val kind: ObiStepKind, val title: String, val query: String = "", val done: Boolean = false)

data class ObiJob(
    val id: String,
    val ask: String,
    val askedAtMs: Long,
    val state: ObiJobState,
    val stopped: Boolean,
    val steps: List<ObiStep>,
    val notes: List<String>,
    val found: String?,
    val need: String?,
    val draft: String?,
    val didAtMs: Long?,
    val nextAtMs: Long?,
    val stepDay: Long?,
    val daySteps: Int,
    val totalSteps: Int,
    val waiting: String?,
) {
    /** Stopped by him, or finished: no step will ever run again. */
    val finished: Boolean get() = stopped || state == ObiJobState.DONE || state == ObiJobState.STOPPED
    val nextStep: ObiStep? get() = steps.firstOrNull { !it.done }
}

/** One job as Obi is working on lists it. */
data class ObiJobRow(
    val id: String,
    /** His words, as a title: "Find someone to service the boiler". */
    val title: String,
    /** "Working · step 2 of 4", "Needs you", "Done today", "Stopped". */
    val status: String,
    /** What I did: the steps done so far, or what he found. */
    val did: String,
    val need: String?,
    val draft: String?,
    val canStop: Boolean,
    val spoken: String,
) {
    val needsYou: Boolean get() = need != null
}

/** Non-AI, pure. */
object ObiJobRules {
    const val MAX_ASK = 300
    /** At most this many steps in a plan. */
    const val MAX_PLAN = 6
    const val MAX_TITLE = 80
    const val MAX_QUERY = 120
    const val MAX_NOTES = 12
    const val MAX_FOUND = 900
    const val MAX_NEED = 240
    const val MAX_DRAFT = 1_500
    /** A job takes at most this many steps on one day, then waits for the next. */
    const val DAY_STEP_CAP = 8
    /** And at most this many in all, then stops and asks him whether to carry on. */
    const val TOTAL_STEP_CAP = 24
    /** Between two steps of one job. */
    const val STEP_GAP_MS = 60_000L
    /** After a try that couldn't run (AI off, out of budget, a failure). */
    const val RETRY_MS = 60 * 60_000L
    /** At most this many jobs are open (not finished) at once; a new ask beyond it is refused. */
    const val MAX_OPEN = 5
    /** Finished jobs stay in the list this many days. */
    const val KEEP_DAYS = 14L

    const val TITLE = "Errands you asked for"
    const val HINT = "Find someone to service the boiler"
    const val INTRO = "Ask Obi to look into something. He works on it step by step and comes back when he needs you. He never sends, books or buys anything; a draft is for you to send."
    const val EMPTY = "Nothing asked yet · try “find someone to service the boiler”"
    const val TOO_MANY = "Obi is already on $MAX_OPEN errands · stop one or wait for one to finish"
    const val BLANK = "Say what you'd like Obi to look into"
    const val CAP_NEED = "I've taken $TOTAL_STEP_CAP steps on this and stopped · tell me to carry on, or stop it here"

    private fun clean(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /** "  find someone   to service the boiler. " → "Find someone to service the boiler"; null when blank. */
    fun tidyAsk(text: String): String? {
        val t = clean(text).trimEnd('.', ' ').take(MAX_ASK).trim()
        if (t.isEmpty()) return null
        return t.replaceFirstChar { it.uppercase() }
    }

    /** One line per step: done mark ("x" or "-"), kind, title, query; tabs and newlines inside are spaces. */
    fun encodeSteps(steps: List<ObiStep>): String = steps.joinToString("\n") { s ->
        listOf(if (s.done) "x" else "-", s.kind.code, flat(s.title), flat(s.query)).joinToString("\t")
    }

    fun decodeSteps(text: String?): List<ObiStep> = text.orEmpty().lines().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 3) return@mapNotNull null
        val kind = ObiStepKind.of(f[1]) ?: return@mapNotNull null
        val title = f[2].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ObiStep(kind, title, f.getOrNull(3)?.trim().orEmpty(), f[0] == "x")
    }

    private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /**
     * A plan from the model, checked: known kinds, titles and queries trimmed, a search with no query dropped, at most
     * [MAX_PLAN], and it always ends on something that reports back (a WORK, DRAFT or ASK step is added when it doesn't).
     */
    fun checkedPlan(steps: List<ObiStep>): List<ObiStep> {
        val kept = steps.mapNotNull { s ->
            val title = clean(s.title).take(MAX_TITLE).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val query = clean(s.query).take(MAX_QUERY)
            if (s.kind == ObiStepKind.SEARCH && query.isEmpty()) return@mapNotNull null
            ObiStep(s.kind, title, if (s.kind == ObiStepKind.SEARCH) query else "", false)
        }.take(MAX_PLAN)
        val reports = kept.any { it.kind != ObiStepKind.SEARCH }
        return if (reports) kept else (kept.take(MAX_PLAN - 1) + ObiStep(ObiStepKind.WORK, "Tell you what I found"))
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** One job from its fields; null without his words. */
    fun parse(id: String, s: Map<String, FieldValue>): ObiJob? {
        val ask = s.f(ObiJobFields.ASK).textOrNull?.takeIf { it.isNotBlank() } ?: return null
        val stopped = s.f(ObiJobFields.STOPPED).boolOrNull == true
        return ObiJob(
            id = id, ask = ask, askedAtMs = s.f(ObiJobFields.ASKED_AT).longOrNull ?: 0L,
            state = if (stopped) ObiJobState.STOPPED else ObiJobState.of(s.f(ObiJobFields.STATE).textOrNull) ?: ObiJobState.ASKED,
            stopped = stopped,
            steps = decodeSteps(s.f(ObiJobFields.STEPS).textOrNull),
            notes = s.f(ObiJobFields.NOTES).textOrNull.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
            found = s.f(ObiJobFields.FOUND).textOrNull?.takeIf { it.isNotBlank() },
            need = s.f(ObiJobFields.NEED).textOrNull?.takeIf { it.isNotBlank() },
            draft = s.f(ObiJobFields.DRAFT).textOrNull?.takeIf { it.isNotBlank() },
            didAtMs = s.f(ObiJobFields.DID_AT).longOrNull,
            nextAtMs = s.f(ObiJobFields.NEXT_AT).longOrNull,
            stepDay = s.f(ObiJobFields.STEP_DAY).longOrNull,
            daySteps = (s.f(ObiJobFields.DAY_STEPS).longOrNull ?: 0L).toInt(),
            totalSteps = (s.f(ObiJobFields.TOTAL_STEPS).longOrNull ?: 0L).toInt(),
            waiting = s.f(ObiJobFields.WAITING).textOrNull?.takeIf { it.isNotBlank() },
        )
    }

    fun jobs(raw: Map<String, Map<String, FieldValue>>): List<ObiJob> = raw.mapNotNull { (id, s) -> parse(id, s) }

    private fun day(atMs: Long?, nowMs: Long, cal: LocalCalendar): String {
        if (atMs == null || atMs <= 0) return ""
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        return if (label == "Today" || label == "Yesterday") label.lowercase() else label
    }

    /** "Working · step 2 of 4", "Needs you", "Done today", "Stopped", "Waiting · Navilife's AI is off". */
    fun status(j: ObiJob, nowMs: Long, cal: LocalCalendar): String {
        val done = j.steps.count { it.done }
        return when {
            j.stopped || j.state == ObiJobState.STOPPED -> "Stopped"
            j.state == ObiJobState.DONE -> "Done ${day(j.didAtMs, nowMs, cal)}".trim()
            j.state == ObiJobState.NEEDS_YOU -> "Needs you"
            j.waiting != null -> "Waiting · ${j.waiting}"
            j.steps.isEmpty() -> "Starting · Obi is planning it"
            j.daySteps >= DAY_STEP_CAP && j.stepDay == cal.epochDayOf(nowMs) -> "Paused till tomorrow · step ${done} of ${j.steps.size}"
            else -> "Working · step ${minOf(done + 1, j.steps.size)} of ${j.steps.size}"
        }
    }

    /** What I did: what he found, else the steps done ("Searched: boiler engineers near home"), else that it's started. */
    fun did(j: ObiJob): String {
        j.found?.let { return it }
        val done = j.steps.filter { it.done }
        if (done.isEmpty()) return if (j.steps.isEmpty()) "Not started yet" else "Planned ${j.steps.size} steps · first: ${j.steps.first().title}"
        return "Done so far: " + done.joinToString(" · ") { it.title }
    }

    fun row(j: ObiJob, nowMs: Long, cal: LocalCalendar): ObiJobRow {
        val title = tidyAsk(j.ask) ?: j.ask
        val status = status(j, nowMs, cal)
        val did = did(j)
        val need = if (j.finished) null else j.need
        return ObiJobRow(
            j.id, title, status, did, need, j.draft, canStop = !j.finished,
            spoken = listOfNotNull(title, status, did, need?.let { "Needs you: $it" }).joinToString(". "),
        )
    }

    /** The list: needing him first, then working, then finished in the last [KEEP_DAYS]; newest ask first within each. */
    fun rows(jobs: List<ObiJob>, nowMs: Long, cal: LocalCalendar): List<ObiJobRow> {
        val today = cal.epochDayOf(nowMs)
        return jobs.filter { !it.finished || today - cal.epochDayOf(it.didAtMs ?: it.askedAtMs) < KEEP_DAYS }
            .sortedWith(
                compareBy<ObiJob> { if (!it.finished && it.need != null) 0 else if (!it.finished) 1 else 2 }
                    .thenByDescending { it.askedAtMs }.thenBy { it.id },
            )
            .map { row(it, nowMs, cal) }
    }

    /** The fields a new ask writes; null (nothing written) for a blank one. */
    fun askFields(text: String, nowMs: Long): Map<String, FieldValue>? {
        val ask = tidyAsk(text) ?: return null
        return mapOf(
            ObiJobFields.ASK to ask.fv(), ObiJobFields.ASKED_AT to nowMs.fv(), ObiJobFields.STATE to ObiJobState.ASKED.code.fv(),
        )
    }
}

/** What asking Obi for an errand did: the new job's id, or why nothing was written. */
data class ObiJobAsked(val id: String?, val line: String)

/** The device side: his asks and his Stops (the server does the rest). */
class ObiJobs(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    fun all(): List<ObiJob> = ObiJobRules.jobs(
        replica.entities(EntityTypes.OBI_JOB).filter { !it.deleted }.associate { it.ref.entityId to it.fields },
    )

    fun ask(text: String): ObiJobAsked {
        val fields = ObiJobRules.askFields(text, nowMs()) ?: return ObiJobAsked(null, ObiJobRules.BLANK)
        if (all().count { !it.finished } >= ObiJobRules.MAX_OPEN) return ObiJobAsked(null, ObiJobRules.TOO_MANY)
        val id = newId()
        replica.commitLocal(EntityTypes.OBI_JOB, id, fields)
        return ObiJobAsked(id, "Obi is on it · ${ObiJobRules.tidyAsk(text)}")
    }

    /** False (nothing written) for an unknown or finished job. */
    fun stop(id: String): Boolean {
        val j = all().firstOrNull { it.id == id } ?: return false
        if (j.finished) return false
        replica.commitLocal(EntityTypes.OBI_JOB, id, mapOf(ObiJobFields.STOPPED to true.fv()))
        return true
    }
}
