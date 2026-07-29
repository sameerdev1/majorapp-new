package com.majorgym.app.data

import org.json.JSONArray
import org.json.JSONObject

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

fun String.toHistoryList(): List<HistoryEntry> {
    if (isBlank()) return emptyList()
    val arr = JSONArray(this)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        HistoryEntry(
            type = o.getString("type"),
            plan = o.getString("plan"),
            fee = o.getDouble("fee"),
            dateMillis = o.getLong("date"),
            expiryMillis = o.getLong("expiry")
        )
    }
}
