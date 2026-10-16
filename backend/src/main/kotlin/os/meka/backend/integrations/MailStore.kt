package os.meka.backend.integrations

import os.meka.backend.PostgresOpStore
import os.meka.core.domain.MailBacklogKind
import os.meka.core.domain.MailSortRules
import os.meka.core.domain.MailSorted
import os.meka.core.domain.MailTidy
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
    /** Ids still waiting for their headers, the most recently listed first (inbox fixes, slice 1). */
    fun pending(accountId: String, limit: Int): List<String>
    fun put(householdId: String, accountId: String, header: MailHeader)
    /** Inbox fixes, slice 8: these emails were marked read in the mailbox (their sort is kept). */
    fun markSeen(accountId: String, ids: Collection<String>)
    fun remove(accountId: String, id: String)
    /** Drops what was received before [beforeMs] (pending rows stay until read). */
    fun prune(accountId: String, beforeMs: Long)
    /** Everything for this account, cursor included (Disconnect; a cursor the provider forgot). */
    fun forget(accountId: String)
    /** The account's headers, newest first (the sorting slice reads these; tests too). */
    fun messages(accountId: String): List<MailHeader>
    fun pendingCount(accountId: String): Int
    /**
     * Meeting prep, slice 4: the newest email (headers read, received by [beforeMs]) from each of these addresses (lower
     * case), by address. Never logged.
     */
    fun lastFrom(accountId: String, addresses: Collection<String>, beforeMs: Long): Map<String, MailHeader>
    /** Slice 2a: headers not sorted yet (a header read again is unsorted again), newest first. */
    fun unsorted(accountId: String, limit: Int): List<MailHeader>
    fun setSorts(accountId: String, sorts: Map<String, MailSorted>)
    /** Slice 2a: emails still in the inbox per sort name (sorted ones only). */
    fun sortCounts(accountId: String): Map<String, Int>
    /** Slice 2a: one email's sort, or null before it's sorted (tests; later the cards). */
    fun sortOf(accountId: String, id: String): MailSorted?
    /** Slice 2b: emails still in the inbox the rules put in Needs you unsure (for the AI), newest first. */
    fun unsure(accountId: String, limit: Int): List<MailHeader>
    /** Slice 3a: every sorted email still in the inbox with its sort (Autopilot's dry run plans these). */
    fun inboxSorted(accountId: String): List<Pair<MailHeader, MailSorted>>

    /** Slice 3b: what one tidy moved (ids only), so its Undo can put them back. */
    fun recordTidy(accountId: String, batchId: String, moved: List<MailMoved>)
    /** Slice 3b: the emails one tidy of this account moved. */
    fun tidied(accountId: String, batchId: String): List<MailMoved>
    /** Slice 3b: an Undo done: these ids (as they were, and as they are back in the inbox) are never tidied again. */
    fun neverTidy(accountId: String, ids: Collection<String>)
    fun neverTidied(accountId: String): Set<String>

    /**
     * Slice 5a: a newsletter or promo email's sender noted (name, list headers and message id from the newest one;
     * the seen count rises by one for each newer email). Nothing for an email without a sender.
     */
    fun noteSender(householdId: String, accountId: String, h: MailHeader)
    /** Slice 5a: the account's noted senders, busiest first, at most [limit]. */
    fun senders(accountId: String, limit: Int): List<MailSenderRecord>
    /** Slice 5a: Outlook: the list headers read from the sender's latest email (on demand). */
    fun setSenderHeaders(accountId: String, address: String, listUnsubscribe: String?, oneClick: Boolean)
    /**
     * Slice 5c: per sender address (lower case), its emails received since [sinceMs] whose headers were read, and how
     * many of them are read (Gmail's UNREAD label / Outlook's isRead).
     */
    fun openStats(accountId: String, sinceMs: Long): Map<String, SenderOpens>

    /** Slice 6a: the kept previews of these emails (id → preview; those without one left out). */
    fun previews(accountId: String, ids: Collection<String>): Map<String, String>

    /** Slice 8a: the backlog scan's progress, or null before it started. */
    fun backlogScan(accountId: String): BacklogScan?
    fun setBacklogScan(accountId: String, scan: BacklogScan)
    /** Slice 8a: listed ids whose headers still have to be read (Gmail), kept pending. */
    fun addBacklogIds(accountId: String, ids: Collection<String>)
    fun backlogPending(accountId: String, limit: Int): List<String>
    /** Slice 8a: planned emails (a pending one becomes planned). */
    fun putBacklog(accountId: String, items: Collection<BacklogItem>)
    fun removeBacklog(accountId: String, id: String)
    /** Slice 8a: every planned email of the account's backlog. */
    fun backlogItems(accountId: String): List<BacklogItem>
    /** Inbox fixes, slice 2: forgets the account's backlog plan and scan, so the next check starts it again. */
    fun resetBacklog(accountId: String)
}

/** Slice 8a: where the backlog scan has got to: mail received before [beforeMs]; [nextPage] continues the listing. */
data class BacklogScan(val beforeMs: Long, val startedMs: Long, val nextPage: String?, val listed: Boolean, val readyMs: Long?)

/** Slice 8a: one older email and its plan (ids, sender and date only). */
data class BacklogItem(
    val id: String, val threadId: String?, val fromAddress: String?, val fromName: String?, val receivedAtMs: Long, val kind: MailBacklogKind,
)

/** Slice 5c: a sender's emails in the window, and how many of them were opened. */
data class SenderOpens(val total: Int, val opened: Int)

/** A noted sender as the server keeps it (slice 5a). [lastMessageId] is the id of its newest email (Outlook's headers are read from it). */
data class MailSenderRecord(
    val address: String,
    val name: String?,
    val listUnsubscribe: String?,
    val oneClick: Boolean,
    val headersChecked: Boolean,
    val lastMessageId: String?,
    val lastAtMs: Long,
    val seen: Int,
)

/** One email a tidy moved: its id before, the id it has now ([movedId]; Outlook's move changes it) and what was done. */
data class MailMoved(val id: String, val movedId: String, val tidy: MailTidy)

/** Slice 2b: the AI's answer per email id ("needs_you", "fyi", or null for none); null overall when it couldn't answer. */
fun interface MailAiSorter {
    fun lanes(emails: List<MailHeader>): Map<String, String?>?
}

class InMemoryMailStore : MailStore {
    private val cursors = mutableMapOf<String, String>()
    private val rows = mutableMapOf<String, LinkedHashMap<String, MailHeader?>>()
    private val sorts = mutableMapOf<String, MutableMap<String, MailSorted>>()
    private val tidies = mutableMapOf<Pair<String, String>, List<MailMoved>>()
    private val never = mutableMapOf<String, MutableSet<String>>()
    private val senders = mutableMapOf<String, LinkedHashMap<String, MailSenderRecord>>()
    private val scans = mutableMapOf<String, BacklogScan>()
    private val backlog = mutableMapOf<String, LinkedHashMap<String, BacklogItem?>>()

    private fun of(accountId: String) = rows.getOrPut(accountId) { LinkedHashMap() }

