package com.namesminer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var idField: EditText
    private lateinit var mobileOnly: CheckBox
    private lateinit var summary: TextView
    private lateinit var rows: LinearLayout
    private lateinit var message: EditText
    private lateinit var askReply: CheckBox
    private lateinit var modeBlocks: RadioButton
    private lateinit var modeBroadcast: RadioButton
    private lateinit var blockSize: EditText
    private lateinit var delaySec: EditText
    private lateinit var quietOn: CheckBox
    private lateinit var quietStart: EditText
    private lateinit var quietEnd: EditText
    private lateinit var progress: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val rowViews = mutableListOf<RowViews>()
    private var pendingAction: (() -> Unit)? = null
    private val listener: () -> Unit = { refresh() }

    private class RowViews(
        val id: String,
        val cb: CheckBox,
        val title: TextView,
        val sub: TextView,
        val status: TextView
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.ensureLoaded(this)
        setContentView(buildUi())
        loadSettings()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Store.removeListener(listener)
        saveSettings()
        super.onStop()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        root.addView(heading("1 · Find clients in your contacts"))
        idField = EditText(this).apply {
            hint = "Tags, separated by commas"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(labeled("Client tags in the first name or surname (any case)", idField))
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Tags must stand as their own word: \"CL John\", \"Mary cl\", \"C.L. Brown\", \"Coopmed Ann\" match. " +
                "Names that merely contain the letters, like Clarke or Michael, are ignored."
        })
        mobileOnly = check("Mobile-labelled numbers only")
        root.addView(mobileOnly)
        root.addView(button("Scan contacts") { scan() })
        summary = TextView(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(summary)
        root.addView(
            hrow(
                button("Select all") { selectAll(true) },
                button("Select none") { selectAll(false) },
                button("Export list") { exportList() }
            )
        )
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)

        root.addView(heading("2 · Message"))
        message = EditText(this).apply {
            hint = "Type your message. {name} inserts the client's first name."
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(message)
        askReply = check("Add \"Please reply YES to confirm\" (replies show as Confirmed)")
        root.addView(askReply)

        root.addView(heading("3 · How to send"))
        modeBlocks = RadioButton(this).apply { text = "In blocks"; id = View.generateViewId() }
        modeBroadcast = RadioButton(this).apply {
            text = "One-time broadcast to everyone selected"
            id = View.generateViewId()
        }
        val group = RadioGroup(this).apply {
            addView(modeBlocks)
            addView(modeBroadcast)
        }
        root.addView(group)
        blockSize = numberField()
        delaySec = numberField()
        root.addView(hrow(labeled("Messages per block", blockSize), labeled("Minutes between blocks", delaySec)))
        quietOn = check("Good-neighbour hours: only send between these times")
        root.addView(quietOn)
        quietStart = numberField()
        quietEnd = numberField()
        root.addView(hrow(labeled("From (hour, 0–23)", quietStart), labeled("Until (hour, 0–23)", quietEnd)))
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Good-neighbour protocol: small blocks, long gaps, no sends at night, never the same message twice " +
                "to the same number, and anyone who replies STOP is never messaged again."
        })
        group.setOnCheckedChangeListener { _, _ -> updateModeFields() }

        startBtn = button("Start sending") { confirmAndSend() }
        stopBtn = button("Stop") {
            startService(Intent(this, SendService::class.java).setAction(SendService.ACTION_STOP))
        }
        root.addView(hrow(startBtn, stopBtn, button("Reset statuses") { confirmReset() }))
        root.addView(button("Report: who got it, who didn't") { showReport() })
        progress = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(progress)
        root.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
            text = "Sent = left your phone. Duplicates are blocked automatically.  Delivered = the network confirmed it reached the client's phone. " +
                "Confirmed = the client replied. Plain SMS cannot report when a message is opened, so a reply " +
                "is the confirmation of receipt. Messages go from your own SIM at your carrier's normal rates " +
                "and also appear in your Messages app."
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun labeled(label: String, view: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, dp(6), 0)
        addView(TextView(this@MainActivity).apply { text = label; textSize = 12f })
        addView(view)
    }

    private fun check(t: String) = CheckBox(this).apply { text = t }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun numberField() = EditText(this).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun hrow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun updateModeFields() {
        val blocks = modeBlocks.isChecked
        blockSize.isEnabled = blocks
        delaySec.isEnabled = blocks
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- List ----------

    private fun refresh() {
        val recs = Store.recipients
        if (rowViews.size != recs.size || rowViews.indices.any { rowViews[it].id != recs[it].id }) rebuildRows()
        recs.forEachIndexed { i, r ->
            val v = rowViews[i]
            v.cb.isChecked = r.selected
            v.title.text = r.name
            v.sub.text = "${r.number} · ${r.label}" + if (r.note.isNotBlank()) "\n${r.note}" else ""
            v.status.text = r.status.label
            v.status.setTextColor(colorFor(r.status))
        }
        refreshSummary()
    }

    private fun refreshSummary() {
        val recs = Store.recipients
        val sel = recs.filter { it.selected }
        val c = sel.groupingBy { it.status }.eachCount()
        summary.text = if (recs.isEmpty()) "" else "${recs.size} numbers found · ${sel.size} selected"
        progress.text = buildString {
            if (sel.isNotEmpty()) {
                append("Not sent ${(c[Status.NEW] ?: 0) + (c[Status.QUEUED] ?: 0)} · ")
                append("Sent ${c[Status.SENT] ?: 0} · ")
                append("Delivered ${c[Status.DELIVERED] ?: 0} · ")
                append("Confirmed ${c[Status.CONFIRMED] ?: 0} · ")
                append("Failed ${c[Status.FAILED] ?: 0}")
                val oo = recs.count { it.status == Status.OPTED_OUT }
                val sk = c[Status.SKIPPED] ?: 0
                if (oo > 0) append(" · Opted out $oo")
                if (sk > 0) append(" · Duplicates blocked $sk")
            }
            if (Store.progressLine.isNotBlank()) append("\n").append(Store.progressLine)
        }
        startBtn.isEnabled = !Store.running
        stopBtn.isEnabled = Store.running
    }

    private fun rebuildRows() {
        rows.removeAllViews()
        rowViews.clear()
        if (Store.recipients.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = "No matches yet. Tap Scan contacts."
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (r in Store.recipients) {
            val cb = CheckBox(this)
            val title = TextView(this).apply { setTypeface(typeface, Typeface.BOLD) }
            val sub = TextView(this).apply { textSize = 12f }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub)
            }
            val status = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.END
                setPadding(dp(8), 0, 0, 0)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(cb)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(status)
            val id = r.id
            cb.setOnClickListener {
                Store.find(id)?.let { it.selected = cb.isChecked }
                Store.save(this)
                refreshSummary()
            }
            rows.addView(row)
            rowViews.add(RowViews(id, cb, title, sub, status))
        }
    }

    private fun colorFor(s: Status): Int = when (s) {
        Status.NEW -> 0xFF757575.toInt()
        Status.QUEUED -> 0xFF1565C0.toInt()
        Status.SENT -> 0xFF6A1B9A.toInt()
        Status.DELIVERED -> 0xFF2E7D32.toInt()
        Status.CONFIRMED -> 0xFF00796B.toInt()
        Status.FAILED -> 0xFFC62828.toInt()
        Status.SKIPPED -> 0xFF8D6E00.toInt()
        Status.OPTED_OUT -> 0xFF424242.toInt()
    }

    private fun selectAll(value: Boolean) {
        Store.recipients.forEach { it.selected = value }
        Store.save(this)
        refresh()
    }

    // ---------- Actions ----------

    private fun scan() {
        if (Store.running) { toast("Wait for sending to finish, or tap Stop."); return }
        withPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) {
            val tags = ContactScanner.parseTags(idField.text.toString())
            if (tags.isEmpty()) {
                toast("Enter at least one tag, e.g. CL, Coopmed.")
                return@withPermissions
            }
            saveSettings()
            summary.text = "Scanning contacts…"
            val mo = mobileOnly.isChecked
            Thread {
                val found = try {
                    ContactScanner.scan(this, tags, mo)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (found == null) {
                        toast("Couldn't read contacts.")
                    } else {
                        Store.replaceWithScan(found)
                        Store.save(this)
                        refresh()
                        toast("Found ${found.size} numbers tagged ${tags.joinToString(" or ")}.")
                    }
                }
            }.start()
        }
    }

    private fun composed(text: String) =
        if (askReply.isChecked) "$text\n\nPlease reply YES to confirm you received this." else text

    private fun confirmAndSend() {
        if (Store.running) return
        val text = message.text.toString().trim()
        if (text.isEmpty()) { toast("Type a message first."); return }
        val targets = Store.recipients.filter {
            it.selected && (it.status == Status.NEW || it.status == Status.FAILED)
        }
        if (targets.isEmpty()) {
            toast("No selected contacts are waiting to be sent. Scan, select some, or Reset statuses.")
            return
        }
        val broadcast = modeBroadcast.isChecked
        val block = (blockSize.text.toString().toIntOrNull() ?: 5).coerceIn(1, 100)
        val delay = (delaySec.text.toString().toIntOrNull() ?: 20).coerceIn(1, 240)
        val qs = (quietStart.text.toString().toIntOrNull() ?: 8).coerceIn(0, 23)
        val qe = (quietEnd.text.toString().toIntOrNull() ?: 20).coerceIn(0, 23)
        quietStart.setText(qs.toString())
        quietEnd.setText(qe.toString())
        blockSize.setText(block.toString())
        delaySec.setText(delay.toString())

        val full = composed(text)
        val dupes = targets.count { Store.fingerprint(it.id, full) in Store.sentLog }
        val sample = full.replace("{name}", targets.first().firstName.ifBlank { "there" })
        val blocks = (targets.size + block - 1) / block
        val secs = if (broadcast) targets.size * 1.5 else (blocks - 1) * delay * 60.0 + targets.size * 1.5
        val how = (if (broadcast) "One-time broadcast to all ${targets.size}" else "$blocks blocks of up to $block, $delay min apart") +
            (if (quietOn.isChecked) ", only between %02d:00 and %02d:00".format(qs, qe) else "") +
            (if (dupes > 0) ". $dupes already had this exact message and will be skipped" else "")

        AlertDialog.Builder(this)
            .setTitle("Send to ${targets.size} clients?")
            .setMessage(
                "$how. About ${formatDuration(secs)}.\n\nPreview:\n$sample\n\n" +
                    "Your carrier's normal SMS rates apply. If Android warns that the app is sending " +
                    "a large number of messages, choose Always allow."
            )
            .setPositiveButton("Send") { _, _ ->
                withPermissions(sendPermissions()) {
                    ensureBackgroundAllowed {
                        launch(full, broadcast, block, delay, if (quietOn.isChecked) qs else -1, if (quietOn.isChecked) qe else -1)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDuration(secs: Double): String = when {
        secs < 90 -> "${secs.roundToInt()} seconds"
        secs < 5400 -> "${(secs / 60).roundToInt()} minutes"
        else -> "%.1f hours".format(secs / 3600)
    }

    /** Long runs (hours) need Samsung/Android to leave the app running in the background. */
    private fun ensureBackgroundAllowed(then: () -> Unit) {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { then(); return }
        AlertDialog.Builder(this)
            .setTitle("Keep sending in the background?")
            .setMessage("Sending in blocks can take hours. Allow Names Miner to run in the background so the phone doesn't pause it. " +
                "Then also check Settings › Apps › Names Miner › Battery is set to Unrestricted.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
                then()
            }
            .setNegativeButton("Not now") { _, _ -> then() }
            .show()
    }

    private fun showReport() {
        val reportText = Report.build(Store.recipients)
        val tv = TextView(this).apply {
            text = reportText
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Send report")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Names Miner report")
                    .putExtra(Intent.EXTRA_TEXT, reportText)
                startActivity(Intent.createChooser(send, "Share report"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun launch(text: String, broadcast: Boolean, block: Int, delay: Int, qStart: Int, qEnd: Int) {
        saveSettings()
        Store.progressLine = "Starting…"
        val i = Intent(this, SendService::class.java)
            .setAction(SendService.ACTION_START)
            .putExtra(SendService.EXTRA_TEXT, text)
            .putExtra(SendService.EXTRA_BROADCAST, broadcast)
            .putExtra(SendService.EXTRA_BLOCK, block)
            .putExtra(SendService.EXTRA_DELAY, delay)
            .putExtra(SendService.EXTRA_QUIET_START, qStart)
            .putExtra(SendService.EXTRA_QUIET_END, qEnd)
        startForegroundService(i)
        refresh()
    }

    private fun confirmReset() {
        if (Store.running) { toast("Stop sending first."); return }
        AlertDialog.Builder(this)
            .setTitle("Reset statuses?")
            .setMessage("Marks every client as Not sent so you can send a new message. Delivery and reply info is cleared.")
            .setPositiveButton("Reset") { _, _ ->
                Store.recipients.forEach { it.resetStatus() }
                Store.progressLine = ""
                Store.save(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportList() {
        if (Store.recipients.isEmpty()) { toast("Nothing to export yet."); return }
        val csv = buildString {
            append("Name,Number,Type,Selected,Status,Note\n")
            for (r in Store.recipients) {
                append(
                    listOf(r.name, r.number, r.label, if (r.selected) "Yes" else "No", r.status.label, r.note)
                        .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
                ).append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Names Miner client list")
            .putExtra(Intent.EXTRA_TEXT, csv)
        startActivity(Intent.createChooser(send, "Export client list"))
    }

    // ---------- Permissions & settings ----------

    private fun withPermissions(perms: Array<String>, action: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        val optional = setOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
        val deniedEssential = permissions.indices.any {
            grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED && permissions[it] !in optional
        }
        if (permissions.isNotEmpty() && !deniedEssential) {
            action?.invoke()
        } else {
            toast("Names Miner needs that permission. You can allow it in Settings › Apps › Names Miner › Permissions.")
        }
    }

    private fun loadSettings() {
        val p = Store.prefs(this)
        idField.setText(p.getString("tags", "CL, Coopmed"))
        mobileOnly.isChecked = p.getBoolean("mobileOnly", false)
        message.setText(p.getString("message", ""))
        askReply.isChecked = p.getBoolean("askReply", true)
        if (p.getBoolean("broadcast", false)) modeBroadcast.isChecked = true else modeBlocks.isChecked = true
        blockSize.setText(p.getInt("block", 5).toString())
        delaySec.setText(p.getInt("delayMin", 20).toString())
        quietOn.isChecked = p.getBoolean("quietOn", true)
        quietStart.setText(p.getInt("quietStart", 8).toString())
        quietEnd.setText(p.getInt("quietEnd", 20).toString())
        updateModeFields()
    }

    private fun saveSettings() {
        Store.prefs(this).edit()
            .putString("tags", idField.text.toString().trim())
            .putBoolean("mobileOnly", mobileOnly.isChecked)
            .putString("message", message.text.toString())
            .putBoolean("askReply", askReply.isChecked)
            .putBoolean("broadcast", modeBroadcast.isChecked)
            .putInt("block", blockSize.text.toString().toIntOrNull() ?: 5)
            .putInt("delayMin", delaySec.text.toString().toIntOrNull() ?: 20)
            .putBoolean("quietOn", quietOn.isChecked)
            .putInt("quietStart", quietStart.text.toString().toIntOrNull() ?: 8)
            .putInt("quietEnd", quietEnd.text.toString().toIntOrNull() ?: 20)
            .apply()
    }
}
