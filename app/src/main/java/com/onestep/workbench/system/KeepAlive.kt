package com.onestep.workbench.system

import android.util.Log
import com.onestep.workbench.shizuku.ShellResult
import com.onestep.workbench.shizuku.ShellRunner

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

    private fun enableSteps(pkg: String): List<Step> = listOf(
        Step("deviceidle", "加入电池优化白名单", "cmd deviceidle whitelist +$pkg"),
        Step("inactive", "标记为活跃应用", "am set-inactive $pkg false"),
        Step("bucket", "待机桶 → active", "am set-standby-bucket $pkg active"),
        Step("runAny", "允许后台运行（RUN_ANY_IN_BACKGROUND）", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"),
        Step("runBg", "允许后台运行（RUN_IN_BACKGROUND）", "cmd appops set $pkg RUN_IN_BACKGROUND allow")
    )

    private fun disableSteps(pkg: String): List<Step> = listOf(
        Step("deviceidle", "移出电池优化白名单", "cmd deviceidle whitelist -$pkg"),
        Step("inactive", "清除活跃标记", "am set-inactive $pkg true"),
        Step("bucket", "待机桶恢复默认", "am set-standby-bucket $pkg default"),
        Step("runAny", "撤销后台运行（RUN_ANY_IN_BACKGROUND）", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND default"),
        Step("runBg", "撤销后台运行（RUN_IN_BACKGROUND）", "cmd appops set $pkg RUN_IN_BACKGROUND default")
    )

    data class Applied(val step: Step, val result: ShellResult, val success: Boolean)

    fun apply(pkg: String, enable: Boolean): List<Applied> {
        val steps = if (enable) enableSteps(pkg) else disableSteps(pkg)
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

    /** 只读审计，用于 Dock 长按复查与「收工后复查」。 */
    fun audit(pkg: String): Audit {
        val whitelist = ShellRunner.exec("dumpsys deviceidle whitelist").stdout
        val runAny = ShellRunner.exec("cmd appops get $pkg RUN_ANY_IN_BACKGROUND").stdout
        val bucket = ShellRunner.exec("am get-standby-bucket $pkg").stdout
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
