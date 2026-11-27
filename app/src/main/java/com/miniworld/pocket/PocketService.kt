package com.miniworld.pocket

import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.os.WorkSource
import java.lang.reflect.Method

/**
 * 「倒置熄屏」的提权侧实现：**跑在 Shizuku 的 UserService 进程里（shell, uid=2000）**，
 * 不是应用自己的进程 —— 这一点是本功能的全部前提，别改成普通 Service。
 *
 * 为什么必须走 shell：接近传感器关屏锁（PROXIMITY_SCREEN_OFF_WAKE_LOCK）由系统
 * 在 PowerManagerService 里按「应用身份 + 是否后台」双重把关。真机实测（2026-11-26，
 * Redmi 25098PN5AC / Android 17）：
 *  - 应用自己 acquire（即使常驻通知的前台服务在跑、isForeground=true）→ 锁被标 DISABLED，
 *    屏幕不灭；退到后台以后更是直接被禁用；
 *  - 以 uid=2000 调同一个 acquireWakeLock → 锁正常 ACQ，遮住传感器后屏幕真灭、
 *    设备不睡（wakefulness 仍是 Awake）、前台游戏 CPU 持续增长、声音不断。
 * 所以这里直接拿 Hidden API，经由 ServiceManager 反射，不依赖任何第三方 native 库。
 *
 * 生命周期：由 PocketClient 通过 Shizuku.bindUserService 绑定；用户关闭或
 * 进程被回收时 releaseWakeLock。daemon=false，进程随解绑退出，不会常驻。
 */
class PocketService : IPocketService.Stub() {

    /** 锁的凭据。同一个 token 才能释放；一个服务进程只需要一把锁。 */
    private val token = Binder()

    private var power: Any? = null
    private var holding = false
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** 最近一次调用的细节，只用于诊断（写进应用的 diag.log，本机 logcat 读不到）。 */
    private var diagnose = "未调用"

    override fun acquire(): Boolean {
        if (holding) return true
        val manager = try {
            power ?: loadPowerManager().also { power = it }
        } catch (t: Throwable) {
            diagnose = "拿不到 power 服务：${t.javaClass.simpleName}: ${t.message}"
            return false
        }
        val method = pickAcquireMethod(manager)
        if (method == null) {
            diagnose = "没有 acquireWakeLock(IBinder,int,String,String,...) 签名"
            return false
        }
        val args = buildAcquireArgs(method)
        return try {
            method.invoke(manager, *args)
            holding = true
            diagnose = "ok · ${signatureOf(method)}"
            true
        } catch (t: Throwable) {
            // InvocationTargetException 会把真正的原因包在 cause 里，必须拆开看。
            val cause = t.cause ?: t
            holding = false
            diagnose = "调用失败 · ${signatureOf(method)} · ${cause.javaClass.simpleName}: ${cause.message}"
            false
        }
    }

    override fun release(): Boolean = releaseLock()

    override fun shutdown() {
        releaseLock()
        diagnose = "shutdown"
        // 先让这次 binder 调用回完再退场，否则客户端会收到 DeadObjectException
        Thread {
            Thread.sleep(200)
            System.exit(0)
        }.apply { isDaemon = false }.start()
    }

    /** 放锁的实际动作；正常关闭和「宿主已死」两条路都走这里。 */
    private fun releaseLock(): Boolean {
        if (!holding) return true
        val manager = power ?: return true
        return try {
            val method = manager.javaClass.methods.firstOrNull {
                it.name == "releaseWakeLock" && it.parameterTypes.size == 2
            } ?: return false
            method.invoke(manager, token, FLAGS)
            holding = false
            diagnose = "released"
            true
        } catch (t: Throwable) {
            diagnose = "释放失败：${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    /**
     * 宿主进程的死亡通知。
     *
     * 为什么需要它：UserService 跑在 shell 进程里，和应用进程是两条命。应用被强停或崩掉时
     * binder 会断，但锁不会自己松开 —— 那把 PROXIMITY 锁留在 shell 里，手机一进兜就变成
     * 「屏幕灭 + 整机全速跑」，用户毫无感知。真机验过：强停应用后锁还 ACQ 着。
     */
    override fun watchDeath(client: IBinder) {
        if (deathRecipient != null) return
        val recipient = IBinder.DeathRecipient {
            Log.i(LOG_TAG, "宿主进程已结束，主动放锁并退出")
            runCatching { releaseLock() }
            // 放锁之后自己退场：Shizuku 不一定会来收这个进程（真机测过，应用强停后
            // 它会一直挂在 ps 里），留着就是一个空转的 shell 进程。
            System.exit(0)
        }
        // 必须自己攥着引用：Binder 内部对 recipient 只持弱引用，本地变量被回收后就收不到回调
        deathRecipient = recipient
        runCatching { client.linkToDeath(recipient, 0) }
            .onFailure {
                diagnose = "监听宿主死亡失败：${it.message}"
                Log.w(LOG_TAG, diagnose)
            }
    }

    override fun isHolding(): Boolean = holding

    override fun peerUid(): Int = Process.myUid()

    override fun diagnose(): String = "$diagnose · holding=$holding · uid=${Process.myUid()}"

    /**
     * 反射 ServiceManager → android.os.IPowerManager$Stub → asInterface(power)。
     * 不走 getSystemService：UserService 进程里没有应用 Context 系的那套管理器缓存。
     */
    private fun loadPowerManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "power") as IBinder
        val stub = Class.forName("android.os.IPowerManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    /**
     * 挑签名：首参必须是 IBinder，且前四个是 (IBinder, int, String, String)；
     * 多个重载时取参数最多的那个（新版多出来的都是 WorkSource / historyTag / uid / 回调）。
     */
    private fun pickAcquireMethod(manager: Any): Method? =
        manager.javaClass.methods
            .filter { method ->
                method.name == "acquireWakeLock" &&
                    method.parameterTypes.size >= 4 &&
                    method.parameterTypes[0] == IBinder::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[2] == String::class.java &&
                    method.parameterTypes[3] == String::class.java
            }
            .maxByOrNull { it.parameterTypes.size }

    /**
     * 按形参类型填实参。
     *
     * 末尾的 uid 一律传 0（"未知，按调用方算"）—— 真机对照过：传 2000 时调用不报错，
     * 但锁在 dumpsys power 里根本不出现（PowerManagerService 的 app-op 归属判定会静默丢弃），
     * 传 0 才会被系统记账（这条是 shell 侧逐参数试出来的）。
     */
    private fun buildAcquireArgs(method: Method): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        var ints = 0
        var strings = 0
        for (i in types.indices) {
            when {
                types[i] == IBinder::class.java -> args[i] = token
                types[i] == Int::class.javaPrimitiveType -> {
                    args[i] = if (ints == 0) FLAGS else 0
                    ints++
                }
                types[i] == String::class.java -> {
                    args[i] = if (strings == 0) TAG else SHELL_PACKAGE
                    strings++
                }
                types[i] == WorkSource::class.java -> args[i] = null
                types[i] == Boolean::class.javaPrimitiveType -> args[i] = false
                types[i] == Long::class.javaPrimitiveType -> args[i] = 0L
                else -> args[i] = null
            }
        }
        return args
    }

    private fun signatureOf(method: Method): String =
        method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } + ")"

    private companion object {
        /** PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK */
        const val FLAGS = 0x00000020
        const val TAG = "miniworld:pocket"
        const val LOG_TAG = "PocketService"
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
