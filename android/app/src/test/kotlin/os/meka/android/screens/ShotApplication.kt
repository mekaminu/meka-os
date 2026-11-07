package os.meka.android.screens

import os.meka.android.MekaApplication

/**
 * The quality gate's stand-in for [MekaApplication] (slice 3, Today's shots): screens such as Today's Needs you column
 * read the app object (its capture store, its core), so a plain Application can't draw them; the real one opens the
 * device's keystore and encrypted database, which Robolectric has neither of. This one starts nothing; each test hands it
 * a core with invented data ([MekaApplication.useCoreForScreens]). Health Connect reads as not on the phone.
 */
class ShotApplication : MekaApplication() {
    override fun onCreate() {
        // Robolectric has no Health Connect service and reports it missing from a background thread once the client
        // is made (CI runs 1477, 1551, 1552, 1601, 1602): in the shots Health Connect isn't on this phone.
        os.meka.android.goals.HealthWorkouts.availableForTests = false
    }
}
