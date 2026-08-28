package com.majorgym.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "History"

data class HistoryEntry(
    val type: String,
    val plan: String,
    val fee: Double,
    val dateMillis: Long,
    val expiryMillis: Long
)

fun List<HistoryEntry>.toJson(): String {
    val arr = JSONArray()
    forEach {
        val o = JSONObject()
        o.put("type", it.type)
        o.put("plan", it.plan)
        o.put("fee", it.fee)
        o.put("date", it.dateMillis)
        o.put("expiry", it.expiryMillis)
        arr.put(o)
    }
    return arr.toString()
}

/**
 * Parses stored/synced/restored history JSON defensively (fix #5): malformed
 * JSON, a non-array payload, missing fields, wrong types, or one corrupted
 * entry among many good ones must never crash the Profile screen. A single
 * bad entry is skipped and logged (not silently pretended never to have
 * existed at the top level - the owner can still tell something was off by
 * seeing fewer history rows than expected), and a completely unparsable
 * payload falls back to an empty list rather than throwing.
 */
fun String.toHistoryList(): List<HistoryEntry> {
    if (isBlank()) return emptyList()
    val arr = try {
        JSONArray(this)
    } catch (e: Exception) {
        Log.w(TAG, "Corrupted history JSON, showing empty history: ${e.message}")
        return emptyList()
    }
    val result = mutableListOf<HistoryEntry>()
    for (i in 0 until arr.length()) {
        try {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "").ifBlank { "Unknown" }
            val plan = o.optString("plan", "").ifBlank { "Unknown" }
            val fee = o.optDouble("fee", 0.0).let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
            val date = o.optLong("date", 0L)
            val expiry = o.optLong("expiry", 0L)
            result += HistoryEntry(type, plan, fee, date, expiry)
        } catch (e: Exception) {
            Log.w(TAG, "Skipping corrupted history entry $i: ${e.message}")
        }
    }
    return result
}
