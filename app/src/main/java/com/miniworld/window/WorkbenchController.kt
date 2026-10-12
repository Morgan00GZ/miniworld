package com.miniworld.window

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miniworld.apps.AppCatalog
import com.miniworld.data.SceneStore
import com.miniworld.model.LayoutPreset
import com.miniworld.model.LiveWindow
import com.miniworld.model.Scene
import com.miniworld.model.WorkbenchMode
import com.miniworld.service.DockView
import com.miniworld.system.Diag
import com.miniworld.system.FreeformController
import com.miniworld.system.KeepAlive
import com.miniworld.system.ScreenMetrics
import com.miniworld.system.TaskInspector
import java.util.concurrent.Executors

/**
 * 工作台编排器。
 *
 * 三件事：
 *  1. 按场景把应用拉起来（走 FreeformController 的策略链），并缩成右下角 2×2 的隐形小点
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
        val log: List<String> = emptyList()
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
    private fun handBackStolenFocus() {
        if (state.manualOverlay != null) return
        if (appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            return
        }
        val managed = (
            state.windows.map { it.packageName } +
                (state.scene?.slots?.map { it.packageName } ?: emptyList())
            ).toSet()
        if (managed.isEmpty()) return

        val snap = TaskInspector.focusSnapshot()
        val focusedPkg = snap.focusedPackage ?: return
        if (!managed.contains(focusedPkg)) return
        val target = snap.resumedTaskId ?: return
        if (target == snap.focusedTaskId) return
        val now = System.currentTimeMillis()
        if (now - lastFocusHandBackAt < 3_000) return
        lastFocusHandBackAt = now
        Diag.log(TAG, "焦点被抢：$focusedPkg（任务 ${snap.focusedTaskId}）-> 交还给任务 $target")
        FreeformController.focusTask(target, null)
    }

    private val reconcileTick = object : Runnable {
        override fun run() {
            if (!state.running) return
            submit("校正窗口") { reconcileBounds() }
            main.postDelayed(this, 2_000)
        }
    }

    /** 屏幕方向/尺寸变化后立刻校正一次（不等看门狗的下一拍）。 */
    fun forceReconcile() = submit("旋转后校正") { reconcileBounds() }

    private fun reconcileBounds() {
        val current = state
        val scene = current.scene ?: return
        if (current.mode != WorkbenchMode.FREEFORM_TILING) return

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
            LayoutEngine.layout(available, current.preset, ordered.size)
        }
        ordered.forEachIndexed { index, window ->
            val target = targets.getOrElse(index) { targets.last() }
            // 用户手动浮出的那个小窗不要动，否则 6 秒后就被看门狗掰回小点
            if (window.packageName == current.manualOverlay) return@forEachIndexed
            val task = TaskInspector.find(window.packageName) ?: return@forEachIndexed
            if (!task.isFreeform) return@forEachIndexed
            if (task.bounds != target) {
                // 和弹出时一样走"先小后大"两步：单次 resize 在转屏之后经常不生效，
                // 结果就是"收起状态下转一下屏，浮窗又自己弹出来"（真机 m02032 第二条）。
                FreeformController.resizeTask(task.taskId, scaled(target, 3, 5))
                Thread.sleep(200)
                FreeformController.resizeTask(task.taskId, target)
                Diag.log(TAG, "校正：${window.packageName} ${task.bounds} -> $target")
            }
        }

        // 焦点被别的全屏任务抢走 → 还给用户真正在看的那个任务：只发一条 am task focus，几何不动。
        handBackStolenFocus()

        // 把**当前应该在前台的那个**重新置顶（不是固定第一个）。
        // 但用户手动浮出小窗时要放过：他一浮出，主窗再抢前台就会把小窗顶掉（"闪现一下又消失"）。
        if (current.manualOverlay != null) return
        ordered.firstOrNull()?.let { main ->
            TaskInspector.find(main.packageName)?.let { task ->
                FreeformController.focusTask(task.taskId, main.component)
            }
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
                    tasks.any {
                        it.packageName == slot.packageName &&
                            (slot.userId == 0 || it.userId == slot.userId)
                    }
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
            LayoutEngine.layout(available, scene.preset, scene.slots.size)
        }
        val log = ArrayList<String>()
        log += "可用区=$available ${screen.width}x${screen.height} 顶栏=${screen.topInset}" +
            " 底栏=${screen.bottomInset} Dock占宽=${dockInset}px"
        log += "档位=${mode.label}"

        val windows = ArrayList<LiveWindow>()

        if (mode == WorkbenchMode.FREEFORM_TILING) {
            scene.slots.forEachIndexed { index, slot ->
                val component = (slot.component ?: catalog.find(slot.packageName)?.component)?.let { if (slot.userId != 0) "$it@${slot.userId}" else it }
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
                val component = (slot.component ?: catalog.find(slot.packageName)?.component)?.let { if (slot.userId != 0) "$it@${slot.userId}" else it }
                if (component == null) {
                    log += "× ${slot.label}：找不到可启动组件"
                    return@forEachIndexed
                }
                val ok = FreeformController.launchPlain(component)
                log += "${if (ok) "✓" else "×"} ${slot.label} → 普通启动"
                if (ok) {
                    val task = TaskInspector.waitForTask(slot.packageName, 2_000)
                    if (task != null) {
                        windows += LiveWindow(
                            slotIndex = index,
                            packageName = slot.packageName,
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
            for (attempt in 0 until 12) {
                Thread.sleep(800)
                var drifted = 0
                scene.slots.forEachIndexed { index, slot ->
                    val target = boundsList.getOrElse(index) { boundsList.last() }
                    val component = (slot.component ?: catalog.find(slot.packageName)?.component)?.let { if (slot.userId != 0) "$it@${slot.userId}" else it }
                    val task = TaskInspector.find(slot.packageName, slot.userId) ?: return@forEachIndexed
                    if (task.bounds != target) {
                        FreeformController.resizeTask(task.taskId, target)
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
                TaskInspector.find(mainSlot.packageName)?.let { mainTask ->
                    FreeformController.focusTask(mainTask.taskId, mainSlot.component)
                }
                if (drifted == 0 && attempt >= 3) break
            }
            // 校正完把最新任务号同步进 windows，Dock 的切换就靠它
            scene.slots.forEachIndexed { index, slot ->
                TaskInspector.find(slot.packageName, slot.userId)?.let { fresh ->
                    val live = LiveWindow(
                        slotIndex = index,
                        packageName = slot.packageName,
                        component = slot.component ?: catalog.find(slot.packageName)?.component,
                        taskId = fresh.taskId,
                        bounds = fresh.bounds ?: boundsList.getOrElse(index) { boundsList.last() },
                        launchedBy = "settled"
                    )
                    val tracked = windows.indexOfFirst { it.packageName == slot.packageName }
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
            val mainComponent = main.component ?: catalog.find(main.packageName)?.component
            val mainTask = TaskInspector.find(main.packageName, null)
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
        applyKeepAlive(windows.map { it.packageName }, enable = true)

        // 开工后启动窗口看门狗（每 6 秒把跑偏的自由窗口拉回格子）
        main.removeCallbacks(reconcileTick)
        main.postDelayed(reconcileTick, 6_000)
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
                TaskInspector.find(other.packageName)?.let { task ->
                    FreeformController.resizeTask(task.taskId, dotBounds())
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
        val slotUserId = current.scene?.slots
            ?.firstOrNull { it.packageName == target.packageName }?.userId ?: 0
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
            FreeformController.resizeTask(task.taskId, scaled(next, 3, 5))
            Thread.sleep(350)
        }
        FreeformController.resizeTask(task.taskId, next)
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
            val rawComp = target.component ?: catalog.find(target.packageName)?.component
            val comp = rawComp?.let {
                if (slotUserId != 0 && !it.contains('@')) "$it@$slotUserId" else it
            }
            if (comp != null) {
                FreeformController.launchInFreeform(comp, next, appContext)
                Diag.log(TAG, "切换：${target.packageName} resize 未生效，已重新拉起一次")
            }
        }
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
            TaskInspector.find(window.packageName, null)?.let { task ->
                FreeformController.resizeTask(task.taskId, dotBounds())
            }
        }
        update { it.copy(manualOverlay = null, message = "已收起浮窗") }
    }

    /** 隐形保活小点：右下角 2×2 像素。 */
    private fun dotBounds(): com.miniworld.model.Bounds {
        val a = ScreenMetrics.read(appContext).availableBounds(rightInset = DockView.widthPx(appContext))
        return com.miniworld.model.Bounds(a.right - 2, a.bottom - 2, a.right, a.bottom)
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

    private fun applyKeepAlive(packages: List<String>, enable: Boolean): String {
        if (packages.isEmpty()) return "没有可保活的应用"
        val log = ArrayList<String>()
        packages.forEach { pkg ->
            val applied = KeepAlive.apply(pkg, enable)
            val failed = applied.filterNot { it.success }
            log += "${if (failed.isEmpty()) "✓" else "!"} $pkg：${if (enable) "已保活" else "已撤销"}" +
                if (failed.isEmpty()) "" else "（${failed.size} 项不支持）"
        }
        update { it.copy(keepAliveOn = enable, log = state.log + log, message = log.joinToString("；")) }
        return log.joinToString("；")
    }

    // ------------------------------------------------------------------ 收工

    /** @param killApps true 时连应用一起关掉；默认只把窗口收回全屏并撤销保活。 */
    fun stop(killApps: Boolean = false) = submit("收工") {
        main.removeCallbacks(reconcileTick)
        val current = state
        val log = ArrayList<String>()

        // 「关闭」= 直接结束须弥芥子启动的那个 App（含分身上的那份），而不只是停服务
        if (killApps) {
            val targets = (current.windows.map { it.packageName } +
                (current.scene?.slots?.map { it.packageName } ?: emptyList())).distinct()
            targets.forEach { pkg ->
                val uid = current.scene?.slots?.firstOrNull { it.packageName == pkg }?.userId ?: 0
                FreeformController.closePackage(pkg, uid)
                log += "× $pkg 已结束（用户 $uid）"
            }
        }

        if (current.mode == WorkbenchMode.FREEFORM_TILING) {
            current.windows.forEach { window ->
                if (killApps) {
                    FreeformController.closePackage(window.packageName)
                    log += "× ${window.packageName} 已关闭"
                } else {
                    FreeformController.toFullscreen(window.taskId, window.component)
                    log += "↗ ${window.packageName} 已恢复全屏"
                }
            }
        } else if (killApps) {
            current.windows.forEach { FreeformController.closePackage(it.packageName) }
            log += "已关闭所有应用"
        }

        if (current.keepAliveOn) {
            applyKeepAlive(current.windows.map { it.packageName }, enable = false)
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
    }

    fun shutdown() {
        main.removeCallbacks(reconcileTick)
        runCatching { worker.shutdownNow() }
    }

    private companion object {
        const val TAG = "WorkbenchController"

        /** 非平铺档位下拿不到真实 bounds，用零矩形明确表示"未知"，不要假装知道。 */
        val UNSET_BOUNDS = com.miniworld.model.Bounds(0, 0, 0, 0)
    }
}
