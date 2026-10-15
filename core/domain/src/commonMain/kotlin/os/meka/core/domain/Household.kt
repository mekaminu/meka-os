package os.meka.core.domain

import kotlin.concurrent.Volatile

/**
 * Who the household's clubs and children are, as the rules read them (rule 13, release 2 slice 1, 2026-10-15): the
 * children by club age group and the clubs' short names, taken from About you ([AboutYou.children] and
 * [AboutYou.clubs]) and never from the code. [FootballRules] and [ClubChildRules] read [Household.names]; the facade
 * sets it from the synced profile on every refresh ([Household.use]), so a child or club added on the Mac reaches the
 * Fold's fixtures after a sync, and an empty profile means no child is named and no club fixture is spotted.
 */
data class HouseholdNames(
    /** Age group → the child's first name ("7" → "Alex"); a child without an age group isn't here. */
    val children: Map<Int, String> = emptyMap(),
    /** The clubs' short names, upper case, as calendar titles write them ("OFC"). */
    val clubs: List<String> = emptyList(),
    /** Short name → the club's full name, said aloud ("OFC" → "Oakfield Town"); only clubs with one. */
    val clubNames: Map<String, String> = emptyMap(),
) {
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
    fun useTyped(children: String, clubs: String): Boolean = use(AboutYou(
        children = children.split('\n').mapNotNull { AboutYouRules.parseChild(it) },
        clubs = AboutYouRules.clubs(clubs),
    ))

    /** Sets [n] directly (tests, and [use]); true when it changed. */
    fun set(n: HouseholdNames): Boolean {
        if (n == names) return false
        names = n
        return true
    }
}
