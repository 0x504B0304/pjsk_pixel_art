package dev.pjsk.beadpainter

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class MainActivity : Activity() {
    companion object {
        const val EXTRA_PICK_FOR_OVERLAY = "dev.pjsk.beadpainter.PICK_FOR_OVERLAY"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingPreview = Runnable { updatePreview() }
    private var syncingControls = false
    private var pickForOverlay = false
    private lateinit var sourcePreview: ImageView
    private lateinit var beadPreview: ImageView
    private var sourceBitmap: Bitmap? = null
    private var beadBitmap: Bitmap? = null
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var fit: Spinner
    private lateinit var dither: Spinner
    private lateinit var brightness: SeekBar
    private lateinit var contrast: SeekBar
    private lateinit var saturation: SeekBar
    private lateinit var hue: SeekBar
    private lateinit var temperature: SeekBar
    private lateinit var highlights: SeekBar
    private lateinit var shadows: SeekBar
    private lateinit var paintWhite: Switch
    private lateinit var start: Button
    private val refresh = object : Runnable {
        override fun run() {
            status.text = PaintStatus.message
            progress.text = if (PaintStatus.total > 0) "${PaintStatus.completed}/${PaintStatus.total}" else ""
            start.isEnabled = PaintWorkspace.target != null && !PaintStatus.busy && PainterService.instance != null
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }
        if (landscape) {
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            columns.addView(left, LinearLayout.LayoutParams(dp(310), ViewGroup.LayoutParams.MATCH_PARENT))
            columns.addView(ScrollView(this).apply { addView(right) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(columns)
        } else {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(left)
            content.addView(right)
            setContentView(ScrollView(this).apply { addView(content) })
        }

        left.addView(TextView(this).apply {
            text = "PJSK 拼豆绘制"
            textSize = 22f
            setTextColor(Color.rgb(26, 77, 72))
        })
        left.addView(Button(this).apply {
            text = "选择图片"
            setOnClickListener { chooseImage() }
        })
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sourcePreview = previewColumn(previews, "调色原图", landscape)
        beadPreview = previewColumn(previews, "拼豆预览", landscape)
        left.addView(previews)
        right.addView(Button(this).apply {
            text = "开启无障碍服务"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        start = Button(this).apply {
            text = "开始绘制并保存"
            isEnabled = false
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(31, 124, 101), Color.rgb(210, 216, 214)),
            )
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, Color.rgb(91, 99, 96)),
            ))
            setOnClickListener { beginPainting() }
        }
        right.addView(start)
        right.addView(Button(this).apply {
            text = "在游戏中悬浮控制"
            setOnClickListener { openFloatingControls() }
        })
        progress = TextView(this).apply { textSize = 16f }
        status = TextView(this).apply { textSize = 15f }
        right.addView(progress)
        right.addView(status)
        right.addView(TextView(this).apply { text = "画面适配" })
        fit = spinner(arrayOf("裁切铺满", "完整装入", "拉伸"), right)
        right.addView(TextView(this).apply { text = "颜色处理" })
        dither = spinner(arrayOf("插画优先", "不抖动", "照片抖动", "柔和抖动"), right)
        right.addView(TextView(this).apply { text = "原图调色" })
        brightness = slider("亮度", right)
        contrast = slider("对比度", right)
        saturation = slider("饱和度", right)
        temperature = slider("色温", right, -100, 100, 0)
        hue = slider("色相", right, -180, 180, 0)
        highlights = slider("高光", right, -100, 100, 0)
        shadows = slider("阴影", right, -100, 100, 0)
        right.addView(Button(this).apply {
            text = "重置调色"
            setOnClickListener {
                brightness.progress = 100
                contrast.progress = 100
                saturation.progress = 100
                temperature.progress = 100
                hue.progress = 180
                this@MainActivity.highlights.progress = 100
                shadows.progress = 100
                schedulePreview()
            }
        })
        paintWhite = Switch(this).apply {
            text = "白色也上色"
            setOnCheckedChangeListener { _, _ -> if (!syncingControls) schedulePreview() }
        }
        right.addView(paintWhite)

        val selectionListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!syncingControls) schedulePreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        fit.onItemSelectedListener = selectionListener
        dither.onItemSelectedListener = selectionListener
        handleOverlayPick(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOverlayPick(intent)
    }

    private fun handleOverlayPick(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_PICK_FOR_OVERLAY, false)) return
        intent.removeExtra(EXTRA_PICK_FOR_OVERLAY)
        pickForOverlay = true
        chooseImage()
    }

    override fun onResume() {
        super.onResume()
        PainterService.instance?.suspendFloatingControls()
        syncFromWorkspace()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pendingPreview)
        sourcePreview.setImageDrawable(null)
        beadPreview.setImageDrawable(null)
        sourceBitmap?.recycle()
        beadBitmap?.recycle()
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker without additional storage permissions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    PaintWorkspace.setSource(decode(uri))
                    schedulePreview()
                } catch (error: Exception) {
                    Toast.makeText(this, "图片读取失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (requestCode == 1 && pickForOverlay) {
            pickForOverlay = false
            PainterService.instance?.showFloatingControls()
            returnToGame()
        }
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 1)
    }

    private fun decode(uri: Uri): PixelImage {
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 2048))
        }
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val width = image.width
        val height = image.height
        image.recycle()
        return PixelImage(width, height, pixels)
    }

    private fun schedulePreview() {
        PaintWorkspace.invalidate()
        handler.removeCallbacks(pendingPreview)
        handler.postDelayed(pendingPreview, 150)
    }

    private fun updatePreview() {
        if (PaintWorkspace.source == null) return
        try {
            val options = ConvertOptions(
                fit = FitMode.entries[fit.selectedItemPosition.coerceIn(0, 2)],
                dither = DitherMode.entries[dither.selectedItemPosition.coerceIn(0, 3)],
                brightness = brightness.progress,
                contrast = contrast.progress,
                saturation = saturation.progress,
                hue = hue.progress - 180,
                temperature = temperature.progress - 100,
                highlights = highlights.progress - 100,
                shadows = shadows.progress - 100,
                paintWhite = paintWhite.isChecked,
            )
            PaintWorkspace.update(options)
            renderWorkspace()
        } catch (error: Exception) {
            PaintWorkspace.invalidate()
            PaintStatus.message = "转换失败：${error.message}"
        }
    }

    private fun renderWorkspace() {
        val adjusted = PaintWorkspace.adjusted ?: return
        val mapped = PaintWorkspace.target ?: return
        try {
            val nextSource = Bitmap.createBitmap(adjusted.pixels, adjusted.width, adjusted.height, Bitmap.Config.ARGB_8888)
            sourcePreview.setImageBitmap(nextSource)
            sourceBitmap?.recycle()
            sourceBitmap = nextSource
            val pixels = mapped.map { index -> if (index == BeadPalette.EMPTY) 0xfff1f1f1.toInt() else BeadPalette.colors[index].rgb }.toIntArray()
            val small = Bitmap.createBitmap(pixels, 24, 24, Bitmap.Config.ARGB_8888)
            val enlarged = Bitmap.createScaledBitmap(small, 144, 144, false)
            beadPreview.setImageBitmap(enlarged)
            beadBitmap?.recycle()
            beadBitmap = enlarged
            small.recycle()
            PaintStatus.message = "预览已更新 · ${mapped.count { it != BeadPalette.EMPTY }} 格上色"
        } catch (error: Exception) {
            PaintWorkspace.invalidate()
            PaintStatus.message = "转换失败：${error.message}"
        }
    }

    private fun syncFromWorkspace() {
        val options = PaintWorkspace.options
        syncingControls = true
        fit.setSelection(options.fit.ordinal)
        dither.setSelection(options.dither.ordinal)
        brightness.progress = options.brightness
        contrast.progress = options.contrast
        saturation.progress = options.saturation
        temperature.progress = options.temperature + 100
        hue.progress = options.hue + 180
        highlights.progress = options.highlights + 100
        shadows.progress = options.shadows + 100
        paintWhite.isChecked = options.paintWhite
        syncingControls = false
        renderWorkspace()
    }

    private fun beginPainting() {
        val matrix = PaintWorkspace.target ?: return
        if (matrix.count { it != BeadPalette.EMPTY } < 20) {
            Toast.makeText(this, "至少需要 20 格上色，游戏才允许保存", Toast.LENGTH_LONG).show()
            return
        }
        val service = PainterService.instance ?: run {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        val gameIntent = packageManager.getLaunchIntentForPackage(PainterService.GAME_PACKAGE) ?: run {
            Toast.makeText(this, "未找到 PJSK 中国版", Toast.LENGTH_LONG).show()
            return
        }
        if (!service.startRun(matrix)) return
        gameIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(gameIntent)
    }

    private fun openFloatingControls() {
        val service = PainterService.instance ?: run {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        service.showFloatingControls()
        returnToGame()
    }

    private fun returnToGame() {
        val gameIntent = packageManager.getLaunchIntentForPackage(PainterService.GAME_PACKAGE) ?: run {
            Toast.makeText(this, "未找到 PJSK 中国版", Toast.LENGTH_LONG).show()
            return
        }
        gameIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(gameIntent)
    }

    private fun spinner(items: Array<String>, parent: LinearLayout): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        parent.addView(this)
    }

    private fun previewColumn(parent: LinearLayout, label: String, landscape: Boolean): ImageView {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = label })
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xffeeeeee.toInt())
        }
        column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 160 else 190)))
        parent.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return image
    }

    private fun slider(label: String, parent: LinearLayout, minimum: Int = 0, maximum: Int = 200, neutral: Int = 100): SeekBar {
        val title = TextView(this).apply { text = "$label $neutral${if (minimum == 0) "%" else ""}" }
        parent.addView(title)
        return SeekBar(this).apply {
            max = maximum - minimum
            progress = neutral - minimum
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    title.text = "$label ${value + minimum}${if (minimum == 0) "%" else ""}"
                    if (fromUser && !syncingControls) schedulePreview()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            parent.addView(this)
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
