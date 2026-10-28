package com.example.matepet

import android.content.Context
import android.graphics.*
import android.view.View

/** Lightweight 2D face for temi's screen: blinks, and the mouth moves while temi speaks. */
interface Talker { var talking: Boolean }

class FaceView(c: Context) : View(c), Talker {
    @Volatile override var talking = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onDraw(cv: Canvas) {
        val t = System.currentTimeMillis(); val w = width.toFloat(); val h = height.toFloat()
        cv.drawColor(Color.BLACK)
        val eye = if (t % 4000 < 150) h * .01f else h * .12f
        for (x in floatArrayOf(w * .32f, w * .68f))
            cv.drawOval(RectF(x - w * .05f, h * .35f - eye, x + w * .05f, h * .35f + eye), p)
        val open = if (talking) Math.abs(Math.sin(t / 90.0)).toFloat() * h * .08f else 0f
        cv.drawRoundRect(RectF(w * .42f, h * .6f, w * .58f, h * .61f + open), 24f, 24f, p)
        postInvalidateOnAnimation()
    }
}
