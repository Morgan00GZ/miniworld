package com.miniworld.model

/**
 * 「包名 + Android 用户号」—— 须弥芥子里唯一定位一个**应用实例**的键。
 *
 * 小米「手机分身」(XSpace) 里的应用与主系统里的那一份**包名完全相同**，只有用户号不同
 * （0 = 主系统，999 = 分身）。所以任何"对某个应用下手"的动作（启动 / 关闭 / 保活 / 找任务）
 * 都必须带着这一对；只带包名就会命中另一份。
 *
 * 真机事故（用户 2026-10-13 反馈）：点「关闭」时 nrc 的主机版和分身版**一起被关掉** ——
 * 根因就是关闭路径只传了包名，命令退化成不带 `--user` 的 `am force-stop`。
 */
data class AppTarget(val packageName: String, val userId: Int) {
    /** 去重用的稳定键。 */
    val key: String get() = "$packageName@$userId"

    /** 日志里给用户看的名字：分身那一份要标出来。 */
    val label: String get() = if (userId == 0) packageName else "$packageName（分身 $userId）"
}

/**
 * 「关闭 / 保活」要处理的目标清单：运行中的窗口 + 场景槽位，按 (包名, 用户号) 去重，保持出现顺序。
 *
 * 纯函数，JVM 单测直接覆盖：这里错一次，用户看到的就是"关一个应用，两份一起关"。
 * 注意**不要去重包名** —— 同名包的两个用户号是两个独立实例，都必须保留。
 */
fun appTargets(windows: List<LiveWindow>, slots: List<Slot>): List<AppTarget> {
    val seen = LinkedHashMap<String, AppTarget>()
    fun add(packageName: String, userId: Int) {
        val target = AppTarget(packageName, userId)
        seen.getOrPut(target.key) { target }
    }
    windows.forEach { add(it.packageName, it.userId) }
    slots.forEach { add(it.packageName, it.userId) }
    return seen.values.toList()
}

/**
 * 启动时给某个目标记下的任务号 —— 收工摘后台卡片时要用。
 *
 * 为什么要它兜底：真机事故（2026-10-13）收工把工作线程打断，现场 `dumpsys` 抛
 * `InterruptedException` 返回空，卡片"摘掉 0/0"，用户看到后台卡片还在。
 * 关闭时优先用现场查到的最新结果（最准），只有现场查**失败**才退回这里。
 */
fun taskIdsOf(target: AppTarget, windows: List<LiveWindow>): List<Int> =
    windows.filter { it.packageName == target.packageName && it.userId == target.userId }
        .map { it.taskId }
        .distinct()
