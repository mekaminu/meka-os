package os.meka.core.domain

/** What a tap did to a task on the shutdown or the brief, shown before the core's write lands. */
enum class TaskMove { DONE, TOMORROW, SKIP, SOMEDAY }

/**
 * Taps feel instant for task moves in the evening shutdown and the morning brief (Meka 2026-10-25 01:45: "it should be
 * instantaneous"; the instant-taps item, (2)). A tick, Tomorrow, Skip, Someday or "Move the rest to tomorrow" only
 * changed the list once the core's call came back from its one thread. Now the apps keep the moves on their way (task id
 * → [TaskMove]) and draw [shutdown] / [lines] over the core's view in the same frame. A move is forgotten when its call
 * returns ([forget]; the core publishes its own view before the call returns), so a failed call puts the task back as the
 * core has it and the bar says why ([InstantTapRules.failedLine]).
 *
 * Only what the move changes is drawn: the row leaves "Left from today" (a tick joins "Done today" at the top), and the
 * counts and the card's line follow. Tomorrow's preview is left to the core, which settles it a moment later. Non-AI and
 * pure, so the Fold and the Mac draw the same.
 */
object InstantTaskRules {
    /** [taps] with [id] moved. A newer move replaces an older one. */
    fun move(taps: Map<String, TaskMove>, id: String, move: TaskMove): Map<String, TaskMove> = taps + (id to move)

    /** "Move the rest to tomorrow": every row still left (and not already moved here) goes to tomorrow. */
    fun carryAll(taps: Map<String, TaskMove>, v: ShutdownView): Map<String, TaskMove> =
        taps + v.left.map { it.task.id }.filter { it !in taps }.associateWith { TaskMove.TOMORROW }

    /** The ids [carryAll] moved, so its call's return forgets just those. */
    fun carried(taps: Map<String, TaskMove>, v: ShutdownView): List<String> = v.left.map { it.task.id }.filter { it !in taps }

    /** The call that made [move] on [id] returned (done or failed). A newer move for the same task is kept. */
    fun forget(taps: Map<String, TaskMove>, id: String, move: TaskMove): Map<String, TaskMove> =
        if (taps[id] == move) taps - id else taps

    /** [forget] for several ids at once (Move the rest to tomorrow). */
    fun forgetAll(taps: Map<String, TaskMove>, ids: List<String>, move: TaskMove): Map<String, TaskMove> =
        ids.fold(taps) { t, id -> forget(t, id, move) }

    /** The shutdown as the screens draw it: the core's [v] with [taps] applied. [time] labels a tick's Done row. */
    fun shutdown(v: ShutdownView, taps: Map<String, TaskMove>, time: String = JUST_NOW): ShutdownView {
        if (taps.isEmpty()) return v
        val moved = v.left.filter { it.task.id in taps }
        if (moved.isEmpty()) return v
        val left = v.left.filter { it.task.id !in taps }
        val known = v.done.map { it.id }.toSet()
        val ticked = moved.filter { taps[it.task.id] == TaskMove.DONE && it.task.id !in known }
            .map { DoneRow(it.task.id, it.task.title, time) }
        val doneCount = v.doneCount + ticked.size
        val leftBit = if (left.isEmpty()) "Nothing left" else "${left.size} left"
        val rest = v.cardLine.substringAfter(" · ", missingDelimiterValue = "")
        return v.copy(
            left = left,
            leftLine = if (left.isEmpty()) "Nothing left from today" else "${left.size} left from today",
            cardLine = if (rest.isEmpty()) leftBit else "$leftBit · $rest",
            doneCount = doneCount,
            doneCountLine = if (doneCount == 0) ShutdownRules.NOTHING_DONE else "$doneCount done today",
            done = (ticked.reversed() + v.done).take(ShutdownRules.DONE_SHOWN),
            doneMore = ShutdownRules.doneMore(doneCount),
        )
    }

    /** The brief's Focus or Today rows as drawn: a task ticked here leaves at once (events stay). */
    fun lines(lines: List<BriefDayLine>, taps: Map<String, TaskMove>): List<BriefDayLine> =
        if (taps.isEmpty()) lines else lines.filter { it.taskId == null || it.taskId !in taps }

    /** The bar's word for a failed [move]. */
    fun what(move: TaskMove): String = when (move) {
        TaskMove.DONE -> "Done"
        TaskMove.TOMORROW -> "Tomorrow"
        TaskMove.SKIP -> "Skip"
        TaskMove.SOMEDAY -> "Someday"
    }

    const val JUST_NOW = "now"
}
