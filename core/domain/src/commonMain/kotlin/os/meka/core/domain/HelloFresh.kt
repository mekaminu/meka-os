package os.meka.core.domain

/*
 * The orb says hello like a person, slice 4 (Fix first, Meka 2026-10-20 18:13, point 5): fresh openers Ada writes
 * herself, in the background, so the hello keeps surprising him. Non-AI and pure: these rules decide when to ask, check
 * every line that comes back against the same rules as the hand-written openers, and keep the set on the device.
 *
 * Once a week at most (and one try a day), the device asks the companion's small model for a few openers per part of
 * the day, with "{n}" where his name goes. Nothing about him is sent: no name, no day, no data, only "write openers".
 * Every line is checked here: his name exactly once, the question at the end, short, no digits or symbols, no other
 * names (no capitalised word but Ada's few allowed ones), nothing private (family, money, health, work details), the
 * right part of the day, and not a copy of an opener already in the pools. Lines that pass join that part's plain pool
 * (never the first hello, a quick second tap, the welcome back or the moments, which keep their own), take their turn
 * in the same shuffle and are never said within the last [HelloOpeners.RECENT] like any other.
 */

/** A batch of fresh openers as kept on the device ([HelloFreshRules.KEY]): [gen] numbers the batch, [atMs] when it came. */
data class HelloFreshSet(
    val gen: Int = 0,
    val atMs: Long? = null,
    /** The last time this device asked (answered or not), so a failure doesn't ask again for a day. */
    val triedMs: Long? = null,
    val byPart: Map<HelloPart, List<HelloOpener>> = emptyMap(),
) {
    fun of(part: HelloPart): List<HelloOpener> = byPart[part].orEmpty()
    val size: Int get() = byPart.values.sumOf { it.size }
}

/** One line as the model wrote it, before [HelloFreshRules.check]. */
data class HelloFreshRaw(val part: String, val open: String, val ask: String)

object HelloFreshRules {
    /** The device's own store key. */
    const val KEY = "hello.fresh"
    /** The server meter's feature: in Activity's "Chat" with the companion. */
    const val FEATURE = "chat.hello"
    /** Openers asked for, and kept at most, per part of the day. */
    const val PER_PART = 6
    /** A batch is asked for again after this long… */
    const val REFRESH_MS = 7 * 24 * 60 * 60_000L
    /** …and never more often than this, answered or not. */
    const val RETRY_MS = 20 * 60 * 60_000L

    const val MAX_OPEN = 60
    const val MAX_ASK = 40
    private const val N = "{n}"

