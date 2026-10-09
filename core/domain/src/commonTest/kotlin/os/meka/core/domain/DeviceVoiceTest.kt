package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The device's own voice listed one by one, with speed and pitch (Weather and a voice, slice 10). */
class DeviceVoiceTest {
    private val google = "Speech Services by Google"
    private val phone = listOf(
        DeviceVoice("en-us-x-iol-local", "", "en-US", 400, google),
        DeviceVoice("en-gb-x-gbd-network", "", "en-GB", 500, google, needsNetwork = true),
        DeviceVoice("en-gb-x-rjs-local", "", "en_GB", 300, google),
        DeviceVoice("en-gb-x-gba-local", "", "en-GB", 400, google),
        DeviceVoice("en-gb-x-gbc-local", "", "en-GB", 500, google, installed = false),
        DeviceVoice("fr-fr-x-local", "", "fr-FR", 500, google),
        DeviceVoice("en-au-x-aua-local", "", "en-AU", 500, google),
    )

    @Test
    fun thePhonesVoicesAreListedBestFirstWithEngineAccentAndQuality() {
        val v = DeviceVoiceRules.view(phone, DeviceVoiceSettings.DEFAULT, mac = false)
        assertEquals("This phone's voices", v.title)
        assertEquals(listOf("", "en-gb-x-gba-local", "en-gb-x-rjs-local", "en-au-x-aua-local", "en-us-x-iol-local"), v.rows.map { it.id })
        assertEquals("Automatic", v.rows[0].label)
        assertEquals("MEKA picks the best installed · now Voice GBA", v.rows[0].detail)
        assertTrue(v.rows[0].selected)
        assertEquals("Voice GBA", v.rows[1].label)
        assertEquals("Speech Services by Google · British · high quality", v.rows[1].detail)
        assertEquals("Speech Services by Google · Australian · very high quality", v.rows[3].detail)
        assertEquals(emptyList(), v.more)
        assertNull(v.moreLabel)
        assertNull(v.emptyLine)
        assertEquals("Speed · normal", v.rateLine)
        assertEquals("Pitch · normal", v.pitchLine)
        // The same order MEKA speaks with.
        assertEquals(TalkVoice.best(phone.map { it.candidate() })?.name, DeviceVoiceRules.pick(phone, DeviceVoiceSettings.DEFAULT)?.name)
    }

    @Test
    fun aChosenVoiceIsLitAndSpokenWithUntilItIsNoLongerInstalled() {
        val s = DeviceVoiceSettings(voice = "en-us-x-iol-local")
        val v = DeviceVoiceRules.view(phone, s, mac = false)
        assertEquals(listOf("en-us-x-iol-local"), v.rows.filter { it.selected }.map { it.id })
        assertEquals("en-us-x-iol-local", DeviceVoiceRules.pick(phone, s)?.name)
        val gone = phone.filter { it.name != "en-us-x-iol-local" }
        assertEquals("en-gb-x-gba-local", DeviceVoiceRules.pick(gone, s)?.name)
        assertEquals(listOf(""), DeviceVoiceRules.view(gone, s, mac = false).rows.filter { it.selected }.map { it.id })
        // A network voice can't be chosen into use: the words would be sent away.
        assertEquals("en-gb-x-gba-local", DeviceVoiceRules.pick(phone, DeviceVoiceSettings(voice = "en-gb-x-gbd-network"))?.name)
    }

    @Test
    fun manyVoicesShowSixThenMoreAndAChosenOneFurtherDownStaysInSight() {
        val many = (1..9).map { DeviceVoice("en-us-x-v${it}-local", "", "en-US", 300, google) }
        val v = DeviceVoiceRules.view(many, DeviceVoiceSettings.DEFAULT, mac = false)
        assertEquals(1 + DeviceVoiceRules.SHOWN, v.rows.size)
        assertEquals("+3 more", v.moreLabel)
        assertEquals(3, v.more.size)
        val far = DeviceVoiceRules.view(many, DeviceVoiceSettings(voice = "en-us-x-v8-local"), mac = false)
        assertEquals(1 + 8, far.rows.size)
        assertEquals("+1 more", far.moreLabel)
        val open = DeviceVoiceRules.view(many, DeviceVoiceSettings.DEFAULT, mac = false, expanded = true)
        assertEquals(10, open.rows.size)
        assertNull(open.moreLabel)
    }

    @Test
    fun theMacsVoicesUseTheirNamesAndPremiumEnhanced() {
        val mac = listOf(
            DeviceVoice("com.apple.voice.compact.en-GB.Daniel", "Daniel", "en-GB", 1, "Apple"),
            DeviceVoice("com.apple.voice.premium.en-GB.Serena", "Serena (Premium)", "en-GB", 3, "Apple"),
            DeviceVoice("com.apple.voice.enhanced.en-IE.Moira", "Moira", "en-IE", 2, "Apple"),
        )
        val v = DeviceVoiceRules.view(mac, DeviceVoiceSettings.DEFAULT, mac = true)
        assertEquals("This Mac's voices", v.title)
        assertEquals(listOf("Automatic", "Serena", "Daniel", "Moira"), v.rows.map { it.label })
        assertEquals("Apple · British · Premium", v.rows[1].detail)
        assertEquals("Apple · British · standard quality", v.rows[2].detail)
        assertEquals("Apple · Irish · Enhanced", v.rows[3].detail)
        assertTrue(v.note.contains("the Mac's own voice"))
        assertEquals(
            "No English voice is installed on this Mac yet, so the system's default speaks.",
            DeviceVoiceRules.view(emptyList(), DeviceVoiceSettings.DEFAULT, mac = true).emptyLine,
        )
        assertEquals("The engine's own voice", DeviceVoiceRules.view(emptyList(), DeviceVoiceSettings.DEFAULT, mac = false).rows.single().detail)
    }

    @Test
    fun speedAndPitchStayInRangeOnStepsAndSayWhatTheyDo() {
        assertEquals(1.6f, DeviceVoiceRules.rate(3f))
        assertEquals(0.6f, DeviceVoiceRules.rate(0.1f))
        assertEquals(1.2f, DeviceVoiceRules.rate(1.19f))
        assertEquals(0.75f, DeviceVoiceRules.pitch(0.2f))
        assertEquals("Speed · 1.2× faster", DeviceVoiceRules.rateLine(1.2f))
        assertEquals("Speed · 0.8× slower", DeviceVoiceRules.rateLine(0.8f))
        assertEquals("Speed · 1.25× faster", DeviceVoiceRules.rateLine(1.25f))
        assertEquals("Speed · normal", DeviceVoiceRules.rateLine(1.01f))
        assertEquals("Pitch · 10% higher", DeviceVoiceRules.pitchLine(1.1f))
        assertEquals("Pitch · 15% lower", DeviceVoiceRules.pitchLine(0.85f))
        assertEquals(0.5f, DeviceVoiceRules.macRate(1f))
        assertEquals(0.6f, DeviceVoiceRules.macRate(1.2f))
    }

    @Test
    fun theSettingsAreKeptAsOneLineOnTheDeviceAndAnythingOddReadsAsTheDefault() {
        val s = DeviceVoiceSettings("en-gb-x-gba-local", 1.1f, 0.95f)
        assertEquals("v=en-gb-x-gba-local;r=1.1;p=0.95", DeviceVoiceRules.encode(s))
        assertEquals(s, DeviceVoiceRules.decode(DeviceVoiceRules.encode(s)))
        assertEquals("r=1.0;p=1.0", DeviceVoiceRules.encode(DeviceVoiceSettings.DEFAULT))
        assertEquals(DeviceVoiceSettings.DEFAULT, DeviceVoiceRules.decode(DeviceVoiceRules.encode(DeviceVoiceSettings.DEFAULT)))
        assertEquals(DeviceVoiceSettings.DEFAULT, DeviceVoiceRules.decode(null))
        assertEquals(DeviceVoiceSettings.DEFAULT, DeviceVoiceRules.decode("garbage"))
        assertEquals(DeviceVoiceSettings(null, 1.6f, 1f), DeviceVoiceRules.decode("r=9;p=x"))
        assertEquals("com.apple.voice.premium.en-GB.Serena", DeviceVoiceRules.decode("v=com.apple.voice.premium.en-GB.Serena;r=1.0;p=1.0").voice)
    }
}
