package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * Money: his own categories (Meka 2026-10-26 23:18: "News ON The → a Vape category"). From the payment sheet he taps
 * "+ New category", names it, picks one of the money palette's colours and an icon, and the payment's payee moves into
 * it, past and future, with Undo. A category of his is synced (one `money_category` entity, LWW, ADR-008 addendum
 * 2026-10-27) and its payments get their own row and slice on Spending.
 *
 * Under the hood a payee in one of his categories keeps the built-in [MoneyCategory.OTHER] for every older reckoning
 * (tips, forecasts, caps, the year), so totals never change; Spending, the payment rows and the sheet show his own name,
 * icon and colour instead. Its rule is stored as `own:<id>` in the merchant rule's `category`, which an older app reads
 * as no rule at all (the words' own category). Both apps draw from here.
 */
object MoneyOwnFields {
    const val NAME = "name"
    /** One of the ring's tones, 0 until [MoneySpending.TONES]. */
    const val TONE = "tone"
    /** A [MoneyIcon] name from [MoneyOwnCategories.ICONS]. */
    const val ICON = "icon"
    const val CREATED_AT = "createdAtMs"
    const val DELETED = "deleted"
}

/** One of his own categories: [id] (`k<hash>`), [name] ("Vape"), [tone] its colour in the ring, [icon]. */
data class MoneyOwnCategory(val id: String, val name: String, val tone: Int, val icon: MoneyIcon, val createdAtMs: Long = 0L) {
    /** Its key wherever a category key goes (chips, rows, rules): `own:<id>`. */
    val key: String get() = MoneyOwnCategories.PREFIX + id
}

/** A colour to pick on the New category form: its [tone] and how a screen reader names it. */
data class MoneyOwnTone(val tone: Int, val name: String)

object MoneyOwnCategories {
    const val PREFIX = "own:"
    const val MAX_NAME = 24
    const val NEW = "+ New category"
    const val NEW_SPOKEN = "Make a new category of your own"
    const val TITLE = "New category"
    const val NAME_HINT = "Name it (e.g. Vape)"
    const val COLOUR_LABEL = "COLOUR"
    const val ICON_LABEL = "ICON"
    const val CANCEL = "Cancel"
    const val CREATE = "Create"
    const val NO_NAME = "Give it a name first"

    /** The icons he can pick from (the money set's, drawn on both apps already). */
    val ICONS: List<MoneyIcon> = listOf(
        MoneyIcon.DOTS, MoneyIcon.BAG, MoneyIcon.CART, MoneyIcon.CUP, MoneyIcon.FORK, MoneyIcon.TICKET, MoneyIcon.HEART,
        MoneyIcon.GIFT, MoneyIcon.HOME, MoneyIcon.KID, MoneyIcon.SHIRT, MoneyIcon.PLANE, MoneyIcon.TRAIN, MoneyIcon.REPEAT,
    )

    /** How a screen reader names each icon. */
    fun iconName(icon: MoneyIcon): String = when (icon) {
        MoneyIcon.DOTS -> "dots"
        MoneyIcon.BAG -> "bag"
        MoneyIcon.CART -> "trolley"
        MoneyIcon.CUP -> "cup"
        MoneyIcon.FORK -> "fork"
        MoneyIcon.TICKET -> "ticket"
        MoneyIcon.HEART -> "heart"
        MoneyIcon.GIFT -> "gift"
        MoneyIcon.HOME -> "house"
        MoneyIcon.KID -> "child"
        MoneyIcon.SHIRT -> "shirt"
        MoneyIcon.PLANE -> "plane"
        MoneyIcon.TRAIN -> "train"
        MoneyIcon.REPEAT -> "repeat"
        else -> icon.name.lowercase()
    }

    /** The colours (the ring's seven tones), named for a screen reader in the money palette's order. */
    val TONES: List<MoneyOwnTone> = listOf("gold", "periwinkle", "terracotta", "sage", "sand", "lilac", "blue")
        .mapIndexed { i, n -> MoneyOwnTone(i, n) }

    fun isOwn(key: String?): Boolean = key?.startsWith(PREFIX) == true

    fun idOf(key: String?): String? = key?.takeIf { isOwn(it) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    /** The id for a name: the same name on both devices is the same category ("vape", "Vape " → one). */
    fun idFor(name: String): String = "k" + ActivityRules.fnv64("moneycat:" + fold(name))

    private fun fold(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    /** [raw] tidied: spaces collapsed, at most [MAX_NAME] characters; null when nothing is left. */
    fun clean(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /** The built-in category a name already is ("groceries" → Groceries), so he isn't given a second one. */
    fun builtIn(name: String): MoneyCategory? = MoneyCategory.entries.firstOrNull { fold(it.label) == fold(name) || it.key == fold(name) }

    /** The colour a new category is offered first: the first tone none of his [own] wears yet. */
    fun nextTone(own: List<MoneyOwnCategory>): Int {
        val used = own.mapTo(HashSet()) { it.tone }
        return (0 until MoneySpending.TONES).firstOrNull { it !in used } ?: (own.size % MoneySpending.TONES)
    }

    /** One stored category, or null when it was taken away or has no name. */
    fun read(id: String, fields: Map<String, FieldValue>): MoneyOwnCategory? {
        if ((fields[MoneyOwnFields.DELETED] as? FieldValue.Bool)?.value == true) return null
        val name = (fields[MoneyOwnFields.NAME] as? FieldValue.Text)?.value?.let(::clean) ?: return null
        val tone = ((fields[MoneyOwnFields.TONE] as? FieldValue.Int64)?.value ?: 0L).toInt().coerceIn(0, MoneySpending.TONES - 1)
        val icon = (fields[MoneyOwnFields.ICON] as? FieldValue.Text)?.value?.let { n -> ICONS.firstOrNull { it.name == n } } ?: MoneyIcon.DOTS
        val at = (fields[MoneyOwnFields.CREATED_AT] as? FieldValue.Int64)?.value ?: 0L
        return MoneyOwnCategory(id, name, tone, icon, at)
    }

    /** "Made Vape · moved 14 payments from News ON The", or "Made Vape · News ON The is Vape from now on". */
    fun madeLine(name: String, merchant: String?, count: Int): String = when {
        merchant == null -> "Made $name"
        count > 1 -> "Made $name · moved $count payments from $merchant"
        else -> "Made $name · $merchant is $name from now on"
    }

    /** "Groceries is already a category · pick it above". */
    fun alreadyLine(c: MoneyCategory): String = "${c.label} is already a category · pick it above"

    /** The form's spoken colour swatch. */
    fun toneSpoken(t: MoneyOwnTone, on: Boolean): String = if (on) "${t.name} colour, chosen" else "${t.name} colour"

    fun iconSpoken(icon: MoneyIcon, on: Boolean): String = if (on) "${iconName(icon)} icon, chosen" else "${iconName(icon)} icon"
}
