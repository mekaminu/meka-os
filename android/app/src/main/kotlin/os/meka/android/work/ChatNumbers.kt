package os.meka.android.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import os.meka.core.domain.ContactPhone
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.TriageCard
import os.meka.core.domain.TriageReplyRules

/**
 * WhatsApp Open chat lands in the chat (2026-10-29): WhatsApp's notifications name a 1:1 chat's person but carry no
 * number, so Open chat could only open WhatsApp's home. Here, on the Fold, each such card's number is looked up from the
 * Family and always-notify lists, then the phone's contacts of exactly that name ([TriageReplyRules.lookupNumber]: one
 * match only, never a guess). Read with the contacts permission he already granted, held in memory for the screen only:
 * never stored, synced or sent. Opening the link sends nothing; he taps WhatsApp's own send (Level 3 unchanged).
 */
object ChatNumbers {
    /** Card id → number for every card in [cards] that needs one and has exactly one match. */
    fun find(context: Context, cards: List<TriageCard>, lists: PeopleLists): Map<String, String> {
        val want = cards.filter(TriageReplyRules::needsLookup)
        if (want.isEmpty()) return emptyMap()
        val canRead = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val byName = HashMap<String, String?>()
        val out = HashMap<String, String>()
        want.forEach { card ->
            val name = TriageReplyRules.who(card).trim()
            val number = byName.getOrPut(name.lowercase()) {
                val contacts = if (canRead) runCatching { contactsNamed(context, name) }.getOrDefault(emptyList()) else emptyList()
                TriageReplyRules.lookupNumber(name, lists, contacts)
            }
            if (number != null) out[card.id] = number
        }
        return out
    }

    /** The phone numbers of contacts whose name is exactly [name] (case aside). */
    private fun contactsNamed(context: Context, name: String): List<ContactPhone> {
        val cols = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE)
        val out = ArrayList<ContactPhone>()
        context.contentResolver.query(
            Phone.CONTENT_URI, cols, "${Phone.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(name), null,
        )?.use { c ->
            while (c.moveToNext() && out.size < 50) {
                val number = c.getString(2) ?: continue
                out += ContactPhone(c.getLong(0).toString(), c.getString(1).orEmpty(), number, c.getInt(3) == Phone.TYPE_MOBILE)
            }
        }
        return out
    }
}
