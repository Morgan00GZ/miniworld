package com.onestep.workbench.window

import com.onestep.workbench.model.Bounds
import com.onestep.workbench.model.LayoutPreset

/**
 * 布局引擎：把"屏幕可用区 + 预设 + 槽位数"算成每个槽位的矩形。
 *
 * 这里刻意保持**纯函数、零 Android 依赖**，因为它是最容易算错、也最值得被测试的部分。
 * 不变量（由 LayoutEngineTest 逐条断言）：
 *  1. 返回数量恒等于 slotCount
 *  2. 所有矩形有效且落在可用区内
 *  3. 两两不重叠（可以贴边，但不交叠）
 *  4. 主位（index 0）面积不小于任何其它槽位
 */
object LayoutEngine {

    /** 窗口之间的留白，避免窗口边缘的握把互相压住。 */
    const val GAP = 8

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
            LayoutPreset.STACK_2 -> stackPreset(available, n)
            LayoutPreset.SIDE_2 -> sidePreset(available, n)
            LayoutPreset.MAIN_SIDE -> mainSide(available, n)
            LayoutPreset.ROW_EQUAL -> splitVertical(available, List(n) { 1f })
            LayoutPreset.SOLO_KEEPALIVE -> soloKeepAlive(available, n)
            LayoutPreset.GRID_4 -> grid(available, n)
            LayoutPreset.MAIN_3MINI -> mainThreeMini(available, n)
        }
    }

    // ------------------------------------------------------------------ 预设

    private fun stackPreset(a: Bounds, n: Int): List<Bounds> = when (n) {
        1 -> listOf(a)
        2 -> splitVertical(a, listOf(0.5f, 0.5f))
        else -> {
            val rows = splitVertical(a, listOf(0.6f, 0.4f))
            listOf(rows[0]) + splitHorizontal(rows[1], List(n - 1) { 1f })
        }
    }

    private fun sidePreset(a: Bounds, n: Int): List<Bounds> = when (n) {
        1 -> listOf(a)
        2 -> splitHorizontal(a, listOf(0.5f, 0.5f))
        else -> {
            val cols = splitHorizontal(a, listOf(0.6f, 0.4f))
            listOf(cols[0]) + splitVertical(cols[1], List(n - 1) { 1f })
        }
    }

    private fun mainSide(a: Bounds, n: Int): List<Bounds> = when (n) {
        1 -> listOf(a)
        else -> {
            // 主位占 68% 高：窄屏手机上"主位够大"比"每格平均"更重要
            // （真机反馈：上下各半时画面太小、看着变形）。
            val rows = splitVertical(a, listOf(0.68f, 0.32f))
            listOf(rows[0]) + splitHorizontal(rows[1], List(n - 1) { 1f })
        }
    }

    private fun mainThreeMini(a: Bounds, n: Int): List<Bounds> = when (n) {
        1 -> listOf(a)
        else -> {
            val cols = splitHorizontal(a, listOf(0.66f, 0.34f))
            listOf(cols[0]) + splitVertical(cols[1], List(n - 1) { 1f })
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

    private fun grid(a: Bounds, n: Int): List<Bounds> = when {
        1 == n -> listOf(a)

        2 == n -> splitVertical(a, listOf(0.5f, 0.5f))

        3 == n -> {
            val rows = splitVertical(a, listOf(0.5f, 0.5f))
            listOf(rows[0]) + splitHorizontal(rows[1], listOf(0.5f, 0.5f))
        }

        4 == n -> {
            val rows = splitVertical(a, listOf(0.5f, 0.5f))
            splitHorizontal(rows[0], listOf(0.5f, 0.5f)) +
                splitHorizontal(rows[1], listOf(0.5f, 0.5f))
        }

        else -> {
            // 主位吃掉上方 40%，剩下的在下方按两列铺开
            val rows = splitVertical(a, listOf(0.4f, 0.6f))
            val rest = n - 1
            val rowCount = (rest + 1) / 2
            val rowStrips = splitVertical(rows[1], List(rowCount) { 1f })
            val out = ArrayList<Bounds>(n)
            out += rows[0]
            var placed = 0
            for (r in 0 until rowCount) {
                val remaining = rest - placed
                if (remaining <= 0) break
                if (remaining == 1) {
                    out += rowStrips[r]
                    placed += 1
                } else {
                    out += splitHorizontal(rowStrips[r], listOf(0.5f, 0.5f))
                    placed += 2
                }
            }
            out
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 把区域按比例切成上下若干条，条与条之间留 GAP。 */
    internal fun splitVertical(area: Bounds, fractions: List<Float>): List<Bounds> {
        val n = fractions.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(area)

        val total = area.height - GAP * (n - 1)
        if (total <= 0) {
            return List(n) { Bounds(area.left, area.top, area.right, area.top) }
        }

        // fractions 是**相对权重**（[1f,1f] 与 [0.5f,0.5f] 等价），必须先按总和归一化：
        // 直接相乘会把 [1f,1f] 算成"第一个占满、其余为 0"，从而产生零宽/零高窗口。
        val sum = fractions.sum().takeIf { it > 0f } ?: n.toFloat()
        val sizes = IntArray(n)
        var assigned = 0
        for (i in 1 until n) {
            sizes[i] = (total * (fractions[i] / sum)).toInt().coerceAtLeast(0)
            assigned += sizes[i]
        }
        // 取整余量一律给主位（index 0）：这样"主位不小于其它槽位"恒成立，
        // 否则向下取整会让最后一个窗口比主位大 1px。
        sizes[0] = (total - assigned).coerceAtLeast(0)

        val out = ArrayList<Bounds>(n)
        var y = area.top
        for (i in 0 until n) {
            val bottom = (y + sizes[i]).coerceAtMost(area.bottom)
            out += Bounds(area.left, y, area.right, bottom)
            y = bottom + GAP
        }
        return out
    }

    /** 把区域按比例切成左右若干列，列与列之间留 GAP。 */
    internal fun splitHorizontal(area: Bounds, fractions: List<Float>): List<Bounds> {
        val n = fractions.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(area)

        val total = area.width - GAP * (n - 1)
        if (total <= 0) {
            return List(n) { Bounds(area.left, area.top, area.left, area.bottom) }
        }

        val sum = fractions.sum().takeIf { it > 0f } ?: n.toFloat()
        val sizes = IntArray(n)
        var assigned = 0
        for (i in 1 until n) {
            sizes[i] = (total * (fractions[i] / sum)).toInt().coerceAtLeast(0)
            assigned += sizes[i]
        }
        sizes[0] = (total - assigned).coerceAtLeast(0)

        val out = ArrayList<Bounds>(n)
        var x = area.left
        for (i in 0 until n) {
            val right = (x + sizes[i]).coerceAtMost(area.right)
            out += Bounds(x, area.top, right, area.bottom)
            x = right + GAP
        }
        return out
    }
}
