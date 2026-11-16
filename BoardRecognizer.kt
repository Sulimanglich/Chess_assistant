package com.example.chessassistant

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs

/**
 * Распознаёт позицию по скриншоту без ML: форма фигуры (маска отличий от фона клетки)
 * сравнивается с образцами, снятыми со стартовой расстановки; цвет фигуры — по яркости.
 */
class BoardRecognizer {

    private class Sample(val type: Char, val white: Boolean, val mask: BooleanArray, val lum: Float)
    private class Cell(val mask: BooleanArray, val fill: Float, val lum: Float)

    private val samples = ArrayList<Sample>()
    val ready: Boolean get() = samples.isNotEmpty()

    companion object {
        private const val S = 32                       // размер клетки после уменьшения
        private const val D = 8 * S
        private const val RING = 3                     // рамка клетки = фон
        private const val RING_N = S * S - (S - 2 * RING) * (S - 2 * RING)
        private const val DIFF = 60                    // порог отличия от фона
        private const val EMPTY_FILL = 0.10f           // меньше — клетка пустая

        /** Фигура в стартовой позиции на экранной клетке (row — сверху, col — слева). */
        fun startPiece(row: Int, col: Int, whiteBottom: Boolean): Char? {
            val back = "RNBQKBNR"
            return if (whiteBottom) {
                when (row) {
                    0 -> back[col].lowercaseChar()
                    1 -> 'p'
                    6 -> 'P'
                    7 -> back[col]
                    else -> null
                }
            } else {
                val rev = back.reversed()
                when (row) {
                    0 -> rev[col]
                    1 -> 'P'
                    6 -> 'p'
                    7 -> rev[col].lowercaseChar()
                    else -> null
                }
            }
        }
    }

    /** Усредняет рамку доски до D×D пикселей (формат 0xRRGGBB). */
    private fun downscale(bmp: Bitmap, r: RectF): IntArray {
        val x0 = r.left.toInt().coerceIn(0, bmp.width - 9)
        val y0 = r.top.toInt().coerceIn(0, bmp.height - 9)
        val w = minOf(r.width().toInt(), bmp.width - x0).coerceAtLeast(8)
        val h = minOf(r.height().toInt(), bmp.height - y0).coerceAtLeast(8)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)

