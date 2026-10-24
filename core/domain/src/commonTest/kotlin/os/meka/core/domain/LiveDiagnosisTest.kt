package os.meka.core.domain

import os.meka.core.domain.LiveDiagnosisRules.KeyState
import os.meka.core.domain.LiveDiagnosisRules.LiveFallback
import os.meka.core.domain.LiveDiagnosisRules.LiveOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meka 2026-10-24 19:31 + 19:38: Live On still takes 10 s a turn: Health and the stage say Live or why not. */
class LiveDiagnosisTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 24)
    private val now = day * CivilDate.DAY_MS + (21 * 60 + 45) * HealthRules.MIN
    private val at = now - 5 * HealthRules.MIN // 21:40

    @Test
    fun withATokenGoogleGetsSixSecondsElseTwo() {
        assertFalse(LiveDiagnosisRules.fallBack(0, 1_999, setUp = false, tokenIn = false))
        assertTrue(LiveDiagnosisRules.fallBack(0, 2_000, setUp = false, tokenIn = false))
        assertFalse(LiveDiagnosisRules.fallBack(0, 5_999, setUp = false, tokenIn = true))
        assertTrue(LiveDiagnosisRules.fallBack(0, 6_000, setUp = false, tokenIn = true))
        assertFalse(LiveDiagnosisRules.fallBack(0, 60_000, setUp = true, tokenIn = true))
    }

    @Test
    fun keysAreNeverShown() {
        val key = "AQ.Ab8RN6Jx2kQpZt0vYw3sLmN4oPqRsTuV"
        assertEquals("API key … not valid", LiveDiagnosisRules.redact("API key $key not valid", key))
        assertEquals("key AQ.… refused", LiveDiagnosisRules.redact("key AQ.Zz9yX8wV7uT6s refused"))
        assertEquals("key AIza… refused", LiveDiagnosisRules.redact("key AIzaSyA1b2C3d4E5f6 refused"))
        assertEquals("token …", LiveDiagnosisRules.redact("token " + "a".repeat(40)))
        assertNull(LiveDiagnosisRules.redact("   "))
        assertEquals(LiveDiagnosisRules.MAX_DETAIL, LiveDiagnosisRules.redact("word ".repeat(100))!!.length)
    }

    @Test
    fun theKeyLineQuotesGoogle() {
        assertEquals("Live ready", LiveDiagnosisRules.keyLine(KeyState.READY, 200, null))
        assertEquals("Live · Google said 403: PERMISSION_DENIED: Permission denied", LiveDiagnosisRules.keyLine(KeyState.REFUSED, 403, "PERMISSION_DENIED: Permission denied"))
        assertEquals("Live · no key on Navilife's server (meka-os-dev/ai/gemini is empty)", LiveDiagnosisRules.keyLine(KeyState.NO_KEY, null, null))
        assertEquals("Live · couldn't reach Google (HTTP 503)", LiveDiagnosisRules.keyLine(KeyState.UNREACHABLE, 503, null))
        assertTrue(LiveDiagnosisRules.keyFault(KeyState.REFUSED))
        assertFalse(LiveDiagnosisRules.keyFault(KeyState.READY))
        assertFalse(LiveDiagnosisRules.keyFault(KeyState.UNCHECKED))
        assertEquals("Google said 401: UNAUTHENTICATED", LiveDiagnosisRules.settingsLine(KeyState.REFUSED, 401, "UNAUTHENTICATED"))
        assertNull(LiveDiagnosisRules.settingsLine(KeyState.READY, 200, null))
    }

    @Test
    fun aRefusalNamesTheConsoleStep() {
        fun step(code: Int, detail: String) = LiveDiagnosisRules.consoleStep(KeyState.REFUSED, code, detail)
        assertTrue(step(403, "PERMISSION_DENIED (SERVICE_DISABLED): Generative Language API has not been used in project 12 before or it is disabled.")!!.contains("Enable"))
        assertTrue(step(403, "PERMISSION_DENIED (API_KEY_SERVICE_BLOCKED): Requests to this API are blocked.")!!.contains("API restrictions"))
        assertTrue(step(403, "PERMISSION_DENIED (IAM_PERMISSION_DENIED): Permission denied on resource")!!.contains("IAM"))
        assertTrue(step(400, "INVALID_ARGUMENT (API_KEY_INVALID): API key not valid. Please pass a valid API key.")!!.contains("AI Studio"))
        assertTrue(step(404, "NOT_FOUND: models/gemini-x is not found for API version v1beta")!!.contains("MEKA_LIVE_MODEL"))
        assertTrue(step(429, "RESOURCE_EXHAUSTED: Quota exceeded")!!.contains("Billing"))
        assertNull(step(400, "INVALID_ARGUMENT: something else"))
        assertNotNull(LiveDiagnosisRules.consoleStep(KeyState.BAD_SECRET, null, null))
        assertNull(LiveDiagnosisRules.consoleStep(KeyState.UNREACHABLE, 503, null))
    }

    @Test
    fun theStageSaysWhyNormalObiAnswered() {
        assertEquals(LiveFallback.REFUSED, LiveDiagnosisRules.fromSession("failed", "Google said 403: blocked"))
        assertEquals(LiveFallback.NO_ANSWER, LiveDiagnosisRules.fromSession("failed", null))
        assertEquals(LiveFallback.NO_ANSWER, LiveDiagnosisRules.fromSession(null, null))
        assertEquals(LiveFallback.NO_KEY, LiveDiagnosisRules.fromSession("off", "Live needs the Gemini key on Navilife's server"))
        assertEquals(LiveFallback.OVER, LiveDiagnosisRules.fromSession("over", null))
        assertNull(LiveDiagnosisRules.fromSession("ready", null))
        assertEquals("Normal Obi · Live unavailable: Google said 403: blocked", LiveDiagnosisRules.stageLine(LiveFallback.REFUSED, "Google said 403: blocked"))
        assertEquals("Normal Obi · Live unavailable: Google took over 6 s to open the session", LiveDiagnosisRules.stageLine(LiveFallback.SLOW_GOOGLE, null))
        assertEquals("closed 1008: model not found", LiveDiagnosisRules.closeLine(1008, " model not found "))
        assertNull(LiveDiagnosisRules.closeLine(1000, ""))
        assertEquals("closed 1011", LiveDiagnosisRules.closeLine(1011, null))
    }

    @Test
    fun healthKeepsTheLastTalk() {
        val fell = LiveOutcome(at, false, LiveFallback.CLOSED, "closed 1008: model not found")
        assertEquals("Last talk 21:40 · normal Obi: Google closed the connection before it was set up (closed 1008: model not found)", LiveDiagnosisRules.healthLine(fell, now, cal))
        assertEquals("Last talk 21:40 · Live", LiveDiagnosisRules.healthLine(LiveOutcome(at, true, null, null), now, cal))
        assertEquals(fell, LiveDiagnosisRules.decode(LiveDiagnosisRules.encode(fell)))
        assertEquals(LiveOutcome(at, true, null, null), LiveDiagnosisRules.decode(LiveDiagnosisRules.encode(LiveOutcome(at, true, null, null))))
        assertNull(LiveDiagnosisRules.decode("rubbish"))
        assertNull(LiveDiagnosisRules.decode("12\tNOPE\t"))
        val h = LiveHealth(true, KeyState.REFUSED, 403, "PERMISSION_DENIED: blocked", fell)
        assertEquals(
            "Live · Google said 403: PERMISSION_DENIED: blocked\nLast talk 21:40 · normal Obi: Google closed the connection before it was set up (closed 1008: model not found)",
            LiveDiagnosisRules.health(h, now, cal),
        )
        assertNull(LiveDiagnosisRules.health(h.copy(on = false), now, cal))
        assertEquals("Live · couldn't ask Navilife's server", LiveDiagnosisRules.health(LiveHealth(true, null), now, cal))
    }

    @Test
    fun healthsVoiceRowCarriesLiveAndTurnsAmberOnARefusal() {
        val server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now)
        fun voice(live: LiveHealth?) = HealthRules.view(
            HealthFacts(
                device = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, callRoleHeld = true),
                connected = true, lastSyncedMs = now, server = server, accounts = emptyList(),
                ai = AiStatusView("On", lit = false, canAsk = true), callAssistantOn = true, nowMs = now, live = live,
            ),
            cal,
        ).rows.first { it.key == "voice" }
        val refused = voice(LiveHealth(true, KeyState.REFUSED, 403, "PERMISSION_DENIED: blocked"))
        assertEquals("Live · Google said 403: PERMISSION_DENIED: blocked", refused.detail)
        assertEquals(HealthState.WARN, refused.state)
        val ready = voice(LiveHealth(true, KeyState.READY, last = LiveOutcome(at, true, null, null)))
        assertEquals("Live ready\nLast talk 21:40 · Live", ready.detail)
        assertEquals(HealthState.OK, ready.state)
        assertNull(voice(null).detail)
    }
}
