package com.example.chessassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Полноэкранный слой: два тапа по углам доски, затем подтверждение по сетке. */
class BoardPickerView(
    context: Context,
    private val onDone: (View, RectF) -> Unit
) : View(context) {

    private var a: PointF? = null
    private var b: PointF? = null
    private val loc = IntArray(2)

    private val dim = Paint().apply { color = 0x55000000 }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    private fun rect(): RectF? {
        val p = a ?: return null
        val q = b ?: return null
        return RectF(min(p.x, q.x), min(p.y, q.y), max(p.x, q.x), max(p.y, q.y))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.rawX
        val y = e.rawY
        when {
            a == null -> a = PointF(x, y)
            b == null -> b = PointF(x, y)
            else -> {
                val r = rect()!!
                if (r.contains(x, y)) {
                    onDone(this, r)
                    return true
                }
                a = PointF(x, y)
                b = null
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        c.drawPaint(dim)

        val hint = when {
            a == null -> "Тапните ЛЕВЫЙ ВЕРХНИЙ угол доски"
            b == null -> "Теперь ПРАВЫЙ НИЖНИЙ угол доски"
            else -> "Сетка совпала? Тап внутри — готово, мимо — заново"
        }
        c.drawText(hint, 30f, 230f, text)

        a?.let { c.drawCircle(it.x, it.y, 18f, grid) }
        b?.let { c.drawCircle(it.x, it.y, 18f, grid) }
        rect()?.let { r ->
            c.drawRect(r, grid)
            for (i in 1 until 8) {
                val gx = r.left + r.width() * i / 8f
                val gy = r.top + r.height() * i / 8f
                c.drawLine(gx, r.top, gx, r.bottom, grid)
                c.drawLine(r.left, gy, r.right, gy, grid)
            }
        }
    }
}
