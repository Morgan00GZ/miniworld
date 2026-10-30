package com.miniworld.system

import com.miniworld.shizuku.ShellRunner

/**
 * 主页「自检」面板的一屏数据。
 *
 * 全部只读：只跑查询命令，改不了任何状态，所以可以随时点、随便点。
 * 以前排障要「连电脑 + adb + 读 512 KB 的 diag.log」，现在手机点一下、几秒出结果。
 *
 * 字段刻意都是**短句/短值**：面板塞在一屏里，一行太长就会折行把后面的挤出去。
 * 所以 appops 那条带时间戳的长输出、待机桶的数字，都在 [SelfCheck] 里先翻成人话。
 */
data class SelfCheckReport(
    /** Shizuku 状态（由 UI 侧传入，避免这里再依赖 ShizukuGateway）。 */
    val shizuku: String,
    /** `am get-current-user`：shell 自己被放在哪个用户下跑（通常 0）。 */
    val shellUser: Int,
    /** 当前场景里挂机应用的用户号：0 = 主系统，999 = 小米手机分身。 */
    val appUser: Int,
    val whitelisted: Boolean,
    /** RUN_ANY_IN_BACKGROUND 的当前值，已翻成人话。 */
    val runAny: String,
    /** 待机桶，已翻成人话（5 = 豁免）。 */
    val bucket: String,
    /** `settings get global cached_apps_freezer`：开了就会冻后台进程。 */
    val freezer: String,
    /** `settings get global enable_freeform_support`。本机是 0，但自由窗口靠 shell 起，不受它影响。 */
    val freeformEnabled: String,
    val dumpCount: Int,
    val corrections: Int,
    val dumpBytesP50: Long,
    val dumpBytesP95: Long,
    val dumpMillisP50: Long,
    val dumpMillisP95: Long
)

object SelfCheck {

    /**
     * 采集一次。**会跑 5~9 条 shell 命令**，必须在后台线程调用。
     *
     * @param pkg 当前挂机应用；为空时跳过保活审计（没选应用就没有可查的）。
     * @param userId 0 = 主系统，999 = 手机分身。查保活必须带上，否则查到的是另一份。
     */
    fun collect(shizuku: String, pkg: String?, userId: Int): SelfCheckReport {
        val shellUser = ShellRunner.exec("am get-current-user").stdout.trim().toIntOrNull() ?: -1
        val freezer = ShellRunner.exec("settings get global cached_apps_freezer").stdout.trim()
            .ifEmpty { "读不到" }
        val freeform = ShellRunner.exec("settings get global enable_freeform_support").stdout.trim()
            .ifEmpty { "读不到" }
        val audit = if (pkg.isNullOrBlank()) null else KeepAlive.audit(pkg, userId)

        val samples = Metrics.snapshot()
        val bytes = samples.map { it.bytes.toLong() }
        val millis = samples.map { it.millis }

        return SelfCheckReport(
            shizuku = shizuku,
            shellUser = shellUser,
            appUser = userId,
            whitelisted = audit?.inDeviceIdleWhitelist ?: false,
            runAny = if (audit == null) "未选择应用" else appOpWord(audit.runAnyInBackground),
            bucket = if (audit == null) "未选择应用" else bucketWord(audit.standbyBucket),
            freezer = freezer,
            freeformEnabled = freeform,
            dumpCount = Metrics.dumpCount,
            corrections = Metrics.correctionCount,
            dumpBytesP50 = Metrics.p50(bytes),
            dumpBytesP95 = Metrics.p95(bytes),
            dumpMillisP50 = Metrics.p50(millis),
            dumpMillisP95 = Metrics.p95(millis)
        )
    }

    /**
     * `cmd appops get … RUN_ANY_IN_BACKGROUND` 的原始输出长这样：
     * `RUN_ANY_IN_BACKGROUND: default; time=+77d15h47m41s188ms ago`
     *
     * 那个 `time=` 是"上次被修改的时间"，对判断"能不能在后台跑"毫无用处、
     * 还特别长（会把整行撑到折三四行），所以只取冒号后、分号前的模式词再翻人话。
     */
    internal fun appOpWord(raw: String): String {
        val v = raw.substringAfter(':', "").substringBefore(';').trim()
        return when (v) {
            "" -> "读不到"
            "allow" -> "允许"
            "default" -> "未允许（default）"
            "ignore" -> "忽略（ignore）"
            "deny" -> "禁止（deny）"
            "foreground" -> "仅前台（foreground）"
            else -> v
        }
    }

    /**
     * `am get-standby-bucket` 返回数字：5 豁免 / 10 活跃 / 20 工作中 / 30 常用 / 40 罕见 / 45 受限。
     * 数字对人没意义，翻译一下；未知值原样返回——哪天 ROM 换了取值，至少要看得见。
     */
    internal fun bucketWord(raw: String): String = when (raw.trim()) {
        "" -> "读不到"
        "5" -> "5 豁免"
        "10" -> "10 活跃"
        "20" -> "20 工作中"
        "30" -> "30 常用"
        "40" -> "40 罕见"
        "45" -> "45 受限"
        else -> raw.trim()
    }
}