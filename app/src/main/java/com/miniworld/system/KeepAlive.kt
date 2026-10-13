package com.miniworld.system

import android.util.Log
import com.miniworld.shizuku.ShellResult
import com.miniworld.shizuku.ShellRunner

/**
 * 挂机保活。
 *
 * 目标很具体：让"挂机游戏"在锁屏/切走后不被系统冻结、不掉线。
 * 手段全部来自 shell，幂等、可撤销——「收工」时逐条还原，不在用户手机上留副作用。
 *
 * 只下发"几乎一定有用、且影响面可控"的几条：白名单 / 活跃标记 / 待机桶 / 两个后台运行 appop。
 * 唤醒锁与 MIUI 自启动这类影响面更大的开关**不做**——用户要的是挂机不被冻结，
 * 不是把整机省电策略关掉（也避免"某天发现手机被悄悄改了"）。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    data class Step(val key: String, val label: String, val command: String)

    /**
     * 保活命令：**全部按 Android 用户号下发**。
     *
     * 原因和关闭一样：手机分身里的应用与主系统里的那份包名相同，不带 `--user` 时命令落在
     * "当前用户"上，分身那一份就完全没被保活（而用户以为自己两份都挂上了）。
     *
     * 例外是 `cmd deviceidle whitelist` —— 电池优化白名单是全机的，命令本身没有 `--user` 维度。
     */
    private fun enableSteps(pkg: String, userId: Int): List<Step> = listOf(
        Step("deviceidle", "加入电池优化白名单", "cmd deviceidle whitelist +$pkg"),
        Step("inactive", "标记为活跃应用", "am set-inactive --user $userId $pkg false"),
        Step("bucket", "待机桶 → active", "am set-standby-bucket --user $userId $pkg active"),
        Step("runAny", "允许后台运行（RUN_ANY_IN_BACKGROUND）", "cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND allow"),
        Step("runBg", "允许后台运行（RUN_IN_BACKGROUND）", "cmd appops set --user $userId $pkg RUN_IN_BACKGROUND allow")
    )

    private fun disableSteps(pkg: String, userId: Int): List<Step> = listOf(
        Step("deviceidle", "移出电池优化白名单", "cmd deviceidle whitelist -$pkg"),
        Step("inactive", "清除活跃标记", "am set-inactive --user $userId $pkg true"),
        // 故意**没有**"待机桶恢复默认"这一步：这台 ROM 的 `am set-standby-bucket` 只认
        // active|working_set|frequent|rare|restricted，没有 `default`
        // （实测 exit=255 "Unknown bucket: default"），而硬塞一个 `rare` 等于主动把 App 降级。
        // 系统没提供"恢复默认"，就不假装能恢复：保活时设的 active 由系统按使用情况自己回收。
        Step("runAny", "撤销后台运行（RUN_ANY_IN_BACKGROUND）", "cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND default"),
        Step("runBg", "撤销后台运行（RUN_IN_BACKGROUND）", "cmd appops set --user $userId $pkg RUN_IN_BACKGROUND default")
    )

    data class Applied(val step: Step, val result: ShellResult, val success: Boolean)

    fun apply(pkg: String, userId: Int, enable: Boolean): List<Applied> {
        val steps = if (enable) enableSteps(pkg, userId) else disableSteps(pkg, userId)
        return steps.map { step ->
            val result = ShellRunner.exec(step.command)
            if (!result.ok) {
                Log.w(TAG, "保活步骤失败：${step.label} -> ${result.describe()}")
            }
            Applied(step, result, result.ok)
        }
    }

    data class Audit(
        val inDeviceIdleWhitelist: Boolean,
        val runAnyInBackground: String,
        val standbyBucket: String,
        val cachedAppsFreezer: String,
        val raw: Map<String, String>
    ) {
        /**
         * 关于 cached_apps_freezer：**故意不提供开关**。
         * 它是全局设置，关掉会影响整机所有应用的省电表现，代价和收益不成比例。
         * 这里只把它读出来给用户看，让用户知道"系统有没有在冻结后台"。
         */
        val summary: String
            get() = buildString {
                append(if (inDeviceIdleWhitelist) "白名单 ✓" else "白名单 ✗")
                append(" · 后台运行=").append(runAnyInBackground.ifBlank { "?" })
                append(" · 待机桶=").append(standbyBucket.ifBlank { "?" })
                append(" · 冻结器=").append(cachedAppsFreezer.ifBlank { "?" })
            }
    }

    /** 只读审计，用于 Dock 长按复查与「收工后复查」。按用户号读——分身那份和主机那份是两套状态。 */
    fun audit(pkg: String, userId: Int): Audit {
        val whitelist = ShellRunner.exec("dumpsys deviceidle whitelist").stdout
        val runAny = ShellRunner.exec("cmd appops get --user $userId $pkg RUN_ANY_IN_BACKGROUND").stdout
        val bucket = ShellRunner.exec("am get-standby-bucket --user $userId $pkg").stdout
        val freezer = ShellRunner.exec("settings get global cached_apps_freezer").stdout

        return Audit(
            inDeviceIdleWhitelist = whitelist.contains(pkg),
            runAnyInBackground = runAny.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
            standbyBucket = bucket.trim(),
            cachedAppsFreezer = freezer.trim(),
            raw = mapOf(
                "deviceidle" to whitelist,
                "appops" to runAny,
                "bucket" to bucket,
                "freezer" to freezer
            )
        )
    }
}