    @Synchronized override fun cursor(accountId: String) = cursors[accountId]
    @Synchronized override fun apply(householdId: String, accountId: String, changes: MailChanges) {
        val r = of(accountId)
        changes.headers.forEach { r[it.id] = it; sorts[accountId]?.remove(it.id) }
        changes.fetch.forEach { r[it] = null; sorts[accountId]?.remove(it) }
        changes.removed.forEach { r.remove(it); sorts[accountId]?.remove(it) }
        cursors[accountId] = changes.cursor
    }
    // Inbox fixes, slice 1: the newest listed first, so new mail isn't stuck behind a first listing still being read.
    @Synchronized override fun pending(accountId: String, limit: Int) = of(accountId).filterValues { it == null }.keys.reversed().take(limit)
    @Synchronized override fun put(householdId: String, accountId: String, header: MailHeader) { of(accountId)[header.id] = header; sorts[accountId]?.remove(header.id) }
    @Synchronized override fun remove(accountId: String, id: String) { of(accountId).remove(id); sorts[accountId]?.remove(id) }
    @Synchronized override fun markSeen(accountId: String, ids: Collection<String>) {
        val r = of(accountId)
        ids.forEach { id -> r[id]?.let { r[id] = it.copy(unread = false) } }
    }
    @Synchronized override fun prune(accountId: String, beforeMs: Long) {
        of(accountId).entries.removeIf { (id, h) -> (h != null && h.receivedAtMs < beforeMs).also { if (it) sorts[accountId]?.remove(id) } }
    }
    @Synchronized override fun forget(accountId: String) {
        rows.remove(accountId); cursors.remove(accountId); sorts.remove(accountId)
        tidies.keys.removeIf { it.first == accountId }; never.remove(accountId); senders.remove(accountId)
        scans.remove(accountId); backlog.remove(accountId)
    }
    private fun backlogOf(accountId: String) = backlog.getOrPut(accountId) { LinkedHashMap() }
    @Synchronized override fun backlogScan(accountId: String) = scans[accountId]
    @Synchronized override fun setBacklogScan(accountId: String, scan: BacklogScan) { scans[accountId] = scan }
    @Synchronized override fun addBacklogIds(accountId: String, ids: Collection<String>) { val b = backlogOf(accountId); ids.forEach { b.putIfAbsent(it, null) } }
    @Synchronized override fun backlogPending(accountId: String, limit: Int) = backlogOf(accountId).filterValues { it == null }.keys.take(limit)
    @Synchronized override fun putBacklog(accountId: String, items: Collection<BacklogItem>) { val b = backlogOf(accountId); items.forEach { b[it.id] = it } }
    @Synchronized override fun removeBacklog(accountId: String, id: String) { backlogOf(accountId).remove(id) }
    @Synchronized override fun backlogItems(accountId: String) = backlogOf(accountId).values.filterNotNull()
    @Synchronized override fun resetBacklog(accountId: String) { scans.remove(accountId); backlog.remove(accountId) }
    @Synchronized override fun messages(accountId: String) = of(accountId).values.filterNotNull().sortedByDescending { it.receivedAtMs }
    @Synchronized override fun lastFrom(accountId: String, addresses: Collection<String>, beforeMs: Long): Map<String, MailHeader> {
        val want = addresses.map { it.lowercase() }.toSet()
        return of(accountId).values.filterNotNull()
            .filter { h -> h.receivedAtMs <= beforeMs && h.fromAddress?.lowercase() in want }
            .groupBy { it.fromAddress!!.lowercase() }
            .mapValues { (_, hs) -> hs.maxWith(compareBy<MailHeader> { it.receivedAtMs }.thenBy { it.id }) }
    }
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
    @Synchronized override fun unsure(accountId: String, limit: Int): List<MailHeader> {
        val m = sorts[accountId].orEmpty()
        return messages(accountId).filter { h -> h.inInbox && m[h.id]?.let(MailSortRules::wantsAi) == true }.take(limit)
    }
    @Synchronized override fun inboxSorted(accountId: String): List<Pair<MailHeader, MailSorted>> {
        val m = sorts[accountId].orEmpty()
        return messages(accountId).filter { it.inInbox }.mapNotNull { h -> m[h.id]?.let { h to it } }
    }
    @Synchronized override fun recordTidy(accountId: String, batchId: String, moved: List<MailMoved>) {
        val ids = moved.map { it.id }.toSet()
        tidies[accountId to batchId] = tidies[accountId to batchId].orEmpty().filter { it.id !in ids } + moved
    }
    @Synchronized override fun tidied(accountId: String, batchId: String) = tidies[accountId to batchId].orEmpty()
    @Synchronized override fun neverTidy(accountId: String, ids: Collection<String>) { never.getOrPut(accountId) { mutableSetOf() } += ids }
    @Synchronized override fun neverTidied(accountId: String): Set<String> = never[accountId].orEmpty().toSet()
    @Synchronized override fun noteSender(householdId: String, accountId: String, h: MailHeader) {
        val address = h.fromAddress?.lowercase() ?: return
        val m = senders.getOrPut(accountId) { LinkedHashMap() }
        val prev = m[address]
        if (prev != null && (h.receivedAtMs < prev.lastAtMs || h.id == prev.lastMessageId)) return
        m[address] = MailSenderRecord(
            address, h.fromName ?: prev?.name, h.listUnsubscribe, h.oneClickUnsubscribe,
            headersChecked = h.listUnsubscribe != null,
            lastMessageId = h.id, lastAtMs = h.receivedAtMs, seen = (prev?.seen ?: 0) + 1,
        )
    }
    @Synchronized override fun senders(accountId: String, limit: Int) = senders[accountId].orEmpty().values
        .sortedWith(compareByDescending<MailSenderRecord> { it.seen }.thenByDescending { it.lastAtMs }.thenBy { it.address }).take(limit)
    @Synchronized override fun setSenderHeaders(accountId: String, address: String, listUnsubscribe: String?, oneClick: Boolean) {
        val m = senders[accountId] ?: return
        m[address]?.let { m[address] = it.copy(listUnsubscribe = listUnsubscribe, oneClick = oneClick, headersChecked = true) }
    }
    @Synchronized override fun previews(accountId: String, ids: Collection<String>): Map<String, String> =
        ids.mapNotNull { id -> of(accountId)[id]?.preview?.let { id to it } }.toMap()
    @Synchronized override fun openStats(accountId: String, sinceMs: Long): Map<String, SenderOpens> =
        of(accountId).values.filterNotNull().filter { it.receivedAtMs >= sinceMs && it.fromAddress != null }
            .groupBy { it.fromAddress!!.lowercase() }
            .mapValues { (_, hs) -> SenderOpens(hs.size, hs.count { !it.unread }) }
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
        c.prepareStatement("SELECT message_id FROM mail_message WHERE account_id = ? AND headers_at IS NULL ORDER BY created_at DESC, message_id LIMIT ?").use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun put(householdId: String, accountId: String, header: MailHeader) = c { c -> upsert(c, householdId, accountId, header) }

