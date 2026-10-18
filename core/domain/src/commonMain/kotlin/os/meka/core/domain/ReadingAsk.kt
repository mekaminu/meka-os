package os.meka.core.domain

/**
 * Reading and learning, slice 2: Ask and Talk know the reading list, with no AI. "What am I reading?" is answered from
 * his own list on the device; "I'm on page 200", "I'm halfway through Dune", "I've finished Atomic Habits", "I've
 * started reading Dune" and "add Dune to my reading list" are his own direct requests about his own list, done at
 * once with Undo (on screen and in Activity) under the same rule as "the mic should just add it" (What Navilife may
 * do → Add what you ask for; Ask first makes each a card he taps). Nothing is fetched and nothing leaves the device.
 */
enum class ReadingChange { ADD, START, PROGRESS, FINISH }

/** What a question about reading asks for, before it is matched to his list. */
sealed interface ReadingAsk {
    /** "What am I reading?", "what's on my reading list?"; [title] for one ("how far am I through Dune?"). */
    data class What(val title: String?) : ReadingAsk
    /** "Add Dune to my reading list", "I want to read Dune", "add the podcast The Rest Is History" ([text] as typed in Reading). */
    data class Add(val text: String) : ReadingAsk
    /** "I've started Dune", "I'm reading Dune" ([reading]: said with "reading", so an unknown title is added on the go). */
    data class Start(val title: String, val reading: Boolean) : ReadingAsk
    /** "I'm on page 200 (of Dune)", "I'm 45% through", "episode 5 of The Rest Is History": [text] as Reading's field takes it. */
    data class Progress(val title: String?, val text: String, val vague: Boolean = false) : ReadingAsk
    /**
     * "I've finished Atomic Habits", "I've finished the book". [vague] when only "it" or "that" was said: it is the
     * book only while exactly one thing is on the go, else the AI's (it could be a task).
     */
    data class Finish(val title: String?, val vague: Boolean = false) : ReadingAsk
}

/** What the device makes of it: words to say, or a change to make. */
sealed interface ReadingAskResult {
    data class Answer(val text: String) : ReadingAskResult
    data class Do(val proposal: AskProposal.ReadingUpdate) : ReadingAskResult
}

object ReadingAskRules {
    const val EMPTY = "Nothing on your reading list yet. Say \"add Atomic Habits to my reading list\", or add one in Ask → Daily → Reading."
    const val NOTHING_ON = "Nothing's on the go in Reading. Say which one, like \"page 200 of Atomic Habits\"."

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private val polite = Regex(
        """^(?:(?:hey|ok|okay|oh|um|erm|right|so)[,\s]+)*(?:(?:meka|navilife|navi life|navy life)[,\s]+)?(?:(?:can|could|would|will) you\s+)?(?:please\s+)?""", opt,
    )
    private val tailPolite = Regex("""[\s,]+(?:please|thanks|thank you|for me)$""", opt)

    private const val NOW = """(?:\s+(?:at\s+the\s+moment|right\s+now|now|currently|these\s+days|this\s+(?:week|month)))?"""
    private val what = listOf(
        Regex("""^what(?:\s+am\s+i|\s+i'?m|'?m\s+i)\s+(?:currently\s+|now\s+)?(?:reading|listening\s+to|studying|learning)$NOW$""", opt),
        Regex("""^what\s+(?:books?|podcasts?|courses?)\s+(?:am\s+i|are\s+on\s+my\s+list|do\s+i\s+have)(?:\s+(?:reading|listening\s+to|doing|taking|on\s+the\s+go))?$NOW$""", opt),
        Regex("""^(?:what'?s|what\s+is|what\s+are|show\s+me|read\s+me|tell\s+me|read\s+out)\s+(?:on\s+|in\s+)?(?:my|the)\s+(?:reading(?:\s+list)?|to[- ]?read(?:\s+list)?|book\s+list|books)$""", opt),
        Regex("""^(?:my\s+)?reading\s+list$""", opt),
        Regex("""^what\s+(?:should|shall|do)\s+i\s+read\s+next$""", opt),
        Regex("""^what'?s\s+next\s+(?:to\s+read|on\s+my\s+reading\s+list|on\s+my\s+list\s+to\s+read)$""", opt),
        Regex("""^(?:what|how\s+many\s+books)\s+(?:have\s+i|did\s+i)\s+(?:finished|read)(?:\s+(?:this\s+(?:year|month)|lately|recently))?$""", opt),
    )
    private val howFar = Regex("""^how\s+far\s+(?:am\s+i|have\s+i\s+got|did\s+i\s+get|i'?m)\s+(?:through|into|with|in|on)\s+(.+)$""", opt)

