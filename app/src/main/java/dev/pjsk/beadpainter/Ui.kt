package dev.pjsk.beadpainter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.StateSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * 统一的界面样式：配色、圆角卡片、按钮、滑杆、开关。
 * 主界面与悬浮控制面板共用，保证视觉一致。
 */
object Ui {
    val ACCENT = Color.rgb(38, 166, 137)
    val ACCENT_DARK = Color.rgb(23, 120, 99)
    val ACCENT_SOFT = Color.rgb(214, 240, 233)
    val PAGE_BG = Color.rgb(240, 246, 244)
    val CARD_BG = Color.WHITE
    val TEXT_PRIMARY = Color.rgb(30, 55, 51)
    val TEXT_SECONDARY = Color.rgb(104, 122, 118)
    val DIVIDER = Color.rgb(226, 235, 232)
    val PREVIEW_BG = Color.rgb(232, 240, 238)
    val DISABLED_BG = Color.rgb(213, 222, 219)
    val DISABLED_TEXT = Color.rgb(140, 152, 148)

    fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    /** 页面背景色，直接设置到根视图。 */
    fun pageBackground(): GradientDrawable = GradientDrawable().apply { setColor(PAGE_BG) }

    /** 白色圆角卡片背景。 */
    fun card(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD_BG)
        cornerRadius = dp(context, 16).toFloat()
    }

    /** 预览图的内凹圆角底色。 */
    fun previewBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(PREVIEW_BG)
        cornerRadius = dp(context, 10).toFloat()
    }

    /** 主要按钮：圆角实心强调色，带按压涟漪与禁用态。 */
    fun primaryButton(context: Context, label: String): Button = Button(context).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.WHITE, DISABLED_TEXT),
        ))
        val enabledBg = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = dp(context, 12).toFloat()
        }
        val disabledBg = GradientDrawable().apply {
            setColor(DISABLED_BG)
            cornerRadius = dp(context, 12).toFloat()
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled), enabledBg)
            addState(StateSet.WILD_CARD, disabledBg)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), states, null)
        stateListAnimator = null
    }

    /** 次要按钮：圆角描边浅色底。 */
    fun secondaryButton(context: Context, label: String): Button = Button(context).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(ACCENT_DARK)
        val bg = GradientDrawable().apply {
            setColor(ACCENT_SOFT)
            cornerRadius = dp(context, 12).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 23, 120, 99)), bg, null)
        stateListAnimator = null
    }

    /** 圆形悬浮小球。 */
    fun bubbleButton(context: Context, label: String): Button = Button(context).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        val size = dp(context, 56)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), bg, null)
        stateListAnimator = null
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        layoutParams = ViewGroup.LayoutParams(size, size)
        elevation = dp(context, 6).toFloat()
    }

    /** 区块小标题。 */
    fun sectionHeader(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(ACCENT_DARK)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
    }

    /** 滑杆配色。 */
    fun styleSeekBar(seekBar: SeekBar) {
        seekBar.progressTintList = ColorStateList.valueOf(ACCENT)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(DIVIDER)
        seekBar.thumbTintList = ColorStateList.valueOf(ACCENT_DARK)
    }

    /** 开关配色。 */
    fun styleSwitch(toggle: Switch, context: Context) {
        toggle.textSize = 14f
        toggle.setTextColor(TEXT_PRIMARY)
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ACCENT, Color.rgb(158, 170, 166)),
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ACCENT_SOFT, Color.rgb(216, 224, 221)),
        )
        toggle.setPadding(0, dp(context, 4), 0, dp(context, 4))
    }

    /** 下拉框圆角浅色背景。 */
    fun styleSpinner(spinner: Spinner, context: Context) {
        spinner.background = GradientDrawable().apply {
            setColor(Color.rgb(244, 249, 247))
            cornerRadius = dp(context, 10).toFloat()
            setStroke(dp(context, 1), DIVIDER)
        }
        spinner.setPadding(dp(context, 12), 0, dp(context, 4), 0)
    }

    /** 添加一条垂直间距。 */
    fun spacer(parent: LinearLayout, context: Context, height: Int) {
        parent.addView(android.view.View(context), LinearLayout.LayoutParams(1, dp(context, height)))
    }

    /** 添加一个占满宽度的子视图，并带统一的左右间距。 */
    fun addFullWidth(parent: LinearLayout, view: android.view.View, context: Context, topMargin: Int = 8, bottomMargin: Int = 0) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(context, topMargin)
        lp.bottomMargin = dp(context, bottomMargin)
        parent.addView(view, lp)
    }

    /** 分隔线。 */
    fun divider(context: Context): android.view.View = android.view.View(context).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
    }

    /** 标题行容器。 */
    fun titleRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
}
