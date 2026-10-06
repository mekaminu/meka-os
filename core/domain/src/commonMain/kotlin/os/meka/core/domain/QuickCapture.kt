package os.meka.core.domain

/**
 * Capture from anywhere (build plan M1): text arriving from the Android share sheet, the widget, the quick-settings
 * tile, voice, or the Mac menu bar and Services menu becomes one task. Deterministic, no AI (ADR-006): the first
 * line is the title, everything else is kept in the notes so nothing shared is lost.
 *
 * Shared text is untrusted content. It only ever becomes a task's title and notes; it never sets a date,
 * a priority or anything that would act on Meka's behalf.
 */
object QuickCapture {
    const val MAX_TITLE = 120
    const val MAX_NOTES = 10_000

    data class Draft(val title: String, val notes: String?)

    private val url = Regex("""^(https?://)?([A-Za-z0-9-]+\.)+[A-Za-z]{2,}(/\S*)?$""")
    private val space = Regex("""\s+""")

    /**
     * [text] is what was typed, spoken or shared; [subject] is what some apps send alongside a share (an email or
     * article title). Returns null when there is nothing to capture.
     */
    fun draft(text: String?, subject: String? = null): Draft? {
        val lines = (text ?: "").replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trim() }
            .dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        val body = lines.joinToString("\n")
        val subj = subject?.let { oneLine(it) }?.takeIf { it.isNotEmpty() }

        if (subj != null) {
            val title = shorten(subj)
            val notes = listOfNotNull(subj.takeIf { title != it }, body.takeIf { it.isNotEmpty() && oneLine(it) != subj })
                .joinToString("\n\n")
            return Draft(title, cap(notes))
        }
        if (lines.isEmpty()) return null

        val first = oneLine(lines.first())
        val rest = lines.drop(1).joinToString("\n").trim('\n')
        val title = when {
            url.matches(first) -> "Open ${host(first)}"
            else -> shorten(first)
        }
        val notes = when {
            title == first -> rest
            else -> body // title was cut or rewritten: keep the whole thing
        }
        return Draft(title, cap(notes))
    }

    private fun oneLine(s: String) = s.trim().replace(space, " ")

    private fun shorten(s: String): String {
        if (s.length <= MAX_TITLE) return s
        val cut = s.lastIndexOf(' ', MAX_TITLE - 1).takeIf { it >= MAX_TITLE / 2 } ?: (MAX_TITLE - 1)
        return s.substring(0, cut).trimEnd(' ', ',', ';', ':', '-') + "…"
    }

    private fun host(link: String): String =
        link.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()

    private fun cap(notes: String): String? = when {
        notes.isBlank() -> null
        notes.length <= MAX_NOTES -> notes
        else -> notes.substring(0, MAX_NOTES - 1) + "…"
    }
}
