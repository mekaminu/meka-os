package os.meka.backend

import os.meka.core.domain.ServerSpeedRules
import os.meka.core.domain.ServerTurnTiming
import os.meka.core.wire.ServerSpeedCodec
import javax.sql.DataSource

/**
 * Talking to Ada like a conversation, slice 2 (`POST /v1/talk/speed`): the server's own timing of each answer it
 * wrote ([AskService.askStream] and [AskService.ask]), kept per household (the newest [ServerSpeedRules.KEPT]), read
 * by Speed on the devices and by GitHub's release publisher for the deploy report. Times only: nothing of what was
 * asked or answered is in a timing, and none is logged.
 */
interface TalkSpeedStore {
    fun put(householdId: String, t: ServerTurnTiming)
    fun recent(householdId: String, sinceMs: Long): List<ServerTurnTiming>
}

class TalkSpeed(private val store: TalkSpeedStore, private val nowMs: () -> Long = System::currentTimeMillis) {
    /** Never throws: a timing that can't be kept is dropped. */
    fun record(householdId: String, t: ServerTurnTiming) {
        runCatching { store.put(householdId, t) }
    }

    fun recent(who: DeviceIdentity): List<ServerSpeedCodec.Turn> =
        store.recent(who.householdId, nowMs() - ServerSpeedRules.WINDOW_MS).map(::wire)

    companion object {
        fun wire(t: ServerTurnTiming) = ServerSpeedCodec.Turn(
            t.atMs, t.open, t.spoken, t.model, t.webMs, t.firstTokenMs, t.firstSentenceMs, t.clipMs, t.doneMs, t.answered,
        )

        fun domain(t: ServerSpeedCodec.Turn) = ServerTurnTiming(
            t.atMs, t.open, t.spoken, t.model, t.webMs, t.firstTokenMs, t.firstSentenceMs, t.clipMs, t.doneMs, t.answered,
        )
    }
}

class InMemoryTalkSpeedStore : TalkSpeedStore {
    private val rows = HashMap<String, List<ServerTurnTiming>>()
    @Synchronized override fun put(householdId: String, t: ServerTurnTiming) {
        rows[householdId] = ServerSpeedRules.add(rows[householdId].orEmpty(), t)
    }
    @Synchronized override fun recent(householdId: String, sinceMs: Long) = rows[householdId].orEmpty().filter { it.atMs >= sinceMs }
}

class PostgresTalkSpeedStore(private val ds: DataSource) : TalkSpeedStore {
    override fun put(householdId: String, t: ServerTurnTiming) = ds.connection.use { c ->
        c.prepareStatement("INSERT INTO talk_timing(household_id, at_ms, timing) VALUES (?,?,?)").use {
            it.setString(1, householdId); it.setLong(2, t.atMs); it.setString(3, ServerSpeedCodec.encodeTurn(TalkSpeed.wire(t)))
            it.executeUpdate()
        }
        // Only the newest are kept: these are the server's own timings, not his data.
        c.prepareStatement(
            """DELETE FROM talk_timing WHERE household_id = ? AND id NOT IN
               (SELECT id FROM talk_timing WHERE household_id = ? ORDER BY id DESC LIMIT ${ServerSpeedRules.KEPT})""",
        ).use { it.setString(1, householdId); it.setString(2, householdId); it.executeUpdate() }
        Unit
    }

    override fun recent(householdId: String, sinceMs: Long): List<ServerTurnTiming> = ds.connection.use { c ->
        c.prepareStatement("SELECT timing FROM talk_timing WHERE household_id = ? AND at_ms >= ? ORDER BY id DESC LIMIT ${ServerSpeedRules.KEPT}").use { st ->
            st.setString(1, householdId); st.setLong(2, sinceMs)
            st.executeQuery().use { rs ->
                val out = ArrayList<ServerTurnTiming>()
                while (rs.next()) ServerSpeedCodec.decodeTurn(rs.getString(1))?.let { out += TalkSpeed.domain(it) }
                out.asReversed()
            }
        }
    }
}