    /** The part's word on the wire and in the store. */
    fun wire(part: HelloPart): String = part.name.lowercase()
    fun partOf(s: String): HelloPart? = HelloPart.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }

    /** Whether to ask for a batch now: none yet, or the last one is a week old; never twice within [RETRY_MS]. */
    fun due(set: HelloFreshSet, nowMs: Long): Boolean {
        set.triedMs?.let { if (nowMs - it in 0 until RETRY_MS) return false }
        val at = set.atMs ?: return true
        return set.size == 0 || nowMs - at !in 0 until REFRESH_MS
    }

    /** Capitalised words allowed anywhere (sentence starts are always fine). */
    private val ALLOWED_CAPS = setOf("I", "I'm", "I've", "I'll", "I'd")

    /** Words that would make a hello personal or odd: other people, money, health, work details, Ada naming herself. */
    private val BLOCKED = setOf(
        "wife", "husband", "partner", "kids", "children", "child", "son", "daughter", "boys", "boy", "girls", "family",
        "school", "mum", "dad", "money", "bank", "pay", "paid", "salary", "debt", "bills", "trading", "trade", "health",
        "doctor", "hospital", "sick", "ill", "pain", "weight", "diet", "drink", "drinks", "beer", "wine", "boss",
        "meeting", "meetings", "deadline", "ada", "navilife", "sir", "darling", "love", "babe", "dear", "sexy",
        "weekend", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "tomorrow",
        "yesterday", "rain", "raining", "sun", "sunny", "snow", "cold", "hot", "football", "match", "game",
    )

    /** Part-of-day words and the parts each may be said in. */
    private val PART_WORDS = mapOf(
        "morning" to setOf(HelloPart.MORNING),
        "breakfast" to setOf(HelloPart.MORNING),
        "afternoon" to setOf(HelloPart.AFTERNOON),
        "lunch" to setOf(HelloPart.AFTERNOON),
        "evening" to setOf(HelloPart.EVENING, HelloPart.LATE),
        "tonight" to setOf(HelloPart.EVENING, HelloPart.LATE),
        "dinner" to setOf(HelloPart.EVENING),
        "night" to setOf(HelloPart.EVENING, HelloPart.LATE),
        "late" to setOf(HelloPart.LATE),
        "midnight" to setOf(HelloPart.LATE),
        "bed" to setOf(HelloPart.LATE),
        "bedtime" to setOf(HelloPart.LATE),
    )

    private fun allowedChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in " ,.?!'’-"

    private fun words(s: String): List<String> =
        s.replace(N, " ").split(' ', ',', '.', '?', '!', '-').map { it.trim('\'', '’') }.filter { it.isNotEmpty() }

    /** Letters only, lower case, without the name: two lines that differ only in punctuation are the same line. */
    fun key(open: String, ask: String): String = (open.replace(N, "") + ask).lowercase().filter { it in 'a'..'z' }

    /**
     * Why [r] can't be said in [part] (null when it can): every rule a hand-written opener keeps, and a few more because
     * a model wrote it.
     */
    fun problem(open: String, ask: String, part: HelloPart): String? {
        val o = open.trim()
        val a = ask.trim()
        if (o.length !in 6..MAX_OPEN || a.length !in 6..MAX_ASK) return "length"
        if (o.split(N).size != 2 || N in a) return "name"
        val at = o.indexOf(N)
        if (at == 0 || o[at - 1] != ' ') return "name"
        if (!a.endsWith("?")) return "ask"
        if (o.last() !in ".?!") return "open"
        if (!(o.replace(N, "") + a).all(::allowedChar)) return "chars"
        if ("  " in o || "  " in a || "!!" in o || "??" in o + a) return "chars"
        // No other names: a capital only at a sentence's start (or "I").
        listOf(o, a).forEach { s ->
            var start = true
            s.replace(N, "x").split(' ').filter { it.isNotEmpty() }.forEach { w ->
                val bare = w.trim(',', '.', '?', '!', '\'', '’')
                if (!start && bare.firstOrNull()?.isUpperCase() == true && bare !in ALLOWED_CAPS) return "capital"
                start = w.last() in ".?!"
            }
        }
        val ws = words(o + " " + a).map { it.lowercase() }
        if (ws.any { it in BLOCKED }) return "blocked"
        if (ws.any { w -> PART_WORDS[w]?.let { part !in it } == true }) return "part"
        return null
    }

    /**
     * The model's lines checked: the ones that pass, per part, at most [PER_PART] each, none a copy of a hand-written
     * opener ([existing]) or of each other, with ids "x<part><gen><i>" (a–z, 0–9, never one a hand-written opener has).
     */
    fun check(raw: List<HelloFreshRaw>, gen: Int, existing: List<HelloOpener> = HelloOpeners.ALL): Map<HelloPart, List<HelloOpener>> {
        val seen = existing.map { key(it.open, it.ask) }.toMutableSet()
        val seenOpen = existing.map { key(it.open, "") }.toMutableSet()
        val out = mutableMapOf<HelloPart, MutableList<HelloOpener>>()
        val g = gen.mod(1296).toString(36)
        raw.forEach { r ->
            val part = partOf(r.part) ?: return@forEach
            val open = r.open.trim()
            val ask = r.ask.trim()
            if (problem(open, ask, part) != null) return@forEach
            val k = key(open, ask)
            val ko = key(open, "")
            if (k in seen || ko in seenOpen) return@forEach
            val list = out.getOrPut(part) { mutableListOf() }
            if (list.size >= PER_PART) return@forEach
            seen += k
            seenOpen += ko
            list += HelloOpener("x${part.name.first().lowercaseChar()}$g${list.size}", open, ask)
        }
        return out
    }

    /** [set] after a batch came back: the checked lines replace the old ones when any passed; [nowMs] the try either way. */
    fun took(set: HelloFreshSet, raw: List<HelloFreshRaw>, nowMs: Long): HelloFreshSet {
        val gen = set.gen + 1
        val checked = check(raw, gen)
        if (checked.values.sumOf { it.size } == 0) return set.copy(triedMs = nowMs)
        return HelloFreshSet(gen, nowMs, nowMs, checked)
    }

    /** As kept: "gen;atMs;triedMs" then one "part|open|ask" line each ("|" and newlines never pass [problem]). */
    fun encode(set: HelloFreshSet): String = buildString {
        append(set.gen).append(';').append(set.atMs ?: "").append(';').append(set.triedMs ?: "")
        HelloPart.entries.forEach { p ->
            set.of(p).forEach { o -> append('\n').append(wire(p)).append('|').append(o.open).append('|').append(o.ask) }
        }
    }

    /** [encode]'s text read back, every line checked again; an empty set when missing or unreadable. */
    fun decode(stored: String?): HelloFreshSet {
        val lines = stored?.split('\n').orEmpty()
        val head = lines.firstOrNull()?.split(';') ?: return HelloFreshSet()
        if (head.size != 3) return HelloFreshSet()
        val gen = head[0].toIntOrNull() ?: return HelloFreshSet()
        val raw = lines.drop(1).mapNotNull { l ->
            val p = l.split('|')
            if (p.size != 3) null else HelloFreshRaw(p[0], p[1], p[2])
        }
        return HelloFreshSet(gen, head[1].toLongOrNull(), head[2].toLongOrNull(), check(raw, gen))
    }
}
