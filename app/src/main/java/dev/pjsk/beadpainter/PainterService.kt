package dev.pjsk.beadpainter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PaintStatus {
    @Volatile var message: String = "等待选择图片"
    @Volatile var completed: Int = 0
    @Volatile var total: Int = 0
    @Volatile var busy: Boolean = false
}

class PainterService : AccessibilityService() {
    companion object {
        const val GAME_PACKAGE = "com.hermes.mk.bilibili"
        @Volatile var instance: PainterService? = null
            private set
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: Button? = null
    private var floatingControls: FloatingControls? = null
    private var floatingRequested = false
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var saveNoticeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        PaintStatus.message = "无障碍服务已就绪"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        if (packageName == GAME_PACKAGE) {
            foregroundPackage = GAME_PACKAGE
            if (floatingRequested && !running.get()) controls().show(true)
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != this.packageName &&
            rootInActiveWindow?.packageName?.toString() == packageName
        ) {
            foregroundPackage = packageName
            floatingControls?.hide()
        }
        if (event?.packageName?.toString() == GAME_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.text.any { it.toString().contains("保存成功") }
        ) {
            saveNoticeAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onInterrupt() {
        stopRequested.set(true)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        instance = null
        mainHandler.post {
            removeStopButton()
            floatingControls?.hide()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    fun startRun(target: IntArray): Boolean {
        if (target.size != ImageConverter.CELL_COUNT || target.any { it !in BeadPalette.EMPTY until BeadPalette.colors.size }) return false
        if (target.count { it != BeadPalette.EMPTY } < 20) {
            PaintStatus.message = "至少需要 20 格上色，游戏才允许保存"
            return false
        }
        if (!running.compareAndSet(false, true)) return false
        stopRequested.set(false)
        PaintStatus.busy = true
        PaintStatus.completed = 0
        PaintStatus.total = target.size
        PaintStatus.message = "等待游戏拼豆页面"
        mainHandler.post {
            floatingControls?.hide()
            showStopButton()
        }
        executor.execute {
            try {
                paint(target.copyOf())
            } catch (error: Exception) {
                Log.e("BeadPainter", "Painting stopped", error)
                PaintStatus.message = "已停止：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running.set(false)
                PaintStatus.busy = false
                mainHandler.post {
                    removeStopButton()
                    if (floatingRequested && isGameForeground()) controls().show(false)
                }
            }
        }
        return true
    }

    fun requestStop() {
        stopRequested.set(true)
        PaintStatus.message = "正在停止"
    }

    fun showFloatingControls() {
        mainHandler.post {
            floatingRequested = true
            if (!running.get() && isGameForeground()) controls().show(true)
            mainHandler.postDelayed({
                if (floatingRequested && !running.get() && isGameForeground() && floatingControls?.let { it.isVisible } != true) {
                    controls().show(true)
                }
            }, 1_000)
        }
    }

    fun closeFloatingControls() {
        floatingRequested = false
        floatingControls?.hide()
    }

    fun suspendFloatingControls() {
        floatingControls?.hide()
    }

    fun pickImageFromOverlay() {
        floatingControls?.hide()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_PICK_FOR_OVERLAY, true)
        })
    }

    fun startFromOverlay() {
        val target = PaintWorkspace.target ?: run {
            PaintStatus.message = "请先选择图片并等待预览更新"
            return
        }
        startRun(target)
    }

    private fun controls(): FloatingControls = floatingControls ?: FloatingControls(this).also { floatingControls = it }

    private fun paint(target: IntArray) {
        if (!org.opencv.android.OpenCVLoader.initLocal()) error("OpenCV 初始化失败")
        val vision = GameVision(this)
        var firstCapture: Bitmap? = null
        var layout: GameLayout? = null
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested.get()) {
            val captured = capture() ?: error("无法截取游戏画面")
            val found = vision.locate(captured)
            if (found != null && isGameForeground()) {
                firstCapture = captured
                layout = found
                break
            }
            captured.recycle()
            SystemClock.sleep(800)
        }
        if (stopRequested.get()) error("用户已停止")
        val initial = firstCapture ?: error("30 秒内未识别到 PJSK 6.4.0 拼豆页面")
        var activeLayout = layout ?: error("网格定位失败")
        val expectedWidth = initial.width
        val expectedHeight = initial.height
        val initialCells = vision.readCells(initial, activeLayout) ?: error("无法读取当前画布")
        storeScreenshot(initial, "before")
        initial.recycle()

        val pending = target.indices.filter { target[it] != initialCells[it] }
        PaintStatus.total = pending.size
        PaintStatus.message = "开始绘制 ${pending.size} 格"
        var attempted = 0
        val groups = pending.groupBy { target[it] }.toSortedMap()
        for ((color, positions) in groups) {
            ensureRunning()
            activeLayout = checkedLayout(vision, expectedWidth, expectedHeight)
            val tool = if (color == BeadPalette.EMPTY) activeLayout.eraser else activeLayout.palette[color]
            if (!tap(tool.x, tool.y)) error("无法选择 ${if (color == BeadPalette.EMPTY) "橡皮擦" else BeadPalette.colors[color].code}")
            SystemClock.sleep(200)
            for (position in positions) {
                ensureRunning()
                if (attempted > 0 && attempted % 20 == 0) activeLayout = checkedLayout(vision, expectedWidth, expectedHeight)
                val center = activeLayout.grid.cell(position % 24, position / 24)
                if (!tap(center.x, center.y)) error("点击失败：第 ${position / 24 + 1} 行 ${position % 24 + 1} 列")
                attempted++
                PaintStatus.completed = attempted
                PaintStatus.message = "绘制 $attempted/${pending.size} · 第 ${position / 24 + 1} 行 ${position % 24 + 1} 列"
            }
        }

        var remaining = verify(vision, target, expectedWidth, expectedHeight)
        for (retry in 1..2) {
            if (remaining.isEmpty()) break
            PaintStatus.message = "复核发现 ${remaining.size} 格不一致，重试 $retry/2"
            for ((color, positions) in remaining.groupBy { target[it] }.toSortedMap()) {
                ensureRunning()
                activeLayout = checkedLayout(vision, expectedWidth, expectedHeight)
                val tool = if (color == BeadPalette.EMPTY) activeLayout.eraser else activeLayout.palette[color]
                if (!tap(tool.x, tool.y)) error("重试选色失败")
                SystemClock.sleep(200)
                for (position in positions) {
                    ensureRunning()
                    val center = activeLayout.grid.cell(position % 24, position / 24)
                    if (!tap(center.x, center.y)) error("重试点击失败：第 ${position / 24 + 1} 行 ${position % 24 + 1} 列")
                }
            }
            remaining = verify(vision, target, expectedWidth, expectedHeight)
        }
        if (remaining.isNotEmpty()) {
            val first = remaining.first()
            error("${remaining.size} 格未通过复核，未保存；首个位置：第 ${first / 24 + 1} 行 ${first % 24 + 1} 列")
        }

        ensureRunning()
        val completedCapture = capture() ?: error("完成截图失败，未保存")
        if (completedCapture.width != expectedWidth || completedCapture.height != expectedHeight) error("屏幕尺寸已变化，未保存")
        activeLayout = vision.locate(completedCapture) ?: error("保存前页面位置改变，未保存")
        storeScreenshot(completedCapture, "painted")
        completedCapture.recycle()
        PaintStatus.message = "576 格复核通过，正在保存"
        SystemClock.sleep(1_100)
        ensureRunning()
        saveNoticeAt = 0L
        repeat(2) { attempt ->
            ensureRunning()
            val started = SystemClock.elapsedRealtime()
            if (!tap(activeLayout.saveIcon.x, activeLayout.saveIcon.y, 120)) error("保存按钮点击失败")
            SystemClock.sleep(700)
            val savedCapture = capture() ?: error("保存后截图失败，保存状态未确认")
            val confirmed = vision.hasSaveSuccess(savedCapture, activeLayout) || saveNoticeAt >= started
            if (confirmed || attempt == 1) storeScreenshot(savedCapture, if (confirmed) "saved" else "save-unconfirmed")
            savedCapture.recycle()
            if (confirmed) {
                PaintStatus.message = "保存成功，未提交审核"
                return
            }
            SystemClock.sleep(1_100)
        }
        error("已点击保存完成，但未看到保存成功提示；请手动核实")
    }

