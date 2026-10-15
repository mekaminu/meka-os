package os.meka.core.domain

/**
 * People, slice 2 (build plan V1, "People"): Ask → More → People on both apps. One row per person named in a promise
 * (either way: "You promised" or "Waiting for") or a birthday in Gifts, with what's owed both ways, their next birthday
 * and the last contact MEKA already knows of (the synced triage cards and the messages held at work: "Last message
 * Tue 13:02 · WhatsApp", "You replied Tue 13:10 · WhatsApp"). Nothing new is read and nothing leaves the phone: every
 * input is already synced. Non-AI and pure; names matched with [People.key], so "rex" and "Rex" are one row.
 */
data class ContactSeen(
    val person: String,
    val atMs: Long,
    val app: CaptureApp?,
    /** True when it was Meka replying (a triage card resolved as Sent), false when they wrote. */
    val fromMe: Boolean,
    val kind: CaptureKind = CaptureKind.MESSAGE,
)

/**
 * People, slice 4: who Meka lists on the Fold (Work mode → People: Family and Always notify; "Watch for requests from"),
 * so they have a row before any promise or birthday names them. These lists stay on the phone: the Fold passes them to
 * its own core in memory ([os.meka.core.domain.PeopleRules.view]'s `listed`); they are never synced, so the Mac's pane
 * doesn't have them.
 */
data class ListedPeople(
    val family: Set<String> = emptySet(),
    val alwaysNotify: Set<String> = emptySet(),
    val watched: Set<String> = emptySet(),
) {
    companion object {
        val NONE = ListedPeople()
        const val FAMILY = "Family"
        const val ALWAYS = "Always notify"
        const val WATCHED = "Watched for requests"
    }
}

data class PersonOwed(val id: String, val title: String, val meta: String, val due: Boolean)

data class PersonRow(
    /** [People.key] of the name. */
    val key: String,
    val name: String,
    /** "You promised 2 · Waiting for 1", null when nothing is owed either way. */
    val owedLine: String?,
    /** What Meka promised them, due first. */
    val promised: List<PersonOwed>,
    /** What Meka is waiting for from them, due first. */
    val waiting: List<PersonOwed>,
    /** "Birthday Tue 3 Nov · in 24 days · turns 60", "Birthday today · turns 60"; null when not in Gifts. */
    val birthday: String?,
    /** "Last message Tue 13:02 · WhatsApp", "You replied Yesterday 18:40 · SMS"; null when MEKA hasn't seen any. */
    val lastContact: String?,
    /** A promise or a chase is due, or the birthday is within [PeopleRules.BIRTHDAY_SOON_DAYS]. */
    val lit: Boolean,
    val spoken: String,
    /** Which of the Fold's lists has them: "Family", "Always notify" or "Watched for requests" (the first that does). */
    val listed: String? = null,
)

data class PeopleView(val rows: List<PersonRow>, val summary: String) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = PeopleView(emptyList(), PeopleRules.EMPTY_LINE) }
}

object PeopleRules {
    const val EMPTY_LINE = "People you promise things to, wait on, have a birthday for or list as family show here"
    const val BIRTHDAY_SOON_DAYS = 7L

    private class Acc(val key: String) {
        var name: String = ""
        val promised = mutableListOf<PersonOwed>()
        val waiting = mutableListOf<PersonOwed>()
        var birthday: Occasion? = null
        var last: ContactSeen? = null
        var listed: String? = null
        fun offer(n: String) {
            // The first spelling seen wins, unless a later one is capitalised and the first wasn't ("rex" → "Rex").
            if (name.isEmpty() || (name.first().isLowerCase() && n.first().isUpperCase())) name = n
        }
    }

