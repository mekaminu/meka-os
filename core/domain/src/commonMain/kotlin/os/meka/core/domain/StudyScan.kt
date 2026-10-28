package os.meka.core.domain

/**
 * Study plan slice 3, part 1: **the outline from a screenshot**. In a course, "From a screenshot" reads a picture of the
 * course's contents page (a learning app's curriculum, a syllabus) **on the device** (ML Kit on the Fold, Vision on the
 * Mac, the same readers as Screenshots and photos; Needs Meka #23's yes) and these rules turn the screen's words into
 * outline lines for the Add lessons field: section headers stay headers ("Section 2: Core skills" → a module), a
 * lesson's time on its own line or after it ("Video · 5 min", "02:14", "1h 5m") becomes its length, and the page's
 * furniture ("Course content", "Play", "3 / 5 | 32min", "25% complete", "Completed") goes. Nothing is added until he
 * checks the lines in the field and taps Add lessons; the picture is never sent or kept, and nothing about his courses
 * lives in this code.
 *
 * Pure and shared by both apps.
 */
data class StudyScan(
    /** The lines for the field, one lesson or module header a line ([StudyRules.readOutline] reads them). */
    val outline: String,
    val modules: Int,
    val lessons: Int,
    /** The line under the field: "Read 14 lessons in 3 modules · check them, then Add lessons · …", or [StudyScanRules.NONE_LINE]. */
    val line: String,
) {
    val found: Boolean get() = lessons > 0
}

object StudyScanRules {
    const val ACTION = "From a screenshot"
    const val ACTION_MAC = "From a screenshot…"
    const val PASTE_MAC = "Paste a copied screenshot"
    const val ACTION_DESCRIPTION = "Read the lessons from a screenshot of the course's contents; it's read on this device and not kept"
    const val NONE_LINE = "No lessons in that picture · try a screenshot of the course's contents page"
    /** At most this many lines go into the field from one picture. */
    const val MAX_LINES = StudyRules.MAX_PASTE + 40

    /** Words a learning app's page shows on their own line that are never a lesson. */
    private val furniture = setOf(
        "course content", "course contents", "contents", "curriculum", "syllabus", "overview", "q&a", "notes",
        "announcements", "reviews", "resources", "learning tools", "preview", "play", "start", "resume", "continue",
        "completed", "complete", "not started", "in progress", "locked", "download", "downloads", "video", "reading",
        "quiz", "article", "lecture", "assignment", "practice", "lab", "exercise", "up next", "next", "previous",
        "mark as complete", "mark as completed", "go to course", "about this course", "description", "instructor",
        "more", "share", "back", "search", "menu", "home", "close", "lessons", "modules", "sections", "your progress",
        "progress", "transcript", "show more", "show less", "see all", "expand all", "collapse all", "optional",
        "graded", "ungraded", "required", "free", "new", "open", "today", "my learning", "learn", "certificate",
    )

    private val GLYPHS = Regex("^[\\s☐☑☒✓✔✗○●◯◉◎□■▢▣▶►▷⏵⏯▸▹➤→✅⬜⬛•·*\\-–—>]+")
    private val TYPE = "(?:video|reading|quiz|lecture|article|lab|practice|exercise|assignment|audio|lesson|test|survey|discussion)"
    /** "3 / 5 | 32min", "0/4", "5 lectures • 45min", "12 sections • 89 lectures • 10h 30m total length". */
    private val COUNTER = Regex("(?i)^\\d{1,3}\\s*/\\s*\\d{1,3}\\b.*$|^\\d{1,4}\\s*(?:lectures?|lessons?|videos?|items?|sections?|modules?|readings?|quizzes|quiz|steps?|parts?)\\b.*$")
    /** "25% complete", "100%", "4 of 38 complete", "Lesson 3 of 10". */
    private val PROGRESS = Regex("(?i)^\\d{1,3}\\s*%.*$|^\\d{1,4}\\s+of\\s+\\d{1,4}(?:\\s+.*)?$|^(?:lesson|lecture|video|step|item)\\s+\\d{1,4}\\s+of\\s+\\d{1,4}$|.*\\btotal length\\b.*$")
    /** A header's own counter after it: "Section 1: Intro 3 / 5 | 32min", "Week 2 · 4 videos · 1 h". */
    private val HEADER_TAIL = Regex("(?i)\\s*(?:[|·•\\-–—,]\\s*)?(?:\\d{1,3}\\s*/\\s*\\d{1,3}|\\d{1,4}\\s*(?:lectures?|lessons?|videos?|items?|readings?|quizzes|quiz|steps?))\\b.*$")
    /** A line that is only a lesson's kind and time: "Video · 5 min", "2min", "02:14", "1h 5m", "Reading • 10 minutes". */
    private val TIME_ONLY = Regex("(?i)^(?:$TYPE\\s*[:•·|\\-–—,]?\\s*)?(\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{1,2}(?:[.,]\\d)?\\s*(?:h|hr|hrs|hours?)\\s*(?:\\d{1,2}\\s*(?:m|min|mins|minutes?)?)?|\\d{1,3}\\s*(?:m|min|mins|minutes?))\\s*(?:left|remaining)?$")
    /** A kind before a time at a lesson's end: "Welcome · Video · 5 min" → "Welcome · 5 min". */
    private val TYPE_TAIL = Regex("(?i)\\s*[:•·|\\-–—,]\\s*$TYPE\\s*[:•·|\\-–—,]?\\s*(?=\\d)")
    /** A kind alone at a lesson's end: "Welcome · Video". */
    private val TYPE_END = Regex("(?i)\\s*[:•·|\\-–—,(]\\s*$TYPE\\s*\\)?$")
    /** "Welcome 02:14", "Setup (1:02:30)". */
    private val CLOCK_END = Regex("\\s*[(\\[]?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*[)\\]]?$")
    /** A lesson's ticked/left state after it: "Welcome · Completed". */
    private val STATE_END = Regex("(?i)\\s*[:•·|\\-–—,]\\s*(?:completed|complete|done|not started|in progress|locked|preview|play)\\s*$")

