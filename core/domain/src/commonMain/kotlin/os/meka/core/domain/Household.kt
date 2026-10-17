package os.meka.core.domain

import kotlin.concurrent.Volatile

/**
 * Who the household's clubs and children are, as the rules read them (rule 13, release 2 slice 1, 2026-10-15): the
 * children by club age group and the clubs' short names, taken from About you ([AboutYou.children] and
 * [AboutYou.clubs]) and never from the code. [FootballRules] and [ClubChildRules] read [Household.names]; the facade
 * sets it from the synced profile on every refresh ([Household.use]), so a child or club added on the Mac reaches the
 * Fold's fixtures after a sync, and an empty profile means no child is named and no club fixture is spotted.
 * Release 2 slice 2 (2026-10-15): the partner too — Leaving now's draft, the Family link and the week ahead's
 * "to sort out with …" read [partner], and the family-event words read [familyWords], never a name in the code.
 * Release 2 slice 3 (2026-10-15): home and work for the weather ([home], [work]) and the call assistant's number
 * ([assistantNumber]) too; with none typed the weather falls back to a generic place and the number isn't shown.
 * Release 2 slice 4 (2026-10-15): his own name as About you has it ([shown]), so a group message naming him counts as
 * a mention without his full name in the code.
 * Release 2 slice 5 (2026-10-15): the school's council's holidays ([council]), kept in his profile, not in the code.
 * Release 2 slice 6 (2026-10-15): the commute's train route ([route]), so no line or station is named in the code.
 */
data class HouseholdNames(
    /** Age group → the child's first name ("7" → "Alex"); a child without an age group isn't here. */
    val children: Map<Int, String> = emptyMap(),
    /** The clubs' short names, upper case, as calendar titles write them ("OFC"). */
    val clubs: List<String> = emptyList(),
    /** Short name → the club's full name, said aloud ("OFC" → "Oakfield Town"); only clubs with one. */
    val clubNames: Map<String, String> = emptyMap(),
    /** The partner's name as About you has it ("Sam"); null with none typed. */
    val partner: String? = null,
    /** Every child's first name, About you's order (with or without an age group). */
    val childNames: List<String> = emptyList(),
    /** About you's home as a place the weather can follow ("Oakfield"); null with none typed. */
    val home: String? = null,
    /** About you's work place ("Riverside"); null with none typed. */
    val work: String? = null,
    /** The call assistant's number as About you has it ("01632 960000"); null with none typed. */
    val assistantNumber: String? = null,
    /**
     * About you's "Your name" as typed ("Sam Lane"); null with none (release 2 slice 4): the names a group message
     * mentions him by ([MessageTriageRules.names]) add it to the app's own short name.
     */
    val shown: String? = null,
    /**
     * The school's council's holidays as his data keeps them (About you's `councilDates`, release 2 slice 5); null with
     * none. The School pane's council card offers only this ([SchoolTerms.councils]).
     */
    val council: TermCalendar? = null,
    /** About you's Train route ([RouteRules.route] reads it); null with none typed. */
    val route: CommuteRoute? = null,
    /** About you's Home station (live trains); null with none typed. */
    val homeStation: String? = null,
) {
    /** The partner's first name ("Sam" for "Sam Lane"); null with none. */
    val partnerFirst: String? get() = partner?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    /** Lower-case first names of the partner and the children: a calendar title naming one is a family thing. */
    val familyWords: Set<String>
        get() = (listOfNotNull(partnerFirst) + childNames.map { it.trim().substringBefore(' ') })
            .map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        val EMPTY = HouseholdNames()

        /** What [a] says: each child with an age group (the first child per group), each club. */
        fun of(a: AboutYou): HouseholdNames {
            val kids = linkedMapOf<Int, String>()
            for (c in a.children) { val g = c.ageGroup ?: continue; if (g !in kids) kids[g] = c.name }
            val clubs = AboutYouRules.clubList(a.clubs)
            return HouseholdNames(
                children = kids,
                clubs = clubs.map { it.short },
                clubNames = clubs.mapNotNull { c -> c.name?.let { c.short to it } }.toMap(),
                partner = a.partnerName?.trim()?.takeIf { it.isNotEmpty() },
                childNames = a.children.map { it.name },
                home = a.home?.let(WeatherPlaceRules::normalize),
                work = a.work?.let(WeatherPlaceRules::normalize),
                assistantNumber = a.assistantNumber?.trim()?.takeIf { it.isNotEmpty() },
                shown = a.shownName?.trim()?.takeIf { it.isNotEmpty() },
                council = a.councilDates,
                route = RouteRules.parse(a.route),
                homeStation = a.homeStation,
            )
        }
    }
}

/** The household the rules read; set by the facade from About you. */
object Household {
    @Volatile
    var names: HouseholdNames = HouseholdNames.EMPTY
        private set

    /** Reads [a] (the synced profile); true when what the rules see changed. */
    fun use(a: AboutYou): Boolean = set(HouseholdNames.of(a))

    /**
     * The household as typed in About you (tests and the Mac's bridge tests; Swift passes Strings only): [children]
     * one per line ("Alex, U7"), [clubs] as the Clubs line ("OFC Oakfield Town, HRFC").
     */
    fun useTyped(children: String, clubs: String): Boolean = useTyped(children, clubs, "")

    /** [useTyped] with the partner's name as typed in About you ("Sam"; blank for none). */
    fun useTyped(children: String, clubs: String, partner: String): Boolean = use(AboutYou(
        children = children.split('\n').mapNotNull { AboutYouRules.parseChild(it) },
        clubs = AboutYouRules.clubs(clubs),
        partnerName = partner.trim().takeIf { it.isNotEmpty() },
    ))

    /**
     * The places and the call assistant's number as typed in About you (tests and the Mac's bridge tests; blank for
     * none), keeping the children, clubs and partner already set.
     */
    fun usePlaces(home: String, work: String, assistantNumber: String): Boolean = set(names.copy(
        home = WeatherPlaceRules.normalize(home),
        work = WeatherPlaceRules.normalize(work),
        assistantNumber = assistantNumber.trim().takeIf { it.isNotEmpty() },
    ))

    /** About you's "Your name" as typed (tests and the Mac's bridge tests; blank for none), keeping the rest. */
    fun useShownName(name: String): Boolean = set(names.copy(shown = name.trim().takeIf { it.isNotEmpty() }))

    /** The train route as typed in About you (tests and the Mac's bridge tests; blank for none), keeping the rest. */
    fun useRoute(typed: String): Boolean = set(names.copy(route = RouteRules.parse(typed)))

    /** The council's holidays as his profile would hold them (tests; null for none), keeping the rest. */
    fun useCouncil(cal: TermCalendar?): Boolean = set(names.copy(council = cal))

    /** Sets [n] directly (tests, and [use]); true when it changed. */
    fun set(n: HouseholdNames): Boolean {
        if (n == names) return false
        names = n
        return true
    }
}
