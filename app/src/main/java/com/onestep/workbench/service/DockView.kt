package com.onestep.workbench.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 「一步」式侧边 Dock。
 *
 * 交互（全部在一个窄条里完成，不需要新开窗口，因此不会抢焦点）：
 *  - 单击图标：该 App 换到主位（其余按预设重排，**不重启应用**）
 *  - 长按图标：该 App 加入/移出挂机保活
 *  - 上下拖动空白处：整条 Dock 换位置
 *  - 底部三个按钮：切换布局 / 保活总开关 / 收工
 *
 * 为什么不用 PopupWindow 做长按菜单：任何可获焦的窗口都会把焦点从游戏抢走，
 * 而"点一下 Dock 游戏就暂停"是这类工具最致命的体验问题。
 */
class DockView(context: Context) : View(context) {

    interface Listener {
        fun onSlotTap(slotIndex: Int)
        fun onSlotLongPress(slotIndex: Int)
        fun onHome()
        fun onDragBy(dy: Int)
        fun onDragFinished()
    }

    data class Entry(
        val slotIndex: Int,
        val packageName: String,
        val label: String,
        val icon: Bitmap?
    )

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    // 尺寸刻意压到最小：这台机器是 375dp 宽的竖屏，早先 70dp 宽的 Dock 直接吃掉右边 1/5，
    // 真机反馈「悬浮窗会挡屏幕，令显示面积更小」。现在整条宽 48dp（= WIDTH_DP），且空闲时半透明。
    private val handleHeight = 12f * density
    private val iconSize = 40f * density
    private val iconGap = 6f * density
    private val paddingH = 4f * density
    private val buttonHeight = 24f * density
    private val buttonGap = 4f * density

    /** 空闲多久后自动变淡；一碰就恢复不透明。 */
    private val idleDelayMs = 4_000L
    private val idleAlpha = 0.35f
    private val idleRunnable = Runnable { animate().alpha(idleAlpha).setDuration(200).start() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC14141A.toInt() }
    private val primaryStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFF4C8DFF.toInt()
    }
    private val idleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x55FFFFFF
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val buttonActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC7BD88F.toInt() }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }

    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var entries: List<Entry> = emptyList()
    private var primarySlot = 0
    private var keepAliveOn = false

    private var pressedSlot: Int? = null
    private var longPressFired = false
    private var dragging = false
    private var downY = 0f
    private var lastY = 0f

    private val longPressRunnable = Runnable {
        val slot = pressedSlot ?: return@Runnable
        longPressFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onSlotLongPress(slot)
    }

    fun update(entries: List<Entry>, primarySlot: Int, keepAliveOn: Boolean) {
        this.entries = entries
        this.primarySlot = primarySlot
        this.keepAliveOn = keepAliveOn
        requestLayout()
        invalidate()
        // 每次刷新内容都重置"变淡"计时：用户刚看过，不该立刻淡下去。
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, idleDelayMs)
    }

    // ------------------------------------------------------------------ 测量

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (iconSize + paddingH * 2).toInt()
        val iconsHeight = if (entries.isEmpty()) 0f else entries.size * (iconSize + iconGap)
        val buttonsHeight = buttonHeight + buttonGap // 图标条：应用图标 + 一个「主页」小按钮
        val height = (handleHeight + iconsHeight + buttonGap * 2 + buttonsHeight + paddingH * 2).toInt()
        setMeasuredDimension(width, height)
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 18f * density, 18f * density, bgPaint)

        // 顶部拖动把手
        rect.set(w / 2 - 12f * density, handleHeight / 2 - 1.5f * density,
            w / 2 + 12f * density, handleHeight / 2 + 1.5f * density)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, handlePaint)

        entries.forEachIndexed { index, entry ->
            val top = handleHeight + paddingH + index * (iconSize + iconGap)
            rect.set(paddingH, top, paddingH + iconSize, top + iconSize)
            val bitmap = entry.icon
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, iconPaint)
            } else {
                canvas.drawRect(rect, buttonPaint)
                val label = entry.label.take(1)
                canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint)
            }
            canvas.drawRoundRect(
                rect,
                12f * density,
                12f * density,
                if (entry.slotIndex == primarySlot) primaryStroke else idleStroke
            )
        }

        // 底部三个按钮
        var buttonTop = handleHeight + paddingH + entries.size * (iconSize + iconGap) + buttonGap
        drawButton(canvas, buttonTop, "主页", false)
    }

    private fun drawButton(canvas: Canvas, top: Float, text: String, active: Boolean) {
        rect.set(paddingH / 2, top, width - paddingH / 2, top + buttonHeight)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, if (active) buttonActivePaint else buttonPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint)
    }

    // ------------------------------------------------------------------ 触摸

    private fun entryRect(index: Int): RectF {
        val top = handleHeight + paddingH + index * (iconSize + iconGap)
        return RectF(paddingH, top, paddingH + iconSize, top + iconSize)
    }

    private fun buttonRect(index: Int): RectF {
        val top = handleHeight + paddingH + entries.size * (iconSize + iconGap) +
            buttonGap + index * (buttonHeight + buttonGap)
        return RectF(paddingH / 2, top, width - paddingH / 2, top + buttonHeight)
    }

    private fun hitSlot(x: Float, y: Float): Int? {
        entries.forEachIndexed { index, _ ->
            if (entryRect(index).contains(x, y)) return index
        }
        return null
    }

    private fun hitButton(x: Float, y: Float): Int? {
        for (i in 0..0) {
            if (buttonRect(i).contains(x, y)) return i
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(idleRunnable)
                animate().alpha(1f).setDuration(120).start()
                downY = event.y
                lastY = event.y
                dragging = false
                longPressFired = false
                pressedSlot = hitSlot(event.x, event.y)
                if (pressedSlot != null) {
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    listener?.onDragBy((event.y - lastY).toInt())
                    lastY = event.y
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    listener?.onDragFinished()
                } else if (!longPressFired) {
                    val index = hitSlot(event.x, event.y)
                    if (index != null) {
                        val entry = entries.getOrNull(index)
                        if (entry != null) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            listener?.onSlotTap(entry.slotIndex)
                        }
                    } else {
                        when (hitButton(event.x, event.y)) {
                            0 -> listener?.onHome()
                        }
                    }
                }
                pressedSlot = null
                handler.postDelayed(idleRunnable, idleDelayMs)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                pressedSlot = null
                dragging = false
                handler.postDelayed(idleRunnable, idleDelayMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** 整条 Dock 的宽度（dp）= 图标 + 左右内边距，必须与 onMeasure 的算法保持一致。 */
        const val WIDTH_DP = 48f

        /** 给布局引擎用：平铺时把这段宽度从可用区里挖掉，Dock 就不会压住任何窗口。 */
        fun widthPx(context: Context): Int =
            (WIDTH_DP * context.resources.displayMetrics.density).toInt()
    }
}
