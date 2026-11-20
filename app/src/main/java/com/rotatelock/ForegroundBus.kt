package com.rotatelock

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 前台感知总线：知道"当前前台是哪个应用/Activity"，驱动按应用锁定与全局档位的轴管理。
 *
 * 两个来源，按锁定方式各取所需：
 *  - **无障碍服务**的 TYPE_WINDOW_STATE_CHANGED —— 实时、带 Activity 组件名；
 *    悬浮窗 / 系统设置方式只有这一个来源可用。
 *  - **UsageStatsManager 轮询** —— 500ms 查一次"最近的 Activity resumed"事件（同样带组件名）；
 *    Shizuku 方式用它替代无障碍：PACKAGE_USAGE_STATS 是 AppOps 型权限，
 *    Shizuku(shell) 能 `pm grant` 自动授予，用户无感，整个 Shizuku 链路不再依赖无障碍开关。
 *
 * 两个来源并存时按 (包名, 组件名) 去重，先到先得。
 */
object ForegroundBus {

    private const val TAG = "RotationLock"
    private const val DEBUG_LOG = true
    private const val SYSTEM_UI = "com.android.systemui"

    /** 轮询间隔与查询窗口：窗口故意比间隔大，避免错过边界处的事件；重复由 dispatch 去重。 */
    private const val POLL_INTERVAL_MS = 500L
    private const val QUERY_WINDOW_MS = 2000L

    /** 写完系统设置后等这么久再看屏幕角度（方向切换有动画，立刻读会读到旧值）。 */
    private const val VERIFY_DELAY_MS = 1200L

    /** Shizuku 调用要走 binder + shell，不能压在主线程上。 */
    private val worker = Executors.newSingleThreadExecutor()

    /** 派发与延迟校验都在主线程，避免和传感器/窗口事件的时序打架。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 同一条提示每次进程会话只弹一次（按字符串资源去重）。 */
    private val warned = HashSet<Int>()

    /** 同一前台 (包名, 组件名) 不重复派发。 */
    private var lastDispatched: Pair<String, String?>? = null

    /** 进程级 applicationContext：dispatch 时记下，供日志与提示使用。 */
    private var appContext: Context? = null

    // ---- 轮询（Shizuku 方式的前台来源） ----

