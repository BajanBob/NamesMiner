package com.namesminer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.PhoneNumberUtils

/**
 * Marks a client as Confirmed when they text back after being messaged.
 * Your normal Messages app still receives the reply as usual.
 */
class SmsReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.ensureLoaded(context)
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val waiting = setOf(Status.QUEUED, Status.SENT, Status.DELIVERED, Status.FAILED)
        var changed = false

        messages.groupBy { it.originatingAddress ?: "" }.forEach { (from, parts) ->
            if (from.isBlank()) return@forEach
            val body = parts.joinToString("") { it.messageBody ?: "" }.trim()
            val r = Store.recipients.firstOrNull {
                it.status in waiting && PhoneNumberUtils.compare(it.number, from)
            } ?: return@forEach
            r.status = Status.CONFIRMED
            r.note = "Replied: " + body.take(80)
            r.updatedAt = System.currentTimeMillis()
            changed = true
        }
        if (changed) {
            Store.save(context)
            Store.changed()
        }
    }
}
