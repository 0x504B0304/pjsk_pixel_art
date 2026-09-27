package dev.pjsk.beadpainter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

class FloatingControls(private val service: PainterService) {
    val isVisible: Boolean get() = activeView != null
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var activeView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var sourceBitmap: Bitmap? = null
    private var targetBitmap: Bitmap? = null
    private var sourcePreview: ImageView? = null
    private var targetPreview: ImageView? = null
    private var status: TextView? = null
    private var start: Button? = null
    private var fit: Spinner? = null
    private var dither: Spinner? = null
    private var brightness: SeekBar? = null
    private var contrast: SeekBar? = null
    private var saturation: SeekBar? = null
    private var temperature: SeekBar? = null
    private var hue: SeekBar? = null
    private var highlights: SeekBar? = null
    private var shadows: SeekBar? = null
    private var paintWhite: Switch? = null
    private var revision = -1
    private var dirty = false
    private var syncing = false
    private var x = dp(10)
    private var y = dp(32)
    private val refresh = object : Runnable {
        override fun run() {
            if (activeView == null) return
            status?.text = if (PaintStatus.busy && PaintStatus.total > 0) {
                "${PaintStatus.completed}/${PaintStatus.total} · ${PaintStatus.message}"
            } else {
                PaintStatus.message
            }
            start?.isEnabled = PaintWorkspace.target != null && !PaintStatus.busy
            if (!dirty && revision != PaintWorkspace.revision) syncWorkspace()
            handler.postDelayed(this, 300)
        }
    }
    private val recalculate = Runnable {
        dirty = false
        try {
            PaintWorkspace.update(currentOptions())
            PaintStatus.message = "预览已更新 · ${PaintWorkspace.target?.count { it != BeadPalette.EMPTY } ?: 0} 格上色"
        } catch (error: Exception) {
            PaintWorkspace.invalidate()
            PaintStatus.message = "转换失败：${error.message}"
        }
        syncWorkspace()
    }

    fun show(expanded: Boolean) {
        if (activeView != null) return
        if (expanded) showPanel() else showBubble()
    }

    fun hide() {
        if (dirty) {
            handler.removeCallbacks(recalculate)
            recalculate.run()
        }
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(recalculate)
        activeView?.let { manager.removeView(it) }
        activeView = null
        params = null
        sourcePreview = null
        targetPreview = null
        status = null
        start = null
        sourceBitmap?.recycle()
        targetBitmap?.recycle()
        sourceBitmap = null
        targetBitmap = null
        revision = -1
    }

