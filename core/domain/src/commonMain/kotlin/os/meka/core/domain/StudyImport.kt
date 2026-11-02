package os.meka.core.domain

/**
 * One-tap course import (build plan, deferred Study item, 2026-11-02). A course outline arrives as a link
 * `navilife://study/import?v=1&d=<base64url of gzip(JSON)>` (or the same JSON shared as text) with the shape
 * `{title, modules:[{title, lessons:[title]}]}`. Read on the device, never sent anywhere; the app asks
 * "Add <course> · 10 modules · 38 lessons?" and only Add writes it, through the same rules as a pasted outline
 * (a module named like "Module 1: Foundations" becomes "1 · Foundations", "45 min" at the end of a lesson is its length).
 */
data class StudyImportCourse(val title: String, val entries: List<StudyEntry>, val modules: Int)

/** What reading an import gave: the course, or the plain line saying why not. */
data class StudyImportRead(val course: StudyImportCourse?, val line: String)

/** The question the apps show before anything is written; [ok] false shows [line] alone with Close. */
data class StudyImportOffer(
    val ok: Boolean,
    val line: String,
    val title: String,
    val modules: Int,
    val lessons: Int,
    /** A course of the same name is already in Study (the question says so; Add still adds a second). */
    val again: Boolean,
    val spoken: String,
)

object StudyImportRules {
    /** Links and shared text over this are refused before anything is decoded. */
    const val MAX_INPUT_CHARS = 32 * 1024
    /** The unpacked JSON is never allowed past this (a small link can't unpack into something huge). */
    const val MAX_JSON = 64 * 1024
    const val MAX_MODULES = 60
    const val ADD = "Add"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val LABEL = "ADD A COURSE"
    const val NOTE = "From a course link · read on this device"
    const val TOO_BIG = "That course link is too big to read (over 32 KB)."
    const val NOT_READ = "That link isn't a course Navilife can read."
    const val NO_TITLE = "That course has no name."
    const val NO_LESSONS = "That course has no lessons in it."
    const val TOO_MANY = "That course has more than ${StudyRules.MAX_PASTE} lessons, more than one course can hold."
    const val VERSION = "That course link is from a newer Navilife. Update the app, then open it again."

    private val PREFIXES = listOf("navilife://study/import", "mekaos://study/import")

    /** A course link or the course's JSON, as opposed to any other shared text. */
    fun isImport(text: String): Boolean {
        val t = text.trim()
        if (PREFIXES.any { t.startsWith(it, ignoreCase = true) }) return true
        return t.startsWith("{") && t.endsWith("}") && t.contains("\"modules\"")
    }

    /** Reads a link or the JSON itself; nothing is written. */
    fun read(text: String): StudyImportRead {
        val t = text.trim()
        if (t.length > MAX_INPUT_CHARS) return refused(TOO_BIG)
        val json = if (t.startsWith("{")) t else {
            val prefix = PREFIXES.firstOrNull { t.startsWith(it, ignoreCase = true) } ?: return refused(NOT_READ)
            val query = t.substring(prefix.length).substringAfter('?', "").substringBefore('#')
            val params = query.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val v = params["v"]?.toIntOrNull() ?: 1
            if (v > 1) return refused(VERSION)
            val d = params["d"]?.let(::percentDecoded) ?: return refused(NOT_READ)
            val bytes = Base64Url.decode(d) ?: return refused(NOT_READ)
            val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                try { Inflate.gunzip(bytes, MAX_JSON) } catch (e: Inflate.Bad) { return refused(if (e.tooBig) TOO_BIG else NOT_READ) }
            } else bytes
            if (raw.size > MAX_JSON) return refused(TOO_BIG)
            raw.decodeToString()
        }
        if (json.length > MAX_JSON) return refused(TOO_BIG)
        val root = MiniJson.parse(json) as? Map<*, *> ?: return refused(NOT_READ)
        return course(root)
    }

    private fun course(root: Map<*, *>): StudyImportRead {
        val title = (root["title"] as? String)?.let { StudyRules.courseTitle(it.lines().first()) } ?: return refused(NO_TITLE)
        val modules = root["modules"] as? List<*> ?: return refused(NOT_READ)
        if (modules.size > MAX_MODULES) return refused(TOO_MANY)
        val entries = mutableListOf<StudyEntry>()
        val names = mutableListOf<String>()
        for ((i, m) in modules.withIndex()) {
            val mod = m as? Map<*, *> ?: return refused(NOT_READ)
            val given = (mod["title"] as? String).orEmpty().trim()
            val name = given.takeIf { it.isNotEmpty() }?.let { StudyRules.moduleOf(it) ?: StudyRules.clean(it, StudyRules.MAX_MODULE) }
                ?.takeIf { n -> n.any { it.isLetterOrDigit() } } ?: "Module ${i + 1}"
            val lessons = mod["lessons"] as? List<*> ?: return refused(NOT_READ)
            var any = false
            for (l in lessons) {
                val (words, typed) = when (l) {
                    is String -> l to null
                    is Map<*, *> -> (l["title"] as? String ?: return refused(NOT_READ)) to (l["mins"] as? Double)?.toInt()
                    else -> return refused(NOT_READ)
                }
                val (rest, said) = StudyRules.lengthOf(words.replace('\n', ' ').trim())
                val lesson = StudyRules.clean(rest, StudyRules.MAX_LESSON)
                if (lesson.none { it.isLetterOrDigit() }) continue
                val mins = (typed ?: said)?.takeIf { it in 1..StudyRules.MAX_MINS }
                entries += StudyEntry(name, lesson, mins)
                any = true
                if (entries.size > StudyRules.MAX_PASTE) return refused(TOO_MANY)
            }
            if (any && name.lowercase() !in names.map { it.lowercase() }) names += name
        }
        if (entries.isEmpty()) return refused(NO_LESSONS)
        return StudyImportRead(StudyImportCourse(title, entries, names.size), "")
    }

    /** "Add Data analytics · 10 modules · 38 lessons?"; [existing] are the courses' names already in Study. */
    fun offer(read: StudyImportRead, existing: List<String>): StudyImportOffer {
        val c = read.course ?: return StudyImportOffer(false, read.line, "", 0, 0, again = false, spoken = read.line)
        val again = existing.any { it.equals(c.title, ignoreCase = true) }
        val mods = if (c.modules == 1) "1 module" else "${c.modules} modules"
        val lessons = if (c.entries.size == 1) "1 lesson" else "${c.entries.size} lessons"
        val line = if (again) "You already have ${c.title} · add it again with $mods · $lessons?" else "Add ${c.title} · $mods · $lessons?"
        val spoken = if (again) "You already have ${c.title}. Add it again, with $mods and $lessons?" else "Add ${c.title}, with $mods and $lessons?"
        return StudyImportOffer(true, line, c.title, c.modules, c.entries.size, again, spoken)
    }

    /** The link for [course] (tests, and the page Claude makes): `navilife://study/import?v=1&d=` + base64url(JSON). */
    fun link(title: String, modules: List<Pair<String, List<String>>>): String {
        val json = buildString {
            append("{\"title\":").append(MiniJson.quote(title)).append(",\"modules\":[")
            modules.forEachIndexed { i, (m, ls) ->
                if (i > 0) append(',')
                append("{\"title\":").append(MiniJson.quote(m)).append(",\"lessons\":[")
                append(ls.joinToString(",") { MiniJson.quote(it) }).append("]}")
            }
            append("]}")
        }
        return "navilife://study/import?v=1&d=" + Base64Url.encode(json.encodeToByteArray())
    }

    private fun refused(line: String) = StudyImportRead(null, line)

    private fun percentDecoded(s: String): String? {
        if ('%' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 3 <= s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.append(code.toChar()); i += 3
            } else { out.append(ch); i++ }
        }
        return out.toString()
    }
}

/** Base64 with the URL alphabet (the standard one's + and / read too); padding and spaces ignored. */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decode(s: String): ByteArray? {
        val out = ArrayList<Byte>(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in s) {
            val v = when (c) {
                '+' -> 62
                '/' -> 63
                '=', ' ', '\n', '\r', '\t' -> continue
                else -> ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out += ((buf shr bits) and 0xff).toByte() }
        }
        return out.toByteArray()
    }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            var chunk = 0
            for (k in 0 until 3) chunk = (chunk shl 8) or (if (k < n) b[i + k].toInt() and 0xff else 0)
            for (k in 0..n) sb.append(ALPHABET[(chunk shr (18 - 6 * k)) and 0x3f])
            i += 3
        }
        return sb.toString()
    }
}

/** A small DEFLATE / gzip reader (RFC 1951/1952, after zlib's puff), so the link reads the same on the Fold and the Mac. */
internal object Inflate {
    class Bad(val tooBig: Boolean = false) : Exception()

    private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private fun u8(b: ByteArray, i: Int): Int = if (i < b.size) b[i].toInt() and 0xff else throw Bad()

    fun gunzip(b: ByteArray, max: Int): ByteArray {
        if (b.size < 18 || u8(b, 0) != 0x1f || u8(b, 1) != 0x8b || u8(b, 2) != 8) throw Bad()
        val flg = u8(b, 3)
        var p = 10
        if (flg and 4 != 0) p += 2 + (u8(b, p) or (u8(b, p + 1) shl 8))
        if (flg and 8 != 0) { while (u8(b, p) != 0) p++; p++ }
        if (flg and 16 != 0) { while (u8(b, p) != 0) p++; p++ }
        if (flg and 2 != 0) p += 2
        val bits = Bits(b, p)
        val out = inflate(bits, max)
        val end = bits.pos
        if (end + 8 > b.size) throw Bad()
        val crc = (u8(b, end) or (u8(b, end + 1) shl 8) or (u8(b, end + 2) shl 16) or (u8(b, end + 3) shl 24))
        if (crc != crc32(out)) throw Bad()
        return out
    }

    fun crc32(b: ByteArray): Int {
        var c = -1
        for (x in b) {
            c = c xor (x.toInt() and 0xff)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        }
        return c.inv()
    }

