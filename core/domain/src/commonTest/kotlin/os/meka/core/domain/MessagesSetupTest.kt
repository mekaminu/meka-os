package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Work mode → Messages (build plan V1, messages assistant slice 5): the setup screen's lines and the never-to-AI list. */
class MessagesSetupTest {
    private val day = 10L * 24 * 60 * 60_000L
    private fun msg(who: String, group: String? = null, atMs: Long = day, kind: CaptureKind = CaptureKind.MESSAGE) =
        CapturedItem("$who-$group-$atMs", CaptureApp.WHATSAPP, kind, who, "hi", group, atMs)

    @Test
    fun theStatusLineSaysWhatIsMissing() {
        val r = MessagesSetupRules
        assertTrue(r.statusLine(listening = false, aiOn = true).startsWith("Give MEKA notification access"))
        assertTrue(r.statusLit(listening = false, aiOn = true))
        assertTrue(r.statusLine(listening = true, aiOn = false).contains("MEKA's AI is off"))
        assertTrue(r.statusLit(listening = true, aiOn = false))
        assertTrue(r.statusLine(listening = true, aiOn = true).contains("replies drafted for you to send"))
        assertFalse(r.statusLit(listening = true, aiOn = null))
        // The Mac never reads messages: it points at the Fold.
        assertTrue(r.statusLine(listening = null, aiOn = true).contains("Work mode → Messages"))
        assertFalse(r.statusLit(listening = null, aiOn = false))
    }

    @Test
    fun theScreenStatesTheWhatsAppSettingsPrivacyCostAndLimits() {
        val r = MessagesSetupRules
        assertTrue(r.WHATSAPP_STEPS.any { it.contains("Notification tone \"None\"") && it.contains("High-priority notifications \"Off\"") })
        assertTrue(r.WHATSAPP_STEPS.any { it.startsWith("Don't mute groups") })
        assertTrue(r.PRIVACY.any { it.contains("text, the sender's name and the time") })
        assertTrue(r.PRIVACY.any { it.contains("dropped after 7 days") })
        assertTrue(r.PRIVACY.any { it.contains("without your tap") })
        assertTrue(r.COST.contains("monthly cap"))
        assertTrue(r.LIMITS.any { it.contains("Voice notes and photos") })
        assertTrue(r.LIMITS.any { it.contains("muted chat") })
    }

    @Test
    fun peopleAndGroupsFromTheLastWeekNewestFirstPlusEveryoneKeptFromTheAi() {
        val hour = 60 * 60_000L
        val recent = listOf(
            msg("Tunde", atMs = day - 2 * hour),
            msg("Femi", "Barça lads", day - hour),
            msg("tunde ", atMs = day - 3 * hour), // the same person
            msg("Obi", atMs = day - 8 * 24 * hour), // older than a week
            msg("Dad", atMs = day - hour, kind = CaptureKind.MISSED_CALL), // a call, not a message
            msg("Ada", "Family", day - 5 * hour),
            msg("Mum", atMs = day - 30 * 60_000L),
        )
        val settings = TriageSettings(
            groupModes = mapOf("School run" to GroupMode.IGNORE),
            neverToAi = setOf("Ngozi", "family"),
        )
        val list = MessagesSetupRules.rows(recent, settings, day)
        assertEquals(listOf("Mum", "Tunde", "Ngozi"), list.people.map { it.name })
        assertEquals(listOf(false, false, true), list.people.map { it.private })
        assertEquals(listOf("Barça lads", "Family", "School run"), list.groups.map { it.name })
        assertEquals(listOf(GroupMode.DIGEST, GroupMode.DIGEST, GroupMode.IGNORE), list.groups.map { it.mode })
        assertEquals(listOf(false, true, false), list.groups.map { it.private })
        assertEquals("Sent to MEKA's AI for a lane and a draft", list.people[0].line)
        assertEquals("Kept from MEKA's AI · shown as FYI, nothing drafted", list.people[2].line)
        assertEquals("Kept from MEKA's AI · Digest, no gist", list.groups[1].line)
        assertEquals(GroupDigestRules.modeHint(GroupMode.IGNORE), list.groups[2].line)
        assertTrue(MessagesSetupRules.rows(emptyList(), TriageSettings(), day).isEmpty)
    }

    @Test
    fun theNeverToAiListIsKeptByPerson() {
        val r = MessagesSetupRules
        val one = r.setNeverToAi(emptySet(), " Tunde ", on = true)
        assertEquals(setOf("Tunde"), one)
        assertEquals(setOf("tunde"), r.setNeverToAi(one, "tunde", on = true))
        assertEquals(emptySet(), r.setNeverToAi(one, "TUNDE", on = false))
        assertEquals(one, r.setNeverToAi(one, "  ", on = true))
        assertTrue(TriageSettings(neverToAi = one).isPrivate("tunde"))
        assertEquals("Tunde · kept from MEKA's AI", r.privateLine("Tunde", on = true))
        assertEquals("Tunde · back to MEKA's AI", r.privateLine("Tunde", on = false))
    }
}
