package os.meka.core.domain

/**
 * What MEKA knows, slice 4: a shared form's fields answered from the facts page (V1). When Meka shares a form's text
 * with "Handle this" ("Vehicle registration: ____", "Name of GP surgery", "Shoe size?"), MEKA lays each field beside
 * the fact that answers it, with Copy, so filling it in is copy and paste.
 *
 * Non-AI, pure and on the device: the facts never leave it for this (not even to MEKA's AI, so a fact kept from the AI
 * answers too: the clipboard is Meka's own). The match is cautious: a field gets a fact only when every telling word
 * of the fact's name is in the field ("Car reg" ↔ "Vehicle registration number", through a few synonyms), or when
 * every telling word of the field is in exactly one fact's name ("Address" ↔ "Home address"). Words like "name",
 * "your" or "please" never tie a field to a fact on their own. The shared text is only read, never stored.
 */
data class FormAnswer(
    /** The field as the form words it, tidied: "Vehicle registration". */
    val field: String,
    /** The fact's name on the page: "Car reg". */
    val label: String,
    /** What Copy puts on the clipboard: the fact alone, "AB12 CDE". */
    val copyText: String,
    /** "Vehicle registration: AB12 CDE, from Car reg". */
    val spoken: String,
    /** The Copy button's spoken label: "Copy Car reg for Vehicle registration". */
    val copySpoken: String,
)

data class FormAnswersView(
    val answers: List<FormAnswer>,
    /** "2 answers from What MEKA knows · Copy, then paste into the form". */
    val line: String,
    /** Copy all: one "Field: fact" line per answer. */
    val copyAllText: String,
)

object FormFillRules {
    /** The block's heading in the capture sheet and the Mac sheet. */
    const val TITLE = "From What Navilife knows"
    const val COPY = "Copy"
    const val COPY_ALL = "Copy all"
    /** Most answers shown for one share. */
    const val MAX_ANSWERS = 8
    /** A line longer than this is a paragraph, not a field. */
    const val MAX_FIELD_LINE = 120
    /** A field's own words (before its colon) longer than this aren't a field's name. */
    const val MAX_FIELD = 60
    /** How much of a fact the copied line shows before "…". */
    const val SHOWN = 40
    /** The Mac's sheet title. */
    const val SHEET_TITLE = "Form answers"

    /** Words that never tie a field to a fact on their own. */
    private val STOP = setOf(
        "my", "our", "your", "the", "a", "an", "of", "for", "is", "and", "or", "to", "in", "on", "at", "by", "with",
        "name", "number", "no", "what", "who", "which", "where", "when", "please", "enter", "write", "give", "provide",
        "full", "current", "detail", "if", "any", "applicable", "optional", "required", "here", "below", "this", "that",
        "do", "you", "have", "are", "it", "be", "us", "we", "i", "me", "per", "e", "g", "eg", "etc",
    )

    /** Different words a form and the page use for the same thing, read as one ("registration" is "reg"). */
    private val SAME = mapOf(
        "registration" to "reg", "vehicle" to "car", "doctor" to "gp", "practice" to "surgery",
        "telephone" to "phone", "tel" to "phone", "mobile" to "phone",
        "insurance" to "insurer", "provider" to "supplier", "dob" to "birthday", "birth" to "birthday",
        "zip" to "postcode", "e-mail" to "email", "dentistry" to "dentist",
    )

    private val BLANKS = Regex("""[_.…]{2,}|\[\s*]|\(\s*\)""")
    private val LEADER = Regex("""^\s*(?:[-*•▪◦>]+|\(?\d{1,2}[.)]|\(?[a-z][.)])\s+""", RegexOption.IGNORE_CASE)

    /** A word as the match sees it: [FactRules.word], then [SAME]. */
    private fun canon(w: String): String = FactRules.word(w).let { SAME[it] ?: it }

