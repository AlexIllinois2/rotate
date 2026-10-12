package com.rotatelock

import android.content.Context
import android.provider.Settings
import android.view.Surface

/**
 * 读写系统旋转设置。所有写入都需要 WRITE_SETTINGS（特殊应用权限）。
 */
object RotationController {

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /** 1 = 跟随重力感应（自动旋转开），0 = 锁定角度。 */
    fun autoRotate(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)

    /** 锁定的角度：0=0°、1=90°、2=180°、3=270°。 */
    fun userRotation(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)

    /** 系统当前是否已经处于"锁定到 rotation"的状态（自愈判断用）。 */
    fun isLockedTo(context: Context, rotation: Int): Boolean =
        autoRotate(context) == 0 && userRotation(context) == rotation

    /**
     * 锁定到指定角度。只有第一次进入目标应用时才记录快照；
     * 同一应用改了目标角度只更新期望值，避免污染原始快照。
     */
    fun lock(
        context: Context,
        packageName: String,
        rotation: Int,
        engine: EngineKind = EngineKind.SETTINGS,
    ): Boolean {
        if (!canWrite(context)) return false
        if (AppPrefs.lockedPackage(context) == null) {
            AppPrefs.beginLock(context, packageName, rotation, engine)
        } else if (AppPrefs.lockedPackage(context) != packageName || AppPrefs.lockedRotation(context) != rotation) {
            AppPrefs.updateLock(context, packageName, rotation, engine)
        }
        return write(context, rotation)
    }

    /** 还原进入目标应用前的系统旋转设置。 */
    fun restore(context: Context): Boolean {
        if (AppPrefs.lockedPackage(context) == null) return false
        if (!canWrite(context)) return false
        val baseAuto = AppPrefs.baseAutoRotate(context)
        val baseUser = AppPrefs.baseUserRotation(context)
        AppPrefs.clearLock(context)
        val resolver = context.contentResolver
        return runCatching {
            if (baseUser >= 0) Settings.System.putInt(resolver, Settings.System.USER_ROTATION, baseUser)
            if (baseAuto >= 0) Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, baseAuto)
            true
        }.getOrDefault(false)
    }

    /**
     * 无条件回到"没有锁定"的状态：不管当前用的是哪种方式（系统设置 / 悬浮窗）都清干净。
     * 用于关总开关、改配置、以及界面上的"立即还原"急救按钮。
     */
    fun resetAll(context: Context) {
        val engine = AppPrefs.lockedEngine(context)
        if (engine == EngineKind.SETTINGS) restore(context)
        if (engine == EngineKind.SHIZUKU) {
            // Shizuku 方式要还原 fixed-to-user-rotation 与旋转设置，都是 shell 调用，放后台线程
            Thread { ShizukuRotation.restore(context) }.start()
            return
        }
        OverlayRotation.clear(context)
        AppPrefs.clearLock(context)
    }

    private fun write(context: Context, rotation: Int): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            // 先关自动旋转再写角度，否则部分机型会被传感器立刻覆盖
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
            true
        }.getOrDefault(false)
    }
}
