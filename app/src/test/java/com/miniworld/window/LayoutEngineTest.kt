package com.miniworld.window

import com.miniworld.model.Bounds
import com.miniworld.model.LayoutPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局引擎不变量测试。
 *
 * 这些断言是"保活小点不会跑到屏幕外、主窗一定是最大的那个"的唯一保证。
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

    private val preset = LayoutPreset.SOLO_KEEPALIVE

    @Test
    fun `slot count always matches request`() {
        for (n in 1..7) {
            val result = LayoutEngine.layout(area, preset, n)
            assertEquals("n=$n", n, result.size)
        }
    }

    @Test
    fun `every window is valid and inside available area`() {
        for (n in 1..7) {
            LayoutEngine.layout(area, preset, n).forEachIndexed { index, b ->
                assertTrue("n=$n index=$index bounds=$b 无效", b.isValid)
                assertTrue(
                    "n=$n index=$index bounds=$b 越界（可用区=$area）",
                    b.left >= area.left && b.top >= area.top &&
                        b.right <= area.right && b.bottom <= area.bottom
                )
            }
        }
    }

    @Test
    fun `primary window is never smaller than the others`() {
        for (n in 1..7) {
            val result = LayoutEngine.layout(area, preset, n)
            val primary = result[0]
            result.drop(1).forEachIndexed { i, other ->
                assertTrue(
                    "n=$n 主位(${primary.area}) 小于第 ${i + 1} 个(${other.area})",
                    primary.area >= other.area
                )
            }
        }
    }

    /**
     * 最终形态的具体形状：槽位 0 占满可用区，其余是**右下角 2×2 像素**的小点。
     *
     * 这里不断言"两两不重叠"——小点是故意压在主窗右下角上的（那样才"看不见"），
     * 重叠在这里是设计要求，不是缺陷。
     */
    @Test
    fun `solo keepalive gives one full area plus 2x2 dots at bottom right corner`() {
        val result = LayoutEngine.layout(area, preset, 3)
        assertEquals(3, result.size)
        assertEquals(area, result[0])
        result.drop(1).forEachIndexed { i, dot ->
            assertEquals("第 ${i + 1} 个不是 2px 宽：$dot", 2, dot.width)
            assertEquals("第 ${i + 1} 个不是 2px 高：$dot", 2, dot.height)
            assertEquals("小点没贴右边：$dot", area.right, dot.right)
            assertEquals("小点没贴下边：$dot", area.bottom, dot.bottom)
        }
    }

    @Test
    fun `invalid available area degrades to repeats`() {
        val broken = Bounds(0, 0, 0, 0)
        val result = LayoutEngine.layout(broken, preset, 3)
        assertEquals(3, result.size)
        result.forEach { assertEquals(broken, it) }
    }
}
