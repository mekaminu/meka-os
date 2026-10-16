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
        assertEquals(0, fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "first idea", now), DiscordCaptured(general, "Sam", "gm", now))))
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
        ))
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
        assertEquals(0, fold.noteDiscord(listOf(DiscordCaptured(ideas, "Admin", "another", now))))
    }
}
