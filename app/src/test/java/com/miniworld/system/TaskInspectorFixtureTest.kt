package com.miniworld.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 0 层：把**一份真机的** `dumpsys activity activities` 存成测试夹具，钉死解析结果。
 *
 * 为什么值得单独存在：看门狗每一拍都靠这份 dump 判断"任务还在不在、该在哪、谁在前面"，
 * 而它的输出格式完全由 ROM 决定。以后想给 `dumpsys` 加 awk 过滤（少读点文本）时，
 * 改动一旦漏掉某类行，解析结果就会**静默变错**（不是崩，而是把分身当主系统、把可见当不存在）。
 * 有了这份夹具，"过滤后解析结果必须与全量一致"才有可执行的判据。
 *
 * 夹具来源：2026-11-04 真机（25098PN5AC）挂机《洛克王国：世界》分身那份时抓的，
 * 任务 `#88`、`A=99910332:com.tencent.nrc U=999`、屏幕外 2×2 小点、前台在桌面。
 * 处理：去掉 CR（adb 输出带 \r），保留原样，不做裁剪——裁剪过的夹具就失去了"真实排版"这一步。
 */
class TaskInspectorFixtureTest {

    private val dump: String by lazy {
        javaClass.getResourceAsStream("/dumps/activities-keepalive.txt")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("找不到测试夹具 /dumps/activities-keepalive.txt")
    }

    @Test
    fun 夹具本身是完整的真机输出() {
        assertTrue("夹具太短，像是被截断了", dump.length > 100_000)
        assertTrue(dump.contains("ACTIVITY MANAGER"))
        assertTrue(dump.contains("A=99910332:com.tencent.nrc"))
    }

    @Test
    fun 能解析出受管任务并认得它是自由窗口() {
        val task = TaskInspector.find("com.tencent.nrc", 999, dump)
        assertNotNull("挂机中的分身任务必须能查到", task)
        task!!
        assertEquals("com.tencent.nrc", task.packageName)
        assertEquals(999, task.userId)
        assertTrue("自由窗口模式", task.isFreeform)
        assertTrue("窗口在系统眼里仍然可见（保活就靠这个，不是靠坐标）", task.visible)
    }

    @Test
    fun 小点是屏幕外的二乘二() {
        val task = TaskInspector.find("com.tencent.nrc", 999, dump)!!
        val b = task.bounds
        assertNotNull("必须读到 mBounds，读不到就会被误判成 resize 失败", b)
        assertEquals(2, b!!.right - b.left)
        assertEquals(2, b.bottom - b.top)
        // 屏 1220×2656 + DOT_OUT_X(16) / DOT_OUT_Y(34)
        assertEquals(1236, b.left)
        assertEquals(2690, b.top)
    }

    @Test
    fun 分身与主系统必须分得清() {
        // 同一份夹具里，主系统那份是没有的 —— 这正是"丢了 @999 就会操作错对象"的场景
        assertNull(TaskInspector.find("com.tencent.nrc", 0, dump))
        assertNotNull(TaskInspector.find("com.tencent.nrc", 999, dump))
    }

    @Test
    fun 焦点在桌面时快照读不到包名但有任务号() {
        // 真机这一状态下 ROM 写的是 `topDisplayFocusedRootTask=Task{26f142e #1 type=home}`：
        // 桌面任务的头部**不带 A=<uid>:<包名>**，所以解析出来的 focusedPackage 是 null。
        // 这不是 bug —— 调用方（userIsBusyElsewhere）遇到 null 会当成"没人占着前景"，
        // 继续按原逻辑判断，不会误判成"用户在用别的应用"。
        val snap = TaskInspector.focusSnapshot(dump)
        assertNull(snap.focusedPackage)
        assertEquals("焦点根任务 = 桌面的 #1", 1, snap.focusedTaskId)
        // 而"用户在看的那个任务"仍然读得到：显示级那条 topResumedActivity 在最后 = 桌面 t2
        assertEquals(2, snap.resumedTaskId)
    }

    @Test
    fun 受管窗口被顶成顶层焦点任务时快照能抓到它() {
        // 合成一行 topDisplayFocusedRootTask，模拟这台 ROM 的全部坏状态
        //（浮窗自己弹出来、变形、收不回 —— 3.6 起靠这一行判断要不要交还焦点）。
        val synthetic = dump.replaceFirst(
            "Display #0 (activities from top to bottom):",
            "topDisplayFocusedRootTask=Task{5ffebef #88 type=standard A=99910332:com.tencent.nrc}\n" +
                "Display #0 (activities from top to bottom):"
        )
        val snap = TaskInspector.focusSnapshot(synthetic)
        assertEquals("com.tencent.nrc", snap.focusedPackage)
        assertEquals(88, snap.focusedTaskId)
        assertEquals(2, snap.resumedTaskId)
    }

    @Test
    fun 查不到的任务要老实返回空() {
        assertNull(TaskInspector.find("com.not.installed", 0, dump))
        assertTrue("解析不出来的文本只应得到空列表，不该抛异常", TaskInspector.parse("").isEmpty())
        assertTrue(TaskInspector.parse("garbage\nno task here").isEmpty())
    }

    @Test
    fun 重复列出的同一个任务只算一份() {
        // 真机 dump 里 #88 被列了两次（一次带 mBounds 的完整块，一次是摘要行）。
        // 不去重的话，任何"按任务数/按窗口数"的判断都会错位。
        val managed = TaskInspector.parse(dump).filter { it.packageName == "com.tencent.nrc" }
        assertEquals("同包名同用户号只应出现一次", 1, managed.size)
        assertEquals(88, managed.first().taskId)
        assertEquals(999, managed.first().userId)
        assertNotNull("留下的必须是带 mBounds 的那一条", managed.first().bounds)
        // 不许把别的应用当成受管任务
        assertFalse(managed.any { it.packageName == "com.miui.home" })
    }
}