    private fun upsert(c: Connection, householdId: String, accountId: String, h: MailHeader) {
        c.prepareStatement(
            """INSERT INTO mail_message(account_id, message_id, household_id, thread_id, from_address, from_name, subject,
                 received_at_ms, unread, in_inbox, labels, list_unsubscribe, one_click_unsubscribe, list_id, preview, headers_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
               ON CONFLICT (account_id, message_id) DO UPDATE SET thread_id = EXCLUDED.thread_id,
                 from_address = EXCLUDED.from_address, from_name = EXCLUDED.from_name, subject = EXCLUDED.subject,
                 received_at_ms = EXCLUDED.received_at_ms, unread = EXCLUDED.unread, in_inbox = EXCLUDED.in_inbox,
                 labels = EXCLUDED.labels, list_unsubscribe = EXCLUDED.list_unsubscribe,
                 one_click_unsubscribe = EXCLUDED.one_click_unsubscribe, list_id = EXCLUDED.list_id,
                 preview = COALESCE(EXCLUDED.preview, mail_message.preview), headers_at = now(),
                 sort = NULL, sort_reason = NULL, sort_sure = NULL""",
        ).use { st ->
            st.setString(1, accountId); st.setString(2, h.id); st.setString(3, householdId); st.setString(4, h.threadId)
            st.setString(5, h.fromAddress); st.setString(6, h.fromName); st.setString(7, h.subject); st.setLong(8, h.receivedAtMs)
            st.setBoolean(9, h.unread); st.setBoolean(10, h.inInbox); st.setString(11, h.labels.joinToString(","))
            st.setString(12, h.listUnsubscribe); st.setBoolean(13, h.oneClickUnsubscribe); st.setString(14, h.listId)
            st.setString(15, h.preview)
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
            c.prepareStatement("DELETE FROM mail_tidy_item WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            c.prepareStatement("DELETE FROM mail_never_tidy WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            c.prepareStatement("DELETE FROM mail_sender WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            c.prepareStatement("DELETE FROM mail_backlog_scan WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
            c.prepareStatement("DELETE FROM mail_backlog_item WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
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

    override fun lastFrom(accountId: String, addresses: Collection<String>, beforeMs: Long): Map<String, MailHeader> {
        val want = addresses.map { it.lowercase() }.distinct()
        if (want.isEmpty()) return emptyMap()
        return c { c ->
            c.prepareStatement(
                """SELECT DISTINCT ON (lower(from_address)) message_id, thread_id, from_address, from_name, subject, received_at_ms,
                     unread, in_inbox, labels, list_unsubscribe, one_click_unsubscribe, list_id
                   FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL AND received_at_ms <= ?
                     AND lower(from_address) = ANY(?)
                   ORDER BY lower(from_address), received_at_ms DESC, message_id DESC""",
            ).use { st ->
                st.setString(1, accountId); st.setLong(2, beforeMs); st.setArray(3, c.createArrayOf("text", want.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildMap { while (rs.next()) { val h = rs.header(); h.fromAddress?.let { put(it.lowercase(), h) } } }
                }
            }
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

    override fun unsure(accountId: String, limit: Int): List<MailHeader> = c { c ->
        c.prepareStatement(
            """SELECT message_id, thread_id, from_address, from_name, subject, received_at_ms, unread, in_inbox, labels,
                 list_unsubscribe, one_click_unsubscribe, list_id
               FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL AND in_inbox
                 AND sort = 'NEEDS_YOU' AND sort_sure = FALSE
               ORDER BY received_at_ms DESC, message_id LIMIT ?""",
        ).use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.header()) } }
        }
    }

    override fun inboxSorted(accountId: String): List<Pair<MailHeader, MailSorted>> = c { c ->
        c.prepareStatement(
            """SELECT message_id, thread_id, from_address, from_name, subject, received_at_ms, unread, in_inbox, labels,
                 list_unsubscribe, one_click_unsubscribe, list_id, sort, sort_reason, sort_sure
               FROM mail_message WHERE account_id = ? AND headers_at IS NOT NULL AND in_inbox AND sort IS NOT NULL
               ORDER BY received_at_ms DESC, message_id""",
        ).use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val sort = os.meka.core.domain.MailSort.of(rs.getString(13)) ?: continue
                        val reason = os.meka.core.domain.MailSortReason.of(rs.getString(14)) ?: continue
                        add(rs.header() to MailSorted(sort, reason, rs.getBoolean(15)))
                    }
                }
            }
        }
    }

