package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talk without tapping the mic, slice 1: the side button and the headphones' button open MEKA listening. */
class TalkStartTest {
    @Test
    fun theAssistGestureAndTheHeadsetButtonStartTalkNothingElseDoes() {
        assertEquals(TalkStart.SIDE_BUTTON, TalkStartRules.fromAction("android.intent.action.ASSIST"))
        assertEquals(TalkStart.HEADSET_BUTTON, TalkStartRules.fromAction("android.intent.action.VOICE_COMMAND"))
        assertNull(TalkStartRules.fromAction("android.intent.action.MAIN"))
        assertNull(TalkStartRules.fromAction(null))
    }

    @Test
    fun theStartTravelsToTheMainScreenAndBack() {
        for (s in TalkStart.entries) assertEquals(s, TalkStartRules.fromOpen(TalkStartRules.openValue(s)))
        assertEquals("talk:SIDE_BUTTON", TalkStartRules.openValue(TalkStart.SIDE_BUTTON))
        assertNull(TalkStartRules.fromOpen("talk:WAKE_WORD"))
        assertNull(TalkStartRules.fromOpen("news:"))
        assertNull(TalkStartRules.fromOpen(null))
    }

    @Test
    fun notTheAssistantYetSaysHowOnASamsungAndOffersDefaultApps() {
        val v = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = true)
        assertEquals("Talk", v.title)
        assertEquals(listOf("Side button", "Headphones and the car", "Home screen", "Bedside and the cover screen", "Safety"), v.sections.map { it.label })
        val side = v.sections[0]
        assertFalse(side.lit)
        assertEquals("Not set up yet: holding the side key opens another assistant.", side.status)
        assertEquals(2, side.steps.size)
        assertTrue(side.steps[0].startsWith("Settings → Apps → Choose default apps → Digital assistant app"))
        assertTrue(side.steps[1].contains("Side button → Press and hold → Digital assistant"))
        assertEquals("Open default apps", side.action)
        // Another phone: Android's own menu names, one step.
        val pixel = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = false).sections[0]
        assertEquals(listOf("Settings → Apps → Default apps → Digital assistant app → MEKA."), pixel.steps)
    }

    @Test
    fun onceTheAssistantTheSideButtonIsLitAndNeedsNoButton() {
        val side = TalkStartRules.setup(mac = false, assistantHeld = true, samsung = true).sections[0]
        assertTrue(side.lit)
        assertTrue(side.status.startsWith("MEKA is this phone's digital assistant"))
        assertNull(side.action)
        assertTrue(side.steps.single().contains("Bixby"))
        assertTrue(TalkStartRules.setup(mac = false, assistantHeld = true, samsung = false).sections[0].steps.isEmpty())
    }

    @Test
    fun theMacSaysOptionSpaceAndKeepsTheSafetyLine() {
        val v = TalkStartRules.setup(mac = true)
        assertEquals(listOf("On the Mac", "Safety"), v.sections.map { it.label })
        assertTrue(v.sections[0].status.contains("⌥Space"))
        assertTrue(v.sections.all { it.action == null })
        assertTrue(v.sections[1].status.contains("Undo"))
    }

    @Test
    fun openingFromTheLauncherListensWithBluetoothAudioOrInTheCarOnly() {
        assertEquals(TalkStart.HEADPHONES, TalkStartRules.onOpen(fromLauncher = true, bluetoothAudio = true, carMode = false))
        assertEquals(TalkStart.CAR, TalkStartRules.onOpen(fromLauncher = true, bluetoothAudio = true, carMode = true))
        assertEquals(TalkStart.CAR, TalkStartRules.onOpen(fromLauncher = true, bluetoothAudio = false, carMode = true))
        // The phone's own speaker: Ask waits for the mic.
        assertNull(TalkStartRules.onOpen(fromLauncher = true, bluetoothAudio = false, carMode = false))
        // A notification (or anything but the launcher) never starts listening, headphones or not.
        assertNull(TalkStartRules.onOpen(fromLauncher = false, bluetoothAudio = true, carMode = true))
    }

    @Test
    fun theWidgetTravelsLikeTheButtonsAndThePaneSaysHowToAddIt() {
        assertEquals("talk:WIDGET", TalkStartRules.openValue(TalkStart.WIDGET))
        assertEquals(TalkStart.WIDGET, TalkStartRules.fromOpen("talk:WIDGET"))
        val v = TalkStartRules.setup(mac = false, assistantHeld = true, samsung = true)
        val home = v.sections.single { it.label == "Home screen" }
        assertTrue(home.steps.single().endsWith("Widgets → MEKA → Talk to MEKA."))
        val phones = v.sections.single { it.label == "Headphones and the car" }
        assertTrue(phones.status.contains("Bluetooth headphones"))
        assertTrue(phones.steps.any { it.contains("notification never starts listening") })
        assertEquals("Safety", v.sections.last().label)
    }

    @Test
    fun theBedsideAndCoverMicsStartListeningAndThePaneSaysWhere() {
        assertEquals(TalkStart.BEDSIDE, TalkStartRules.fromMic(bedside = true))
        assertEquals(TalkStart.COVER, TalkStartRules.fromMic(bedside = false))
        assertEquals("talk:COVER", TalkStartRules.openValue(TalkStart.COVER))
        assertEquals(TalkStart.BEDSIDE, TalkStartRules.fromOpen("talk:BEDSIDE"))
        val v = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = true)
        val mics = v.sections.single { it.label == "Bedside and the cover screen" }
        assertTrue(mics.status.contains("bedside clock") && mics.status.contains("now card"))
        assertNull(mics.action)
        assertFalse(mics.lit)
        // The Mac has neither screen.
        assertTrue(TalkStartRules.setup(mac = true).sections.none { it.label.contains("Bedside") })
    }
}