    private val add = listOf(
        Regex("""^(?:add|put|pop|stick|save)\s+(.+?)\s+(?:to|on|onto|in|into)\s+(?:my|the)\s+(?:reading(?:\s+list)?|to[- ]?read(?:\s+list)?|books?(?:\s+list)?|reading\s+pile)$""", opt),
        Regex("""^(?:add|save)\s+((?:the\s+|a\s+)?(?:book|audiobook|podcast|course)\s+.+)$""", opt),
        Regex("""^i\s+(?:want|'d\s+like|would\s+like)\s+to\s+read\s+(.+)$""", opt),
    )

    /** "the podcast Hard Fork" → "podcast Hard Fork", so Reading reads the kind. */
    private val article = Regex("""^(?:the|a|an)\s+(?=(?:book|audiobook|podcast|course)\s)""", opt)

    private val unitWord = """(page|p|pg|episode|ep|lesson|module|chapter|ch)"""
    private const val OF_TITLE = """(?:\s+(?:of|in|on|with|through|for)\s+(.+?))?"""
    private val progressLead = """(?:(?:i'?m|i\s+am)(?:\s+now)?\s+(?:on|at|up\s+to)|now\s+(?:on|at)|(?:i'?ve|i\s+have|i)\s+(?:got|gotten|read)\s+(?:up\s+)?to|on)"""
    private val progressAt = Regex("""^(?:$progressLead\s+)?$unitWord\.?\s*(\d{1,5})(?:\s*(?:of|/|out\s+of)\s*(\d{1,5}))?$OF_TITLE$""", opt)
    private val percent = Regex(
        """^(?:i'?m|i\s+am)\s+(?:about\s+|around\s+|nearly\s+|almost\s+)?(\d{1,3})\s*(?:%|percent|per\s+cent)\s+(?:of\s+the\s+way\s+)?(?:through|into|done\s+with|in|of)(?:\s+(.+))?$""", opt,
    )
    private val halfway = Regex("""^(?:i'?m|i\s+am)\s+(?:about\s+|nearly\s+|almost\s+)?half\s*-?\s*way\s+(?:through|into|in)(?:\s+(.+))?$""", opt)
    private val finish = Regex(
        """^(?:(?:i'?ve|i\s+have|i)\s+(?:just\s+|now\s+|finally\s+)?)?(?:finished|completed)\s+(?:reading\s+|listening\s+to\s+)?(.+)$""", opt,
    )
    private val started = Regex("""^(?:i'?ve|i\s+have|i)\s+(?:just\s+)?(?:started|begun|began)\s+(reading\s+|listening\s+to\s+)?(.+)$""", opt)
    private val nowReading = Regex("""^(?:i'?m|i\s+am)\s+(?:now\s+|currently\s+)?(reading|listening\s+to)\s+(.+)$""", opt)

    /** Words that point at the one on the go rather than name it. */
    private val PRONOUNS = setOf(
        "it", "that", "this", "this one", "that one", "the book", "my book", "the podcast", "my podcast", "the course",
        "my course", "the audiobook", "my audiobook", "the one", "",
    )

