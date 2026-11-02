package com.example.matepet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** The menu that opens when you hold the pet: Quit / Settings / Status. A separate window so it always sits above the avatar. */
class PetPanel(private val ctx: Context, private val wm: WindowManager, private val host: Host) {
    interface Host {
        fun quit()                      // hide the pet (keeps listening for "hey mate" if that is on)
        fun exitAll()                   // stop MatePet completely
        fun settingsChanged()
        fun statusText(): String
        fun refreshExternalStatus()     // slow checks (IAQ app) done off the UI thread
    }

    private val main = Handler(Looper.getMainLooper())
    private val box = LinearLayout(ctx)
    private var statusView: TextView? = null
    private var shown = false
    private val autoClose = Runnable { dismiss() }
    private val refresh = object : Runnable {
        override fun run() { statusView?.text = host.statusText(); if (statusView != null) main.postDelayed(this, 1000) }
    }

    fun show() {
        box.orientation = LinearLayout.VERTICAL
        box.background = roundBg(0xF2141A24.toInt(), ctx.dp(22))
        box.setPadding(ctx.dp(22), ctx.dp(20), ctx.dp(22), ctx.dp(20))
        box.minimumWidth = ctx.dp(340)
        box.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false }
        menuPage()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.CENTER
        shown = runCatching { wm.addView(box, lp); true }.getOrDefault(false)
    }

    fun dismiss() {
        main.removeCallbacks(autoClose); main.removeCallbacks(refresh); statusView = null
        if (shown) { shown = false; runCatching { wm.removeView(box) } }
    }

    // ---- pages ----
    private fun reset(title: String, closeAfterMs: Long) {
        main.removeCallbacks(autoClose); main.removeCallbacks(refresh); statusView = null
        box.removeAllViews()
        box.addView(TextView(ctx).apply {
            text = title; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, ctx.dp(14))
        })
        main.postDelayed(autoClose, closeAfterMs)
    }

    private fun button(label: String, color: Int = 0xFF3A4252.toInt(), onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = roundBg(color, ctx.dp(14)); setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) }
    }

    private fun menuPage() {
        reset("MatePet", 12_000)
        box.addView(button("Quit", 0xFFD64545.toInt()) { dismiss(); host.quit() })
        box.addView(button("Settings") { settingsPage() })
        box.addView(button("Status") { statusPage() })
    }

    private fun statusPage() {
        reset("Status", 120_000)
        host.refreshExternalStatus()
        val t = TextView(ctx).apply { textSize = 17f; setTextColor(0xFFDDE3EE.toInt()); typeface = Typeface.MONOSPACE; text = host.statusText() }
        statusView = t
        box.addView(t)
        box.addView(button("Back") { menuPage() })
        main.postDelayed(refresh, 1000)
    }

    private fun settingsPage() {
        reset("Settings", 60_000)
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        box.addView(button("Pet size: ${Prefs.SIZES[Prefs.size(ctx)].first}") {
            Prefs.setSize(ctx, (Prefs.size(ctx) + 1) % Prefs.SIZES.size); host.settingsChanged(); settingsPage()
        })
        box.addView(button("Graphics: " + if (Prefs.fast(ctx)) "Fast" else "Sharp") {
            Prefs.setFast(ctx, !Prefs.fast(ctx)); host.settingsChanged(); settingsPage()
        })
        box.addView(button("Wake word \"hey mate\": ${onOff(Prefs.wake(ctx))}") {
            Prefs.setWake(ctx, !Prefs.wake(ctx)); host.settingsChanged(); settingsPage()
        })
        box.addView(button("Transparent background: ${onOff(Prefs.transparent(ctx))}") {
            Prefs.setTransparent(ctx, !Prefs.transparent(ctx)); host.settingsChanged(); settingsPage()
        })
        box.addView(button("Exit MatePet completely", 0xFFD64545.toInt()) { dismiss(); host.exitAll() })
        box.addView(button("Back") { menuPage() })
    }
}
