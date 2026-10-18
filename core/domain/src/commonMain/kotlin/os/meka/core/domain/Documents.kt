package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * The Vault (V2), slice 1: the household's documents and when they run out, typed once — "Passport expires 3 Mar
 * 2029", "Alex passport 20 May 2027", "Driving licence 12 Jun 2031", "Railcard 05/2027", "Birth certificate Theo" — a row
 * each with how long is left ("Renew now · expires in 4 months", lit), and the expiry put onto the plan: a heads-up at
 * 09:00 when the renewal window opens (six months before a passport, two before a licence…), a month and a week
 * before, and on the day. Plain, non-AI rules; nothing leaves Meka's synced data.
 *
 * MEKA keeps no document numbers: a run of digits that looks like one is left out of the title. The document itself
 * (a photo or PDF, encrypted on the device before it is stored) is a later slice. One synced entity type (ADR-008
 * addendum 2026-10-12): `vault_doc`.
 */
object VaultDocFields {
    /** "Theo passport" (at most [DocumentRules.MAX_TITLE]). */
    const val TITLE = "title"
    /** [DocKind.wire]. */
    const val KIND = "kind"
    /** The last day it is valid (a local epoch day); absent for a document that doesn't run out. */
    const val EXPIRES = "expiresDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
    /** Slice 2b: the document's encrypted copy ([VaultFileRules.encode]); absent or null for none. */
    const val FILE = "file"
}

/**
 * What a document is: its [label], the days before expiry its renewal window opens ([leadDays]) and the one thing
 * worth knowing then ([hint]).
 */
enum class DocKind(val wire: String, val label: String, val leadDays: Int, val hint: String) {
    PASSPORT("passport", "Passport", 183, "many countries want six months left on a passport, and renewing can take weeks"),
    DRIVING_LICENCE("licence", "Driving licence", 60, "the photocard is renewed every 10 years"),
    ID_CARD("id_card", "ID card", 60, "renew it before then"),
    HEALTH_CARD("health_card", "Health card", 30, "a GHIC is free to renew"),
    RAILCARD("railcard", "Railcard", 14, "renew it to keep the discount"),
    INSURANCE("insurance", "Insurance", 30, "compare before it renews"),
    WARRANTY("warranty", "Warranty", 30, "anything to claim, claim before then"),
    CERTIFICATE("certificate", "Certificate", 30, "renew or replace it before then"),
    OTHER("other", "Document", 30, "renew or replace it before then");

    companion object { fun of(wire: String?): DocKind = entries.firstOrNull { it.wire == wire } ?: OTHER }
}

/** One stored document. */
data class DocumentItem(
    val id: String,
    val title: String,
    val kind: DocKind,
    val expiresDay: Long?,
    val addedAtMs: Long,
    /** Slice 2b: its encrypted copy, if one was added. */
    val file: VaultFileRef? = null,
)

/** A row in the Vault: "Theo passport" · "Passport · expires Thu 20 May 2027" · "Renew now · expires in 7 months" ([statusLit]). */
data class DocumentRow(
    val id: String,
    val title: String,
    val line: String,
    val status: String,
    val statusLit: Boolean,
    val spoken: String,
    /** Slice 2b: "Photo · 412 KB · encrypted · added on the Fold, Sun 18 Oct", or null with no copy. */
    val copyLine: String? = null,
    /** The copy is a PDF (opened page by page) rather than a photo. */
    val copyIsPdf: Boolean = false,
) {
    val hasCopy: Boolean get() = copyLine != null
}

data class DocumentsView(
    val rows: List<DocumentRow>,
    /** "3 documents · 1 to renew", "3 documents · next to run out: Railcard, May 2027", or the empty line. */
    val summary: String,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = DocumentsView(emptyList(), DocumentRules.EMPTY_LINE) }
}

/**
 * What adding a line did: the line under the field, and whether nothing was written. [id] is the document written
 * (null when refused) and [updated] whether it was one already there taking a new date (Undo can't remove that).
 */
data class DocumentAdded(val line: String, val refused: Boolean, val id: String? = null, val updated: Boolean = false)