    /**
     * The People pane. [promises] and [waiting] as Lists reads them, [occasions] from Gifts (anniversaries aren't a
     * person's and are left out), [contacts] from the triage cards and held messages. A contact only adds to a row
     * someone else made (MEKA doesn't list everyone who ever messaged): the same name, or a first name that only one
     * row has ("Jordan" and "Jordan Lane"). [listed] (the Fold's own lists, slice 4) gives each listed name
     * a row of its own, or joins the row its name (or a first name only one row has) already made; a listed phone
     * number with no name is left out.
     */
    fun view(
        promises: List<PromiseItem>,
        waiting: List<WaitingItem>,
        occasions: List<Occasion>,
        contacts: List<ContactSeen>,
        today: Long,
        nowMs: Long,
        cal: LocalCalendar,
        listed: ListedPeople = ListedPeople.NONE,
    ): PeopleView {
        val rows = LinkedHashMap<String, Acc>()
        fun acc(name: String): Acc? {
            val n = tidy(name) ?: return null
            val k = People.key(n)
            return rows.getOrPut(k) { Acc(k) }.also { it.offer(n) }
        }
        promises.forEach { p -> p.to?.let(::acc)?.promised?.add(PersonOwed(p.id, p.title, p.meta, p.state == DueState.DUE)) }
        waiting.forEach { w -> w.who?.let(::acc)?.waiting?.add(PersonOwed(w.id, w.title, w.meta, w.state == DueState.DUE)) }
        occasions.filter { it.kind == OccasionKind.BIRTHDAY && it.person.isNotBlank() }.forEach { o ->
            val a = acc(o.person) ?: return@forEach
            val b = a.birthday
            if (b == null || GiftRules.next(o, today) < GiftRules.next(b, today)) a.birthday = o
        }
        for ((names, tag) in listOf(listed.family to ListedPeople.FAMILY, listed.alwaysNotify to ListedPeople.ALWAYS, listed.watched to ListedPeople.WATCHED)) {
            names.sortedBy(People::key).forEach { name ->
                val n = tidy(name) ?: return@forEach
                val k = People.key(n)
                if (k.startsWith("tel:")) return@forEach
                val a = match(k, rows.keys)?.let { rows.getValue(it) } ?: acc(n) ?: return@forEach
                if (a.listed == null) a.listed = tag
            }
        }
        contacts.forEach { c ->
            val k = match(People.key(c.person), rows.keys) ?: return@forEach
            val a = rows.getValue(k)
            val l = a.last
            if (l == null || c.atMs > l.atMs) a.last = c
        }
        if (rows.isEmpty()) return PeopleView.EMPTY
        val built = rows.values.map { row(it, today, nowMs, cal) }
            .sortedWith(compareBy<PersonRow> { if (it.lit) 0 else 1 }.thenBy { it.name.lowercase() }.thenBy { it.key })
        return PeopleView(built, summary(built))
    }

    /** What the after-work summary holds, as contacts (1:1 only; a group message isn't contact with one person). */
    fun heldContacts(summary: AfterWorkSummary): List<ContactSeen> = summary.people.flatMap { p ->
        p.items.filter { it.conversation.isNullOrBlank() }.map { ContactSeen(p.personName, it.atMs, it.app, false, it.kind) }
    }

    /** The row [personKey] belongs to: the same key, else a first name only one row shares. */
    fun match(personKey: String, keys: Collection<String>): String? {
        if (personKey.isEmpty()) return null
        if (personKey in keys) return personKey
        val first = personKey.substringBefore(' ')
        val byFirst = keys.filter { k -> k == first || k.substringBefore(' ') == first && personKey == first }
        return byFirst.singleOrNull()
    }

    /** "3 people · 1 promise due" */
    fun summary(rows: List<PersonRow>): String {
        val n = rows.size
        val due = rows.sumOf { r -> r.promised.count { it.due } }
        val chase = rows.sumOf { r -> r.waiting.count { it.due } }
        return listOfNotNull(
            if (n == 1) "1 person" else "$n people",
            due.takeIf { it > 0 }?.let { if (it == 1) "1 promise due" else "$it promises due" },
            chase.takeIf { it > 0 }?.let { if (it == 1) "1 to chase" else "$it to chase" },
        ).joinToString(" · ")
    }

