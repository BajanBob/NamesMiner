package com.namesminer.app

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data

/** Reads the phone's contacts and returns the numbers or email addresses to message. */
object ContactScanner {
    private val splitter = Regex("[^\\p{L}\\p{N}]+")
    // Joins dotted initials so "C.L." or "c.l" is read as the word "CL".
    private val dottedInitials = Regex("(?<![\\p{L}\\p{N}])(\\p{L})\\.\\s?(\\p{L})\\.?(?![\\p{L}\\p{N}])")
    private val emailRe = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    private class PhoneRow(val number: String, val normalized: String?, val type: Int, val label: String?)
    private class EmailRow(val address: String, val type: Int, val label: String?)

    private class Contact {
        var display = ""
        var given: String? = null
        val names = mutableListOf<String>()
        val phones = mutableListOf<PhoneRow>()
        val emails = mutableListOf<EmailRow>()
    }

    private fun words(text: String): List<String> =
        text.replace(dottedInitials, "$1$2").split(splitter).filter { it.isNotBlank() }

    fun parseTags(raw: String): List<String> =
        raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** True only when a tag appears as its own word, in any letter case ("CL John", "Smith-cl", "C.L. Brown"). */
    fun matches(text: String?, tags: List<String>): Boolean {
        if (text.isNullOrBlank() || tags.isEmpty()) return false
        val w = words(text)
        return tags.any { tag ->
            if (tag.all { it.isLetterOrDigit() }) w.any { it.equals(tag, ignoreCase = true) }
            else text.contains(tag, ignoreCase = true)
        }
    }

    fun firstName(candidates: List<String>, tags: List<String>): String {
        for (c in candidates) {
            val word = words(c).firstOrNull { wd ->
                wd.any { it.isLetter() } && tags.none { wd.equals(it, ignoreCase = true) }
            }
            if (word != null) return word
        }
        return ""
    }

    fun normalize(number: String, e164: String?): String {
        if (!e164.isNullOrBlank()) return e164
        val plus = number.trim().startsWith("+")
        val digits = number.filter { it.isDigit() }
        return (if (plus) "+" else "") + digits
    }

    /** Every email address in a harvested list (CSV or plain text), lower-cased, in order, no duplicates. */
    fun emailsIn(text: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val m = emailRe.find(line) ?: continue
            val addr = m.value.lowercase()
            if (out.containsKey(addr)) continue
            // In the harvesting tool's CSV the name is the second column.
            val cols = line.split(',').map { it.trim().trim('"') }
            val name = if (cols.size > 1 && cols[0].equals(m.value, true)) cols[1] else ""
            out[addr] = name
        }
        return out.map { it.key to it.value }
    }

    /**
     * @param tags          client tags; ignored when allContacts is true
     * @param prefixes      up to three 3-digit codes; a contact qualifies if one of its numbers starts with one
     * @param email         true = return email addresses, false = return phone numbers
     */
    fun scan(
        ctx: Context,
        tags: List<String>,
        allContacts: Boolean,
        prefixes: List<String>,
        email: Boolean,
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
            "${Data.MIMETYPE} IN (?, ?, ?)",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val contact = contacts.getOrPut(c.getLong(0)) { Contact() }
                val display = c.getString(2)
                if (contact.display.isEmpty() && display != null) contact.display = display
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        for (col in 3..8) {
                            val v = c.getString(col)
                            if (!v.isNullOrBlank()) contact.names.add(v)
                        }
                        if (contact.given == null) contact.given = c.getString(4)
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val num = c.getString(3)
                        if (!num.isNullOrBlank()) {
                            val type = c.getString(4)?.toIntOrNull() ?: Phone.TYPE_OTHER
                            contact.phones.add(PhoneRow(num, c.getString(6), type, c.getString(5)))
                        }
                    }
                    Email.CONTENT_ITEM_TYPE -> {
                        val addr = c.getString(3)?.trim()
                        if (!addr.isNullOrBlank() && addr.contains('@')) {
                            val type = c.getString(4)?.toIntOrNull() ?: Email.TYPE_OTHER
                            contact.emails.add(EmailRow(addr, type, c.getString(5)))
                        }
                    }
                }
            }
        }

        val out = LinkedHashMap<String, Recipient>()
        for (ct in contacts.values) {
            val allNames = ct.names + ct.display
            if (!allContacts && allNames.none { matches(it, tags) }) continue
            val phones = ct.phones.filter { !mobileOnly || email || it.type == Phone.TYPE_MOBILE || it.type == Phone.TYPE_WORK_MOBILE }
            val withPrefix = phones.map { it to PhonePrefix.localPrefix(it.number, it.normalized) }
            val qualifying = if (prefixes.isEmpty()) withPrefix else withPrefix.filter { (_, p) -> prefixes.any { p.startsWith(it) } }
            if (prefixes.isNotEmpty() && qualifying.isEmpty()) continue
            val first = firstName(listOfNotNull(ct.given, ct.display), tags)

            if (!email) {
                for ((p, pre) in qualifying) {
                    val key = normalize(p.number, p.normalized)
                    if (key.count { it.isDigit() } < 3 || out.containsKey(key)) continue
                    out[key] = Recipient(
                        id = key,
                        name = ct.display.ifBlank { p.number },
                        firstName = first,
                        number = p.number.trim(),
                        label = Phone.getTypeLabel(ctx.resources, p.type, p.label).toString(),
                        selected = !allContacts,
                        prefix = pre
                    )
                }
            } else {
                val pre = qualifying.firstOrNull()?.second ?: ""
                for (e in ct.emails) {
                    val key = "mailto:" + e.address.lowercase()
                    if (out.containsKey(key)) continue
                    out[key] = Recipient(
                        id = key,
                        name = ct.display.ifBlank { e.address },
                        firstName = first,
                        number = e.address,
                        label = Email.getTypeLabel(ctx.resources, e.type, e.label).toString(),
                        selected = !allContacts,
                        prefix = pre
                    )
                }
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }
}
