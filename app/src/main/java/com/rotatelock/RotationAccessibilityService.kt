package com.rotatelock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 这个服务只做一件事：知道当前前台是哪个应用。
 * 配置里关闭了窗口内容读取（canRetrieveWindowContent=false），不显示常驻通知。
 *
 * 知道前台是谁之后，按每个应用各自配置的方式去锁定方向：
 *   SETTINGS — 改系统旋转设置（进入时快照、离开时精确还原）
 *   OVERLAY  — 加一个固定 screenOrientation 的 0×0 悬浮窗（完全不碰系统设置）
 *   SHIZUKU  — 进入时用 Shizuku（shell 身份）强制显示方向，五个方向都支持，离开时还原
 */
class RotationAccessibilityService : AccessibilityService() {

    /** 同一条提示每次服务会话只弹一次（按字符串资源去重）。 */
    private val warned = HashSet<Int>()

    /** Shizuku 调用要走 binder + shell，不能压在主线程上。 */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = e.packageName?.toString().orEmpty()
        if (packageName.isEmpty()) return
        if (packageName == SYSTEM_UI) return
        // 我们自己的覆盖层窗口也会发窗口事件。不排除它的话，"挂上悬浮窗"会被下面判成
        // "切到了另一个可启动应用"，于是刚锁上就立刻释放（真机实测就是这样抖的）。
        if (e.className?.toString() == OverlayRotation.WINDOW_CLASS_NAME) return
        if (DEBUG_LOG) Log.i(TAG, "windowStateChanged pkg=" + packageName)
        journal("ev " + packageName + " cls=" + e.className)
        applyForeground(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 只有"服务真的被关掉了"才收窗口。厂商 ROM 在重负载下会重启无障碍服务，
        // 那种瞬断不该把悬浮窗撤掉（否则会出现"锁定一下就弹回去"的假象）。
        val stillEnabled = isEnabled(applicationContext)
        journal("svc onUnbind enabled=" + stillEnabled)
        if (!stillEnabled) OverlayRotation.clear(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        journal("svc onDestroy pid=" + android.os.Process.myPid())
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        journal("svc onCreate pid=" + android.os.Process.myPid() + " " + runCatching { AppCatalog.debugHome(applicationContext) }.getOrDefault(""))
        // 兜底：进程被杀时可能留下一个还开着的显示级固定（Shizuku 方式），启动时清掉
        worker.execute { ShizukuRotation.cleanupStaleForce(applicationContext) }
    }

    private fun journal(line: String) {
        if (!DEBUG_LOG) return
        runCatching { AppPrefs.journal(applicationContext, line) }
    }

    private fun applyForeground(packageName: String) {
        val context = applicationContext
        val config = AppPrefs.config(context)
        val mode = if (config.enabled) config.targets[packageName] else null
        if (DEBUG_LOG) {
            Log.i(
                TAG,
                "foreground=" + packageName + " targetMode=" + mode +
                    " lockedPkg=" + AppPrefs.lockedPackage(context) +
                    " lockedEngine=" + AppPrefs.lockedEngine(context) +
                    " launchable=" + (packageName in AppCatalog.launchable(context)),
            )
        }
        journal(
            "fg " + packageName + " target=" + mode + " enabled=" + config.enabled + " targets=" + config.targets.size +
                " engine=" + (config.engines[packageName] ?: EngineKind.SETTINGS) +
                " locked=" + AppPrefs.lockedPackage(context) + "/" + AppPrefs.lockedEngine(context) +
                " launchable=" + (packageName in AppCatalog.launchable(context)) +
                " rot=" + currentRotation(),
        )

        if (mode == null) {
            // 只有"切到了另一个可启动应用"才解除锁定；
            // SystemUI / 输入法 / 权限对话框等系统组件不是可启动应用，保持锁定不被打断。
            if (AppPrefs.lockedPackage(context) != null && packageName in AppCatalog.launchable(context)) {
                val released = release(context)
                if (DEBUG_LOG) Log.i(TAG, "release for " + packageName + " -> " + released)
            } else if (AppPrefs.lockedPackage(context) != null) {
                journal("keep lock: " + packageName + " not treated as leaving")
            }
            return
        }

        when (config.engines[packageName] ?: EngineKind.SETTINGS) {
            EngineKind.SHIZUKU -> applyShizuku(context, packageName, mode)

            EngineKind.OVERLAY -> applyOverlay(context, packageName, mode)

            EngineKind.SETTINGS -> applySettings(context, packageName, mode)
        }
    }

    /**
     * Shizuku 方式：进入目标应用时用 shell 身份把方向强制下来（含反向竖屏等 compat 表达不了的方向）。
     * 显示级 fixed-to-user-rotation 是"粘"的，已经在同一个应用上生效就不用重复执行。
     */
    private fun applyShizuku(context: Context, packageName: String, mode: OrientationMode) {
        // 「保持进入时」的期望角度就是进入时记下来的那个，所以比较已记录的 rotation 即可
        val desired = if (mode == OrientationMode.KEEP_CURRENT) {
            AppPrefs.lockedRotation(context)
        } else {
            mode.userRotation
        }
        val alreadyLocked = AppPrefs.lockedPackage(context) == packageName &&
            AppPrefs.lockedEngine(context) == EngineKind.SHIZUKU &&
            AppPrefs.lockedRotation(context) == desired
        if (alreadyLocked) return
        worker.execute { ShizukuRotation.apply(context, packageName, mode) }
    }

    private fun applyOverlay(context: Context, packageName: String, mode: OrientationMode) {
        if (!OverlayRotation.isSupported()) {
            warnOnce(R.string.warn_overlay_needs_android12)
            return
        }
        if (!OverlayRotation.canDrawOverlay(context)) {
            warnOnce(R.string.warn_no_overlay_permission)
            return
        }
        // 上一次锁定若是"设置式"的（比如刚把方式从系统设置改成悬浮窗），先还原掉，避免两套机制叠加
        if (AppPrefs.lockedEngine(context) == EngineKind.SETTINGS) RotationController.restore(context)
        if (OverlayRotation.apply(context, mode)) {
            AppPrefs.updateLock(context, packageName, mode.userRotation, EngineKind.OVERLAY)
            // 0×0 悬浮窗看不见，用一条常驻通知告诉用户"锁已生效"（离开应用时随之消失）
            LockNotification.show(context, packageName, mode)
            journal("overlay lock " + packageName + " -> " + mode)
        }
    }

    private fun applySettings(context: Context, packageName: String, mode: OrientationMode) {
        val desired = if (mode == OrientationMode.KEEP_CURRENT) {
            val locked = AppPrefs.lockedPackage(context)
            if (locked == packageName && AppPrefs.lockedRotation(context) >= 0) {
                AppPrefs.lockedRotation(context)
            } else {
                currentRotation()
            }
        } else {
            mode.userRotation
        }

        if (RotationController.isLockedTo(context, desired)) {
            // 已经是期望状态：不重复写系统设置，只校正记录（例如连续打开两个方向相同的应用）
            if (AppPrefs.lockedPackage(context) != packageName) {
                AppPrefs.updateLock(context, packageName, desired, EngineKind.SETTINGS)
            }
            return
        }

        if (!RotationController.lock(context, packageName, desired, EngineKind.SETTINGS)) {
            warnOnce(R.string.warn_no_write_settings)
        }
        journal("settings lock " + packageName + " desired=" + desired)
    }

    /** 离开目标应用：按当前会话实际用的方式解除锁定。 */
    private fun release(context: Context): Boolean {
        val fromPackage = AppPrefs.lockedPackage(context)
        val fromEngine = AppPrefs.lockedEngine(context)
        val released = when (fromEngine) {
            EngineKind.SETTINGS -> RotationController.restore(context)
            EngineKind.SHIZUKU -> {
                worker.execute { ShizukuRotation.restore(context) }
                true
            }
            else -> {
                OverlayRotation.clear(context)
                AppPrefs.clearLock(context)
                true
            }
        }
        // 兜底：会话记录丢了但悬浮窗还在（例如服务被系统重启过）也要收掉
        if (OverlayRotation.isActive()) OverlayRotation.clear(context)
        journal("release from=" + fromPackage + "/" + fromEngine + " ok=" + released)
        return released
    }

    private fun currentRotation(): Int = runCatching {
        val manager = getSystemService(DisplayManager::class.java)
        manager.getDisplay(Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun warnOnce(messageRes: Int) {
        if (!warned.add(messageRes)) return
        runCatching {
            Toast.makeText(applicationContext, getString(messageRes), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val DEBUG_LOG = true
        private const val TAG = "RotationLock"
        private const val SYSTEM_UI = "com.android.systemui"

        /** 无障碍服务是否已被用户在系统设置里开启。 */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, RotationAccessibilityService::class.java)
            if (enabledFromSetting(context, expected)) return true
            // 兜底：有些 ROM 会把 enabled_accessibility_services 清掉（或写成别的形式），
            // 但服务其实还在系统里跑着。再问一次系统"当前实际启用了哪些无障碍服务"。
            return runCatching {
                context.getSystemService(AccessibilityManager::class.java)
                    ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .orEmpty()
                    .any { info -> ComponentName.unflattenFromString(info.id) == expected }
            }.getOrDefault(false)
        }

        private fun enabledFromSetting(context: Context, expected: ComponentName): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
            }
            return false
        }
    }
}
