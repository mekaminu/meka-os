package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.ParcelFolded
import os.meka.core.domain.ParcelRules
import os.meka.core.domain.ParcelState
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 3g: the server's parcels reach Today's line on both devices; a delivered one drops off. */
class ParcelFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvpc${k++}", "hh", EntityTypes.PARCEL, id, f, v, clock.now(), emptyList(), "server"))
    }

    @Test
    fun parcelsArrivingTodayShowOnTodayAndADeliveryClearsThem() = runTest {
        val today = CivilDate.toEpochDay(2026, 10, 17)
        val a = ParcelRules.entityId("acc1", "112-4455")
        serverWrite(a, ParcelRules.fields(ParcelFolded("112-4455", ParcelState.OUT_FOR_DELIVERY, today, "10:15–11:15", "DPD", "Shop", now), "Personal"))
        serverWrite(ParcelRules.entityId("acc1", "m:x"), ParcelRules.fields(ParcelFolded("m:x", ParcelState.COMING, today + 1, null, null, "Other", now), "Personal"))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("Parcel out for delivery · Shop · 10:15–11:15", fold.today.value.parcels?.text)
        assertEquals(true, mac.today.value.parcels?.lit)
        serverWrite(a, ParcelRules.fields(ParcelFolded("112-4455", ParcelState.DELIVERED, today, "10:15–11:15", "DPD", "Shop", now), "Personal"))
        assertTrue(fold.syncNow())
        assertNull(fold.today.value.parcels)
    }
}
