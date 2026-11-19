package os.meka.core.domain

/**
 * A possible scam never becomes a task (Meka 2026-11-19, Today screenshot: Up next showed "Our bank account details …
 * are changing · carried over 8 days", made from an email Navilife itself marks "Possible scam").
 *
 * An email flagged as a possible scam ([MailScamRules.scamLine] on its warning, unless he said Not a scam) or one he sent
 * to Junk ([MailItem.junkAsked]) can't be made a task or a Waiting for item, its row offers neither, and a task or
 * Waiting for item already made from one (before it was flagged, or by an older app) is removed, with a line in
 * Activity. A task he already finished is left alone; nothing is sent, deleted from his mail or told to anyone.
 */
object MailScamTaskRules {
    /** The line when Task, To a day or Waiting for is tapped on such an email (nothing is made). */
    const val BLOCKED_LINE = "A possible scam can't become a task · tap Not a scam first if it's real"

    /** Activity's why for a removed task. */
    const val WHY = "The email it came from is marked as a possible scam or junk, so it shouldn't be on your list"

    const val SOURCE = "mail:scam-task"

    /** True when [i] must not become a task or a Waiting for item. */
    fun blocked(i: MailItem): Boolean =
        i.junkAsked || (MailScamRules.scamLine(i.warning) && !MailNotScamRules.notScam(i))

    /** A row whose email can't become a task offers neither Task nor To a day. */
    fun offersTask(row: MailNeedsRow): Boolean = !row.scam

    /** One stray to remove: the task or Waiting for item made from a blocked email. */
    data class Stray(val itemId: String, val taskId: String?, val waitingId: String?, val title: String)

    /**
     * The strays among [items]: each blocked email's task (by its own id, [MailNeedsRules.taskId], or the one recorded on
     * it) while [openTask] says it is still open, and its Waiting for item while [liveWaiting] says it is there.
     */
    fun strays(items: List<MailItem>, openTask: (String) -> Boolean, liveWaiting: (String) -> Boolean): List<Stray> =
        items.filter(::blocked).mapNotNull { i ->
            val task = listOfNotNull(i.taskId?.takeIf { it.isNotBlank() }, MailNeedsRules.taskId(i.id)).distinct().firstOrNull(openTask)
            val waiting = listOfNotNull(i.waitingId?.takeIf { it.isNotBlank() }, MailNeedsRules.waitingId(i.id)).distinct().firstOrNull(liveWaiting)
            if (task == null && waiting == null) null else Stray(i.id, task, waiting, MailNeedsRules.taskTitle(i))
        }

    /** Activity's entry id for [s]: one entry, whichever device removes it first. */
    fun activityId(s: Stray): String = "x" + ActivityRules.fnv64("scamtask:${s.itemId}")

    /** "Removed "Our bank details are changing" from your list · it came from a possible scam email". */
    fun summary(s: Stray): String {
        val t = s.title.trim().let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
        val what = if (s.taskId != null) "your list" else "Waiting for"
        return "Removed “$t” from $what · it came from a possible scam email"
    }
}
