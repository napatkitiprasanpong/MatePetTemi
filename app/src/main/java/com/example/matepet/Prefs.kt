package com.example.matepet

import android.content.Context

/** User settings (changed from the long-press menu > Settings). */
object Prefs {
    /** label to avatar square size in dp */
    val SIZES = arrayOf("Small" to 200, "Medium" to 280, "Large" to 380)

    private fun sp(c: Context) = c.getSharedPreferences("matepet", Context.MODE_PRIVATE)
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()

    fun size(c: Context) = int(c, "size", 1).coerceIn(0, SIZES.lastIndex)
    fun setSize(c: Context, v: Int) = putInt(c, "size", v)

    /** true = render the avatar at reduced resolution (much lighter on temi's GPU) */
    fun fast(c: Context) = sp(c).getBoolean("fast", true)
    fun setFast(c: Context, v: Boolean) = sp(c).edit().putBoolean("fast", v).apply()

    /** listen for "hey mate" in the background */
    fun wake(c: Context) = sp(c).getBoolean("wake", true)
    fun setWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("wake", v).apply()

    fun transparent(c: Context) = sp(c).getBoolean("transparent", true)
    fun setTransparent(c: Context, v: Boolean) = sp(c).edit().putBoolean("transparent", v).apply()
}

/** Live facts shown in the "Status" menu. Written from any thread, read on the UI thread. */
object PetStatus {
    @Volatile var voice = "Starting..."
    @Volatile var llm = "Loading..."
    @Volatile var avatar = "Not shown"
    @Volatile var heard = "-"
    @Volatile var speed = "-"
    @Volatile var iaq = "-"
    @Volatile var fps = 0
}
