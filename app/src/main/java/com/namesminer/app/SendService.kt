package com.namesminer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends the queued messages in the background, either in blocks (N messages, then a pause)
 * or as a one-time broadcast to everyone (short gap between each so the carrier doesn't throttle).
 */
class SendService : Service() {

    companion object {
        const val ACTION_START = "com.namesminer.app.START"
        const val ACTION_STOP = "com.namesminer.app.STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_BROADCAST = "broadcast"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_DELAY = "delay"

        private const val CHANNEL = "sending"
        private const val NOTIF_ID = 7
        private const val GAP_MS = 1500L
        private val seq = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var text = ""
    private var broadcast = false
    private var block = 5
    private var delayMs = 60_000L
    private var inBlock = 0
    private var blockNo = 0
    private var totalBlocks = 1
    private var sentCount = 0
    private var totalCount = 0
    private var countdownEnd = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.ensureLoaded(this)
        if (intent?.action == ACTION_STOP) {
            if (Store.running) finish("Stopped. Unsent contacts are back to Not sent.") else stopSelf()
            return START_NOT_STICKY
        }
        // Started with startForegroundService: must go foreground straight away.
        startInForeground("Preparing…")
        if (intent == null || intent.action != ACTION_START) { stopIfIdle(); return START_NOT_STICKY }
        if (Store.running) return START_NOT_STICKY

        text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        broadcast = intent.getBooleanExtra(EXTRA_BROADCAST, false)
        block = intent.getIntExtra(EXTRA_BLOCK, 5).coerceAtLeast(1)
        delayMs = intent.getIntExtra(EXTRA_DELAY, 60).coerceAtLeast(1) * 1000L

        queue.clear()
        for (r in Store.recipients) {
            if (r.selected && (r.status == Status.NEW || r.status == Status.FAILED)) {
                r.resetStatus()
                r.status = Status.QUEUED
                queue.addLast(r.id)
            }
        }
        if (queue.isEmpty() || text.isBlank()) { stopIfIdle(); return START_NOT_STICKY }

        totalCount = queue.size
        sentCount = 0
        inBlock = 0
        blockNo = 0
        totalBlocks = if (broadcast) 1 else (totalCount + block - 1) / block
        Store.running = true
        Store.save(this)

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NamesMiner:send").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        handler.post { step() }
        return START_NOT_STICKY
    }

    private fun step() {
        if (!Store.running) return
        val id = queue.removeFirstOrNull()
        if (id == null) {
            finish("Done: $sentCount sent. Delivery reports and replies will keep updating.")
            return
        }
        Store.find(id)?.let { if (it.status == Status.QUEUED) sendTo(it) }
        sentCount++

        if (queue.isEmpty()) {
            finish("Done: $sentCount sent. Delivery reports and replies will keep updating.")
            return
        }
        if (broadcast) {
            progress("Broadcast: $sentCount of $totalCount sent…")
            handler.postDelayed({ step() }, GAP_MS)
            return
        }
        inBlock++
        if (inBlock >= block) {
            inBlock = 0
            blockNo++
            countdownEnd = SystemClock.elapsedRealtime() + delayMs
            tick()
        } else {
            progress("Block ${blockNo + 1} of $totalBlocks: $sentCount of $totalCount sent…")
            handler.postDelayed({ step() }, GAP_MS)
        }
    }

    private fun tick() {
        if (!Store.running) return
        val left = countdownEnd - SystemClock.elapsedRealtime()
        if (left <= 0) {
            step()
            return
        }
        progress("Block $blockNo of $totalBlocks sent ($sentCount of $totalCount). Next block in ${(left + 999) / 1000}s")
        handler.postDelayed({ tick() }, 1000)
    }

    private fun sendTo(r: Recipient) {
        try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val body = text.replace("{name}", r.firstName.ifBlank { "there" })
            val parts = sms.divideMessage(body)
            r.partsTotal = parts.size
            r.partsSent = 0
            r.partsDelivered = 0
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent.add(statusIntent(SmsStatusReceiver.ACTION_SENT, r.id))
                delivered.add(statusIntent(SmsStatusReceiver.ACTION_DELIVERED, r.id))
            }
            if (parts.size == 1) {
                sms.sendTextMessage(r.number, null, parts[0], sent[0], delivered[0])
            } else {
                sms.sendMultipartTextMessage(r.number, null, parts, sent, delivered)
            }
            r.updatedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            r.status = Status.FAILED
            r.note = e.message ?: "Could not send"
        }
        Store.save(this)
        Store.changed()
    }

    private fun statusIntent(action: String, id: String): PendingIntent {
        val i = Intent(this, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra(SmsStatusReceiver.EXTRA_ID, id)
        // Mutable so the system can attach the delivery-report PDU; the intent is explicit, so this is safe.
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, seq.incrementAndGet(), i, flags)
    }

    private fun startInForeground(line: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sending messages", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(line)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(line: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SendService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Names Miner")
            .setContentText(line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
    }

    private fun progress(line: String) {
        Store.progressLine = line
        Store.changed()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(line))
    }

    private fun stopIfIdle() {
        if (!Store.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finish(line: String) {
        handler.removeCallbacksAndMessages(null)
        for (r in Store.recipients) if (r.status == Status.QUEUED && r.partsTotal == 0) r.status = Status.NEW
        queue.clear()
        Store.running = false
        Store.progressLine = line
        Store.save(this)
        Store.changed()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (Store.running) finish("Sending stopped.")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
