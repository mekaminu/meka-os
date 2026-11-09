package os.meka.core.domain

/** Which of Today's three header tiles ([TodayHeaderRules]). */
enum class HeaderTileKind { TASKS, NEXT, MAIL }

/** One header tile: a small caps [key], the figure [value] and the line under it, said whole by [spoken]. */
data class HeaderTile(val kind: HeaderTileKind, val key: String, val value: String, val line: String, val spoken: String)

/**
 * The Today rebuild (Meka approved 2026-11-08 22:58, `docs/design/day-list/TodayNew.dc.html`), slice 2b: the top of
 * Today says the day at a glance instead of a wall of lines. Non-AI and pure, shared by the Fold and the Mac.
 *
 * - **Tiles** ([tiles]): three across under the greeting. **TASKS** "1 of 3 · done today" (today's own tasks, the same
 *   ones as the Today list's head, [EveningCardRules.head]); **NEXT** the next timed event or booked session: "16:55 ·
 *   Training", "Now · Standup" while it's on, "Free · nothing else today"; **MAIL** "1,048 · unread · tidy 380", "0 ·
 *   nothing unread", "– · not read yet" (only once Navilife has counted the mail or knows it can't). They take the
 *   place of the unread-mail lines and the countdown tile.
 * - **Weather chip** ([weatherChip]): "14° light rain" from the weather line "14° · light rain from 16:00"; on an
 *   office day ("Home 9° now · Riverside 14°, light rain …") home's temperature now and what work's hours do. The full
 *   line stays for screen readers.
 */
object TodayHeaderRules {
    const val TASKS = "TASKS"
    const val NEXT = "NEXT"
    const val MAIL = "MAIL"
    const val FREE = "Free"
    const val NOTHING_ELSE = "nothing else today"
    const val NOW = "Now"

    fun tiles(timeline: DayTimeline, done: List<Task>, mail: MailUnreadLine?): List<HeaderTile> =
        listOfNotNull(tasksTile(timeline.anytime, done), nextTile(timeline), mail?.let(::mailTile))

    fun tasksTile(open: List<Task>, done: List<Task>): HeaderTile {
        val all = open.size + done.size
        return when {
            all == 0 -> HeaderTile(HeaderTileKind.TASKS, TASKS, "0", "nothing planned", "No tasks for today yet.")
            open.isEmpty() -> HeaderTile(HeaderTileKind.TASKS, TASKS, "$all of $all", "all done ✓", "All $all of today's tasks done.")
            else -> HeaderTile(
                HeaderTileKind.TASKS, TASKS, "${done.size} of $all", "done today",
                "${done.size} of $all of today's tasks done.",
            )
        }
    }

    /**
     * The row the NEXT tile names and opens: the first timed event or booked session still on the timeline (not a
     * planned task, work or the commute); null when nothing else is on today. Both apps open its event from the tile.
     */
    fun nextRow(timeline: DayTimeline): TimelineRow? =
        timeline.rows.firstOrNull { it.kind == TimelineKind.EVENT || it.kind == TimelineKind.SESSION }

    fun nextTile(timeline: DayTimeline): HeaderTile {
        val row = nextRow(timeline)
            ?: return HeaderTile(HeaderTileKind.NEXT, NEXT, FREE, NOTHING_ELSE, "Next: nothing else today.")
        val title = row.title.trim().ifEmpty { "Your next event" }
        if (row.running) return HeaderTile(HeaderTileKind.NEXT, NEXT, NOW, title, "Now: $title.")
        val start = row.time.substringBefore("–").trim()
        return HeaderTile(HeaderTileKind.NEXT, NEXT, start, title, "Next: $title at $start.")
    }

    fun mailTile(line: MailUnreadLine): HeaderTile {
        val n = line.unread ?: return HeaderTile(HeaderTileKind.MAIL, MAIL, "–", "not read yet", line.spoken)
        if (n == 0) return HeaderTile(HeaderTileKind.MAIL, MAIL, "0", "nothing unread", line.spoken)
        val tidy = if (line.tidyCount > 0) " · tidy ${SpeechRules.grouped(line.tidyCount.toLong())}" else ""
        return HeaderTile(HeaderTileKind.MAIL, MAIL, SpeechRules.grouped(n.toLong()), "unread$tidy", line.spoken)
    }

    /**
     * "14° · light rain from 16:00" → "14° light rain"; "16° · partly cloudy, dry today" → "16° partly cloudy";
     * "Home 9° now · Riverside 14°, light rain from 15:00 — take a coat" → "9° · work light rain"; null for no line.
     */
    fun weatherChip(nowLine: String?): String? {
        val line = nowLine?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val places = Regex("^.*?(-?\\d+)° now · .*?-?\\d+°,\\s*(.+)$").find(line)
        if (places != null) {
            val what = words(places.groupValues[2])
            return if (what == null) "${places.groupValues[1]}°" else "${places.groupValues[1]}° · work $what"
        }
        val head = line.substringBefore(" · ").trim()
        val what = words(line.substringAfter(" · ", ""))
        return if (what == null) head else "$head $what"
    }

    /** "light rain from 16:00" → "light rain"; "partly cloudy, dry today" → "partly cloudy"; "dry — take a coat" → "dry". */
    private fun words(rest: String): String? = rest
        .substringBefore(" — ").substringBefore(",").substringBefore(" from ").substringBefore(" until ")
        .trim().takeIf { it.isNotEmpty() }
}
