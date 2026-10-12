package com.miniworld.system

import com.miniworld.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskInspector 的解析回归测试。
 *
 * 下面的 dump 片段是**逐字**从真机抓下来的（Xiaomi 25098PN5AC / Android 17 / HyperOS 4，
 * `adb shell dumpsys activity activities`），不是编出来的。解析器是整个 App 唯一的事实来源：
 * 它读错一个字段，上层就会得出"这台手机不支持多窗口"的错误结论，把用户引到错误的降级路线上。
 */
class TaskInspectorTest {

    /** 真机 dump 片段：一个计算器自由窗口、一个游戏自由窗口、一个桌面任务。 */
    private val REAL_DUMP = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{82ebf4 #125 type=standard A=10281:com.miui.calculator U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
            mBounds=Rect(306, 760 - 915, 1980)
            isAlwaysOnTop
            fullscreenRequestAllowMode=NONE
            mNonOccludedFreeformAreaRatio=100
            isSleeping=false
            topResumedActivity=ActivityRecord{129950536 u0 com.miui.calculator/.cal.CalculatorActivity t125}
            * Hist  #0: ActivityRecord{129950536 u0 com.miui.calculator/.cal.CalculatorActivity t125}
              packageName=com.miui.calculator processName=com.miui.calculator
              launchedFromUid=2000 launchedFromPackage=com.android.shell launchedFromFeature=null userId=0
          * Task{618a9ed #91 type=standard A=10332:com.tencent.nrc U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
            mBounds=Rect(44, 329 - 584, 1529)
            isAlwaysOnTop
            fullscreenRequestAllowMode=NONE
            mLastPausedActivity: ActivityRecord{70389491 u0 com.tencent.nrc/com.tencent.gcloud.msdk.core.policy.MSDKPolicyV3Activity t-1 f}}
            mLastNonFullscreenBounds=Rect(826, 462 - 1831, 842)
            mNonOccludedFreeformAreaRatio=83
            isSleeping=false
            topResumedActivity=ActivityRecord{29122565 u0 com.tencent.nrc/com.epicgames.ue4.GameActivity t91}
            * Hist  #0: ActivityRecord{29122565 u0 com.tencent.nrc/com.epicgames.ue4.GameActivity t91}
              packageName=com.tencent.nrc processName=com.tencent.nrc
              launchedFromUid=10332 launchedFromPackage=com.tencent.nrc launchedFromFeature=null userId=0
              app=ProcessRecord{b0f0d5f 32506:com.tencent.nrc/u0a332}
              Intent { flg=0x10000000 xflg=0x4 cmp=com.tencent.nrc/com.epicgames.ue4.GameActivity (has extras) }
              rootOfTask=true task=Task{618a9ed #91 type=standard A=10332:com.tencent.nrc}
              taskAffinity=10332:com.tencent.nrc
              mActivityComponent=com.tencent.nrc/com.epicgames.ue4.GameActivity
              dataDir=/data/user/0/com.tencent.nrc
          * Task{5568aa7 #2 type=home I=com.miui.home/.launcher.Launcher U=0 rootTaskId=1 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
            mBounds=Rect(0, 0 - 1220, 2656)
            topResumedActivity=ActivityRecord{9e4b6b0 u0 com.miui.home/.launcher.Launcher t2}
    """.trimIndent()

    private fun tasksByPackage(dump: String): Map<String, TaskInfo> =
        TaskInspector.parse(dump).associateBy { it.packageName }

    @Test
    fun `自由窗口任务的包名_模式_坐标_可见性全部解析正确`() {
        val tasks = tasksByPackage(REAL_DUMP)

        val calc = tasks["com.miui.calculator"]
        assertNotNull("计算器任务没解析出来", calc)
        assertEquals(125, calc!!.taskId)
        assertEquals("freeform", calc.windowingModeRaw)
        assertTrue(calc.isFreeform)
        assertTrue(calc.visible)
        assertEquals(Bounds(306, 760, 915, 1980), calc.bounds)
        assertEquals("com.miui.calculator/.cal.CalculatorActivity", calc.activity)

        val game = tasks["com.tencent.nrc"]
        assertNotNull("游戏任务没解析出来", game)
        assertEquals(91, game!!.taskId)
        assertTrue(game.isFreeform)
        assertEquals(Bounds(44, 329, 584, 1529), game.bounds)
    }

    @Test
    fun `取的是当前Activity而不是已经暂停的旧页面`() {
        // 真机 dump 里 mLastPausedActivity 出现在 topResumedActivity 之前。
        // 如果按"块里第一个 ActivityRecord"来读，会拿到 MSDK 政策页而不是正在显示的游戏页。
        val game = tasksByPackage(REAL_DUMP)["com.tencent.nrc"]!!
        assertEquals("com.tencent.nrc/com.epicgames.ue4.GameActivity", game.activity)
        assertFalse(game.activity.contains("MSDKPolicyV3Activity"))
    }

    @Test
    fun `块内的引用行不会截断任务块`() {
        // `rootOfTask=true task=Task{...}` 这种引用行不是新任务头。
        // 一旦被当成新任务头，块就被截断，后面的字段读不到，表现为"任务在但坐标是空的"。
        val parsed = TaskInspector.parse(REAL_DUMP)
        assertEquals("只应有 3 个任务，引用行不算任务", 3, parsed.size)

        val game = parsed.first { it.packageName == "com.tencent.nrc" }
        assertNotNull("引用行把任务块截断了，坐标丢了", game.bounds)
        assertEquals(Bounds(44, 329, 584, 1529), game.bounds)
        assertTrue("引用行把任务块截断了，当前 Activity 丢了", game.activity.endsWith("GameActivity"))
    }

    @Test
    fun `没有A字段的桌面任务用基础Intent的I字段兜底`() {
        val home = tasksByPackage(REAL_DUMP)["com.miui.home"]
        assertNotNull("桌面任务没解析出来：任务头只有 I= 没有 A=", home)
        assertEquals(2, home!!.taskId)
        assertEquals("fullscreen", home.windowingModeRaw)
        assertFalse(home.isFreeform)
    }

    @Test
    fun `find按顶部第一条返回_自由窗口任务列表只含自由窗口`() {
        val tasks = TaskInspector.parse(REAL_DUMP)
        val game = tasks.firstOrNull { it.packageName == "com.tencent.nrc" }
        assertEquals(91, game?.taskId)
        assertEquals(
            "自由窗口应有计算器和游戏两个（桌面是全屏）",
            setOf("com.miui.calculator", "com.tencent.nrc"),
            tasks.filter { it.isFreeform }.map { it.packageName }.toSet()
        )
    }

    @Test
    fun `mLastNonFullscreenBounds不会被当成窗口坐标`() {
        val dump = """
            * Task{aabbcc #7 type=standard A=10001:com.example.app U=0 visible=true mode=freeform sz=1}
            mLastNonFullscreenBounds=Rect(1, 2 - 3, 4)
        """.trimIndent()
        val task = TaskInspector.parse(dump).single()
        assertEquals("com.example.app", task.packageName)
        assertTrue(task.isFreeform)
        assertNull("mLastNonFullscreenBounds 是上次全屏前的旧坐标，不能当成窗口坐标", task.bounds)
    }

    @Test
    fun `mode等于数字5也识别为自由窗口`() {
        val dump = """
            * Task{aabbcc #8 type=standard A=10002:com.foo.bar U=0 visible=true mode=5}
            mBounds=Rect(0, 0 - 100, 200)
        """.trimIndent()
        val task = TaskInspector.parse(dump).single()
        assertEquals("5", task.windowingModeRaw)
        assertTrue(task.isFreeform)
        assertEquals(Bounds(0, 0, 100, 200), task.bounds)
    }

    @Test
    fun `解析不出来时返回空列表而不是抛异常`() {
        assertTrue(TaskInspector.parse("").isEmpty())
        assertTrue(TaskInspector.parse("   \n  \n").isEmpty())
        // ROM 换了排版 / 命令被 SELinux 截断时，最容易出现的就是这种"有输出但没任务"的文本。
        // 这条路径必须安静地返回空列表，上层才能把它和"命令失败"区分开。
        assertTrue(TaskInspector.parse("Error: java.lang.SecurityException: Permission Denial").isEmpty())
        assertTrue(TaskInspector.parse("Task{没有井号也没有右括号").isEmpty())
    }
}
