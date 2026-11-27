package os.meka.core.domain

/** Where Shut down can send a task left from today. */
enum class ShutdownMove { TOMORROW, SKIP, SOMEDAY }

/**
 * Shut down's per-task Tomorrow · Skip · Someday as one "Move ›" on the task's row opening a sheet (walk-through week 5,
 * A17: no open chip rows on a page; every left task carried its own sideways row of two or three pills). The row keeps
 * the task's circle, title and line; "Move ›" on its right opens the sheet, which lists only the moves that task can make
 * (Skip for a repeating one, Someday for a one-off), each saying what it does. A choice moves the task at once, as the
 * chip did, and the sheet closes. Reuses Notifications' sheet shape. Pure.
 */
object ShutdownMoveChoiceRules {
    const val MOVE = "Move"
    const val NOTE = "Or tick it done. Move the rest to tomorrow carries everything left at once."

    const val TOMORROW_LINE = "Carries it over to tomorrow"
    const val SKIP_LINE = "Skips today's; the next one comes on its day"
    const val SOMEDAY_LINE = "Takes it off your days and keeps it on Someday"

    /** The moves [item] can make, in the order the chips had. */
    fun moves(item: ShutdownItem): List<ShutdownMove> = buildList {
        add(ShutdownMove.TOMORROW)
        if (item.canSkip) add(ShutdownMove.SKIP)
        if (item.canSomeday) add(ShutdownMove.SOMEDAY)
    }

    fun label(m: ShutdownMove): String = when (m) {
        ShutdownMove.TOMORROW -> "Tomorrow"
        ShutdownMove.SKIP -> "Skip"
        ShutdownMove.SOMEDAY -> "Someday"
    }

    fun line(m: ShutdownMove): String = when (m) {
        ShutdownMove.TOMORROW -> TOMORROW_LINE
        ShutdownMove.SKIP -> SKIP_LINE
        ShutdownMove.SOMEDAY -> SOMEDAY_LINE
    }

    /** What a screen reader says for the row's "Move ›": "Move Fix the gate latch. Opens Tomorrow or Someday". */
    fun spoken(item: ShutdownItem): String {
        val labels = moves(item).map(::label)
        val list = if (labels.size == 1) labels[0] else labels.dropLast(1).joinToString(", ") + " or " + labels.last()
        return "$MOVE ${item.task.title}. Opens $list"
    }

    /** "Move “Fix the gate latch”": the task's moves, none lit (each is an action, not a setting). */
    fun sheet(item: ShutdownItem): NotifyChoiceSheet = NotifyChoiceSheet(
        "$MOVE “${item.task.title}”", NOTE,
        moves(item).map { m -> NotifyChoice(m.name, label(m), line(m), false, "${label(m)}, ${line(m)}") },
    )

    /** The move a choice names, or null when it isn't one [item] can make. */
    fun named(item: ShutdownItem, name: String): ShutdownMove? = moves(item).firstOrNull { it.name == name }
}
