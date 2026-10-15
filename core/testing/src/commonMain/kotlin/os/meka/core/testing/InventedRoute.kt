package os.meka.core.testing

/**
 * An invented train route for tests (rule 13: no real line or station on his commute in the code; the app reads his
 * from About you's Train route). TfL ids: [FIRST] "south-rail", [FALLBACK] "north-rail", [ONWARD] "metro".
 */
object InventedRoute {
    const val TYPED = "South Rail to Oakfield Junction, then Metro line; or North Rail to Riverside"
    const val FIRST = "south-rail"
    const val FALLBACK = "north-rail"
    const val ONWARD = "metro"
}
