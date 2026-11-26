package com.namesminer.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Good-neighbour rules shared by SMS and email sending:
 *  - each person gets at most MAX_PER_DAY messages in any 24 hours,
 *  - at least MIN_GAP_MS apart,
 *  - only inside the chosen daytime window.
 */
object GoodNeighbour {
    const val MAX_PER_DAY = 2
    const val MIN_GAP_MS = 6 * 60 * 60 * 1000L
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Earliest time this person may be messaged again (0 or a past time = now is fine). */
    fun nextAllowedAt(history: List<Long>?, now: Long): Long {
        val recent = history.orEmpty().filter { now - it < DAY_MS }.sorted()
        if (recent.isEmpty()) return 0L
        var t = recent.last() + MIN_GAP_MS
        if (recent.size >= MAX_PER_DAY) t = maxOf(t, recent[recent.size - MAX_PER_DAY] + DAY_MS)
        return t
    }

    fun inWindow(start: Int, end: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (start < 0 || end < 0 || start == end) return true
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return if (start < end) h in start until end else (h >= start || h < end)
    }

    fun when_(t: Long): String {
        val now = System.currentTimeMillis()
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(t)) == it.format(Date(now)) }
        return SimpleDateFormat(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.getDefault()).format(Date(t))
    }
}

/** The 3-digit selection code for a phone number. */
object PhonePrefix {
    // Two-digit country calling codes; 1 and 7 are one digit, every other code is three digits (ITU codes are prefix-free).
    private val TWO = setOf(
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98"
    )

    fun split(digits: String): Pair<String, String> {
        if (digits.isEmpty()) return "" to ""
        if (digits[0] == '1' || digits[0] == '7') return digits.substring(0, 1) to digits.substring(1)
        if (digits.length >= 2 && digits.substring(0, 2) in TWO) return digits.substring(0, 2) to digits.substring(2)
        val n = minOf(3, digits.length)
        return digits.substring(0, n) to digits.substring(n)
    }

    /**
     * +1 numbers (North America and Caribbean): the 3 digits after the area code, e.g. +1 246 823 4567 → 823.
     * Other countries: the first 3 digits of the national number, e.g. +234 803 555 0000 → 803.
     */
    fun localPrefix(number: String, e164: String?): String {
        val n = number.trim()
        val intl = when {
            !e164.isNullOrBlank() -> e164
            n.startsWith("+") -> n
            n.startsWith("00") -> "+" + n.filter { it.isDigit() }.drop(2)
            else -> null
        }
        if (intl != null) {
            val (cc, nat) = split(intl.filter { it.isDigit() })
            return if (cc == "1") nat.drop(3).take(3) else nat.take(3)
        }
        val d = n.filter { it.isDigit() }
        return when {
            d.length == 7 -> d.take(3)
            d.length == 11 && d.startsWith("1") -> d.drop(4).take(3)
            d.length == 10 && !d.startsWith("0") -> d.drop(3).take(3)
            else -> d.trimStart('0').take(3)
        }
    }
}
