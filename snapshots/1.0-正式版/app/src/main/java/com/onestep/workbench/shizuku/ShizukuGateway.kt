package com.onestep.workbench.shizuku

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

data class ShizukuState(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val uid: Int = -1,
    val version: Int = -1,
    val message: String = "未初始化"
) {
    /** 只有"binder 活着 + 已授权"才能执行命令。 */
    val ready: Boolean get() = binderAlive && permissionGranted

    val uidLabel: String
        get() = when (uid) {
            0 -> "root (uid=0)"
            2000 -> "shell (uid=2000)"
            -1 -> "未知"
            else -> "uid=$uid"
        }
}

/**
 * Shizuku 授权与连接状态。只负责"有没有权限"，不负责跑命令（那是 ShellRunner）。
 */
object ShizukuGateway {

    private const val TAG = "ShizukuGateway"
    private const val REQUEST_CODE = 1001
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var initialized = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            Log.i(TAG, "权限结果：$grantResult")
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 已连接")
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder 已断开")
        refresh()
    }

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { Log.w(TAG, "注册 Shizuku 监听失败：${it.message}") }
        refresh(app)
    }

    fun refresh(app: Application? = null) {
        val context = app ?: AppHolder.application
        val installed = context?.let { isInstalled(it.packageManager) } ?: _state.value.installed

        if (!installed) {
            _state.value = ShizukuState(
                installed = false,
                message = "未安装 Shizuku。请先安装 Shizuku 并通过 adb 或无线调试启动它。"
            )
            return
        }

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = ShizukuState(
                installed = true,
                message = "Shizuku 已安装但服务未运行。请在 Shizuku 里通过 adb / 无线调试启动服务。"
            )
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

        _state.value = ShizukuState(
            installed = true,
            binderAlive = true,
            permissionGranted = granted,
            uid = uid,
            version = version,
            message = if (granted) {
                "已授权 · ${
                    when (uid) {
                        0 -> "root (uid=0)"
                        2000 -> "shell (uid=2000)"
                        else -> "uid=$uid"
                    }
                } · API v$version"
            } else {
                "Shizuku 已运行，但本应用尚未获得授权。点下面的按钮申请。"
            }
        )
    }

    fun requestPermission() {
        if (!_state.value.installed) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "申请授权失败：${it.message}") }
    }

    private fun isInstalled(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)
}

/** 给没有 Context 参数的调用点用（例如刷新状态）。 */
object AppHolder {
    @Volatile
    var application: Application? = null
}