    override fun recordTidy(accountId: String, batchId: String, moved: List<MailMoved>) {
        if (moved.isEmpty()) return
        c { c ->
            c.prepareStatement(
                """INSERT INTO mail_tidy_item(account_id, batch_id, message_id, moved_id, tidy) VALUES (?,?,?,?,?)
                   ON CONFLICT (account_id, batch_id, message_id) DO UPDATE SET moved_id = EXCLUDED.moved_id, tidy = EXCLUDED.tidy""",
            ).use { st ->
                moved.forEach { m ->
                    st.setString(1, accountId); st.setString(2, batchId); st.setString(3, m.id); st.setString(4, m.movedId)
                    st.setString(5, m.tidy.name); st.addBatch()
                }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun tidied(accountId: String, batchId: String): List<MailMoved> = c { c ->
        c.prepareStatement("SELECT message_id, moved_id, tidy FROM mail_tidy_item WHERE account_id = ? AND batch_id = ? ORDER BY message_id").use { st ->
            st.setString(1, accountId); st.setString(2, batchId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) MailTidy.of(rs.getString(3))?.let { add(MailMoved(rs.getString(1), rs.getString(2), it)) } }
            }
        }
    }

    override fun markSeen(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        c { c ->
            c.prepareStatement("UPDATE mail_message SET unread = false WHERE account_id = ? AND message_id = ?").use { st ->
                ids.toSet().forEach { id -> st.setString(1, accountId); st.setString(2, id); st.addBatch() }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun neverTidy(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        c { c ->
            c.prepareStatement("INSERT INTO mail_never_tidy(account_id, message_id) VALUES (?,?) ON CONFLICT DO NOTHING").use { st ->
                ids.toSet().forEach { id -> st.setString(1, accountId); st.setString(2, id); st.addBatch() }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun neverTidied(accountId: String): Set<String> = c { c ->
        c.prepareStatement("SELECT message_id FROM mail_never_tidy WHERE account_id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    // Newer email only: the newest one's name and headers win and the count rises; a re-read of the same one changes nothing.
    override fun noteSender(householdId: String, accountId: String, h: MailHeader) {
        val address = h.fromAddress?.lowercase() ?: return
        c { c ->
            c.prepareStatement(
                """INSERT INTO mail_sender(account_id, address, household_id, name, list_unsubscribe, one_click, headers_checked,
                     last_message_id, last_at_ms, seen_count) VALUES (?,?,?,?,?,?,?,?,?,1)
                   ON CONFLICT (account_id, address) DO UPDATE SET
                     name = COALESCE(EXCLUDED.name, mail_sender.name), list_unsubscribe = EXCLUDED.list_unsubscribe,
                     one_click = EXCLUDED.one_click, headers_checked = EXCLUDED.headers_checked,
                     last_message_id = EXCLUDED.last_message_id, last_at_ms = EXCLUDED.last_at_ms,
                     seen_count = mail_sender.seen_count + 1, updated_at = now()
                   WHERE EXCLUDED.last_at_ms >= mail_sender.last_at_ms AND EXCLUDED.last_message_id IS DISTINCT FROM mail_sender.last_message_id""",
            ).use { st ->
                st.setString(1, accountId); st.setString(2, address); st.setString(3, householdId); st.setString(4, h.fromName)
                st.setString(5, h.listUnsubscribe); st.setBoolean(6, h.oneClickUnsubscribe); st.setBoolean(7, h.listUnsubscribe != null)
                st.setString(8, h.id); st.setLong(9, h.receivedAtMs)
                st.executeUpdate()
            }
            Unit
        }
    }

    override fun senders(accountId: String, limit: Int): List<MailSenderRecord> = c { c ->
        c.prepareStatement(
            """SELECT address, name, list_unsubscribe, one_click, headers_checked, last_message_id, last_at_ms, seen_count
               FROM mail_sender WHERE account_id = ? ORDER BY seen_count DESC, last_at_ms DESC, address LIMIT ?""",
        ).use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(MailSenderRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6), rs.getLong(7), rs.getInt(8),
                    ))
                }
            }
        }
    }

    override fun setSenderHeaders(accountId: String, address: String, listUnsubscribe: String?, oneClick: Boolean) = c { c ->
        c.prepareStatement("UPDATE mail_sender SET list_unsubscribe = ?, one_click = ?, headers_checked = TRUE, updated_at = now() WHERE account_id = ? AND address = ?").use { st ->
            st.setString(1, listUnsubscribe); st.setBoolean(2, oneClick); st.setString(3, accountId); st.setString(4, address)
            st.executeUpdate()
        }
        Unit
    }

    override fun previews(accountId: String, ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return c { c ->
            c.prepareStatement("SELECT message_id, preview FROM mail_message WHERE account_id = ? AND message_id = ANY(?) AND preview IS NOT NULL").use { st ->
                st.setString(1, accountId); st.setArray(2, c.createArrayOf("text", ids.toTypedArray()))
                st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
        }
    }

    override fun openStats(accountId: String, sinceMs: Long): Map<String, SenderOpens> = c { c ->
        c.prepareStatement(
            """SELECT lower(from_address), count(*), count(*) FILTER (WHERE NOT unread) FROM mail_message
               WHERE account_id = ? AND headers_at IS NOT NULL AND from_address IS NOT NULL AND received_at_ms >= ?
               GROUP BY lower(from_address)""",
        ).use { st ->
            st.setString(1, accountId); st.setLong(2, sinceMs)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), SenderOpens(rs.getInt(2), rs.getInt(3))) } }
        }
    }

    override fun backlogScan(accountId: String): BacklogScan? = c { c ->
        c.prepareStatement("SELECT before_ms, started_ms, next_page, listed, ready_ms FROM mail_backlog_scan WHERE account_id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else BacklogScan(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5).takeIf { !rs.wasNull() })
            }
        }
    }

