package os.meka.core.domain

/**
 * The Vault, slice 3: Ask and Talk know the documents. "When does Alex's passport run out?", "is my driving licence
 * still valid?", "do I have a copy of my passport?", "what's in my Vault?", "which documents need renewing?" are
 * answered on this device from the Vault by these rules, with Open the Vault under the answer. Never sent to the AI
 * (document names and dates stay in his own data, ADR-006 addendum 2026-10-18), so it answers offline too.
 *
 * A question names a document kind ([DocumentRules.kindOf]) or the Vault itself, and asks when, whether it's valid,
 * renewing or a copy. "How do I …" questions are the help's. Insurance, warranties, receipts and other documents
 * are answered here only when the Vault holds one that matches (else they're the renewals radar's, through the AI);
 * a passport, licence, ID card, health card or railcard the Vault doesn't hold gets "not in your Vault yet".
 */
object VaultAskRules {
    /** At most this many documents are named in one answer. */
    const val SHOWN = 4
    const val BUTTON = "Open the Vault"
    const val EMPTY = "Your Vault is empty. Add a document with the day it runs out, like \"Passport 20 May 2027\", and Navilife reminds you in time to renew."

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private val polite = Regex(
        """^(?:(?:hey|ok|okay|oh|um|erm|right|so)[,\s]+)*(?:(?:meka|navilife|navi life|navy life)[,\s]+)?(?:(?:can|could|would|will) you\s+)?(?:please\s+)?(?:(?:tell|remind)\s+me\s+)?""", opt,
    )
    /** The help's questions, not his documents'. */
    private val howTo = Regex("""^(?:how\s+(?:do|can|should|would)\s+(?:i|you|we)|how\s+to|where\s+(?:do|can)\s+i|what\s+is\s+an?\b|what'?s\s+an?\b|why)\b""", opt)
    private val vaultWords = Regex("""\b(?:vault|my\s+documents|our\s+documents|documents\s+(?:do\s+)?(?:i|we)\s+have|which\s+documents|what\s+documents|any\s+documents|documents?\s+(?:need|to)\s+renew)""", opt)
    private val askWords = Regex(
        """\b(?:when|expir\w*|runs?\s+out|running\s+out|ran\s+out|valid|in\s+date|out\s+of\s+date|renew\w*|due|date|copy|copies|still\s+good|how\s+long|(?:do|did)\s+(?:i|we)\s+(?:have|keep))\b""", opt,
    )
    /** The TV licence is a bill (the renewals radar's), not a driving licence. */
    private val tvLicence = Regex("""\b(?:tv|television)\s+licen[cs]e\b""", opt)
    private val copyWords = Regex("""\b(?:copy|copies|photo|scan|scanned|pdf|picture)\b""", opt)
    private val renewWords = Regex("""\b(?:renew\w*|expir\w*\s+soon|running\s+out|run\s+out\s+soon|to\s+renew|soon)\b""", opt)

    /** Kinds answered even when the Vault holds none ("there's no passport in your Vault yet"). */
    private val OWN = setOf(DocKind.PASSPORT, DocKind.DRIVING_LICENCE, DocKind.ID_CARD, DocKind.HEALTH_CARD, DocKind.RAILCARD)

    /** Words that aren't a name in "when does Alex's passport run out?". */
    private val STOP = setOf(
        "when", "does", "do", "did", "is", "are", "was", "will", "my", "our", "the", "a", "an", "his", "her", "their", "its",
        "i", "we", "me", "you", "it", "of", "for", "to", "in", "on", "out", "run", "runs", "running", "ran", "expire", "expires",
        "expiry", "expiring", "expired", "valid", "still", "date", "renew", "renewed", "renewing", "renewal", "due", "what",
        "what's", "whats", "which", "how", "long", "left", "have", "has", "got", "copy", "copies", "photo", "scan", "pdf",
        "picture", "kept", "keep", "saved", "any", "there", "a", "need", "needs", "good", "until", "till", "by", "end", "ends",
        "in", "date", "and", "or", "kids", "boys", "children", "everyone's", "everyones", "all", "vault", "documents", "document",
        "passport", "passports", "licence", "license", "driving", "photocard", "ghic", "ehic", "railcard", "oyster", "insurance",
        "policy", "cover", "warranty", "guarantee", "certificate", "cert", "receipt", "receipts", "id", "identity", "card",
        "health", "be", "can", "check", "please", "now", "yet", "soon", "this", "that", "year", "month", "next",
    )
    private val ALL_OF = Regex("""\b(?:kids|boys|children|everyone'?s|all|our)\b""", opt)

    private fun clean(question: String): String? {
        var q = question.replace('’', '\'').replace(space, " ").trim()
        if (q.isEmpty() || q.length > 200) return null
        q = q.substring(polite.find(q)?.value?.length ?: 0).trim()
        return q.trimEnd('?', '.', '!').trim().ifEmpty { null }
    }

    /** What a question asks of the Vault. */
    data class Request(
        val kind: DocKind?, val names: List<String>, val copy: Boolean, val renew: Boolean, val all: Boolean,
        /** Names said as someone's ("Alex's passport"): no match for one of them is "no passport for Alex". */
        val owners: List<String> = emptyList(),
    )

