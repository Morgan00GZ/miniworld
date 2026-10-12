package com.miniworld.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.miniworld.shizuku.ShizukuGateway

/**
 * 开机 / 应用更新后刷新一次 Shizuku 状态。
 *
 * 只做一件事：重算授权与连接状态（服务重启后 binder 会变，状态必须重算）。
 * 打开 App 时才重新渲染界面，所以这里不需要弹任何东西。
 *
 * **不静默改写系统设置**：enable_freeform_support 被 ROM 重置时，
 * 用户会在 App 里看到明确提示，而不是某天发现手机被悄悄改了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        com.miniworld.system.Diag.log("BootReceiver", "收到广播 action=" + intent.action)
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "收到 $action，刷新 Shizuku 状态")
        ShizukuGateway.refresh(context.applicationContext as? android.app.Application)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
