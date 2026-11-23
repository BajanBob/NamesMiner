package com.namesminer.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage

/** Receives "sent" and "delivered" reports for each message from the phone's radio. */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SENT = "com.namesminer.app.SMS_SENT"
        const val ACTION_DELIVERED = "com.namesminer.app.SMS_DELIVERED"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Store.ensureLoaded(context)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val r = Store.find(id) ?: return

        when (intent.action) {
            ACTION_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    r.partsSent++
                    if (r.partsSent >= r.partsTotal && r.status == Status.QUEUED) r.status = Status.SENT
                } else if (r.status != Status.CONFIRMED && r.status != Status.DELIVERED) {
                    r.status = Status.FAILED
                    r.note = sendError(resultCode)
                }
            }
            ACTION_DELIVERED -> {
                when (deliveryState(intent)) {
                    1 -> {
                        r.partsDelivered++
                        if (r.partsDelivered >= r.partsTotal &&
                            (r.status == Status.QUEUED || r.status == Status.SENT)
                        ) {
                            r.status = Status.DELIVERED
                            r.note = ""
                        }
                    }
                    -1 -> if (r.status != Status.CONFIRMED) {
                        r.status = Status.FAILED
                        r.note = "Network reported it could not be delivered"
                    }
                    else -> Unit // still pending at the network; a later report may follow
                }
            }
            else -> return
        }
        r.updatedAt = System.currentTimeMillis()
        Store.save(context)
        Store.changed()
    }

    /** 1 = delivered, 0 = pending, -1 = failed. */
    private fun deliveryState(intent: Intent): Int {
        val pdu = intent.getByteArrayExtra("pdu") ?: return 1
        val format = intent.getStringExtra("format") ?: "3gpp"
        val msg = try { SmsMessage.createFromPdu(pdu, format) } catch (e: Exception) { null } ?: return 1
        val st = msg.status
        return if (format == "3gpp2") {
            when ((st shr 24) and 0x03) { 0 -> 1; 2 -> 0; else -> -1 }
        } else {
            when {
                st < 0x20 -> 1
                st < 0x40 -> 0
                else -> -1
            }
        }
    }

    private fun sendError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Failed (network or SIM error)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "Failed: no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Failed: mobile radio off / airplane mode"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Failed: message could not be encoded"
        else -> "Failed (error $code)"
    }
}
