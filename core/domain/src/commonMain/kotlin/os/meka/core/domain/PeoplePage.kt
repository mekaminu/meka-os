package os.meka.core.domain

/**
 * Ask → Life → People in the page shape (every page under Ask in one shape, slice 2; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`, the People example): the title with its ⓘ, one gold + Add (a birthday, something
 * he promised, something he's waiting for), three numbers (the next birthday, what's due or to chase, how many people),
 * then **Coming up** (birthdays in the next month and anyone with something due, soonest first) and **Everyone** (every
 * row of the People pane, with what MEKA last saw of them). A row opens that person's sheet (what he promised, with
 * Kept, and what he's waiting for). Built from the [PeopleView] both apps already have; nothing new is read or sent.
 * Non-AI, pure.
 */
object PeoplePageRules {
    const val TITLE = "People"
    const val INFO = "Birthdays, what you've promised people and what you're waiting for from them, and when you last heard from each. Nothing is sent to anyone."
    const val PRIVACY = "Last contact comes from the messages Navilife has already seen; nothing new is read. Family, Always notify and Watch for requests are set in Work mode → People on your phone and stay there."
    const val ADD_TITLE = "Add to People"
    const val COMING_UP = "Coming up"
    const val EVERYONE = "Everyone"
    const val COMING_EMPTY = "Nothing coming up · birthdays in the next month and promises due show here"
    const val EVERYONE_EMPTY = "No one yet · + Add a birthday or a promise and they show here"
    /** A birthday this many days ahead is in Coming up. */
    const val COMING_DAYS = 30L
    /** A birthday this close is brass. */
    const val SOON_DAYS = 7L
    const val NOT_READ = "Type who and what, e.g. Sam: fix his bike"
    const val NOTHING_OWED = "Nothing owed either way · + Add on People adds a promise or something you're waiting for"
    /** What a row with something due says on the right. */
    const val DUE = "due"

    const val ADD_BIRTHDAY = "birthday"
    const val ADD_PROMISE = "promise"
    const val ADD_WAITING = "waiting"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_BIRTHDAY, "A birthday", "e.g. Sam's birthday 14 Nov · it goes in Gifts"),
        AddChoice(ADD_PROMISE, "Something I promised", "e.g. Sam: fix his bike"),
        AddChoice(ADD_WAITING, "Something I'm waiting for", "e.g. Alex: the drill back"),
    )
    /** The field's hint for each choice. */
    fun hint(choice: String): String = when (choice) {
        ADD_BIRTHDAY -> "Sam's birthday 14 Nov"
        ADD_PROMISE -> "Sam: fix his bike"
        else -> "Alex: the drill back"
    }

    /** The key a person's row carries ("person:theo"); [personKey] gives it back. */
    fun rowKey(personKey: String): String = "person:$personKey"
    fun personKey(rowKey: String): String? = rowKey.takeIf { it.startsWith("person:") }?.removePrefix("person:")

    /** "Today", "Tomorrow", "Sat" within the week, else "14 Nov". */
    fun dayWord(day: Long, today: Long): String = when (val d = day - today) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> if (d in 2..6) LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]
        else CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }
    }

    private fun dueCount(r: PersonRow): Int = r.promised.count { it.due } + r.waiting.count { it.due }

    fun page(view: PeopleView, today: Long): LifePageView {
        val rows = view.rows
        val nextBirthday = rows.filter { it.birthdayDay != null }.minByOrNull { it.birthdayDay!! }
        val birthdayTile = nextBirthday?.let { r ->
            val d = r.birthdayDay!!
            PageTile(dayWord(d, today), "${r.name.substringBefore(' ')}'s birthday", d - today <= SOON_DAYS)
        } ?: PageTile("No birthdays", "in Gifts yet", false)
        val due = rows.sumOf(::dueCount)
        val open = rows.sumOf { it.promised.size + it.waiting.size }
        val owedTile = when {
            due > 0 -> PageTile("$due", "due or to chase", true)
            open > 0 -> PageTile("$open", "promised or waited on", false)
            else -> PageTile("Nothing", "owed either way", false)
        }
        val peopleTile = PageTile("${rows.size}", if (rows.size == 1) "person" else "people", false)

        val coming = rows.mapNotNull { r ->
            val n = dueCount(r)
            val bDay = r.birthdayDay?.takeIf { it - today in 0..COMING_DAYS }
            if (n == 0 && bDay == null) return@mapNotNull null
            val birthdayWords = bDay?.let { listOfNotNull("Birthday", r.birthdayAge).joinToString(" · ") }
            val line = listOfNotNull(r.owedLine?.takeIf { n > 0 }, birthdayWords).joinToString(" · ")
            val sortDay = if (n > 0) today else bDay!!
            sortDay to PageRow(
                rowKey(r.key), r.name, line,
                if (n > 0) DUE else dayWord(bDay!!, today),
                n > 0 || bDay!! - today <= SOON_DAYS,
            )
        }.sortedWith(compareBy<Pair<Long, PageRow>> { it.first }.thenBy { it.second.name.lowercase() }).map { it.second }

        val everyone = rows.map { r ->
            PageRow(rowKey(r.key), r.name, r.lastContact ?: r.owedLine ?: r.birthday ?: r.listed ?: "", "", false)
        }
        return LifePageView(
            TITLE, INFO,
            listOf(birthdayTile, owedTile, peopleTile),
            listOf(
                LifePageRules.card(COMING_UP, COMING_UP, coming, empty = COMING_EMPTY),
                LifePageRules.card(EVERYONE, EVERYONE, everyone, empty = EVERYONE_EMPTY),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /** The person behind a row's key, or null when they've gone (the row was tapped as the pane changed). */
    fun person(view: PeopleView, rowKey: String): PersonRow? = personKey(rowKey)?.let { k -> view.rows.firstOrNull { it.key == k } }

    /** What + Add's promise or waiting field read: "Sam: fix his bike" → Sam and "Fix his bike"; no colon → nobody. */
    data class Owed(val who: String?, val what: String)

    /** The line once added: "Added your promise to Sam: Fix his bike" / "Added to Waiting for: The drill back, from Alex". */
    fun addedLine(choice: String, o: Owed): String =
        if (choice == ADD_PROMISE) "Added your promise" + (o.who?.let { " to $it: " } ?: ": ") + o.what
        else "Added to Waiting for: ${o.what}" + (o.who?.let { ", from $it" } ?: "")

    fun readOwed(text: String): Owed? {
        val t = text.replace('\n', ' ').split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        val colon = t.indexOf(':')
        val who = if (colon > 0) t.substring(0, colon).trim().take(60).ifEmpty { null } else null
        val what = (if (colon > 0) t.substring(colon + 1) else t).trim().take(Lists.MAX_TITLE).trim()
        if (what.isEmpty()) return null
        return Owed(who, what.replaceFirstChar { it.uppercase() })
    }
}
