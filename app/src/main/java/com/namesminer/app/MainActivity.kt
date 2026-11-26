package com.namesminer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 42
        private const val REQ_HARVEST = 101
        private const val REQ_ATTACH = 102
        private const val DEFAULT_SUBJECT = "An update for you"
        private const val DEFAULT_EMAIL = "Dear {name},\n\n[Write your message here.]\n\n" +
            "If you have any questions, simply reply to this email.\n\nKind regards,\n[Your name]\n[Company] · [Phone]"
    }

    // ----- Find tab -----
    private lateinit var tabFind: Button
    private lateinit var tabSend: Button
    private lateinit var findView: LinearLayout
    private lateinit var sendView: ScrollView
    private lateinit var filters: LinearLayout
    private lateinit var filtersToggle: Button
    private lateinit var whoTagged: RadioButton
    private lateinit var whoAll: RadioButton
    private lateinit var idField: EditText
    private lateinit var prefix1: EditText
    private lateinit var prefix2: EditText
    private lateinit var prefix3: EditText
    private lateinit var chSms: RadioButton
    private lateinit var chEmail: RadioButton
    private lateinit var mobileOnly: CheckBox
    private lateinit var harvestBtn: Button
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var listView: ListView
    private val shown = mutableListOf<Recipient>()
    private lateinit var adapter: RowAdapter

    // ----- Send tab -----
    private lateinit var smsBox: LinearLayout
    private lateinit var emailBox: LinearLayout
    private lateinit var message: EditText
    private lateinit var askReply: CheckBox
    private lateinit var modeBlocks: RadioButton
    private lateinit var modeBroadcast: RadioButton
    private lateinit var subject: EditText
    private lateinit var emailBody: EditText
    private lateinit var attachList: TextView
    private lateinit var blockSize: EditText
    private lateinit var delayMin: EditText
    private lateinit var quietOn: CheckBox
    private lateinit var quietStart: EditText
    private lateinit var quietEnd: EditText
    private lateinit var progress: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val attachments = mutableListOf<Uri>()
    private var pendingAction: (() -> Unit)? = null
    private val listener: () -> Unit = { refresh() }
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshSummary()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun emailMode() = Store.prefs(this).getString("listChannel", "sms") == "email"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.ensureLoaded(this)
        setContentView(buildUi())
        loadSettings()
        showTab(true)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(listener)
        ui.post(ticker)
        refresh()
    }

    override fun onStop() {
        Store.removeListener(listener)
        ui.removeCallbacks(ticker)
        saveSettings()
        super.onStop()
    }

    // ================= UI =================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabFind = button("1 · Find") { showTab(true) }
        tabSend = button("2 · Message & send") { showTab(false) }
        root.addView(hrow(tabFind, tabSend).apply { setPadding(dp(8), dp(4), dp(8), 0) })

        val frame = FrameLayout(this)
        findView = buildFind()
        sendView = buildSend()
        frame.addView(findView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(sendView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildFind(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        filtersToggle = button("Filters ▴") { setFiltersOpen(filters.visibility != View.VISIBLE) }
        v.addView(filtersToggle)

        filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whoTagged = RadioButton(this).apply { text = "Clients with tags"; id = View.generateViewId() }
        whoAll = RadioButton(this).apply { text = "All contacts"; id = View.generateViewId() }
        val who = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(whoTagged); addView(whoAll) }
        who.setOnCheckedChangeListener { _, _ -> idField.isEnabled = whoTagged.isChecked }
        filters.addView(who)
        idField = EditText(this).apply {
            hint = "Tags, separated by commas"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        filters.addView(labeled("Tags in first name or surname (any case, whole words)", idField))
        prefix1 = prefixField(); prefix2 = prefixField(); prefix3 = prefixField()
        filters.addView(small("Only numbers whose local number starts with (up to 3 codes; blank = all). For +1 246 823 4567 the code is 823."))
        filters.addView(hrow(prefix1, prefix2, prefix3))
        chSms = RadioButton(this).apply { text = "Cell numbers (SMS)"; id = View.generateViewId() }
        chEmail = RadioButton(this).apply { text = "Emails"; id = View.generateViewId() }
        val ch = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(chSms); addView(chEmail) }
        ch.setOnCheckedChangeListener { _, _ -> harvestBtn.visibility = if (chEmail.isChecked) View.VISIBLE else View.GONE }
        filters.addView(ch)
        mobileOnly = check("Mobile-labelled numbers only")
        filters.addView(mobileOnly)
        filters.addView(button("Scan contacts") { scan() })
        harvestBtn = button("Load harvested email list (.csv / .txt)") { pickHarvested() }
        filters.addView(harvestBtn)
        v.addView(filters)

        search = EditText(this).apply {
            hint = "Search names or numbers…"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        v.addView(search)
        v.addView(hrow(
            button("Select shown") { selectShown(true) },
            button("Clear shown") { selectShown(false) },
            button("Export") { exportList() }
        ))
        summary = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)); textSize = 13f }
        v.addView(summary)

        listView = ListView(this)
        adapter = RowAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val r = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (r.status == Status.OPTED_OUT) { toast("${r.name} opted out and can't be selected."); return@setOnItemClickListener }
            r.selected = !r.selected
            Store.save(this)
            adapter.notifyDataSetChanged()
            refreshSummary()
        }
        v.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return v
    }

    private fun buildSend(): ScrollView {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        // SMS message
        smsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        smsBox.addView(heading("Text message"))
        message = multiLine("Type your message. {name} inserts the client's first name.")
        smsBox.addView(message)
        askReply = check("Add \"Please reply YES to confirm\" (replies show as Confirmed)")
        smsBox.addView(askReply)
        modeBlocks = RadioButton(this).apply { text = "In blocks"; id = View.generateViewId() }
        modeBroadcast = RadioButton(this).apply { text = "One-time broadcast to everyone selected"; id = View.generateViewId() }
        smsBox.addView(RadioGroup(this).apply { addView(modeBlocks); addView(modeBroadcast) })
        v.addView(smsBox)

        // Email template
        emailBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emailBox.addView(heading("Email template"))
        subject = EditText(this).apply { isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        emailBox.addView(labeled("Subject", subject))
        emailBody = multiLine("Email message")
        emailBox.addView(labeled("Message ({name} becomes \"Valued Client\" in group emails)", emailBody))
        emailBox.addView(hrow(
            button("Add attachments") { pickAttachments() },
            button("Clear attachments") { attachments.clear(); saveAttachments(); showAttachments() },
            button("Reset template") { subject.setText(DEFAULT_SUBJECT); emailBody.setText(DEFAULT_EMAIL) }
        ))
        attachList = small("")
        emailBox.addView(attachList)
        emailBox.addView(small("Each block opens your email app with the clients in BCC, so nobody sees anyone else's address. Press Send there."))
        v.addView(emailBox)

        // Good-neighbour settings
        v.addView(heading("Good-neighbour protocol"))
        blockSize = numberField()
        delayMin = numberField()
        v.addView(hrow(labeled("Messages per block", blockSize), labeled("Minutes between blocks", delayMin)))
        quietOn = check("Only send between these hours")
        v.addView(quietOn)
        quietStart = numberField()
        quietEnd = numberField()
        v.addView(hrow(labeled("From (0–23)", quietStart), labeled("Until (0–23)", quietEnd)))
        v.addView(small(
            "Always on: each person gets at most ${GoodNeighbour.MAX_PER_DAY} messages in any 24 hours, at least 6 hours apart; " +
                "never the same message twice; anyone who replies STOP is never messaged again. People held back by these " +
                "rules are marked Held and included next time."
        ))

        startBtn = button("Start sending") { if (emailMode()) sendEmailBlock() else confirmAndSend() }
        stopBtn = button("Stop") {
            startService(Intent(this, SendService::class.java).setAction(SendService.ACTION_STOP))
        }
        v.addView(hrow(startBtn, stopBtn))
        v.addView(hrow(
            button("Report") { showReport() },
            button("Reset statuses") { confirmReset() }
        ))
        progress = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        v.addView(progress)
        return ScrollView(this).apply { addView(v) }
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun small(t: String) = TextView(this).apply { text = t; textSize = 12f; setPadding(0, dp(4), 0, dp(4)) }

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

    private fun numberField() = EditText(this).apply { isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER }

    private fun prefixField() = EditText(this).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "e.g. 823"
        this.filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(3))
    }

    private fun multiLine(h: String) = EditText(this).apply {
        hint = h
        minLines = 4
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun hrow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun showTab(find: Boolean) {
        findView.visibility = if (find) View.VISIBLE else View.GONE
        sendView.visibility = if (find) View.GONE else View.VISIBLE
        tabFind.setTypeface(null, if (find) Typeface.BOLD else Typeface.NORMAL)
        tabSend.setTypeface(null, if (find) Typeface.NORMAL else Typeface.BOLD)
        updateChannelUi()
    }

    private fun setFiltersOpen(open: Boolean) {
        filters.visibility = if (open) View.VISIBLE else View.GONE
        filtersToggle.text = if (open) "Filters ▴" else "Filters ▾ (who, tags, codes, texts or emails)"
    }

    private fun updateChannelUi() {
        val email = emailMode()
        smsBox.visibility = if (email) View.GONE else View.VISIBLE
        emailBox.visibility = if (email) View.VISIBLE else View.GONE
        startBtn.text = if (email) "Open next email block" else "Start sending"
        showAttachments()
    }

    // ================= List =================

    private class RowHolder(val cb: CheckBox, val title: TextView, val sub: TextView, val status: TextView)

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: View
            val h: RowHolder
            if (convertView == null) {
                val cb = CheckBox(this@MainActivity).apply { isFocusable = false; isClickable = false }
                val title = TextView(this@MainActivity).apply { setTypeface(typeface, Typeface.BOLD) }
                val sub = TextView(this@MainActivity).apply { textSize = 12f }
                val status = TextView(this@MainActivity).apply { textSize = 12f; gravity = Gravity.END; setPadding(dp(8), 0, 0, 0) }
                val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
                val ll = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(cb)
                    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(status)
                }
                h = RowHolder(cb, title, sub, status)
                ll.tag = h
                row = ll
            } else {
                row = convertView
                h = convertView.tag as RowHolder
            }
            val r = shown[position]
            h.cb.isChecked = r.selected
            h.title.text = r.name
            h.sub.text = buildString {
                append(r.number).append(" · ").append(r.label)
                if (r.prefix.isNotBlank()) append(" · code ").append(r.prefix)
                if (r.note.isNotBlank()) append('\n').append(r.note)
            }
            h.status.text = r.status.label
            h.status.setTextColor(colorFor(r.status))
            return row
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        if (q.isEmpty()) shown.addAll(Store.recipients)
        else shown.addAll(Store.recipients.filter { it.name.lowercase().contains(q) || it.number.lowercase().contains(q) })
        adapter.notifyDataSetChanged()
        refreshSummary()
    }

    private fun refresh() = applyFilter()

    private fun refreshSummary() {
        val recs = Store.recipients
        val sel = recs.filter { it.selected }
        val c = sel.groupingBy { it.status }.eachCount()
        val kind = if (emailMode()) "email addresses" else "numbers"
        summary.text = if (recs.isEmpty()) "Nothing loaded yet. Open Filters and tap Scan contacts."
        else "${recs.size} $kind · ${shown.size} shown · ${sel.size} selected"
        progress.text = buildString {
            if (sel.isNotEmpty()) {
                append("Not sent ${(c[Status.NEW] ?: 0) + (c[Status.QUEUED] ?: 0)} · Sent ${c[Status.SENT] ?: 0} · ")
                if (!emailMode()) append("Delivered ${c[Status.DELIVERED] ?: 0} · Confirmed ${c[Status.CONFIRMED] ?: 0} · ")
                append("Failed ${c[Status.FAILED] ?: 0}")
                val held = c[Status.HELD] ?: 0
                val oo = recs.count { it.status == Status.OPTED_OUT }
                val sk = c[Status.SKIPPED] ?: 0
                if (held > 0) append(" · Held $held")
                if (oo > 0) append(" · Opted out $oo")
                if (sk > 0) append(" · Duplicates blocked $sk")
            }
            if (emailMode()) {
                val next = Store.prefs(this@MainActivity).getLong("emailNextAt", 0L)
                val left = next - System.currentTimeMillis()
                if (left > 0) append("\nNext email block allowed in ${(left + 59_999) / 60_000} min (at ${GoodNeighbour.when_(next)}).")
            }
            if (Store.progressLine.isNotBlank()) append("\n").append(Store.progressLine)
        }
        startBtn.isEnabled = !Store.running
        stopBtn.isEnabled = Store.running
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
        Status.HELD -> 0xFFE65100.toInt()
    }

    private fun selectShown(value: Boolean) {
        shown.forEach { if (it.status != Status.OPTED_OUT) it.selected = value }
        Store.save(this)
        adapter.notifyDataSetChanged()
        refreshSummary()
    }

    // ================= Find actions =================

    private fun prefixes(): List<String> =
        listOf(prefix1, prefix2, prefix3).map { f -> f.text.toString().filter { it.isDigit() } }.filter { it.isNotEmpty() }

    private fun scan() {
        if (Store.running) { toast("Wait for sending to finish, or tap Stop."); return }
        withPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) {
            val all = whoAll.isChecked
            val tags = if (all) emptyList() else ContactScanner.parseTags(idField.text.toString())
            if (!all && tags.isEmpty()) {
                toast("Enter at least one tag (e.g. CL), or choose All contacts.")
                return@withPermissions
            }
            val pre = prefixes()
            val email = chEmail.isChecked
            val mo = mobileOnly.isChecked
            saveSettings()
            summary.text = "Scanning contacts…"
            Thread {
                val found = try {
                    ContactScanner.scan(this, tags, all, pre, email, mo)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (found == null) {
                        toast("Couldn't read contacts.")
                    } else {
                        Store.replaceWithScan(found)
                        Store.save(this)
                        Store.prefs(this).edit().putString("listChannel", if (email) "email" else "sms").apply()
                        setFiltersOpen(false)
                        updateChannelUi()
                        refresh()
                        val what = if (email) "email addresses" else "numbers"
                        toast(if (all) "Loaded ${found.size} $what. Search by name and tick who to message."
                        else "Found ${found.size} $what for ${tags.joinToString(" or ")}.")
                    }
                }
            }.start()
        }
    }

    private fun pickHarvested() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
        try { startActivityForResult(i, REQ_HARVEST) } catch (e: Exception) { toast("No file picker available.") }
    }

    private fun loadHarvested(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { "" }
        val found = ContactScanner.emailsIn(text)
        if (found.isEmpty()) { toast("No email addresses found in that file."); return }
        if (!emailMode()) {
            Store.replaceWithScan(emptyList())
            Store.prefs(this).edit().putString("listChannel", "email").apply()
        }
        val wanted = found.map { "mailto:" + it.first }.toSet()
        var added = 0
        Store.recipients.forEach { if (it.status != Status.OPTED_OUT) it.selected = it.id in wanted }
        for ((addr, name) in found) {
            val id = "mailto:$addr"
            if (Store.find(id) != null) continue
            val first = name.split(' ').firstOrNull { it.any { ch -> ch.isLetter() } } ?: ""
            Store.recipients.add(Recipient(id = id, name = name.ifBlank { addr }, firstName = first, number = addr,
                label = "Harvested", selected = id !in Store.optOuts,
                status = if (id in Store.optOuts) Status.OPTED_OUT else Status.NEW))
            added++
        }
        Store.recipients.sortBy { it.name.lowercase() }
        Store.save(this)
        chEmail.isChecked = true
        updateChannelUi()
        refresh()
        toast("${found.size} harvested addresses selected ($added new). Everyone else was unticked.")
    }

    private fun exportList() {
        if (Store.recipients.isEmpty()) { toast("Nothing to export yet."); return }
        val csv = buildString {
            append("Name,Number or email,Type,Code,Selected,Status,Note\n")
            for (r in Store.recipients) {
                append(listOf(r.name, r.number, r.label, r.prefix, if (r.selected) "Yes" else "No", r.status.label, r.note)
                    .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append('\n')
            }
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Names Miner list")
                .putExtra(Intent.EXTRA_TEXT, csv),
            "Export list"
        ))
    }

    // ================= SMS sending =================

    private fun composed(text: String) =
        if (askReply.isChecked) "$text\n\nPlease reply YES to confirm you received this." else text

    private fun quietHours(): Pair<Int, Int> {
        val qs = (quietStart.text.toString().toIntOrNull() ?: 8).coerceIn(0, 23)
        val qe = (quietEnd.text.toString().toIntOrNull() ?: 20).coerceIn(0, 23)
        quietStart.setText(qs.toString())
        quietEnd.setText(qe.toString())
        return if (quietOn.isChecked) qs to qe else -1 to -1
    }

    private fun confirmAndSend() {
        if (Store.running) return
        val text = message.text.toString().trim()
        if (text.isEmpty()) { toast("Type a message first."); return }
        val now = System.currentTimeMillis()
        val targets = Store.recipients.filter {
            it.selected && !it.isEmail && (it.status == Status.NEW || it.status == Status.FAILED || it.status == Status.HELD)
        }
        if (targets.isEmpty()) {
            toast("No selected contacts are waiting to be sent. Tick some on the Find tab, or Reset statuses.")
            return
        }
        val broadcast = modeBroadcast.isChecked
        val block = (blockSize.text.toString().toIntOrNull() ?: 5).coerceIn(1, 100)
        val delay = (delayMin.text.toString().toIntOrNull() ?: 20).coerceIn(1, 240)
        val (qs, qe) = quietHours()
        val full = composed(text)
        val dupes = targets.count { Store.fingerprint(it.id, full) in Store.sentLog }
        val held = targets.count { Store.nextAllowedAt(it.id, now) > now }
        val ready = (targets.size - dupes - held).coerceAtLeast(0)
        val sample = full.replace("{name}", targets.first().firstName.ifBlank { "there" })
        val blocks = (ready + block - 1) / block
        val secs = if (broadcast) ready * 1.5 else (blocks - 1).coerceAtLeast(0) * delay * 60.0 + ready * 1.5
        val how = buildString {
            append(if (broadcast) "One-time broadcast to $ready" else "$ready in $blocks blocks of up to $block, $delay min apart")
            if (qs >= 0) append(", only between %02d:00 and %02d:00".format(qs, qe))
            if (held > 0) append(". $held held back by the 2-in-24-hours / 6-hours-apart rule")
            if (dupes > 0) append(". $dupes already had this exact message and will be skipped")
        }
        AlertDialog.Builder(this)
            .setTitle("Send to $ready clients?")
            .setMessage("$how. About ${formatDuration(secs)}.\n\nPreview:\n$sample\n\n" +
                "Your carrier's normal SMS rates apply. If Android warns about sending many messages, choose Always allow.")
            .setPositiveButton("Send") { _, _ ->
                withPermissions(sendPermissions()) {
                    ensureBackgroundAllowed { launch(full, broadcast, block, delay, qs, qe) }
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

    private fun ensureBackgroundAllowed(then: () -> Unit) {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { then(); return }
        AlertDialog.Builder(this)
            .setTitle("Keep sending in the background?")
            .setMessage("Sending in blocks can take hours. Allow Names Miner to run in the background so the phone doesn't pause it. " +
                "Also set Settings › Apps › Names Miner › Battery to Unrestricted.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
                then()
            }
            .setNegativeButton("Not now") { _, _ -> then() }
            .show()
    }

    // ================= Email sending =================

    private fun pickAttachments() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_ATTACH) } catch (e: Exception) { toast("No file picker available.") }
    }

    private fun fileName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
    } catch (e: Exception) { uri.lastPathSegment ?: "file" }

    private fun showAttachments() {
        if (!::attachList.isInitialized) return
        attachList.text = if (attachments.isEmpty()) "No attachments."
        else "Attachments: " + attachments.joinToString(", ") { fileName(it) }
    }

    private fun saveAttachments() {
        Store.prefs(this).edit().putString("attachments", attachments.joinToString("\n") { it.toString() }).apply()
    }

    private fun sendEmailBlock() {
        val subj = subject.text.toString().trim()
        val body = emailBody.text.toString().trim()
        if (subj.isEmpty() || body.isEmpty()) { toast("Fill in the subject and message first."); return }
        if (body.contains("[Write your message here.]")) { toast("Replace the [Write your message here.] line in the template first."); return }
        val now = System.currentTimeMillis()
        val lockUntil = Store.prefs(this).getLong("emailNextAt", 0L)
        if (now < lockUntil) { toast("Good-neighbour pause: next email block at ${GoodNeighbour.when_(lockUntil)}."); return }
        val (qs, qe) = quietHours()
        if (!GoodNeighbour.inWindow(qs, qe, now)) { toast("Outside sending hours (%02d:00–%02d:00).".format(qs, qe)); return }
        val block = (blockSize.text.toString().toIntOrNull() ?: 5).coerceIn(1, 100)
        val delay = (delayMin.text.toString().toIntOrNull() ?: 20).coerceIn(1, 240)
        val fpText = "$subj\n$body"
        val picked = mutableListOf<Recipient>()
        for (r in Store.recipients) {
            if (picked.size >= block) break
            if (!r.selected || !r.isEmail || r.id in Store.optOuts) continue
            if (r.status != Status.NEW && r.status != Status.FAILED && r.status != Status.HELD) continue
            if (Store.fingerprint(r.id, fpText) in Store.sentLog) {
                r.status = Status.SKIPPED
                r.note = "Already received this exact email"
                continue
            }
            val next = Store.nextAllowedAt(r.id, now)
            if (next > now) {
                r.status = Status.HELD
                r.note = "Good-neighbour limit: next allowed " + GoodNeighbour.when_(next)
                continue
            }
            picked.add(r)
        }
        if (picked.isEmpty()) {
            Store.save(this)
            refresh()
            toast("Nobody selected is ready to email right now. Check Held times in the list.")
            return
        }
        val greeting = if (picked.size == 1) picked[0].firstName.ifBlank { "Valued Client" } else "Valued Client"
        val text = body.replace("{name}", greeting)
        val bcc = picked.map { it.number }.toTypedArray()
        val intent = if (attachments.isEmpty()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_BCC, bcc)
                .putExtra(Intent.EXTRA_SUBJECT, subj)
                .putExtra(Intent.EXTRA_TEXT, text)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_BCC, bcc)
                putExtra(Intent.EXTRA_SUBJECT, subj)
                putExtra(Intent.EXTRA_TEXT, text)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
                val clip = ClipData.newRawUri("attachment", attachments[0])
                attachments.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No email app found. Install Gmail or Outlook and sign in.")
            return
        }
        for (r in picked) {
            r.status = Status.SENT
            r.note = "Handed to email app (BCC) " + GoodNeighbour.when_(now)
            r.sentAt = now
            Store.recordSend(r.id, now)
            Store.sentLog.add(Store.fingerprint(r.id, fpText))
        }
        val nextAt = now + delay * 60_000L
        Store.prefs(this).edit().putLong("emailNextAt", nextAt).apply()
        Store.progressLine = "Email block of ${picked.size} handed to your email app: press Send there. Next block at ${GoodNeighbour.when_(nextAt)}."
        Store.save(this)
        refresh()
    }

    // ================= Report / reset =================

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
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Names Miner report")
                        .putExtra(Intent.EXTRA_TEXT, reportText),
                    "Share report"
                ))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmReset() {
        if (Store.running) { toast("Stop sending first."); return }
        AlertDialog.Builder(this)
            .setTitle("Reset statuses?")
            .setMessage("Marks everyone as Not sent so you can send a new message. Opt-outs, the no-duplicates guard and the " +
                "2-in-24-hours limit all stay in force.")
            .setPositiveButton("Reset") { _, _ ->
                Store.recipients.forEach { it.resetStatus() }
                Store.progressLine = ""
                Store.save(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= Results, permissions, settings =================

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_HARVEST -> data.data?.let { loadHarvested(it) }
            REQ_ATTACH -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                data.data?.let { if (uris.isEmpty()) uris.add(it) }
                for (u in uris) {
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                    if (u !in attachments) attachments.add(u)
                }
                saveAttachments()
                showAttachments()
            }
        }
    }

    private fun withPermissions(perms: Array<String>, action: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
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
            toast("Names Miner needs that permission. If it's greyed out: Settings › Apps › Names Miner › ⋮ › Allow restricted settings.")
        }
    }

    private fun loadSettings() {
        val p = Store.prefs(this)
        if (p.getBoolean("allContacts", false)) whoAll.isChecked = true else whoTagged.isChecked = true
        idField.isEnabled = whoTagged.isChecked
        idField.setText(p.getString("tags", "CL, Coopmed"))
        val pre = (p.getString("prefixes", "") ?: "").split(',')
        prefix1.setText(pre.getOrNull(0) ?: "")
        prefix2.setText(pre.getOrNull(1) ?: "")
        prefix3.setText(pre.getOrNull(2) ?: "")
        if (emailMode()) chEmail.isChecked = true else chSms.isChecked = true
        harvestBtn.visibility = if (chEmail.isChecked) View.VISIBLE else View.GONE
        mobileOnly.isChecked = p.getBoolean("mobileOnly", false)
        message.setText(p.getString("message", ""))
        askReply.isChecked = p.getBoolean("askReply", true)
        if (p.getBoolean("broadcast", false)) modeBroadcast.isChecked = true else modeBlocks.isChecked = true
        subject.setText(p.getString("emailSubject", DEFAULT_SUBJECT))
        emailBody.setText(p.getString("emailBody", DEFAULT_EMAIL))
        blockSize.setText(p.getInt("block", 5).toString())
        delayMin.setText(p.getInt("delayMin", 20).toString())
        quietOn.isChecked = p.getBoolean("quietOn", true)
        quietStart.setText(p.getInt("quietStart", 8).toString())
        quietEnd.setText(p.getInt("quietEnd", 20).toString())
        attachments.clear()
        (p.getString("attachments", "") ?: "").split('\n').filter { it.isNotBlank() }.forEach { attachments.add(Uri.parse(it)) }
        setFiltersOpen(Store.recipients.isEmpty())
    }

    private fun saveSettings() {
        Store.prefs(this).edit()
            .putBoolean("allContacts", whoAll.isChecked)
            .putString("tags", idField.text.toString().trim())
            .putString("prefixes", listOf(prefix1, prefix2, prefix3).joinToString(",") { it.text.toString().trim() })
            .putBoolean("mobileOnly", mobileOnly.isChecked)
            .putString("message", message.text.toString())
            .putBoolean("askReply", askReply.isChecked)
            .putBoolean("broadcast", modeBroadcast.isChecked)
            .putString("emailSubject", subject.text.toString())
            .putString("emailBody", emailBody.text.toString())
            .putInt("block", blockSize.text.toString().toIntOrNull() ?: 5)
            .putInt("delayMin", delayMin.text.toString().toIntOrNull() ?: 20)
            .putBoolean("quietOn", quietOn.isChecked)
            .putInt("quietStart", quietStart.text.toString().toIntOrNull() ?: 8)
            .putInt("quietEnd", quietEnd.text.toString().toIntOrNull() ?: 20)
            .apply()
    }
}
