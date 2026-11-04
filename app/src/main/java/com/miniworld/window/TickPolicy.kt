package com.miniworld.window

/**
 * 看门狗的节拍策略（用户 m02893 选定的档位：**熄屏 20 s，亮屏不变 2 s**）。
 *
 * 为什么要有它：看门狗是运行期唯一还在周期性干活的东西 —— 每拍一条
 * `dumpsys activity activities`（真机实测约 0.01 s、72 KB 输出，还要经 Shizuku 起一个 `sh`）。
 * 挂机时用户根本不看屏幕，熄屏后还每 2 秒问一次系统纯属浪费；
 * 但也不能停 —— "窗口被系统搬走 / 进程被冻结"的兜底必须留着，所以只是**降频**，不是停拍。
 *
 * 这里刻意做成纯函数（不碰 Android 类型），每一档都能在 JVM 单测里钉死。
 */
object TickPolicy {

    /** 亮屏节拍：与 1.11 完全一致 —— 亮屏时用户看到的行为一点都没变。 */
    const val TICK_MS = 2_000L

    /**
     * 亮屏但**没在动窗口**时的节拍：5 s 一拍（1.2 起）。
     *
     * 2 s 那一档原本是为了"刚开工那十几秒窗口在系统手里乱跳"；稳定挂机时其实用不上，
     * 而每拍都要过一份 108 KB 的 `dumpsys`（实测 70 ms + 一个 `sh` 进程），
     * 亮屏 2 s 一拍相当于每分钟经手 3.2 MB 文本。所以：刚动过窗口/焦点 → 2 s（`FAST_HOLD_MS` 内），
     * 稳定之后 → 5 s。代价是"任务被划掉"这类事件的发现延迟从约 6 s 变成最多约 15 s（3 拍），可接受。
     */
    const val TICK_MS_IDLE = 5_000L

    /** 熄屏节拍：20 s 一拍（兜底最长也就晚 20 s 补上）。 */
    const val TICK_MS_SCREEN_OFF = 20_000L

    /**
     * 刚校正过窗口 / 刚交还过焦点之后，回到快档保持这么久。
     * 防的是"熄屏时刚发出校正，却没盯着它落位就等 20 s"——`resize` 在这台 ROM 上
     * 本来就要"先小后大"两步才生效，落位确认必须跟得上。
     */
    const val FAST_HOLD_MS = 10_000L

    /** 开工后第一拍的延迟：给系统留出把窗口拉起来的时间（沿用 1.11 的 6 s）。 */
    const val START_DELAY_MS = 6_000L

    /** 心跳日志间隔：按**时间**算而不是按拍数，两种节拍下都是 5 分钟一条。 */
    const val HEARTBEAT_MS = 5 * 60 * 1000L

    /**
     * 闹钟兜底间隔：每 10 分钟给自己投一个"还活着吗"的系统闹钟。
     *
     * 真机 2026-10-13 复现过**整进程被 ROM 挂起 13.5 分钟**：那种情况下进程内的巡检线程
     * （watchdogGuard）一起被冻住，谁也救不了自己。系统为投递闹钟会主动唤醒进程，
     * `setAndAllowWhileIdle` 又不需要任何新权限 —— 这是唯一还能从进程外把我们叫醒的通道。
     */
    const val ALARM_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 下一拍该等多久：
     *  - `fastUntil` 还没到 → 快档 2 s（刚动过窗口，要盯着它落位）；
     *  - 熄屏且没在动 → 慢档 20 s（用户根本不看屏幕）；
     *  - 亮屏且没在动 → 空闲档 5 s（1.2 起；稳定挂机不需要 2 s 的精度）。
     */
    fun intervalMs(screenOn: Boolean, fastUntil: Long, now: Long): Long {
        val fast = now < fastUntil
        if (!screenOn) return if (fast) TICK_MS else TICK_MS_SCREEN_OFF
        return if (fast) TICK_MS else TICK_MS_IDLE
    }

    /**
     * 漏拍判据：超过"两拍 + 5 秒"还没跳，就认为节拍链断了、或者被系统压住了。
     * 真机 2026-10-13 实测过一次 **13.5 分钟零拍**：进程没死、服务没重启、窗口也没被回收，
     * 但消息循环整段没跑（拉回前台后积压的消息一次性补跑）。有这条判据，"看门狗还活着吗"
     * 才在日志里看得见，也能自己补一拍回来。
     */
    fun stallThresholdMs(intervalMs: Long): Long = intervalMs * 2 + 5_000L

    fun isStalled(now: Long, lastTickAt: Long, intervalMs: Long): Boolean =
        now - lastTickAt > stallThresholdMs(intervalMs)
}