    /** Minutes in "02:14" (m:ss) or "1:02:30" (h:mm:ss), at least 1; null when out of range. */
    fun clockMins(m: MatchResult): Int? {
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        val c = m.groupValues[3].toIntOrNull()
        if (b > 59 || (c != null && c > 59)) return null
        val secs = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        return ((secs + 30) / 60).coerceAtLeast(1).takeIf { it <= StudyRules.MAX_MINS }
    }

    /** The minutes a time-only line says ([TIME_ONLY]), or null when it isn't one. */
    fun timeOnly(line: String): Int? {
        val m = TIME_ONLY.find(line.trim()) ?: return null
        val t = m.groupValues[1]
        CLOCK_END.find(t)?.let { return clockMins(it) }
        val (_, mins) = StudyRules.lengthOf("x $t")
        return mins
    }

    private fun isFurniture(line: String): Boolean {
        val k = line.lowercase().trim().trimEnd('.', ':', '›', '>', '…').trim()
        return k in furniture || COUNTER.matches(line) || PROGRESS.matches(line)
    }

    /** A lesson line tidied: its kind and state dropped, a clock time at the end as "N min". */
    private fun lessonLine(line: String): String {
        var t = line.replace(STATE_END, "").replace(TYPE_TAIL, " ")
        CLOCK_END.find(t)?.let { m ->
            val rest = t.substring(0, m.range.first)
            if (rest.any { it.isLetter() }) t = clockMins(m)?.let { "$rest $it min" } ?: rest
        }
        return t.replace(TYPE_END, "").trim()
    }

    /**
     * The outline in [text] (what the device read off the picture, [PictureTextRules.tidy]'s lines). Lines before the
     * first header or lesson that look like the page's title are kept as lessons only if they read like one; he checks
     * the field before anything is added.
     */
    fun scan(text: String): StudyScan {
        val out = ArrayList<String>()
        var lastIsLesson = false
        var lastHasTime = false
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.replace(GLYPHS, "").replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty() || line.count { it.isLetterOrDigit() } < 2) continue
            val mins = timeOnly(line)
            if (mins != null) {
                if (lastIsLesson && !lastHasTime) { out[out.size - 1] = "${out.last()} $mins min"; lastHasTime = true }
                continue
            }
            if (isFurniture(line)) continue
            val bare = line.replace(HEADER_TAIL, "").trim()
            val header = bare.takeIf { it.isNotEmpty() }?.let { StudyRules.moduleOf(StudyRules.lengthOf(it).first) }
            if (header != null) {
                val h = StudyRules.lengthOf(bare).first.trim().trimEnd(':').trim()
                val keep = if (StudyRules.moduleOf(h) != null) h else "$h:"
                if (out.lastOrNull() != keep) out += keep
                lastIsLesson = false; lastHasTime = false
                continue
            }
            val lesson = lessonLine(line)
            if (lesson.count { it.isLetter() } < 2 || isFurniture(lesson)) continue
            if (out.lastOrNull() == lesson) continue
            out += lesson
            lastIsLesson = true
            lastHasTime = StudyRules.lengthOf(lesson).second != null
            if (out.size >= MAX_LINES) break
        }
        val outline = out.joinToString("\n")
        val entries = StudyRules.readOutline(outline, null)
        val headers = out.count { StudyRules.moduleOf(it) != null }
        val modules = if (headers == 0) 0 else entries.map { it.module }.distinct().size
        return StudyScan(outline, modules, entries.size, readLine(entries.size, modules, entries.count { it.mins != null }))
    }

    /** "Read 14 lessons in 3 modules, 12 with a time · check them, then Add lessons · the picture isn't kept". */
    fun readLine(lessons: Int, modules: Int, timed: Int): String {
        if (lessons == 0) return NONE_LINE
        val l = if (lessons == 1) "1 lesson" else "$lessons lessons"
        val m = when (modules) { 0 -> ""; 1 -> " in 1 module"; else -> " in $modules modules" }
        val t = when {
            timed == 0 -> ""
            timed == lessons -> if (lessons == 1) ", with its time" else ", all with a time"
            else -> ", $timed with a time"
        }
        return "Read $l$m$t · check them, then Add lessons · ${PictureTextRules.NOT_KEPT}"
    }

    /** What the field holds after a read: [field]'s own lines first (he may be adding a second screenshot), then the new ones. */
    fun merge(field: String, scan: StudyScan): String = when {
        !scan.found -> field
        field.isBlank() -> scan.outline
        else -> field.trimEnd() + "\n" + scan.outline
    }
}
