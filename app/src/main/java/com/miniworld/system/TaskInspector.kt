package com.miniworld.system

import android.util.Log
import com.miniworld.model.Bounds
import com.miniworld.shizuku.ShellRunner

private val USER_IN_HEADER = Regex("""\sU=(\d+)""")

/** 从 dumpsys 里解析出来的一个任务。字段允许缺失——不同 ROM 的排版差别很大。 */
data class TaskInfo(
    val taskId: Int,
    val packageName: String,
    val activity: String,
    val windowingModeRaw: String,
    val isFreeform: Boolean,
    val visible: Boolean,
    val bounds: Bounds?,
    val header: String
) {
    /** 任务头里的 U=0 / U=999：0=主系统，999=手机分身(XSpace)。区分同名包的两份实例全靠它。 */
    val userId: Int
        get() = USER_IN_HEADER.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    override fun toString(): String =
        "#$taskId $packageName mode=$windowingModeRaw visible=$visible bounds=${bounds ?: "?"}"
}

/**
 * 任务列表查询。
 *
 * 设计原则：**解析失败 ≠ 命令失败**。前者返回空列表并记日志，后者由 ShellResult 表达。
 * 混在一起会让"ROM 换了排版"被误报成"这台手机不支持"，进而把用户引到错误的降级路线上。
 */
object TaskInspector {

    private const val TAG = "TaskInspector"

    /**
     * 任务头必须出现在行首（允许前面的 `* ` 前缀）。
     *
     * 不能只用 `Task\{...}` 做子串匹配：实测 dump 里任务块内部会出现
     * `rootOfTask=true task=Task{618a9ed #91 ...}`、`(freeform) Task{...}` 这类"引用行"，
     * 一旦把它们当成新任务头，当前块会被就地截断，块后面的 `mBounds`/`topResumedActivity`
     * 就再也读不到——表现为"任务在，但坐标是空的"，最后被误判成 resize 失败。
     */
    /**
     * Android 的 Regex 走 ICU，规则比 JVM 的 java.util.regex 严：
     * `...}` 这种"结尾一个没转义的花括号"在 JVM 能编译，在真机上抛 PatternSyntaxException；
     * 而这几条正则是在 object 的静态初始化里编译的 → 整个 App 直接闪退（真机已复现）。
     * 所以：① 花括号一律转义；② 统一走 rx() 编译，单条正则坏掉只让该条永不匹配，不拖垮 App。
     * 注意 JVM 单测**测不出**这一类问题（JVM 更宽松），必须真机跑一遍。
     */
    private fun rx(pattern: String): Regex =
        runCatching { Regex(pattern) }
            .onFailure { Log.e(TAG, "正则编译失败（Android=ICU，比 JVM 严）：$pattern -> ${it.message}") }
            .getOrElse { Regex("""\b\B""") } // 词边界+非词边界：永不可能匹配

    private val TASK_HEADER = rx("""^\*?\s*Task\{[^}]*#(\d+)[^}]*\}""")

    private fun headerAt(line: String): MatchResult? = TASK_HEADER.find(line.trimStart())
    private val PKG = rx("""A=(\d+):([A-Za-z0-9_.]+)""")

