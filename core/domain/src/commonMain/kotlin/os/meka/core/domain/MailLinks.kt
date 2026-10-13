package os.meka.core.domain

/**
 * Inbox autopilot, slice 6c: **linked emails** (Meka, 2026-10-12: each Needs-you card shows its "linked thread/related
 * emails (same thread, same order number, same person+topic)"). Non-AI, pure, unit-tested; no regexes.
 *
 * Two emails on "From your mail" are linked when, in the same account, they are
 *  1. in the same thread (the server's [MailItem.threadKey], a hash of the account and the provider's thread id), or
 *  2. about the same order, booking, ticket or reference number ([orderRef], read from the subject, then the preview), or
 *  3. from the same person about the same thing (the sender's name and the subject without its "Re:"/"Fwd:").
 * Links chain (A–B by thread, B–C by order: one group). Each group is one row on the card: the newest email, with the
 * rest under it ("+ 2 linked · same order 112-4455"); Done, Snooze, Task and Waiting for on the row take the whole
 * group with them (one Undo brings them all back); Reply answers the newest and archives the rest with it.
 */
data class MailLinkedRow(
    val id: String,
    val from: String,
    val subject: String,
    /** "Personal · yesterday 18:40". */
    val line: String,
)

object MailLinkRules {
    /** Words after which a number is an order, booking, ticket or reference. */
    private val KEYWORDS = setOf(
        "order", "booking", "reference", "ref", "invoice", "ticket", "case", "reservation", "confirmation", "tracking",
        "parcel", "shipment", "claim", "policy", "account", "application",
    )
    /** Little words allowed between the keyword and the number ("Order no. 123", "booking ref: AB12CD"). */
    private val FILLERS = setOf("no", "number", "num", "nr", "id", "ref", "reference", "#", ":", "-", "is", "code")
    const val MIN_DIGITS = 4
    const val MAX_REF = 24

    /** The reference number in [text] ("Your order 112-4455 has shipped" → "112-4455"), upper-cased, or null. */
    fun orderRef(text: String?): String? {
        val s = text ?: return null
        val tokens = tokens(s)
        for ((i, t) in tokens.withIndex()) {
            // "#12345" on its own counts.
            if (t.startsWith("#")) refOf(t.drop(1))?.let { return it }
            if (t.lowercase().trimEnd(':', '.') !in KEYWORDS) continue
            var j = i + 1
            while (j < tokens.size && j <= i + 3) {
                val n = tokens[j]
                val bare = n.trimStart('#')
                refOf(bare)?.let { return it }
                if (n.lowercase().trimEnd(':', '.') !in FILLERS && n.lowercase().trimEnd(':', '.') !in KEYWORDS) break
                j++
            }
        }
        return null
    }

    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
        for (c in s) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '#' || c == '/' -> cur.append(c)
                c == ':' || c == '.' -> { cur.append(c); flush() }
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** A token that is a reference: letters, digits, '-' or '/', at least [MIN_DIGITS] digits, not a date or a year. */
    private fun refOf(raw: String): String? {
        val t = raw.trimEnd(':', '.', ',', '-', '/').trimStart('-', '/')
        if (t.length !in MIN_DIGITS..MAX_REF) return null
        if (!t.all { it.isLetterOrDigit() || it == '-' || it == '/' }) return null
        val digits = t.count { it.isDigit() }
        if (digits < MIN_DIGITS) return null
        // "12/10/2026" and "2026-10-12" are dates, "2026" alone a year.
        val parts = t.split('-', '/')
        if (parts.size == 3 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.length <= 4 }) return null
        if (t.length == 4 && t.all { it.isDigit() } && t.startsWith("20")) return null
        return t.uppercase()
    }

    /** "Re: Fwd: The invoice!" → "the invoice": what the email is about, for "same person, same topic". */
    fun topic(i: MailItem): String? {
        val t = MailNeedsRules.taskTitle(i)
        if (t == "Email from ${i.from}") return null
        val words = t.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }
        return words.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** Why two emails are linked, strongest first; null when they aren't. */
    enum class Why { THREAD, ORDER, TOPIC }

    fun why(a: MailItem, b: MailItem): Why? {
        if (a.account != b.account) return null
        if (a.threadKey != null && a.threadKey == b.threadKey) return Why.THREAD
        val ra = ref(a)
        if (ra != null && ra == ref(b)) return Why.ORDER
        val ta = topic(a)
        if (ta != null && a.from.equals(b.from, ignoreCase = true) && ta == topic(b)) return Why.TOPIC
        return null
    }

    private fun ref(i: MailItem): String? = orderRef(i.subject) ?: orderRef(i.preview)

    /**
     * [items] (already the ones showing, newest first) in groups: each group newest first, the groups in the order of
     * their newest email. An email linked to none is a group of one.
     */
    fun groups(items: List<MailItem>): List<List<MailItem>> {
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val n = parent[y]; parent[y] = r; y = n }; return r }
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (why(items[i], items[j]) != null) {
                val a = find(i); val b = find(j)
                if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
        val byRoot = LinkedHashMap<Int, MutableList<MailItem>>()
        for (i in items.indices) byRoot.getOrPut(find(i)) { ArrayList() } += items[i]
        return byRoot.values.toList()
    }

    /** "+ 2 linked · same order 112-4455", "+ 1 more in this thread", "+ 1 more from Sam Taylor about this". */
    fun linkedLine(group: List<MailItem>): String? {
        if (group.size < 2) return null
        val head = group.first()
        val rest = group.drop(1)
        // Each linked email's strongest link to any other in the group.
        val whys = rest.mapNotNull { r -> group.filter { it !== r }.mapNotNull { why(it, r) }.minOrNull() }.toSet()
        val n = rest.size
        val ref = group.firstNotNullOfOrNull { ref(it) }
        return when {
            whys == setOf(Why.THREAD) -> "+ $n more in this thread"
            whys == setOf(Why.ORDER) && ref != null -> "+ $n linked · same order $ref"
            whys == setOf(Why.TOPIC) -> "+ $n more from ${head.from} about this"
            else -> if (n == 1) "+ 1 linked email" else "+ $n linked emails"
        }
    }

    /** The ids of [id]'s group among the emails showing on the card ([id] first), or just [id]. */
    fun groupIds(items: List<MailItem>, id: String, nowMs: Long): List<String> {
        val showing = MailNeedsRules.showingSorted(items, nowMs)
        val g = groups(showing).firstOrNull { grp -> grp.any { it.id == id } } ?: return listOf(id)
        return listOf(id) + g.map { it.id }.filter { it != id }
    }

    /** "Done · 3 emails archived at Navilife's next check" for a group, else the one-email line. */
    fun doneLine(n: Int): String = if (n < 2) MailNeedsRules.DONE_LINE else "Done · $n emails archived at Navilife's next check"
    fun snoozedLine(n: Int): String = if (n < 2) MailNeedsRules.SNOOZED_LINE else "$n emails snoozed till tomorrow 08:00"
    fun taskLine(n: Int): String = if (n < 2) MailNeedsRules.TASK_LINE else "Task added · $n emails archived at Navilife's next check"
    fun waitingLine(n: Int): String = if (n < 2) MailNeedsRules.WAITING_LINE else "In Waiting for · chase in 3 days · $n emails archived at Navilife's next check"
}
