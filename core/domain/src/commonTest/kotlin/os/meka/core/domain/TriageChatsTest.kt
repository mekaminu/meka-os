package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Messages grouped per chat, slice 1: one Needs you card per conversation, replies and questions on top. */
class TriageChatsTest {
    private fun card(
        id: String, who: String, at: Long, gist: String = "Shared a photo of the pitch",
        lane: TriageLane = TriageLane.FYI, group: String? = null, app: CaptureApp = CaptureApp.WHATSAPP,
        draft: String? = null,
    ): TriageCard {
        val time = "14:" + (at.toInt() % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, draft, app, group != null, at, "spoken", group)
    }

    @Test
    fun aBusyGroupBecomesOneCardWithItsCountPeopleAndWhatMattersFirst() {
        val group = (1..20).map { i ->
            card("g$i", listOf("Tunde", "Femi", "Obi", "Ada")[i % 4], at = i.toLong(), group = "Barça lads")
        } + card("g21", "Femi", at = 3, group = "Barça lads", gist = "Asks if you're coming Saturday?", lane = TriageLane.NEEDS_REPLY, draft = "Yes")
        val chats = TriageChatRules.chats(group)
        val chat = chats.single()
        assertEquals("Barça lads", chat.title)
        assertTrue(chat.isGroup)
        assertEquals("21 new · 4 people · 1 needs a reply", chat.countLine)
        assertEquals("Femi: Asks if you're coming Saturday?", chat.top.first())
        // Identical gists collapse, so the top lines say different things.
        assertEquals(listOf("Femi: Asks if you're coming Saturday?", "Tunde: Shared a photo of the pitch", "Ada: Shared a photo of the pitch"), chat.top)
        assertEquals(group.sortedBy { it.atMs }.map { it.id }, chat.ids)
        assertEquals("WhatsApp · 14:20", chat.meta)
        assertEquals("Show all 21", TriageChatRules.expandLabel(chat))
        assertEquals("Done · Barça lads · 21 messages", TriageChatRules.doneLine(chat))
        assertFalse(chat.single)
    }

    @Test
    fun chatsAreKeptApartByPersonGroupAndAppAndRepliesLeadTheList() {
        val cards = listOf(
            card("a", "Tunde", at = 10),
            card("b", "Tunde", at = 12, gist = "Running late"),
            card("c", "Tunde", at = 30, group = "Barça lads"),
            card("d", "Tunde", at = 40, app = CaptureApp.SMS),
            card("e", "Ada", at = 5, lane = TriageLane.NEEDS_REPLY, gist = "Asks about dinner", draft = "Yes"),
        )
        val chats = TriageChatRules.chats(cards)
        assertEquals(listOf("Ada", "Tunde", "Barça lads", "Tunde"), chats.map { it.title })
        assertEquals(listOf(1, 1, 1, 2), chats.map { it.cards.size })
        val tunde = chats.single { it.cards.size == 2 }
        assertEquals("2 new", tunde.countLine)
        assertEquals(listOf("Running late", "Shared a photo of the pitch"), tunde.top)
        assertTrue(chats.first().single)
        assertEquals("Messages · 14:40", chats.single { it.app == CaptureApp.SMS }.meta)
        // The badge counts chats with a reply waiting, not messages.
        assertEquals(1, TriageChatRules.replyChats(cards + card("f", "Ada", at = 6, lane = TriageLane.NEEDS_REPLY)))
    }

    private fun request(id: String, who: String, at: Long, action: String) = RequestCard(
        id = id, messageId = "m-$id", personKey = if (who.isBlank()) "" else People.key(who),
        proposal = RequestProposal(RequestKind.TASK, action, null, null),
        from = "From $who · 14:00", quote = "“…”", action = action, detail = null, declineLabel = "Not a task",
        spoken = "spoken", atMs = at, person = who,
    )

    @Test
    fun aPersonsRequestsSitInsideTheirChatAndDoneLeavesThemWaiting() {
        val cards = listOf(
            card("a", "Wife", at = 10, gist = "Running late"),
            card("b", "Wife", at = 20, lane = TriageLane.NEEDS_REPLY, gist = "Asks when you're home?", draft = "About 7"),
            card("c", "Wife", at = 30, group = "Family"),
            card("d", "Tunde", at = 40),
        )
        val requests = listOf(
            request("r2", "Wife", at = 25, action = "Add task: Book the dentist"),
            request("r1", "Wife", at = 15, action = "Add task: Pick up dry cleaning · Tomorrow"),
            request("r3", "Ade", at = 5, action = "Add task: Return the drill"),
            request("r4", "", at = 6, action = "Add event: Parents' evening"),
        )
        val chats = TriageChatRules.chatsWith(cards, requests)
        val wife = chats.single { it.title == "Wife" }
        assertEquals(listOf("r1", "r2"), wife.requests.map { it.id })
        assertEquals("2 new · 1 needs a reply · 2 requests", wife.countLine)
        assertTrue(wife.spoken.endsWith("Add task: Pick up dry cleaning · Tomorrow. Add task: Book the dentist."))
        assertEquals("Done · Wife · 2 messages · 2 requests still waiting", TriageChatRules.doneLine(wife))
        // The family group and Tunde carry none; Ade (no open message) and Meka's own share stay on their own.
        assertTrue(chats.filter { it.title != "Wife" }.all { it.requests.isEmpty() })
        assertEquals(listOf("r3", "r4"), TriageChatRules.looseRequests(chats, requests).map { it.id })
        // With no requests nothing changes.
        assertEquals(TriageChatRules.chats(cards), TriageChatRules.chatsWith(cards, emptyList()))
    }