/** A typed document ([DocumentRules.read]). */
data class DocumentEntry(val title: String, val kind: DocKind, val expiresDay: Long?)

object DocumentRules {
    const val MAX_TITLE = 50
    /** How far back an expiry may be typed (an expired passport noted to renew). */
    const val MAX_DAYS_BACK = 3_650L
    /** How far ahead (a 10-year passport, a 15-year licence and a little). */
    const val MAX_DAYS_AHEAD = 5_600L
    /** Heads-ups go at 09:00. */
    const val NOTICE_MIN = 9 * 60
    /** Besides the kind's own window: a month, a week and the day itself. */
    val STAGES = listOf(30, 7, 0)

    const val ADD_HINT = "Add… (Passport expires 3 Mar 2029, Alex passport 20 May 2027, Railcard 05/2027)"
    const val EMPTY_LINE = "No documents yet. Type each one with the date it runs out; Navilife reminds you in time to renew."
    const val NOT_READ = "Navilife couldn't read that. Try \"Passport expires 3 Mar 2029\" or \"Railcard 05/2027\"."
    const val BAD_DATE = "Navilife couldn't read that date. Try \"3 Mar 2029\" or \"05/2029\"."
    const val NOTE = "Names and dates only: Navilife keeps no document numbers. Open a document to add a copy (a photo or PDF), encrypted on this device first."