/** Talk without tapping the mic, slice 4: "Listen when I open MEKA" (off by default). */
class TalkOnOpenTest {
    @Test
    fun listenOnOpenStartsOnlyFromTheLauncherWithTheSettingOnAndTheMicAllowed() {
        assertEquals(TalkStart.OPEN, TalkStartRules.openStart(fromLauncher = true, bluetoothAudio = false, carMode = false, listenOnOpen = true, micAllowed = true))
        // Off by default: nothing.
        assertNull(TalkStartRules.openStart(fromLauncher = true, bluetoothAudio = false, carMode = false, listenOnOpen = false, micAllowed = true))
        // A notification (not a launcher open) never listens.
        assertNull(TalkStartRules.openStart(fromLauncher = false, bluetoothAudio = true, carMode = true, listenOnOpen = true, micAllowed = true))
        // No microphone yet: an open never asks for it.
        assertNull(TalkStartRules.openStart(fromLauncher = true, bluetoothAudio = false, carMode = false, listenOnOpen = true, micAllowed = false))
        // Headphones and the car keep their own start.
        assertEquals(TalkStart.HEADPHONES, TalkStartRules.openStart(fromLauncher = true, bluetoothAudio = true, carMode = false, listenOnOpen = true, micAllowed = true))
        assertEquals(TalkStart.CAR, TalkStartRules.openStart(fromLauncher = true, bluetoothAudio = true, carMode = true, listenOnOpen = false, micAllowed = false))
        assertEquals(TalkStart.OPEN, TalkStartRules.fromOpen(TalkStartRules.openValue(TalkStart.OPEN)))
    }

    @Test
    fun theRoomCheckTellsAQuietRoomFromANoisyOne() {
        assertNull(TalkOnOpenRules.roomLevel(ShortArray(0)))
        assertEquals(TalkOnOpenRules.FLOOR_DB, TalkOnOpenRules.roomLevel(ShortArray(4800)))
        // Full scale ≈ 90.
        val full = TalkOnOpenRules.roomLevel(ShortArray(100) { 32767 })!!
        assertTrue(full > 89.9 && full <= 90.0, "$full")
        // A quiet room: RMS ~100 → about 40.
        val quiet = TalkOnOpenRules.roomLevel(ShortArray(4800) { if (it % 2 == 0) 100 else -100 })!!
        assertTrue(quiet in 39.0..41.0, "$quiet")
        assertFalse(TalkOnOpenRules.tooNoisy(quiet))
        // A loud café: RMS ~2000 → about 66.
        val loud = TalkOnOpenRules.roomLevel(ShortArray(4800) { if (it % 2 == 0) 2000 else -2000 })!!
        assertTrue(loud in 65.0..67.0, "$loud")
        assertTrue(TalkOnOpenRules.tooNoisy(loud))
        // Only what was read counts.
        val part = ShortArray(10) { if (it < 5) 2000 else 0 }
        assertTrue(TalkOnOpenRules.roomLevel(part, 5)!! > 60.0)
        assertFalse(TalkOnOpenRules.tooNoisy(null)) // couldn't measure: listen
        assertFalse(TalkOnOpenRules.tooNoisy(60.0))
        assertEquals("Too noisy — tap to talk", TalkProblem.TOO_NOISY.line)
    }

    @Test
    fun saySomethingWithinSixSecondsOrTodayComesBack() {
        assertFalse(TalkOnOpenRules.windowLapsed(1_000, 6_999, speechBegan = false))
        assertTrue(TalkOnOpenRules.windowLapsed(1_000, 7_000, speechBegan = false))
        assertFalse(TalkOnOpenRules.windowLapsed(1_000, 20_000, speechBegan = true))
        assertEquals(300L, TalkOnOpenRules.ROOM_CHECK_MS)
    }

    @Test
    fun theTalkPaneSectionHasItsSwitchAndLightsWhenOn() {
        val off = TalkOnOpenRules.section(on = false)
        assertEquals("When I open MEKA", off.label)
        assertFalse(off.lit)
        assertEquals("Turn on", off.action)
        assertTrue(off.status.startsWith("Off"))
        assertTrue(off.steps.any { "Too noisy — tap to talk" in it })
        assertTrue(off.steps.any { "notification" in it })
        val on = TalkOnOpenRules.section(on = true)
        assertTrue(on.lit)
        assertEquals("Turn off", on.action)
        assertTrue(on.status.startsWith("On"))
        // The shared setup is unchanged; the Fold adds this section itself.
        assertFalse(TalkStartRules.setup(mac = false).sections.any { it.label == "When I open MEKA" })
    }
}
