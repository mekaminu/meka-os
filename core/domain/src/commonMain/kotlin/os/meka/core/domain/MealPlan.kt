package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Meal plan → shopping (V1, Meka approved 2026-10-10). Favourite dinners are typed once with their ingredients
 * ("Chilli: mince, kidney beans, rice"), the week's dinners are picked from them a day at a time, and one tap puts the
 * week's ingredients on the shared shopping list ([Shopping]), merging what is already there. Tonight's dinner shows in
 * Today's header in the evening. Two synced entity types (ADR-008 addendum 2026-10-10): `meal` (one favourite, a random
 * id) and `meal_day` (what's for dinner on one day, id `d<epoch day>` so both devices write the same entity). Plain,
 * non-AI rules; nothing leaves Meka's own synced data.
 */
object MealFields {
    const val TITLE = "title"
    /** The ingredients as typed, one per line ("mince\nkidney beans\nrice"); empty when none were given. */
    const val INGREDIENTS = "ingredients"
    const val ADDED_AT = "addedAtMs"
    /** Who added it: [ShoppingRules.OWNER] or a family member's name key ("jeanette"). Display only. */
    const val BY = "by"
    const val DELETED = ActionableFields.DELETED
}

object MealDayFields {
    /** The favourite planned for that day (a `meal` id), or Null once cleared. Last writer wins. */
    const val MEAL = "mealId"
    const val SET_AT = "setAtMs"
    const val BY = "by"
}

/** What one typed line says: the dinner's name and its ingredients. */
data class MealEntry(val title: String, val ingredients: List<String>)

/** One favourite as stored. */
data class Meal(val id: String, val title: String, val ingredients: List<String>, val by: String, val addedAtMs: Long)

/** A favourite's row: "Chilli" · "mince, kidney beans, rice". */
data class MealRow(val id: String, val title: String, val line: String, val spoken: String)

/** One day of the week: "Tonight" · "Chilli" · "3 ingredients", or "Sun 11 Oct" · not planned. */
data class MealDayRow(
    val day: Long,
    val label: String,
    val mealId: String?,
    val title: String?,
    val line: String,
    val spoken: String,
)

/** Tonight's dinner in Today's header: "Dinner tonight: Chilli". */
data class MealLine(val text: String, val spoken: String)

/** What "Add to shopping" did, for its line and its Undo ([Shopping.takeBack]). */
data class MealsShopped(val line: String, val added: List<String>, val revived: List<String>)

data class MealPlanView(
    /** Today and the six days after it, in order. */
    val week: List<MealDayRow>,
    /** Every favourite, by name. */
    val favourites: List<MealRow>,
    /** "4 of 7 dinners planned · tonight: Chilli". */
    val summary: String,
    /** The week's ingredients not yet on the shopping list (merged, in the order the days come). */
    val toAdd: List<String>,
    /** "Add 9 ingredients to shopping", or null when there is nothing to add. */
    val shoppingLabel: String?,
    /** Under the button: "3 are already on the list", "All 6 ingredients are on the shopping list", or a hint. */
    val shoppingLine: String,
) {
    val plannedCount: Int get() = week.count { it.mealId != null }

    companion object {
        val EMPTY = MealPlanView(emptyList(), emptyList(), MealRules.NO_FAVOURITES, emptyList(), null, MealRules.SHOPPING_HINT)
    }
}

object MealRules {
    const val WEEK_DAYS = 7
    /** How far ahead a day can be planned (the next fortnight). */
    const val PLAN_AHEAD_DAYS = 13
    const val MAX_TITLE = 60
    const val MAX_INGREDIENTS = 30
    /** Tonight's dinner shows in Today's header from 15:00 until 21:00. */
    const val TONIGHT_FROM_MIN = 15 * 60
    const val TONIGHT_UNTIL_MIN = 21 * 60

    const val ADD_HINT = "Add a dinner… (Chilli: mince, beans, rice)"
    const val NOT_READ = "Type the dinner's name, then a colon and its ingredients: “Chilli: mince, beans, rice”."
    const val NO_FAVOURITES = "No favourite dinners yet. Add one below: “Chilli: mince, beans, rice”."
    const val NOTHING_PLANNED = "Nothing planned this week · tap a day to pick a dinner"
    const val SHOPPING_HINT = "Pick the week's dinners and their ingredients can go on the shopping list in one tap."
    const val NOT_PLANNED = "Not planned"
    const val NONE_CHOICE = "None"
    const val SHARED = "Favourites and the week's dinners are on the Fold and the Mac. Ingredients go on the shopping list Jeanette shares."

    /** Separators between a dinner's name and its ingredients, in the order they're looked for. */
    private val SEPARATORS = listOf(":", " - ", " – ", " — ")

    /**
     * Reads one typed line: "Chilli: mince, kidney beans, rice" → Chilli with three ingredients; "Fish and chips" alone
     * is a favourite with none yet; "Pasta bake (pasta, cheese, tomatoes)" works too. Ingredients split like the
     * shopping list ([ShoppingRules.split]). Null when there is no name.
     */
    fun read(text: String): MealEntry? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val sep = SEPARATORS.map { t.indexOf(it) to it }.filter { it.first >= 0 }.minByOrNull { it.first }
        val (name, rest) = when {
            sep != null -> t.substring(0, sep.first) to t.substring(sep.first + sep.second.length)
            t.endsWith(")") && t.indexOf('(') > 0 -> t.substring(0, t.indexOf('(')) to t.substring(t.indexOf('(') + 1, t.length - 1)
            else -> t to ""
        }
        val title = title(name) ?: return null
        return MealEntry(title, ShoppingRules.split(rest).take(MAX_INGREDIENTS))
    }

    /** "  chilli con carne. " → "Chilli con carne"; cut at a word past [MAX_TITLE]; null when nothing is left. */
    fun title(name: String): String? {
        var t = name.trim().trimEnd('.', ',', ';', ':', '-', '–', '—').trim()
        if (t.isEmpty()) return null
        if (t.length > MAX_TITLE) {
            val cut = t.take(MAX_TITLE)
            t = cut.substringBeforeLast(' ', cut).trim()
        }
        return t.replaceFirstChar { it.uppercase() }
    }

    /** Two favourites are the same dinner when their names match ignoring case and spaces. */
    fun key(title: String): String = ShoppingRules.key(title)

    fun encodeIngredients(list: List<String>): String = list.joinToString("\n")

    fun decodeIngredients(text: String?): List<String> =
        text.orEmpty().split('\n').map(ShoppingRules::tidy).filter { it.isNotEmpty() }

    /** The id of [day]'s `meal_day` entity: one per day, the same on every device. */
    fun dayId(day: Long): String = "d$day"

    fun dayOf(id: String): Long? = id.removePrefix("d").takeIf { id.startsWith("d") }?.toLongOrNull()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The favourites still on the list (id → fields). */
    fun meals(entities: Map<String, Map<String, FieldValue>>): List<Meal> = entities.mapNotNull { (id, s) ->
        if (s.f(MealFields.DELETED).boolOrNull == true) return@mapNotNull null
        val title = s.f(MealFields.TITLE).textOrNull?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        Meal(
            id, title, decodeIngredients(s.f(MealFields.INGREDIENTS).textOrNull),
            s.f(MealFields.BY).textOrNull ?: ShoppingRules.OWNER, s.f(MealFields.ADDED_AT).longOrNull ?: 0L,
        )
    }

    /** What's planned, day → meal id, from the `meal_day` entities (cleared days left out). */
    fun plan(entities: Map<String, Map<String, FieldValue>>): Map<Long, String> = entities.mapNotNull { (id, s) ->
        val day = dayOf(id) ?: return@mapNotNull null
        val meal = s.f(MealDayFields.MEAL).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        day to meal
    }.toMap()

    /** "Tonight", "Tomorrow", "Mon 12 Oct". */
    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Tonight"
        today + 1 -> "Tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    fun ingredientsLine(list: List<String>): String = when (list.size) {
        0 -> "No ingredients yet"
        1 -> "1 ingredient"
        else -> "${list.size} ingredients"
    }

    /** A favourite's line: its ingredients ("mince, kidney beans, rice", cut at a word past 80), and who added it. */
    fun mealLine(m: Meal): String {
        val list = if (m.ingredients.isEmpty()) "No ingredients yet · type “${m.title}: …” to add them" else {
            val all = m.ingredients.joinToString(", ")
            if (all.length <= 80) all else all.take(80).substringBeforeLast(", ", all.take(80)) + "…"
        }
        return ShoppingRules.byName(m.by)?.let { "$list · from $it" } ?: list
    }

    /**
     * The week from [today] (seven days), the favourites by name, and what "Add to shopping" would add: the planned
     * dinners' ingredients merged by name ([ShoppingRules.key]), less what's already to buy ([toBuyKeys]).
     */
    fun view(meals: List<Meal>, plan: Map<Long, String>, today: Long, toBuyKeys: Set<String>): MealPlanView {
        val byId = meals.associateBy { it.id }
        val week = (0 until WEEK_DAYS).map { i ->
            val day = today + i
            val label = dayLabel(day, today)
            val meal = plan[day]?.let(byId::get)
            if (meal == null) MealDayRow(day, label, null, null, NOT_PLANNED, "$label: not planned")
            else MealDayRow(day, label, meal.id, meal.title, ingredientsLine(meal.ingredients), "$label: ${meal.title}")
        }
        val favourites = meals.sortedWith(compareBy<Meal> { key(it.title) }.thenBy { it.id })
            .map { MealRow(it.id, it.title, mealLine(it), "${it.title}. ${mealLine(it)}") }
        val wanted = week.mapNotNull { it.mealId?.let(byId::get) }.flatMap { it.ingredients }.distinctBy(ShoppingRules::key)
        val toAdd = wanted.filter { ShoppingRules.key(it) !in toBuyKeys }
        val already = wanted.size - toAdd.size
        val planned = week.count { it.mealId != null }
        val tonight = week.first().title
        val summary = when {
            meals.isEmpty() -> NO_FAVOURITES
            planned == 0 -> NOTHING_PLANNED
            tonight != null -> "$planned of $WEEK_DAYS dinners planned · tonight: $tonight"
            else -> "$planned of $WEEK_DAYS dinners planned"
        }
        val label = when (toAdd.size) {
            0 -> null
            1 -> "Add 1 ingredient to shopping"
            else -> "Add ${toAdd.size} ingredients to shopping"
        }
        val line = when {
            wanted.isEmpty() -> SHOPPING_HINT
            toAdd.isEmpty() -> if (wanted.size == 1) "It's already on the shopping list" else "All ${wanted.size} ingredients are on the shopping list"
            already == 0 -> "For the dinners planned this week · the same thing twice goes on once"
            already == 1 -> "1 is already on the list"
            else -> "$already are already on the list"
        }
        return MealPlanView(week, favourites, summary, toAdd, label, line)
    }

    /** "Added 9 to shopping", "Added 9 to shopping · 3 were already on it". */
    fun shoppedLine(added: Int, already: Int): String = when {
        added == 0 -> "Everything is already on the shopping list"
        already == 0 -> "Added $added to shopping"
        else -> "Added $added to shopping · $already ${if (already == 1) "was" else "were"} already on it"
    }

    /** "Added Chilli · 3 ingredients", "Updated Chilli · 4 ingredients". */
    fun addedLine(e: MealEntry, updated: Boolean): String =
        "${if (updated) "Updated" else "Added"} ${e.title} · ${ingredientsLine(e.ingredients).replaceFirstChar { it.lowercase() }}"

    /** "Chilli for tonight", "Nothing planned for Mon 12 Oct". */
    fun plannedLine(day: Long, today: Long, title: String?): String {
        val label = dayLabel(day, today)
        val when_ = if (day == today) "tonight" else if (day == today + 1) "tomorrow" else label
        return if (title == null) "Nothing planned for $when_" else "$title for $when_"
    }

    /** Today's header between [TONIGHT_FROM_MIN] and [TONIGHT_UNTIL_MIN]: "Dinner tonight: Chilli"; null otherwise. */
    fun tonightLine(meals: List<Meal>, plan: Map<Long, String>, today: Long, minuteOfDay: Int): MealLine? {
        if (minuteOfDay !in TONIGHT_FROM_MIN until TONIGHT_UNTIL_MIN) return null
        val meal = plan[today]?.let { id -> meals.firstOrNull { it.id == id } } ?: return null
        return MealLine("Dinner tonight: ${meal.title}", "Dinner tonight is ${meal.title}.")
    }

    /**
     * Ask and Talk (one line, so "what's for dinner tonight?" and "what's planned this week?" can be answered):
     * "Dinners · tonight: Chilli · Sun: Roast chicken · Tue: not planned…" up to the week's end; null when nothing is
     * planned and there are no favourites.
     */
    fun askLine(view: MealPlanView): String? {
        if (view.favourites.isEmpty() && view.plannedCount == 0) return null
        val days = view.week.joinToString(" · ") { r ->
            val label = if (r.label == "Tonight") "tonight" else r.label
            "$label: ${r.title ?: "not planned"}"
        }
        val favs = view.favourites.take(12).joinToString(", ") { it.title }
        return "Dinners · $days" + if (favs.isEmpty()) "" else " · favourites: $favs"
    }
}

