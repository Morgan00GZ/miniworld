package com.miniworld.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.miniworld.model.Bounds
import com.miniworld.window.LayoutEngine

/**
 * 屏幕尺寸与系统栏内缩。
 *
 * 为什么不用 WindowManager.getCurrentWindowMetrics()：
 * 服务在后台时没有可用的可视上下文，拿它可能直接抛异常。
 * DisplayManager + 系统资源里的 status_bar_height / navigation_bar_height
 * 是服务里最稳的一种取法（不依赖任何窗口是否可见）。
 */
object ScreenMetrics {

    data class Screen(
        val width: Int,
        val height: Int,
        val topInset: Int,
        val bottomInset: Int
    ) {
        /**
         * @param rightInset 右侧要留出的宽度（侧边 Dock 用）。平铺时把它留给 Dock，
         *                   窗口就不会被 Dock 压住——真机反馈过"Dock 挡屏幕、显示面积更小"。
         */
        fun availableBounds(rightInset: Int = 0): Bounds = LayoutEngine.availableArea(
            width = width,
            height = height,
            topInset = topInset,
            bottomInset = bottomInset,
            sideInset = 0,
            rightInset = rightInset
        )
    }

    @Suppress("DEPRECATION")
    fun read(context: Context): Screen {
        val metrics = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            val wm = context.getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
        }

        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
        val density = if (metrics.density > 0f) metrics.density else 3f

        return Screen(
            width = width,
            height = height,
            topInset = systemDimension(context, "status_bar_height", (26 * density).toInt()),
            bottomInset = systemDimension(context, "navigation_bar_height", (16 * density).toInt())
        )
    }

    private fun systemDimension(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id <= 0) return fallback
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(fallback)
    }
}
