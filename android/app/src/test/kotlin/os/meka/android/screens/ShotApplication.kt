package os.meka.android.screens

import os.meka.android.MekaApplication

/**
 * The quality gate's stand-in for [MekaApplication] (slice 3, Today's shots): screens such as Today's Needs you column
 * read the app object (its capture store, its core), so a plain Application can't draw them; the real one opens the
 * device's keystore and encrypted database, which Robolectric has neither of. This one starts nothing; each test hands it
 * a core with invented data ([MekaApplication.useCoreForScreens]).
 */
class ShotApplication : MekaApplication() {
    override fun onCreate() = Unit
}
