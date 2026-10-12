package com.onestep.workbench.window

import com.onestep.workbench.model.Bounds
import com.onestep.workbench.model.LayoutPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局引擎不变量测试。
 *
 * 这些断言是"窗口不会叠在一起、不会跑到屏幕外"的唯一保证。
 * 纯 JVM 测试，`gradlew :app:testDebugUnitTest` 即可跑，不需要真机。
 */
class LayoutEngineTest {

    private val area = LayoutEngine.availableArea(
        width = 1080,
        height = 2400,
        topInset = 120,
        bottomInset = 72,
        sideInset = 0
    )

    private val presets = LayoutPreset.entries.toList()

    @Test
    fun `slot count always matches request`() {
        for (preset in presets) {
            for (n in 1..7) {
                val result = LayoutEngine.layout(area, preset, n)
                assertEquals("preset=$preset n=$n", n, result.size)
            }
        }
    }

    @Test
    fun `every window is valid and inside available area`() {
        for (preset in presets) {
            for (n in 1..7) {
                LayoutEngine.layout(area, preset, n).forEachIndexed { index, b ->
                    assertTrue("preset=$preset n=$n index=$index bounds=$b 无效", b.isValid)
                    assertTrue(
                        "preset=$preset n=$n index=$index bounds=$b 越界（可用区=$area）",
                        b.left >= area.left && b.top >= area.top &&
                            b.right <= area.right && b.bottom <= area.bottom
                    )
                }
            }
        }
    }

    @Test
    fun `windows never overlap each other`() {
        for (preset in presets) {
            for (n in 1..7) {
                val result = LayoutEngine.layout(area, preset, n)
                for (i in result.indices) {
                    for (j in i + 1 until result.size) {
                        assertTrue(
                            "preset=$preset n=$n 第 $i 与第 $j 个窗口重叠：${result[i]} / ${result[j]}",
                            !result[i].overlaps(result[j])
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `primary window is never smaller than the others`() {
        for (preset in presets) {
            for (n in 1..7) {
                val result = LayoutEngine.layout(area, preset, n)
                val primary = result[0]
                result.drop(1).forEachIndexed { i, other ->
                    assertTrue(
                        "preset=$preset n=$n 主位(${primary.area}) 小于第 ${i + 1} 个(${other.area})",
                        primary.area >= other.area
                    )
                }
            }
        }
    }

    @Test
    fun `stack two splits the area into two halves`() {
        val result = LayoutEngine.layout(area, LayoutPreset.STACK_2, 2)
        assertEquals(area.left, result[0].left)
        assertEquals(area.right, result[0].right)
        assertEquals(area.top, result[0].top)
        assertEquals(area.bottom, result[1].bottom)
        // 两半高度接近（差值只应来自 GAP 的取整）
        assertTrue(kotlin.math.abs(result[0].height - result[1].height) <= 2 * LayoutEngine.GAP)
    }

    @Test
    fun `main three mini gives one big plus three strips`() {
        val result = LayoutEngine.layout(area, LayoutPreset.MAIN_3MINI, 4)
        assertEquals(4, result.size)
        // 主位在左半区，其余在右列且逐条更靠下
        assertTrue(result[0].right <= result[1].left)
        assertTrue(result[1].bottom <= result[2].top)
        assertTrue(result[2].bottom <= result[3].top)
    }

    @Test
    fun `invalid available area degrades to full screen repeats`() {
        val broken = Bounds(0, 0, 0, 0)
        val result = LayoutEngine.layout(broken, LayoutPreset.GRID_4, 3)
        assertEquals(3, result.size)
    }

    /**
     * 回归测试：等份切分的权重 [1f,1f] 若是直接相乘，会被算成"第一个占满、第二个为 0"，
     * 于是出现零宽窗口。权重必须按总和归一化。
     * 这个缺陷最早是被 tools/verify-layout.py 的交叉校验抓到的。
     */
    @Test
    fun `equal share weights are normalized instead of collapsing to zero`() {
        val result = LayoutEngine.layout(area, LayoutPreset.STACK_2, 3)
        assertEquals(3, result.size)
        result.forEach { assertTrue("出现零面积窗口：$it", it.isValid) }

        val bottomColumns = result.drop(1)
        assertTrue(
            "等份切分应该接近等宽，实际 ${bottomColumns.map { it.width }}",
            kotlin.math.abs(bottomColumns[0].width - bottomColumns[1].width) <= 2
        )
        assertTrue("底部两列应该占满宽度", bottomColumns.sumOf { it.width } > area.width / 2)
    }
}
