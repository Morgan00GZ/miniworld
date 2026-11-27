package com.miniworld.window

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.miniworld.apps.AppCatalog
import com.miniworld.data.SceneStore
import com.miniworld.model.AppTarget
import com.miniworld.model.LayoutPreset
import com.miniworld.model.LiveWindow
import com.miniworld.model.Scene
import com.miniworld.model.Slot
import com.miniworld.model.WorkbenchMode
import com.miniworld.model.appTargets
import com.miniworld.model.taskIdsOf
import com.miniworld.service.DockView
import com.miniworld.service.WorkbenchService
import com.miniworld.system.Diag
import com.miniworld.system.FreeformController
import com.miniworld.system.KeepAlive
import com.miniworld.system.ScreenMetrics
import com.miniworld.system.TaskInspector
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 工作台编排器。
 *
 * 三件事：
 *  1. 按场景把应用拉起来（走 FreeformController 的策略链），并缩成**屏幕外** 2×2 的隐形小点
 *  2. 运行期间用 LayoutEngine 算出的坐标把窗口钉在小点上（`am task resize`）；
 *     焦点被别的全屏任务抢走时，把它还给用户真正在看的那个任务（`am task focus`）
 *  3. Dock 点按 = 在"隐形小点"和"约 3/4 屏浮窗"之间切换，**不重新启动 App**
 *
 * 线程模型：所有会碰 shell 的动作都串行跑在单线程 worker 上，
 * 状态通过主线程 Handler 回吐给 UI / 悬浮 Dock。这样既不会互相打断，
 * 也不会因为某条命令卡住而冻结界面。
 */
class WorkbenchController(context: Context) {

    data class State(
        val running: Boolean = false,
        val scene: Scene? = null,
        val preset: LayoutPreset = LayoutPreset.SOLO_KEEPALIVE,
        val windows: List<LiveWindow> = emptyList(),
        val mode: WorkbenchMode = WorkbenchMode.FREEFORM_TILING,
        val keepAliveOn: Boolean = false,
        /** 用户手动"浮出小窗"的那个包名；看门狗必须放过它，否则 6 秒后就被掰回小点。 */
        val manualOverlay: String? = null,
        val message: String = "未开工",
        val log: List<String> = emptyList(),
        /**
         * 「倒置熄屏」是否武装中（Shizuku 已拿到接近传感器关屏锁）。
         *
         * 只影响掉线判定和 Dock 按钮的底色；没开启时这里恒为 false，
         * 所有原有节拍、判定、功耗行为跟不带这个功能时完全一致。
         */
        val pocketArmed: Boolean = false,
        /**
         * 已经判定掉线、还没回来的应用（给人看的名字）。
         *
         * 界面拿它把「状态：运行中」改成「已掉线」，常驻通知也拿它写一行提醒 ——
         * 挂机是"放着不管"的用法，掉线不吭声等于白挂。
         */
        val dropped: List<String> = emptyList()
    )

    private val appContext = context.applicationContext
    private val catalog = AppCatalog(appContext)
    private val store = SceneStore(appContext)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "workbench-worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var state = State()

    // 同时有 UI 与悬浮 Dock 两个观察者，所以是列表而不是单个 listener
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    fun addListener(newListener: (State) -> Unit) {
        listeners += newListener
        val snapshot = state
        main.post { newListener(snapshot) }
    }

    fun currentState(): State = state

    /** 倒置熄屏的真状态由 WorkbenchService 同步进来（Shizuku 掉线时会被改回 false）。 */
    fun setPocketArmed(armed: Boolean) {
        if (state.pocketArmed == armed) return
        update { it.copy(pocketArmed = armed) }
    }

    private fun update(transform: (State) -> State) {
        val next = transform(state)
        state = next
        main.post { listeners.forEach { it(next) } }
    }