    private fun clean(question: String): String? {
        var q = question.replace('’', '\'').replace(space, " ").trim()
        if (q.isEmpty() || q.length > 200) return null
        q = q.substring(polite.find(q)?.value?.length ?: 0).trim()
        q = q.trimEnd('?', '.', '!').trim().replace(tailPolite, "").trim()
        return q.ifEmpty { null }
    }

    private val VAGUE = setOf("it", "that", "this", "this one", "that one", "the one")

    private fun vague(s: String?): Boolean = s?.trim()?.lowercase()?.trim('.', ',') in VAGUE

    private fun named(s: String?): String? = s?.trim()?.trim('"', '“', '”', ',', '.')?.takeIf { it.lowercase() !in PRONOUNS }

    /** What [question] asks of the reading list; null when it isn't about it (the other rules' and the AI's). */
    fun request(question: String): ReadingAsk? {
        val q = clean(question) ?: return null
        if (what.any { it.matches(q) }) return ReadingAsk.What(null)
        howFar.matchEntire(q)?.let { m -> return ReadingAsk.What(named(m.groupValues[1])) }
        add.firstNotNullOfOrNull { it.matchEntire(q) }?.let { m -> return ReadingAsk.Add(m.groupValues[1].trim().replace(article, "")) }
        progressAt.matchEntire(q)?.let { m ->
            val text = "${m.groupValues[1]} ${m.groupValues[2]}" + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { " of $it" } ?: "")
            return ReadingAsk.Progress(named(m.groupValues[4]), text.lowercase())
        }
        percent.matchEntire(q)?.let { m ->
            val pct = m.groupValues[1].toInt()
            if (pct > 100) return null
            return ReadingAsk.Progress(named(m.groupValues[2]), "$pct%", vague(m.groupValues[2]))
        }
        halfway.matchEntire(q)?.let { m -> return ReadingAsk.Progress(named(m.groupValues[1]), "50%", vague(m.groupValues[1])) }
        finish.matchEntire(q)?.let { m -> return ReadingAsk.Finish(named(m.groupValues[1]), vague(m.groupValues[1])) }
        started.matchEntire(q)?.let { m -> named(m.groupValues[2])?.let { return ReadingAsk.Start(it, m.groupValues[1].isNotBlank()) } }
        nowReading.matchEntire(q)?.let { m ->
            named(m.groupValues[2])?.let { return ReadingAsk.Start(it, m.groupValues[1].lowercase().startsWith("reading")) }
        }
        return null
    }

    private fun core(s: String): String = ReadingRules.fold(
        s.lowercase().removePrefix("the ").removePrefix("my ").removePrefix("a ")
            .removeSuffix(" one").removeSuffix(" book").removeSuffix(" podcast").removeSuffix(" course"),
    )

    /** The item on his list [name] names: the same name first, then one starting with or holding it; on the go first. */
    fun find(items: List<ReadingItem>, name: String): ReadingItem? {
        val n = core(name)
        if (n.length < 2) return null
        val live = items.filter { !it.removed }.sortedBy {
            when (it.state) { ReadingState.READING -> 0; ReadingState.WANT -> 1; ReadingState.PUT_DOWN -> 2; ReadingState.FINISHED -> 3 }
        }
        live.firstOrNull { core(it.title) == n || ReadingRules.fold(it.title) == n }?.let { return it }
        if (n.length < 4) return null
        return live.firstOrNull { core(it.title).startsWith(n) }
            ?: live.firstOrNull { core(it.title).contains(n) }
            ?: live.firstOrNull { val t = core(it.title); t.length >= 4 && n.contains(t) }
    }

    private fun fits(i: ReadingItem, unit: ReadingUnit?): Boolean = when (unit) {
        null, ReadingUnit.PERCENT -> true
        ReadingUnit.PAGE, ReadingUnit.CHAPTER -> i.kind == ReadingKind.BOOK
        ReadingUnit.EPISODE -> i.kind == ReadingKind.PODCAST
        ReadingUnit.LESSON -> i.kind == ReadingKind.COURSE
    }

    private fun names(titles: List<String>): String = when (titles.size) {
        0 -> ""
        1 -> titles[0]
        else -> titles.dropLast(1).joinToString(", ") + " or " + titles.last()
    }

    /** "Page 120 of 320 · 37%" → "page 120 of 320, 37%" (for saying). */
    private fun said(s: String): String = s.replace(" · ", ", ").replaceFirstChar { it.lowercase() }

    /** What [ask] comes to against his list [items]; null when it names nothing on it and should go to the AI. */
    fun resolve(ask: ReadingAsk, items: List<ReadingItem>, nowMs: Long, cal: LocalCalendar): ReadingAskResult? {
        val live = items.filter { !it.removed }
        return when (ask) {
            is ReadingAsk.What -> {
                if (ask.title == null) return ReadingAskResult.Answer(whatLine(live, nowMs, cal))
                val i = find(live, ask.title) ?: return null
                ReadingAskResult.Answer(oneLine(i, cal))
            }
            is ReadingAsk.Add -> {
                val p = ReadingRules.parse(ask.text) ?: return null
                live.firstOrNull { ReadingRules.fold(it.title) == ReadingRules.fold(p.title) && it.state != ReadingState.FINISHED }?.let {
                    return ReadingAskResult.Answer("${it.title} is already on your reading list, " + said(ReadingRules.row(it, cal).progress) + ".")
                }
                val where = if (p.started) "on the go" else "up next"
                do_(ReadingChange.ADD, null, p.title, ask.text, "Added ${p.kind.label.lowercase()} ${p.title}" + (if (p.by.isNotEmpty()) " by ${p.by}" else "") + " · $where")
            }
            is ReadingAsk.Start -> {
                val i = find(live, ask.title)
                when {
                    i == null && ask.reading -> resolve(ReadingAsk.Add("reading ${ask.title}"), items, nowMs, cal)
                    i == null -> null
                    i.state == ReadingState.READING -> ReadingAskResult.Answer("${i.title} is already on the go, " + said(ReadingRules.row(i, cal).progress) + ".")
                    else -> do_(ReadingChange.START, i.id, i.title, "", "On the go: ${i.title}")
                }
            }
            is ReadingAsk.Progress -> {
                val p = ReadingRules.progress(ask.text) ?: return null
                val i = target(live, ask.title, p.unit, ask.vague) ?: return null
                if (i is Pick.Say) return ReadingAskResult.Answer(i.text)
                val item = (i as Pick.One).item
                val moved = ReadingRules.moved(item, p)
                    ?: return ReadingAskResult.Answer("That's past the end of ${item.title}" + (if (item.total > 0) " (${ShutdownRules.count(item.total.toInt(), item.unit.one)})" else "") + ".")
                if (moved.total > 0 && moved.at >= moved.total) return do_(ReadingChange.FINISH, item.id, item.title, "", "Finished ${item.title}")
                do_(ReadingChange.PROGRESS, item.id, item.title, ask.text, "${item.title} · ${ReadingRules.where(moved) ?: "on the go"}")
            }
            is ReadingAsk.Finish -> {
                val i = target(live, ask.title, null, ask.vague) ?: return null
                if (i is Pick.Say) return ReadingAskResult.Answer(i.text)
                val item = (i as Pick.One).item
                if (item.state == ReadingState.FINISHED) return ReadingAskResult.Answer("${item.title} is already finished, " + said(ReadingRules.row(item, cal).progress) + ".")
                do_(ReadingChange.FINISH, item.id, item.title, "", "Finished ${item.title}")
            }
        }
    }

    private sealed interface Pick {
        data class One(val item: ReadingItem) : Pick
        data class Say(val text: String) : Pick
    }

    /** The one named, else the one on the go (of the kind the unit fits); asks which when there are several. */
    private fun target(live: List<ReadingItem>, title: String?, unit: ReadingUnit?, vague: Boolean): Pick? {
        if (title != null) return find(live, title)?.let { Pick.One(it) }
        val on = live.filter { it.state == ReadingState.READING }.sortedByDescending { it.movedAtMs }
        val fit = on.filter { fits(it, unit) }.ifEmpty { on }
        if (vague) return fit.singleOrNull()?.let { Pick.One(it) }
        if (live.isEmpty()) return Pick.Say(EMPTY)
        return when (fit.size) {
            0 -> Pick.Say(NOTHING_ON)
            1 -> Pick.One(fit[0])
            else -> Pick.Say("Which one? ${names(fit.take(3).map { it.title })}. Say it with the name, like \"page 200 of ${fit[0].title}\".")
        }
    }

    private fun do_(change: ReadingChange, id: String?, title: String, text: String, line: String) =
        ReadingAskResult.Do(AskProposal.ReadingUpdate(change, id, title, text, line))

    /** One item in words: "Atomic Habits by James Clear: page 120 of 320, 37%." */
    fun oneLine(i: ReadingItem, cal: LocalCalendar): String =
        i.title + (if (i.by.isNotEmpty()) " by ${i.by}" else "") + ": " + said(ReadingRules.row(i, cal).progress) + "."

    /** The whole list in a few sentences, fit to say aloud. */
    fun whatLine(live: List<ReadingItem>, nowMs: Long, cal: LocalCalendar): String {
        val v = ReadingRules.view(live, nowMs, cal)
        if (v.isEmpty) return EMPTY
        val on = live.filter { it.state == ReadingState.READING }.sortedByDescending { it.movedAtMs }
        val s1 = if (on.isEmpty()) "Nothing on the go right now."
        else "On the go: " + on.take(3).joinToString("; ") { i -> i.title + (ReadingRules.where(i)?.let { ", ${said(it)}" } ?: "") } +
            (if (on.size > 3) "; and ${on.size - 3} more" else "") + "."
        val next = v.upNext.map { it.title }
        val s2 = when {
            next.isEmpty() -> null
            next.size <= 3 -> "Up next: " + names(next).replace(" or ", " and ") + "."
            else -> "Up next: " + next.take(2).joinToString(", ") + " and ${next.size - 2} more."
        }
        val year = CivilDate.fromEpochDay(cal.epochDayOf(nowMs)).year
        val done = live.filter { it.state == ReadingState.FINISHED && it.endedAtMs > 0 && CivilDate.fromEpochDay(cal.epochDayOf(it.endedAtMs)).year == year }
            .sortedByDescending { it.endedAtMs }
        val s3 = done.firstOrNull()?.let { "Finished ${done.size} this year, most recently ${it.title}." }
        return listOfNotNull(s1, s2, s3).joinToString(" ")
    }

    /** The card's words when Ask first is on ("Atomic Habits · Page 200 of 320 · 62%"). */
    fun cardLine(p: AskProposal.ReadingUpdate): String = when (p.change) {
        ReadingChange.ADD -> p.line.replaceFirst("Added ", "Add ")
        ReadingChange.START -> "Start ${p.title} · Reading"
        ReadingChange.PROGRESS -> p.line
        ReadingChange.FINISH -> "Mark ${p.title} finished"
    }

    fun button(p: AskProposal.ReadingUpdate): String = when (p.change) {
        ReadingChange.ADD -> "Add"
        ReadingChange.START -> "Start"
        ReadingChange.PROGRESS -> "Save"
        ReadingChange.FINISH -> "Finished"
    }

    /** Talk's words: "noted page 200 of 320 in Atomic Habits" / "note …". */
    fun phrase(p: AskProposal.ReadingUpdate, past: Boolean): String = when (p.change) {
        ReadingChange.ADD -> (if (past) "added " else "add ") + p.title + " to your reading list"
        ReadingChange.START -> (if (past) "started " else "start ") + p.title
        ReadingChange.PROGRESS -> (if (past) "noted " else "note ") + said(p.line.substringAfter(" · ")) + " in " + p.title
        ReadingChange.FINISH -> (if (past) "marked " else "mark ") + p.title + " finished"
    }
}