    /** "Last message Tue 13:02 · WhatsApp" / "You replied 14:05 · SMS". */
    fun contactLine(c: ContactSeen, nowMs: Long, cal: LocalCalendar): String =
        when {
            c.fromMe -> "You replied "
            c.kind == CaptureKind.MISSED_CALL -> "Missed call "
            c.kind == CaptureKind.VOICE_MESSAGE -> "Voice message "
            else -> "Last message "
        } + HeldPreviewRules.timeLabel(c.atMs, nowMs, cal) +
            (c.app?.let { " · ${it.label}" } ?: "")

    /** "Birthday Tue 3 Nov · in 24 days · turns 60", "Birthday tomorrow · turns 60", "Birthday today". */
    fun birthdayLine(o: Occasion, today: Long): String {
        val day = GiftRules.next(o, today)
        val whenText = when (day - today) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "${CivilDate.shortLabel(day)} · in ${day - today} days"
        }
        return listOfNotNull("Birthday $whenText", GiftRules.age(o, day)).joinToString(" · ")
    }

    private fun row(a: Acc, today: Long, nowMs: Long, cal: LocalCalendar): PersonRow {
        val promised = a.promised.sortedWith(compareBy<PersonOwed> { if (it.due) 0 else 1 })
        val waiting = a.waiting.sortedWith(compareBy<PersonOwed> { if (it.due) 0 else 1 })
        val owed = listOfNotNull(
            promised.size.takeIf { it > 0 }?.let { "You promised $it" },
            waiting.size.takeIf { it > 0 }?.let { "Waiting for $it" },
        ).joinToString(" · ").ifEmpty { null }
        val birthday = a.birthday?.let { birthdayLine(it, today) }
        val soon = a.birthday?.let { GiftRules.next(it, today) - today <= BIRTHDAY_SOON_DAYS } == true
        val last = a.last?.let { contactLine(it, nowMs, cal) }
        val lit = soon || promised.any { it.due } || waiting.any { it.due }
        val spoken = buildString {
            append(a.name).append('.')
            a.listed?.let { append(' ').append(it).append('.') }
            promised.forEach { append(" You promised: ").append(it.title).append(", ").append(it.meta).append('.') }
            waiting.forEach { append(" Waiting for: ").append(it.title).append(", ").append(it.meta).append('.') }
            birthday?.let { append(' ').append(it).append('.') }
            last?.let { append(' ').append(it).append('.') }
        }
        return PersonRow(a.key, a.name, owed, promised, waiting, birthday, last, lit, spoken, a.listed)
    }

    private fun tidy(name: String): String? {
        val t = name.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ").take(60)
        return t.ifEmpty { null }
    }
}

/**
 * People, slice 3: Ask and Talk know the People pane ("what did I promise Jordan?", "what am I waiting on from
 * Rex?", "when did Mum last message?"), and a model's `add_promise` ("Rex: fix his bike", a by-day optional) becomes a
 * card that adds the promise to Lists → Promises on Meka's tap, with Undo. Plain rules; nothing is sent to anyone.
 */
object PeopleAskRules {
    /** People the Ask lines name (lit first, as the pane orders them). */
    const val ASK_PEOPLE = 8
    /** Promises and waiting-fors named per person. */
    const val ASK_OWED = 4
    /** At most this many lines in all, the head included. */
    const val ASK_LINES = 28
    const val GONE = "That promise isn't in Lists any more"

