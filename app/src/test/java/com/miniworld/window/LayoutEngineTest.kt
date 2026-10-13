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
     * 最终形态的具体形状：槽位 0 占满可用区，其余是 2×2 像素的小点。
     *
     * 不传 offscreenDot 时退回"可用区右下角贴边"（旧行为，只作拿不到真实屏幕尺寸时的兜底）；
     * 真机上小点由调用方放到**屏幕外**，见下一个用例。
     *
     * 这里不断言"两两不重叠"——小点是故意压在主窗右下角上的（那样才"看不见"），
     * 重叠在这里是设计要求，不是缺陷。
     */
    @Test
    fun `solo keepalive falls back to bottom right corner dots without an offscreen target`() {
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

    /**
     * 2026-10-13 真机实测：小点只要还落在屏幕内，系统给自由窗口画的那圈装饰就会露出屏幕一角
     * （用户原话"大半在屏幕外也是坏掉的"）。所以调用方会把小点整块放到**屏幕外**，
     * 这里断言这个位置被原样用在所有非 0 槽位上。
     */
    @Test
    fun `solo keepalive uses the offscreen dot position when given`() {
        val offscreen = Bounds(area.right + 172, area.bottom + 99, area.right + 174, area.bottom + 101)
        val result = LayoutEngine.layout(area, preset, 3, offscreen)
        assertEquals(3, result.size)
        assertEquals(area, result[0])
        assertEquals(listOf(offscreen, offscreen), result.drop(1))
    }

    @Test
    fun `invalid available area degrades to repeats`() {
        val broken = Bounds(0, 0, 0, 0)
        val result = LayoutEngine.layout(broken, preset, 3)
        assertEquals(3, result.size)
        result.forEach { assertEquals(broken, it) }
    }
}
