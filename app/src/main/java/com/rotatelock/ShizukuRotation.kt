package com.rotatelock

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Shizuku 方式（运行期强制，五个方向都支持）：
 *
 *   1) settings put system accelerometer_rotation 0
 *   2) settings put system user_rotation <角度>        （0/1/2/3，或"保持进入时"读当前角度）
 *   3) wm fixed-to-user-rotation enabled
 *
 * 第 3 条是关键：打开后显示完全忽略应用自己请求的方向、只认 user_rotation，所以**连清单里写死方向的
 * 应用也能被掰到任意角度**（compat 那套覆盖只能表达竖屏/反向横屏两种，补不齐反向竖屏等方向）。
 * 这些命令都由 Shizuku 以 shell 身份执行，不需要「修改系统设置」权限。
 *
 * 离开目标应用时按进入前的快照还原，包括 fixed-to-user-rotation 的原始值。
 */
object ShizukuRotation {

    private const val TAG = "RotationLock"

    fun isReady(context: Context): Boolean =
        ShizukuBridge.status(context) == ShizukuBridge.Status.READY

    /** 进入目标应用：先存快照再动手。返回是否成功。 */
    fun apply(context: Context, packageName: String, mode: OrientationMode): Boolean {
        if (!isReady(context)) {
            AppPrefs.journal(context, "shizuku live: not ready, skip")
            return false
        }
        val rotation = if (mode == OrientationMode.KEEP_CURRENT) {
            currentRotation(context)
        } else {
            mode.userRotation
        }
        val baseFixed = ShizukuBridge.fixedToUserRotation(context)
        AppPrefs.beginLock(context, packageName, rotation, EngineKind.SHIZUKU, baseFixed)
        val result = ShizukuBridge.run(
            context,
            listOf(
                "settings put system accelerometer_rotation 0",
                "settings put system user_rotation " + rotation,
                "wm fixed-to-user-rotation enabled",
            ),
        )
        AppPrefs.setLastResult(context, "shizuku live apply ok=" + result.ok + " rot=" + rotation + " " + result.message)
        AppPrefs.journal(
            context,
            "shizuku live apply " + packageName + " rot=" + rotation + " baseFixed=" + baseFixed + " ok=" + result.ok,
        )
        if (!result.ok) AppPrefs.clearLock(context)
        Log.i(TAG, "shizuku live apply ok=" + result.ok + " rot=" + rotation)
        return result.ok
    }

    /** 离开目标应用：还原 fixed-to-user-rotation 与旋转设置。 */
    fun restore(context: Context): Boolean {
        if (AppPrefs.lockedPackage(context) == null) return false
        val baseAuto = AppPrefs.baseAutoRotate(context)
        val baseUser = AppPrefs.baseUserRotation(context)
        val baseFixed = AppPrefs.baseFixedToUser(context)
        AppPrefs.clearLock(context)

        if (!isReady(context)) {
            AppPrefs.journal(context, "shizuku live: not ready, cannot restore fixed=" + baseFixed)
            return false
        }
        val commands = ArrayList<String>()
        commands.add("wm fixed-to-user-rotation " + baseFixed)
        if (baseUser >= 0) commands.add("settings put system user_rotation " + baseUser)
        if (baseAuto >= 0) commands.add("settings put system accelerometer_rotation " + baseAuto)
        val result = ShizukuBridge.run(context, commands)
        AppPrefs.journal(context, "shizuku live restore fixed=" + baseFixed + " auto=" + baseAuto + " user=" + baseUser + " ok=" + result.ok)
        Log.i(TAG, "shizuku live restore ok=" + result.ok + " fixed=" + baseFixed)
        return result.ok
    }

    /**
     * 兜底：进程被系统杀掉时可能会留下一个还开着的 fixed-to-user-rotation（它是"显示级"设置），
     * 表现成"整个手机被固定在某个角度"。没有会话记录却还开着，就说明是残留，改回 default。
     */
    fun cleanupStaleForce(context: Context) {
        if (AppPrefs.lockedPackage(context) != null) return
        // 全局旋转（Shizuku 方式）也会开着 fixed-to-user-rotation，那是正常状态，不能当残留清掉
        if (GlobalRotation.isEngaged(context) && AppPrefs.engine(context) == EngineKind.SHIZUKU) {
            AppPrefs.journal(context, "cleanup stale skipped: global shizuku engaged")
            return
        }
        if (!isReady(context)) return
        val current = ShizukuBridge.fixedToUserRotation(context)
        if (current == "default") return
        val result = ShizukuBridge.run(context, listOf("wm fixed-to-user-rotation default"))
        AppPrefs.journal(context, "cleanup stale fixed-to-user=" + current + " -> default ok=" + result.ok)
    }

    private fun currentRotation(context: Context): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .rotation
    }.getOrDefault(Surface.ROTATION_0)
}
