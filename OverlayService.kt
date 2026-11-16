package com.example.chessassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private enum class ColorMode { AUTO, WHITE, BLACK }

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "chess"
        private const val START_BOARD = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        private const val ORANGE = 0xFFFF8A00.toInt()
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var playBtn: TextView
    private lateinit var chipAuto: TextView
    private lateinit var chipWhite: TextView
    private lateinit var chipBlack: TextView
    private lateinit var menu: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureThread = HandlerThread("chess-capture").apply { start() }

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var panel: View? = null
    private var arrow: ArrowView? = null

    private val engine by lazy { StockfishManager(this) }
    private val recognizer = BoardRecognizer()

    private var board: RectF? = null
    private var colorMode = ColorMode.AUTO
    @Volatile private var curWhiteBottom = true
    @Volatile private var engineReady = false
    @Volatile private var running = false
    @Volatile private var arrowVisible = false

    // слежение за изменениями на доске (используется только в потоке worker)
    private var baseSig: IntArray? = null
    private var settleSig: IntArray? = null
    private var skipUntil = 0L
    private var prevGrid: Array<CharArray>? = null

    private val tickRunnable = Runnable { worker.execute { tick() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground-уведомление обязательно ДО получения MediaProjection
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Chess Assistant", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Chess Assistant работает")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        if (capture != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("chess", MODE_PRIVATE)
        loadPrefs()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        val (w, h) = screenSize()
        capture = ScreenCapture(
            proj, w, h, resources.displayMetrics.densityDpi, Handler(captureThread.looper)
        ) { ui.post { stopSelf() } }

        addArrowLayer()
        addPanel()

        worker.execute {
            val err = engine.start()
            engineReady = err == null
            ui.post { say(err ?: "Нажмите «Начать»") }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    // ---------- настройки ----------

    private fun loadPrefs() {
        colorMode = ColorMode.values()[prefs.getInt("color", 0).coerceIn(0, 2)]
        if (prefs.contains("bl")) {
            board = RectF(
                prefs.getFloat("bl", 0f), prefs.getFloat("bt", 0f),
                prefs.getFloat("br", 0f), prefs.getFloat("bb", 0f)
            )
        }
        prefs.getString("templates", null)?.let { recognizer.load(it) }
    }

    private fun saveBoard(r: RectF) {
        prefs.edit()
            .putFloat("bl", r.left).putFloat("bt", r.top)
            .putFloat("br", r.right).putFloat("bb", r.bottom)
            .apply()
    }

    // ---------- окна ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun addArrowLayer() {
        val v = ArrowView(this)
        arrow = v
        wm.addView(v, fullScreenParams(touchable = false))
    }

    private fun label(t: String, size: Float = 16f) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun addPanel() {
        status = label("Загрузка…", 12f).apply {
            background = roundBg(0xCC121420.toInt(), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(0xFFDDDDDD.toInt())
            maxWidth = dp(260)
        }
        playBtn = label("Начать")
        val menuBtn = label("☰", 20f)
        val closeBtn = label("✕", 18f)

        fun divider() = View(this).apply {
            setBackgroundColor(0xFF444654.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(0xF2121420.toInt(), 26)
            addView(playBtn)
            addView(divider())
            addView(menuBtn)
            addView(divider())
            addView(closeBtn)
        }

        chipAuto = label("Авто", 13f)
        chipWhite = label("Белые", 13f)
        chipBlack = label("Чёрные", 13f)
        chipAuto.setOnClickListener { setColor(ColorMode.AUTO) }
        chipWhite.setOnClickListener { setColor(ColorMode.WHITE) }
        chipBlack.setOnClickListener { setColor(ColorMode.BLACK) }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Цвет:", 13f))
            addView(chipAuto)
            addView(chipWhite)
            addView(chipBlack)
        }
        val itemBoard = label("Выбрать доску", 14f).apply { setOnClickListener { onBoard(false) } }
        val itemLearn = label("Запомнить фигуры", 14f).apply { setOnClickListener { learnNow(false) } }
        val itemStop = label("Стоп", 14f).apply {
            setTextColor(0xFFFF6B6B.toInt())
            setOnClickListener { stopSelf() }
        }
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xF2121420.toInt(), 16)
            visibility = View.GONE
            addView(chipsRow)
            addView(itemBoard)
            addView(itemLearn)
            addView(itemStop)
        }
        refreshChips()

        fun gap() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(pill, gap())
            addView(menu, gap())
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(100)
        }

        // кнопки панели: короткий тап = действие, перетаскивание = двигает панель
        fun draggable(v: View, onClick: () -> Unit) {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            v.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (moved || abs(dx) > 12 || abs(dy) > 12) {
                            moved = true
                            lp.x = sx + dx.toInt()
                            lp.y = sy + dy.toInt()
                            wm.updateViewLayout(root, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onClick()
                }
                true
            }
        }
        draggable(status) { }
        draggable(playBtn) { onStartPause() }
        draggable(menuBtn) {
            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        draggable(closeBtn) { stopSelf() }

        panel = root
        wm.addView(root, lp)
    }

    private fun say(text: String) {
        status.text = text
    }

    private fun setColor(m: ColorMode) {
        colorMode = m
        prefs.edit().putInt("color", m.ordinal).apply()
        refreshChips()
        say(
            when (m) {
                ColorMode.AUTO -> "Цвет: авто"
                ColorMode.WHITE -> "Вы играете белыми"
                ColorMode.BLACK -> "Вы играете чёрными"
            }
        )
        prevGrid = null
    }

    private fun refreshChips() {
        fun mark(v: TextView, on: Boolean) {
            v.background = if (on) roundBg(ORANGE, 14) else null
        }
        mark(chipAuto, colorMode == ColorMode.AUTO)
        mark(chipWhite, colorMode == ColorMode.WHITE)
        mark(chipBlack, colorMode == ColorMode.BLACK)
    }

    // ---------- управление ----------

    private fun regionOf(b: RectF) = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())

    private fun onStartPause() {
        if (running) {
            running = false
            ui.removeCallbacks(tickRunnable)
            arrow?.clear()
            arrowVisible = false
            playBtn.text = "Начать"
            say("Пауза")
            return
        }
        menu.visibility = View.GONE
        if (board == null) {
            onBoard(true)
            return
        }
        if (!recognizer.ready) {
            learnNow(true)
            return
        }
        startRunning()
    }

    private fun startRunning() {
        if (!engineReady) return say("Движок не готов")
        val b = board ?: return
        val cap = capture ?: return
        running = true
        playBtn.text = "⏸"
        say("Анализ…")
        prevGrid = null
        baseSig = null
        settleSig = null
        skipUntil = 0L
        worker.execute {
            try {
                analyze(cap, b)
            } catch (e: Exception) {
                ui.post { say(e.message ?: "Ошибка") }
            } finally {
                scheduleTick()
            }
        }
    }

    private fun onBoard(thenStart: Boolean) {
        menu.visibility = View.GONE
        val picker = BoardPickerView(this) { v, r ->
            wm.removeView(v)
            board = r
            saveBoard(r)
            learnNow(thenStart)
        }
        wm.addView(picker, fullScreenParams(touchable = true))
    }

    /** Запоминает фигуры (нужна начальная расстановка на экране). */
    private fun learnNow(thenStart: Boolean) {
        menu.visibility = View.GONE
        val b = board ?: return say("Сначала выберите доску")
        val cap = capture ?: return
        say("Запоминаю фигуры…")
        arrow?.clear()
        arrowVisible = false
        worker.execute {
            Thread.sleep(350) // чтобы со снимка исчезли стрелка / окно выбора доски
            val bmp = cap.snapshot(regionOf(b))
            if (bmp == null) {
                ui.post { say("Нет кадра, повторите") }
                return@execute
            }
            val err = recognizer.learn(bmp, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
            bmp.recycle()
            if (err == null) prefs.edit().putString("templates", recognizer.serialize()).apply()
            ui.post {
                if (err != null) {
                    say(err)
                } else {
                    say("Фигуры запомнены ✓")
                    if (thenStart) startRunning()
                }
            }
        }
    }

    // ---------- цикл слежения ----------

    private fun scheduleTick(delayMs: Long = 450) {
        if (!running) return
        ui.removeCallbacks(tickRunnable)
        ui.postDelayed(tickRunnable, delayMs)
    }

    private fun resetBaseline(extraSkipMs: Long) {
        baseSig = null
        settleSig = null
        skipUntil = SystemClock.uptimeMillis() + extraSkipMs
    }

    private fun tick() {
        try {
            val b = board
            val cap = capture
            if (!running || b == null || cap == null) return
            if (SystemClock.uptimeMillis() < skipUntil) return

            val bmp = cap.snapshot(regionOf(b)) ?: return
            val sig = recognizer.signature(bmp, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
            bmp.recycle()

            val base = baseSig
            if (base == null) {
                baseSig = sig
                settleSig = null
                return
            }
            if (!recognizer.differs(sig, base)) {
                settleSig = null
                return
            }
            val settle = settleSig
            if (settle != null && !recognizer.differs(sig, settle)) {
                // доска изменилась и «успокоилась» (анимация хода закончилась)
                settleSig = null
                analyze(cap, b)
            } else {
                settleSig = sig
            }
        } catch (e: Exception) {
            ui.post { say(e.message ?: "Ошибка") }
        } finally {
            scheduleTick()
        }
    }

    /** Снимает чистый кадр (без нашей стрелки), распознаёт позицию, при вашем ходе рисует стрелку. */
    private fun analyze(cap: ScreenCapture, b: RectF) {
        if (arrowVisible) {
            val latch = CountDownLatch(1)
            ui.post {
                arrow?.clear()
                arrowVisible = false
                latch.countDown()
            }
            latch.await(500, TimeUnit.MILLISECONDS)
            cap.waitFrameAfter(SystemClock.uptimeMillis() + 30, 700)
        }
        val bmp = cap.snapshot(regionOf(b)) ?: return
        val grid = recognizer.readGrid(bmp, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))
        bmp.recycle()

        val whiteBottom = when (colorMode) {
            ColorMode.WHITE -> true
            ColorMode.BLACK -> false
            ColorMode.AUTO -> recognizer.whiteIsBottom(grid)
        }
        curWhiteBottom = whiteBottom

        val firstFen = try {
            recognizer.toFen(grid, whiteBottom, true)
        } catch (e: IllegalStateException) {
            prevGrid = null
            resetBaseline(500)
            ui.post { say(e.message ?: "Позиция не распознана") }
            return
        }

        val prev = prevGrid
        val mover = if (prev != null) recognizer.moverOf(prev, grid) else null
        prevGrid = grid
        val whiteToMove = when {
            firstFen.startsWith(START_BOARD) -> true
            mover == 'w' -> false
            mover == 'b' -> true
            else -> whiteBottom          // нет данных — считаем, что ход ваш
        }

        if (whiteToMove != whiteBottom) {
            ui.post { say("Ход соперника…") }
            resetBaseline(300)
            return
        }

        val fen = recognizer.toFen(grid, whiteBottom, whiteToMove)
        val res = engine.bestMove(fen, 800)
        ui.post {
            if (!running) return@post
            if (res == null) say("Ходов нет") else showMove(res, b)
        }
        resetBaseline(700)
    }

    private fun showMove(res: StockfishManager.Result, b: RectF) {
        val mv = res.move
        val (x1, y1) = squareCenter(mv.substring(0, 2), b)
        val (x2, y2) = squareCenter(mv.substring(2, 4), b)
        arrow?.show(x1, y1, x2, y2, b.width() / 8f)
        arrowVisible = true
        say("$mv  ${res.score}")
    }

    private fun squareCenter(sq: String, b: RectF): Pair<Float, Float> {
        val f = sq[0] - 'a'
        val r = sq[1] - '1'
        val wb = curWhiteBottom
        val col = if (wb) f else 7 - f
        val row = if (wb) 7 - r else r
        return Pair(
            b.left + (col + 0.5f) * b.width() / 8f,
            b.top + (row + 0.5f) * b.height() / 8f
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        ui.removeCallbacksAndMessages(null)
        try { arrow?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        capture?.release()
        projection?.stop()
        engine.stop()
        worker.shutdownNow()
        captureThread.quitSafely()
    }
}
