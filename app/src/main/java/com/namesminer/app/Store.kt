package com.namesminer.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

enum class Status(val label: String) {
    NEW("Not sent"),
    QUEUED("Queued"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    CONFIRMED("Confirmed"),
    FAILED("Failed"),
    SKIPPED("Skipped: already had this message"),
    OPTED_OUT("Opted out"),
    HELD("Held: good-neighbour limit")
}

class Recipient(
    val id: String,
    var name: String,
    var firstName: String,
    var number: String,
    var label: String,
    var selected: Boolean = true,
    var status: Status = Status.NEW,
    var partsTotal: Int = 0,
    var partsSent: Int = 0,
    var partsDelivered: Int = 0,
    var note: String = "",
    var updatedAt: Long = 0L,
    var sentAt: Long = 0L,
    var prefix: String = ""
) {
    val isEmail: Boolean get() = id.startsWith("mailto:")

    fun resetStatus() {
        if (status == Status.OPTED_OUT) return
        status = Status.NEW
        sentAt = 0L
        partsTotal = 0
        partsSent = 0
        partsDelivered = 0
        note = ""
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("firstName", firstName)
        .put("number", number).put("label", label).put("selected", selected)
        .put("status", status.name).put("partsTotal", partsTotal)
        .put("partsSent", partsSent).put("partsDelivered", partsDelivered)
        .put("note", note).put("updatedAt", updatedAt).put("sentAt", sentAt).put("prefix", prefix)

    companion object {
        fun fromJson(o: JSONObject) = Recipient(
            id = o.getString("id"),
            name = o.optString("name"),
            firstName = o.optString("firstName"),
            number = o.optString("number"),
            label = o.optString("label"),
            selected = o.optBoolean("selected", true),
            status = try { Status.valueOf(o.optString("status")) } catch (e: Exception) { Status.NEW },
            partsTotal = o.optInt("partsTotal"),
            partsSent = o.optInt("partsSent"),
            partsDelivered = o.optInt("partsDelivered"),
            note = o.optString("note"),
            updatedAt = o.optLong("updatedAt"),
            sentAt = o.optLong("sentAt"),
            prefix = o.optString("prefix")
        )
    }
}

/** In-memory list of contacts, saved to app storage. All access happens on the main thread. */
object Store {
    val recipients = mutableListOf<Recipient>()

    @Volatile
    var running = false
    var progressLine = ""

    /** "id|message fingerprint" for every message already sent: the no-duplicates guard. */
    val sentLog = mutableSetOf<String>()
    /** People who replied STOP (or were marked opted out). They are never messaged again. */
    val optOuts = mutableSetOf<String>()
    /** Send times per person (number or email), for the 2-per-24-hours / 6-hours-apart rule. */
    val history = mutableMapOf<String, MutableList<Long>>()

    fun fingerprint(id: String, template: String): String =
        id + "|" + template.trim().replace(Regex("\\s+"), " ").hashCode()

    fun nextAllowedAt(id: String, now: Long = System.currentTimeMillis()): Long =
        GoodNeighbour.nextAllowedAt(history[id], now)

    fun recordSend(id: String, now: Long = System.currentTimeMillis()) {
        val list = history.getOrPut(id) { mutableListOf() }
        list.add(now)
        list.removeAll { now - it > 2 * GoodNeighbour.DAY_MS }
    }

    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("names_miner", Context.MODE_PRIVATE)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.toList().forEach { it() } } }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        readSet(ctx, "sentLog", sentLog)
        readSet(ctx, "optOuts", optOuts)
        try {
            val h = JSONObject(prefs(ctx).getString("history", "{}") ?: "{}")
            for (k in h.keys()) {
                val arr = h.getJSONArray(k)
                history[k] = MutableList(arr.length()) { arr.getLong(it) }
            }
        } catch (e: Exception) { }
        val raw = prefs(ctx).getString("recipients", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val r = Recipient.fromJson(arr.getJSONObject(i))
                // A fresh process means any earlier sending run was interrupted.
                if (r.status == Status.QUEUED) {
                    if (r.partsTotal == 0) r.status = Status.NEW
                    else { r.status = Status.SENT; r.note = "Run was interrupted; delivery unknown" }
                }
                recipients.add(r)
            }
        } catch (e: Exception) {
            recipients.clear()
        }
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        recipients.forEach { arr.put(it.toJson()) }
        val h = JSONObject()
        history.forEach { (k, v) -> h.put(k, JSONArray(v)) }
        prefs(ctx).edit()
            .putString("recipients", arr.toString())
            .putString("sentLog", JSONArray(sentLog.toList()).toString())
            .putString("optOuts", JSONArray(optOuts.toList()).toString())
            .putString("history", h.toString())
            .apply()
    }

    private fun readSet(ctx: Context, key: String, into: MutableSet<String>) {
        val raw = prefs(ctx).getString(key, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) into.add(arr.getString(i))
        } catch (e: Exception) { }
    }

    fun find(id: String): Recipient? = recipients.firstOrNull { it.id == id }

    /** Replace the list with a fresh scan, keeping selection and status for entries already known. */
    fun replaceWithScan(found: List<Recipient>) {
        val old = recipients.associateBy { it.id }
        recipients.clear()
        for (f in found) {
            old[f.id]?.let { o ->
                f.selected = o.selected
                f.status = o.status
                f.partsTotal = o.partsTotal
                f.partsSent = o.partsSent
                f.partsDelivered = o.partsDelivered
                f.note = o.note
                f.updatedAt = o.updatedAt
                f.sentAt = o.sentAt
            }
            if (f.id in optOuts) {
                f.status = Status.OPTED_OUT
                f.selected = false
            }
            recipients.add(f)
        }
    }
}
