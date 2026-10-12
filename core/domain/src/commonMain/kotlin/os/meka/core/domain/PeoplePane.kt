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
)

data class PeopleView(val rows: List<PersonRow>, val summary: String) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = PeopleView(emptyList(), PeopleRules.EMPTY_LINE) }
}

object PeopleRules {
    const val EMPTY_LINE = "People you promise things to, wait on or have a birthday for show here"
    const val BIRTHDAY_SOON_DAYS = 7L

    private class Acc(val key: String) {
        var name: String = ""
        val promised = mutableListOf<PersonOwed>()
        val waiting = mutableListOf<PersonOwed>()
        var birthday: Occasion? = null
        var last: ContactSeen? = null
        fun offer(n: String) {
            // The first spelling seen wins, unless a later one is capitalised and the first wasn't ("rex" → "Rex").
            if (name.isEmpty() || (name.first().isLowerCase() && n.first().isUpperCase())) name = n
        }
    }

    /**
     * The People pane. [promises] and [waiting] as Lists reads them, [occasions] from Gifts (anniversaries aren't a
     * person's and are left out), [contacts] from the triage cards and held messages. A contact only adds to a row
     * someone else made (MEKA doesn't list everyone who ever messaged): the same name, or a first name that only one
     * row has ("Jeanette" and "Jeanette Akunebuni").
     */
    fun view(
        promises: List<PromiseItem>,
        waiting: List<WaitingItem>,
        occasions: List<Occasion>,
        contacts: List<ContactSeen>,
        today: Long,
        nowMs: Long,
        cal: LocalCalendar,
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
            promised.forEach { append(" You promised: ").append(it.title).append(", ").append(it.meta).append('.') }
            waiting.forEach { append(" Waiting for: ").append(it.title).append(", ").append(it.meta).append('.') }
            birthday?.let { append(' ').append(it).append('.') }
            last?.let { append(' ').append(it).append('.') }
        }
        return PersonRow(a.key, a.name, owed, promised, waiting, birthday, last, lit, spoken)
    }

    private fun tidy(name: String): String? {
        val t = name.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ").take(60)
        return t.ifEmpty { null }
    }
}
