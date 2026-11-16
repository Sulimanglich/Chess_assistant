package com.example.chessassistant

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock

/** Постоянный VirtualDisplay; всегда хранит последний кадр, из него по запросу вырезается область. */
class ScreenCapture(
    projection: MediaProjection,
    width: Int,
    height: Int,
    dpi: Int,
    handler: Handler,
    onStopped: () -> Unit
) {
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val display: VirtualDisplay
    private val lock = Any()
    private var held: Image? = null

    @Volatile var lastFrameAt = 0L
        private set

    init {
        // Android 14+: callback обязателен до createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        }, handler)

        display = projection.createVirtualDisplay(
            "chess-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                synchronized(lock) {
                    held?.close()
                    held = img
                    lastFrameAt = SystemClock.uptimeMillis()
                }
            }
        }, handler)
    }

    /** Ждёт кадра, пришедшего не раньше момента t (uptimeMillis). */
    fun waitFrameAfter(t: Long, timeoutMs: Long) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (lastFrameAt < t && SystemClock.uptimeMillis() < end) Thread.sleep(25)
    }

    /** Вырезает область из последнего кадра (null, если кадров ещё не было). */
    fun snapshot(region: Rect): Bitmap? = synchronized(lock) {
        val img = held ?: return null
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val x0 = region.left.coerceIn(0, img.width - 9)
        val y0 = region.top.coerceIn(0, img.height - 9)
        val w = minOf(region.width(), img.width - x0).coerceAtLeast(8)
        val h = minOf(region.height(), img.height - y0).coerceAtLeast(8)
        val px = IntArray(w * h)
        val row = ByteArray(w * ps)
        for (y in 0 until h) {
            buf.position((y0 + y) * rs + x0 * ps)
            buf.get(row, 0, w * ps)
            for (x in 0 until w) {
                val i = x * ps
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        synchronized(lock) {
            held?.close()
            held = null
        }
        try { display.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }
}