    private fun showBubble() {
        hide()
        val bubble = Button(service).apply {
            text = "拼豆"
            contentDescription = "展开拼豆控制"
            setOnClickListener { showPanel() }
        }
        add(bubble, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        draggable(bubble) { showPanel() }
    }

    private fun showPanel() {
        hide()
        val screen = service.resources.displayMetrics
        val width = min(dp(340), screen.widthPixels - dp(24))
        val height = min(dp(590), screen.heightPixels - dp(56))
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(249, 251, 250))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.rgb(182, 202, 198))
            }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(service).apply {
            text = "拼豆绘制"
            textSize = 17f
            setTextColor(Color.rgb(35, 71, 66))
            setPadding(dp(5), dp(8), 0, dp(8))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(service).apply {
            text = "−"
            contentDescription = "收起悬浮控制"
            tooltipText = contentDescription
            setOnClickListener { showBubble() }
        }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(Button(service).apply {
            text = "×"
            contentDescription = "关闭悬浮控制"
            tooltipText = contentDescription
            setOnClickListener { service.closeFloatingControls() }
        }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header)
        draggable(title)

        val previews = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        sourcePreview = previewColumn(previews, "调色原图")
        targetPreview = previewColumn(previews, "拼豆预览")
        root.addView(previews)
        status = TextView(service).apply {
            textSize = 13f
            setTextColor(Color.rgb(66, 80, 78))
            maxLines = 2
        }
        root.addView(status)
        val scroll = ScrollView(service)
        val controls = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        controls.addView(Button(service).apply {
            text = "选择图片"
            setOnClickListener { service.pickImageFromOverlay() }
        })
        fit = spinner(controls, arrayOf("裁切铺满", "完整装入", "拉伸"))
        dither = spinner(controls, arrayOf("插画优先", "不抖动", "照片抖动", "柔和抖动"))
        brightness = slider(controls, "亮度", 0, 200, 100)
        contrast = slider(controls, "对比度", 0, 200, 100)
        saturation = slider(controls, "饱和度", 0, 200, 100)
        temperature = slider(controls, "色温", -100, 100, 0)
        hue = slider(controls, "色相", -180, 180, 0)
        highlights = slider(controls, "高光", -100, 100, 0)
        shadows = slider(controls, "阴影", -100, 100, 0)
        controls.addView(Button(service).apply {
            text = "重置调色"
            setOnClickListener {
                syncing = true
                brightness?.progress = 100
                contrast?.progress = 100
                saturation?.progress = 100
                temperature?.progress = 100
                hue?.progress = 180
                this@FloatingControls.highlights?.progress = 100
                shadows?.progress = 100
                syncing = false
                scheduleRecalculate()
            }
        })
        paintWhite = Switch(service).apply {
            text = "白色也上色"
            setOnCheckedChangeListener { _, _ -> if (!syncing) scheduleRecalculate() }
        }
        controls.addView(paintWhite)
        start = Button(service).apply {
            text = "开始绘制并保存"
            setOnClickListener { service.startFromOverlay() }
        }
        root.addView(start)
        add(root, width, height)
        syncWorkspace()
        handler.post(refresh)
    }

    private fun add(view: View, width: Int, height: Int) {
        val screen = service.resources.displayMetrics
        x = x.coerceIn(0, (screen.widthPixels - if (width > 0) width else dp(80)).coerceAtLeast(0))
        y = y.coerceIn(0, (screen.heightPixels - if (height > 0) height else dp(50)).coerceAtLeast(0))
        val next = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = this@FloatingControls.x
            this.y = this@FloatingControls.y
        }
        manager.addView(view, next)
        activeView = view
        params = next
    }

    private fun draggable(view: View, onTap: (() -> Unit)? = null) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = x
                    initialY = y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) {
                        val layout = params ?: return@setOnTouchListener true
                        val screen = service.resources.displayMetrics
                        x = (initialX + dx).coerceIn(0, (screen.widthPixels - activeView!!.width).coerceAtLeast(0))
                        y = (initialY + dy).coerceIn(0, (screen.heightPixels - activeView!!.height).coerceAtLeast(0))
                        layout.x = x
                        layout.y = y
                        manager.updateViewLayout(activeView!!, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> true
            }
        }
    }

    private fun previewColumn(parent: LinearLayout, label: String): ImageView {
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(service).apply { text = label; textSize = 12f })
        val image = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(237, 240, 239))
        }
        column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        parent.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return image
    }

    private fun spinner(parent: LinearLayout, items: Array<String>): Spinner = Spinner(service).apply {
        adapter = ArrayAdapter(service, android.R.layout.simple_spinner_dropdown_item, items)
        parent.addView(this)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!syncing) scheduleRecalculate()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun slider(parent: LinearLayout, label: String, minimum: Int, maximum: Int, neutral: Int): SeekBar {
        val title = TextView(service).apply { text = "$label $neutral${if (minimum == 0) "%" else ""}"; textSize = 13f }
        parent.addView(title)
        return SeekBar(service).apply {
            max = maximum - minimum
            progress = neutral - minimum
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    title.text = "$label ${progress + minimum}${if (minimum == 0) "%" else ""}"
                    if (fromUser && !syncing) scheduleRecalculate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            parent.addView(this)
        }
    }

    private fun scheduleRecalculate() {
        if (PaintWorkspace.source == null) return
        dirty = true
        PaintWorkspace.invalidate()
        handler.removeCallbacks(recalculate)
        handler.postDelayed(recalculate, 160)
    }

    private fun currentOptions(): ConvertOptions = ConvertOptions(
        fit = FitMode.entries[fit!!.selectedItemPosition.coerceIn(0, 2)],
        dither = DitherMode.entries[dither!!.selectedItemPosition.coerceIn(0, 3)],
        brightness = brightness!!.progress,
        contrast = contrast!!.progress,
        saturation = saturation!!.progress,
        temperature = temperature!!.progress - 100,
        hue = hue!!.progress - 180,
        highlights = highlights!!.progress - 100,
        shadows = shadows!!.progress - 100,
        paintWhite = paintWhite!!.isChecked,
    )

    private fun syncWorkspace() {
        if (activeView == null || sourcePreview == null) return
        syncing = true
        val options = PaintWorkspace.options
        fit?.setSelection(options.fit.ordinal)
        dither?.setSelection(options.dither.ordinal)
        brightness?.progress = options.brightness
        contrast?.progress = options.contrast
        saturation?.progress = options.saturation
        temperature?.progress = options.temperature + 100
        hue?.progress = options.hue + 180
        highlights?.progress = options.highlights + 100
        shadows?.progress = options.shadows + 100
        paintWhite?.isChecked = options.paintWhite
        syncing = false
        PaintWorkspace.adjusted?.let { image ->
            val next = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            sourcePreview?.setImageBitmap(next)
            sourceBitmap?.recycle()
            sourceBitmap = next
        } ?: run {
            sourcePreview?.setImageDrawable(null)
            sourceBitmap?.recycle()
            sourceBitmap = null
        }
        PaintWorkspace.target?.let { cells ->
            val pixels = cells.map { index -> if (index == BeadPalette.EMPTY) 0xfff1f1f1.toInt() else BeadPalette.colors[index].rgb }.toIntArray()
            val small = Bitmap.createBitmap(pixels, 24, 24, Bitmap.Config.ARGB_8888)
            val next = Bitmap.createScaledBitmap(small, 96, 96, false)
            small.recycle()
            targetPreview?.setImageBitmap(next)
            targetBitmap?.recycle()
            targetBitmap = next
        } ?: run {
            targetPreview?.setImageDrawable(null)
            targetBitmap?.recycle()
            targetBitmap = null
        }
        revision = PaintWorkspace.revision
    }

    private fun dp(value: Int): Int = (service.resources.displayMetrics.density * value).toInt()
}
