package com.example.matepet

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.WindowManager

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusPx: Int): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radiusPx.toFloat() }

/** Window type for a floating overlay: API 26+ needs TYPE_APPLICATION_OVERLAY, older temi V2 (Android 6) uses TYPE_PHONE. */
@Suppress("DEPRECATION")
fun overlayType(): Int =
    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE
