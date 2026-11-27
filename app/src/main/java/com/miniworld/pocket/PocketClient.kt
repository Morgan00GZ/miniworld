package com.miniworld.pocket

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.miniworld.shizuku.ShizukuGateway
import com.miniworld.system.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 「倒置熄屏」的应用侧：想开的时候在这里拿锁、想关的时候在这里放锁。
 *
 * 干三件事：把 Shizuku 的 UserService（[PocketService]，shell 身份）绑起来、
 * 让它拿/放接近传感器关屏锁、把「是否真的武装中」暴露给 UI 和常驻通知。
 *
 * 为什么不用 ShellRunner 跑一次性命令：那把锁要跨小时持有，必须是活着的
 * binder 对象；一次性 app_process 退出时锁就跟着没了（真机验证过）。
 *
 * 线程模型与 WorkbenchController 一致：所有 binder 调用都串行在一个 worker 上，
 * 回调回主线程；没开启这个功能时没有任何轮询、没有连接、没有额外唤醒。
 */
object PocketClient {

    data class State(
        /** 用户要求开启，且锁确实拿到了。 */
        val armed: Boolean = false,
        val message: String = "未开启"
    )

    private const val TAG = "PocketClient"
    private const val SERVICE_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 10_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 服务/界面侧的回调（状态一变就通知，主线程）。 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocket-client").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * 交给服务进程盯着「宿主还活着」的凭据。
     *
     * 必须是这个单例里的强引用字段：binder 的死亡通知是挂在 token 上的，token 被回收
     * 就等于没监听 —— 那正是我们要避免的「应用死了、锁还在」状态。
     */
    private val clientToken = Binder()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remote: IPocketService? = null

    @Volatile
    private var armed = false

    @Volatile
    private var initialized = false

    private var connecting: CountDownLatch? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = runCatching { IPocketService.Stub.asInterface(binder) }.getOrNull()
            connecting?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            if (armed) {
                // Shizuku 被杀 / 用户撤销授权时锁会跟着进程一起消失，界面必须跟着改回来，
                // 否则用户以为自己还在挂机，其实屏幕早就亮了。
                armed = false
                publish("Shizuku 服务已断开，倒置熄屏已停止")
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (armed) {
            armed = false
            publish("Shizuku 已退出，倒置熄屏已停止")
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            .onFailure { Diag.log(TAG, "注册 binder 死亡监听失败：${it.message}") }
    }

    fun isArmed(): Boolean = armed

    fun addListener(newListener: (State) -> Unit) {
        listeners += newListener
        main.post { newListener(_state.value) }
    }

    /** 必须和 [addListener] 成对调用：这个列表是单例的，漏摘会一直持有已销毁的 Service。 */
    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    /**
     * 开 / 关。回调在主线程；[ok] 为 false 时 [message] 是可以直接给用户看的原因。
     */
    fun setArmed(enabled: Boolean, done: (Boolean, String) -> Unit = { _, _ -> }) {
        val context = appContext
        worker.execute {
            val result = if (enabled) {
                if (context == null) false to "内部状态未初始化" else enable(context)
            } else {
                disable()
            }
            publish(result.second)
            main.post { done(result.first, result.second) }
        }
    }

    /**
     * 停服务、关场景时用；不关心结果，但**状态必须回灌**。
     *
     * 漏了 publish 会让主页一直显示「已开启」（锁其实已经放了），用户下次看到
     * 一个亮着的开关会以为功能生效了 —— 这类"界面在说真话之外的话"是本项目最忌讳的。
     */
    fun releaseQuietly() {
        worker.execute {
            val result = disable()
            publish(result.second)
        }
    }

    private fun enable(context: Context): Pair<Boolean, String> {
        if (!ShizukuGateway.state.value.ready) {
            return false to "Shizuku 未就绪（需要它在运行且已授权）"
        }
        val service = ensureService(context) ?: return false to "连接 Shizuku 服务失败，请确认授权还在"
        val acquired = runCatching { service.acquire() }.getOrDefault(false)
        val uid = runCatching { service.peerUid() }.getOrDefault(-1)
        if (!acquired) {
            // 失败才把服务进程里的反射细节打出来：成功时那串签名只是噪音。
            val detail = runCatching { service.diagnose() }.getOrDefault("")
            Diag.log(TAG, "熄屏锁获取失败（uid=$uid）$detail")
            return false to "获取熄屏锁失败（uid=$uid）"
        }
        Diag.log(TAG, "熄屏锁已获取（uid=$uid）")
        if (uid != 2000 && uid != 0) {
            runCatching { service.release() }
            return false to "Shizuku 跑在 uid=$uid，不是 shell，锁会被系统忽略"
        }
        // 让服务盯着我们的 binder：应用被强停 / 崩了，锁要跟着放掉，不能留在 shell 进程里
        runCatching { service.watchDeath(clientToken) }
            .onFailure { Diag.log(TAG, "注册宿主死亡监听失败：${it.message}") }
        armed = true
        return true to "倒置熄屏已开启：把手机屏幕朝下扣下即熄屏，挂机继续跑，拿起自动亮屏"
    }

    private fun disable(): Pair<Boolean, String> {
        val service = remote
        if (service != null) {
            runCatching { service.release() }
            // 让 shell 进程自己退掉：只解绑的话它会一直挂在 ps 里占内存（真机测过）
            runCatching { service.shutdown() }
        }
        val args = userServiceArgs
        if (args != null) {
            // remove=true：连进程一起收掉。这个服务只在开启期间存在，没必要留着。
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        remote = null
        armed = false
        Diag.log(TAG, "release")
        return true to "倒置熄屏已关闭"
    }

    private fun ensureService(context: Context): IPocketService? {
        remote?.let { return it }
        val args = userServiceArgs ?: buildArgs(context).also { userServiceArgs = it }
        val latch = CountDownLatch(1)
        connecting = latch
        val bound = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
        if (!bound) {
            connecting = null
            Diag.log(TAG, "bindUserService 调用失败")
            return null
        }
        val connected = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        connecting = null
        if (!connected) Diag.log(TAG, "绑定 UserService 超时")
        return remote
    }

    private fun buildArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PocketService::class.java.name))
            .processNameSuffix("pocket")
            .tag("miniworld-pocket")
            .version(SERVICE_VERSION)
            // daemon=false：跟着 binder 活着，解绑即退出，不常驻后台
            .daemon(false)
            .debuggable(false)

    private fun publish(message: String) {
        val next = State(armed = armed, message = message)
        _state.value = next
        main.post { listeners.forEach { it(next) } }
    }
}
