package com.onestep.workbench

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.onestep.workbench.apps.AppCatalog
import com.onestep.workbench.data.SceneStore
import com.onestep.workbench.model.LayoutPreset
import com.onestep.workbench.model.Slot
import com.onestep.workbench.service.WorkbenchService
import com.onestep.workbench.system.Diag

import com.onestep.workbench.ui.WorkbenchRoot
import kotlinx.coroutines.launch

/**
 * 唯一的一屏 UI：三个页签（自检 / 场景 / 运行）。
 * 真正的逻辑都在 system/、window/、service/ 里，这里只负责把状态摆出来。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = SceneStore(this)
        val catalog = AppCatalog(this)

        handleDebugIntent()

        // 打开工作台即自动开工（用户要求：不必在横屏游戏里手点"开工"）。
        // 是否自动，交给场景里的「开机自动开工」开关，用户可自己关。
        if (incomingAutoStart()) {
            SceneStore(this).activeScene()?.let { scene ->
                Diag.log(TAG, "打开即开工：${scene.name}")
                WorkbenchService.send(this, WorkbenchService.ACTION_START_SCENE, scene.id)
                moveTaskToBack(true)
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WorkbenchRoot(activity = this, store = store, catalog = catalog)
                }
            }
        }
    }

    /**
     * 调试旁路：电脑上一条命令就能触发，用户不用在手机上点任何东西。
     *
     * ```
     * adb shell am force-stop com.onestep.workbench
     * adb shell am start -n com.onestep.workbench/.MainActivity --ez diag_probe true
     * adb shell am start -n com.onestep.workbench/.MainActivity --ez auto_start true
     * ```
     *
     *  - `diag_probe`：跑一遍最原始的 shell 命令 + 完整自检，把**原文**写进
     *    `/sdcard/Android/data/com.onestep.workbench/files/diag.log`，用 `adb shell cat` 读走。
     *  - `auto_start`：等价于用户点「开工」，按当前活动场景启动工作台。
     *
     * 加 `force-stop` 是为了让 Activity 重新走 onCreate（否则 launchMode=singleTask 下会走
     * onNewIntent，这里不处理，调试入口不会执行）。
     */
    /** 打开 App 时是否自动开工：看活动场景的「开机自动开工」开关。 */
    private fun incomingAutoStart(): Boolean = false // 用户要求：必须点图标条上的「运行」才启动

    private fun handleDebugIntent() {
        // v3：调试入口已移除
    }

    /**
     * 已存在的 Activity 收到新 Intent 时（`am start` 最常见的路径）也要处理调试旁路：
     * 实测 `am force-stop` 之后 `am start` 常常只是"把已有任务拉到前台"，
     * 这时走的是 onNewIntent 而不是 onCreate，调试入口会静默失效。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent()

        // 打开工作台即自动开工（用户要求：不必在横屏游戏里手点"开工"）。
        // 是否自动，交给场景里的「开机自动开工」开关，用户可自己关。
        if (incomingAutoStart()) {
            SceneStore(this).activeScene()?.let { scene ->
                Diag.log(TAG, "打开即开工：${scene.name}")
                WorkbenchService.send(this, WorkbenchService.ACTION_START_SCENE, scene.id)
                moveTaskToBack(true)
            }
        }    }

    /**
     * 用 App 自己的代码路径建/更新一个场景——手写 SharedPreferences 的 XML 会被解析成空（试过），
     * 走真代码才可靠。名字固定，重复执行只更新不重复添加。
     *
     * ```
     * adb shell am start -n com.onestep.workbench/.MainActivity \
     *   --es seed_pkgs "com.tencent.nrc,com.ib3port.game,com.ss.android.ugc.aweme"
     * ```
     */
    private fun seedScene(packagesCsv: String?) {
        val store = SceneStore(this)
        val picks = packagesCsv
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("com.tencent.nrc", "com.ib3port.game", "com.ss.android.ugc.aweme")

        val existing = store.loadScenes().firstOrNull { it.name == SEED_SCENE_NAME }
        val scene = (existing ?: store.newScene(SEED_SCENE_NAME)).copy(
            preset = LayoutPreset.SOLO_KEEPALIVE,
            autoStart = false,
            slots = picks.mapIndexed { index, raw ->
                // 支持 "包名@用户号"：com.tencent.nrc@999 = 手机分身里的那一份
                val pkg = raw.substringBefore('@').trim()
                val user = raw.substringAfter('@', "0").trim().toIntOrNull() ?: 0
                Slot(index = index, packageName = pkg, component = null, label = pkg, userId = user)
            }
        )
        store.upsert(scene)
        store.setActiveScene(scene.id)
        Diag.log(
            TAG,
            "seed：已保存「${scene.name}」preset=${scene.preset} 槽位=${picks.size}=$picks，" +
                "读回 ${store.loadScenes().size} 个场景，活动=${store.activeScene()?.name}"
        )
    }

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_DIAG_PROBE = "diag_probe"
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_SEED_PKGS = "seed_pkgs"
        const val EXTRA_NO_AUTO_START = "no_auto_start"
        const val SEED_SCENE_NAME = "挂机场景"
    }
}
