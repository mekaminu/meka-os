package os.meka.core.domain

/**
 * Who MEKA reads for requests (build plan V1, "Requests from my wife become tasks", slice 3; Work mode → People →
 * Watch for requests from). Non-AI and pure. The list lives on the Fold beside the Family list (it never syncs: only
 * the phone's notification listener reads messages), and applies all day, not only at work.
 *
 * Family is always watched; [people] adds anyone else (Meka's wife if she isn't on the Family list, a nanny…);
 * [groups] are group chats Meka named, read like a 1:1 chat. Everything else is never read.
 */
data class RequestWatch(
    val people: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
)

object RequestWatchRules {
    const val MAX_NAME = 60
    /** A conversation notification can carry old messages: only the last day's are read for requests. */
    const val MAX_AGE_MS = 24 * 60 * 60_000L
    /** At most this many messages from one notification go to the AI (the newest). */
    const val MAX_PER_NOTIFICATION = 5
    /** Ids already read are remembered this long, so a re-post never costs a second call. */
    const val SEEN_RETENTION_MS = 7 * 24 * 60 * 60_000L

    const val TITLE = "Watch for requests from"
    const val HINT = "When someone here asks you to do something, MEKA turns it into a card in Needs you: " +
        "Add · Change · Not a task. Family is always watched. All day, not only at work."
    const val GROUPS_HINT = "Group chats are skipped unless you name one here (as it's called in WhatsApp)."
    const val LIMITS = "MEKA only sees what Android's notification shows: very long messages may be cut, voice notes " +
        "and photos can't be read (you get a reminder to listen instead), and a message you read first on another " +
        "device may never notify. Only the message, the sender's name and its time go to MEKA's AI; nothing is kept " +
        "on the server. MEKA never replies and never marks anything read."

    /** A typed or picked name: one line, spaces collapsed, at most [MAX_NAME] characters; null when empty. */
    fun clean(name: String?): String? {
        val t = name?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()
        return t?.takeIf { it.isNotEmpty() }?.take(MAX_NAME)?.trimEnd()
    }

    /** Adds [name] unless it is already watched (the Family list counts) under another spelling. */
    fun addPerson(watch: RequestWatch, lists: PeopleLists, name: String?): RequestWatch {
        val n = clean(name) ?: return watch
        if (MessageRequestRules.isWatched(n, lists, watch.people)) return watch
        return watch.copy(people = watch.people + n)
    }

    fun removePerson(watch: RequestWatch, name: String): RequestWatch =
        watch.copy(people = watch.people.filterNot { People.key(it) == People.key(name) }.toSet())

    fun addGroup(watch: RequestWatch, name: String?): RequestWatch {
        val n = clean(name) ?: return watch
        if (watch.groups.any { People.key(it) == People.key(n) }) return watch
        return watch.copy(groups = watch.groups + n)
    }

    fun removeGroup(watch: RequestWatch, name: String): RequestWatch =
        watch.copy(groups = watch.groups.filterNot { People.key(it) == People.key(name) }.toSet())

    /** Everyone watched, Family first then the rest, each by name (the Family list's spelling wins). */
    fun everyone(lists: PeopleLists, watch: RequestWatch): List<String> {
        val family = lists.family.sortedBy { it.lowercase() }
        val keys = family.map(People::key).toSet()
        return family + watch.people.filter { People.key(it) !in keys }.sortedBy { it.lowercase() }
    }

    /**
     * The line under the section's title: "Reading requests from Ada and Wife · all day", "… Ada, Wife and 2 more",
     * "Add someone to watch for requests", or what's missing first ("Needs notification access", "Needs MEKA's AI").
     */
    fun statusLine(lists: PeopleLists, watch: RequestWatch, listening: Boolean, aiOn: Boolean = true): String {
        val names = everyone(lists, watch)
        return when {
            names.isEmpty() -> "Add someone to watch for requests"
            !listening -> "Needs notification access to read requests"
            !aiOn -> "Voice notes only until MEKA's AI is on"
            else -> "Reading requests from ${joinNames(names)} · all day"
        }
    }

    /** "1:1 chats only" or "1:1 chats and Family, Football dads". */
    fun groupsLine(watch: RequestWatch): String =
        if (watch.groups.isEmpty()) "1:1 chats only"
        else "1:1 chats and " + watch.groups.sortedBy { it.lowercase() }.joinToString(", ")

    /**
     * Which of one notification's [items] to read for requests: the ones [MessageRequestRules.shouldRead] allows, not
     * already in [seen], from the last [MAX_AGE_MS] before [nowMs], the newest [MAX_PER_NOTIFICATION] in time order.
     */
    fun toRead(items: List<CapturedItem>, lists: PeopleLists, watch: RequestWatch, seen: Set<String>, nowMs: Long): List<CapturedItem> =
        items.asSequence()
            .filter { it.id !in seen && it.atMs >= nowMs - MAX_AGE_MS }
            .filter { MessageRequestRules.shouldRead(it, lists, watch.people, watch.groups) }
            .distinctBy { it.id }
            .sortedBy { it.atMs }
            .toList()
            .takeLast(MAX_PER_NOTIFICATION)

    /** [seen] (id → when it was read) without the ones older than [SEEN_RETENTION_MS]. */
    fun pruneSeen(seen: Map<String, Long>, nowMs: Long): Map<String, Long> =
        seen.filterValues { it >= nowMs - SEEN_RETENTION_MS }

    private fun joinNames(names: List<String>): String = when {
        names.size == 1 -> names[0]
        names.size == 2 -> "${names[0]} and ${names[1]}"
        names.size == 3 -> "${names[0]}, ${names[1]} and ${names[2]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }
}