    private val KIND_WORDS: Map<String, DocKind> = buildMap {
        listOf("passport", "passports").forEach { put(it, DocKind.PASSPORT) }
        listOf("licence", "license", "driving", "photocard").forEach { put(it, DocKind.DRIVING_LICENCE) }
        listOf("ghic", "ehic").forEach { put(it, DocKind.HEALTH_CARD) }
        listOf("railcard", "oyster").forEach { put(it, DocKind.RAILCARD) }
        listOf("insurance", "policy", "cover").forEach { put(it, DocKind.INSURANCE) }
        listOf("warranty", "guarantee").forEach { put(it, DocKind.WARRANTY) }
        listOf("certificate", "cert").forEach { put(it, DocKind.CERTIFICATE) }
    }
    /** Words that only say "it expires": left out of the title. */
    private val EXPIRY_WORDS = setOf(
        "expires", "expire", "expiring", "expiry", "exp", "expired", "valid", "until", "till", "to", "renew", "renews", "renewal",
        "by", "ends", "end", "on", "due", "runs", "out", "date", "-", "–", ":",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun bare(w: String): String = w.trim { !it.isLetterOrDigit() && it != '/' }.lowercase()

    /** The kind any word names ("Theo's passport", "Home insurance", "16-25 Railcard"); "ID card" by its pair. */
    fun kindOf(text: String): DocKind {
        val ws = text.split(' ').map(::bare).filter { it.isNotEmpty() }
        for (i in ws.indices) {
            val w = ws[i].removeSuffix("'s").removeSuffix("’s")
            KIND_WORDS[w]?.let { return it }
            if ((w == "id" || w == "identity") && ws.getOrNull(i + 1) == "card") return DocKind.ID_CARD
            if (w == "health" && ws.getOrNull(i + 1) == "card") return DocKind.HEALTH_CARD
        }
        return DocKind.OTHER
    }

    /** A word that looks like a document number (6+ letters and digits, 4+ of them digits): never kept. */
    fun looksLikeNumber(word: String): Boolean {
        val w = word.trim { !it.isLetterOrDigit() }
        if (w.length < 6 || !w.all { it.isLetterOrDigit() }) return false
        return w.count { it.isDigit() } >= 4
    }

    /** A month's name ("Mar", "March") as 1–12, or null. */
    private fun monthOf(w: String): Int? {
        if (w.length < 3) return null
        val i = MONTHS.indexOf(w.take(3))
        return if (i >= 0 && MONTH_FULL[i].startsWith(w)) i + 1 else null
    }
    /** The whole names: a typed month is a prefix of one ("sept", "march"). */
    private val MONTH_FULL = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    /**
     * A card's month-only expiry, "05/2027", "5/27" (when the second number can't be a month) or "May 2027": its last
     * day, with the words around it. Read by hand, no regex. Null when there is none.
     */
    fun monthExpiry(words: List<String>): Pair<Long, List<String>>? {
        for (i in words.indices) {
            val w = bare(words[i])
            val slash = w.split('/')
            if (slash.size == 2 && slash.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
                val m = slash[0].toInt()
                val y = when (slash[1].length) {
                    4 -> slash[1].toInt()
                    2 -> slash[1].toInt().takeIf { it > 12 }?.plus(2000)
                    else -> null
                }
                if (y != null && m in 1..12) return CivilDate.toEpochDay(y, m, CivilDate.lengthOfMonth(y, m)) to (words.subList(0, i) + words.subList(i + 1, words.size))
            }
            val m = monthOf(w) ?: continue
            val next = words.getOrNull(i + 1)?.let(::bare) ?: continue
            if (next.length != 4 || !next.all { it.isDigit() }) continue
            val prev = words.getOrNull(i - 1)?.let(::bare)?.trimEnd('s', 't', 'n', 'd', 'r', 'h')
            if (prev != null && prev.isNotEmpty() && prev.all { it.isDigit() }) continue // "3 Mar 2029" has a day
            val y = next.toInt()
            return CivilDate.toEpochDay(y, m, CivilDate.lengthOfMonth(y, m)) to (words.subList(0, i) + words.subList(i + 2, words.size))
        }
        return null
    }

    /** What one typed line says. */
    sealed interface Read {
        data class Doc(val entry: DocumentEntry) : Read
        data class Refused(val line: String) : Read
    }

    /**
     * Reads a line typed in the Vault: a date (any of School's forms, "3 Mar 2029", "03/03/2029", or a month,
     * "05/2027", "May 2027") is when it runs out; with none it doesn't ("Birth certificate Theo"). The kind is the
     * document word anywhere in the line; the title is the words left, without "expires" and without anything that
     * looks like a document number.
     */
    fun read(text: String, today: Long): Read {
        val s = SchoolRules.tidy(text)
        if (s.isEmpty()) return Read.Refused(NOT_READ)
        val kept = s.split(' ').filter { it.isNotEmpty() && !looksLikeNumber(it) }
        var expires: Long? = null
        var words = kept
        val month = monthExpiry(kept)
        if (month != null) {
            expires = month.first
            words = month.second
        } else {
            when (val span = SchoolRules.findSpan(kept.joinToString(" "), today, earliest = today - MAX_DAYS_BACK)) {
                SpanFound.Bad -> return Read.Refused(BAD_DATE)
                is SpanFound.Days -> { expires = span.endDay; words = span.rest.split(' ').filter { it.isNotEmpty() } }
                SpanFound.None -> Unit
            }
        }
        if (expires != null && (expires > today + MAX_DAYS_AHEAD || expires < today - MAX_DAYS_BACK)) return Read.Refused(BAD_DATE)
        val titleWords = words.filter { bare(it).let { b -> b.isNotEmpty() && b !in EXPIRY_WORDS } }
        val kind = kindOf(s)
        val title = SchoolRules.cleanTitle(titleWords.joinToString(" ")).take(MAX_TITLE).trim().ifEmpty { kind.label }
        if (expires == null && kind == DocKind.OTHER && titleWords.isEmpty()) return Read.Refused(NOT_READ)
        return Read.Doc(DocumentEntry(title, kind, expires))
    }

    // ---- Stored entities ----

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live documents, soonest to run out first, then those that don't; unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<DocumentItem> = entities.mapNotNull { (id, s) ->
        if (s.f(VaultDocFields.DELETED).boolOrNull == true) return@mapNotNull null
        val title = s.f(VaultDocFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DocumentItem(
            id, title, DocKind.of(s.f(VaultDocFields.KIND).textOrNull), s.f(VaultDocFields.EXPIRES).longOrNull,
            s.f(VaultDocFields.ADDED_AT).longOrNull ?: 0L, VaultFileRules.decode(s.f(VaultDocFields.FILE).textOrNull),
        )
    }.sortedWith(compareBy<DocumentItem> { it.expiresDay ?: Long.MAX_VALUE }.thenBy { it.title.lowercase() }.thenBy { it.id })

    fun fields(e: DocumentEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        VaultDocFields.TITLE to e.title.fv(),
        VaultDocFields.KIND to e.kind.wire.fv(),
        VaultDocFields.EXPIRES to e.expiresDay.fv(),
        VaultDocFields.ADDED_AT to nowMs.fv(),
        VaultDocFields.DELETED to false.fv(),
    )

    // ---- Words ----

    /** "Thu 20 May 2027". */
    fun dateLabel(day: Long): String = CivilDate.shortLabel(day) + " " + CivilDate.fromEpochDay(day).year

    /** "5 days", "3 weeks", "7 months", "2 years" (rounded down; at least 1). */
    fun spanWords(days: Long): String {
        fun p(n: Long, w: String) = if (n == 1L) "1 $w" else "$n ${w}s"
        return when {
            days < 14 -> p(days, "day")
            days < 61 -> p(days / 7, "week")
            days < 730 -> p(days / 30, "month")
            else -> p(days / 365, "year")
        }
    }

    /** The renewal window is open (or it has run out). */
    fun due(d: DocumentItem, today: Long): Boolean = d.expiresDay != null && d.expiresDay - today <= d.kind.leadDays

    /** "No expiry", "Expires in 2 years", "Renew now · expires in 4 months", "Expires today", "Expired 12 days ago". */
    fun status(d: DocumentItem, today: Long): String {
        val e = d.expiresDay ?: return "No expiry"
        val left = e - today
        return when {
            left < 0 -> "Expired ${spanWords(-left)} ago"
            left == 0L -> "Expires today"
            left == 1L -> "Renew now · expires tomorrow"
            due(d, today) -> "Renew now · expires in ${spanWords(left)}"
            else -> "Expires in ${spanWords(left)}"
        }
    }

    /** "Passport · expires Thu 20 May 2027" (just the kind when it doesn't run out; the title alone names it). */
    fun rowLine(d: DocumentItem, today: Long): String {
        val kind = d.kind.label.takeUnless { d.title.equals(it, ignoreCase = true) }
        val whenText = d.expiresDay?.let { (if (it < today) "expired " else "expires ") + dateLabel(it) }
        return listOfNotNull(kind, whenText).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "No expiry" }
    }

