package com.rotatelock

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务：这个类的实例运行在 Shizuku 拉起的独立进程里，Linux 身份是 shell(2000)，
 * 所以它执行的命令和你在电脑上敲 `adb shell ...` 等价 —— 这就是"应用内自带无线 adb"的落点。
 *
 * 之所以不用 Shizuku.newProcess：该方法在 Shizuku API 13.1.5 已经改成 private
 * （官方 changelog 写明计划在 API 14 移除），UserService 才是官方推荐做法。
 */
class UserService : IUserService.Stub {

    /** Shizuku 优先用带 Context 的构造函数，没有则用无参构造函数；两个都留着最稳。 */
    @Suppress("unused")
    constructor(context: Context) : super()

    @Suppress("unused")
    constructor() : super()

    override fun destroy() {
        Log.i(TAG, "user service destroy")
        System.exit(0)
    }

    override fun runCommand(command: String): String {
        Log.i(TAG, "user service run: " + command)
        return try {
            val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            builder.redirectErrorStream(true)
            // am / app_process 都靠 PATH 找，显式给一份，不依赖继承来的环境
            builder.environment()["PATH"] = "/system/bin:/system/xbin"
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else -1
            if (!finished) runCatching { process.destroyForcibly() }
            "exit=" + code + "\n" + output.trim()
        } catch (error: Throwable) {
            Log.w(TAG, "user service command failed: " + error)
            "exit=-1\n" + error
        }
    }

    private companion object {
        const val TAG = "RotationLock"
    }
}
