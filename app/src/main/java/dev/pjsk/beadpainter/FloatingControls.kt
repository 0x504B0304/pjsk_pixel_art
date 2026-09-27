package dev.pjsk.beadpainter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
        val bubble = Ui.bubbleButton(service, "拼豆").apply {
            contentDescription = "展开拼豆控制"
            setOnClickListener { showPanel() }
        }
        add(bubble, dp(56), dp(56))
        draggable(bubble) { showPanel() }
    }

    private fun showPanel() {
        hide()
        val screen = service.resources.displayMetrics
        val width = min(dp(340), screen.widthPixels - dp(24))
        val height = min(dp(590), screen.heightPixels - dp(56))
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 252, 251))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Ui.DIVIDER)
            }
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val accentDot = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.ACCENT)
            }
        }
        header.addView(accentDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        val title = TextView(service).apply {
            text = "拼豆绘制"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(8))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerButton("−", "收起悬浮控制") { showBubble() })
        header.addView(headerButton("×", "关闭悬浮控制") { service.closeFloatingControls() })
        root.addView(header)
        draggable(title)

        val previews = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        sourcePreview = previewColumn(previews, "调色原图")
        targetPreview = previewColumn(previews, "拼豆预览")
        root.addView(previews)
        status = TextView(service).apply {
            textSize = 12f
            setTextColor(Ui.TEXT_SECONDARY)
            maxLines = 2
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(status)
        val scroll = ScrollView(service)
        val controls = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Ui.addFullWidth(controls, Ui.secondaryButton(service, "选择图片").apply {
            setOnClickListener { service.pickImageFromOverlay() }
        }, service, topMargin = 2)
        Ui.addFullWidth(controls, Ui.sectionHeader(service, "画面适配"), service, topMargin = 10)
        fit = spinner(controls, arrayOf("裁切铺满", "完整装入", "拉伸"))
        Ui.addFullWidth(controls, Ui.sectionHeader(service, "颜色处理"), service, topMargin = 10)
        dither = spinner(controls, arrayOf("插画优先", "不抖动", "照片抖动", "柔和抖动"))
        Ui.addFullWidth(controls, Ui.sectionHeader(service, "原图调色"), service, topMargin = 12)
        brightness = slider(controls, "亮度", 0, 200, 100)
        contrast = slider(controls, "对比度", 0, 200, 100)
        saturation = slider(controls, "饱和度", 0, 200, 100)
        temperature = slider(controls, "色温", -100, 100, 0)
        hue = slider(controls, "色相", -180, 180, 0)
        highlights = slider(controls, "高光", -100, 100, 0)
        shadows = slider(controls, "阴影", -100, 100, 0)
        Ui.addFullWidth(controls, Ui.secondaryButton(service, "重置调色").apply {
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
        }, service, topMargin = 8)
        paintWhite = Switch(service).apply {
            text = "白色也上色"
            Ui.styleSwitch(this, service)
            setOnCheckedChangeListener { _, _ -> if (!syncing) scheduleRecalculate() }
        }
        Ui.addFullWidth(controls, paintWhite!!, service, topMargin = 4)
        start = Ui.primaryButton(service, "开始绘制并保存").apply {
            setOnClickListener { service.startFromOverlay() }
        }
        Ui.addFullWidth(root, start!!, service, topMargin = 8)
        add(root, width, height)
        syncWorkspace()
        handler.post(refresh)
    }

    private fun headerButton(symbol: String, description: String, onClick: () -> Unit): Button =
        Button(service).apply {
            text = symbol
            textSize = 16f
            isAllCaps = false
            setTextColor(Ui.TEXT_SECONDARY)
            contentDescription = description
            tooltipText = description
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(238, 244, 242))
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 23, 120, 99)), bg, null)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) }
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
        column.addView(TextView(service).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(2))
        })
        val image = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.previewBackground(service)
            clipToOutline = true
        }
        column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (parent.childCount > 0) params.marginStart = dp(6)
        parent.addView(column, params)
        return image
    }

    private fun spinner(parent: LinearLayout, items: Array<String>): Spinner = Spinner(service).apply {
        adapter = ArrayAdapter(service, android.R.layout.simple_spinner_dropdown_item, items)
        Ui.styleSpinner(this, service)
        Ui.addFullWidth(parent, this, service, topMargin = 4)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!syncing) scheduleRecalculate()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun slider(parent: LinearLayout, label: String, minimum: Int, maximum: Int, neutral: Int): SeekBar {
        val title = TextView(service).apply {
            text = "$label $neutral${if (minimum == 0) "%" else ""}"
            textSize = 12f
            setTextColor(Ui.TEXT_SECONDARY)
        }
        Ui.addFullWidth(parent, title, service, topMargin = 8)
        return SeekBar(service).apply {
            max = maximum - minimum
            progress = neutral - minimum
            Ui.styleSeekBar(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    title.text = "$label ${progress + minimum}${if (minimum == 0) "%" else ""}"
                    if (fromUser && !syncing) scheduleRecalculate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            Ui.addFullWidth(parent, this, service, topMargin = 0)
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
