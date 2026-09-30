package os.meka.core.sync

/**
 * Hybrid logical clock timestamp (ADR-003).
 *
 * Total order: wall time, then counter, then node id. Two distinct nodes can never produce
 * equal timestamps because the node id participates in the ordering.
 */
data class Hlc(val wallMs: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    init {
        require(wallMs >= 0) { "wallMs must be >= 0" }
        require(counter >= 0) { "counter must be >= 0" }
    }

    override fun compareTo(other: Hlc): Int {
        if (wallMs != other.wallMs) return wallMs.compareTo(other.wallMs)
        if (counter != other.counter) return counter.compareTo(other.counter)
        return node.compareTo(other.node)
    }

    /** Fixed-width encoding whose lexicographic order equals [compareTo]; used for DB columns. */
    fun encode(): String = wallMs.toString().padStart(15, '0') + "-" +
        counter.toString().padStart(6, '0') + "-" + node

    override fun toString(): String = encode()

    companion object {
        val ZERO = Hlc(0, 0, "")

        fun decode(s: String): Hlc {
            val first = s.indexOf('-')
            val second = s.indexOf('-', first + 1)
            require(first > 0 && second > first) { "Malformed HLC: $s" }
            return Hlc(s.substring(0, first).toLong(), s.substring(first + 1, second).toInt(), s.substring(second + 1))
        }
    }
}

class ClockDriftException(message: String) : IllegalStateException(message)

/**
 * A node's clock. Not thread-safe by itself; the [Replica] serialises access.
 *
 * @param maxDriftMs remote timestamps further than this ahead of local physical time are rejected,
 * which bounds the damage a device with a wildly wrong clock can do to everyone's ordering.
 */
class HlcClock(
    val node: String,
    private val physicalNow: () -> Long,
    private val maxDriftMs: Long = 24L * 60 * 60 * 1000,
) {
    init {
        require(node.isNotBlank() && !node.contains('-')) { "node id must be non-blank and contain no '-'" }
    }

    private var last: Hlc = Hlc.ZERO

    val latest: Hlc get() = last

    /** Timestamp for a local event. */
    fun now(): Hlc {
        val pt = physicalNow()
        last = if (pt > last.wallMs) Hlc(pt, 0, node) else Hlc(last.wallMs, last.counter + 1, node)
        return last
    }

    /** Advance on receipt of a remote timestamp. */
    fun receive(remote: Hlc) {
        val pt = physicalNow()
        if (remote.wallMs - pt > maxDriftMs) {
            throw ClockDriftException("Remote HLC ${remote.wallMs} is more than ${maxDriftMs}ms ahead of local $pt")
        }
        val wall = maxOf(pt, last.wallMs, remote.wallMs)
        val counter = when {
            wall == last.wallMs && wall == remote.wallMs -> maxOf(last.counter, remote.counter) + 1
            wall == last.wallMs -> last.counter + 1
            wall == remote.wallMs -> remote.counter + 1
            else -> 0
        }
        last = Hlc(wall, counter, node)
    }

    /** Restore after process restart so timestamps never go backwards. */
    fun restore(persisted: Hlc) {
        if (persisted > last) last = Hlc(persisted.wallMs, persisted.counter, node)
    }
}
