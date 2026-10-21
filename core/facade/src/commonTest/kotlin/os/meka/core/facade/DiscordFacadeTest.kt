package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DiscordCaptured
import os.meka.core.domain.DiscordChannel
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discord channel watch, slice 1, through the facade: picked on the Mac, kept by the Fold, Done on either. */
class DiscordFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val ideas = DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas")
    private val general = DiscordChannel("example traders#general", "Example Traders", "general")

    @Test
    fun aChannelPickedOnTheMacIsKeptByTheFoldAndDoneClearsBoth() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Before anything is picked: channels are noted, nothing is kept.
        assertEquals(0, fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now), DiscordCaptured(general, "Sam", "gm", now))).fresh)
        assertNull(fold.discord.value)
        assertEquals(2, fold.discordWatch.value.rows.size)
        fold.syncNow(); mac.syncNow()
        assertEquals(2, mac.discordWatch.value.rows.size)
        assertEquals("Following #trade-ideas · Example Traders · its posts come to Needs you", mac.setDiscordWatched(ideas.key, true))
        assertEquals("That channel isn't listed any more", mac.setDiscordWatched("nope#x", true))
        mac.syncNow(); fold.syncNow()

        now += 60_000
        val n = fold.noteDiscord(listOf(
            DiscordCaptured(ideas, "Admin", "BTC long idea above 68k, stop 66.5k", now),
            DiscordCaptured(ideas, "Admin", "BTC long idea above 68k, stop 66.5k", now), // a re-post
            DiscordCaptured(general, "Sam", "anyone around?", now),
        )).fresh
        assertEquals(1, n)
        val card = fold.discord.value!!.cards.single()
        assertEquals("#trade-ideas", card.title)
        assertEquals(listOf("BTC long idea above 68k, stop 66.5k"), card.posts.map { it.text })
        fold.syncNow(); mac.syncNow()
        assertEquals(card.ids, mac.discord.value!!.cards.single().ids)

        // Done on the Mac, Undo, Done again: both apps follow.
        assertEquals("Done · #trade-ideas · 1 post", mac.discordDone(ideas.key))
        assertNull(mac.discord.value)
        mac.undoDiscord()
        assertEquals(1, mac.discord.value!!.cards.size)
        mac.discordDone(ideas.key)
        mac.syncNow(); fold.syncNow()
        assertNull(fold.discord.value)

        // Stop following: new posts from it are no longer kept.
        assertTrue(fold.setDiscordWatched(ideas.key, false).startsWith("Stopped following"))
        now += 60_000
        assertEquals(0, fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "another", now))).fresh)
    }

    @Test
    fun peopleAndWordsSetOnTheMacAlertOnTheFold() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now)))
        fold.setDiscordWatched(ideas.key, true)
        fold.syncNow(); mac.syncNow()
        assertEquals("Alerts straight away for posts from Admin", mac.setDiscordAlert("@Admin", person = true, add = true))
        assertEquals("Alerts straight away for posts saying “stop”", mac.setDiscordAlert("stop", person = false, add = true))
        assertEquals("Alerts straight away for posts from Admin, or saying stop", mac.discordWatch.value.alertLine)
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Admin"), fold.discordWatch.value.people)

        now += 60_000
        val noted = fold.noteDiscord(listOf(
            DiscordCaptured(ideas, "Sam", "gm", now),
            DiscordCaptured(ideas, "Sam", "BTC entry 67.8k, stop 66.5k", now + 1),
        ))
        assertEquals(2, noted.fresh)
        val alert = noted.alerts.single()
        assertEquals("Sam: BTC entry 67.8k, stop 66.5k", alert.text)
        assertEquals("Says “stop”", alert.why)
        fold.syncNow(); mac.syncNow()
        val card = mac.discord.value!!.cards.single()
        assertEquals("1 alert · latest 10:01", card.alertLine)
        assertEquals("Says “stop”", card.posts.last().alert)
        // Admin is already watched, so only Sam is offered.
        assertEquals(listOf("Sam"), mac.discordWatch.value.suggestedPeople)
        assertEquals("No longer alerting for Admin", fold.setDiscordAlert("admin", person = true, add = false))
    }

    @Test
    fun aPostKeptAsAnIdeaLandsInTradingIdeasOnBothAppsAndUndoTakesItOut() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now)))
        fold.setDiscordWatched(ideas.key, true)
        now += 60_000
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "BTC long above 68k, stop 66.5k", now)))
        fold.syncNow(); mac.syncNow()
        val post = mac.discord.value!!.cards.single().posts.single()
        assertEquals(false, post.kept)

        assertEquals(os.meka.core.domain.DiscordRules.KEPT_LINE, mac.keepDiscordIdea(post.id))
        assertEquals(os.meka.core.domain.DiscordRules.ALREADY_KEPT, mac.keepDiscordIdea(post.id))
        mac.syncNow(); fold.syncNow()
        assertEquals(true, fold.discord.value!!.cards.single().posts.single().kept)
        val group = fold.listsView.value.someday.single { it.kind == os.meka.core.domain.SomedayKind.TRADING }
        assertEquals("Trading ideas", group.label)
        val task = group.items.single()
        assertEquals("Admin in #trade-ideas: BTC long above 68k, stop 66.5k", task.title)
        assertTrue(task.notes!!.contains("Information only"))

        // Undo on the Mac: the note goes and the post can be kept again.
        mac.undoKeepDiscordIdea(post.id)
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.listsView.value.someday.none { it.kind == os.meka.core.domain.SomedayKind.TRADING })
        assertEquals(false, fold.discord.value!!.cards.single().posts.single().kept)
        assertEquals("That post's words are gone · nothing to keep", fold.keepDiscordIdea("nope"))
    }

    @Test
    fun toADayPutsAPostOnThatDaysBoardColumnAsATaskAndUndoTakesItOff() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now)))
        fold.setDiscordWatched(ideas.key, true)
        now += 60_000
        fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "BTC long above 68k, stop 66.5k", now)))
        fold.syncNow(); mac.syncNow()
        val post = mac.discord.value!!.cards.single().posts.single()
        // Mon 12 Oct: Thursday's chip.
        val thu = mac.boardFeedDays().first { it.label == "Thu" }.day
        assertEquals("Idea on Thu 15 Oct · a task to look at it, nothing is traded", mac.discordIdeaToDay(post.id, thu))
        assertEquals(os.meka.core.domain.DiscordRules.ALREADY_KEPT, mac.discordIdeaToDay(post.id, thu))
        assertEquals(os.meka.core.domain.DiscordRules.ALREADY_KEPT, mac.keepDiscordIdea(post.id))
        val row = mac.discord.value!!.cards.single().posts.single()
        assertTrue(row.kept); assertTrue(row.onWeek)
        mac.tick()
        val day = mac.weekBoard.value.days.single { d -> d.cards.any { it.title.startsWith("Admin in #trade-ideas") } }
        assertEquals(thu, day.epochDay)
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertEquals(true, fold.discord.value!!.cards.single().posts.single().onWeek)
        val card = fold.weekBoard.value.days.flatMap { it.cards }.single { it.title.startsWith("Admin in #trade-ideas") }
        assertEquals(thu, fold.weekBoard.value.dayOf(card.id)?.epochDay)
        // Not in Trading ideas: it's a task on his week.
        assertTrue(fold.listsView.value.someday.none { it.kind == os.meka.core.domain.SomedayKind.TRADING })

        // Undo on the Fold: off the Board and the post can be kept again.
        fold.undoKeepDiscordIdea(post.id)
        fold.tick()
        assertNull(fold.weekBoard.value.dayOf(card.id))
        assertEquals(false, fold.discord.value!!.cards.single().posts.single().kept)
        // Today's chip: on Today's column with no day of its own.
        assertEquals("Idea for today · a task to look at it, nothing is traded", fold.discordIdeaToDay(post.id, fold.boardFeedDays()[0].day))
        fold.tick()
        val today = fold.weekBoard.value.days.flatMap { it.cards }.single { it.title.startsWith("Admin in #trade-ideas") }
        assertEquals(fold.boardFeedDays()[0].day, fold.weekBoard.value.dayOf(today.id)?.epochDay)
        assertEquals("That post's words are gone · nothing to keep", fold.discordIdeaToDay("nope", thu))
    }
}