    /** What [question] asks of the Vault; null when it isn't about his documents (the help's, the AI's). */
    fun request(question: String): Request? {
        val q = clean(question) ?: return null
        if (howTo.containsMatchIn(q) || tvLicence.containsMatchIn(q)) return null
        val kind = DocumentRules.kindOf(q).takeIf { it != DocKind.OTHER }
        val vault = vaultWords.containsMatchIn(q)
        if (kind == null && !vault) return null
        if (!vault && !askWords.containsMatchIn(q)) return null
        val raw = q.lowercase().split(' ', ',').map { it.trim { c -> !c.isLetterOrDigit() && c != '\'' } }
        fun bare(w: String) = w.removeSuffix("'s").removeSuffix("s'").trim('\'')
        fun name(w: String) = w.length >= 2 && w !in STOP && w.none(Char::isDigit)
        val names = raw.map(::bare).filter(::name).distinct()
        val owners = raw.filter { it.endsWith("'s") || it.endsWith("s'") }.map(::bare).filter(::name).distinct()
        return Request(kind, names, copyWords.containsMatchIn(q), renewWords.containsMatchIn(q), ALL_OF.containsMatchIn(q), owners)
    }

    private fun words(title: String): Set<String> =
        title.lowercase().split(' ').map { it.trim { c -> !c.isLetterOrDigit() }.removeSuffix("s") }.filter { it.isNotEmpty() }.toSet()

    /** One document in words: "Alex passport expires Thu 20 May 2027, in 7 months. Navilife reminds you from …". */
    fun docLine(d: DocumentItem, today: Long, copy: Boolean, cal: LocalCalendar, device: String?): String {
        val e = d.expiresDay
        val head = when {
            d.receipt != null -> "${d.title}: " + VaultReceiptRules.status(d, today).replaceFirstChar { it.lowercase() } + "."
            e == null -> "${d.title} doesn't run out."
            e < today -> "${d.title} expired ${DocumentRules.dateLabel(e)}, ${DocumentRules.spanWords(today - e)} ago: renew it."
            e == today -> "${d.title} expires today: renew it."
            DocumentRules.due(d, today) -> "${d.title} expires ${DocumentRules.dateLabel(e)}, in ${DocumentRules.spanWords(e - today)}: it's time to renew it."
            else -> "${d.title} expires ${DocumentRules.dateLabel(e)}, in ${DocumentRules.spanWords(e - today)}. " +
                "Navilife reminds you from ${DocumentRules.dateLabel(e - d.kind.leadDays)}."
        }
        if (!copy) return head
        val c = d.file?.let { "A copy is kept: " + VaultFileRules.copyLine(it, cal, device).let { l -> if (l.startsWith("PDF")) l else l.replaceFirstChar { ch -> ch.lowercase() } } + "." }
            ?: "No copy kept yet: open it in the Vault and Add a copy."
        return "$head $c"
    }

    private fun more(n: Int): String = if (n <= 0) "" else " And ${if (n == 1) "1 more" else "$n more"} in the Vault."

    private fun kindName(k: DocKind): String = when (k) {
        DocKind.DRIVING_LICENCE -> "driving licence"
        DocKind.ID_CARD -> "ID card"
        DocKind.HEALTH_CARD -> "health card"
        else -> k.label.lowercase()
    }

    /**
     * The answer to [r] from [items]: the words, or null when the Vault has nothing to say (an insurance question with
     * no insurance in the Vault goes on to the AI and the renewals radar).
     */
    fun answer(r: Request, items: List<DocumentItem>, today: Long, cal: LocalCalendar, device: String?): String? {
        val k = r.kind
        if (k == null) {
            // "What's in my Vault?", "which documents need renewing?"
            if (items.isEmpty()) return EMPTY
            val v = DocumentRules.view(items, today, cal, device)
            val due = items.filter { DocumentRules.due(it, today) }
            if (r.renew) {
                if (due.isEmpty()) {
                    val next = items.filter { (it.expiresDay ?: Long.MIN_VALUE) >= today }.minByOrNull { it.expiresDay!! }
                    return "Nothing in your Vault needs renewing yet." +
                        (next?.let { " Next to run out: ${it.title}, ${DocumentRules.dateLabel(it.expiresDay!!)}." } ?: "")
                }
                return due.take(SHOWN).joinToString(" ") { docLine(it, today, r.copy, cal, device) } + more(due.size - SHOWN)
            }
            val head = "Your Vault holds " + v.summary.replace(" · ", ", ") + "."
            val shown = (due + items.filterNot { DocumentRules.due(it, today) }).take(SHOWN)
            return head + " " + shown.joinToString(" ") { docLine(it, today, r.copy, cal, device) } + more(items.size - SHOWN)
        }
        val ofKind = items.filter { it.kind == k || (k == DocKind.WARRANTY && it.kind == DocKind.RECEIPT) }
        if (ofKind.isEmpty()) {
            if (k !in OWN) return null
            return "There's no ${kindName(k)} in your Vault yet. Add it with the day it runs out, like \"${k.label} 20 May 2027\", and Navilife reminds you in time to renew."
        }
        val named = if (r.names.isEmpty() || r.all) ofKind else ofKind.filter { d ->
            val t = words(d.title)
            r.names.any { n -> n.removeSuffix("s") in t }
        }
        if (named.isEmpty() && r.owners.isEmpty()) {
            // "Is my passport still valid for Spain?": the words weren't a name, so every one of that kind.
            return ofKind.take(SHOWN).joinToString(" ") { docLine(it, today, r.copy, cal, device) } + more(ofKind.size - SHOWN)
        }
        if (named.isEmpty()) {
            if (k !in OWN) return null
            val who = r.owners.joinToString(" and ") { it.replaceFirstChar(Char::uppercase) }
            return "There's no ${kindName(k)} for $who in your Vault. It holds: " + ofKind.take(SHOWN).joinToString(", ") { it.title } + "."
        }
        return named.take(SHOWN).joinToString(" ") { docLine(it, today, r.copy, cal, device) } + more(named.size - SHOWN)
    }

    /** Open the Vault under the answer (the help's Take me there, to the Vault on either app). */
    val link: HelpLink get() = HelpLink("vault", HelpPlace.VAULT, BUTTON)
}
