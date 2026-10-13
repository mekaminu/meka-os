package os.meka.backend.integrations

import os.meka.backend.PostgresOpStore
import os.meka.core.domain.MailSorted
import java.sql.Connection
import java.sql.ResultSet

/**
 * The mail headers the server keeps per mail account (Inbox autopilot, slice 1c; migration V20): the last 60 days,
 * headers only, never a body, never logged. A row whose headers haven't been read yet is pending (Gmail lists ids
 * first and reads their headers a few hundred per check). [cursor] is where the provider's change feed continues.
 * Disconnect forgets an account's rows and cursor.
 */
interface MailStore {
    fun cursor(accountId: String): String?
    /** One round of changes: whole headers stored, [fetch] ids stored as pending, [removed] gone, the cursor moved on. */
    fun apply(householdId: String, accountId: String, changes: MailChanges)
    /** Ids still waiting for their headers, oldest first. */
    fun pending(accountId: String, limit: Int): List<String>
    fun put(householdId: String, accountId: String, header: MailHeader)
    fun remove(accountId: String, id: String)
    /** Drops what was received before [beforeMs] (pending rows stay until read). */
    fun prune(accountId: String, beforeMs: Long)
    /** Everything for this account, cursor included (Disconnect; a cursor the provider forgot). */
    fun forget(accountId: String)
    /** The account's headers, newest first (the sorting slice reads these; tests too). */
    fun messages(accountId: String): List<MailHeader>
    fun pendingCount(accountId: String): Int
    /** Slice 2a: headers not sorted yet (a header read again is unsorted again), newest first. */
    fun unsorted(accountId: String, limit: Int): List<MailHeader>
    fun setSorts(accountId: String, sorts: Map<String, MailSorted>)
    /** Slice 2a: emails still in the inbox per sort name (sorted ones only). */
    fun sortCounts(accountId: String): Map<String, Int>
    /** Slice 2a: one email's sort, or null before it's sorted (tests; later the cards). */
    fun sortOf(accountId: String, id: String): MailSorted?
}

class InMemoryMailStore : MailStore {
    private val cursors = mutableMapOf<String, String>()
    private val rows = mutableMapOf<String, LinkedHashMap<String, MailHeader?>>()
    private val sorts = mutableMapOf<String, MutableMap<String, MailSorted>>()

    private fun of(accountId: String) = rows.getOrPut(accountId) { LinkedHashMap() }

    @Synchronized override fun cursor(accountId: String) = cursors[accountId]
    @Synchronized override fun apply(householdId: String, accountId: String, changes: MailChanges) {
        val r = of(accountId)
        changes.headers.forEach { r[it.id] = it; sorts[accountId]?.remove(it.id) }
        changes.fetch.forEach { r[it] = null; sorts[accountId]?.remove(it) }
        changes.removed.forEach { r.remove(it); sorts[accountId]?.remove(it) }
        cursors[accountId] = changes.cursor
    }
    @Synchronized override fun pending(accountId: String, limit: Int) = of(accountId).filterValues { it == null }.keys.take(limit)
    @Synchronized override fun put(householdId: String, accountId: String, header: MailHeader) { of(accountId)[header.id] = header; sorts[accountId]?.remove(header.id) }
    @Synchronized override fun remove(accountId: String, id: String) { of(accountId).remove(id); sorts[accountId]?.remove(id) }
    @Synchronized override fun prune(accountId: String, beforeMs: Long) {
        of(accountId).entries.removeIf { (id, h) -> (h != null && h.receivedAtMs < beforeMs).also { if (it) sorts[accountId]?.remove(id) } }
    }
    @Synchronized override fun forget(accountId: String) { rows.remove(accountId); cursors.remove(accountId); sorts.remove(accountId) }
    @Synchronized override fun messages(accountId: String) = of(accountId).values.filterNotNull().sortedByDescending { it.receivedAtMs }
    @Synchronized override fun pendingCount(accountId: String) = of(accountId).values.count { it == null }
    @Synchronized override fun unsorted(accountId: String, limit: Int): List<MailHeader> {
        val done = sorts[accountId].orEmpty()
        return messages(accountId).filter { it.id !in done }.take(limit)
    }
    @Synchronized override fun setSorts(accountId: String, sorts: Map<String, MailSorted>) {
        val r = of(accountId)
        val m = this.sorts.getOrPut(accountId) { mutableMapOf() }
        sorts.forEach { (id, s) -> if (r[id] != null) m[id] = s }
    }
    @Synchronized override fun sortCounts(accountId: String): Map<String, Int> {
        val m = sorts[accountId].orEmpty()
        return of(accountId).values.filterNotNull().filter { it.inInbox }.mapNotNull { m[it.id]?.sort?.name }.groupingBy { it }.eachCount()
    }
    @Synchronized override fun sortOf(accountId: String, id: String) = sorts[accountId]?.get(id)
}