    @Test
    fun oneMessageWithARequestBecomesAChatCardAndTheLatestAppTakesIt() {
        val cards = listOf(
            card("a", "Wife", at = 10),
            card("b", "Wife", at = 50, app = CaptureApp.SMS),
        )
        val chats = TriageChatRules.chatsWith(cards, listOf(request("r1", "Wife", at = 12, action = "Add task: Buy milk")))
        val sms = chats.single { it.app == CaptureApp.SMS }
        val wa = chats.single { it.app == CaptureApp.WHATSAPP }
        assertEquals(1, sms.requests.size)
        assertFalse(sms.single)
        assertEquals("1 new · 1 request", sms.countLine)
        assertTrue(wa.single)
    }

    private fun gist(group: String, count: Int, gist: String?) = GroupGist(
        groupKey = People.key(group), title = group, countLine = "$count messages", count = count, people = "Tunde",
        gist = gist, slotMs = 0, slotLabel = "Lunchtime digest", latestMs = 0, caughtUpAtMs = null, spoken = "spoken",
    )

    @Test
    fun aGroupsDigestChatterRidesInItsChatCardAndIsShownOnce() {
        val cards = listOf(
            card("a", "Femi", at = 10, group = "Barça  Lads", gist = "Asks if you're coming Saturday?", lane = TriageLane.NEEDS_REPLY, draft = "Yes"),
            card("b", "Ada", at = 20),
        )
        val chatter = (1..14).map { i ->
            CapturedItem("d$i", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Tunde", "Lineup for Getafe $i", "Barça Lads", 100L + i)
        } + (1..3).map { i ->
            CapturedItem("x$i", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Obi", "Golf on Sunday $i", "Golf crew", 200L + i)
        }
        val settings = TriageSettings()
        val digest = GroupDigestRules.cards(chatter, settings, emptyMap())
        assertEquals(2, digest.size)
        val chats = TriageChatRules.chats(cards)
        val keys = TriageChatRules.groupKeys(chats)
        assertEquals(listOf("barça lads"), keys)

        // The Fold: the group's chat card carries the chatter, with MEKA's gist when one was made; Ada's is untouched.
        val folded = TriageChatRules.withDigest(chats, digest, listOf(gist("Barça Lads", 14, "Lineup debate for Getafe")))
        val barca = folded.single { it.isGroup }
        assertEquals(14, barca.chatter)
        assertEquals("1 new · +14 in the group", barca.countLine)
        assertEquals("+ 14 more in the group · Lineup debate for Getafe", barca.chatterLine)
        assertTrue(barca.spoken.endsWith("14 more in the group. Lineup debate for Getafe."))
        assertFalse(barca.single)
        assertEquals("barça lads", barca.groupKey)
        assertEquals("Done · Barça  Lads · 1 message · group caught up", TriageChatRules.doneLine(barca))
        assertEquals(chats.single { !it.isGroup }, folded.single { !it.isGroup })

        // ...and the digest leaves that group out, so it's shown once; with no other news there's no digest at all.
        val view = GroupDigestRules.view(chatter, settings, emptyMap(), dayStartMs = 0, nowMinute = 13 * 60, inChats = keys)!!
        assertEquals(listOf("golf crew"), view.cards.map { it.groupKey })
        assertEquals("1 group · 3 messages", view.line)
        assertEquals(null, GroupDigestRules.view(chatter.take(14), settings, emptyMap(), 0, 13 * 60, inChats = keys))

        // The Mac: the synced cards fold in the same way, and its digest keeps only the rest.
        val gists = listOf(gist("Barça Lads", 14, null), gist("Golf crew", 3, "Tee time moved"))
        val mac = TriageChatRules.withGists(chats, gists).single { it.isGroup }
        assertEquals("+ 14 more in the group", mac.chatterLine)
        assertEquals(listOf("Golf crew"), TriageChatRules.gistsOutside(chats, gists).map { it.title })
        assertEquals(listOf("Barça Lads"), TriageChatRules.gistsInside(mac, gists).map { it.title })
        // A group with nothing in the digest is unchanged apart from knowing its key.
        val quiet = TriageChatRules.withGists(chats, emptyList()).single { it.isGroup }
        assertEquals(0, quiet.chatter)
        assertEquals(null, quiet.chatterLine)
        assertEquals("1 new", quiet.countLine)
    }
}
