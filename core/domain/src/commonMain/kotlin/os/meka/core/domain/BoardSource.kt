package os.meka.core.domain

/**
 * Where a Board card came from (Week board by hand, slice 5; Meka 2026-10-21 19:00: "a colour dot for where it came
 * from (mail, Needs you, Discord, school)"). [tone] is a calendar tone (1–5, never the amber of "carried" or the gold of
 * goals) so the dot reads the same on the Fold and the Mac in Dark and Light.
 */
enum class CardSource(val label: String, val tone: Int) {
    MAIL("From your mail", 4),
    NEEDS("From Needs you", 1),
    DISCORD("From Discord", 2),
    SCHOOL("From School", 5),
}

/**
 * Non-AI and pure: a task's source, read from what it already carries, so tasks made before this slice get their dot
 * too and nothing new is synced for the old ones:
 * - Discord ideas carry the provenance `discord`;
 * - an email's task has the id `tm…` ([MailNeedsRules.taskId]) and a bill, return or renewal from mail `meka:mail`;
 * - a school ask from mail has `st…` ([SchoolMailRules.taskId]) and a school date sent to a day `tbs…`
 *   ([BoardFeedRules.dateTaskId]);
 * - a task made from a request card in Needs you carries [NEEDS] (set from this slice on).
 * Random task ids are lowercase hex, so those prefixes (starting with `t` or `s`) can't be chance. Anything he typed
 * himself has no dot.
 */
object BoardSourceRules {
    /** The provenance a task made from a Needs you request card carries. */
    const val NEEDS = "meka:needs"
    const val DISCORD = "discord"
    const val MAIL = "meka:mail"

    fun of(task: Task): CardSource? = of(task.id, task.source)

    fun of(id: String, provenance: String?): CardSource? = when {
        provenance == DISCORD -> CardSource.DISCORD
        provenance == NEEDS -> CardSource.NEEDS
        provenance == MAIL || id.startsWith("tm") -> CardSource.MAIL
        id.startsWith("st") || id.startsWith("tbs") -> CardSource.SCHOOL
        else -> null
    }

    /** Screen readers: "from your mail". */
    fun spoken(source: CardSource): String = source.label.replaceFirstChar { it.lowercase() }
}
