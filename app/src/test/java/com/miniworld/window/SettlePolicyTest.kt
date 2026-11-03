package com.miniworld.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补课循环收工判据 + 掉线判定/自愈上限。
 *
 * 这两组数字直接决定"点启动后要傻等几秒"和"多久才敢说掉线"，
 * 改了要能从测试里看出来，别只靠实机手感。
 */
class SettlePolicyTest {

    @Test
    fun keeps_fixing_while_windows_are_still_drifting() {
        // 只要还有窗口没摆好，第几轮都不收工
        assertFalse(SettlePolicy.shouldStop(0, drifted = 1))
        assertFalse(SettlePolicy.shouldStop(5, drifted = 1))
        assertFalse(SettlePolicy.shouldStop(11, drifted = 2))
    }

    @Test
    fun needs_one_quiet_round_before_stopping() {
        // 第 0 轮就摆好了也再看一轮：应用抢窗口可能晚一拍
        assertFalse(SettlePolicy.shouldStop(0, drifted = 0))
        assertTrue(SettlePolicy.shouldStop(1, drifted = 0))
        assertTrue(SettlePolicy.shouldStop(2, drifted = 0))
    }

    @Test
    fun normal_start_stops_after_two_rounds() {
        // 2 轮 × 800 ms = 1.6 s，是"点完启动多久能点 Dock"的目标值
        var attempt = 0
        while (attempt < SettlePolicy.MAX_ROUNDS) {
            if (SettlePolicy.shouldStop(attempt, drifted = 0)) break
            attempt++
        }
        assertEquals(2, attempt + 1)
    }

    @Test
    fun lost_needs_three_consecutive_misses() {
        assertFalse(DropPolicy.isLost(0))
        assertFalse(DropPolicy.isLost(1))
        assertFalse(DropPolicy.isLost(2))
        assertTrue(DropPolicy.isLost(3))
        assertTrue(DropPolicy.isLost(9))
    }

    @Test
    fun relaunch_only_after_lost_and_under_the_cap() {
        assertFalse(DropPolicy.mayRelaunch(2, 0))   // 还没认定掉线
        assertTrue(DropPolicy.mayRelaunch(3, 0))    // 第一次
        assertTrue(DropPolicy.mayRelaunch(3, 1))    // 第二次
        assertFalse(DropPolicy.mayRelaunch(3, 2))   // 到顶，交给用户
    }

    @Test
    fun relaunch_waits_for_the_grace_period() {
        // 真机事故：两次重开只隔 3.2 s，游戏还没起来就被第二次启动顶掉
        assertFalse(DropPolicy.mayRelaunchNow(3, 1, sinceLastMs = 2_000L))
        assertEquals(
            false,
            DropPolicy.mayRelaunchNow(4, 1, sinceLastMs = DropPolicy.RELAUNCH_GRACE_MS - 1)
        )
        assertTrue(DropPolicy.mayRelaunchNow(4, 1, sinceLastMs = DropPolicy.RELAUNCH_GRACE_MS))
        assertTrue(DropPolicy.mayRelaunchNow(9, 1, sinceLastMs = 60_000L))
        // 冷却期过了但次数已用尽，仍然不许再动
        assertFalse(DropPolicy.mayRelaunchNow(9, 2, sinceLastMs = 60_000L))
    }

    @Test
    fun constants_stay_in_the_agreed_range() {
        assertEquals(800L, SettlePolicy.ROUND_INTERVAL_MS)
        assertEquals(3, DropPolicy.MISS_TICKS_TO_LOST)
        assertEquals(2, DropPolicy.MAX_RELAUNCH)
        assertEquals(10_000L, DropPolicy.RELAUNCH_GRACE_MS)
    }
}