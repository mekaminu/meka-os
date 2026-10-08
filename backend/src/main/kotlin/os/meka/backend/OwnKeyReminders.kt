package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.OwnKey
import os.meka.core.domain.OwnKeyRules
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore

/**
 * Puts MEKA's own keys that run out (the Outlook sign-in secret, the AI key; core `OwnKeyRules`) on each household's
 * renewals radar, once, as server-authored ops with fixed op ids, and wakes the devices when it wrote any. Every field's
 * op is written only if that op id isn't stored yet, so running again (every poll) writes nothing, Meka's later edits
 * (newer ops) win, and one he deleted or stopped tracking never comes back.
 *
 * End dates sit at 09:00 UTC on their day (10:00 in British summer time, still the same day in London).
 */
class OwnKeyReminders(
    private val ops: ServerOpStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val keys: List<OwnKey> = OwnKeyRules.ALL,
    /** Called after something was written for a household, so push can wake its devices. */
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)

    /** Returns whether anything was written for [householdId]. */
    fun ensure(householdId: String): Boolean {
        val wrote = ops.transaction {
            var appended = false
            for (key in keys) {
                for ((field, value) in OwnKeyRules.fields(key, now(), LocalCalendar.UTC)) {
                    val opId = opId(key.id, field)
                    if (ops.find(householdId, opId) != null) continue
                    ops.append(
                        Op(
                            opId = opId, householdId = householdId, entityType = EntityTypes.OBLIGATION, entityId = key.id,
                            field = field, value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = emptyList(),
                            deviceId = Integrations.SERVER_DEVICE,
                        ),
                    )
                    appended = true
                }
            }
            appended
        }
        if (wrote) runCatching { onWritten(householdId) }
        return wrote
    }

    companion object {
        fun opId(keyId: String, field: String) = "srvkey$keyId${field.lowercase().filter(Char::isLetterOrDigit)}"
    }
}
