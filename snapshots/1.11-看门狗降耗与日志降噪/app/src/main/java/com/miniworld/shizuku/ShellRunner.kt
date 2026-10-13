package com.miniworld.shizuku

import android.util.Log
import com.miniworld.system.Diag
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/** 一条 shell 命令的完整结果。任何"成功"判断都必须基于这里的内容，不能凭"没报错"。 */
data class ShellResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean
) {
    val ok: Boolean get() = !timedOut && exitCode == 0

    val combined: String
        get() = listOf(stdout, stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

    fun contains(needle: String): Boolean = combined.contains(needle, ignoreCase = true)

    fun firstLine(): String =
        combined.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** ROM 差异排查时用得上：把命令与输出一起写进日志。 */
    fun describe(): String = "$ $command\n(exit=$exitCode${if (timedOut) ", TIMEOUT" else ""})\n$combined"
}

/**
 * 以 Shizuku（shell, uid 2000）身份执行命令。
 *
 * 为什么用反射调 `Shizuku.newProcess`：
 *  1. 它在 Shizuku 社区里是被反复讨论"可能被移除"的 API（见 Shizuku-API issue #276），
 *     反射调用可以在它变更时优雅降级，而不是编译不过。
 *  2. 拿到的是标准 `java.lang.Process`，读写流、超时销毁都不依赖 Shizuku 的类型。
 *
 * 上游若真的移除它，替代方案是 `Shizuku.bindUserService` + 一个以 app_process 启动的
 * UserService，把同样的命令在服务端执行。接口保持不变，只换这一层实现。
 *
 * 2026-10 真机踩坑：HyperOS 4 上这台机器 logcat 的 main 缓冲区被抽干（读不到任何 App 日志），
 * 所以这里每条命令都会同时写进 [Diag] 的落盘文件，出问题可以 `adb shell cat` 读走。
 * 另外 shell 一律用绝对路径 `/system/bin/sh` 启动，失败才退回 `sh`——
 * Shizuku 服务端的环境变量不由我们控制，PATH 里没有 sh 时会直接启动失败。
 */
object ShellRunner {

    private const val TAG = "ShellRunner"
    private const val DEFAULT_TIMEOUT_MS = 8_000L
    private const val SHELL_ABS = "/system/bin/sh"
    private const val SHELL_FALLBACK = "sh"

    private val newProcessMethod: Method? by lazy {
        runCatching {
            Class.forName("rikka.shizuku.Shizuku")
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
        }.onFailure {
            Log.w(TAG, "Shizuku.newProcess 不可用：${it.message}")
            Diag.log(TAG, "Shizuku.newProcess 反射失败：${it.javaClass.name}: ${it.message}")
        }.onSuccess {
            Diag.log(TAG, "Shizuku.newProcess 反射成功：$it")
        }.getOrNull()
    }

    val available: Boolean get() = newProcessMethod != null

    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val method = newProcessMethod
            ?: return ShellResult(
                command, "", "Shizuku.newProcess 不可用（Shizuku 未授权或 API 已变更）", -1, false
            ).also { Diag.log(TAG, "跳过命令（无通道）：$command") }

        val result = try {
            runOnce(method, command, timeoutMs)
        } catch (t: Throwable) {
            val real = unwrap(t)
            Log.w(TAG, "执行失败：$command", real)
            // 被中断和真报错要分开写：中断是"我们自己把线程掐了"，看日志的人不该把它
            // 当成 ROM 不支持（真机事故：收工打断 dumpsys，日志看上去像"查不到任务"）。
            val what = if (real is InterruptedException) "命令被中断（线程被打断）" else "命令抛异常"
            Diag.log(
                TAG,
                "$what：$command\n  ${real.javaClass.name}: ${real.message}" +
                    "\n  cause=${real.cause?.javaClass?.name}: ${real.cause?.message}"
            )
            ShellResult(command, "", "${real.javaClass.simpleName}: ${real.message}", -1, false)
        }
        // S4：正常命令不再逐条写盘。原来看门狗每 2 秒跑 3 条 dumpsys 就写 3 行，
        // 挂机几十分钟就把 diag.log 顶到 512 KB 上限、整个文件被删重来，反而看不到历史。
        // 失败/超时照旧写全 —— 排障要的正是这些。
        if (!result.ok) Diag.log(TAG, summarize(result))
        return result
    }

    private fun runOnce(method: Method, command: String, timeoutMs: Long): ShellResult {
        val process = try {
            openProcess(method, SHELL_ABS, command)
        } catch (t: Throwable) {
            val real = unwrap(t)
            Diag.log(TAG, "$SHELL_ABS 启动失败（${real.javaClass.simpleName}: ${real.message}），改用 $SHELL_FALLBACK")
            openProcess(method, SHELL_FALLBACK, command)
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val tErr = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        tOut.isDaemon = true
        tErr.isDaemon = true
        tOut.start()
        tErr.start()

        // 注意：这里**不能**用 `process.waitFor(timeout, unit)`。
        //
        // Shizuku 返回的 ShizukuRemoteProcess 继承了 java.lang.Process 的默认 waitFor 实现，
        // 那个实现靠轮询 exitValue() 来判断"还在跑"，且**只捕获 IllegalThreadStateException**。
        // 而 Shizuku 服务端在进程未结束时抛的正是 IllegalThreadStateException，
        // 它经过 Binder 传回客户端会变成 java.lang.IllegalArgumentException("process hasn't exited")——
        // 默认实现不认识这个类型，于是异常直接冒出来：命令刚启动就失败、永远拿不到输出。
        // 真机实测（HyperOS 4 + Shizuku 13）：每一条命令都是这个错，见 diag.log。
        //
        // 所以这里自己轮询 exitValue()，把两种异常都当成"尚未结束"。
        var finished = false
        var code = -1
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                code = process.exitValue()
                finished = true
                break
            } catch (e: IllegalThreadStateException) {
                // 正常：命令还在跑
            } catch (e: IllegalArgumentException) {
                // Shizuku 把服务端的 IllegalThreadStateException 变成了这个
            }
            Thread.sleep(25)
        }
        if (!finished) {
            runCatching { process.destroy() }
            code = -1
        }
        runCatching { tOut.join(800) }
        runCatching { tErr.join(800) }

        return ShellResult(command, out.toString().trim(), err.toString().trim(), code, !finished)
    }

    private fun openProcess(method: Method, shell: String, command: String): Process =
        method.invoke(null, arrayOf(shell, "-c", command), null, null) as Process

    /** 反射调用会把真正的错包在 InvocationTargetException 里，日志要的是里面那个。 */
    private fun unwrap(t: Throwable): Throwable {
        var current = t
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    private fun summarize(r: ShellResult): String = buildString {
        append(r.command).append(" -> exit=").append(r.exitCode)
        if (r.timedOut) append(" TIMEOUT")
        append(" stdout=").append(r.stdout.length).append(" stderr=").append(r.stderr.length)
        // 只有异常情况才带内容，正常命令不重复写一份（日志文件别爆掉）。
        if (!r.ok || r.stdout.isEmpty()) {
            append(" | out=").append(r.stdout.take(200))
            append(" | err=").append(r.stderr.take(200))
        }
    }
}
