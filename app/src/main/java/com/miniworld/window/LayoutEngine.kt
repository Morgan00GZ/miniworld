package com.miniworld.window

import com.miniworld.model.Bounds
import com.miniworld.model.LayoutPreset

/**
 * 布局引擎：把"屏幕可用区 + 槽位数"算成每个槽位的矩形。
 *
 * 这里刻意保持**纯函数、零 Android 依赖**，因为它是最容易算错、也最值得被测试的部分。
 * 不变量（由 LayoutEngineTest 逐条断言）：
 *  1. 返回数量恒等于 slotCount
 *  2. 所有矩形有效且落在可用区内
 *  3. 主位（index 0）面积不小于任何其它槽位
 */
object LayoutEngine {

    /** 可用区：从整屏里挖掉状态栏/挖孔/手势条之后剩下的部分。 */
    fun availableArea(
        width: Int,
        height: Int,
        topInset: Int = 0,
        bottomInset: Int = 0,
        sideInset: Int = 0,
        rightInset: Int = 0
    ): Bounds = Bounds(
        left = sideInset,
        top = topInset,
        // rightInset 只挖右边：侧边 Dock 只占一侧，左右都挖会白白浪费本就紧张的宽度。
        right = (width - sideInset - rightInset).coerceAtLeast(sideInset),
        bottom = (height - bottomInset).coerceAtLeast(topInset)
    )

    fun layout(available: Bounds, preset: LayoutPreset, slotCount: Int): List<Bounds> {
        val n = slotCount.coerceAtLeast(1)
        if (!available.isValid) return List(n) { available }
        return when (preset) {
            LayoutPreset.SOLO_KEEPALIVE -> soloKeepAlive(available, n)
        }
    }

    /**
     * 「一个全屏 + 其余隐形保活」：
     *  - 槽位 0 = 整个可用区（用户看到的那一个全屏窗口）
     *  - 槽位 1..n-1 = 右下角一个 2×2 像素的小点
     *
     * 为什么留小点而不是把 App 关掉：Android 的 multi-resume 是"**窗口可见**就 RESUMED"。
     * 缩到 2×2 像素仍然算"在屏幕上"，进程继续跑、画面继续渲染，而占用的视觉空间是 0；
     * 点 Dock 图标把某个 App 换到槽位 0，它就变成那个全屏的。
     * 用户明确要求过：「小窗真的不能不出现吗……保活在后台，用按钮切换回前台就行」。
     *
     * 注意：小点是**故意压在槽位 0 之上**的（所以两者"重叠"是设计，不是缺陷）。
     */
    private fun soloKeepAlive(a: Bounds, n: Int): List<Bounds> {
        val dot = 2
        return List(n) { index ->
            if (index == 0) a
            else Bounds(
                left = (a.right - dot).coerceAtLeast(a.left),
                top = (a.bottom - dot).coerceAtLeast(a.top),
                right = a.right,
                bottom = a.bottom
            )
        }
    }
}
