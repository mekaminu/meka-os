package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
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
            "Navilife's AI is off · read from your day as it is, in Navilife's own words",
            AiFeatureRules.line(AiFeature.BRIEF, off),
        )
        val over = AskRules.statusView("on", null, 2000, 2000, "over")
        assertEquals(
            "This month's AI budget is used up till the 1st · nothing is drafted · write your reply in the editor and Send works as usual",
            AiFeatureRules.line(AiFeature.DRAFTS, over),
        )
        assertEquals(
            "Can't reach Navilife's AI just now · kept with your words · Make cards on the note once the AI is back",
            AiFeatureRules.line(AiFeature.VOICE_NOTES, AskRules.STATUS_UNKNOWN),
        )
        assertTrue(AiFeatureRules.line(AiFeature.CALLS, AskRules.statusView("failing", null, 0, 2000, "ok"))!!.startsWith("Navilife's AI isn't answering · "))
    }

    @Test
    fun settingsListsWhatStillWorksUnderAHeadingForTheMode() {
        val off = AskRules.statusView("off", null, 0, 2000, "ok")
        assertEquals("While it's off", off.fallbackHeading)
        assertEquals(AiFeature.entries.size, off.fallbacks.size)
        assertEquals(AiFallbackLine("Morning brief", "read from your day as it is, in Navilife's own words"), off.fallbacks[1])
        assertEquals("Until the 1st", AskRules.statusView("on", null, 2000, 2000, "over").fallbackHeading)
        assertEquals("While it can't be reached", AskRules.STATUS_UNKNOWN.fallbackHeading)
        // The spend still shows beside it.
        val month = listOf(AiMonthUse("ask.talk", 40, 120))
        assertEquals(listOf("Talk", AiSpendRules.UNSPLIT), AskRules.statusView("on", null, 2000, 2000, "over", month).spend.map { it.label })
        // Every line is short enough for one or two caption lines.
        AiFeature.entries.forEach { f -> AiMode.entries.forEach { m -> assertTrue((AiFeatureRules.prefix(m)?.length ?: 0) + f.fallback.length < 160) } }
    }
}
