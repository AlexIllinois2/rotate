package com.rotatelock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 这个服务只做一件事：以无障碍事件（TYPE_WINDOW_STATE_CHANGED）的方式感知前台应用，
 * 交给 [ForegroundBus] 统一派发。配置里关闭了窗口内容读取（canRetrieveWindowContent=false），
 * 不显示常驻通知。
 *
 * 「知道前台是谁」之后的全部业务（按应用锁定、全局档位轴管理、横屏让路、自愈）都在
 * ForegroundBus 里；悬浮窗/系统设置方式依赖本服务提供前台事件，Shizuku 方式则由
 * ForegroundBus 的 UsageStats 轮询替代本服务——两种来源按锁定方式各取所需。
 *
 * 本服务还承载「全局强制旋转」档位的方向监听宿主：自动档（360°/270°/180° 自动）的
 * OrientationWatcher 跑在 GlobalRotation 的进程单例里，由本服务在 onServiceConnected
 * 时拉起（无障碍服务是系统常驻绑定的、唯一稳定的宿主）。
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
        // 我们自己的覆盖层窗口也会发窗口事件。不排除它的话，"挂上悬浮窗"会被
        // ForegroundBus 判成"切到了另一个可启动应用"，于是刚锁上就立刻释放（真机实测就是这样抖的）。
        if (e.className?.toString() == OverlayRotation.WINDOW_CLASS_NAME) return
        if (DEBUG_LOG) Log.i(TAG, "windowStateChanged pkg=" + packageName)
        journal("ev " + packageName + " cls=" + e.className)
        ForegroundBus.dispatch(applicationContext, packageName, e.className?.toString())
    }

    override fun onInterrupt() = Unit

    /** 系统绑上服务的那一刻就把全局档位落实下去（开机、服务被重启都会走这里）。 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        journal("svc connected global=" + AppPrefs.globalMode(applicationContext).storedName)
        applyGlobal(applicationContext)
        // Shizuku 方式下前台感知靠 ForegroundBus 的轮询；无障碍在位时轮询 tick 会自行让位
        ForegroundBus.startPolling(applicationContext)
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

    private fun warnOnce(messageRes: Int) {
        if (!warned.add(messageRes)) return
        runCatching {
            Toast.makeText(applicationContext, getString(messageRes), Toast.LENGTH_LONG).show()
        }
    }

    private fun journal(line: String) {
        if (!DEBUG_LOG) return
        runCatching { AppPrefs.journal(applicationContext, line) }
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
