package com.onestep.workbench.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.onestep.workbench.data.SceneStore
import com.onestep.workbench.shizuku.ShizukuGateway

/**
 * 开机 / 应用更新后的自检。
 *
 * 只做两件事：
 *  1. 刷新 Shizuku 状态（服务重启后 binder 会变，状态必须重算）
 *  2. 如果当前场景开了"开机自动开工"，把服务拉起来
 *
 * **不静默改写系统设置**：enable_freeform_support 被 ROM 重置时，
 * 用户会在 App 里看到明确提示，而不是某天发现手机被悄悄改了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        com.onestep.workbench.system.Diag.log("BootReceiver", "收到广播 action=" + intent.action)
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "收到 $action，开始自检")
        ShizukuGateway.refresh(context.applicationContext as? android.app.Application)

        val store = SceneStore(context)
        val scene = store.activeScene()
        if (scene?.autoStart == true && action == "com.onestep.workbench.AUTO_START_DISABLED") { // 用户要求：只有点「运行」才启动
            Log.i(TAG, "场景「${scene.name}」已开启开机自动开工")
            WorkbenchService.send(context, WorkbenchService.ACTION_START_SCENE, scene.id)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
