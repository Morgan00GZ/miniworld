package com.miniworld.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活命令合并成一条 `sh` 脚本之后，如何还原「哪几条失败」。
 *
 * 这块纯逻辑最容易出的错是「把命令正常输出里的 Error 当成失败」，
 * 所以规则定得很死：**只认自己打的 `__FAIL__:` 标记，而且必须出现在行首**。
 */
class KeepAliveBatchTest {

    private val steps = listOf(
        KeepAlive.Step("a", "步骤A", "cmd a"),
        KeepAlive.Step("b", "步骤B", "cmd b"),
        KeepAlive.Step("c", "步骤C", "cmd c")
    )

    @Test
    fun no_marker_means_all_succeeded() {
        assertTrue(KeepAlive.parseBatchFailures(steps, "ok\nok\nok").isEmpty())
    }

    @Test
    fun marker_maps_back_to_its_own_step() {
        val failed = KeepAlive.parseBatchFailures(steps, "ok\n__FAIL__:b\nok")
        assertEquals(listOf("b"), failed.map { it.key })
    }

    @Test
    fun several_markers_keep_the_step_order() {
        val failed = KeepAlive.parseBatchFailures(steps, "__FAIL__:c\nok\n__FAIL__:a")
        assertEquals(listOf("a", "c"), failed.map { it.key })
    }

    @Test
    fun marker_line_is_trimmed_but_not_matched_loosely() {
        // 只有标记、没有 key —— 不该匹配到任何一步
        assertTrue(KeepAlive.parseBatchFailures(steps, "  ok  \n  __FAIL__:  ").isEmpty())
        assertEquals(listOf("c"), KeepAlive.parseBatchFailures(steps, " __FAIL__:c ").map { it.key })
    }

    @Test
    fun unknown_marker_is_ignored() {
        assertTrue(KeepAlive.parseBatchFailures(steps, "__FAIL__:zzz\nError: something").isEmpty())
    }

    @Test
    fun marker_must_start_the_line() {
        // 命令自己的输出里出现 "xx __FAIL__:a" 不算失败
        assertTrue(KeepAlive.parseBatchFailures(steps, "note: xx __FAIL__:a").isEmpty())
    }

    @Test
    fun script_joins_commands_in_order_with_markers() {
        assertEquals(
            "cmd a || echo __FAIL__:a; cmd b || echo __FAIL__:b; cmd c || echo __FAIL__:c",
            KeepAlive.buildBatchScript(steps)
        )
    }

    @Test
    fun script_keeps_each_command_untouched() {
        val script = KeepAlive.buildBatchScript(
            listOf(KeepAlive.Step("bucket", "待机桶", "am set-standby-bucket --user 999 com.x active"))
        )
        assertTrue(
            script.startsWith(
                "am set-standby-bucket --user 999 com.x active || echo __FAIL__:bucket"
            )
        )
    }
}