class PostgresMailStore(private val ops: PostgresOpStore) : MailStore {
    private fun <T> c(block: (Connection) -> T): T = ops.withConnection(block)

    override fun cursor(accountId: String): String? = c { c ->
        c.prepareStatement("SELECT cursor FROM mail_sync WHERE account_id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun apply(householdId: String, accountId: String, changes: MailChanges) = ops.transaction {
        c { c ->
            changes.headers.forEach { upsert(c, householdId, accountId, it) }
            if (changes.fetch.isNotEmpty()) c.prepareStatement(
                """INSERT INTO mail_message(account_id, message_id, household_id) VALUES (?,?,?)
                   ON CONFLICT (account_id, message_id) DO UPDATE SET headers_at = NULL, sort = NULL, sort_reason = NULL, sort_sure = NULL""",
            ).use { st ->
                changes.fetch.forEach { id -> st.setString(1, accountId); st.setString(2, id); st.setString(3, householdId); st.addBatch() }
                st.executeBatch()
            }
            if (changes.removed.isNotEmpty()) c.prepareStatement("DELETE FROM mail_message WHERE account_id = ? AND message_id = ?").use { st ->
                changes.removed.forEach { id -> st.setString(1, accountId); st.setString(2, id); st.addBatch() }
                st.executeBatch()
            }
            c.prepareStatement(
                """INSERT INTO mail_sync(account_id, cursor) VALUES (?,?)
                   ON CONFLICT (account_id) DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = now()""",
            ).use { st -> st.setString(1, accountId); st.setString(2, changes.cursor); st.executeUpdate() }
            Unit
        }
    }

    override fun pending(accountId: String, limit: Int): List<String> = c { c ->
        c.prepareStatement("SELECT message_id FROM mail_message WHERE account_id = ? AND headers_at IS NULL ORDER BY created_at, message_id LIMIT ?").use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun put(householdId: String, accountId: String, header: MailHeader) = c { c -> upsert(c, householdId, accountId, header) }

    private fun upsert(c: Connection, householdId: String, accountId: String, h: MailHeader) {
        c.prepareStatement(
            """INSERT INTO mail_message(account_id, message_id, household_id, thread_id, from_address, from_name, subject,
                 received_at_ms, unread, in_inbox, labels, list_unsubscribe, one_click_unsubscribe, list_id, headers_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
               ON CONFLICT (account_id, message_id) DO UPDATE SET thread_id = EXCLUDED.thread_id,
                 from_address = EXCLUDED.from_address, from_name = EXCLUDED.from_name, subject = EXCLUDED.subject,
                 received_at_ms = EXCLUDED.received_at_ms, unread = EXCLUDED.unread, in_inbox = EXCLUDED.in_inbox,
                 labels = EXCLUDED.labels, list_unsubscribe = EXCLUDED.list_unsubscribe,
                 one_click_unsubscribe = EXCLUDED.one_click_unsubscribe, list_id = EXCLUDED.list_id, headers_at = now(),
                 sort = NULL, sort_reason = NULL, sort_sure = NULL""",
        ).use { st ->
            st.setString(1, accountId); st.setString(2, h.id); st.setString(3, householdId); st.setString(4, h.threadId)
            st.setString(5, h.fromAddress); st.setString(6, h.fromName); st.setString(7, h.subject); st.setLong(8, h.receivedAtMs)
            st.setBoolean(9, h.unread); st.setBoolean(10, h.inInbox); st.setString(11, h.labels.joinToString(","))
            st.setString(12, h.listUnsubscribe); st.setBoolean(13, h.oneClickUnsubscribe); st.setString(14, h.listId)
            st.executeUpdate()
        }
    }

    override fun remove(accountId: String, id: String) = c { c ->
        c.prepareStatement("DELETE FROM mail_message WHERE account_id = ? AND message_id = ?").use { st ->
            st.setString(1, accountId); st.setString(2, id); st.executeUpdate()
        }
        Unit
    }

    override fun prune(accountId: String, beforeMs: Long) = c { c ->
        c.prepareStatement("DELETE FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL AND received_at_ms < ?").use { st ->
            st.setString(1, accountId); st.setLong(2, beforeMs); st.executeUpdate()
        }
        Unit
    }

    override fun forget(accountId: String) = ops.transaction {
        c { c ->
            c.prepareStatement("DELETE FROM mail_message WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            c.prepareStatement("DELETE FROM mail_sync WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            Unit
        }
    }

    override fun messages(accountId: String): List<MailHeader> = c { c ->
        c.prepareStatement(
            """SELECT message_id, thread_id, from_address, from_name, subject, received_at_ms, unread, in_inbox, labels,
                 list_unsubscribe, one_click_unsubscribe, list_id
               FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL ORDER BY received_at_ms DESC, message_id""",
        ).use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.header()) } }
        }
    }

    override fun pendingCount(accountId: String): Int = c { c ->
        c.prepareStatement("SELECT count(*) FROM mail_message WHERE account_id = ? AND headers_at IS NULL").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    override fun unsorted(accountId: String, limit: Int): List<MailHeader> = c { c ->
        c.prepareStatement(
            """SELECT message_id, thread_id, from_address, from_name, subject, received_at_ms, unread, in_inbox, labels,
                 list_unsubscribe, one_click_unsubscribe, list_id
               FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL AND sort IS NULL
               ORDER BY received_at_ms DESC, message_id LIMIT ?""",
        ).use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.header()) } }
        }
    }

    override fun setSorts(accountId: String, sorts: Map<String, MailSorted>) {
        if (sorts.isEmpty()) return
        c { c ->
            c.prepareStatement(
                "UPDATE mail_message SET sort = ?, sort_reason = ?, sort_sure = ? WHERE account_id = ? AND message_id = ? AND headers_at IS NOT NULL",
            ).use { st ->
                sorts.forEach { (id, s) ->
                    st.setString(1, s.sort.name); st.setString(2, s.reason.name); st.setBoolean(3, s.sure)
                    st.setString(4, accountId); st.setString(5, id); st.addBatch()
                }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun sortCounts(accountId: String): Map<String, Int> = c { c ->
        c.prepareStatement("SELECT sort, count(*) FROM mail_message WHERE account_id = ? AND in_inbox AND sort IS NOT NULL GROUP BY sort").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }
    }

    override fun sortOf(accountId: String, id: String): MailSorted? = c { c ->
        c.prepareStatement("SELECT sort, sort_reason, sort_sure FROM mail_message WHERE account_id = ? AND message_id = ?").use { st ->
            st.setString(1, accountId); st.setString(2, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val sort = os.meka.core.domain.MailSort.of(rs.getString(1)) ?: return@use null
                val reason = os.meka.core.domain.MailSortReason.of(rs.getString(2)) ?: return@use null
                MailSorted(sort, reason, rs.getBoolean(3))
            }
        }
    }

    private fun ResultSet.header() = MailHeader(
        id = getString(1), threadId = getString(2), fromAddress = getString(3), fromName = getString(4), subject = getString(5),
        receivedAtMs = getLong(6), unread = getBoolean(7), inInbox = getBoolean(8),
        labels = getString(9)?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
        listUnsubscribe = getString(10), oneClickUnsubscribe = getBoolean(11), listId = getString(12),
    )
}
