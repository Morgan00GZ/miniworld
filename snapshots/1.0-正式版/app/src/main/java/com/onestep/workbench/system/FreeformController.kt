package com.onestep.workbench.system

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import com.onestep.workbench.model.Bounds
import com.onestep.workbench.shizuku.ShellRunner

/**
 * 自由窗口（Freeform）控制层。
 *
 * 这里最重要的设计是**启动策略链**：从"零特权"到"高特权"逐级尝试，第一个成功即停，
 * 并把成功的策略记下来。原因很直接——`windowingMode`、`--bounds` 这些选项在各家 ROM 上
 * 存在与否都不一样，写死一条命令必然在某个机型上翻车。
 */
object FreeformController {

    private const val TAG = "FreeformController"

    const val WINDOWING_MODE_FULLSCREEN = 1
    const val WINDOWING_MODE_FREEFORM = 5

    /**
     * 隐藏方法，可能被 hidden-API 限制拦下（拦下就落到 shell 策略，不影响功能）。
     * 用反射而不是直接调用，是为了保证在 API 变动时只降级、不崩。
     */
    private val setLaunchWindowingMode: java.lang.reflect.Method? by lazy {
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        }.onFailure { Log.i(TAG, "setLaunchWindowingMode 不可用（正常，会退到 shell）：${it.message}") }
            .getOrNull()
    }

    // ------------------------------------------------------------------ 命令面

    data class CommandSurface(
        val windowingMode: Boolean,
        val bounds: Boolean,
        val taskResize: Boolean,
        /**
         * `am help` 在 Android 17 上**不列** focus 子命令，但 `am task focus <id>` 实测可用。
         * 所以这个字段是三态的：null = 帮助没写、也还没实测过；true/false = 实测结果。
         * 用 Boolean? 而不是 Boolean，是为了不把"不知道"伪装成"不支持"。
         */
        val taskFocus: Boolean?,
        val rawHelp: String
    ) {
        val summary: String
            get() = "--windowingMode=${yn(windowingMode)} · --bounds=${yn(bounds)} · " +
                "task resize=${yn(taskResize)} · task focus=" + when (taskFocus) {
                true -> "实测可用"
                false -> "实测失败"
                null -> "未实测（help 未列）"
            }

        private fun yn(v: Boolean) = if (v) "支持" else "不支持"
    }

    @Volatile
    private var cachedSurface: CommandSurface? = null

    /**
     * 探测 `am` 的命令面。
     * 与其猜"这个 ROM 支不支持 --bounds"，不如直接读 `am help` 的输出——
     * 一次探测换来的是后续所有启动/改尺寸动作都不会打空。
     */
    fun commandSurface(force: Boolean = false): CommandSurface {
        cachedSurface?.let { if (!force) return it }

        val help = ShellRunner.exec("am help", 10_000).combined
        // `am task` 不带子命令在 Android 17 上直接抛 IllegalArgumentException（Argument expected after "task"），
        // 拿不到子命令清单，所以判定只用 `am help`，这次调用仅作为线索保留在日志里。
        ShellRunner.exec("am task", 8_000)

        // 真机教训：`am help` 的 task 段落里写的是**裸子命令名**：
        //     task [COMMAND] [...]: sub-commands for operating on activity tasks.
        //          resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM>
        //          resizeable <TASK_ID> [0|1|2|3]
        // 根本没有 "task resize" 这个字面串。旧写法 `help.contains("task resize")` 在这台机器上
        // 恒为 false，会把实测可用的 resize 报成"不支持"（focus 更是连列都没列）。
        // 因此改成按段落里的子命令签名匹配，而"能不能用"最终由实测决定。
        val surface = CommandSurface(
            windowingMode = help.contains("--windowingMode"),
            bounds = Regex("(?m)^\\s*--bounds\\b").containsMatchIn(help),
            taskResize = Regex("(?m)^\\s*resize\\s*<TASK_ID>").containsMatchIn(help),
            taskFocus = when {
                Regex("(?m)^\\s*focus\\s*<TASK_ID>").containsMatchIn(help) -> true
                else -> null
            },
            rawHelp = help
        )
        cachedSurface = surface
        return surface
    }

    /** 实测成功过一次就把它记进缓存，自检页显示的就不再是"未实测"。 */
    private fun markFocusWorking() {
        cachedSurface = cachedSurface?.copy(taskFocus = true)
    }

    /** 实测失败也记下来，免得每次切换都白试一条命令。 */
    private fun markFocusFailed() {
        cachedSurface = cachedSurface?.copy(taskFocus = false)
    }

    // ------------------------------------------------------------------ 启动

    data class LaunchOutcome(
        val success: Boolean,
        val strategy: String,
        val task: TaskInfo?,
        val log: List<String>
    )

    /** 组件串可以带 "@用户号" 后缀（…Activity@999 = 手机分身里的那一份）。 */
    private fun splitUser(component: String): Pair<String, String> {
        val at = component.lastIndexOf('@')
        // 一律显式带 --user：不写用户号时，有分身的应用（如 nrc）会弹"选哪一个"的系统选择框，
        // 表现就是启动失败、窗口乱跳（真机 m01854 反馈）。
        if (at <= 0) return component to "--user 0 "
        val user = component.substring(at + 1).toIntOrNull() ?: return component to "--user 0 "
        return component.substring(0, at) to "--user $user "
    }

    private fun amStart(component: String, extra: String = ""): String {
        val (comp, userArg) = splitUser(component)
        return "am start $userArg-n $comp$extra"
    }

    fun launchInFreeform(
        component: String,
        bounds: Bounds,
        context: Context? = null,
        preferredStrategy: String? = null
    ): LaunchOutcome {
        val pkg = component.substringBefore('/')
        val log = ArrayList<String>()

        // 上次已经证明"App 内启动这条路在本机走不通"时，就别让用户再看一遍黑屏
        val skipAppLaunch = preferredStrategy == "shell-windowing-mode" ||
            preferredStrategy == "fullscreen-then-resize" ||
            preferredStrategy == "vendor-broadcast"

        // 策略 1：App 内直启 + setLaunchBounds（零特权，最干净）
        if (context == null || skipAppLaunch) {
            if (skipAppLaunch) log += "[1] 跳过：上次记录的成功策略是 $preferredStrategy"
        } else {
            log += "[1] startActivity + setLaunchBounds ${bounds.toRect()}"
            if (launchFromApp(context, component, bounds)) {
                val task = TaskInspector.waitForTask(pkg, 2_500)
                if (task != null && (task.isFreeform || boundsClose(task.bounds, bounds))) {
                    return LaunchOutcome(true, "app-launch-bounds", ensureBounds(task, bounds, log), log)
                }
                log += "    → 窗口未按预期出现：${task ?: "无任务"}"
            } else {
                log += "    → 启动被拒"
            }
        }

        val surface = commandSurface()

        // 策略 2：shell 指定窗口模式（能带 bounds 就带，省掉一次 resize 闪烁）
        if (surface.windowingMode) {
            val boundsArg = if (surface.bounds) " --bounds ${bounds.toShellArg()}" else ""
            val cmd = amStart(component, " --windowingMode $WINDOWING_MODE_FREEFORM$boundsArg")
            log += "[2] $cmd"
            val result = ShellRunner.exec(cmd)
            log += "    exit=${result.exitCode} ${result.firstLine()}"
            if (!result.contains("Error") && !result.contains("Exception")) {
                val task = waitForFreeformTask(pkg, 2_500)
                if (task != null) {
                    return LaunchOutcome(true, "shell-windowing-mode", ensureBounds(task, bounds, log), log)
                }
                log += "    → 命令成功但没等到自由窗口"
            }
        } else {
            log += "[2] 跳过：本机 am 不支持 --windowingMode"
        }

        // 策略 3：全屏启动 + 改尺寸（有的 ROM 只是不接受启动参数，但允许事后 resize）
        log += "[3] am start（全屏）后 resize"
        val plain = ShellRunner.exec(amStart(component))
        log += "    exit=${plain.exitCode} ${plain.firstLine()}"
        val task = TaskInspector.waitForTask(pkg, 3_000)
        if (task != null) {
            val resized = resizeTask(task.taskId, bounds)
            log += "    resize $task.taskId -> ${bounds.toResizeArgs()} : $resized"
            val after = TaskInspector.listTasks().firstOrNull { it.taskId == task.taskId }
            if (resized) {
                return LaunchOutcome(true, "fullscreen-then-resize", after ?: task, log)
            }
            return LaunchOutcome(false, "fullscreen-only", after ?: task, log)
        }
        log += "    → 任务始终没出现"

        // 策略 4：厂商小窗广播（可选，装了 hyper_freeform_x 才有）
        log += "[4] 厂商小窗广播（可选）"
        val broadcast = ShellRunner.exec(
            "am broadcast -a name.liuyi.mifreeformx.action.SWITCH_TO_FREEFORM --es component $component"
        )
        log += "    exit=${broadcast.exitCode} ${broadcast.firstLine()}"
        if (!broadcast.contains("Error") && broadcast.contains("Broadcast completed")) {
            val vendorTask = TaskInspector.waitForTask(pkg, 2_500)
            val placed = vendorTask?.let { ensureBounds(it, bounds, log) }
            return LaunchOutcome(placed != null, "vendor-broadcast", placed, log)
        }

        return LaunchOutcome(false, "none", null, log)
    }

    private fun launchFromApp(context: Context, component: String, bounds: Bounds): Boolean = runCatching {
        val pkg = component.substringBefore('/')
        val cls = expandClassName(pkg, component.substringAfter('/'))

        val intent = Intent().apply {
            this.component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }

        val options = ActivityOptions.makeBasic()
        // setLaunchBounds 是公开 API（API 24+），且没有 getter，所以只能显式 setter 调用
        options.setLaunchBounds(bounds.toRect())
        // 尽力而为：隐藏方法被拦下不影响主流程，bounds 仍然生效
        setLaunchWindowingMode?.invoke(options, WINDOWING_MODE_FREEFORM)

        context.startActivity(intent, options.toBundle())
        true
    }.onFailure { Log.w(TAG, "App 内启动失败：${it.message}") }.getOrDefault(false)

    private fun waitForFreeformTask(pkg: String, timeoutMs: Long): TaskInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = TaskInspector.listTasks().firstOrNull { it.packageName == pkg }
            if (task != null && task.isFreeform) return task
            Thread.sleep(250)
        }
        return TaskInspector.listTasks().firstOrNull { it.packageName == pkg }
    }

    /**
     * 「启动成功」不等于「铺好了」。
     *
     * 这台机器**不支持** `am start --bounds`（真机实测：`am help` 里没有 --bounds），
     * 所以策略 2 起的自由窗口会先落在系统默认位置（实测 88,1110-1132,1631），
     * 必须再 resize 一次才和栅格对得上——少了这一步，界面上写着"已平铺"，
     * 实际几个窗口叠在同一个地方，用户会以为是坏的。
     *
     * 返回复查后的 TaskInfo，让调用方拿到的是**真实**落点而不是期望值。
     */
    private fun ensureBounds(task: TaskInfo, bounds: Bounds, log: MutableList<String>): TaskInfo {
        if (boundsClose(task.bounds, bounds)) {
            log += "    → 落点已是目标 ${task.bounds}"
            return task
        }
        log += "    → 落点 ${task.bounds ?: "读不到"}，按目标 [${bounds.toResizeArgs()}] 再 resize"
        val ok = resizeTask(task.taskId, bounds)
        log += "    → resize ${if (ok) "成功" else "失败（或读不到 bounds，按退出码判断）"}"
        return TaskInspector.listTasks().firstOrNull { it.taskId == task.taskId } ?: task
    }

    // ------------------------------------------------------------------ 窗口操作

    /** 改窗口大小，并**复查**是否真的改了（只看退出码会被 ROM 骗过去）。 */
    fun resizeTask(taskId: Int, bounds: Bounds): Boolean {
        // 注意：这里是四个分开的数字（toResizeArgs），不是逗号串——真机实测逗号串必抛 NumberFormatException
        val result = ShellRunner.exec("am task resize $taskId ${bounds.toResizeArgs()}")
        if (result.contains("Error") || result.contains("Unknown")) {
            Log.w(TAG, "resize 被拒：${result.describe()}")
            return false
        }
        Thread.sleep(250)
        val task = TaskInspector.listTasks().firstOrNull { it.taskId == taskId }
        if (task?.bounds == null) {
            // ROM 没暴露 bounds，只能相信退出码——把它记下来，别假装验证过了
            Log.i(TAG, "无法读取 bounds，按退出码判断：exit=${result.exitCode}")
            return result.ok || result.combined.isEmpty()
        }
        return boundsClose(task.bounds, bounds)
    }

    /**
     * 把指定任务前置（"一键切换主位"的底层动作）。
     * 优先 `am task focus`，不可用时退到 `--activity-reorder-to-front`。
     */
    fun focusTask(taskId: Int, component: String?): Boolean {
        // 注意这里**不按能力探测提前跳过**：Android 17 的 `am help` 里没有 focus 这一行，
        // 但 `am task focus 91` 实测可用（输出 "Setting focus to task 91"）。
        // 静态判断在这件事上已经骗过我们一次，所以一律先试真命令，失败才回退。
        val result = ShellRunner.exec("am task focus $taskId")
        if (!result.contains("Error") && !result.contains("Exception") && !result.contains("Unknown")) {
            markFocusWorking()
            return true
        }
        markFocusFailed()
        Log.i(TAG, "am task focus 失败，回退 reorder-to-front：${result.firstLine()}")
        if (component != null) {
            val fallback = ShellRunner.exec(amStart(component, " --activity-reorder-to-front"))
            return !fallback.contains("Error")
        }
        return false
    }

    /** 把一个自由窗口收回全屏。 */
    fun toFullscreen(taskId: Int, component: String?): Boolean {
        if (component != null) {
            val result = ShellRunner.exec(
                amStart(component, " --windowingMode $WINDOWING_MODE_FULLSCREEN")
            )
            if (!result.contains("Error")) return true
        }
        // 兜底：放大到超出屏幕，由系统裁到全屏（该任务仍在 freeform 栈里，但视觉等同全屏）
        val result = ShellRunner.exec("am task resize $taskId 0 0 9999 9999")
        return !result.contains("Error")
    }

    /** 普通启动，不做任何窗口模式尝试。QUICK_SWITCH / VENDOR_FREEFORM 档位用。 */
    fun launchPlain(component: String): Boolean {
        val result = ShellRunner.exec(amStart(component))
        return !result.contains("Error")
    }

    /**
     * 结束一个应用，并把它从「最近任务」里摘掉 —— 等价于用户在后台把那张卡片划走。
     *
     * 为什么不能只发 force-stop：`am force-stop` 只结束进程，**不动那条最近任务记录**。
     * 真机实测（HyperOS 4 / Android 17，m02777 / m02785）：force-stop 之后
     * `dumpsys activity activities` 里已经搜不到该包（0 命中），但 `dumpsys activity recents` 里
     * 卡片还在：`* Recent #1: Task{d9798fc #787 type=standard A=1000:com.android.settings.root}`，
     * 点它还会把应用冷启动回来 —— 这就是用户报的"任务后台还能看到这个应用"。
     *
     * 摘卡片用什么：`am task remove` **不存在**（实测 `Error: unknown command 'remove'`，
     * `cmd activity task remove` 同样不行）；可用的是 **`am stack remove <rootTaskId>`**
     * ——现代 Android 里每个标准任务自己就是一个 root task，所以任务 id 就是 stack id。
     * 实测对 787 发一次 `am stack remove 787`：recents 卡片数 1 → 0，进程也随之消失。
     *
     * 顺序有讲究：任务 id 必须在 force-stop **之前**取（force-stop 会把任务从活动列表里清掉，
     * 之后就取不到了）；而 `am stack remove` 在 force-stop 之后照样有效（已实测）。
     */
    fun closePackage(packageName: String, userId: Int = 0): Boolean {
        val taskIds = TaskInspector.listTasks()
            .filter { it.packageName == packageName && (userId == 0 || it.userId == userId) }
            .map { it.taskId }
        val result = ShellRunner.exec("am force-stop ${if (userId != 0) "--user $userId " else ""}$packageName")
        var removed = 0
        for (id in taskIds) {
            val r = ShellRunner.exec("am stack remove $id")
            if (r.contains("Error") || r.contains("Exception")) {
                Diag.log(TAG, "摘后台卡片失败：am stack remove $id -> ${r.firstLine()}")
            } else {
                removed++
            }
        }
        Diag.log(
            TAG,
            "关闭 $packageName（用户 $userId）：force-stop ok=${result.ok}，摘掉后台卡片 $removed/${taskIds.size}"
        )
        return result.ok
    }

    // ------------------------------------------------------------------ 工具

    private fun expandClassName(pkg: String, cls: String): String = when {
        cls.startsWith(".") -> pkg + cls
        cls.contains('.') -> cls
        else -> "$pkg.$cls"
    }

    private fun boundsClose(actual: Bounds?, expected: Bounds, tolerance: Int = 24): Boolean {
        val a = actual ?: return false
        return kotlin.math.abs(a.left - expected.left) <= tolerance &&
            kotlin.math.abs(a.top - expected.top) <= tolerance &&
            kotlin.math.abs(a.right - expected.right) <= tolerance &&
            kotlin.math.abs(a.bottom - expected.bottom) <= tolerance
    }

    internal fun Bounds.toRect(): Rect = Rect(left, top, right, bottom)
}
