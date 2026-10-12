package com.onestep.workbench.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.onestep.workbench.MainActivity
import com.onestep.workbench.R
import com.onestep.workbench.apps.AppCatalog
import com.onestep.workbench.data.SceneStore
import com.onestep.workbench.model.WorkbenchMode
import com.onestep.workbench.system.KeepAlive
import com.onestep.workbench.window.WorkbenchController

/**
 * 工作台前台服务：持有编排器 + 悬浮 Dock 的生命周期。
 *
 * 用 `specialUse` 类型的前台服务：Android 14+ 对这种"没有更贴切分类"的常驻服务是允许的，
 * 但必须在清单里声明子类型说明用途（已写在工作台的 manifest 里）。
 */
class WorkbenchService : Service(), DockView.Listener {

    companion object {
        const val ACTION_START_SCENE = "com.onestep.workbench.START_SCENE"
        const val ACTION_STOP = "com.onestep.workbench.STOP"
        const val EXTRA_SCENE_ID = "scene_id"
        const val EXTRA_KILL = "kill"

        private const val TAG = "WorkbenchService"
        private const val CHANNEL_ID = "workbench"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: WorkbenchService? = null
            private set

        /** 给 UI 用的入口：拿到当前正在运行的服务里的编排器。 */
        fun controllerOrNull(): WorkbenchController? = instance?.controller

        fun send(
            context: Context,
            action: String,
            sceneId: String? = null,
            kill: Boolean = false
        ) {
            val intent = Intent(context, WorkbenchService::class.java).apply {
                this.action = action
                sceneId?.let { putExtra(EXTRA_SCENE_ID, it) }
                putExtra(EXTRA_KILL, kill)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    lateinit var controller: WorkbenchController
        private set

    private lateinit var store: SceneStore
    private lateinit var windowManager: WindowManager
    private val catalog by lazy { AppCatalog(this) }
    private val main = Handler(Looper.getMainLooper())

    private var dockView: DockView? = null
    private var dockParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SceneStore(this)
        windowManager = getSystemService(WindowManager::class.java)
        controller = WorkbenchController(this)
        controller.addListener { state -> renderDock(state) }

        // 后台预热应用列表：Dock 一出现就要有图标，不能等它慢慢查
        Thread { runCatching { catalog.loadApps() } }.start()

        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 临时诊断：到底是谁在启动服务、带了什么参数
        com.onestep.workbench.system.Diag.log(TAG, "onStartCommand action=" + intent?.action + " extras=" + intent?.extras)
        startForegroundCompat()

        if (intent == null) {
            Log.i(TAG, "服务被系统重建，等待新指令")
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_SCENE -> {
                val sceneId = intent.getStringExtra(EXTRA_SCENE_ID)
                val scene = store.loadScenes().firstOrNull { it.id == sceneId } ?: store.activeScene()
                if (scene == null) {
                    toast("没有可用场景，请先在 App 里建一个")
                    stopSelf()
                    return START_NOT_STICKY
                }
                store.setActiveScene(scene.id)
                // 只有一条路：自由窗口 + 隐形保活小点。以前这里会读"自检推荐的档位"和
                // "用户手动覆盖的档位"，那两个键是自检页时代的产物；老设备上残留一个
                // mode_override=QUICK_SWITCH 就会让 App 走"不缩成小点、不隐形"那条死路。
                controller.startScene(scene, WorkbenchMode.FREEFORM_TILING)
            }

            ACTION_STOP -> {
                controller.stop(intent.getBooleanExtra(EXTRA_KILL, false))
                teardownDock()
                stopSelf()
            }

            else -> Log.i(TAG, "未知指令：${intent.action}")
        }

        return START_NOT_STICKY
    }

    /**
     * 转屏/尺寸变化会把这台 ROM 上的自由窗口"还原"成上一次的大小（真机 m02042：
     * 收起状态下转一次屏，浮窗会自己弹出来）。收到配置变化就立刻校正一次，
     * 这样最多闪一下，不会停留两三秒。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.forceReconcile()
    }

    override fun onHome() {
        // 收起浮窗，再把主界面叫回前台
        controller.collapseFloat()
        startActivity(
            android.content.Intent(this, com.onestep.workbench.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onDestroy() {
        teardownDock()
        runCatching { controller.shutdown() }
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Dock

    private fun renderDock(state: WorkbenchController.State) {
        // 只要工作台在运行，Dock 就必须在 —— 切不了 App 的"工作台"等于没有。
        // 真机反馈（2026-10-09）：nrc 正常启动了，但看不到 Dock、没法切换。
        // 原因就是旧代码要求 state.windows 非空，而重游戏的任务号在 2 秒超时内读不到，
        // windows 为空 → 直接把 Dock 拆了。现在没有窗口记录时改用场景槽位渲染。
        if (!state.running) {
            teardownDock()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "缺少「显示在其他应用上层」权限，Dock 不显示")
            return
        }

        val live = state.windows.sortedBy { it.slotIndex }
        val source: List<Int> = if (live.isNotEmpty()) {
            live.map { it.slotIndex }
        } else {
            state.scene?.slots?.sortedBy { it.index }?.map { it.index } ?: emptyList()
        }
        if (source.isEmpty()) {
            teardownDock()
            return
        }

        val view = dockView ?: createDock() ?: return
        val entries = source.map { slotIndex ->
            val pkg = live.firstOrNull { it.slotIndex == slotIndex }?.packageName
                ?: state.scene?.slots?.firstOrNull { it.index == slotIndex }?.packageName
                ?: "?"
            val app = catalog.find(pkg)
            DockView.Entry(
                slotIndex = slotIndex,
                packageName = pkg,
                label = app?.label ?: pkg,
                icon = app?.icon
            )
        }
        val primary = live.firstOrNull()?.slotIndex ?: source.first()
        view.update(entries, primary, state.keepAliveOn)
    }

    private fun createDock(): DockView? = runCatching {
        dockView?.let { return it }

        val view = DockView(this).apply { listener = this@WorkbenchService }
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE 是硬要求：Dock 一旦可获焦，点一下就会把焦点从游戏抢走
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (220 * density).toInt()
        }

        windowManager.addView(view, params)
        dockView = view
        dockParams = params
        view
    }.onFailure { Log.e(TAG, "Dock 创建失败", it) }.getOrNull()

    private fun teardownDock() {
        val view = dockView ?: return
        runCatching { windowManager.removeView(view) }
        dockView = null
        dockParams = null
    }

    // ------------------------------------------------------------------ Dock 回调

    override fun onSlotTap(slotIndex: Int) {
        controller.onDockTap(slotIndex)
    }

    override fun onSlotLongPress(slotIndex: Int) {
        val window = controller.currentState().windows.firstOrNull { it.slotIndex == slotIndex } ?: return
        val pkg = window.packageName
        Thread {
            val audit = KeepAlive.audit(pkg)
            val enable = !audit.inDeviceIdleWhitelist
            KeepAlive.apply(pkg, enable)
            toast("$pkg ${if (enable) "已加入挂机保活" else "已移出挂机保活"}")
        }.start()
    }


    override fun onDragBy(dy: Int) {
        val params = dockParams ?: return
        val view = dockView ?: return
        params.y = (params.y + dy).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onDragFinished() {
        Log.i(TAG, "Dock 位置：y=${dockParams?.y}")
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }
}
