package com.miniworld.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自检面板里的两处「翻译」：appops 原始行、待机桶数字。
 *
 * 这两处一旦翻译错，面板会给出与真实情况**相反**的结论
 *（比如把「未允许」显示成「允许」、把「罕见」显示成「豁免」），
 * 所以按真机抓到的原文逐字对齐。
 */
class SelfCheckFormatTest {

    @Test
    fun appOp_line_with_timestamp_is_reduced_to_the_mode() {
        // 真机原样：2026-10-30 审计 com.tencent.nrc 时抓到的
        assertEquals(
            "未允许（default）",
            SelfCheck.appOpWord("RUN_ANY_IN_BACKGROUND: default; time=+77d15h47m41s188ms ago")
        )
    }

    @Test
    fun appOp_allow_is_translated() {
        assertEquals("允许", SelfCheck.appOpWord("RUN_ANY_IN_BACKGROUND: allow"))
    }

    @Test
    fun appOp_blank_is_reported_as_unreadable() {
        assertEquals("读不到", SelfCheck.appOpWord(""))
        assertEquals("读不到", SelfCheck.appOpWord("RUN_ANY_IN_BACKGROUND:"))
    }

    @Test
    fun appOp_unknown_value_is_kept_verbatim() {
        assertEquals("weird", SelfCheck.appOpWord("RUN_ANY_IN_BACKGROUND: weird; time=+1s"))
    }

    @Test
    fun bucket_numbers_are_translated() {
        assertEquals("5 豁免", SelfCheck.bucketWord("5"))
        assertEquals("10 活跃", SelfCheck.bucketWord("10\n"))
        assertEquals("45 受限", SelfCheck.bucketWord(" 45 "))
    }

    @Test
    fun bucket_unknown_and_blank_are_handled() {
        assertEquals("读不到", SelfCheck.bucketWord(""))
        assertEquals("99", SelfCheck.bucketWord("99"))
    }
}