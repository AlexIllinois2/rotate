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
        val ok = runCatching {
            if (baseUser >= 0) Settings.System.putInt(resolver, Settings.System.USER_ROTATION, baseUser)
            if (baseAuto >= 0) Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, baseAuto)
            true
        }.getOrDefault(false)
        // 全局档位还在接管时，离开按应用锁定要回到"全局状态"，而不是回到接管全局之前的快照
        if (GlobalRotation.isEngaged(context)) GlobalRotation.apply(context)
        return ok
    }

    /**
     * 无条件回到"没有锁定"的状态：不管当前用的是哪种方式（系统设置 / 悬浮窗）都清干净。
     * 用于关总开关、改配置、以及界面上的"立即还原"急救按钮。
     *
     * 全局档位是另一个功能、有自己的开关，这里不会把它一起关掉；只保证按应用锁定清干净后，
     * 系统设置回到全局档位要求的状态。
     */
    /**
     * 无条件回到"没有锁定"的状态：不管当前用的是哪种方式（系统设置 / 悬浮窗 / Shizuku）都清干净。
     * 用于关总开关、改配置、换锁定方式，以及界面上的"立即还原"急救按钮。
     *
     * 全局档位是另一个功能、有自己的开关，这里不会把它一起关掉；只保证清干净之后，
     * 系统状态回到全局档位（按**当前**锁定方式）要求的样子。
     */
    fun resetAll(context: Context) {
        val hasSession = AppPrefs.lockedPackage(context) != null
        val sessionEngine = AppPrefs.lockedEngine(context)
        when {
            hasSession && sessionEngine == EngineKind.SETTINGS -> {
                // restore() 内部会在全局接管时把系统设置带回全局档位要求的状态
                restore(context)
                return
            }

            hasSession && sessionEngine == EngineKind.SHIZUKU -> {
                // Shizuku 方式要还原 fixed-to-user-rotation 与旋转设置，都是 shell 调用，放后台线程
                Thread {
                    ShizukuRotation.restore(context)
                    reapplyGlobal(context)
                }.start()
                return
            }

            hasSession -> {
                OverlayRotation.clear(context)
                AppPrefs.clearLock(context)
                reapplyGlobal(context)
                return
            }

            else -> {
                // 没有按应用会话在身：把全局档位按"接管前快照"还原掉，再按当前方式重新接管。
                // 少了这一步，从"系统设置"换到别的方式时，上一次写进去的
                // accelerometer_rotation / user_rotation 会一直留着。
                GlobalRotation.release(context)
                reapplyGlobal(context)
            }
        }
    }

    /**
     * 切换锁定方式：整个应用只有这一个开关，改它必须先把旧方式的痕迹清干净
     * （悬浮窗窗口 / Shizuku 的显示级固定），再按新方式把全局档位补回来。
     */
    fun switchEngine(context: Context, kind: EngineKind) {
        AppPrefs.setEngine(context, kind)
        resetAll(context)
    }

    /** 按应用锁定清干净后补回全局档位（上面收覆盖层时会顺带把全局那个一起收掉）。 */
    private fun reapplyGlobal(context: Context) {
        if (GlobalRotation.isEngaged(context)) GlobalRotation.apply(context)
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
