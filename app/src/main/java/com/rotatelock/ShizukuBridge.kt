package com.rotatelock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 桥：把"一次性打开系统 compat 方向覆盖"这件事从电脑搬到手机里。
 *
 * 用到的 compat 覆盖（和之前在电脑上跑的 adb 命令完全一样）：
 *   竖屏：OVERRIDE_ANY_ORIENTATION + OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT
 *   横屏：OVERRIDE_ANY_ORIENTATION + OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE
 * 原理见 README 第 10 节；命令由 [UserService] 在 shell 身份下执行。
 */
object ShizukuBridge {

    private const val TAG = "RotationLock"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    /**
     * 第一次绑定要让 Shizuku 以 shell 身份 spawn 一个用户服务进程，真机实测要 6~7 秒，
     * 慢的时候更久；绑定一次之后就一直复用（见 [ensureService]），所以这点等待只付一次。
     */
    private const val BIND_TIMEOUT_SECONDS = 30L

    /** 授权请求码，随便取，只要回调时能对上。 */
    const val REQUEST_CODE = 4213

    enum class Status {
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        READY,
    }

    data class Result(val ok: Boolean, val message: String)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun status(context: Context): Status {
        if (!isInstalled(context)) return Status.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return Status.NOT_RUNNING
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return Status.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        return if (granted == PackageManager.PERMISSION_GRANTED) Status.READY else Status.NO_PERMISSION
    }

    fun statusLabel(context: Context): String = when (status(context)) {
        Status.NOT_INSTALLED -> "未安装 Shizuku"
        Status.NOT_RUNNING -> "Shizuku 未运行（需要先用无线调试启动一次）"
        Status.NO_PERMISSION -> "需要授权本应用使用 Shizuku"
        Status.READY -> "Shizuku 已就绪"
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed: " + it) }
    }

    /** 打开 Shizuku 应用（未安装则返回 false）。 */
    fun openShizuku(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 竖屏/横屏两种系统级覆盖，其它方向不支持（compat 覆盖只有这两种落点）。 */
    fun apply(context: Context, packageName: String, mode: OrientationMode): Result {
        val target = when (mode) {
            OrientationMode.LANDSCAPE -> "OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE"
            OrientationMode.PORTRAIT -> "OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT"
            else -> return Result(false, "系统级强制只支持竖屏 / 横屏")
        }
        return runCommands(
            context,
            listOf(
                "am compat enable OVERRIDE_ANY_ORIENTATION " + packageName,
                "am compat enable " + target + " " + packageName,
            ),
        )
    }

    /** 关闭系统级覆盖，把应用恢复成它本来的行为。 */
    fun reset(context: Context, packageName: String): Result = runCommands(
        context,
        listOf(
            "am compat reset OVERRIDE_ANY_ORIENTATION " + packageName,
            "am compat reset OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE " + packageName,
            "am compat reset OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT " + packageName,
        ),
    )

    /** 以 shell 身份执行任意几条命令（调用方负责线程：这是阻塞调用）。 */
    fun run(context: Context, commands: List<String>): Result = runCommands(context, commands)

    /**
     * 当前显示级的 fixed-to-user-rotation 值：default / enabled / disabled。
     * 打开它之后显示会完全忽略应用自己请求的方向，只认 user_rotation —— 这样连"自带硬锁"的应用
     * 也能被强制到任意角度（这正是 Shizuku 方式补齐反向竖屏等方向的关键）。
     */
    fun fixedToUserRotation(context: Context): String {
        val output = run(context, listOf("wm fixed-to-user-rotation")).message
        val token = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("exit=") && !it.startsWith("wm ") }
            .lastOrNull()
            .orEmpty()
        return when {
            token.endsWith("enabled") -> "enabled"
            token.endsWith("disabled") -> "disabled"
            else -> "default"
        }
    }

    /** 绑定一次、之后复用：反复 bind/unbind 会让每次都重新 spawn 一个 shell 进程。 */
    @Volatile
    private var cachedConnection: Connection? = null

    private fun runCommands(context: Context, commands: List<String>): Result {
        when (status(context)) {
            Status.NOT_INSTALLED -> return Result(false, "未安装 Shizuku")
            Status.NOT_RUNNING -> return Result(false, "Shizuku 未运行：先在 Shizuku 里用「无线调试」启动一次")
            Status.NO_PERMISSION -> return Result(false, "还没有授权本应用使用 Shizuku")
            Status.READY -> Unit
        }

        var service = ensureService(context)
        if (service == null) {
            // 一次重试：可能是上次的连接已经死了（Shizuku 服务重启过）
            cachedConnection = null
            service = ensureService(context)
        }
        if (service == null) return Result(false, "绑定 Shizuku 用户服务失败（超时或服务未就绪）")

        val journal = StringBuilder()
        var allOk = true
        var needRebind = false
        commands.forEach { command ->
            val output = runCatching { service.runCommand(command) }
                .getOrElse {
                    needRebind = true
                    "exit=-1\n" + it
                }
            val code = Regex("exit=(-?\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (code != 0) allOk = false
            journal.append(command).append('\n')
                .append(output.lineSequence().joinToString("\n") { "  " + it }).append('\n')
        }
        if (needRebind) cachedConnection = null
        return Result(allOk, journal.toString().trim())
    }

    private fun ensureService(context: Context): IUserService? {
        cachedConnection?.service?.let { return it }
        val connection = Connection()
        val args = userServiceArgs(context)
        return try {
            val startedAt = System.currentTimeMillis()
            Shizuku.bindUserService(args, connection)
            val bound = connection.latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - startedAt
            AppPrefs.journal(context, "shizuku bind bound=" + bound + " wait=" + elapsed + "ms")
            Log.i(TAG, "shizuku bind bound=" + bound + " wait=" + elapsed + "ms")
            val service = connection.service
            if (bound && service != null) cachedConnection = connection
            service
        } catch (error: Throwable) {
            AppPrefs.journal(context, "shizuku bind failed: " + error)
            Log.w(TAG, "shizuku bind failed: " + error)
            null
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs {
        val component = ComponentName(context.packageName, UserService::class.java.name)
        return Shizuku.UserServiceArgs(component)
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)
    }

    private class Connection : ServiceConnection {
        val latch = CountDownLatch(1)

        @Volatile
        var service: IUserService? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IUserService.Stub.asInterface(binder) else null
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }
}
