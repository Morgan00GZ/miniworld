package com.miniworld.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `am task resize` 的失败判定（只吃文本，不碰 Shizuku，所以能纯单测）。
 *
 * 判错这两个词的后果很实在：命令被 ROM 拒了却当成成功，
 * 后面所有「窗口已就位」的判断就全歪了。
 */
class FreeformRejectTest {

    @Test
    fun error_prefix_is_rejected() {
        assertTrue(FreeformController.rejectedByRom("Error: Task 91 doesn't exist"))
    }

    @Test
    fun lowercase_error_is_rejected() {
        assertTrue(FreeformController.rejectedByRom("error: bad bounds"))
    }

    @Test
    fun unknown_command_is_rejected() {
        assertTrue(FreeformController.rejectedByRom("Unknown command: task"))
    }

    @Test
    fun exception_text_is_rejected() {
        assertTrue(
            FreeformController.rejectedByRom("java.lang.IllegalArgumentException: process hasn't exited")
        )
    }

    @Test
    fun normal_output_is_accepted() {
        assertFalse(FreeformController.rejectedByRom("Setting focus to task 91"))
    }

    @Test
    fun empty_and_blank_output_are_accepted() {
        assertFalse(FreeformController.rejectedByRom(""))
        assertFalse(FreeformController.rejectedByRom("   \n  "))
    }

    @Test
    fun ordinary_resize_output_is_accepted() {
        assertFalse(FreeformController.rejectedByRom("com.tencent.nrc 已 resize 到 [0,0,100,100]"))
    }
}