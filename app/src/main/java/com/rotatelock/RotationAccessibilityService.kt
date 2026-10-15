package com.rotatelock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
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
 * 知道前台是谁之后，按全局唯一的那一种锁定方式去锁定方向：
 *   SETTINGS — 改系统旋转设置（进入时快照、离开时精确还原）
 *   OVERLAY  — 加一个固定 screenOrientation 的 0×0 悬浮窗（完全不碰系统设置）
 *   SHIZUKU  — 进入时用 Shizuku（shell 身份）强制显示方向，五个方向都支持，离开时还原
 *
 * 另外还承载「全局强制旋转」档位：自动档（360°/270°/180° 自动）的方向传感器监听跑在这里，
 * 因为系统没有"常驻前台服务"的设计，而无障碍服务是系统常驻绑定的、唯一稳定的宿主。
 */
class RotationAccessibilityService : AccessibilityService() {

    /** 同一条提示每次服务会话只弹一次（按字符串资源去重）。 */
    private val warned = HashSet<Int>()

    /** Shizuku 调用要走 binder + shell，不能压在主线程上。 */
    private val worker = Executors.newSingleThreadExecutor()

    /** 延迟校验（写在主线程上，避免和传感器/窗口事件的时序打架）。 */
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

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

    /** 系统绑上服务的那一刻就把全局档位落实下去（开机、服务被重启都会走这里）。 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        journal("svc connected global=" + AppPrefs.globalMode(applicationContext).storedName)
        applyGlobal(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 只有"服务真的被关掉了"才收窗口。厂商 ROM 在重负载下会重启无障碍服务，
        // 那种瞬断不该把悬浮窗撤掉（否则会出现"锁定一下就弹回去"的假象）。
        val stillEnabled = isEnabled(applicationContext)
        journal("svc onUnbind enabled=" + stillEnabled)
        if (!stillEnabled) {
            OverlayRotation.clear(applicationContext)
            // 自动档靠这个服务里的方向监听驱动；服务没了就别再留着监听
            GlobalRotation.pause(applicationContext)
        }
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
                " engine=" + config.engine.storedName +
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
            } else {
                // 没有按应用锁在身：确认全局档位还在生效（用户可能在快捷设置里动过自动旋转）
                healGlobal(context)
            }
            return
        }

        // 进入已配置的应用：按应用规则优先，先停掉全局的传感器接管，免得两套机制互相改系统设置
        GlobalRotation.pause(context)

        when (config.engine) {
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
        if (!ShizukuRotation.isReady(context)) {
            journal("shizuku not ready for " + packageName)
            warnOnce(R.string.warn_shizuku_not_ready)
            return
        }
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
            return
        }
        journal("settings lock " + packageName + " desired=" + desired)
        // 写入成功不代表屏幕真的转了：应用自己在清单里锁死方向时，user_rotation 对它无效。
        // 等一下再看实际角度，不变就明确告诉用户换方式（否则表现就是"设了没反应"）。
        verifyLock(context, packageName, desired)
    }

    /**
     * 校验「系统设置」方式的锁定是否真的反映到了屏幕上。
     * 延迟一点再看：方向切换本身有动画，写进去的瞬间读可能还没生效。
     */
    private fun verifyLock(context: Context, packageName: String, desired: Int) {
        mainHandler.postDelayed({
            val actual = currentRotation()
            if (actual == desired) return@postDelayed
            // 期间可能已经切走了，或者锁定方式被改掉了
            if (AppPrefs.lockedPackage(context) != packageName) return@postDelayed
            if (AppPrefs.engine(context) != EngineKind.SETTINGS) return@postDelayed
            journal("lock ineffective pkg=" + packageName + " desired=" + desired + " actual=" + actual)
            warnOnce(R.string.warn_lock_ineffective)
        }, VERIFY_DELAY_MS)
    }

    /** 离开目标应用：按当前会话实际用的方式解除锁定。 */
    private fun release(context: Context): Boolean {
        val fromPackage = AppPrefs.lockedPackage(context)
        val fromEngine = AppPrefs.lockedEngine(context)
        val released = when (fromEngine) {
            EngineKind.SETTINGS -> RotationController.restore(context)
            EngineKind.SHIZUKU -> {
                worker.execute {
                    ShizukuRotation.restore(context)
                    // 全局还在接管时，还原完要把系统设置带回全局档位要求的状态
                    if (GlobalRotation.isEngaged(context)) GlobalRotation.apply(context)
                }
                true
            }
            else -> {
                OverlayRotation.clear(context)
                AppPrefs.clearLock(context)
                if (GlobalRotation.isEngaged(context)) GlobalRotation.apply(context)
                true
            }
        }
        // 兜底：会话记录丢了但悬浮窗还在（例如服务被系统重启过）也要收掉
        if (OverlayRotation.isActive()) OverlayRotation.clear(context)
        journal("release from=" + fromPackage + "/" + fromEngine + " ok=" + released)
        return released
    }

    /**
     * 落实全局档位：服务被系统绑上的那一刻调用（开机、服务重启都会走这里）。
     * 权限按锁定方式各自要求：SETTINGS 要「修改系统设置」，OVERLAY 要悬浮窗权限，SHIZUKU 要 Shizuku 就绪。
     */
    private fun applyGlobal(context: Context) {
        if (!AppPrefs.globalMode(context).engages) return
        when (AppPrefs.engine(context)) {
            EngineKind.SETTINGS -> if (!RotationController.canWrite(context)) {
                warnOnce(R.string.warn_no_write_settings)
                return
            }

            EngineKind.OVERLAY -> if (!OverlayRotation.canDrawOverlay(context)) {
                warnOnce(R.string.warn_no_overlay_permission)
                return
            }

            EngineKind.SHIZUKU -> if (!ShizukuRotation.isReady(context)) {
                warnOnce(R.string.warn_shizuku_not_ready)
                return
            }
        }
        GlobalRotation.apply(context)
    }

    /**
     * 自愈：全局档位要求的状态可能被别的途径改掉 —— 用户在快捷设置里开了自动旋转，
     * 或者某个应用自己写了 USER_ROTATION、覆盖层窗口被系统撤掉。
     * 有按应用锁在身时不插手（那时由按应用规则说了算）。
     */
    private fun healGlobal(context: Context) {
        if (!AppPrefs.globalMode(context).engages) return
        if (AppPrefs.lockedPackage(context) != null) return
        GlobalRotation.heal(context)
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
        /** 写完系统设置后等这么久再看屏幕角度（方向切换有动画，立刻读会读到旧值）。 */
        private const val VERIFY_DELAY_MS = 1200L

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