    /** The telling words of a name: canonical, without [STOP]. "Post code" reads as "postcode". */
    fun telling(text: String): Set<String> =
        FactRules.words(text.replace(POST_CODE, "postcode")).map { SAME[it] ?: it }
            .filter { it.length > 1 && it !in STOP }.toSet()

    private val POST_CODE = Regex("""\bpost\s+code\b""", RegexOption.IGNORE_CASE)

    /**
     * The form's fields in [text], in order, each once: a line (or a bullet's text) short enough to be one, cut at its
     * first colon or question mark when there is one, blanks ("____", "…", "[ ]") and leading numbers or bullets gone.
     * "1. Vehicle registration: ________" → "Vehicle registration".
     */
    fun fields(text: String): List<String> {
        val seen = mutableSetOf<String>()
        return text.replace("\r\n", "\n").split('\n', '\t', '|').mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.length > MAX_FIELD_LINE) return@mapNotNull null
            var f = line.replace(LEADER, "")
            val cut = listOf(f.indexOf(':'), f.indexOf('?')).filter { it > 0 }.minOrNull()
            if (cut != null) f = f.substring(0, cut)
            f = f.replace(BLANKS, " ").replace(Regex("""\s+"""), " ").trim().trim('-', '–', '*', ',', ';', '.').trim()
            if (f.isEmpty() || f.length > MAX_FIELD || f.none { it.isLetter() }) return@mapNotNull null
            f.takeIf { seen.add(it.lowercase()) }
        }
    }

    /**
     * The fact that answers [field], or null: the fact whose telling words are all in the field (the one with the most
     * such words; a tie is no answer), else the only fact whose name holds all of the field's telling words.
     */
    fun match(field: String, facts: List<LifeFact>): LifeFact? {
        val fw = telling(field)
        if (fw.isEmpty()) return null
        val forward = facts.map { it to telling(it.label) }.filter { (_, lw) -> lw.isNotEmpty() && fw.containsAll(lw) }
        if (forward.isNotEmpty()) {
            val best = forward.maxOf { it.second.size }
            val top = forward.filter { it.second.size == best }
            return top.singleOrNull()?.first
        }
        return facts.filter { telling(it.label).containsAll(fw) }.singleOrNull()
    }

    /**
     * The answers for a shared [text] (and its [subject]): each field with its fact, in the form's order, each fact once,
     * at most [MAX_ANSWERS]. Empty when the page has no facts or nothing in the text matches one.
     */
    fun answers(text: String, subject: String?, facts: List<LifeFact>): FormAnswersView {
        if (facts.isEmpty()) return EMPTY
        val used = mutableSetOf<String>()
        val answers = fields(listOfNotNull(subject, text).joinToString("\n")).mapNotNull { field ->
            val fact = match(field, facts)?.takeIf { used.add(it.id) } ?: return@mapNotNull null
            val value = fact.value.trim()
            FormAnswer(
                field = field,
                label = fact.label,
                copyText = value,
                spoken = "$field: $value, from ${fact.label}",
                copySpoken = "$COPY ${fact.label} for $field",
            )
        }.take(MAX_ANSWERS)
        if (answers.isEmpty()) return EMPTY
        val count = if (answers.size == 1) "1 answer" else "${answers.size} answers"
        return FormAnswersView(answers, "$count from ${FactAskRules.PAGE} · Copy, then paste into the form",
            answers.joinToString("\n") { "${it.field}: ${it.copyText}" })
    }

    /** The line once one answer is copied: "Copied Car reg · AB12 CDE" (a long fact cut at [SHOWN] with "…"). */
    fun copiedLine(answer: FormAnswer): String {
        val v = answer.copyText
        return "Copied ${answer.label} · " + if (v.length > SHOWN) v.take(SHOWN).trimEnd() + "…" else v
    }

    /** The line once all are copied: "Copied 3 answers · paste them where they go". */
    fun copiedAllLine(view: FormAnswersView): String =
        "Copied ${if (view.answers.size == 1) "1 answer" else "${view.answers.size} answers"} · paste them where they go"

    val EMPTY = FormAnswersView(emptyList(), "", "")
}
