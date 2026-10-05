package net.ithandsfree.softphone.data

import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract

data class DeviceContact(
    val id: Long,
    val displayName: String,
    val phones: List<ContactPhone>,
)

data class ContactPhone(
    val number: String,
    val label: String,
)

/**
 * Device address book (primary). FreePBX/Sangoma PBX directory can be added later
 * as a secondary source — less used in IHF deployments.
 */
class DeviceContactsRepository(private val context: Context) {

    fun loadContacts(limit: Int = 500): List<DeviceContact> {
        val cr = context.contentResolver
        val map = linkedMapOf<Long, MutableList<ContactPhone>>()
        val names = linkedMapOf<Long, String>()

        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
            while (c.moveToNext() && names.size < limit) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx)?.trim().orEmpty().ifBlank { "Unknown" }
                val number = c.getString(numIdx)?.trim().orEmpty()
                if (number.isBlank()) continue
                val type = c.getInt(typeIdx)
                val custom = c.getString(labelIdx)
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    type,
                    custom,
                ).toString()
                names[id] = name
                map.getOrPut(id) { mutableListOf() }.add(ContactPhone(number, label))
            }
        }

        return names.map { (id, name) ->
            DeviceContact(id = id, displayName = name, phones = map[id].orEmpty().distinctBy { it.number })
        }
    }

    fun contactById(id: Long): DeviceContact? {
        return loadContacts().firstOrNull { it.id == id }
    }

    fun photoUri(contactId: Long) =
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
}