    /** The Vault's documents: those to renew (or run out) first, then the rest by when they run out. */
    fun view(items: List<DocumentItem>, today: Long, cal: LocalCalendar = LocalCalendar.UTC, device: String? = null): DocumentsView {
        if (items.isEmpty()) return DocumentsView.EMPTY
        val ordered = items.filter { due(it, today) } + items.filterNot { due(it, today) }
        val rows = ordered.map { d ->
            val line = rowLine(d, today)
            val status = status(d, today)
            val copy = d.file?.let { VaultFileRules.copyLine(it, cal, device) }
            val spoken = "${d.title}. $line. $status" + (copy?.let { ". Copy kept: $it" } ?: "")
            DocumentRow(d.id, d.title, line, status, due(d, today), spoken, copy, d.file?.isPdf == true)
        }
        val count = if (items.size == 1) "1 document" else "${items.size} documents"
        val toRenew = items.count { due(it, today) }
        val next = items.filter { (it.expiresDay ?: Long.MIN_VALUE) >= today }.minByOrNull { it.expiresDay!! }
        val summary = when {
            toRenew > 0 -> "$count · $toRenew to renew"
            next != null -> {
                val y = CivilDate.fromEpochDay(next.expiresDay!!)
                "$count · next to run out: ${next.title}, ${Recurrence.MONTH_SHORT[y.month - 1]} ${y.year}"
            }
            else -> "$count · none runs out"
        }
        return DocumentsView(rows, summary)
    }

