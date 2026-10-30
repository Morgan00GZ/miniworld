package com.miniworld.system

import com.miniworld.shizuku.ShellRunner

/**
 * 主页「自检」面板的一屏数据。
 *
 * 全部只读：只跑查询命令，改不了任何状态，所以可以随时点、随便点。
 * 以前排障要「连电脑 + adb + 读 512 KB 的 diag.log」，现在手机点一下、几秒出结果。
 */
data class SelfCheckReport(
    /** Shizuku 状态（由 UI 侧传入，避免这里再依赖 ShizukuGateway）。 */
    val shizuku: String,
    /** `am get-current-user`：0 = 主系统，999 = 小米手机分身。 */
    val currentUser: Int,
    /** `settings get global cached_apps_freezer`：开了就会冻后台进程。 */
    val freezer: String,
    /** `settings get global enable_freeform_support`：自由窗口总开关。 */
    val freeformEnabled: String,
    /** 当前挂机应用的保活四项，见 [KeepAlive.audit]。 */
    val keepAlive: String,
    val dumpCount: Int,
    val corrections: Int,
    val dumpBytesP50: Long,
    val dumpBytesP95: Long,
    val dumpMillisP50: Long,
    val dumpMillisP95: Long
)

object SelfCheck {

    /**
     * 采集一次。**会跑 4~8 条 shell 命令**，必须在后台线程调用。
     *
     * @param pkg 当前挂机应用；为空时跳过保活审计（没选应用就没有可查的）。
     * @param userId 0 = 主系统，999 = 手机分身。查保活必须带上，否则查的是另一份。
     */
    fun collect(shizuku: String, pkg: String?, userId: Int): SelfCheckReport {
        val currentUser = ShellRunner.exec("am get-current-user").stdout.trim().toIntOrNull() ?: -1
        val freezer = ShellRunner.exec("settings get global cached_apps_freezer").stdout.trim()
            .ifEmpty { "读不到" }
        val freeform = ShellRunner.exec("settings get global enable_freeform_support").stdout.trim()
            .ifEmpty { "读不到" }
        val keepAlive = if (pkg.isNullOrBlank()) "未选择应用" else KeepAlive.audit(pkg, userId).summary

        val samples = Metrics.snapshot()
        val bytes = samples.map { it.bytes.toLong() }
        val millis = samples.map { it.millis }

        return SelfCheckReport(
            shizuku = shizuku,
            currentUser = currentUser,
            freezer = freezer,
            freeformEnabled = freeform,
            keepAlive = keepAlive,
            dumpCount = Metrics.dumpCount,
            corrections = Metrics.correctionCount,
            dumpBytesP50 = Metrics.p50(bytes),
            dumpBytesP95 = Metrics.p95(bytes),
            dumpMillisP50 = Metrics.p50(millis),
            dumpMillisP95 = Metrics.p95(millis)
        )
    }
}