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
    FAILED("Failed")
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
    var updatedAt: Long = 0L
) {
    fun resetStatus() {
        status = Status.NEW
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
        .put("note", note).put("updatedAt", updatedAt)

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
            updatedAt = o.optLong("updatedAt")
        )
    }
}

/** In-memory list of matched clients, saved to app storage. All access happens on the main thread. */
object Store {
    val recipients = mutableListOf<Recipient>()

    @Volatile
    var running = false
    var progressLine = ""

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
        prefs(ctx).edit().putString("recipients", arr.toString()).apply()
    }

    fun find(id: String): Recipient? = recipients.firstOrNull { it.id == id }

    /** Replace the list with a fresh scan, keeping selection and status for numbers already known. */
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
            }
            recipients.add(f)
        }
    }
}
