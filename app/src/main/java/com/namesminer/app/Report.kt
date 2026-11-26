package com.namesminer.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the outcome report: what happened to every message, plus plain-language insights. */
object Report {

    private fun pct(a: Int, b: Int) = if (b == 0) "–" else "${(a * 100.0 / b).roundToInt()}%"

    private fun line(r: Recipient): String {
        val note = if (r.note.isNotBlank()) " — ${r.note}" else ""
        return "• ${r.name} (${r.number})$note"
    }

    fun build(all: List<Recipient>, now: Long = System.currentTimeMillis()): String {
        val recs = all.filter { it.selected || it.status != Status.NEW }
        val failed = recs.filter { it.status == Status.FAILED }
        val sentNoReport = recs.filter { it.status == Status.SENT && !it.isEmail }
        val emailed = recs.filter { it.status == Status.SENT && it.isEmail }
        val held = recs.filter { it.status == Status.HELD }
        val delivered = recs.filter { it.status == Status.DELIVERED }
        val replied = recs.filter { it.status == Status.CONFIRMED }
        val optedOut = recs.filter { it.status == Status.OPTED_OUT }
        val skipped = recs.filter { it.status == Status.SKIPPED }
        val waiting = recs.filter { it.selected && (it.status == Status.NEW || it.status == Status.QUEUED) }
        val messaged = failed.size + sentNoReport.size + delivered.size + replied.size + emailed.size
        val reached = delivered.size + replied.size
        val stale = sentNoReport.filter { it.sentAt > 0 && now - it.sentAt > 2 * 3600_000L }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))

        return buildString {
            append("NAMES MINER REPORT — $stamp\n\n")
            append("Messaged: $messaged · Delivered: $reached (${pct(reached, messaged)}) · Replied: ${replied.size} (${pct(replied.size, reached)} of delivered)\n")
            append("Didn't go through: ${failed.size} · No delivery report yet: ${sentNoReport.size} · Emails handed to email app: ${emailed.size} · Held by good-neighbour limit: ${held.size} · Opted out: ${optedOut.size} · Duplicates blocked: ${skipped.size} · Still to send: ${waiting.size}\n\n")

            append("WHAT IT MEANS\n")
            if (messaged == 0) append("• Nothing has been sent yet.\n")
            if (failed.isNotEmpty()) {
                val reasons = failed.groupingBy { it.note.ifBlank { "Unknown reason" } }.eachCount()
                    .entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} (${it.value})" }
                append("• ${failed.size} message(s) did not go through: $reasons. Press Start again to retry only these; nobody who already received it is messaged twice.\n")
            }
            if (stale.isNotEmpty()) append("• ${stale.size} message(s) left the phone over 2 hours ago with no delivery report. The phone may be off or out of coverage, or their carrier doesn't send reports.\n")
            if (delivered.isNotEmpty()) append("• ${delivered.size} client(s) received the message but haven't replied. SMS can't show whether it was opened, so treat these as \"not yet seen\" and follow up by call or WhatsApp.\n")
            if (optedOut.isNotEmpty()) append("• ${optedOut.size} client(s) asked to stop. They are excluded from all future sends.\n")
            if (skipped.isNotEmpty()) append("• ${skipped.size} duplicate(s) blocked: they had already received this exact message.\n")
            if (waiting.isNotEmpty()) append("• ${waiting.size} client(s) still waiting to be sent.\n")
            if (held.isNotEmpty()) append("• ${held.size} client(s) held back: they already had 2 messages in 24 hours or one in the last 6 hours. Start again after the time shown and they'll be included.\n")
            if (emailed.isNotEmpty()) append("• ${emailed.size} email(s) were handed to your email app. Email can't report delivery or opening here; check your Sent folder and any bounce-backs.\n")
            if (messaged > 0 && failed.isEmpty() && delivered.isEmpty() && stale.isEmpty()) append("• Everyone messaged has replied or is pending a report. Nothing needs action.\n")

            fun section(title: String, list: List<Recipient>) {
                if (list.isEmpty()) return
                append("\n$title (${list.size})\n")
                list.forEach { append(line(it)).append('\n') }
            }
            section("DID NOT GO THROUGH", failed)
            section("SENT, NO DELIVERY REPORT YET", sentNoReport)
            section("EMAILS HANDED TO EMAIL APP", emailed)
            section("HELD BY GOOD-NEIGHBOUR LIMIT", held)
            section("DELIVERED, NO REPLY (NOT YET SEEN)", delivered)
            section("REPLIED", replied)
            section("OPTED OUT", optedOut)
            section("DUPLICATES BLOCKED", skipped)
            section("STILL TO SEND", waiting)
        }
    }
}