    private fun verify(vision: GameVision, target: IntArray, expectedWidth: Int, expectedHeight: Int): List<Int> {
        ensureRunning()
        val bitmap = capture() ?: error("复核截图失败，未保存")
        if (bitmap.width != expectedWidth || bitmap.height != expectedHeight) error("复核时屏幕尺寸已变化，未保存")
        val layout = vision.locate(bitmap) ?: error("复核时编辑页位置改变，未保存")
        val actual = vision.readCells(bitmap, layout) ?: error("复核时画布不可读，未保存")
        bitmap.recycle()
        return target.indices.filter { target[it] != actual[it] }
    }

    private fun checkedLayout(vision: GameVision, expectedWidth: Int, expectedHeight: Int): GameLayout {
        repeat(3) { attempt ->
            ensureRunning()
            val bitmap = capture() ?: error("游戏截屏失败")
            if (bitmap.width != expectedWidth || bitmap.height != expectedHeight) error("屏幕尺寸已变化，已停止")
            val layout = vision.locate(bitmap)
            bitmap.recycle()
            if (layout != null) return layout
            if (attempt < 2) SystemClock.sleep(450)
        }
        error("拼豆页面或屏幕布局已改变，已停止")
    }

    private fun ensureRunning() {
        if (stopRequested.get()) error("用户已停止")
        if (!isGameForeground()) error("游戏已不在前台")
    }

    private fun isGameForeground(): Boolean =
        (rootInActiveWindow?.packageName?.toString() ?: foregroundPackage) == GAME_PACKAGE

    private fun capture(): Bitmap? {
        repeat(3) { attempt ->
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var failureCode = -1
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failureCode = errorCode
                    latch.countDown()
                }
            })
            if (!latch.await(7, TimeUnit.SECONDS)) return null
            if (bitmap != null) return bitmap
            Log.w("BeadPainter", "Screenshot failed with code $failureCode")
            if (failureCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || attempt == 2) return null
            SystemClock.sleep(700)
        }
        return null
    }

    private fun tap(x: Float, y: Float): Boolean = tap(x, y, 55)

    private fun tap(x: Float, y: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val latch = CountDownLatch(1)
        var succeeded = false
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                succeeded = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, mainHandler)
        val completed = accepted && latch.await(5, TimeUnit.SECONDS) && succeeded
        if (completed) SystemClock.sleep(90)
        return completed
    }

    private fun storeScreenshot(bitmap: Bitmap, stage: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pjsk-beads-${System.currentTimeMillis()}-$stage.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PJSKBeadPainter")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法保存 $stage 截图")
        contentResolver.openOutputStream(uri)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            ?: error("无法写入 $stage 截图")
    }

    private fun showStopButton() {
        if (overlay != null) return
        val button = Button(this).apply {
            text = "停止"
            setOnClickListener { requestStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 8
        }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(button, params)
        overlay = button
    }

    private fun removeStopButton() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
    }
}
