package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Checklist templates (Checklists in tasks, slice 3a; Meka, 2026-10-10): "Save as template" keeps a task's checklist
 * under a name (the weekly shop, packing, the boys' kit); "Start from template" adds a saved list's items to a task's
 * checklist, unticked, skipping any already there. Synced `checklist_template` entities; the items are the template's
 * own copy (later edits to the task don't change it). Non-AI, pure rules plus a small store.
 */
object ChecklistTemplateFields {
    /** "Weekly shop": what the template is called; one template per name (case doesn't matter). */
    const val NAME = "name"
    /** The items, one per line, in the list's order. */
    const val ITEMS = "items"
    const val CREATED_AT = "createdAtMs"
    /** When it was last saved over; orders the list (most recent first). */
    const val UPDATED_AT = "updatedAtMs"
    const val DELETED = ActionableFields.DELETED
}

data class ChecklistTemplate(
    val id: String,
    val name: String,
    val items: List<String>,
    val updatedAtMs: Long,
    /** A starter MEKA offers until Meka saves one of his own by that name; never stored until he saves it. */
    val starter: Boolean = false,
) {
    /** "7 items · milk, bread, eggs…" for the picker's row. */
    val line: String get() = ChecklistTemplateRules.line(items)
}

/** What Start from template offers: Meka's own templates first (latest first), then the starters he hasn't replaced. */
data class ChecklistTemplatesView(val saved: List<ChecklistTemplate>, val starters: List<ChecklistTemplate>) {
    val all: List<ChecklistTemplate> get() = saved + starters
    val isEmpty: Boolean get() = saved.isEmpty() && starters.isEmpty()
}

/** What saving did: the template's id, whether it replaced one of the same name, and the undo bar's line. */
data class TemplateSaved(val id: String?, val replaced: Boolean, val line: String) {
    val saved: Boolean get() = id != null
}

/** What Start from template added (ids for Undo, which removes them) and the bar's line. */
data class TemplateApplied(val stepIds: List<String>, val line: String)

object ChecklistTemplateRules {
    const val MAX_NAME = 60
    /** A template keeps at most this many items (a checklist's paste limit is 30; a saved list may be longer). */
    const val MAX_ITEMS = 60
    const val SAVE = "Save as template"
    const val START = "Start from template"
    const val NOTHING_TO_SAVE = "Add some items first, then save them as a template"
    const val PICKER_TITLE = "Start from template"
    const val STARTER_TAG = "Starter"
    const val STARTER_PREFIX = "starter."

    /** Starters for the lists Meka named (weekly shop, packing, kit); each becomes ordinary items to edit once added. */
    val STARTERS: List<ChecklistTemplate> = listOf(
        ChecklistTemplate("${STARTER_PREFIX}weekly-shop", "Weekly shop",
            listOf("Milk", "Bread", "Eggs", "Fruit", "Veg", "Chicken", "Pasta", "Rice", "Cheese", "Toilet roll"), 0, starter = true),
        ChecklistTemplate("${STARTER_PREFIX}packing", "Packing",
            listOf("Phone charger", "Toiletries", "Medication", "Clothes", "Pyjamas", "Wallet and cards", "Keys", "Headphones"), 0, starter = true),
        ChecklistTemplate("${STARTER_PREFIX}kit", "Football kit",
            listOf("Boots", "Shin pads", "Socks", "Shirt and shorts", "Water bottle", "Coat"), 0, starter = true),
    )

    fun tidyName(name: String): String = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()

    private fun key(name: String): String = tidyName(name).lowercase()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun items(raw: Map<String, Map<String, FieldValue>>): List<ChecklistTemplate> =
        raw.filterValues { it.f(ChecklistTemplateFields.DELETED).boolOrNull != true }
            .map { (id, s) ->
                ChecklistTemplate(
                    id,
                    s.f(ChecklistTemplateFields.NAME).textOrNull.orEmpty().let(::tidyName),
                    decode(s.f(ChecklistTemplateFields.ITEMS).textOrNull.orEmpty()),
                    s.f(ChecklistTemplateFields.UPDATED_AT).longOrNull ?: s.f(ChecklistTemplateFields.CREATED_AT).longOrNull ?: 0L,
                )
            }
            .filter { it.name.isNotEmpty() && it.items.isNotEmpty() }
            // Two devices saving the same name offline: the latest one shows (the other stays in the data, unused).
            .groupBy { key(it.name) }.map { (_, same) -> same.maxWith(compareBy<ChecklistTemplate> { it.updatedAtMs }.thenBy { it.id }) }
            .sortedWith(compareByDescending<ChecklistTemplate> { it.updatedAtMs }.thenBy { it.name.lowercase() })

    fun encode(items: List<String>): String = items.joinToString("\n")

    fun decode(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)

    fun view(saved: List<ChecklistTemplate>): ChecklistTemplatesView {
        val names = saved.map { key(it.name) }.toSet()
        return ChecklistTemplatesView(saved, STARTERS.filter { key(it.name) !in names })
    }

    /** The name Save as template suggests: the task's title ("Weekly shop"). */
    fun suggestedName(task: Task): String = tidyName(task.title)

    /** What Save as template keeps: the task's items in the list's order (ticked or not), repeats dropped. */
    fun itemsOf(task: Task): List<String> =
        task.checklist.sortedBy { it.position }.map { it.text.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.take(MAX_ITEMS)

    /** The template a name would save over (the same name, any case), if there is one. */
    fun existing(saved: List<ChecklistTemplate>, name: String): ChecklistTemplate? =
        saved.firstOrNull { key(it.name) == key(name) }

    /** Start from template: the template's items not already on [task]'s checklist (any case), in its order. */
    fun toAdd(template: ChecklistTemplate, task: Task): List<String> {
        val have = task.checklist.map { it.text.trim().lowercase() }.toSet()
        return template.items.filter { it.lowercase() !in have }.distinctBy { it.lowercase() }
    }

    fun line(items: List<String>): String {
        val n = if (items.size == 1) "1 item" else "${items.size} items"
        if (items.isEmpty()) return n
        val shown = items.take(3).joinToString(", ")
        return "$n · $shown${if (items.size > 3) "…" else ""}"
    }

    fun savedLine(name: String, replaced: Boolean): String =
        if (replaced) "Updated the “$name” template" else "Saved “$name” as a template"

    fun appliedLine(name: String, added: Int): String = when (added) {
        0 -> "Everything from “$name” is already here"
        1 -> "Added 1 item from “$name”"
        else -> "Added $added items from “$name”"
    }

    fun removedLine(name: String): String = "Removed the “$name” template"
}

/** The checklist templates on a replica. */
class ChecklistTemplates(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
) {
    fun saved(): List<ChecklistTemplate> =
        ChecklistTemplateRules.items(replica.entities(EntityTypes.CHECKLIST_TEMPLATE).associate { it.ref.entityId to it.fields })

    fun view(): ChecklistTemplatesView = ChecklistTemplateRules.view(saved())

    /** A saved template or a starter, by id. */
    fun find(id: String): ChecklistTemplate? = saved().firstOrNull { it.id == id } ?: ChecklistTemplateRules.STARTERS.firstOrNull { it.id == id }

    /**
     * Save as template: [items] under [name]. A template of the same name is saved over (its id kept, so every device
     * ends with one); nothing is written without a name and at least one item.
     */
    fun save(name: String, items: List<String>): TemplateSaved {
        val tidy = ChecklistTemplateRules.tidyName(name)
        val list = items.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(ChecklistTemplateRules.MAX_ITEMS)
        if (tidy.isEmpty() || list.isEmpty()) return TemplateSaved(null, false, ChecklistTemplateRules.NOTHING_TO_SAVE)
        val old = ChecklistTemplateRules.existing(saved(), tidy)
        val now = nowMs()
        val id = old?.id ?: newId()
        replica.commitLocal(EntityTypes.CHECKLIST_TEMPLATE, id, buildMap {
            put(ChecklistTemplateFields.NAME, tidy.fv())
            put(ChecklistTemplateFields.ITEMS, ChecklistTemplateRules.encode(list).fv())
            put(ChecklistTemplateFields.UPDATED_AT, now.fv())
            if (old == null) put(ChecklistTemplateFields.CREATED_AT, now.fv())
        })
        return TemplateSaved(id, old != null, ChecklistTemplateRules.savedLine(tidy, old != null))
    }

    /** Removes a saved template (for good on every device); returns it so Undo can save it again. */
    fun remove(id: String): ChecklistTemplate? {
        val t = saved().firstOrNull { it.id == id } ?: return null
        replica.commitLocal(EntityTypes.CHECKLIST_TEMPLATE, id, mapOf(ChecklistTemplateFields.DELETED to true.fv()))
        return t
    }

    /** Undo for [remove]: the template comes back (as a new entity, since a removal is for good). */
    fun restore(t: ChecklistTemplate): TemplateSaved = save(t.name, t.items)
}
