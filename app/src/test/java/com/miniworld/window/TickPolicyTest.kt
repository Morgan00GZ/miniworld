package com.miniworld.window

import org.junit.Assert.assertEquals
import org.junit.Test

/** 看门狗节拍档位（1.12）：熄屏 20 s、亮屏 2 s、刚动过窗口保持 10 s 快档。 */
class TickPolicyTest {

    @Test
    fun 亮屏永远是快档() {
        assertEquals(2_000L, TickPolicy.intervalMs(screenOn = true, fastUntil = 0L, now = 1_000L))
        assertEquals(2_000L, TickPolicy.intervalMs(screenOn = true, fastUntil = 99_000L, now = 1_000L))
        // 快档保持已过期也一样：亮屏就是 2 s
        assertEquals(2_000L, TickPolicy.intervalMs(screenOn = true, fastUntil = 1L, now = 9_999_999L))
    }

    @Test
    fun 熄屏降到慢档() {
        assertEquals(20_000L, TickPolicy.intervalMs(screenOn = false, fastUntil = 0L, now = 1_000L))
    }

    @Test
    fun 熄屏但刚动过窗口时保持快档() {
        // fastUntil 还没到：继续 2 s 一拍，确认窗口真的落位
        assertEquals(2_000L, TickPolicy.intervalMs(screenOn = false, fastUntil = 11_000L, now = 1_000L))
        // 刚好到点 → 回慢档
        assertEquals(20_000L, TickPolicy.intervalMs(screenOn = false, fastUntil = 1_000L, now = 1_000L))
        // 过了之后一直是慢档
        assertEquals(20_000L, TickPolicy.intervalMs(screenOn = false, fastUntil = 1_000L, now = 30_000L))
    }

    @Test
    fun 两个档位就是用户选的那两个数() {
        assertEquals(2_000L, TickPolicy.TICK_MS)
        assertEquals(20_000L, TickPolicy.TICK_MS_SCREEN_OFF)
    }

    @Test
    fun 闹钟兜底是十分钟一次() {
        assertEquals(600_000L, TickPolicy.ALARM_INTERVAL_MS)
    }

    @Test
    fun 漏拍判据是两拍加五秒() {
        assertEquals(9_000L, TickPolicy.stallThresholdMs(TickPolicy.TICK_MS))
        assertEquals(45_000L, TickPolicy.stallThresholdMs(TickPolicy.TICK_MS_SCREEN_OFF))
    }

    @Test
    fun 漏拍判据按当前档位算() {
        // 快档：正好到阈值（9 s）还不算漏，超过一点点（9.001 s）才算
        assertEquals(false, TickPolicy.isStalled(now = 9_000L, lastTickAt = 0L, intervalMs = 2_000L))
        assertEquals(true, TickPolicy.isStalled(now = 9_001L, lastTickAt = 0L, intervalMs = 2_000L))
        // 慢档：21 s 是正常的（刚跳完一拍），46 s 才算漏
        assertEquals(false, TickPolicy.isStalled(now = 21_000L, lastTickAt = 0L, intervalMs = 20_000L))
        assertEquals(true, TickPolicy.isStalled(now = 46_000L, lastTickAt = 0L, intervalMs = 20_000L))
    }

    @Test
    fun 真机那次十三分钟零拍一定算漏拍() {
        val gap = 13L * 60 * 1000
        assertEquals(true, TickPolicy.isStalled(now = gap, lastTickAt = 0L, intervalMs = TickPolicy.TICK_MS))
        assertEquals(
            true,
            TickPolicy.isStalled(now = gap, lastTickAt = 0L, intervalMs = TickPolicy.TICK_MS_SCREEN_OFF)
        )
    }
}
