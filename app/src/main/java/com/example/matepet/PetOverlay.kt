package com.example.matepet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.filament.utils.Utils
import java.io.File

/**
 * The pet as a floating window over every other app. Voice only: the window never takes keyboard focus.
 * Drag to move it, press and hold to open the menu.
 */
class PetOverlay(private val ctx: Context, private val wm: WindowManager, private val onLongPress: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val root = LinearLayout(ctx)
    private val bubble = TextView(ctx)
    private val lp: WindowManager.LayoutParams
    private val sizePx = ctx.dp(Prefs.SIZES[Prefs.size(ctx)].second)
    private val hideBubble = Runnable { bubble.visibility = View.GONE }
    val avatar: Talker

    init {
        val fast = Prefs.fast(ctx); val transparent = Prefs.transparent(ctx)
        val vrm = File(ctx.getExternalFilesDir(null), "avatar.vrm")
        var v: View? = null
        if (vrm.exists()) {
            v = runCatching { Utils.init(); VrmAvatar(ctx, vrm, sizePx, if (fast) 0.7f else 1f, transparent) }
                .onFailure {
                    Log.e("MatePet", "VRM failed", it)
                    PetStatus.avatar = "VRM failed (${it.javaClass.simpleName}: ${it.message}), showing simple face"
                }.getOrNull()
            if (v != null) PetStatus.avatar = "VRM avatar (${vrm.length() / 1_000_000} MB)"
        } else PetStatus.avatar = "No avatar.vrm, showing simple face"
        val view = v ?: FaceView(ctx)
        avatar = view as Talker

        bubble.apply {
            textSize = 18f; setTextColor(Color.WHITE); maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            background = roundBg(0xD9202733.toInt(), ctx.dp(16)); setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            visibility = View.GONE
        }
        root.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble, LinearLayout.LayoutParams(sizePx, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(6) })
            addView(view, LinearLayout.LayoutParams(sizePx, sizePx))
            isClickable = true
            setOnTouchListener { _, e -> touch(e) }
        }

        val dm = ctx.resources.displayMetrics
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or            // never opens a keyboard, never steals input from the app below
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or      // touches outside the pet go straight to the app below
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT)
        // anchored bottom-left so the avatar stays put when the speech bubble above it grows
        lp.gravity = Gravity.BOTTOM or Gravity.START
        val margin = ctx.dp(24)
        lp.x = Prefs.int(ctx, "px", dm.widthPixels - sizePx - margin).coerceIn(0, (dm.widthPixels - sizePx).coerceAtLeast(0))
        lp.y = Prefs.int(ctx, "py", margin).coerceIn(0, (dm.heightPixels - sizePx).coerceAtLeast(0))
    }

    /** false if Android refused the window (permission missing). */
    fun add(): Boolean = try { wm.addView(root, lp); true } catch (e: Exception) {
        Log.e("MatePet", "overlay refused", e); PetStatus.avatar = "Overlay refused: ${e.javaClass.simpleName}"; false
    }

    fun remove() { main.removeCallbacks(hideBubble); runCatching { wm.removeView(root) } }

    fun showBubble(text: String) {
        main.post {
            bubble.text = text; bubble.visibility = View.VISIBLE
            main.removeCallbacks(hideBubble); main.postDelayed(hideBubble, 9000)
        }
    }

    fun hideBubbleSoon() { main.postDelayed(hideBubble, 1500) }

    // ---- touch: drag to move, press and hold for the menu ----
    private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longRun = Runnable { if (!dragging) onLongPress() }

    private fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                main.postDelayed(longRun, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!dragging && (dx * dx + dy * dy) > slop * slop) { dragging = true; main.removeCallbacks(longRun) }
                if (dragging) {
                    val dm = ctx.resources.displayMetrics
                    lp.x = (startX + dx).toInt().coerceIn(0, (dm.widthPixels - root.width).coerceAtLeast(0))
                    lp.y = (startY - dy).toInt().coerceIn(0, (dm.heightPixels - root.height).coerceAtLeast(0))   // y counts up from the bottom
                    runCatching { wm.updateViewLayout(root, lp) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longRun)
                if (dragging) { Prefs.putInt(ctx, "px", lp.x); Prefs.putInt(ctx, "py", lp.y) }
            }
        }
        return true
    }
}