/** The favourites and the week's dinners on a replica. */
class MealPlan(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun meals(): List<Meal> = MealRules.meals(replica.entities(EntityTypes.MEAL).associate { it.ref.entityId to it.fields })

    fun plan(): Map<Long, String> = MealRules.plan(replica.entities(EntityTypes.MEAL_DAY).associate { it.ref.entityId to it.fields })

    fun view(toBuyKeys: Set<String>): MealPlanView =
        MealRules.view(meals(), plan(), calendar.epochDayOf(nowMs()), toBuyKeys)

    fun tonight(): MealLine? {
        val now = nowMs()
        val day = calendar.epochDayOf(now)
        val minute = ((now - calendar.toEpochMs(day, 0)) / 60_000L).toInt()
        return MealRules.tonightLine(meals(), plan(), day, minute)
    }

    /**
     * Adds a favourite from one typed line ([MealRules.read]). A dinner already a favourite (same name) keeps its entity:
     * newly typed ingredients replace its old ones (none typed leaves them). Returns the entry and whether it was an
     * update; null when nothing could be read.
     */
    fun add(text: String, by: String = ShoppingRules.OWNER): Pair<MealEntry, Boolean>? {
        val e = MealRules.read(text) ?: return null
        val same = meals().firstOrNull { MealRules.key(it.title) == MealRules.key(e.title) }
        if (same != null) {
            if (e.ingredients.isNotEmpty()) {
                replica.commitLocal(EntityTypes.MEAL, same.id, mapOf(MealFields.INGREDIENTS to MealRules.encodeIngredients(e.ingredients).fv()))
            }
            return MealEntry(same.title, e.ingredients.ifEmpty { same.ingredients }) to true
        }
        replica.commitLocal(EntityTypes.MEAL, newId(), mapOf(
            MealFields.TITLE to e.title.fv(),
            MealFields.INGREDIENTS to MealRules.encodeIngredients(e.ingredients).fv(),
            MealFields.ADDED_AT to nowMs().fv(),
            MealFields.BY to ShoppingRules.whoKey(by).fv(),
        ))
        return e to false
    }

    /** Removes a favourite for good; days it was planned on show "Not planned". */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.MEAL, id)?.takeIf { it[MealFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.MEAL, id, mapOf(MealFields.DELETED to true.fv()))
        return true
    }

    /**
     * Plans [mealId] for [day] (null clears it). Only today up to [MealRules.PLAN_AHEAD_DAYS] ahead, and only a
     * favourite still on the list; false otherwise or when it is already so.
     */
    fun set(day: Long, mealId: String?, by: String = ShoppingRules.OWNER): Boolean {
        val today = calendar.epochDayOf(nowMs())
        if (day < today || day > today + MealRules.PLAN_AHEAD_DAYS) return false
        if (mealId != null && meals().none { it.id == mealId }) return false
        if (plan()[day] == mealId) return false
        replica.commitLocal(EntityTypes.MEAL_DAY, MealRules.dayId(day), mapOf(
            MealDayFields.MEAL to (mealId?.fv() ?: FieldValue.Null),
            MealDayFields.SET_AT to nowMs().fv(),
            MealDayFields.BY to ShoppingRules.whoKey(by).fv(),
        ))
        return true
    }
}
