package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.FuelKind
import os.meka.core.domain.FuelRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.FuelCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Car and parking, slice 6 through the facade: rounded before it leaves, one ask per fuel the cars take. */
class FuelFacadeTest {
    private val now = 1_792_000_000_000L

    private inner class Server(service: SyncService) : SyncTransport, FuelApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Triple<Double, Double, String>>()
        var state = FuelCodec.Response.OK
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun fuelNearby(lat: Double, lon: Double, fuel: String): FuelCodec.Response {
            asked += Triple(lat, lon, fuel)
            if (down) throw TransportException("network error: offline")
            if (state != FuelCodec.Response.OK) return FuelCodec.Response(state)
            val p = if (fuel == "B7") 150.9 else 139.9
            return FuelCodec.Response(
                FuelCodec.Response.OK,
                listOf(FuelCodec.Station("Oak Road", "Fuelco", "AB1 2CD", 52.12, -0.3, p, now - 3_600_000L, 2_200.0)),
            )
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun onlyTheRoundedPointGoesAndEachFuelGetsItsBlock() = runTest {
        val c = core()
        val one = c.fuelNearby(52.104321, -0.298765)
        assertEquals(listOf(Triple(52.1, -0.3, "E10")), server.asked)
        assertEquals(FuelRules.OK, one.state)
        assertEquals(1, one.blockCount)
        assertEquals("UNLEADED (E10)", one.blockAt(0).heading)
        assertEquals("139.9p", one.blockAt(0).rowAt(0).price)

        // A second car on diesel: two blocks, each naming its car.
        val golf = c.addCar("the Golf")!!
        assertTrue(c.setCarFuel(golf, "B7"))
        assertFalse(c.setCarFuel(golf, "LPG"))
        assertEquals(FuelKind.B7, c.carsView.value.cars.first { it.carId == golf }.fuel)
        server.asked.clear()
        val two = c.fuelNearby(52.104321, -0.298765)
        assertEquals(listOf("E10", "B7"), server.asked.map { it.third })
        assertEquals(listOf("UNLEADED (E10) · CAR 1", "DIESEL · THE GOLF"), two.blocks.map { it.heading })
    }

    @Test
    fun whatIsMissingIsSaidNeverThrown() = runTest {
        val c = core()
        assertEquals(FuelRules.NO_LOCATION, c.fuelNearby(Double.NaN, Double.NaN).state)
        assertTrue(server.asked.isEmpty())
        server.state = FuelCodec.Response.OFF
        assertEquals(FuelRules.OFF_LINE, c.fuelNearby(52.1, -0.3).line)
        server.state = FuelCodec.Response.LOADING
        assertEquals(FuelRules.LOADING, c.fuelNearby(52.1, -0.3).state)
        server.down = true
        assertEquals(FuelRules.OFFLINE, c.fuelNearby(52.1, -0.3).state)
        val bare = MekaCore(householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null, secureRandom = Random(1), nowMs = { now })
        assertEquals(FuelRules.OFF, bare.fuelNearby(52.1, -0.3).state)
    }
}
