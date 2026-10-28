package os.meka.core.domain

/**
 * Obi sees everything (Meka 2026-10-28 19:32 and 19:45: "Please ensure he sees everything I have" … "I need the orb to
 * be the same"): the areas of Navilife Obi can read, each with the Ask lines' kind it fills ([kind]), the screen that
 * holds it ([screen], what Obi names when it is empty) and whether its lines can carry other people's words
 * ([max] lines at most, as Ask sends them; [untrusted]: the session's cards then pass the gate as untrusted ones do).
 *
 * The same provider fills Ask's <today> block and answers Live's [LiveToolRules.NAVILIFE_TOOL] look-up on the device,
 * so the orb (Live or not) and typed Ask see the same lines. The day's calendar and tasks, mail and money keep their
 * own look-ups ([LiveToolRules.LOOK_UP_TOOL], [LiveToolRules.MAIL_TOOL], [LiveToolRules.MONEY_TOOL]).
 */
enum class ObiArea(val key: String, val kind: AskItemKind, val label: String, val screen: String, val max: Int, val untrusted: Boolean = false) {
    STUDY("study", AskItemKind.STUDY, "Study", "Goals → Study", StudyAskRules.ASK_MAX),
    GOALS("goals", AskItemKind.GOALS, "Goals", "Goals", GoalAskRules.ASK_GOALS + 1),
    TRIPS("trips", AskItemKind.TRIPS, "Trips and trip lists", "Trips", TripAskRules.ASK_LINES + TripListAskRules.ASK_LINES),
    CHECKLISTS("checklists", AskItemKind.CHECKLISTS, "Task checklists", "the task's own checklist", ChecklistAskRules.ASK_LISTS + 1),
    SHOPPING("shopping", AskItemKind.SHOPPING, "Shopping list", "Shopping", 1, untrusted = true),
    MEALS("meals", AskItemKind.MEALS, "Meal plan", "Meals", 1, untrusted = true),
    ERRANDS("errands", AskItemKind.ERRANDS, "Errands by place", "Errands", ErrandAskRules.ASK_PLACES + 1),
    APPOINTMENTS("appointments", AskItemKind.APPOINTMENTS, "Appointments", "Appointments", AppointmentAskRules.ASK_ITEMS + 1),
    RENEWALS("renewals", AskItemKind.RENEWALS, "Renewals and home jobs", "Home → Renewals", RenewalAskRules.ASK_ITEMS),
    BINS("bins", AskItemKind.BINS, "Bin days", "Home → Bins", 1 + BinRules.ASK_BINS + BinRules.ASK_DAYS),
    CAR("car", AskItemKind.CAR, "Car", "Car", CarAskRules.ASK_LINES_ALL),
    GIFTS("gifts", AskItemKind.GIFTS, "Birthdays and gifts", "Gifts", 1 + GiftAskRules.ASK_ITEMS),
    PEOPLE("people", AskItemKind.PEOPLE, "People and promises", "People", PeopleAskRules.ASK_LINES),
    SCHOOL("school", AskItemKind.SCHOOL, "School dates", "Kids → School", SchoolRules.ASK_OFF + SchoolRules.ASK_DATES + SchoolRules.ASK_WEEKLY),
    LEAVE("leave", AskItemKind.LEAVE, "Annual leave", "Leave", LeaveAskRules.MAX_LINES),
    WINS("wins", AskItemKind.WINS, "Work wins", "Wins", WinAskRules.ASK_ITEMS + 1),
    QUEUE("queue", AskItemKind.QUEUE, "Train queue", "Train queue", QueueAskRules.ASK_ITEMS + QueueAskRules.ASK_DONE + 1, untrusted = true),
    FACTS("facts", AskItemKind.FACTS, "What Navilife knows", "What Navilife knows", FactAskRules.ASK_ALL + 1),
    PROTECTED("protected", AskItemKind.PROTECTED, "Protected time", "Settings → Protected time", ProtectedRules.ASK_LINES),
    LIFE_ADMIN("life_admin", AskItemKind.LIFE_ADMIN, "Life admin hour", "Life admin", 1),
    DATE_NIGHT("date_night", AskItemKind.DATE_NIGHT, "Date night", "Date night", 1),
    COMMUTE("commute", AskItemKind.COMMUTE, "Commute", "Work mode", CommuteAskRules.ASK_LINES);

    companion object {
        fun of(key: String?): ObiArea? = key?.trim()?.lowercase()?.let { k -> entries.firstOrNull { it.key == k } }
        val KEYS: List<String> get() = entries.map { it.key }
    }
}

object ObiSees {
    /** What the model is told for an area Navilife doesn't have. */
    val NO_AREA: String get() =
        "Navilife has no area by that name. Use one of: ${ObiArea.KEYS.joinToString(", ")}; for a day's calendar and tasks use " +
            "${LiveToolRules.LOOK_UP_TOOL}, for email ${LiveToolRules.MAIL_TOOL}, for money ${LiveToolRules.MONEY_TOOL}."
    /** ... when the device couldn't read it just now. */
    const val FAILED = "Navilife couldn't read that just now. Say you can't see it at the moment."

    /** The prompt's rule (Meka 2026-10-28): Obi never says he has "no view" of something in the app. */
    const val NEVER_NO_VIEW = "Never say you have no view of, or no access to, something in Navilife: if it isn't in what you were sent, look it up with the tool; if Navilife truly has nothing, say so and which screen would hold it."

    /** The look-up's answer: the area's lines as Ask's <today> block writes them ("study: …"), or where it would be. */
    fun answer(area: ObiArea, lines: List<String>): String =
        if (lines.isEmpty()) "Navilife has nothing in ${area.label} yet; it would be on the ${area.screen} screen. Say so plainly."
        else lines.joinToString("\n") { "${area.kind.wire}: ${it.replace(Regex("""\s+"""), " ").trim().take(AskRules.MAX_LINE)}" }
}
