package com.onestep.workbench.system

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘诊断日志。
 *
 * 为什么需要它：这台 HyperOS 4 把 logcat 的 main 缓冲区抽干了——
 * `adb logcat -g` 显示 `main: ring buffer is 2 MiB (512 KiB consumed, 0 B readable)`，
 * `adb logcat -d` 一行也读不到（连 crash 缓冲都是空的）。也就是说 App 里所有 Log.i/Log.w
 * 在电脑上都看不见。自检结果和电脑 adb 实测相反的时候，没有日志就无法区分
 * 「命令根本没跑」「命令跑了但报错」「命令正常但结果被解析错」这三种情况。
 *
 * 于是把关键过程写到应用外部私有目录 `/sdcard/Android/data/<包名>/files/diag.log`：
 * 不需要任何权限，电脑上 `adb shell cat` 就能读走，用户不用做任何操作。
 */
object Diag {

    private const val TAG = "Diag"
    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 512L * 1024

    private val lock = Any()

    @Volatile
    private var sink: File? = null

    /** 日志文件路径；没初始化成功时给个能看懂的占位符。 */
    val path: String get() = sink?.absolutePath ?: "(尚未初始化)"

    fun init(context: Context) {
        if (sink != null) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            // 一次会话的日志不该无限增长；超过上限就从头来过。
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            sink = file
            log(TAG, "==== 日志开始 · 文件=${file.absolutePath} ====")
        }.onFailure {
            Log.w(TAG, "初始化失败：${it.message}")
        }
    }

    /** 同时写 logcat 和文件。文件没准备好时静默降级，绝不因为日志把业务搞挂。 */
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val file = sink ?: return
        val line = "${stamp()} [$tag] $message\n"
        runCatching { synchronized(lock) { file.appendText(line) } }
    }

    // SimpleDateFormat 不是线程安全的，而日志会从多个线程写；每次新建一个最省心。
    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
