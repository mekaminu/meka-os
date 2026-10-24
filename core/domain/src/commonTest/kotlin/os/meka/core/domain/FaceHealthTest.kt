package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meka 2026-10-24 13:12: Health says where Obi's face files are, and every row opens its screen. */
class FaceHealthTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 24)
    private val now = day * CivilDate.DAY_MS + (13 * 60 + 10) * HealthRules.MIN
    private val looked = now - 5 * HealthRules.MIN // 13:05
    private val all = FaceFileRules.FILES

    private fun line(h: FaceHealth, mac: Boolean = false) = FaceHealthRules.line(h, mac, now, cal)

    @Test
    fun theLineSaysWhereTheFilesAre() {
        assertEquals("Face · 7 of 7 files on this phone", line(FaceHealth(true, 7, FaceCheck(looked, all))))
        assertEquals("Face · 7 of 7 files on this Mac", line(FaceHealth(true, 7, null), mac = true))
        assertEquals("Face · off", line(FaceHealth(false, 0, null)))
        assertEquals("Face · waiting: 0 of 7 on the server (last looked 13:05)", line(FaceHealth(true, 0, FaceCheck(looked, emptyList()))))
        assertEquals(
            "Face · server has 3 of 7: missing mouth-rest.png, mouth-f.png, mouth-ee.png, mouth-ah.png (last looked 13:05)",
            line(FaceHealth(true, 0, FaceCheck(looked, listOf("face-loop.mp4", "mouth-m.png", "mouth-oh.png")))),
        )
        assertEquals("Face · couldn't reach the server (last looked 13:05)", line(FaceHealth(true, 0, FaceCheck(looked, null))))
        assertEquals("Face · not looked for yet · 0 of 7 on this phone", line(FaceHealth(true, 0, null)))
        assertEquals(
            "Face · server has 7 of 7, 6 on this phone: face-loop.mp4 didn't download (last looked 13:05)",
            line(FaceHealth(true, 6, FaceCheck(looked, all, listOf("face-loop.mp4")))),
        )
    }

    @Test
    fun onlyAServerWithEveryFileAndAPhoneWithoutIsAFault() {
        assertTrue(FaceHealthRules.fault(FaceHealth(true, 6, FaceCheck(looked, all, listOf("face-loop.mp4")))))
        assertFalse(FaceHealthRules.fault(FaceHealth(true, 0, FaceCheck(looked, emptyList()))))
        assertFalse(FaceHealthRules.fault(FaceHealth(true, 7, FaceCheck(looked, all))))
        assertFalse(FaceHealthRules.fault(FaceHealth(false, 0, FaceCheck(looked, all))))
        assertTrue(FaceHealthRules.lookAgain(FaceHealth(true, 7, null)))
        assertFalse(FaceHealthRules.lookAgain(FaceHealth(false, 0, null)))
        assertFalse(FaceHealthRules.lookAgain(null))
    }

    @Test
    fun theCheckRoundTripsAndDropsAnythingOutsideTheSeven() {
        val c = FaceCheck(looked, listOf("mouth-m.png", "secret.txt", "face-loop.mp4"), listOf("face-loop.mp4", "../x"))
        assertEquals(FaceCheck(looked, listOf("face-loop.mp4", "mouth-m.png"), listOf("face-loop.mp4")), FaceHealthRules.decode(FaceHealthRules.encode(c)))
        assertEquals(FaceCheck(looked, null), FaceHealthRules.decode(FaceHealthRules.encode(FaceCheck(looked, null))))
        assertEquals(FaceCheck(looked, emptyList()), FaceHealthRules.decode(FaceHealthRules.encode(FaceCheck(looked, emptyList()))))
        assertNull(FaceHealthRules.decode(null))
        assertNull(FaceHealthRules.decode("not a time\nok"))
        assertNull(FaceHealthRules.decode("12\nmaybe"))
    }

    /** Meka 2026-10-24 19:26: "0 of 7 on the server" must say which: a 404, a refused storage listing, an empty folder. */
    @Test
    fun theLineSaysWhatTheServerAnsweredAndItSurvivesTheDevicesStorage() {
        assertEquals(
            "Face · waiting: 0 of 7 on the server: server answered 404 to /v1/face/manifest (last looked 13:05)",
            line(FaceHealth(true, 0, FaceCheck(looked, emptyList(), why = "server answered 404 to /v1/face/manifest"))),
        )
        assertEquals(
            "Face · waiting: 0 of 7 on the server: couldn't list its storage: AccessDenied (last looked 13:05)",
            line(FaceHealth(true, 0, FaceCheck(looked, emptyList(), why = "couldn't list its storage: AccessDenied"))),
        )
        assertEquals(
            "Face · couldn't reach the server: HTTP 502 from /v1/face/manifest (last looked 13:05)",
            line(FaceHealth(true, 0, FaceCheck(looked, null, why = "HTTP 502 from /v1/face/manifest"))),
        )
        val c = FaceCheck(looked, emptyList(), why = "nothing under face/ in storage · top-level folders: voice/,\nvault/")
        assertEquals(c.copy(why = "nothing under face/ in storage · top-level folders: voice/, vault/"), FaceHealthRules.decode(FaceHealthRules.encode(c)))
        assertEquals(FaceCheck(looked, emptyList(), why = null), FaceHealthRules.decode(FaceHealthRules.encode(FaceCheck(looked, emptyList(), why = "  "))))
        assertEquals(FaceHealthRules.WHY_MAX, FaceHealthRules.decode(FaceHealthRules.encode(FaceCheck(looked, emptyList(), why = "x".repeat(400))))!!.why!!.length)
    }

    private val server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now)

    private fun view(device: DeviceHealth) = HealthRules.view(
        HealthFacts(
            device = device, connected = true, lastSyncedMs = now, server = server,
            accounts = listOf(HealthAccount("google", "a@example.com", "Personal", "ok", now), HealthAccount("weather", "home", "Weather", "ok", now)),
            ai = AiStatusView("On", lit = false, canAsk = true), callAssistantOn = true, nowMs = now,
            aboutYou = null, bankSync = null,
        ),
        cal,
    )

    @Test
    fun obisVoiceCarriesTheFaceLineAndLookAgain() {
        val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, callRoleHeld = true,
            face = FaceHealth(true, 0, FaceCheck(looked, emptyList())))
        val voice = view(fold).rows.first { it.key == "voice" }
        assertEquals("Face · waiting: 0 of 7 on the server (last looked 13:05)", voice.detail)
        assertEquals(HealthFix.FACE_LOOK_AGAIN, voice.fix)
        assertEquals("Look again", voice.fixLabel)
        // Waiting for his upload isn't something broken.
        assertEquals(HealthState.OK, voice.state)
        // The server has them all and the phone doesn't: worth a look.
        val stuck = view(fold.copy(face = FaceHealth(true, 6, FaceCheck(looked, all, listOf("face-loop.mp4"))))).rows.first { it.key == "voice" }
        assertEquals(HealthState.WARN, stuck.state)
        // No face facts (an older build): no line, no button.
        val plain = view(fold.copy(face = null)).rows.first { it.key == "voice" }
        assertNull(plain.detail)
        assertNull(plain.fix)
    }

    @Test
    fun everyRowWithAScreenOpensItAndTheRestDont() {
        val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, callRoleHeld = true, version = "0.1.9")
        val places = view(fold).rows.associate { it.key to it.place }
        assertEquals(HealthPlace.VOICE, places["voice"])
        assertEquals(HealthPlace.CALENDARS, places["calendar:google:a@example.com"])
        assertEquals(HealthPlace.CALENDARS, places["feeds"])
        assertEquals(HealthPlace.WORK, places["calls"])
        assertEquals(HealthPlace.WORK, places["spam"])
        assertEquals(HealthPlace.SYSTEM_BATTERY, places["battery"])
        assertNull(places["server"])
        assertNull(places["update"])
        assertNull(places["ai"])
        assertEquals(HealthPlace.MONEY, HealthRules.place(MoneyBank.HEALTH_KEY, mac = false))
        assertEquals(HealthPlace.ABOUT_YOU, HealthRules.place(RouteRules.HEALTH_KEY, mac = true))
        assertEquals(HealthPlace.ABOUT_YOU, HealthRules.place(AboutYouRules.HEALTH_KEY, mac = true))
        assertEquals(HealthPlace.MAIL, HealthRules.place(MailHealthRules.KEY_PREFIX + "google:a@example.com", mac = true))
        assertEquals(HealthPlace.NOTIFICATIONS, HealthRules.place("notifications", mac = true))
        // The Mac can't open Android's pages.
        assertNull(HealthRules.place("battery", mac = true))
        assertNull(HealthRules.place("messages", mac = true))
        assertEquals(HealthPlace.SYSTEM_LISTENER, HealthRules.place("messages", mac = false))
    }

    /** Meka 2026-10-24 19:29: Health names the voice Obi speaks in, and old clips in another voice are dropped. */
    @Test
    fun oneVoiceEverywhere() {
        assertEquals("Speaking as Brian", HealthRules.voiceInUseLine(" Brian "))
        assertNull(HealthRules.voiceInUseLine(null))
        assertNull(HealthRules.voiceInUseLine(""))
        val by = mapOf("|Morning, Meka." to "Amy", "|Here's the gist" to "Brian", "Arthur|Morning, Meka." to "Arthur", "|Old" to null)
        assertEquals(setOf("|Morning, Meka."), SpeechRules.stale(by, null, "brian"))
        assertEquals(emptySet(), SpeechRules.stale(by, null, null), "an older server that doesn't say: nothing dropped")
        assertEquals(emptySet(), SpeechRules.stale(by, "Arthur", "Arthur"))
    }
}