    private fun submit(label: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "$label 失败", t)
                update { it.copy(message = "$label 失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /**
     * 窗口尺寸的「看门狗」。
     *
     * 为什么要它：App 自己会在启动完成后重新摆放窗口——实测抖音先是进了自由窗口，
     * 随后又跳回系统的默认级联位置（609×1220，正中间挡着主窗），而 com.tencent.nrc
     * 干脆把自己拉成全屏。一次性摆位挡不住这种"事后反悔"，所以运行期间每 6 秒
     * 把**还在自由窗口里**的应用拉回它该在的格子；不肯进自由窗口的（nrc）不强掰，
     * 免得每几秒闪一次全屏。
     */
    /** 交还焦点动作的最小间隔：万一系统反复把窗口顶上来，不至于和它来回抢。 */
    private var lastFocusHandBackAt = 0L

    /**
     * 焦点被系统抢走时把它还回去（最小改动版，**不动任何几何**）。
     *
     * 真机现象：只要别的全屏任务上来（打开主页 / 打开微信 / 上滑看后台），这台 ROM 会把受管的
     * 2×2 自由窗口顶成 `topDisplayFocusedRootTask` → 系统给它画标题栏、把内容重新显示出来，
     * 用户看到"浮窗自己弹出来 + 变形 + 收不回"。实测此时任务矩形和窗口 `Requested w=2 h=2`
     * **全都是对的**，所以比几何的判据全都判不出来（废弃的 3.5 就是这么失败的）。
     *
     * 修复动作只有一条 `am task focus`：把焦点还给**用户此刻真正在看的那个任务**
     * （显示级 `topResumedActivity` 的 `t<id>`）。横屏下就还给那个横屏应用本身，等于什么都没发生——
     * 这正是它和废弃版的关键区别：废弃版挑"第一个不是我们、也不是受管应用的任务"，
     * 横屏时容易挑到桌面，于是把人从横屏应用踢回桌面。
     *
     * 只在"小点模式 + 竖屏"动手：浮出态不碰（那时它本来就该拿着焦点）；横屏/旋转期间不介入
     * （那条路会由系统重排 + forceReconcile 自己好）。
     */
    private fun handBackStolenFocus(dump: String): Boolean {
        if (state.manualOverlay != null) return false
        if (appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            return false
        }
        val managed = (
            state.windows.map { it.packageName } +
                (state.scene?.slots?.map { it.packageName } ?: emptyList())
            ).toSet()
        if (managed.isEmpty()) return false

        val snap = TaskInspector.focusSnapshot(dump)
        val focusedPkg = snap.focusedPackage ?: return false
        if (!managed.contains(focusedPkg)) return false
        val target = snap.resumedTaskId ?: return false
        if (target == snap.focusedTaskId) return false
        val now = System.currentTimeMillis()
        if (now - lastFocusHandBackAt < 3_000) return false
        lastFocusHandBackAt = now
        Diag.log(TAG, "焦点被抢：$focusedPkg（任务 ${snap.focusedTaskId}）-> 交还给任务 $target")
        FreeformController.focusTask(target, null)
        return true
    }

    /** 屏幕是否亮着。熄屏时看门狗降频到 20 s（用户 m02893 选定的档位），亮屏保持 1.11 的 2 s。 */
    @Volatile
    private var screenOn = true

    /** 刚动过窗口（resize / 焦点交还）之后，回到快档保持到这一刻；熄屏慢档下专门用来盯窗口落位。 */
    @Volatile
    private var fastUntil = 0L

    /** 上一次心跳日志的时间戳。按**时间**算而不是按拍数，两种节拍下都是 5 分钟一条。 */
    private var lastHeartbeatAt = 0L

    @Volatile
    private var screenReceiverRegistered = false

    /** 上一次真正跳过拍的时刻：漏拍巡检拿它和现在比。 */
    @Volatile
    private var lastTickAt = 0L

    /**
     * 漏拍巡检线程。为什么需要：真机 2026-10-13 实测过一次 **13.5 分钟零拍** ——
     * 进程没死、服务没重启、窗口也没被回收，但消息循环整段没跑（拉回前台后积压的消息一次性补跑）。
     * 单线程 Handler 链一旦断掉，原本没有任何人会发现。巡检每 60 s 看一眼，
     * 漏拍就补一拍 + 写一条日志，让"看门狗还活着吗"在 diag.log 里看得见、也能自己活过来。
     */
    @Volatile
    private var watchdogGuard: ScheduledExecutorService? = null

    /**
     * 屏幕此刻是不是真的亮着。
     *
     * 为什么不能用 `PowerManager.isInteractive`：倒置熄屏用 shell 身份持着
     * `PROXIMITY_SCREEN_OFF_WAKE_LOCK`，屏幕**物理熄灭**（Display State=OFF）时
     * `isInteractive` 依然是 true（真机 2026-11-26 实测）。拿它当判据，熄屏后
     * 既不会降频、也不会暂停掉线判定。屏的真实状态只有 DisplayManager 知道。
     */
    private fun isDisplayOn(): Boolean {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return true
        val display = runCatching { dm.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull() ?: return true
        return display.state == Display.STATE_ON
    }

    /** 亮灭屏状态统一入口：广播与显示监听都走这里，去重后切档并立刻重排下一拍。 */
    private fun applyScreenState(on: Boolean, source: String) {
        if (on == screenOn) return
        screenOn = on
        val base = TickPolicy.intervalMs(on, 0L, 0L) / 1000
        Diag.log(TAG, "屏幕${if (on) "亮" else "熄"}（$source）：看门狗节拍 -> ${base} s")
        main.removeCallbacks(reconcileTick)
        main.postDelayed(reconcileTick, TickPolicy.TICK_MS)
    }

    /**
     * 亮灭屏广播（`ACTION_SCREEN_ON/OFF` 只能**动态注册**，好在动态注册不需要任何权限）。
     * 收到就切档并立刻重排下一拍 —— 熄屏后先补快一拍（系统此刻常正在搬窗口），然后再慢下来。
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyScreenState(intent?.action == Intent.ACTION_SCREEN_ON, "屏幕广播")
        }
    }

    /**
     * 显示状态监听。**倒置熄屏必须有它**：接近锁关屏时系统不发 `ACTION_SCREEN_OFF`
     * （那不是电源键睡眠），只有 DisplayManager 会回调 `onDisplayChanged`。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            applyScreenState(isDisplayOn(), "显示状态")
        }
    }

    /**
     * 开工后启动看门狗：注册亮灭屏广播（幂等）+ 排第一拍。
     *
     * @param firstDelayMs 首拍延迟。开工补课刚跑完时要传 [TickPolicy.TICK_MS]：
     *   补课已经把窗口摆好了，再空等 6 秒等于留一段没人管的空窗
     *   （nrc 甩窗口正好发生在启动后 1~3 秒）。
     */
    private fun startWatchdog(firstDelayMs: Long = TickPolicy.START_DELAY_MS) {
        if (!screenReceiverRegistered) {
            screenOn = isDisplayOn()
            ContextCompat.registerReceiver(
                appContext,
                screenStateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.registerDisplayListener(displayListener, null)
            }.onFailure { Diag.log(TAG, "注册显示监听失败：${it.message}") }
            screenReceiverRegistered = true
        }
        lastHeartbeatAt = System.currentTimeMillis()
        lastTickAt = lastHeartbeatAt
        startGuard()
        scheduleAlarm()
        main.removeCallbacks(reconcileTick)
        main.postDelayed(reconcileTick, firstDelayMs)
        Diag.log(
            TAG,
            "看门狗开拍：首拍延迟=${firstDelayMs / 1000} s，" +
                "亮屏节拍=${TickPolicy.TICK_MS / 1000} s，熄屏节拍=${TickPolicy.TICK_MS_SCREEN_OFF / 1000} s，" +
                "闹钟兜底=${TickPolicy.ALARM_INTERVAL_MS / 60_000} 分钟"
        )
    }

    /** 启动漏拍巡检（幂等）。 */
    private fun startGuard() {
        if (watchdogGuard != null) return
        val guard = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "watchdog-guard").apply { isDaemon = true }
        }
        watchdogGuard = guard
        guard.scheduleWithFixedDelay(
            { runCatching { checkWatchdog() } },
            GUARD_PERIOD_MS,
            GUARD_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun checkWatchdog() {
        if (!state.running) return
        val now = System.currentTimeMillis()
        val interval = TickPolicy.intervalMs(screenOn, fastUntil, now)
        if (!TickPolicy.isStalled(now, lastTickAt, interval)) return
        Diag.log(
            TAG,
            "看门狗漏拍 ${(now - lastTickAt) / 1000} 秒（节拍=${interval / 1000} s），补一拍"
        )
        main.removeCallbacks(reconcileTick)
        main.post(reconcileTick)
    }

    /** 收工 / 关闭：停拍并摘掉广播，别留着。`reason` 会写进日志，方便事后分清"谁让它停的"。 */
    private fun stopWatchdog(reason: String) {
        stopGuard()
        cancelAlarm()
        main.removeCallbacks(reconcileTick)
        lastTickAt = System.currentTimeMillis()
        if (screenReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(screenStateReceiver) }
            runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(displayListener)
            }
            screenReceiverRegistered = false
        }
        Diag.log(TAG, "看门狗停拍：$reason")
    }

    private fun stopGuard() {
        watchdogGuard?.let { runCatching { it.shutdownNow() } }
        watchdogGuard = null
    }

    /** 闹钟兜底的 PendingIntent（取消时要用同一份实例）。 */
    private var alarmPending: PendingIntent? = null

    /**
     * 排一个"还活着吗"的系统闹钟。
     *
     * 为什么需要它：真机 2026-10-13 复现过**整进程被 ROM 挂起 13.5 分钟** —— 那种情况下
     * 进程内的巡检线程（watchdogGuard）一起被冻住，谁也救不了自己。系统为投递闹钟会主动
     * 唤醒进程，`setAndAllowWhileIdle` 又不需要任何新权限，这是唯一还能从进程外把我们叫醒的
     * 通道。代价只有每 10 分钟一次唤醒（比亮屏 2 s 一拍便宜两个数量级）。
     */
    private fun scheduleAlarm() {
        val am = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getService(
            appContext,
            0,
            Intent(appContext, WorkbenchService::class.java).setAction(WorkbenchService.ACTION_PING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmPending = pi
        runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + TickPolicy.ALARM_INTERVAL_MS,
                pi
            )
        }
    }

    private fun cancelAlarm() {
        val pi = alarmPending ?: return
        runCatching { appContext.getSystemService(AlarmManager::class.java)?.cancel(pi) }
        alarmPending = null
    }

    /**
     * 闹钟到点（由 `WorkbenchService` 的 ACTION_PING 调进来）：报一句"距上一拍多久"，
     * 确认漏拍就补一拍，然后把下一个闹钟排上。
     *
     * 正常情况下这条日志每 10 分钟一条 —— 它和 5 分钟一条的心跳是**两条独立的证据链**
     * （心跳走消息循环，闹钟走系统 AlarmManager）：两条一起哑掉，才是真的被冻住了。
     *
     * @return 工作台是否仍在运行；false 时服务会顺手收掉自己。
     */
    fun pingFromAlarm(): Boolean {
        if (!state.running) {
            Diag.log(TAG, "闹钟兜底：工作台已不在运行，忽略")
            return false
        }
        val now = System.currentTimeMillis()
        val interval = TickPolicy.intervalMs(screenOn, fastUntil, now)
        Diag.log(TAG, "闹钟兜底：距上一拍 ${(now - lastTickAt) / 1000} s（节拍=${interval / 1000} s）")
        if (TickPolicy.isStalled(now, lastTickAt, interval)) {
            Diag.log(TAG, "闹钟兜底：确实漏拍，补一拍")
            main.removeCallbacks(reconcileTick)
            main.post(reconcileTick)
        }
        scheduleAlarm()
        return true
    }

    private val reconcileTick = object : Runnable {
        override fun run() {
            lastTickAt = System.currentTimeMillis()
            if (!state.running) {
                // 这里以前是"直接 return、不再排下一拍"：只要 running 判错一次（或有人漏了停拍日志），
                // 节拍链就永久断掉且没人知道。现在停就写明白原因，续拍一律走 finally。
                Diag.log(TAG, "看门狗停跳：工作台已不在运行")
                return
            }
            try {
                submit("校正窗口") { reconcileBounds() }
                // S4：日志降噪后不再逐条命令写盘，改成 5 分钟一条心跳（不查也 ping，零命令开销）。
                // 按**时间**判断：熄屏 20 s 一拍时，"150 拍"会变成 50 分钟一条。
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatAt >= TickPolicy.HEARTBEAT_MS) {
                    lastHeartbeatAt = now
                    val s = state
                    Diag.log(
                        TAG,
                        "看门狗心跳：窗口=${s.windows.joinToString { "${it.packageName}@${it.userId}" }}，" +
                            "模式=${s.mode}，浮窗=${s.manualOverlay ?: "无"}，" +
                            "节拍=${TickPolicy.intervalMs(screenOn, fastUntil, now) / 1000} s"
                    )
                }
            } catch (t: Throwable) {
                // 单拍的意外（比如 worker 已 shutdown 的 RejectedExecutionException）不许杀死整条节拍链。
                Diag.log(TAG, "看门狗这一拍出错（照常续拍）：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                main.postDelayed(this, TickPolicy.intervalMs(screenOn, fastUntil, System.currentTimeMillis()))
            }
        }
    }

    /** 屏幕方向/尺寸变化后立刻校正一次（不等看门狗的下一拍）。 */
    fun forceReconcile() = submit("旋转后校正") { reconcileBounds() }

    private fun reconcileBounds() {
        val current = state
        val scene = current.scene ?: return
        if (current.mode != WorkbenchMode.FREEFORM_TILING) return

        // S1：一拍只取一份任务列表，下面所有判断都复用这一份。
        // 原来一拍要 dump 3 次（find / focusSnapshot / 末尾兜底 find），每次都是一条
        // `dumpsys activity activities`（真机实测约 0.01 s、72 KB，还要经 Shizuku 起一个 sh）。
        // 拿不到就整拍跳过：宁可这一拍什么都不做，也不能把"没查到"当成"任务不见了"。
        val dump = TaskInspector.dumpOrNull() ?: return

        val available = ScreenMetrics.read(appContext)
            .availableBounds(rightInset = DockView.widthPx(appContext))
        // 关键：按**当前窗口顺序**校正，而不是按场景里的原始顺序。
        // 用户切到另一个 App 后 windows 会被重排（前台那个排到最前），校正必须尊重它；
        // 否则每 6 秒就把用户的选择掰回第一个 App —— 真机现象正是
        // 「无论怎么切，最终都是第一个 App 在前台」。
        val ordered = current.windows.sortedBy { it.slotIndex }
        if (ordered.isEmpty()) return
        // 只有一个应用时（极简保活模式）它的目标就是隐形小点。
        // 不特判的话布局会算出"整块可用区"，看门狗每 6 秒就把它放大弹出一次
        // ——真机现象正是"收起来两三秒后自动弹出来、不断循环"（m01906）。
        val targets = if (ordered.size == 1) {
            listOf(dotBounds())
        } else {
            LayoutEngine.layout(available, current.preset, ordered.size, offscreenDot = dotBounds())
        }
        var moved = false
        ordered.forEachIndexed { index, window ->
            val target = targets.getOrElse(index) { targets.last() }
            // 用户手动浮出的那个小窗不要动，否则 6 秒后就被看门狗掰回小点
            if (window.packageName == current.manualOverlay) return@forEachIndexed
            val task = TaskInspector.find(window.packageName, window.userId, dump)
            if (task == null) {
                // 任务不见了：以前这里静默跳过，界面照样写「运行中」，挂机掉了用户毫不知情。
                noteMissing(window, dump)
                return@forEachIndexed
            }
            notePresent(window)
            if (!task.isFreeform) return@forEachIndexed
            if (task.bounds != target) {
                // 和弹出时一样走"先小后大"两步：单次 resize 在转屏之后经常不生效，
                // 结果就是"收起状态下转一下屏，浮窗又自己弹出来"（真机 m02032 第二条）。
                FreeformController.resizeTask(task.taskId, scaled(target, 3, 5), verify = false)
                Thread.sleep(200)
                // 两步都不复查：这一拍开头已经有 dump 了，下一拍还会再读一次，
                // 中间多查两遍只是把同一份 72 KB 读两遍（一拍一份 dump）。
                FreeformController.resizeTask(task.taskId, target, verify = false)
                Diag.log(TAG, "校正：${window.packageName} ${task.bounds} -> $target")
                com.miniworld.system.Metrics.bumpCorrection()
                moved = true
            }
        }

        // 焦点被别的全屏任务抢走 → 还给用户真正在看的那个任务：只发一条 am task focus，几何不动。
        val handedBack = handBackStolenFocus(dump)

        // 这一拍真的动过东西（校正过窗口 / 交还过焦点）→ 接下来 10 s 保持快档盯着它落位。
        // 熄屏慢档下这一步很关键：一次 resize 要"先小后大"两步才生效，不能发完就睡 20 s。
        if (moved || handedBack) {
            fastUntil = System.currentTimeMillis() + TickPolicy.FAST_HOLD_MS
        }

        // 把**当前应该在前台的那个**重新置顶（不是固定第一个）。
        // 但用户手动浮出小窗时要放过：他一浮出，主窗再抢前台就会把小窗顶掉（"闪现一下又消失"）。
        if (current.manualOverlay != null) return
        // 单应用（隐形小点）模式：窗口在系统眼里**仍然可见**时，一次都不许抢前台。
        // 真机实测（m02023 / m02040）：这台 ROM 上 `am task focus` = 把这个任务整个顶到前台
        // **并抢走输入焦点**；手势导航的返回是发给"当前有焦点的窗口"的 —— 原来每 2 秒来一条，
        // 侧滑就永远落在那颗 2×2 小点上，用户正在用的 App 根本收不到返回（"侧滑返回桌面失效"）。
        // 而保活不靠被 focus：窗口仍可见时进程一直是 TOP（真机实测连续 4 分钟以上不掉、不冻结），
        // 所以这里只在"确实看不见"时才补一次兜底。多窗口平铺档位维持原样。
        val singleDot = ordered.size == 1
        ordered.firstOrNull()?.let { main ->
            TaskInspector.find(main.packageName, main.userId, dump)?.let { task ->
                if (singleDot && task.visible) return@let
                FreeformController.focusTask(task.taskId, main.component)
            }
        }
    }

    // -------------------------------------------------- 掉线判定与自愈（条目 6）

    /** 每个窗口连续几拍没找到任务。key = 包名#用户号。 */
    private val missTicks = HashMap<String, Int>()

    /** 每个窗口已经自动重开过几次。 */
    private val relaunchTries = HashMap<String, Int>()

    /** 每个窗口上次自动重开的时间点，用来做"重开冷却"（见 DropPolicy.RELAUNCH_GRACE_MS）。 */
    private val lastRelaunchAt = HashMap<String, Long>()

    /** 当前「已掉线」的窗口：key -> 给人看的名字（界面和通知都读它）。 */
    private val lostWindows = LinkedHashMap<String, String>()

    /** 已经记过一次「因为前景是别的应用而暂不重开」的窗口，避免每拍刷同一行日志。 */
    private val suppressNoted = HashSet<String>()

    /** 本轮熄屏是否已经记过一行「暂停掉线判定」，避免 20 s 一拍刷满日志。 */
    private var pocketSuppressNoted = false

    private fun keyOf(window: LiveWindow) = "${window.packageName}#${window.userId}"

    /**
     * 组件串补上「@用户号」。
     *
     * 分身（用户 999）那一份**只能靠这个后缀区分**：`FreeformController.amStart` 解析不到后缀时
     * 会退回 `--user 0`，于是"重开 / 置顶"实际是把主系统那一份又拉了一遍。真机踩过——
     * 日志写「重开结果：成功（…任务 74）」，可分身的游戏始终没起来，任务号还指回主系统那个。
     */
    private fun ensureUser(component: String?, userId: Int): String? =
        component?.let { if (userId != 0 && !it.contains('@')) "$it@$userId" else it }

    /** 槽位对应的启动组件串（带用户号）。 */
    private fun resolveComponent(slot: Slot): String? =
        ensureUser(slot.component ?: catalog.find(slot.packageName)?.component, slot.userId)

    private fun labelOf(window: LiveWindow): String {
        val slot = state.scene?.slots?.firstOrNull { it.index == window.slotIndex }
        val name = slot?.label ?: window.packageName
        // 槽位名里本来就可能带「（分身）」（主界面显示的就是「洛克王国：世界（分身）」），
        // 再无条件追加会变成「…（分身）（分身）」，所以只补一次。
        return if (window.userId == 0 || name.contains("（分身）")) name else "$name（分身）"
    }

    /**
     * 这一拍没找到任务：记账 → 够拍才认定掉线 → 必要时自动重开。
     *
     * 不能一拍就判：dump 偶尔会漏一两个任务，一秒内的误判既会让界面闪「已掉线」，
     * 更糟的是触发重开。亮屏 2 s 一拍 → 3 拍约 6 s，够慢也够快。
     */
    private fun noteMissing(window: LiveWindow, dump: String) {
        if (pocketScreenOff()) {
            if (!pocketSuppressNoted) {
                pocketSuppressNoted = true
                Diag.log(TAG, "倒置熄屏中：屏幕已熄灭，暂停掉线判定（不 force-stop、不重开）")
            }
            return
        }
        if (pocketSuppressNoted) pocketSuppressNoted = false
        val key = keyOf(window)
        val misses = (missTicks[key] ?: 0) + 1
        missTicks[key] = misses
        if (!DropPolicy.isLost(misses)) return
        if (!lostWindows.containsKey(key)) {
            lostWindows[key] = labelOf(window)
            Diag.log(TAG, "掉线：${labelOf(window)}（连续 $misses 拍找不到任务）")
            publishLost()
        }
        if (userIsBusyElsewhere(dump)) {
            // 每 2 s 一拍，这里不能每拍都刷一行日志；同一次掉线只记一次就够了。
            if (suppressNoted.add(key)) {
                Diag.log(TAG, "暂不重开：${labelOf(window)}（前景是别的应用，先不抢回来）")
            }
            return
        }
        val tries = relaunchTries[key] ?: 0
        val sinceLast = System.currentTimeMillis() - (lastRelaunchAt[key] ?: 0L)
        if (!DropPolicy.mayRelaunchNow(misses, tries, sinceLast)) return
        val component = window.component ?: return
        relaunchTries[key] = tries + 1
        lastRelaunchAt[key] = System.currentTimeMillis()
        Diag.log(TAG, "重开第 ${tries + 1} 次：${labelOf(window)}（组件 ${component}）")
        // 先结束残进程再冷启动：任务没了但进程还活着时，"am start" 会起不来（见 forceStop 注释）
        FreeformController.forceStop(window.packageName, window.userId)
        // force-stop 是异步的：紧接着就 am start 会撞上"正在停止"，启动被系统直接丢掉
        // （真机对比：手动隔 2 秒再 start 一次就成功，程序里连着发则起不来）。
        Thread.sleep(1_500)
        // 重开时**不能**直接给小点坐标：真机上这么干，游戏起来就没有任务了（启动阶段
        // 窗口整块在屏外，ROM 会把 Activity 收掉）。正常开工是先给一个屏上的正常窗口、
        // 再由看门狗"缩"成小点——缩可以、从出生就在屏外不行，所以这里也照那样来。
        val outcome = FreeformController.launchInFreeform(
            component = component,
            bounds = overlayBounds(),
            context = appContext,
            preferredStrategy = store.workingStrategy()
        )
        Diag.log(
            TAG,
            "重开结果：${if (outcome.success) "成功" else "失败"}" +
                "（策略 ${outcome.strategy}${if (outcome.task != null) "，任务 ${outcome.task.taskId}" else ""}）"
        )
        // 把启动过程逐行落盘：分身（--user 999）这条路上出过"报成功但游戏没起来"，
        // 只有看到真正执行的那条 am start 才能判断是命令没带用户号、还是系统把启动丢了。
        outcome.log.forEach { Diag.log(TAG, "    $it") }
    }

    /**
     * 倒置熄屏中且屏幕确实灭了？
     *
     * 熄屏后（真机 2026-11-26 实测）系统把目标任务的 visibleRequested 置 false、dump 里
     * 任务行整段消失，而进程还活着、游戏还在算 —— 这时按「连续 3 拍找不到 = 掉线」处理，
     * 就会 force-stop 掉用户正挂着的游戏。所以熄屏期间既不判定也不重开，
     * 屏一亮（接近锁自动放屏）立刻恢复正常判定。
     */
    private fun pocketScreenOff(): Boolean {
        if (!state.pocketArmed) return false
        // 不能用 PowerManager.isInteractive：接近锁下屏幕物理灭了它仍然是 true。
        return !isDisplayOn()
    }

    /** 这一拍找到了：清掉记账；真的从「掉线」回来时还要通知界面。 */
    private fun notePresent(window: LiveWindow) {
        val key = keyOf(window)
        missTicks.remove(key)
        relaunchTries.remove(key)
        lastRelaunchAt.remove(key)
        suppressNoted.remove(key)
        if (lostWindows.remove(key) != null) {
            Diag.log(TAG, "回来了：${labelOf(window)}")
            publishLost()
        }
    }

    /**
     * 用户此刻是不是正在用**别人的全屏应用**？
     *
     * 是就不重开：挂机掉线可以等，把用户正在看的画面顶掉是更烦人的打扰。
     * 桌面（com.miui.home）、我们自己、以及工作台里的应用都不算"别人"。
     */
    private fun userIsBusyElsewhere(dump: String): Boolean {
        val pkg = TaskInspector.focusSnapshot(dump).focusedPackage ?: return false
        if (pkg == appContext.packageName) return false
        if (pkg == "com.miui.home") return false
        return state.windows.none { it.packageName == pkg }
    }

    private fun publishLost() {
        val snapshot = lostWindows.values.toList()
        update { it.copy(dropped = snapshot) }
    }

    /** 收工/服务销毁时把掉线记账清干净，下次开工从零算。 */
    private fun clearDropState() {
        missTicks.clear()
        relaunchTries.clear()
        lastRelaunchAt.clear()
        suppressNoted.clear()
        if (lostWindows.isNotEmpty()) {
            lostWindows.clear()
            update { it.copy(dropped = emptyList()) }
        }
    }

    // ------------------------------------------------------------------ 开工

    fun startScene(scene: Scene, mode: WorkbenchMode) = submit("开工") {
        Diag.log(TAG, "startScene 调用栈：" + Throwable().stackTrace.drop(1).take(5).joinToString("|"))
        if (scene.slots.isEmpty()) {
            update { it.copy(message = "这个场景里还没有应用") }
            return@submit
        }

        // 已经在跑、而且场景里每个应用的任务都还在 → **什么都不重启**，只刷新状态。
        // 反复"重新开工"会把受管 App 重新拉起（am start），而重新拉起是画面变形、
        // 触控丢失的主要来源（真机 m01986~m01988 排查结论）。
        if (state.running) {
            val allAlive = runCatching {
                val tasks = TaskInspector.listTasks()
                scene.slots.all { slot ->
                    // 严格按「包名 + 用户号」比对：只要主机那一份还活着就判定"全都在"，
                    // 会让分身那一份永远不被重新拉起。
                    tasks.any { it.packageName == slot.packageName && it.userId == slot.userId }
                }
            }.getOrDefault(false)
            if (allAlive) {
                Diag.log(TAG, "已在运行，跳过重启：${scene.name}")
                update { it.copy(message = "已在运行（没有重启任何应用）") }
                return@submit
            }
        }
        update {
            it.copy(
                running = true,
                scene = scene,
                preset = scene.preset,
                mode = mode,
                message = "正在启动 ${scene.slots.size} 个窗口…",
                windows = emptyList(),
                log = emptyList(),
                keepAliveOn = false
            )
        }

        val screen = ScreenMetrics.read(appContext)
        // 平铺时把右侧 Dock 的宽度挖掉：Dock 悬在所有窗口之上，不挖就一定会压住某个窗口
        // （真机反馈：「悬浮窗会挡屏幕，令显示面积更小」）。
        val dockInset = if (mode == WorkbenchMode.FREEFORM_TILING) DockView.widthPx(appContext) else 0
        val available = screen.availableBounds(rightInset = dockInset)
        // 只有一个应用时，它起步就该是**隐形小点**（只挂一个 App 缩小保活的极简模式），
        // 而不是布局算出来的"整块可用区"。
        val boundsList = if (scene.slots.size == 1) {
            listOf(dotBounds())
        } else {
            LayoutEngine.layout(available, scene.preset, scene.slots.size, offscreenDot = dotBounds())
        }
        val log = ArrayList<String>()
        log += "可用区=$available ${screen.width}x${screen.height} 顶栏=${screen.topInset}" +
            " 底栏=${screen.bottomInset} Dock占宽=${dockInset}px"
        log += "档位=${mode.label}"

        val windows = ArrayList<LiveWindow>()

        if (mode == WorkbenchMode.FREEFORM_TILING) {
            scene.slots.forEachIndexed { index, slot ->
                val component = resolveComponent(slot)
                if (component == null) {
                    log += "× ${slot.label}：找不到可启动组件"
                    return@forEachIndexed
                }
                val bounds = boundsList.getOrElse(index) { boundsList.last() }
                val outcome = FreeformController.launchInFreeform(
                    component = component,
                    bounds = bounds,
                    context = appContext,
                    preferredStrategy = store.workingStrategy()
                )
                if (outcome.success && outcome.strategy != "none") {
                    store.setWorkingStrategy(outcome.strategy)
                }
                log += "${if (outcome.success) "✓" else "×"} ${slot.label} → ${outcome.strategy}"
                log += outcome.log.map { "    $it" }
                if (outcome.task != null) {
                    windows += LiveWindow(
                        slotIndex = index,
                        packageName = slot.packageName,
                        userId = slot.userId,
                        component = component,
                        taskId = outcome.task.taskId,
                        bounds = bounds,
                        launchedBy = outcome.strategy
                    )
                }
            }
        } else {
            // 非平铺档位：不追求栅格，只保证"拉得起来 + 不被冻结 + 能一键切回"
            scene.slots.forEachIndexed { index, slot ->
                val component = resolveComponent(slot)
                if (component == null) {
                    log += "× ${slot.label}：找不到可启动组件"
                    return@forEachIndexed
                }
                val ok = FreeformController.launchPlain(component)
                log += "${if (ok) "✓" else "×"} ${slot.label} → 普通启动"
                if (ok) {
                    val task = TaskInspector.waitForTask(slot.packageName, slot.userId, 2_000)
                    if (task != null) {
                        windows += LiveWindow(
                            slotIndex = index,
                            packageName = slot.packageName,
                            userId = slot.userId,
                            component = component,
                            taskId = task.taskId,
                            bounds = UNSET_BOUNDS,
                            launchedBy = "plain"
                        )
                    }
                }
            }
        }

        // ---- 补课：有些游戏会甩掉我们设的自由窗口 ----
        // 实测 com.tencent.nrc：我们用 --windowingMode 5 拉起的是它的"策略页"，
        // 它自己随后在一个**新任务**里起了全屏的游戏 Activity，于是刚铺好的那一格空了出来。
        // 这里等它稳定后按包名重新找任务：不是自由窗口就再拉一次，任务 id 变了也要同步，
        // 否则后面"切换主位"会操作到已经不存在的旧任务。
        if (mode == WorkbenchMode.FREEFORM_TILING && scene.slots.isNotEmpty()) {
            // 密集校正：开工后前十来秒是"应用自己抢窗口"的高峰期——实测抖音启动完成后会跳回
            // 系统默认的级联位置（609×1220 正中间），com.tencent.nrc 会把自己顶成全屏。
            // 旧版每 2.5~3 秒才看一次，根本追不上，用户看到的就是"窗口乱跳、前台不正常"。
            // 现在每 0.8 秒对所有槽位强制摆位一次，并把主窗（第一个槽位）重新置顶。
            var relaunched = 0
            // 一轮只取**一份** dump：原来每轮按槽位各跑一次全量（单应用时一轮 2 份，
            // 主窗还要再查一次），而这份文本真机上是 108 KB / 70 ms —— 补课期间它是唯一
            // 在干活的东西，省下来最划算。
            for (attempt in 0 until SettlePolicy.MAX_ROUNDS) {
                Thread.sleep(SettlePolicy.ROUND_INTERVAL_MS)
                val roundDump = TaskInspector.dumpOrNull() ?: continue
                var drifted = 0
                scene.slots.forEachIndexed { index, slot ->
                    val target = boundsList.getOrElse(index) { boundsList.last() }
                    val component = resolveComponent(slot)
                    val task = TaskInspector.find(slot.packageName, slot.userId, roundDump)
                        ?: return@forEachIndexed
                    if (task.bounds != target) {
                        FreeformController.resizeTask(task.taskId, target, verify = false)
                        drifted++
                        if (!task.isFreeform && relaunched < 2 && component != null) {
                            // 极少数应用 resize 后仍不在自由窗口：最多补拉两次，避免反复闪屏
                            FreeformController.launchInFreeform(component, target, appContext)
                            relaunched++
                        }
                    }
                }
                // 每轮都把主窗压回最上面——用户看到的必须是第一个 App，而不是某个保活小点
                val mainSlot = scene.slots.first()
                TaskInspector.find(mainSlot.packageName, mainSlot.userId, roundDump)?.let { mainTask ->
                    FreeformController.focusTask(mainTask.taskId, mainSlot.component)
                }
                // 漂移清零后再看一轮就收工（原来是 >=3，等于平白多等 1.6 s）。
                // 剩下的交给看门狗：它紧接着就会以 2 s 节拍接管。
                if (SettlePolicy.shouldStop(attempt, drifted)) break
            }
            // 校正完把最新任务号同步进 windows，Dock 的切换就靠它（同样只读一份 dump）
            val syncDump = TaskInspector.dumpOrNull()
            if (syncDump != null) scene.slots.forEachIndexed { index, slot ->
                TaskInspector.find(slot.packageName, slot.userId, syncDump)?.let { fresh ->
                    val live = LiveWindow(
                        slotIndex = index,
                        packageName = slot.packageName,
                        userId = slot.userId,
                        // 必须走 resolveComponent：这里原来直接存裸组件串，于是补课同步之后
                        // 记录里的组件丢了「@999」，后面重开/置顶全落到主系统那一份上。
                        component = resolveComponent(slot),
                        taskId = fresh.taskId,
                        bounds = fresh.bounds ?: boundsList.getOrElse(index) { boundsList.last() },
                        launchedBy = "settled"
                    )
                    // 同一个包在主机和分身上各有一份时，只按包名匹配会互相覆盖。
                    val tracked = windows.indexOfFirst {
                        it.packageName == slot.packageName && it.userId == slot.userId
                    }
                    if (tracked >= 0) windows[tracked] = live else windows += live
                }
            }
        }

        // 让槽位 0 成为唯一"看得见"的那个：它占满可用区并置顶，
        // 其余 2×2 像素的保活点自然被压在它下面（用户要求"小窗别出现"）。
        windows.minByOrNull { it.slotIndex }?.let { main ->
            FreeformController.focusTask(main.taskId, main.component)
        }

        // 把主位真的拉到最前面。个别游戏（nrc）会把自己提升成**全屏任务**，
        // 而 `am task focus` 对这类任务常"返回成功但不生效"——于是用户看到的是工作台或桌面，
        // 游戏在后台只出声音（真机 m01875："能听到声音，但是显示不正常"）。
        // 对"不在自由窗口里"的主位，直接普通启动一次，系统保证它到前台。
                windows.minByOrNull { it.slotIndex }?.let { main ->
            val mainComponent = ensureUser(main.component ?: catalog.find(main.packageName)?.component, main.userId)
            val mainTask = TaskInspector.find(main.packageName, main.userId)
            if (mainComponent != null && (mainTask == null || !mainTask.isFreeform)) {
                FreeformController.launchPlain(mainComponent)
            }
        }
        val launched = windows.size
        update {
            it.copy(
                windows = windows,
                log = log,
                message = when {
                    launched == 0 -> "一个窗口都没起来，去看自检页的结论"
                    launched < scene.slots.size -> "起来了 $launched/${scene.slots.size} 个，其余失败（见日志）"
                    mode == WorkbenchMode.FREEFORM_TILING -> "$launched 个窗口已就位，可用 Dock 切换主位"
                    else -> "$launched 个应用已保持活跃（当前档位不支持同屏平铺）"
                }
            )
        }

        // 保活是必需的，不提供开关：开工即把每个挂机应用加进白名单/活跃桶。
        applyKeepAlive(appTargets(windows, scene.slots), enable = true)

        // 开工后启动窗口看门狗（把跑偏的自由窗口拉回格子；节拍见 TickPolicy）。
        // 首拍用常规节拍而不是 6 秒：补课刚把窗口摆好，再空等 6 秒就是一段没人管的空窗。
        startWatchdog(TickPolicy.TICK_MS)
    }

    // ------------------------------------------------------------------ 切换

    /**
     * Dock 图标被点击（按用户 m01854 定的交互）：
     *  - 点**全屏那个** → 其他应用全部收回隐形小点（"第二个 App 再次消失并后台运行"）
     *  - 点**后台那个** → 它在"隐形小点"和"约占屏幕三分之二的小窗"之间切换
     *    （"再次点击这个 app 图标时，就变成小窗叠加在主 app 上面，窗口占屏幕三分之二左右"）
     */
    fun onDockTap(slotIndex: Int) = submit("Dock 点击") {
        val current = state
        if (!current.running) {
            update { it.copy(message = "还没开工") }
            return@submit
        }
        val ordered = current.windows.sortedBy { it.slotIndex }
        if (ordered.isEmpty()) {
            update { it.copy(message = "还没有窗口") }
            return@submit
        }
        val primarySlot = ordered.first().slotIndex
        // 场景只有一个应用时（用户最新极简需求：只挂一个 App 并缩小保活），
        // 点它就是在"隐形小点"和"约三分之二浮窗"之间来回切换。
        val onlyOne = ordered.size == 1

        if (!onlyOne && slotIndex == primarySlot) {
            ordered.drop(1).forEach { other ->
                TaskInspector.find(other.packageName, other.userId)?.let { task ->
                    FreeformController.resizeTask(task.taskId, dotBounds(), verify = false)
                }
            }
            update { it.copy(manualOverlay = null, message = "其他应用已收回后台（仍在下点里运行）") }
            return@submit
        }

        val target = ordered.firstOrNull { it.slotIndex == slotIndex }
        if (target == null) {
            update { it.copy(message = "这个位置没有应用") }
            return@submit
        }
        // 每次都重新按"包名 + 用户号"找任务：浮窗被全屏过、或转过屏之后旧任务号常已失效，
        // 继续拿它 resize 就会"再也接管不了"（真机 m01932 第三条）。
        // 用户号直接取这个窗口自己的（记 LiveWindow 时带上了），不再按包名回场景里反查 ——
        // 同名包在主机和手机分身上各有一份时，那种反查会挑到另一份。
        val slotUserId = target.userId
        val task = TaskInspector.find(target.packageName, slotUserId)
        if (task == null) {
            update { it.copy(message = "找不到 ${target.packageName} 的窗口，请重新开工") }
            return@submit
        }
        val w = (task.bounds?.right ?: 0) - (task.bounds?.left ?: 0)
        val isDot = w <= 16
        val next = if (isDot) overlayBounds() else dotBounds()
        if (isDot) {
            // 先摆到中间尺寸再放大：两次尺寸变化会给应用派发一次配置变化，
            // 用来替代"手动切一次横竖屏"（本机 shell 锁不了屏幕方向，见 m02016）。
            FreeformController.resizeTask(task.taskId, scaled(next, 3, 5), verify = false)
            Thread.sleep(350)
        }
        // 不复查：紧跟着就会 find 一次做最终校验（见下面的 `val after`），
        // 由那次的结果决定要不要补拉，这里再查一遍纯属重复。
        FreeformController.resizeTask(task.taskId, next, verify = false)
        FreeformController.focusTask(task.taskId, target.component)
        // resize 之后只校验、**不再重新拉起 App**：
        // 真机实测（10-10 10:05 的 diag 日志）我们的启动命令**全都带了 --user 999**，
        // 但对"有分身的应用"二次 `am start` 仍会弹 MIUI 的"选哪一个 nrc"选择框，
        // 反而要用户多点一下（m01969）。所以这里只记录，不再补拉。
        val after = TaskInspector.find(target.packageName, slotUserId)
        val afterW = (after?.bounds?.right ?: 0) - (after?.bounds?.left ?: 0)
        if (after == null || afterW != (next.right - next.left)) {
            // 必须带上 "@用户号"：不带的话启动有分身的应用会弹"选哪一个"的系统选择框
            //（真机 m01948："全屏之后再次点击图标，会让我选择启动哪个 nrc"）。
            val comp = ensureUser(target.component ?: catalog.find(target.packageName)?.component, slotUserId)
            if (comp != null) {
                FreeformController.launchInFreeform(comp, next, appContext)
                Diag.log(TAG, "切换：${target.packageName} resize 未生效，已重新拉起一次")
            }
        }
        // 刚动过窗口 → 接下来 10 s 用快档盯着落位（1.2 起空闲档是 5 s，不能等它慢慢发现）
        fastUntil = System.currentTimeMillis() + TickPolicy.FAST_HOLD_MS
        update {
            it.copy(
                manualOverlay = if (isDot) target.packageName else null,
                message = if (isDot) "${target.packageName} 已浮出小窗" else "${target.packageName} 已收回后台"
            )
        }
    }

    /** 按比例缩小一个矩形（居中），用于"先小后大"两次摆位。 */
    private fun scaled(b: com.miniworld.model.Bounds, num: Int, den: Int): com.miniworld.model.Bounds {
        val w = ((b.right - b.left).toLong() * num / den).toInt().coerceAtLeast(2)
        val h = ((b.bottom - b.top).toLong() * num / den).toInt().coerceAtLeast(2)
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        return com.miniworld.model.Bounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** 收起浮窗：把"手动浮出的那个"缩回隐形小点。 */
    fun collapseFloat() = submit("收起浮窗") {
        val current = state
        val pkg = current.manualOverlay ?: return@submit
        current.windows.filter { it.packageName == pkg }.forEach { window ->
            TaskInspector.find(window.packageName, window.userId)?.let { task ->
                FreeformController.resizeTask(task.taskId, dotBounds(), verify = false)
            }
        }
        fastUntil = System.currentTimeMillis() + TickPolicy.FAST_HOLD_MS
        update { it.copy(manualOverlay = null, message = "已收起浮窗") }
    }

    /**
     * 隐形保活小点：**屏幕外**右下方向的 2×2 像素。
     *
     * 为什么放在屏幕外，而不是屏幕内右下角（m02040 结论）：
     * 这台 ROM 会给可见的自由窗口画一圈系统装饰（记录里的"小角"）。窗口贴在屏幕内右下角时，
     * 那圈装饰会露出屏幕一角，用户明确表示"大半在屏幕外也是坏掉的"。把窗口连装饰一起挪到
     * **真实屏幕之外**，屏幕上就一点都看不见了；而系统的"可见"判定看窗口状态、不看坐标 ——
     * 真机实测（含 9 分钟长测）：任务仍 `visible=true`、`visibleRequested=true`、窗口 `isOnScreen=true`、
     * 进程 `curProcState=2`(TOP)、`isFrozen=false`，保活照旧。
     *
     * 注意用**真实屏幕**尺寸（不是挖掉 Dock / 系统栏的可用区）：小点在屏幕外，
     * 与 Dock、状态栏、导航栏都不再有关系，也就不会再被"可用区"算进去。
     */
    private fun dotBounds(): com.miniworld.model.Bounds {
        val s = ScreenMetrics.read(appContext)
        val left = s.width + DOT_OUT_X
        val top = s.height + DOT_OUT_Y
        return com.miniworld.model.Bounds(left, top, left + 2, top + 2)
    }

    /**
     * 浮出的小窗：宽度取可用区的 2/3，**长宽比与整屏一致**、居中。
     *
     * 真机试过两种：16:9 横屏矩形"太宽"，而跟屏幕同比例的竖屏矩形是"正确的"
     * （m01932 反馈）——横屏游戏会自己在里面留黑边，但窗口比例对了触控映射才正常。
     */
    private fun overlayBounds(): com.miniworld.model.Bounds {
        val a = ScreenMetrics.read(appContext).availableBounds(rightInset = DockView.widthPx(appContext))
        val fullW = (a.right - a.left).coerceAtLeast(1)
        val fullH = (a.bottom - a.top).coerceAtLeast(1)
        // 用户要求「画面要占满浮窗」：窗口就必须跟画面同比例。
        // 横屏游戏是 16:9，所以窗口 = 整宽 × (宽×9/16)，画面铺满、没有黑边。
        // 注意这是与"窗口跟屏幕同比例"二选一：同比例会让横屏画面上下留黑边。
        // 屏幕当前是不是横着：ScreenMetrics 取的是自然方向尺寸（总是竖的 1220×2656），
        // 所以横屏时必须把长边当宽用，否则浮窗又窄又长（真机 m02032 第一条）。
        val rotation = runCatching {
            (appContext.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager).defaultDisplay.rotation
        }.getOrDefault(0)
        val landscape = rotation == 1 || rotation == 3
        val availW = if (landscape) maxOf(fullW, fullH) else minOf(fullW, fullH)
        val availH = if (landscape) minOf(fullW, fullH) else maxOf(fullW, fullH)
        val w = availW * 3 / 4
        val h = (w.toLong() * 9 / 16).toInt().coerceAtMost(availH)
        val left = a.left + (availW - w) / 2
        val top = a.top + (availH - h) / 2
        return com.miniworld.model.Bounds(left, top, left + w, top + h)
    }

    // ------------------------------------------------------------------ 保活

    private fun applyKeepAlive(targets: List<AppTarget>, enable: Boolean): String {
        if (targets.isEmpty()) return "没有可保活的应用"
        val log = ArrayList<String>()
        targets.forEach { target ->
            // 一次下发：一个 sh 进程做完 5 条（原来是 5 条命令 = 5 个进程，约 600 ms）
            val batch = KeepAlive.applyBatch(target.packageName, target.userId, enable)
            val failed = batch.failed
            log += "${if (failed.isEmpty()) "✓" else "!"} ${target.label}：${if (enable) "已保活" else "已撤销"}" +
                if (failed.isEmpty()) "" else "（${failed.size} 项不支持）"
        }
        update { it.copy(keepAliveOn = enable, log = state.log + log, message = log.joinToString("；")) }
        return log.joinToString("；")
    }

    // ------------------------------------------------------------------ 收工

    /**
     * @param killApps true 时连应用一起关掉；默认只把窗口收回全屏并撤销保活。
     * @param onFinished 收工**真正跑完之后**才回调（服务用它来 stopSelf）。
     *   以前服务是"提交完收工就立刻 stopSelf()"，`onDestroy` 里的 `shutdownNow()` 会把正在
     *   干活的工作线程打断 —— 真机事故（2026-10-13）：收工第一条 `dumpsys` 直接
     *   `InterruptedException`，查回来是空列表，后台卡片"摘掉 0/0"，用户看到卡片还在后台。
     */
    fun stop(killApps: Boolean = false, onFinished: (() -> Unit)? = null) = submit("收工") {
        try {
            clearDropState()
            stopWatchdog("收工")
            val current = state
            val log = ArrayList<String>()
            // 「关闭 / 撤销保活」一律以「包名 + 用户号」为单位：同名包在主机和手机分身上各有一份，
            // 只按包名下手会把两份一起关掉（真机事故：关闭 nrc 主机和分身同时被杀）。
            val targets = appTargets(current.windows, current.scene?.slots ?: emptyList())

            // 「关闭」= 直接结束须弥芥子启动的那个 App（含分身上的那份），而不只是停服务
            if (killApps) {
                targets.forEach { target ->
                    // 顺手把启动时记下的任务号带过去：现场查询万一被打断，还能靠它把卡片摘掉。
                    FreeformController.closePackage(
                        target.packageName,
                        target.userId,
                        taskIdsOf(target, current.windows)
                    )
                    log += "× ${target.label} 已结束"
                }
            } else if (current.mode == WorkbenchMode.FREEFORM_TILING) {
                current.windows.forEach { window ->
                    FreeformController.toFullscreen(window.taskId, window.component)
                    log += "↗ ${window.packageName} 已恢复全屏"
                }
            }

            if (current.keepAliveOn) {
                applyKeepAlive(targets, enable = false)
                log += "保活已撤销"
            }

            update {
                it.copy(
                    running = false,
                    windows = emptyList(),
                    keepAliveOn = false,
                    log = log,
                    message = "已收工"
                )
            }
        } finally {
            // 无论中间哪一步抛异常都要回调，否则服务会永远留在"前台服务"里退不出去。
            onFinished?.let { main.post(it) }
        }
    }

    fun shutdown() {
        stopWatchdog("服务销毁")
        // 温和关闭：让已经在跑的收工动作把话说完。`shutdownNow()` 会打断工作线程，
        // 真机事故：收工里的 dumpsys 被打断成 InterruptedException，后台卡片摘不掉。
        runCatching { worker.shutdown() }
    }

    private companion object {
        const val TAG = "WorkbenchController"

        /** 非平铺档位下拿不到真实 bounds，用零矩形明确表示"未知"，不要假装知道。 */
        val UNSET_BOUNDS = com.miniworld.model.Bounds(0, 0, 0, 0)

        /** 隐形小点相对**真实屏幕**右下角的外移量（像素）：整块落在屏幕外，屏幕上什么都看不见。 */
        const val DOT_OUT_X = 16
        const val DOT_OUT_Y = 34

        /** 漏拍巡检间隔：60 s 看一眼，比任何一档节拍都粗，本身几乎不耗电。 */
        const val GUARD_PERIOD_MS = 60_000L

    }
}