    /**
     * 兜底：桌面/系统任务的任务头只有基础 Intent `I=包名/类名`，没有 `A=uid:包名`。
     * 实测（Android 17 / HyperOS 4）`* Task{5568aa7 #2 type=home I=com.miui.home/.launcher.Launcher ...}` 就是这种。
     * 不加这条，`find("com.miui.home")` 会返回 null，看起来像"系统不支持"。
     */
    private val PKG_FROM_INTENT = rx("""I=([A-Za-z0-9_.]+)/""")
    private val MODE_WORD = rx("""(?:windowingMode|mode)=([A-Za-z0-9_]+)""")
    private val BOUNDS = rx("""mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""")
    private val ACTIVITY = rx("""ActivityRecord\{[^}]*\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")

    fun dump(): String = ShellRunner.exec("dumpsys activity activities", 15_000).stdout

    fun listTasks(): List<TaskInfo> = listTasksOrNull() ?: emptyList()

    /**
     * 同 [listTasks]，但区分"确实没有任务"与"这条命令没跑成"。
     *
     * 为什么必须区分：收工时服务会把自己停掉，`dumpsys` 有可能被打断（真机事故：
     * `InterruptedException` → stdout 为空 → 解析出 0 个任务 → 后台卡片"摘掉 0/0"，
     * 卡片留在后台）。返回 null 让调用方有机会退回"启动时记下的任务号"，
     * 而不是把"没查到"误当成"没有卡片"。
     */
    fun listTasksOrNull(): List<TaskInfo>? {
        val result = ShellRunner.exec("dumpsys activity activities", 15_000)
        if (!result.ok) {
            Log.w(TAG, "任务列表查询失败：${result.firstLine()}")
            return null
        }
        return parse(result.stdout)
    }

    /**
     * 按「包名 + Android 用户号」找任务。
     *
     * userId **必填**：同一个包在主机和手机分身上各有一份时包名完全一样，只按包名找
     * 会随机命中另一份（真机事故：关闭 nrc 时主机与分身两份一起被杀）。确实不关心用户时，
     * 也要显式写出你要的那一个，不要退化成"随便一个"。
     */
    fun find(packageName: String, userId: Int): TaskInfo? =
        listTasks().firstOrNull { it.packageName == packageName && it.userId == userId }

    fun parse(dump: String): List<TaskInfo> {
        if (dump.isBlank()) return emptyList()
        val lines = dump.lineSequence().toList()
        val result = ArrayList<TaskInfo>()

        var i = 0
        while (i < lines.size) {
            val headerLine = lines[i]
            val headerMatch = headerAt(headerLine)
            if (headerMatch == null) {
                i++
                continue
            }
            val taskId = headerMatch.groupValues[1].toIntOrNull()
            if (taskId == null) {
                i++
                continue
            }

            var pkg = PKG.find(headerLine)?.groupValues?.get(2).orEmpty()
            if (pkg.isEmpty()) {
                pkg = PKG_FROM_INTENT.find(headerLine)?.groupValues?.get(1).orEmpty()
            }
            var modeWord = MODE_WORD.find(headerLine)?.groupValues?.get(1).orEmpty()
            var bounds: Bounds? = null
            var visible = headerLine.contains("visible=true")
            var activity = ""
            // 当前正在显示的 Activity 优先。实测 dump 里 `mLastPausedActivity` 出现在 `topResumedActivity` 之前，
            // 直接取第一个 ActivityRecord 会拿到已经暂停的旧页面（例如游戏的 MSDK 政策页而不是 GameActivity）。
            var topActivity = ""

            var j = i + 1
            while (j < lines.size && headerAt(lines[j]) == null) {
                val line = lines[j]
                if (pkg.isEmpty()) {
                    PKG.find(line)?.let { pkg = it.groupValues[2] }
                    if (pkg.isEmpty()) {
                        PKG_FROM_INTENT.find(line)?.let { pkg = it.groupValues[1] }
                    }
                }
                if (modeWord.isEmpty()) {
                    MODE_WORD.find(line)?.let { modeWord = it.groupValues[1] }
                }
                if (bounds == null) {
                    BOUNDS.find(line)?.let { b ->
                        bounds = Bounds(
                            b.groupValues[1].toInt(),
                            b.groupValues[2].toInt(),
                            b.groupValues[3].toInt(),
                            b.groupValues[4].toInt()
                        )
                    }
                }
                if (!visible) visible = line.contains("visible=true")
                if (topActivity.isEmpty() && line.contains("topResumedActivity")) {
                    ACTIVITY.find(line)?.let {
                        topActivity = "${it.groupValues[1]}/${it.groupValues[2]}"
                    }
                } else if (activity.isEmpty()) {
                    ACTIVITY.find(line)?.let {
                        activity = "${it.groupValues[1]}/${it.groupValues[2]}"
                    }
                }
                j++
            }

            val modeNumber = modeWord.toIntOrNull() ?: -1
            val isFreeform = modeWord.contains("freeform", ignoreCase = true) || modeNumber == 5

            result += TaskInfo(
                taskId = taskId,
                packageName = pkg,
                activity = topActivity.ifEmpty { activity },
                windowingModeRaw = if (modeWord.isEmpty()) "unknown" else modeWord,
                isFreeform = isFreeform,
                visible = visible,
                bounds = bounds,
                header = headerLine.trim()
            )
            i = j
        }

        if (result.isEmpty()) {
            Log.w(TAG, "dumpsys 解析出 0 个任务，可能是 ROM 排版差异；原始长度=${dump.length}")
        }
        return result
    }

    /**
     * 顶层焦点任务（`topDisplayFocusedRootTask=Task{...}`）。
     *
     * 这台 ROM（HyperOS 4 / Android 17）会把受管的 2×2 自由窗口顶成它 —— 这正是用户看到的
     * "浮窗自己弹出来 + 变形 + 收不回"的根因。实测坏状态下**任务矩形和窗口 `Requested w=2 h=2`
     * 全都是对的**，所以任何比几何的判据都判不出来；"谁拿着焦点"是唯一可靠信号。
     */
    private val FOCUSED_ROOT_TASK = rx("""topDisplayFocusedRootTask=Task\{[^}]*\}""")

    /** 任务头里的 `#123`。 */
    private val TASK_ID_IN_HEADER = rx("""#(\d+)\b""")

    /**
     * `topResumedActivity=ActivityRecord{... t123}` —— 尾部 `t<任务号>` 就是"用户此刻在看的任务"。
     * dump 里这条会出现多次（任务块内部也有一份），**显示级那条在最后**，所以要取最后一个匹配。
     */
    private val RESUMED_TASK = rx("""topResumedActivity=ActivityRecord\{[^}]*\bt(\d+)\}""")

    /** 一次 dump 就能拿到的"谁拿着焦点 / 用户在看的任务"。 */
    data class FocusSnapshot(
        val focusedTaskId: Int?,
        val focusedPackage: String?,
        val resumedTaskId: Int?
    )

    /**
     * 解析"焦点归属"快照。
     *
     * @param dump 已经取到的 `dumpsys activity activities` 文本；默认自己取一份。
     */
    fun focusSnapshot(dump: String = dump()): FocusSnapshot {
        val header = FOCUSED_ROOT_TASK.find(dump)?.value
        var pkg: String? = null
        if (header != null) {
            pkg = PKG.find(header)?.groupValues?.get(2)
            if (pkg.isNullOrEmpty()) pkg = PKG_FROM_INTENT.find(header)?.groupValues?.get(1)
            if (pkg.isNullOrEmpty()) pkg = null
        }
        return FocusSnapshot(
            focusedTaskId = header
                ?.let { TASK_ID_IN_HEADER.find(it)?.groupValues?.get(1)?.toIntOrNull() },
            focusedPackage = pkg,
            resumedTaskId = RESUMED_TASK.findAll(dump).lastOrNull()
                ?.groupValues?.get(1)?.toIntOrNull()
        )
    }

    /** 轮询等待某个包在**指定用户下**的任务出现。返回 null 表示超时。 */
    fun waitForTask(
        packageName: String,
        userId: Int,
        timeoutMs: Long = 3_000,
        intervalMs: Long = 250
    ): TaskInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = find(packageName, userId)
            if (task != null) return task
            Thread.sleep(intervalMs)
        }
        return null
    }
}
