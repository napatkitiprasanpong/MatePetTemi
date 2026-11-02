package com.example.matepet

import android.content.Context
import android.graphics.*
import android.view.View

/** Lightweight 2D face used when there is no avatar.vrm: transparent background, blinks, mouth moves while temi speaks. */
interface Talker { var talking: Boolean }

class FaceView(c: Context) : View(c), Talker {
    @Volatile override var talking = false
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD66B.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2118.toInt() }
    override fun onDraw(cv: Canvas) {
        val t = System.currentTimeMillis(); val w = width.toFloat(); val h = height.toFloat()
        cv.drawCircle(w / 2, h / 2, minOf(w, h) * .46f, face)
        val eye = if (t % 4000 < 150) h * .01f else h * .07f
        for (x in floatArrayOf(w * .36f, w * .64f))
            cv.drawOval(RectF(x - w * .035f, h * .42f - eye, x + w * .035f, h * .42f + eye), ink)
        val open = if (talking) Math.abs(Math.sin(t / 90.0)).toFloat() * h * .08f else 0f
        cv.drawRoundRect(RectF(w * .42f, h * .62f, w * .58f, h * .63f + open), 24f, 24f, ink)
        postInvalidateDelayed(if (talking) 33L else 70L)   // no need to burn 60 fps on a blinking face
    }
}
