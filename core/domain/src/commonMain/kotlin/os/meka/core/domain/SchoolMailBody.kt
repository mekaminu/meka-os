package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * School emails first, slice 2 (Meka 2026-11-24: "the school email needs a priority as I miss what it says"; non-AI,
 * pure): **the school's emails are read whole**, not from the subject and preview. A long message is read sentence by
 * sentence for every dated thing in it (several dates in one email are several finds), each with its time ("12:00–16:00"
 * from "12pm-4pm", "from 2.30pm"), where it is ("in the school hall"), and the notes that go with it (what to bring, wear
 * or do; parking and travel). The email also gets a **summary**: up to five short lines, When · Where · Cost · Bring / do
 * · Deadline (and For, the year or class, when there is room), which the apps show as its card in From the school.
 *
 * Every word is other people's text (ADR-006): data, never instructions. Nothing here follows a link, pays, signs or
 * sends; a line with a link or an address in it proposes nothing. The body is never kept: only the short lines are.
 * Rule 13: no school, child or family name lives in the code; the children's names come from About you.
 */

/** One line of a school email's summary card: its label ([SchoolBodyRules.WHEN] …) and the email's words. */
data class SchoolSummaryLine(val label: String, val value: String)

/** What a whole school email holds ([SchoolBodyRules.read]): its dated finds and its summary card. */
data class SchoolBodyRead(val finds: List<SchoolBodyFind>, val summary: List<SchoolSummaryLine>)

/** One find from the body, with the details that go into its calendar entry later (slice 3). */
data class SchoolBodyFind(
    val find: SchoolMailFind,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val place: String? = null,
    val notes: List<String> = emptyList(),
)

object SchoolBodyRules {
    const val WHEN = "When"
    const val WHERE = "Where"
    const val COST = "Cost"
    const val BRING = "Bring / do"
    const val DEADLINE = "Deadline"
    const val FOR = "For"
    /** The card's lines, at most. */
    const val MAX_LINES = 5
    /** Finds from one email, at most (a whole term's dates is School's yearly look's job). */
    const val MAX_FINDS = 6
    /** Of the body, this much is read (a newsletter's tail adds nothing). */
    const val MAX_BODY = 20_000
    /** School emails read whole per check, at most. */
    const val PER_CHECK = 10
    private const val MAX_VALUE = 70
    private const val MAX_NOTE = 80

    private val I = setOf(RegexOption.IGNORE_CASE)

    /** The email's plain words: its text part, else its HTML made plain; capped at [MAX_BODY]. */
    fun plain(facts: MailMessageFacts): String {
        val raw = facts.text?.takeIf { it.isNotBlank() } ?: facts.html?.let { MailHtmlSanitizer.text(it) } ?: ""
        return MailHtmlSanitizer.tidyText(raw.take(MAX_BODY))
    }

    /** The sentences of [body]: lines and full stops split them; a "P.E." or "a.m." doesn't. */
    fun sentences(body: String): List<String> {
        val t = body.replace(Regex("""\bp\.\s?e\.""", I), "PE")
            .replace(Regex("""(\d)\s?a\.m\.""", I), "$1am").replace(Regex("""(\d)\s?p\.m\.""", I), "$1pm")
        return t.split(Regex("""\n+|(?<=[.!?])\s+(?=[A-Z0-9£"“(])"""))
            .map { it.trim().trimEnd('.', ' ') }.filter { it.length >= 3 }
    }

    // ---- Times ----

    private const val T = """(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|noon|midday)?"""
    /** "12pm-4pm", "12 – 4pm", "9.15am to 10.30am", "from 12:00 until 16:00". */
    private val RANGE = Regex("""(?<![\d/£.:])$T\s*(?:-|–|—|to|until|till)\s*$T(?![\d/])""", I)
    /** "at 2.30pm", "from 3:15", "doors open 6pm", "12 noon". */
    private val SINGLE = Regex("""(?<![\d/£.:])(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|noon|midday)(?![a-z])|\b(?:at|from)\s+(\d{1,2})[:.](\d{2})\b""", I)

    private fun minute(h: Int, m: Int, suffix: String): Int? {
        if (m !in 0..59) return null
        val s = suffix.lowercase()
        val hour = when (s) {
            "am" -> if (h == 12) 0 else h
            "pm" -> if (h == 12) 12 else h + 12
            "noon", "midday" -> if (h == 12) 12 else return null
            else -> h
        }
        if (hour !in 0..23 || (s.isNotEmpty() && h !in 1..12)) return null
        return hour * 60 + m
    }

    /** The first time or time range in [s] as minutes after midnight: (start, end or null); null when none. */
    fun times(s: String): Pair<Int, Int?>? {
        RANGE.find(s)?.let { r ->
            val g = r.groupValues
            val (h1, m1, s1) = Triple(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), g[3])
            val (h2, m2, s2) = Triple(g[4].toInt(), g[5].ifEmpty { "0" }.toInt(), g[6])
            // A range needs a clock word or a colon somewhere, so "2–3 Nov" or "Years 1-2" is not a time.
            val clocky = s1.isNotEmpty() || s2.isNotEmpty() || (g[2].isNotEmpty() && g[5].isNotEmpty())
            if (clocky) {
                val end = minute(h2, m2, s2)
                var start = minute(h1, m1, s1.ifEmpty { if (s2.equals("pm", true) && h1 in 1..11 && h1 < h2) "pm" else if (s2.isNotEmpty() && !s2.equals("pm", true)) s2 else "" })
                // "12-4pm": a bare 12 before an afternoon end is noon; "10-2pm" is ten in the morning.
                if (s1.isEmpty() && s2.equals("pm", true) && start != null && end != null && start > end) start -= 12 * 60
                if (start != null && end != null && end > start) return start to end
            }
        }
        SINGLE.find(s)?.let { m ->
            val g = m.groupValues
            val at = if (g[1].isNotEmpty()) minute(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), g[3])
            else minute(g[4].toInt(), g[5].toInt(), "")
            if (at != null) return at to null
        }
        return null
    }

    /** "12:00–16:00", "14:30". */
    fun timeLabel(start: Int, end: Int?): String {
        fun hm(x: Int) = "${(x / 60).toString().padStart(2, '0')}:${(x % 60).toString().padStart(2, '0')}"
        return if (end != null) "${hm(start)}–${hm(end)}" else hm(start)
    }

    // ---- Where, cost, notes, who for ----

    private val PLACE_NOUNS = """hall|playground|field|grounds|playing\s+field|church|library|classroom|classrooms|canteen|dining\s+hall|""" +
        """main\s+entrance|front\s+gate|gate|office|reception\s+area|car\s+park|leisure\s+centre|sports\s+centre|community\s+centre|village\s+hall|theatre|park"""
    /** "in the school hall", "at St Mary's Church", "venue: the main hall". */
    private val WHERE_RE = Regex(
        """\b(?:venue|location|where)\s*[:\-]\s*([^.\n;]{3,60})|\b(?:in|at|on)\s+(the\s+(?:[a-z']+\s+){0,3}(?:$PLACE_NOUNS))\b""", I,
    )
    private val WHERE_NAMED = Regex("""\b(?:held|taking\s+place|take\s+place|takes\s+place|meet|meeting)\s+(?:in|at)\s+((?:the\s+)?[A-Z][\w'’]*(?:\s+[A-Z][\w'’]*){0,4})""")
    /** "£1 entry per adult", "£2.50 per child", "£12 each". */
    private val COST_RE = Regex("""£\s?\d{1,4}(?:\.\d{2})?(?:\s*(?:per|a|each|for|entry|donation|on\s+the\s+door)\b[^.,;\n]{0,30})?""", I)
    private val FREE_RE = Regex("""\b(?:free\s+(?:entry|of\s+charge|admission)|entry\s+is\s+free|no\s+charge)\b""", I)
    /** Wear: "children may wear Christmas jumpers", "come dressed as a book character". */
    private val WEAR = Regex(
        """\b(?:(?:children|pupils|your\s+child(?:ren)?|everyone)\s+(?:can|may|are\s+(?:welcome|invited)\s+to|should|will\s+need\s+to)\s+(?:wear|come\s+(?:dressed|in))|wear(?:ing)?|dress(?:ed)?\s+(?:up\s+)?as)\s+([^.!?\n]{3,60})""", I,
    )
    /** Parking and travel notes worth a line on their own. */
    private val TRAVEL = Regex(
        """\b(?:no\s+parking(?:\s+(?:at|on|in|near|outside)\s+[^.,;\n]{3,30})?|parking\s+(?:is|will\s+be)\s+(?:not\s+available|limited|restricted)|please\s+(?:do\s+not|don['’]t)\s+park|please\s+walk|park\s+(?:at|on|in)\s+[^.,;\n]{3,40}|""" +
            """(?:the\s+)?(?:coach|bus)\s+(?:will\s+)?(?:leave|depart|return)s?\b[^.;\n]{0,40}|pick[\s-]?up\s+(?:is\s+|will\s+be\s+)?(?:at|from)\s+[^.,;\n]{3,30}|""" +
            """(?:early|late)\s+(?:finish|pick[\s-]?up)\b[^.;\n]{0,30})""", I,
    )
    /** "Year 3", "Years 5 and 6", "Reception", "KS2", "the whole school". */
    private val FOR_RE = Regex(
        """\b(years?\s+\d{1,2}(?:\s*(?:and|&|-|–|to)\s*\d{1,2})?|reception(?:\s+class)?|nursery|ks\s?[12]|key\s+stage\s+[12]|(?:the\s+)?whole\s+school|all\s+(?:pupils|year\s+groups|children))\b""", I,
    )
    /** A deadline: "pay by", "return by", "book before", "deadline". */
    private val DEADLINE_RE = Regex("""\b(?:by|before|no\s+later\s+than|deadline|due|closes?)\b""", I)
    private val DEADLINE_WHAT = Regex("""\b(?:pay|payment|return|reply|book|booking|sign|consent|form|slip|order|register|sign[\s-]?up|tickets?)\b""", I)
    /** A link, an address or a "click here": nothing to do by hand, never a line. */
    private val LINKY = Regex("""https?:|www\.|@|click\s+here|log\s?in|unsubscribe""", I)

    private fun clip(s: String, max: Int = MAX_VALUE): String {
        val t = s.trim().trimEnd('.', ',', ';', ':', ' ').replace(Regex("""\s+"""), " ")
        if (t.length <= max) return t
        val cut = t.take(max - 1)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: cut.length).trimEnd(',', ' ') + "…"
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun place(s: String): String? {
        if (LINKY.containsMatchIn(s)) return null
        WHERE_RE.find(s)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).takeIf { it.isNotBlank() }?.let { return clip(it, 48) } }
        WHERE_NAMED.find(s)?.let { m -> return clip(m.groupValues[1], 48) }
        return null
    }

    fun cost(s: String): String? {
        if (LINKY.containsMatchIn(s)) return null
        COST_RE.find(s)?.let { return clip(it.value.replace(Regex("""£\s+"""), "£"), 48) }
        FREE_RE.find(s)?.let { return cap(it.value.lowercase()) }
        return null
    }

    /** What to bring, wear or do in [s], and its parking or travel note: "Bring a named water bottle", "Wear Christmas jumpers", "No parking at school". */
    fun notes(s: String): List<String> {
        if (LINKY.containsMatchIn(s)) return emptyList()
        val out = mutableListOf<String>()
        SchoolMailRules.ask(s)?.let { out += it }
        WEAR.find(s)?.let { m ->
            val thing = m.groupValues[1].replace(Regex("""\s+(?:for|on|this|next|to\s+(?:raise|support|celebrate)|in\s+exchange|if|as\s+part)\b.*$""", I), "").trim()
                .replace(Regex("""^(?:their|your|his|her|a\s+pair\s+of)\s+""", I), "")
            if (thing.length >= 3 && out.none { it.contains(thing, ignoreCase = true) }) out += clip("Wear $thing", MAX_NOTE)
        }
        TRAVEL.find(s)?.let { out += clip(cap(it.value), MAX_NOTE) }
        return out
    }

    fun forWhom(s: String): String? = FOR_RE.find(s)?.value?.let { v ->
        cap(v.replace(Regex("""\s+"""), " ").replace(Regex("""^the\s+""", I), "").lowercase()).replace(Regex("""\bks\s?(\d)""", I), "KS$1")
    }

    // ---- The whole email ----

    /**
     * Read the whole school email: [subject] and its [body] (plain, [plain]) that came on [receivedDay], with About
     * you's [children]. Finds: every sentence with a date on or after the email (within [SchoolMailRules.MAX_DAYS])
     * that names a school thing, the subject lending its topic to a sentence that names only a date ("It will take
     * place on Saturday 28 November"); the subject's own read too; one per thing and day, at most [MAX_FINDS]. Each find
     * takes the time, place and notes of its own sentence, else of the email. Undated asks go to the card's Bring / do,
     * not finds (the subject and preview's own read still offers one).
     */
    fun read(subject: String?, body: String, receivedDay: Long, children: List<String> = emptyList()): SchoolBodyRead {
        val sents = sentences(body)
        val all = listOfNotNull(subject) + sents
        val finds = mutableListOf<SchoolBodyFind>()
        fun keep(f: SchoolMailFind, sentence: String?) {
            if (f.ask && f.endDay > f.startDay) return
            if (finds.size >= MAX_FINDS) return
            if (finds.any { it.find.title.equals(f.title, true) && it.find.startDay == f.startDay && it.find.who == f.who }) return
            // The subject's own find and a sentence's of the same thing on the same day are one.
            if (finds.any { it.find.startDay == f.startDay && it.find.kind == f.kind && (it.find.title.startsWith(f.title, true) || f.title.startsWith(it.find.title, true)) }) return
            val own = sentence?.let(::times)
            finds += SchoolBodyFind(
                f, startMinute = own?.first, endMinute = own?.second,
                place = sentence?.let(::place),
                notes = sentence?.let(::notes).orEmpty(),
            )
        }
        val dated = sents.filter { hasDate(it, receivedDay) }
        val subjectTopic = subject?.takeIf { it.isNotBlank() }?.let { SchoolRules.findSpan(it, receivedDay).let { f -> if (f is SpanFound.Days) f.rest else it } }
        for (s in dated) {
            val own = SchoolMailRules.readAll(null, s, receivedDay, children)
            val list = own.ifEmpty {
                subjectTopic?.let { t -> SchoolMailRules.readAll(null, "$s. $t", receivedDay, children) }.orEmpty()
            }
            list.filter { !it.ask || it.endDay == it.startDay }.forEach { keep(it, s) }
        }
        // The subject and the opening's own read, as slice 1 did, so nothing it found is lost.
        SchoolMailRules.readAll(subject, sents.take(3).joinToString(". "), receivedDay, children).forEach { keep(it, null) }
        finds.sortBy { it.find.startDay }

        // The email's own time, place and notes fill its one event's gaps (one event per email is the usual case; its
    // deadlines, "bring a bottle by Friday", are finds of their own).
        val firstTime = sents.firstNotNullOfOrNull(::times)
        val firstPlace = sents.firstNotNullOfOrNull(::place)
        val allNotes = sents.flatMap(::notes).distinctBy { it.lowercase() }
        val single = finds.singleOrNull { !it.find.ask && !SchoolRules.isDue(it.find.title) }
        val filled = finds.map { b ->
            if (b === single) b.copy(
                startMinute = b.startMinute ?: firstTime?.first, endMinute = if (b.startMinute == null) firstTime?.second else b.endMinute,
                place = b.place ?: firstPlace, notes = (b.notes + allNotes).distinctBy { it.lowercase() }.take(4),
            ) else b
        }
        return SchoolBodyRead(filled, summary(filled, all, receivedDay))
    }

    private fun hasDate(s: String, receivedDay: Long): Boolean {
        val f = SchoolRules.findSpan(s, receivedDay)
        if (f is SpanFound.Days && f.startDay >= receivedDay && f.startDay - receivedDay <= SchoolMailRules.MAX_DAYS) return true
        val d = MailWeekRules.dayOf(s, receivedDay) ?: return false
        return d >= receivedDay && d - receivedDay <= SchoolMailRules.MAX_DAYS
    }

    /** The card: When · Where · Cost · Bring / do · Deadline, then For when there is room; at most [MAX_LINES]. */
    private fun summary(finds: List<SchoolBodyFind>, texts: List<String>, receivedDay: Long): List<SchoolSummaryLine> {
        val out = mutableListOf<SchoolSummaryLine>()
        val events = finds.filter { !SchoolRules.isDue(it.find.title) && !it.find.ask }
        val deadlines = finds.filter { SchoolRules.isDue(it.find.title) || it.find.ask }
        events.firstOrNull()?.let { e ->
            val day = if (e.find.kind == SchoolKind.WEEKLY) "Every " + CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(e.find.startDay) - 1]
            else SchoolRules.spanLabel(e.find.startDay, e.find.endDay)
            val time = e.startMinute?.let { ", " + timeLabel(it, e.endMinute) }.orEmpty()
            val more = if (events.size > 1) " (+${events.size - 1} more)" else ""
            out += SchoolSummaryLine(WHEN, "$day$time$more")
        }
        (events.firstOrNull()?.place ?: texts.firstNotNullOfOrNull(::place))?.let { out += SchoolSummaryLine(WHERE, cap(it)) }
        texts.firstNotNullOfOrNull(::cost)?.let { out += SchoolSummaryLine(COST, it) }
        // A thing to do by a day is the Deadline line, not repeated under Bring / do.
        val notes = (events.firstOrNull()?.notes.orEmpty() + texts.flatMap(::notes)).distinctBy { it.lowercase() }
            .filter { n -> deadlines.none { it.find.title.equals(n, true) } }
        if (notes.isNotEmpty()) out += SchoolSummaryLine(BRING, clip(notes.take(2).joinToString(" · "), MAX_VALUE + 20))
        val deadline = deadlines.firstOrNull()?.let { d -> "${d.find.title} · ${CivilDate.shortLabel(d.find.startDay)}" }
            ?: texts.firstOrNull { DEADLINE_RE.containsMatchIn(it) && DEADLINE_WHAT.containsMatchIn(it) && hasDate(it, receivedDay) && !LINKY.containsMatchIn(it) }
                ?.let { s ->
                    val d = (SchoolRules.findSpan(s, receivedDay) as? SpanFound.Days)?.startDay ?: MailWeekRules.dayOf(s, receivedDay)
                    d?.let { "${SchoolMailRules.ask(s) ?: "Reply"} · by ${CivilDate.shortLabel(it)}" }
                }
        deadline?.let { out += SchoolSummaryLine(DEADLINE, clip(it)) }
        if (out.size < MAX_LINES) texts.firstNotNullOfOrNull(::forWhom)?.let { out += SchoolSummaryLine(FOR, it) }
        return out.take(MAX_LINES).map { it.copy(value = clip(it.value, MAX_VALUE + 20)) }
    }

    // ---- On the wire ----

    /** "When\tSat 28 Nov, 12:00–16:00\nWhere\tThe school hall": tabs and line breaks inside a value become spaces. */
    fun encode(lines: List<SchoolSummaryLine>): String = lines.take(MAX_LINES).joinToString("\n") { l ->
        "${l.label.replace(Regex("""[\t\n]"""), " ")}\t${l.value.replace(Regex("""[\t\n]"""), " ")}"
    }

    fun decode(s: String?): List<SchoolSummaryLine> = s?.split('\n')?.mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else SchoolSummaryLine(line.substring(0, i), line.substring(i + 1)).takeIf { it.value.isNotBlank() }
    }?.take(MAX_LINES).orEmpty()

    /** A body find's extra fields on its `mail_find` (beside [SchoolMailRules.serverFields]); none when it has none. */
    fun findFields(b: SchoolBodyFind): Map<String, FieldValue> = buildMap {
        b.startMinute?.let { put(MailFindFields.START_MINUTE, it.toLong().fv()) }
        b.endMinute?.let { put(MailFindFields.END_MINUTE, it.toLong().fv()) }
        b.place?.let { put(MailFindFields.PLACE, it.fv()) }
        if (b.notes.isNotEmpty()) put(MailFindFields.NOTES, b.notes.joinToString("\n").fv())
    }
}
