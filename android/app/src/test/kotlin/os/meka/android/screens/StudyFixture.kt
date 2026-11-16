package os.meka.android.screens

/**
 * Walk-through 2026-11-15 A13 (fixture facts that disagree across screens): Study's shots share one invented course and
 * the same today as Board, Calendar and Today (Wed 21 Oct), so the course page and the link that imports it show the
 * same course ("Data analytics certificate", 3 modules, 8 lessons) and the plan's day chips agree with the Board's
 * week. Invented course (rule 13).
 */
internal object StudyFixture {
    /** Wed 21 Oct 2026, 10:00 in London (epoch day [TODAY]). */
    const val NOW = 1_792_573_200_000L
    const val TODAY = 20_747L

    const val TITLE = "Data analytics certificate"

    val MODULES: List<Pair<String, List<String>>> = listOf(
        "Module 1: Foundations" to listOf("Welcome and setup 30 min", "Spreadsheets refresher 45 min", "Cleaning data 45 min"),
        "Module 2: Core skills" to listOf("Pivot tables 1 h", "Charts that say something 40 min", "Lookups 50 min"),
        "Module 3: Putting it together" to listOf("A small project 1 h", "Presenting findings 45 min"),
    )

    /** The course as a pasted outline: each module's line, then its lessons. */
    val OUTLINE: String = MODULES.joinToString("\n") { (m, lessons) -> (listOf(m) + lessons).joinToString("\n") }
}
