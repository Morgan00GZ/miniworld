package com.miniworld.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「包名 + 用户号」目标清单的回归测试。
 *
 * 这里错一次，用户看到的就是"点关闭，主机和手机分身两份一起被关"（真机 2026-10-13 反馈）。
 * 纯 JVM 测试，`gradlew :app:testDebugUnitTest` 就能跑，不需要真机。
 */
class AppTargetsTest {

    private fun window(index: Int, pkg: String, userId: Int = 0) = LiveWindow(
        slotIndex = index,
        packageName = pkg,
        userId = userId,
        component = null,
        taskId = 100 + index,
        bounds = Bounds(0, 0, 2, 2),
        launchedBy = "test"
    )

    private fun slot(index: Int, pkg: String, userId: Int = 0) = Slot(
        index = index,
        packageName = pkg,
        label = pkg,
        userId = userId
    )

    @Test
    fun `同名包在主机和分身上各有一份时_两个目标都必须保留`() {
        val targets = appTargets(
            windows = listOf(window(0, "com.tencent.nrc", 0), window(1, "com.tencent.nrc", 999)),
            slots = emptyList()
        )
        assertEquals(
            "包名相同、用户号不同的两个实例都要留下，否则关闭时会漏掉一份或关错一份",
            listOf(AppTarget("com.tencent.nrc", 0), AppTarget("com.tencent.nrc", 999)),
            targets
        )
    }

    @Test
    fun `用户号相同才算重复_不同用户号不去重`() {
        val targets = appTargets(
            windows = listOf(window(0, "com.tencent.nrc", 999)),
            slots = listOf(slot(0, "com.tencent.nrc", 999), slot(1, "com.tencent.nrc", 0))
        )
        assertEquals(
            listOf(AppTarget("com.tencent.nrc", 999), AppTarget("com.tencent.nrc", 0)),
            targets
        )
    }

    @Test
    fun `窗口在前槽位在后_顺序稳定`() {
        val targets = appTargets(
            windows = listOf(window(0, "com.a"), window(1, "com.b")),
            slots = listOf(slot(0, "com.c"), slot(1, "com.a"))
        )
        assertEquals(
            listOf("com.a@0", "com.b@0", "com.c@0"),
            targets.map { it.key }
        )
    }

    @Test
    fun `日志标签能看出是分身`() {
        assertEquals("com.tencent.nrc", AppTarget("com.tencent.nrc", 0).label)
        assertEquals("com.tencent.nrc（分身 999）", AppTarget("com.tencent.nrc", 999).label)
    }

    @Test
    fun `同名包两份时_只取对应那份的任务号`() {
        val windows = listOf(window(0, "com.tencent.nrc", 0), window(1, "com.tencent.nrc", 999))
        assertEquals(listOf(100), taskIdsOf(AppTarget("com.tencent.nrc", 0), windows))
        assertEquals(listOf(101), taskIdsOf(AppTarget("com.tencent.nrc", 999), windows))
    }

    @Test
    fun `同一个目标出现两次时任务号去重`() {
        val windows = listOf(window(0, "com.tencent.nrc", 999), window(3, "com.tencent.nrc", 999))
        assertEquals(listOf(100, 103), taskIdsOf(AppTarget("com.tencent.nrc", 999), windows))
    }

    @Test
    fun `没有对应窗口时返回空_不要拿另一份的任务号顶替`() {
        val onlyClone = listOf(window(1, "com.tencent.nrc", 999))
        assertEquals(emptyList<Int>(), taskIdsOf(AppTarget("com.tencent.nrc", 0), onlyClone))
    }
}
