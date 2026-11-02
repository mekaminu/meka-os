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

    // Part 3 slice 5: answering what Obi needs, so the job carries on.
    /** His answer to [NEED] (or "Carry on" after the step cap), at most [ObiJobRules.MAX_ANSWER] characters (device). */
    const val ANSWER = "answer"
    /** When he answered (device). An answer is new while this is later than [ANSWER_TAKEN]. */
    const val ANSWERED_AT = "answeredAtMs"
    /** The [ANSWERED_AT] the server has read (server). */
    const val ANSWER_TAKEN = "answerTakenMs"
    /** What he has told Obi so far, one "question\tanswer" a line, at most [ObiJobRules.MAX_HEARD] (server). */
    const val HEARD = "heard"
    /** The total step cap, raised by "carry on"; absent = [ObiJobRules.TOTAL_STEP_CAP] (server). */
    const val STEP_CAP = "stepCap"
    /** His answer came after the plan ran out: the next step plans what comes next from it (server). */
    const val REPLAN = "replan"
}

enum class ObiJobState(val code: String) {
    /** Written by the device with his words; the server hasn't planned it yet. */
    ASKED("asked"),
    WORKING("working"),
    /** Waits for him: [ObiJobFields.NEED] says what. No further step runs until he answers ([ObiJobFields.ANSWER]). */
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

/** Something he told Obi on an errand: what Obi asked ([question], may be blank) and what he said. */
data class ObiHeard(val question: String, val answer: String)

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
    val answer: String? = null,
    val answeredAtMs: Long? = null,
    val answerTakenMs: Long? = null,
    val heard: List<ObiHeard> = emptyList(),
    val stepCap: Int = ObiJobRules.TOTAL_STEP_CAP,
    val replan: Boolean = false,
) {
    /** Stopped by him, or finished: no step will ever run again. */
    val finished: Boolean get() = stopped || state == ObiJobState.DONE || state == ObiJobState.STOPPED
    val nextStep: ObiStep? get() = steps.firstOrNull { !it.done }
    /** He has answered and the server hasn't read it yet. */
    val answerWaiting: Boolean get() = answer != null && (answeredAtMs ?: 0L) > (answerTakenMs ?: 0L)
    /** It has used every step it may take until he says carry on. */
    val atCap: Boolean get() = totalSteps >= stepCap
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
    /** [did] is what Obi found (from web results, other people's words), not just the steps he took. */
    val found: Boolean = false,
    /** Part 3 slice 5: Answer opens the answer sheet (it waits for him and he hasn't answered yet). */
    val canAnswer: Boolean = false,
    /** Carry on: it stopped at its step cap. */
    val canCarryOn: Boolean = false,
    /** What he last told Obi on it ("Somewhere in Leeds, under £100"), while it is open. */
    val youSaid: String? = null,
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
    const val CAP_NEED = "I've taken all the steps I may on this and stopped · tell me to carry on, or stop it here"

    // Part 3 slice 5: answering what he needs, the same words on the Fold and the Mac.
    const val MAX_ANSWER = 300
    const val MAX_HEARD = 6
    /** "Carry on" after the cap gives it this many more steps. */
    const val CARRY_ON_STEPS = 12
    const val ANSWER = "Answer"
    const val CARRY_ON = "Carry on"
    const val ANSWER_TITLE = "Answer Obi"
    const val ANSWER_HINT = "Your answer, or what to change"
    const val ANSWER_GO = "Tell Obi"
    const val YOU_SAID = "You said"
    const val ANSWERED_LINE = "Obi has your answer · he'll carry on in a minute or so"
    const val CARRY_ON_LINE = "Obi will carry on in a minute or so"
    const val ANSWER_GONE = "That errand isn't waiting for you any more"
    const val ANSWER_STATUS = "Working · Obi has your answer"

    // Part 3 slice 4: the apps' words (Obi is working on on the Fold and the Mac), the same on both.
    /** The row that opens the ask sheet (no open field on the pane, the layout rules). */
    const val ASK_ROW = "Ask Obi for an errand"
    const val SHEET_TITLE = "What should Obi look into?"
    const val SHEET_GO = "Ask Obi"
    const val SHEET_CANCEL = "Cancel"
    const val DRAFT_LABEL = "A draft for you to send"
    const val COPY = "Copy"
    const val COPIED = "Copied · send it yourself when you're ready"
    const val STOP = "Stop"
    const val STOPPED_LINE = "Stopped · Obi won't take another step on it"
    const val STOP_GONE = "That errand has already finished"
    /** Ask's card (Ask, Talk, Live): "Ask Obi to work on: Find someone to service the boiler" with Start. */
    const val CARD_BUTTON = "Start"
    fun cardLine(text: String): String = "Ask Obi to work on: $text"
    fun startedLine(text: String): String = "Obi is on it · $text · see Obi is working on"

    /** Under every draft: Navilife never sends it. */
    const val DRAFT_FOOT = "Navilife never sends this; copy it and send it yourself"

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
            answer = s.f(ObiJobFields.ANSWER).textOrNull?.takeIf { it.isNotBlank() },
            answeredAtMs = s.f(ObiJobFields.ANSWERED_AT).longOrNull,
            answerTakenMs = s.f(ObiJobFields.ANSWER_TAKEN).longOrNull,
            heard = decodeHeard(s.f(ObiJobFields.HEARD).textOrNull),
            stepCap = (s.f(ObiJobFields.STEP_CAP).longOrNull ?: TOTAL_STEP_CAP.toLong()).toInt(),
            replan = s.f(ObiJobFields.REPLAN).boolOrNull == true,
        )
    }

    /** One "question\tanswer" a line, the newest [MAX_HEARD] kept. */
    fun encodeHeard(heard: List<ObiHeard>): String =
        heard.takeLast(MAX_HEARD).joinToString("\n") { "${flat(it.question)}\t${flat(it.answer)}" }

    fun decodeHeard(text: String?): List<ObiHeard> = text.orEmpty().lines().mapNotNull { l ->
        val i = l.indexOf('\t')
        val q = if (i < 0) "" else l.substring(0, i).trim()
        val a = (if (i < 0) l else l.substring(i + 1)).trim()
        a.takeIf { it.isNotEmpty() }?.let { ObiHeard(q, it) }
    }

    /** "  somewhere in   Leeds " → "Somewhere in Leeds"; null when blank. */
    fun tidyAnswer(text: String): String? =
        clean(text).take(MAX_ANSWER).trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }

    /** "carry on", "keep going", "yes", "go on": only telling it to carry on, nothing new to work from. */
    fun isCarryOn(text: String): Boolean =
        clean(text).lowercase().trimEnd('.', '!', ' ') in setOf("carry on", "keep going", "go on", "continue", "yes", "yes please", "ok", "okay")

    /** He can answer it: it waits for him and he hasn't already answered. */
    fun canAnswer(j: ObiJob): Boolean = !j.finished && j.state == ObiJobState.NEEDS_YOU && !j.answerWaiting

    /** The fields his answer writes; null (nothing written) for a blank one. */
    fun answerFields(text: String, nowMs: Long): Map<String, FieldValue>? {
        val a = tidyAnswer(text) ?: return null
        return mapOf(ObiJobFields.ANSWER to a.fv(), ObiJobFields.ANSWERED_AT to nowMs.fv())
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
            j.answerWaiting -> ANSWER_STATUS
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
        val need = if (j.finished || j.answerWaiting) null else j.need
        val youSaid = if (j.finished) null else if (j.answerWaiting) j.answer else j.heard.lastOrNull()?.answer
        val canAnswer = canAnswer(j)
        return ObiJobRow(
            j.id, title, status, did, need, j.draft, canStop = !j.finished,
            spoken = listOfNotNull(title, status, did, need?.let { "Needs you: $it" }, youSaid?.let { "$YOU_SAID: $it" }).joinToString(". "),
            found = j.found != null,
            canAnswer = canAnswer, canCarryOn = canAnswer && j.atCap, youSaid = youSaid,
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

    /**
     * Ask's lines for the errands he asked for (inside `working_on`): "Errand · Find someone to service the boiler ·
     * Working · step 2 of 4 · Done so far: … · needs you: …", needing him first, at most [max]. A draft is never quoted,
     * only that there is one.
     */
    fun askLines(rows: List<ObiJobRow>, max: Int): List<String> = rows.take(max).map { r ->
        listOfNotNull("Errand", r.title, r.status, r.did.take(200), r.need?.let { "needs you: $it" }, r.youSaid?.let { "he said: ${it.take(120)}" },
            r.draft?.let { "a draft is waiting for him to copy and send himself" }).joinToString(" · ")
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

    /**
     * Part 3 slice 5: his answer to what Obi needs ("somewhere in Leeds", "make it shorter", or [ObiJobRules.CARRY_ON]
     * after the step cap); the server reads it on its next look and carries on. False (nothing written) for an unknown
     * job, one not waiting for him, one already answered, or a blank answer.
     */
    fun answer(id: String, text: String): Boolean {
        val j = all().firstOrNull { it.id == id } ?: return false
        if (!ObiJobRules.canAnswer(j)) return false
        val fields = ObiJobRules.answerFields(text, nowMs()) ?: return false
        replica.commitLocal(EntityTypes.OBI_JOB, id, fields)
        return true
    }

    fun carryOn(id: String): Boolean = answer(id, ObiJobRules.CARRY_ON)
}