    private class Bits(val b: ByteArray, var pos: Int) {
        private var buf = 0
        private var cnt = 0
        fun bit(): Int {
            if (cnt == 0) { buf = u8(b, pos++); cnt = 8 }
            val r = buf and 1
            buf = buf shr 1; cnt--
            return r
        }
        fun bits(n: Int): Int { var v = 0; for (i in 0 until n) v = v or (bit() shl i); return v }
        fun align() { cnt = 0 }
    }

    private class Huff(lengths: IntArray, n: Int) {
        val count = IntArray(16)
        val symbol = IntArray(n)
        init {
            for (i in 0 until n) count[lengths[i]]++
            count[0] = 0
            val offs = IntArray(16)
            for (l in 1 until 15) offs[l + 1] = offs[l] + count[l]
            for (s in 0 until n) if (lengths[s] != 0) symbol[offs[lengths[s]]++] = s
        }
    }

    private fun decode(s: Bits, h: Huff): Int {
        var code = 0; var first = 0; var index = 0
        for (len in 1..15) {
            code = code or s.bit()
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count; first += count
            first = first shl 1; code = code shl 1
        }
        throw Bad()
    }

    private class Out(val max: Int) {
        var data = ByteArray(1024)
        var size = 0
        fun put(x: Int) {
            if (size >= max) throw Bad(tooBig = true)
            if (size == data.size) data = data.copyOf(minOf(max, data.size * 2))
            data[size++] = x.toByte()
        }
    }

    private val FIXED_L: Huff by lazy {
        val l = IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }
        Huff(l, 288)
    }
    private val FIXED_D: Huff by lazy { Huff(IntArray(30) { 5 }, 30) }

    private fun inflate(s: Bits, max: Int): ByteArray {
        val out = Out(max)
        do {
            val last = s.bit()
            when (s.bits(2)) {
                0 -> {
                    s.align()
                    val len = u8(s.b, s.pos) or (u8(s.b, s.pos + 1) shl 8)
                    val nlen = u8(s.b, s.pos + 2) or (u8(s.b, s.pos + 3) shl 8)
                    if (len != (nlen.inv() and 0xffff)) throw Bad()
                    s.pos += 4
                    repeat(len) { out.put(u8(s.b, s.pos++)) }
                }
                1 -> codes(s, out, FIXED_L, FIXED_D)
                2 -> dynamic(s, out)
                else -> throw Bad()
            }
        } while (last == 0)
        return out.data.copyOf(out.size)
    }

    private fun dynamic(s: Bits, out: Out) {
        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > 286 || ndist > 30) throw Bad()
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[ORDER[i]] = s.bits(3)
        val lencode = Huff(lengths, 19)
        val all = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val sym = decode(s, lencode)
            if (sym < 16) { all[i++] = sym; continue }
            val (value, times) = when (sym) {
                16 -> { if (i == 0) throw Bad(); all[i - 1] to 3 + s.bits(2) }
                17 -> 0 to 3 + s.bits(3)
                else -> 0 to 11 + s.bits(7)
            }
            if (i + times > nlen + ndist) throw Bad()
            repeat(times) { all[i++] = value }
        }
        if (all[256] == 0) throw Bad()
        codes(s, out, Huff(all.copyOfRange(0, nlen), nlen), Huff(all.copyOfRange(nlen, nlen + ndist), ndist))
    }

    private fun codes(s: Bits, out: Out, lit: Huff, dist: Huff) {
        while (true) {
            val sym = decode(s, lit)
            when {
                sym < 256 -> out.put(sym)
                sym == 256 -> return
                else -> {
                    val li = sym - 257
                    if (li >= 29) throw Bad()
                    val len = LBASE[li] + s.bits(LEXT[li])
                    val di = decode(s, dist)
                    if (di >= 30) throw Bad()
                    val d = DBASE[di] + s.bits(DEXT[di])
                    if (d > out.size) throw Bad()
                    repeat(len) { out.put(out.data[out.size - d].toInt()) }
                }
            }
        }
    }
}

/** Just enough JSON for a course: objects, arrays, strings, numbers, true/false/null; nesting at most 16 deep. */
internal object MiniJson {
    private class Bad : Exception()

    fun parse(s: String): Any? = try {
        val p = P(s)
        val v = p.value(0)
        p.ws()
        if (p.i != s.length) null else v
    } catch (e: Bad) { null }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(depth: Int): Any? {
            if (depth > 16) throw Bad()
            ws()
            if (i >= s.length) throw Bad()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws(); if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws()
                if (s.getOrNull(i) != ':') throw Bad()
                i++
                m[k] = value(depth + 1); ws()
                when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return m }; else -> throw Bad() }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws(); if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value(depth + 1); ws()
                when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return l }; else -> throw Bad() }
            }
        }
        fun word(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw Bad(); i += w.length; return v }
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw Bad()
        }
        fun string(): String {
            if (s.getOrNull(i) != '"') throw Bad()
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s.getOrNull(i++) ?: throw Bad()
                        when (e) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad()
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw Bad()); i += 4
                            }
                            else -> throw Bad()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