        val sr = IntArray(D * D)
        val sg = IntArray(D * D)
        val sb = IntArray(D * D)
        val n = IntArray(D * D)
        for (y in 0 until h) {
            val rowOff = (y * D / h) * D
            val srcOff = y * w
            for (x in 0 until w) {
                val p = px[srcOff + x]
                val i = rowOff + x * D / w
                sr[i] += (p shr 16) and 255
                sg[i] += (p shr 8) and 255
                sb[i] += p and 255
                n[i]++
            }
        }
        return IntArray(D * D) { i ->
            val c = n[i].coerceAtLeast(1)
            ((sr[i] / c) shl 16) or ((sg[i] / c) shl 8) or (sb[i] / c)
        }
    }

    private fun cell(small: IntArray, col: Int, row: Int): Cell {
        val ox = col * S
        val oy = row * S
        val rr = IntArray(RING_N)
        val rg = IntArray(RING_N)
        val rb = IntArray(RING_N)
        var k = 0
        for (j in 0 until S) for (i in 0 until S) {
            if (i < RING || j < RING || i >= S - RING || j >= S - RING) {
                val p = small[(oy + j) * D + ox + i]
                rr[k] = (p shr 16) and 255
                rg[k] = (p shr 8) and 255
                rb[k] = p and 255
                k++
            }
        }
        rr.sort(); rg.sort(); rb.sort()
        val br = rr[k / 2]
        val bg = rg[k / 2]
        val bb = rb[k / 2]

        val mask = BooleanArray(S * S)
        var cnt = 0
        var lumSum = 0f
        for (j in RING until S - RING) for (i in RING until S - RING) {
            val p = small[(oy + j) * D + ox + i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            if (abs(r - br) + abs(g - bg) + abs(b - bb) > DIFF) {
                mask[j * S + i] = true
                cnt++
                lumSum += 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        val inner = (S - 2 * RING) * (S - 2 * RING)
        return Cell(mask, cnt.toFloat() / inner, if (cnt > 0) lumSum / cnt else 0f)
    }

    private fun iou(a: BooleanArray, b: BooleanArray): Float {
        var inter = 0
        var uni = 0
        for (i in a.indices) {
            if (a[i] && b[i]) inter++
            if (a[i] || b[i]) uni++
        }
        return if (uni == 0) 0f else inter.toFloat() / uni
    }

    /** Запоминает фигуры со стартовой расстановки (сторона внизу определяется по яркости). */
    fun learn(bmp: Bitmap, board: RectF): String? {
        val small = downscale(bmp, board)
        val cells = Array(8) { r -> Array(8) { c -> cell(small, c, r) } }

        fun meanLum(rows: IntRange): Float {
            var s = 0f
            var n = 0
            for (r in rows) for (c in 0 until 8) {
                if (cells[r][c].fill >= EMPTY_FILL) { s += cells[r][c].lum; n++ }
            }
            return if (n == 0) 0f else s / n
        }
        val whiteBottom = meanLum(6..7) > meanLum(0..1)

        val fresh = ArrayList<Sample>()
        for (row in 0 until 8) for (col in 0 until 8) {
            val c = cells[row][col]
            val p = startPiece(row, col, whiteBottom)
            if (p == null) {
                if (c.fill > EMPTY_FILL) return "Нужна начальная расстановка (или рамка доски сдвинута)"
            } else {
                if (c.fill < EMPTY_FILL) return "Нужна начальная расстановка: нет фигуры на ${'a' + col}${8 - row}"
                fresh.add(Sample(p.uppercaseChar(), p.isUpperCase(), c.mask, c.lum))
            }
        }
        samples.clear()
        samples.addAll(fresh)
        return null
    }

    private fun classify(c: Cell): Char {
        var best: Sample = samples[0]
        var bestIou = -1f
        for (s in samples) {
            val v = iou(c.mask, s.mask)
            if (v > bestIou) {
                bestIou = v
                best = s
            }
        }
        val type = best.type
        val w = samples.filter { it.type == type && it.white }.map { it.lum.toDouble() }.average()
        val b = samples.filter { it.type == type && !it.white }.map { it.lum.toDouble() }.average()
        return if (abs(c.lum - w) < abs(c.lum - b)) type else type.lowercaseChar()
    }

    /** Сетка по экрану: [строка сверху][колонка слева], ' ' — пусто. */
    fun readGrid(bmp: Bitmap, board: RectF): Array<CharArray> {
        val small = downscale(bmp, board)
        val g = Array(8) { CharArray(8) { ' ' } }
        for (row in 0 until 8) for (col in 0 until 8) {
            val c = cell(small, col, row)
            if (c.fill >= EMPTY_FILL) g[row][col] = classify(c)
        }
        return g
    }

    /** Белые внизу? Определяется по тому, чьи фигуры в среднем ближе к низу экрана. */
    fun whiteIsBottom(g: Array<CharArray>): Boolean {
        var ws = 0; var wn = 0; var bs = 0; var bn = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = g[r][c]
            if (ch == ' ') continue
            if (ch.isUpperCase()) { ws += r; wn++ } else { bs += r; bn++ }
        }
        if (wn == 0 || bn == 0) return true
        return ws.toFloat() / wn > bs.toFloat() / bn
    }

    /** Кто только что сходил: 'w', 'b' или null. */
    fun moverOf(prev: Array<CharArray>, cur: Array<CharArray>): Char? {
        var w = false
        var b = false
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = cur[r][c]
            if (ch != ' ' && ch != prev[r][c]) {
                if (ch.isUpperCase()) w = true else b = true
            }
        }
        return when {
            w && !b -> 'w'
            b && !w -> 'b'
            else -> null
        }
    }

    /** Грубый «отпечаток» доски для обнаружения изменений. */
    fun signature(bmp: Bitmap, board: RectF): IntArray {
        val small = downscale(bmp, board)
        val n = D / 4
        return IntArray(n * n) { i -> small[(i / n * 4) * D + (i % n) * 4] }
    }

    fun differs(a: IntArray, b: IntArray): Boolean {
        var cnt = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            val d = abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
                abs(((p shr 8) and 255) - ((q shr 8) and 255)) +
                abs((p and 255) - (q and 255))
            if (d > 80) cnt++
        }
        return cnt > 24
    }

    /** FEN из экранной сетки. Бросает IllegalStateException с текстом для пользователя. */
    fun toFen(g: Array<CharArray>, whiteBottom: Boolean, whiteToMove: Boolean): String {
        val grid = Array(8) { CharArray(8) { ' ' } }   // [8-я … 1-я горизонталь][a … h]
        for (row in 0 until 8) for (col in 0 until 8) {
            val ch = g[row][col]
            if (ch == ' ') continue
            val r = if (whiteBottom) row else 7 - row
            val f = if (whiteBottom) col else 7 - col
            grid[r][f] = ch
        }

        val wk = grid.sumOf { line -> line.count { it == 'K' } }
        val bk = grid.sumOf { line -> line.count { it == 'k' } }
        if (wk != 1 || bk != 1) {
            throw IllegalStateException("Позиция не распознана (королей: $wk/$bk). Проверьте доску и фигуры")
        }
        if (grid[0].any { it == 'P' || it == 'p' } || grid[7].any { it == 'P' || it == 'p' }) {
            throw IllegalStateException("Позиция не распознана (пешка на крайней горизонтали)")
        }

        val sb = StringBuilder()
        for (r in 0 until 8) {
            var empty = 0
            for (f in 0 until 8) {
                val ch = grid[r][f]
                if (ch == ' ') {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }

        var castle = ""
        if (grid[7][4] == 'K') {
            if (grid[7][7] == 'R') castle += "K"
            if (grid[7][0] == 'R') castle += "Q"
        }
        if (grid[0][4] == 'k') {
            if (grid[0][7] == 'r') castle += "k"
            if (grid[0][0] == 'r') castle += "q"
        }
        if (castle.isEmpty()) castle = "-"

        return "$sb ${if (whiteToMove) "w" else "b"} $castle - 0 1"
    }

    fun serialize(): String = samples.joinToString("\n") { s ->
        val bits = CharArray(s.mask.size) { if (s.mask[it]) '1' else '0' }.concatToString()
        "${s.type},${if (s.white) 1 else 0},${s.lum},$bits"
    }

    fun load(data: String) {
        samples.clear()
        for (line in data.lines()) {
            val p = line.split(",")
            if (p.size != 4 || p[3].length != S * S) continue
            val lum = p[2].toFloatOrNull() ?: continue
            samples.add(Sample(p[0][0], p[1] == "1", BooleanArray(S * S) { p[3][it] == '1' }, lum))
        }
    }
}
