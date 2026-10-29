package com.example.matepet

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Client for the IAQ/IEQ monitoring app's bridge. Protocol: iaq-bridge/INTEGRATION.md */
object IaqClient {
    const val PKG = "com.leslie.iaqieqmornitoring"          // applicationId of the IAQ app
    private val STATUS: Uri = Uri.parse("content://$PKG.bridge/status")
    /** key -> (spoken name, spoken unit) */
    val SENSORS = linkedMapOf(
        "co2" to ("CO2" to "parts per million"), "temp" to ("temperature" to "degrees Celsius"),
        "humidity" to ("humidity" to "percent"), "pm25" to ("PM 2.5" to "micrograms per cubic metre"),
        "pm10" to ("PM 10" to "micrograms per cubic metre"), "tvoc" to ("TVOC" to "milligrams per cubic metre"),
        "hcho" to ("formaldehyde" to "milligrams per cubic metre"), "light" to ("light" to "lux"), "sound" to ("sound" to "decibels"))

    fun open(c: Context): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }
    fun send(c: Context, cmd: String, arg: String = "") =
        c.sendBroadcast(Intent("$PKG.action.COMMAND").setPackage(PKG).putExtra("cmd", cmd).putExtra("arg", arg))

    /** Live status JSON, or null if the app isn't installed/running/ready. Call off the main thread. */
    fun status(c: Context): JSONObject? = runCatching {
        c.contentResolver.query(STATUS, null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }.getOrNull()

    private fun value(j: JSONObject, k: String) = j.optJSONObject("sensors")?.optDouble(k)?.takeIf { !it.isNaN() }
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

    fun sensorLine(j: JSONObject, k: String): String? {
        val (n, u) = SENSORS[k] ?: return null
        return value(j, k)?.let { "$n is ${fmt(it)} $u" }
    }
    /** What temi says for "how is the air?" */
    fun summary(j: JSONObject): String {
        val m = j.optJSONObject("mission")?.takeIf { it.optString("state") == "running" }
        return buildString {
            append("Air quality is ${j.optString("level", "unknown")}, score ${j.optInt("iaq")}. ")
            append(listOf("co2", "temp", "humidity", "pm25").mapNotNull { sensorLine(j, it) }.joinToString(". ")).append(". ")
            if (!j.optBoolean("fresh", true)) append("The sensor data looks old. ")
            if (m != null) append("A mission is running, stop ${m.optInt("stop")} of ${m.optInt("total")}.")
        }.trim()
    }
    /** Compact data line injected into the LLM prompt. */
    fun compact(j: JSONObject) = "IAQ ${j.optInt("iaq")}/100 (${j.optString("level")}); " +
        SENSORS.keys.mapNotNull { k -> value(j, k)?.let { "${SENSORS[k]!!.first} ${fmt(it)}" } }.joinToString(", ")
}