    private var polling = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            val context = appContext ?: return stopPolling()
            runCatching { pollTick(context) }
            scheduleNext()
        }
    }

    /**
     * 启动 UsageStats 轮询（幂等）。只在 Shizuku 方式下有意义；tick 里发现方式换了会自行停。
     * 调用时机：守护服务启动（覆盖开机）、无障碍服务重连、切换锁定方式。
     */
    fun startPolling(context: Context) {
        if (polling) return
        polling = true
        appContext = context.applicationContext ?: context
        journal("fg poll start (shizuku usagestats source)")
        mainHandler.post(pollRunnable)
    }

    fun stopPolling() {
        if (!polling) return
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        Log.i(TAG, "fg poll stopped")
    }

    private fun scheduleNext() {
        if (polling) mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun pollTick(context: Context) {
        if (AppPrefs.engine(context) != EngineKind.SHIZUKU) {
            // 只有 Shizuku 方式需要轮询来源；切走后自行收摊
            stopPolling()
            return
        }
        if (!usageStatsGranted(context)) {
            // 首次使用还没授权：让 Shizuku 静默补一次，下个 tick 再试
            ensureUsageStatsPermission(context)
            return
        }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - QUERY_WINDOW_MS, now)
        var pkg: String? = null
        var cls: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = event.packageName
                cls = event.className
            }
        }
        val foreground = pkg
        if (foreground != null) dispatch(context, foreground, cls)
    }

    // ---- 权限 ----

    /** UsageStats 权限（AppOps 型）是否已授。 */
    fun usageStatsGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Shizuku 在位就静默补授（pm grant + appops set 双保险，ROM 对两者支持不同）。 */
    private fun ensureUsageStatsPermission(context: Context) {
        if (usageStatsGranted(context)) return
        if (!ShizukuRotation.isReady(context)) return
        worker.execute {
            val result = ShizukuBridge.run(
                context,
                listOf(
                    "pm grant " + context.packageName + " android.permission.PACKAGE_USAGE_STATS",
                    "appops set " + context.packageName + " GET_USAGE_STATS allow",
                ),
            )
            AppPrefs.journal(
                context,
                "usagestats grant ok=" + result.ok + " " + result.message,
            )
        }
    }

    /** 前台来源是否可用：Shizuku 方式看 UsageStats 权限，其余方式看无障碍服务开关。 */
    fun sourceAvailable(context: Context): Boolean = when (AppPrefs.engine(context)) {
        EngineKind.SHIZUKU -> usageStatsGranted(context)
        else -> RotationAccessibilityService.isEnabled(context)
    }

    /** 最近一次派发的前台 (包名, 组件名)；还没有任何前台信息时为 null。 */
    fun currentForeground(): Pair<String, String?>? = lastDispatched

    /**
     * 当前前台应用的清单方向是否属于横屏/全方向族（横屏游戏、视频全屏……）。
     * 前台信息缺失、不是可启动应用（桌面除外）或查不到组件时按"不要横屏"处理。
     * 给「跟随应用方向」的播种用：轴跟着前台应用走，而不是跟着屏幕残留角度走。
     */
    fun foregroundWantsLandscape(context: Context): Boolean {
        val foreground = lastDispatched ?: return false
        val (pkg, cls) = foreground
        if (pkg !in AppCatalog.launchable(context)) return false
        if (cls.isNullOrEmpty()) return false
        return ActivityOrientation.requestsFreeRotation(context, pkg, cls) ?: false
    }

    // ---- 派发 ----

    /** 统一入口：任何来源感知到前台变化都走这里。 */
    fun dispatch(context: Context, packageName: String, className: String?) {
        if (appContext == null) appContext = context.applicationContext ?: context
        val key = packageName to className
        if (lastDispatched == key) return
        lastDispatched = key
        onForeground(appContext!!, packageName, className)
    }

    private fun onForeground(context: Context, packageName: String, className: String?) {
        val config = AppPrefs.config(context)
        val mode = if (config.enabled) config.targets[packageName] else null
        if (DEBUG_LOG) {
            Log.i(
                TAG,
                "foreground=" + packageName + " cls=" + className +
                    " targetMode=" + mode +
                    " lockedPkg=" + AppPrefs.lockedPackage(context) +
                    " lockedEngine=" + AppPrefs.lockedEngine(context) +
                    " launchable=" + (packageName in AppCatalog.launchable(context)),
            )
        }
        journal(
            "fg " + packageName + " cls=" + className +
                " target=" + mode + " enabled=" + config.enabled + " targets=" + config.targets.size +
                " engine=" + config.engine.storedName +
                " locked=" + AppPrefs.lockedPackage(context) + "/" + AppPrefs.lockedEngine(context) +
                " launchable=" + (packageName in AppCatalog.launchable(context)) +
                " rot=" + currentRotation(context),
        )

        if (mode == null) {
            // 只有"切到了另一个可启动应用"才解除锁定；
            // SystemUI / 输入法 / 权限对话框等系统组件不是可启动应用，保持锁定不被打断。
            if (AppPrefs.lockedPackage(context) != null) {
                if (packageName in AppCatalog.launchable(context)) {
                    val released = release(context)
                    if (DEBUG_LOG) Log.i(TAG, "release for " + packageName + " -> " + released)
                } else {
                    journal("keep lock: " + packageName + " not treated as leaving")
                    return
                }
            }
            // 横屏让路判定：前台活动自己在清单里请求了横屏/全方向（横屏游戏、视频全屏）
            // 时把全局档位让开，切回竖屏活动或桌面时恢复接管。
            if (GlobalRotation.isEngaged(context) && packageName in AppCatalog.launchable(context)) {
                updateLandscapeYield(context, packageName, className)
            }
            // 让路期间不自愈（否则会把让出去的状态抢回来）；未让路时维持原有自愈行为
            if (!GlobalRotation.isYielded(context)) healGlobal(context)
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
        // 自动档：角度跟姿态走，这里只算种子。同一应用已锁定且记录角度还在允许集合里
        // （姿势提交会同步记录）就视为已锁，不重写；仅需要（重新）启动方向监听。
        val desired = when {
            mode == OrientationMode.KEEP_CURRENT -> AppPrefs.lockedRotation(context)
            mode.isAuto -> {
                val locked = AppPrefs.lockedRotation(context)
                val allowed = mode.allowedRotations
                if (AppPrefs.lockedPackage(context) == packageName &&
                    AppPrefs.lockedEngine(context) == EngineKind.SHIZUKU &&
                    locked >= 0 && allowed?.contains(locked) == true
                ) {
                    locked
                } else {
                    mode.seedFor(currentRotation(context))
                }
            }
            else -> mode.userRotation
        }
        val alreadyLocked = AppPrefs.lockedPackage(context) == packageName &&
            AppPrefs.lockedEngine(context) == EngineKind.SHIZUKU &&
            AppPrefs.lockedRotation(context) == desired
        if (alreadyLocked) {
            if (mode.isAuto) startPerAppAuto(context, packageName, mode, desired)
            return
        }
        worker.execute { ShizukuRotation.apply(context, packageName, mode) }
        if (mode.isAuto) startPerAppAuto(context, packageName, mode, desired)
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
        // 自动档落一个种子角度（当前姿势所在角度），之后由方向监听跟姿态改窗口方向
        val effective = if (mode.isAuto) OrientationMode.from(mode.seedFor(currentRotation(context))) else mode
        if (OverlayRotation.apply(context, effective)) {
            AppPrefs.updateLock(context, packageName, effective.userRotation, EngineKind.OVERLAY)
            // 0×0 悬浮窗看不见，用一条常驻通知告诉用户"锁已生效"（离开应用时随之消失）
            LockNotification.show(context, packageName, mode)
            journal("overlay lock " + packageName + " -> " + mode + " seed=" + effective)
            if (mode.isAuto) startPerAppAuto(context, packageName, mode, effective.userRotation)
        }
    }

    private fun applySettings(context: Context, packageName: String, mode: OrientationMode) {
        val desired = when {
            mode == OrientationMode.KEEP_CURRENT -> {
                val locked = AppPrefs.lockedPackage(context)
                if (locked == packageName && AppPrefs.lockedRotation(context) >= 0) {
                    AppPrefs.lockedRotation(context)
                } else {
                    currentRotation(context)
                }
            }
            // 自动档：种子取当前姿势所在角度，之后方向监听跟姿态写
            mode.isAuto -> mode.seedFor(currentRotation(context))
            else -> mode.userRotation
        }

        if (RotationController.isLockedTo(context, desired)) {
            // 已经是期望状态：不重复写系统设置，只校正记录（例如连续打开两个方向相同的应用）
            if (AppPrefs.lockedPackage(context) != packageName) {
                AppPrefs.updateLock(context, packageName, desired, EngineKind.SETTINGS)
            }
            // pause 在进入时把方向监听停了，自动档要重新拉起来
            if (mode.isAuto) startPerAppAuto(context, packageName, mode, desired)
            return
        }

        if (!RotationController.lock(context, packageName, desired, EngineKind.SETTINGS)) {
            warnOnce(R.string.warn_no_write_settings)
            return
        }
        journal("settings lock " + packageName + " desired=" + desired)
        if (mode.isAuto) {
            startPerAppAuto(context, packageName, mode, desired)
        } else {
            // 写入成功不代表屏幕真的转了：应用自己在清单里锁死方向时，user_rotation 对它无效。
            // 等一下再看实际角度，不变就明确告诉用户换方式（否则表现就是"设了没反应"）。
            verifyLock(context, packageName, desired)
        }
    }

    /**
     * 按应用自动档：轴由配置的档位固定（竖屏自动 0°/180°、横屏自动 90°/270°……），
     * 传感器监听跟姿态在允许集合内落地。复用全局的方向监听器单例——
     * 进入按应用时 GlobalRotation.pause 已把全局监听停掉，这里用按应用的回调重新拉起。
     */
    private fun startPerAppAuto(context: Context, packageName: String, mode: OrientationMode, seed: Int) {
        val allowed = mode.allowedRotations ?: return
        GlobalRotation.acquireWatcher(context).start(
            allowed,
            seed,
            { rotation -> commitPerAppAuto(context, packageName, rotation) },
            false,
        )
        journal("perapp auto watcher " + packageName + " " + mode.name + " seed=" + seed)
    }

    /** 按应用自动档落地一次角度：按当前锁定方式走各自通道。锁已不在这个应用上时不写。 */
    private fun commitPerAppAuto(context: Context, packageName: String, rotation: Int): Boolean {
        if (AppPrefs.lockedPackage(context) != packageName) return false
        val ok = when (AppPrefs.engine(context)) {
            EngineKind.SETTINGS -> {
                if (!RotationController.canWrite(context)) {
                    AppPrefs.journal(context, "perapp auto: no WRITE_SETTINGS")
                    false
                } else {
                    RotationController.putUserRotation(context, rotation)
                    true
                }
            }

            EngineKind.OVERLAY -> OverlayRotation.apply(context, OrientationMode.from(rotation))

            EngineKind.SHIZUKU -> {
                if (!ShizukuRotation.isReady(context)) {
                    false
                } else {
                    worker.execute {
                        val result = ShizukuBridge.run(
                            context,
                            listOf("settings put system user_rotation " + rotation),
                        )
                        AppPrefs.journal(context, "perapp auto shizuku -> " + rotation + " ok=" + result.ok)
                        if (result.ok) AppPrefs.updateLock(context, packageName, rotation, EngineKind.SHIZUKU)
                    }
                    true
                }
            }
        }
        if (ok) {
            // 记录跟到当前姿势：同应用再次触发窗口事件时 alreadyLocked 判定才不会误重写
            AppPrefs.updateLock(context, packageName, rotation, AppPrefs.engine(context))
            journal("perapp auto -> " + rotation + " rot=" + currentRotation(context))
            Log.i(TAG, "perapp auto -> " + rotation)
        }
        return ok
    }

    /**
     * 全局档位的横屏让路判定：前台活动在清单里请求了横屏/全方向时，按当前档位分流——
     *  - 「跟随应用方向」（悬浮窗/Shizuku 方式）：不走让路（应用的方向请求到不了显示层，让路等于整段
     *    放开），改为把轴切到应用的自身方向，横屏族应用在横屏轴内跟姿态翻转；
     *  - 其余档位（含「跟随应用方向」的系统设置方式）：把全局档位让开、还原成原生旋转行为，
     *    切回竖屏活动或桌面时恢复接管。
     * 查不到组件（对话框、输入法等）时保持现状，避免被系统组件窗口误触发。
     */
    private fun updateLandscapeYield(context: Context, packageName: String, className: String?) {
        if (className.isNullOrEmpty()) return
        val wants = ActivityOrientation.requestsFreeRotation(context, packageName, className) ?: return
        val mode = AppPrefs.globalMode(context)
        if (mode.followAppAxis && AppPrefs.engine(context) != EngineKind.SETTINGS) {
            GlobalRotation.setWatcherAxis(context, wants)
            return
        }
        if (wants && !GlobalRotation.isYielded(context)) {
            GlobalRotation.yieldForLandscape(context)
            journal("global yield for " + packageName + "/" + className)
        } else if (!wants && GlobalRotation.isYielded(context)) {
            GlobalRotation.resumeFromYield(context)
            journal("global yield resume for " + packageName + "/" + className)
        }
    }

    /**
     * 校验「系统设置」方式的锁定是否真的反映到了屏幕上。
     * 延迟一点再看：方向切换本身有动画，写进去的瞬间读可能还没生效。
     */
    private fun verifyLock(context: Context, packageName: String, desired: Int) {
        mainHandler.postDelayed({
            val actual = currentRotation(context)
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
        // 按应用自动档的方向监听随锁一起停；全局仍在接管时，下面各通道的 apply 流程会把它拉回来
        GlobalRotation.stopWatcher(context)
        val released = when (fromEngine) {
            EngineKind.SETTINGS -> {
                val restored = RotationController.restore(context)
                // 全局自动档的监听在进入按应用时被 pause 停掉，离开后要主动恢复（原来漏了这步：
                // 表现为"去过一次按应用锁定的应用之后，全局自动旋转就死了"）
                if (GlobalRotation.isEngaged(context)) GlobalRotation.apply(context)
                restored
            }
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
     * 自愈：全局档位要求的状态可能被别的途径改掉 —— 用户在快捷设置里开了自动旋转，
     * 或者某个应用自己写了 USER_ROTATION、覆盖层窗口被系统撤掉。
     * 有按应用锁在身时不插手（那时由按应用规则说了算）。
     */
    private fun healGlobal(context: Context) {
        if (!AppPrefs.globalMode(context).engages) return
        if (AppPrefs.lockedPackage(context) != null) return
        GlobalRotation.heal(context)
    }

    private fun currentRotation(context: Context): Int = runCatching {
        val manager = context.getSystemService(DisplayManager::class.java)
        manager.getDisplay(Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun warnOnce(messageRes: Int) {
        if (!warned.add(messageRes)) return
        mainHandler.post {
            val context = appContext ?: return@post
            runCatching {
                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun journal(line: String) {
        if (!DEBUG_LOG) return
        val context = appContext ?: return
        runCatching { AppPrefs.journal(context, line) }
    }
}
