package com.example.chessassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.hypot

/** Полноэкранный прозрачный слой, рисующий одну стрелку в экранных координатах. */
class ArrowView(context: Context) : View(context) {

    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f
    private var cell = 0f
    private var visible = false
    private val loc = IntArray(2)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FF8A00.toInt()
        style = Paint.Style.STROKE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FF8A00.toInt()
        style = Paint.Style.FILL
    }

    fun show(fromX: Float, fromY: Float, toX: Float, toY: Float, cellSize: Float) {
        x1 = fromX; y1 = fromY; x2 = toX; y2 = toY; cell = cellSize
        visible = true
        invalidate()
    }

    fun clear() {
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val head = cell * 0.5f
        val halfW = cell * 0.3f

        val sx = x1 + ux * cell * 0.15f
        val sy = y1 + uy * cell * 0.15f
        val bx = x2 - ux * head
        val by = y2 - uy * head

        linePaint.strokeWidth = cell * 0.18f
        canvas.drawLine(sx, sy, bx, by, linePaint)

        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(bx - uy * halfW, by + ux * halfW)
            lineTo(bx + uy * halfW, by - ux * halfW)
            close()
        }
        canvas.drawPath(path, headPaint)
    }
}
