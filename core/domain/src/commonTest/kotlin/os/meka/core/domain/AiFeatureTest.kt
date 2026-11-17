package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiFeatureTest {
    @Test
    fun theModeFollowsTheServersStatus() {
        assertEquals(AiMode.ON, AskRules.statusView("on", null, 120, 2000, "ok").mode)
        assertEquals(AiMode.ON, AskRules.statusView("on", null, 1405, 2000, "alert").mode)
        assertEquals(AiMode.OVER, AskRules.statusView("on", null, 2000, 2000, "over").mode)
        assertEquals(AiMode.OFF, AskRules.statusView("off", "No AI key set", 0, 2000, "ok").mode)
        assertEquals(AiMode.FAILING, AskRules.statusView("failing", "timeout", 0, 2000, "ok").mode)
        assertEquals(AiMode.FAILING, AskRules.statusView("on", null, 0, 2000, "ok", keyRefused = true).mode)
        assertEquals(AiMode.OFF, AskRules.statusView("off", null, 0, 2000, "ok", keyRefused = true).mode)
        assertEquals(AiMode.OFFLINE, AskRules.STATUS_UNKNOWN.mode)
        assertEquals(AiMode.NOT_CONNECTED, AskRules.STATUS_NOT_CONNECTED.mode)
    }

    @Test
    fun noLineWhileItAnswersOrBeforeTheDeviceKnows() {
        val on = AskRules.statusView("on", null, 120, 2000, "ok")
        AiFeature.entries.forEach { assertNull(AiFeatureRules.line(it, on)) }
        assertNull(AiFeatureRules.line(AiFeature.BRIEF, null))
        assertTrue(on.fallbacks.isEmpty())
        assertNull(on.fallbackHeading)
    }

    @Test
    fun eachFeatureSaysWhyAndWhatHappensInstead() {
        val off = AskRules.statusView("off", null, 0, 2000, "ok")
        assertEquals(
            "Navilife's AI is off. Your brief is read from your day as it is, in Navilife's own words.",
            AiFeatureRules.line(AiFeature.BRIEF, off),
        )
        val over = AskRules.statusView("on", null, 2000, 2000, "over")
        assertEquals(
            "This month's AI budget is used up till the 1st. Nothing is drafted, so write your reply in the editor and Send works as usual.",
            AiFeatureRules.line(AiFeature.DRAFTS, over),
        )
        assertEquals(
            "Can't reach Navilife's AI just now. Notes are kept with your words; tap Make cards on the note once the AI is back.",
            AiFeatureRules.line(AiFeature.VOICE_NOTES, AskRules.STATUS_UNKNOWN),
        )
        assertTrue(AiFeatureRules.line(AiFeature.CALLS, AskRules.statusView("failing", null, 0, 2000, "ok"))!!.startsWith("Navilife's AI isn't answering. Messages still come "))
    }

    @Test
    fun settingsListsWhatStillWorksUnderAHeadingForTheMode() {
        val off = AskRules.statusView("off", null, 0, 2000, "ok")
        assertEquals("While it's off", off.fallbackHeading)
        assertEquals(AiFeature.entries.size, off.fallbacks.size)
        assertEquals(AiFallbackLine("Morning brief", "read from your day as it is, in Navilife's own words"), off.fallbacks[1])
        assertEquals("Until the 1st", AskRules.statusView("on", null, 2000, 2000, "over").fallbackHeading)
        assertEquals("While it can't be reached", AskRules.STATUS_UNKNOWN.fallbackHeading)
        // Walk-through A16: under "Ask works once this device is connected" the heading doesn't say it again.
        val notConnected = AskRules.STATUS_NOT_CONNECTED
        assertEquals("Until then", notConnected.fallbackHeading)
        assertEquals(1, listOfNotNull(notConnected.line, notConnected.fallbackHeading).count { "this device is connected" in it })
        // The spend still shows beside it.
        val month = listOf(AiMonthUse("ask.talk", 40, 120))
        assertEquals(listOf("Talk", AiSpendRules.UNSPLIT), AskRules.statusView("on", null, 2000, 2000, "over", month).spend.map { it.label })
        // Every line is short enough for one or two caption lines.
        AiFeature.entries.forEach { f -> AiMode.entries.forEach { m -> assertTrue((AiFeatureRules.prefix(m)?.length ?: 0) + f.instead.length < 160) } }
    }

    @Test
    fun eachLineReadsAsTwoSentences() {
        // Walk-through 2026-11-15 A16: "Navilife's AI works once this device is connected · read from your day as it is…".
        val views = listOf(
            AskRules.statusView("off", null, 0, 2000, "ok"),
            AskRules.statusView("on", null, 2000, 2000, "over"),
            AskRules.statusView("failing", null, 0, 2000, "ok"),
            AskRules.STATUS_UNKNOWN,
            AskRules.STATUS_NOT_CONNECTED,
        )
        AiFeature.entries.forEach { f ->
            views.forEach { v ->
                val line = assertNotNull(AiFeatureRules.line(f, v))
                assertFalse(" · " in line, line)
                assertTrue(line.endsWith("."), line)
                assertTrue(line.substringAfter(". ").first().isUpperCase(), line)
            }
        }
        assertEquals(
            "This device isn't connected to Navilife's AI yet. Your brief is read from your day as it is, in Navilife's own words.",
            AiFeatureRules.line(AiFeature.BRIEF, AskRules.STATUS_NOT_CONNECTED),
        )
    }
}
