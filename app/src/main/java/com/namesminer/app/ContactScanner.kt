package com.namesminer.app

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data

/** Reads the phone's contacts and returns every number belonging to a contact tagged with the identifier. */
object ContactScanner {
    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    private class PhoneRow(val number: String, val normalized: String?, val type: Int, val label: String?)

    private class Contact {
        var display = ""
        var given: String? = null
        val names = mutableListOf<String>()
        val phones = mutableListOf<PhoneRow>()
    }

    // Joins dotted initials so "C.L." or "c.l" is read as the word "CL".
    private val dottedInitials = Regex("(?<![\\p{L}\\p{N}])(\\p{L})\\.\\s?(\\p{L})\\.?(?![\\p{L}\\p{N}])")

    private fun words(text: String): List<String> =
        text.replace(dottedInitials, "$1$2").split(splitter).filter { it.isNotBlank() }

    /** Parses "CL, Coopmed" into separate tags. */
    fun parseTags(raw: String): List<String> =
        raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * True only when a tag appears as its own word, in any letter case:
     * "CL John", "john cl", "Smith-Cl", "C.L. Brown", "COOPMED Jane" match;
     * "Clarke", "Michael", "Clyde" do not.
     */
    fun matches(text: String?, tags: List<String>): Boolean {
        if (text.isNullOrBlank() || tags.isEmpty()) return false
        val w = words(text)
        return tags.any { tag ->
            if (tag.all { it.isLetterOrDigit() }) w.any { it.equals(tag, ignoreCase = true) }
            else text.contains(tag, ignoreCase = true)
        }
    }

    /** First real word of the name that isn't a tag, for the {name} placeholder. */
    fun firstName(candidates: List<String>, tags: List<String>): String {
        for (c in candidates) {
            val word = words(c).firstOrNull { wd ->
                wd.any { it.isLetter() } && tags.none { wd.equals(it, ignoreCase = true) }
            }
            if (word != null) return word
        }
        return ""
    }

    /** Key used to spot the same number saved twice. Prefers Android's E.164 form (+countrycode…). */
    fun normalize(number: String, e164: String?): String {
        if (!e164.isNullOrBlank()) return e164
        val plus = number.trim().startsWith("+")
        val digits = number.filter { it.isDigit() }
        return (if (plus) "+" else "") + digits
    }

    fun scan(
        ctx: Context,
        tags: List<String>,
        mobileOnly: Boolean
    ): List<Recipient> {
        val contacts = LinkedHashMap<Long, Contact>()
        val projection = arrayOf(
            Data.CONTACT_ID, Data.MIMETYPE, Data.DISPLAY_NAME,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6
        )
        ctx.contentResolver.query(
            Data.CONTENT_URI,
            projection,
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val contact = contacts.getOrPut(c.getLong(0)) { Contact() }
                val display = c.getString(2)
                if (contact.display.isEmpty() && display != null) contact.display = display
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        // DATA1 display, DATA2 first, DATA3 surname, DATA4 prefix, DATA5 middle, DATA6 suffix
                        for (col in 3..8) {
                            val v = c.getString(col)
                            if (!v.isNullOrBlank()) contact.names.add(v)
                        }
                        if (contact.given == null) contact.given = c.getString(4)
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        // DATA1 number, DATA2 type, DATA3 custom label, DATA4 normalized (E.164)
                        val num = c.getString(3)
                        if (!num.isNullOrBlank()) {
                            val type = c.getString(4)?.toIntOrNull() ?: Phone.TYPE_OTHER
                            contact.phones.add(PhoneRow(num, c.getString(6), type, c.getString(5)))
                        }
                    }
                }
            }
        }

        val out = LinkedHashMap<String, Recipient>()
        for (ct in contacts.values) {
            val allNames = ct.names + ct.display
            if (allNames.none { matches(it, tags) }) continue
            val first = firstName(listOfNotNull(ct.given, ct.display), tags)
            for (p in ct.phones) {
                if (mobileOnly && p.type != Phone.TYPE_MOBILE && p.type != Phone.TYPE_WORK_MOBILE) continue
                val key = normalize(p.number, p.normalized)
                if (key.count { it.isDigit() } < 3 || out.containsKey(key)) continue
                val label = Phone.getTypeLabel(ctx.resources, p.type, p.label).toString()
                out[key] = Recipient(
                    id = key,
                    name = ct.display.ifBlank { p.number },
                    firstName = first,
                    number = p.number.trim(),
                    label = label
                )
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }
}