    /** The line after adding: "Added Theo passport · expires Thu 20 May 2027 · renew from Fri 20 Nov 2026". */
    fun addedLine(e: DocumentEntry, today: Long, replaced: Boolean): String {
        val verb = if (replaced) "Updated" else "Added"
        val e2 = e.expiresDay ?: return "$verb ${e.title} · no expiry"
        val opens = e2 - e.kind.leadDays
        val tail = when {
            e2 < today -> "it has run out: renew it"
            opens <= today -> "renew it now"
            else -> "Navilife reminds you from ${dateLabel(opens)}"
        }
        return "$verb ${e.title} · expires ${dateLabel(e2)} · $tail"
    }

    /**
     * The heads-ups: at [NOTICE_MIN] on the day each document's renewal window opens ([DocKind.leadDays] before it
     * runs out), then a month and a week before and on the day, each only on its own day — "Theo passport expires
     * Thu 20 May 2027" · "In 6 months · many countries want six months left on a passport…". Opens the Vault.
     */
    fun notices(items: List<DocumentItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.mapNotNull { d ->
            val e = d.expiresDay ?: return@mapNotNull null
            val left = e - today
            val stages = (listOf(d.kind.leadDays) + STAGES).filter { it <= d.kind.leadDays }.distinct()
            if (left !in stages.map { it.toLong() }) return@mapNotNull null
            val title = if (left == 0L) "${d.title} expires today" else "${d.title} expires ${dateLabel(e)}"
            val text = if (left == 0L) "Renew it, or note the new date in the Vault" else "In ${spanWords(left)} · ${d.kind.hint}"
            Notice(
                key = "doc:${d.id}:$e:$left", source = NoticeSource.DOCUMENTS, tier = NoticeTier.HEADS_UP,
                title = title, text = text, atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.VAULT,
                expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
    }
}

/** The Vault's documents on a replica. Every write is an op: offline-first, synced. */
class Documents(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<DocumentItem> = DocumentRules.items(replica.entities(EntityTypes.VAULT_DOC).associate { it.ref.entityId to it.fields })

    fun view(device: String? = null): DocumentsView = DocumentRules.view(items(), calendar.epochDayOf(nowMs()), calendar, device)

    fun item(id: String): DocumentItem? = items().firstOrNull { it.id == id }

    /** Slice 2b: the document's copy is now [ref] (a new or replaced one), or none ([ref] null). False when it's gone. */
    fun setFile(id: String, ref: VaultFileRef?): Boolean {
        item(id) ?: return false
        replica.commitLocal(EntityTypes.VAULT_DOC, id, mapOf(VaultDocFields.FILE to ref?.let(VaultFileRules::encode).fv()))
        return true
    }

    /**
     * Adds what [text] says ([DocumentRules.read]); a document with the same title takes the new date (a renewed
     * passport). Refused (nothing written) when MEKA couldn't read it.
     */
    fun add(text: String): DocumentAdded {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        return when (val r = DocumentRules.read(text, today)) {
            is DocumentRules.Read.Refused -> DocumentAdded(r.line, refused = true)
            is DocumentRules.Read.Doc -> addEntry(r.entry)
        }
    }

    /**
     * Writes [entry] (a typed line read, or a picture of a document, [DocumentShareRules]); a document with the same
     * title takes the new date.
     */
    fun addEntry(entry: DocumentEntry): DocumentAdded {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        val existing = items().firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
        val fields = DocumentRules.fields(entry, now).let { f -> if (existing != null) f - VaultDocFields.ADDED_AT else f }
        val id = existing?.id ?: newId()
        replica.commitLocal(EntityTypes.VAULT_DOC, id, fields)
        return DocumentAdded(DocumentRules.addedLine(entry, today, replaced = existing != null), refused = false, id = id, updated = existing != null)
    }

    /** Removes a document for good; false when it isn't there. */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.VAULT_DOC, id)?.takeIf { it.fields[VaultDocFields.DELETED]?.boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.VAULT_DOC, id, mapOf(VaultDocFields.DELETED to true.fv()))
        return true
    }
}