    override fun setBacklogScan(accountId: String, scan: BacklogScan) = c { c ->
        c.prepareStatement(
            """INSERT INTO mail_backlog_scan(account_id, before_ms, started_ms, next_page, listed, ready_ms) VALUES (?,?,?,?,?,?)
               ON CONFLICT (account_id) DO UPDATE SET before_ms = EXCLUDED.before_ms, started_ms = EXCLUDED.started_ms,
                 next_page = EXCLUDED.next_page, listed = EXCLUDED.listed, ready_ms = EXCLUDED.ready_ms, updated_at = now()""",
        ).use { st ->
            st.setString(1, accountId); st.setLong(2, scan.beforeMs); st.setLong(3, scan.startedMs); st.setString(4, scan.nextPage)
            st.setBoolean(5, scan.listed)
            if (scan.readyMs == null) st.setNull(6, java.sql.Types.BIGINT) else st.setLong(6, scan.readyMs)
            st.executeUpdate()
        }
        Unit
    }

    override fun addBacklogIds(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        c { c ->
            c.prepareStatement("INSERT INTO mail_backlog_item(account_id, message_id) VALUES (?,?) ON CONFLICT DO NOTHING").use { st ->
                ids.toSet().forEach { id -> st.setString(1, accountId); st.setString(2, id); st.addBatch() }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun backlogPending(accountId: String, limit: Int): List<String> = c { c ->
        c.prepareStatement("SELECT message_id FROM mail_backlog_item WHERE account_id = ? AND kind IS NULL ORDER BY created_at, message_id LIMIT ?").use { st ->
            st.setString(1, accountId); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun putBacklog(accountId: String, items: Collection<BacklogItem>) {
        if (items.isEmpty()) return
        c { c ->
            c.prepareStatement(
                """INSERT INTO mail_backlog_item(account_id, message_id, thread_id, from_address, from_name, received_at_ms, kind)
                   VALUES (?,?,?,?,?,?,?)
                   ON CONFLICT (account_id, message_id) DO UPDATE SET thread_id = EXCLUDED.thread_id, from_address = EXCLUDED.from_address,
                     from_name = EXCLUDED.from_name, received_at_ms = EXCLUDED.received_at_ms, kind = EXCLUDED.kind""",
            ).use { st ->
                items.forEach { b ->
                    st.setString(1, accountId); st.setString(2, b.id); st.setString(3, b.threadId); st.setString(4, b.fromAddress)
                    st.setString(5, b.fromName); st.setLong(6, b.receivedAtMs); st.setString(7, b.kind.name); st.addBatch()
                }
                st.executeBatch()
            }
            Unit
        }
    }

    override fun removeBacklog(accountId: String, id: String) = c { c ->
        c.prepareStatement("DELETE FROM mail_backlog_item WHERE account_id = ? AND message_id = ?").use { st ->
            st.setString(1, accountId); st.setString(2, id); st.executeUpdate()
        }
        Unit
    }

    override fun resetBacklog(accountId: String) = c { c ->
        c.prepareStatement("DELETE FROM mail_backlog_item WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
        c.prepareStatement("DELETE FROM mail_backlog_scan WHERE account_id = ?").use { st -> st.setString(1, accountId); st.executeUpdate() }
        Unit
    }

    override fun backlogItems(accountId: String): List<BacklogItem> = c { c ->
        c.prepareStatement(
            "SELECT message_id, thread_id, from_address, from_name, received_at_ms, kind FROM mail_backlog_item WHERE account_id = ? AND kind IS NOT NULL ORDER BY message_id",
        ).use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) MailBacklogKind.of(rs.getString(6))?.let { k ->
                        add(BacklogItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), k))
                    }
                }
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