    /**
     * The pane as MEKA describes it to the model (Ask's `people` lines): "People · 3 people · 1 promise due", then
     * per person "Rex · you promised 1 · waiting for 1 · birthday Tue 3 Nov · in 22 days · turns 12 · last message Tue
     * 13:02 · WhatsApp" and a line for each thing owed: "Rex · you promised: Fix his bike · by tomorrow", "Rex ·
     * waiting for: The drill back · chase today". Nothing at all while the pane is empty.
     */
    fun askLines(view: PeopleView): List<String> {
        if (view.rows.isEmpty()) return emptyList()
        val head = "People · " + view.summary + if (view.rows.size > ASK_PEOPLE) " · the first $ASK_PEOPLE below" else ""
        val out = mutableListOf(head)
        for (r in view.rows.take(ASK_PEOPLE)) {
            val about = listOfNotNull(
                r.name,
                r.listed?.lowercase(),
                r.promised.size.takeIf { it > 0 }?.let { "you promised $it" },
                r.waiting.size.takeIf { it > 0 }?.let { "waiting for $it" },
                r.birthday?.replaceFirstChar { it.lowercase() },
                r.lastContact?.let { if (it.startsWith("You ")) it.replaceFirst("You ", "you ") else it.replaceFirstChar { c -> c.lowercase() } },
            ).joinToString(" · ")
            out += about
            r.promised.take(ASK_OWED).forEach { out += "${r.name} · you promised: ${it.title} · ${bareMeta(it.meta, r.key)}" }
            if (r.promised.size > ASK_OWED) out += "${r.name} · you promised ${r.promised.size - ASK_OWED} more"
            r.waiting.take(ASK_OWED).forEach { out += "${r.name} · waiting for: ${it.title} · ${bareMeta(it.meta, r.key)}" }
            if (r.waiting.size > ASK_OWED) out += "${r.name} · waiting for ${r.waiting.size - ASK_OWED} more"
        }
        return out.take(ASK_LINES)
    }

    /** A promise's or waiting-for's meta without the person it already names ("Rex · by tomorrow" → "by tomorrow"). */
    fun bareMeta(meta: String, key: String): String {
        val first = meta.substringBefore(" · ")
        return if (meta.contains(" · ") && People.key(first) == key) meta.substringAfter(" · ") else meta
    }

    /**
     * A model's `add_promise` as a card: "Rex: fix his bike" (the person, a colon, what Meka promised) or just "Fix
     * the shed door" (nobody named), by [byDay] or no date. A person the pane already has is written as the pane
     * writes them ("rex" → "Rex"). Null with nothing promised, or one already open to the same person.
     */
    fun proposal(rows: List<PersonRow>, promised: List<PromiseItem>, title: String?, byDay: Long?): AskProposal.AddPromise? {
        val t = title?.replace('\n', ' ')?.split(' ', '\t')?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return null
        val colon = t.indexOf(':')
        val rawWho = if (colon > 0) t.substring(0, colon).trim().take(60) else ""
        val what = (if (colon > 0) t.substring(colon + 1) else t).trim().take(Lists.MAX_TITLE).trim()
        if (what.isEmpty()) return null
        val who = rawWho.takeIf { it.isNotEmpty() }?.let { w ->
            PeopleRules.match(People.key(w), rows.map { it.key })?.let { k -> rows.first { it.key == k }.name } ?: w
        }
        val whoKey = who?.let(People::key)
        if (promised.any { it.title.equals(what, ignoreCase = true) && it.to?.let(People::key) == whoKey }) return null
        return AskProposal.AddPromise(who, what.replaceFirstChar { it.uppercase() }, byDay)
    }

    private fun byWords(byDay: Long?, today: Long): String? = byDay?.let { "by " + ListRules.dayWord(it, today) }

    /** The card's words: "Promise to Rex · Fix his bike · by tomorrow" / "Promise · Fix the shed door". */
    fun cardLine(p: AskProposal.AddPromise, today: Long): String =
        listOfNotNull(p.to?.let { "Promise to $it" } ?: "Promise", p.title, byWords(p.byDay, today)).joinToString(" · ")

    /** The undo bar's line: "Added your promise to Rex: Fix his bike · by tomorrow". */
    fun doneLine(p: AskProposal.AddPromise, today: Long): String =
        "Added your promise" + (p.to?.let { " to $it: " } ?: ": ") + p.title + (byWords(p.byDay, today)?.let { " · $it" } ?: "")
}
