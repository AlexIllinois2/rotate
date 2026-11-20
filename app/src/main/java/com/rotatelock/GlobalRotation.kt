package com.rotatelock

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.Executors

/**
 * 全局强制旋转档位：不管当前在哪个应用，都按这里的档位控制系统的旋转行为。
 *
 * 档位分两组：
 *  - 固定档（锁定 0°/90°/180°/270°）：直接把方向定死，一次生效，不需要常驻。
 *  - 自动档（360°/270°/180° 自动）：由 OrientationWatcher 按"允许集合"跟着重力感应改方向 ——
 *    这是唯一能做出"限制旋转范围"的办法（理由见 OrientationWatcher）。
 *
 * 命名里的度数指可转到的范围：
 *  360° = 四个方向全放开（含上下颠倒）、270° = 去掉 180°（不可上下颠倒）、180° = 只留 0°/180°（不含横向）。
 *
 * 用哪种方式落地由 [AppPrefs.engine] 决定（整个应用只有这一处设置，按应用锁定与全局旋转共用）：
 *  SETTINGS — 关掉系统自动旋转 + 写 USER_ROTATION（需要「修改系统设置」权限；掰不动自己锁死方向的应用）
 *  OVERLAY  — 0×0 悬浮窗固定方向，自动档就是跟着姿态改窗口的 screenOrientation；完全不动系统设置
 *  SHIZUKU  — shell 身份写 user_rotation + 打开显示级 fixed-to-user-rotation，连自带硬锁的应用也掰得动
 *
 * 与「按应用锁定」共存时按应用规则优先：进入已配置应用时暂停全局（见 pause），
 * 离开后由 RotationController.restore() 回到全局状态而不是回到"接管之前"的快照。
 */
enum class GlobalRotationMode(
    val storedName: String,
    val label: String,
    val desc: String,
    /** 自动档允许落在哪些 Surface.ROTATION_*；null 表示这不是自动档。 */
    val allowedRotations: Set<Int>?,
    /** 固定档要落到的 Surface.ROTATION_*；null 表示这不是固定档。 */
    val fixedRotation: Int?,
) {
    FOLLOW_SYSTEM(
        "follow",
        "跟随系统",
        "不接管旋转：你的自动旋转开关和角度怎么设，系统就怎么转。",
        null,
        null,
    ),
    AUTO_360(
        "auto_360",
        "360° 自动（可上下颠倒）",
        "跟随重力感应转满四个方向：0°、90°、180°、270°，允许转到上下颠倒。",
        setOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270),
        null,
    ),
    AUTO_270(
        "auto_270",
        "270° 自动（不可上下颠倒）",
        "跟随重力感应转 0°、90°、270°；转到上下颠倒的姿势时保持原方向不动。",
        setOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_270),
        null,
    ),
    AUTO_180(
        "auto_180",
        "180° 自动（跟随应用方向）",
        "允许上下颠倒，轴跟着应用自身方向走：竖屏应用在竖屏与反向竖屏间翻转；应用自己转到横屏（全屏视频、横屏游戏）后，在横屏与反向横屏间翻转；横着拿不会把竖屏应用掰成横屏。",
        setOf(Surface.ROTATION_0, Surface.ROTATION_180),
        null,
    ),
    LOCK_0(
        "lock_0",
        "锁定 0°（竖屏）",
        "固定竖屏，不随重力感应变化。",
        null,
        Surface.ROTATION_0,
    ),
    LOCK_90(
        "lock_90",
        "锁定 90°（横屏）",
        "固定横屏（左转 90°），不随重力感应变化。",
        null,
        Surface.ROTATION_90,
    ),
    LOCK_180(
        "lock_180",
        "锁定 180°（反向竖屏）",
        "固定上下颠倒的竖屏，不随重力感应变化。",
        null,
        Surface.ROTATION_180,
    ),
    LOCK_270(
        "lock_270",
        "锁定 270°（反向横屏）",
        "固定反向横屏（右转 90°），不随重力感应变化。",
        null,
        Surface.ROTATION_270,
    ),
    ;

    /** 是否需要接管旋转。 */
    val engages: Boolean get() = this != FOLLOW_SYSTEM

    /** 自动档（需要方向传感器持续监听）。 */
    val isAuto: Boolean get() = allowedRotations != null

    /**
     * 「180° 自动」按应用自身方向定轴：允许集合不是固定的 0°/180°，而是跟随当前轴
     * （竖屏轴 ↔ 横屏轴）动态切换。轴的来源有两种：应用自己把屏幕转到另一轴时从实际角度收养
     * （系统设置方式），或前台应用的清单方向显式驱动（悬浮窗 / Shizuku 方式，见 GlobalRotation.setWatcherAxis）。
     */
    val followAppAxis: Boolean get() = this == AUTO_180

    companion object {
        fun from(value: String?): GlobalRotationMode =
            entries.firstOrNull { it.storedName == value } ?: FOLLOW_SYSTEM
    }
}

/**
 * 全局档位的执行器。按 [AppPrefs.engine] 选通道落地，三种方式的权限要求互不相同：
 * SETTINGS 要「修改系统设置」、OVERLAY 要「显示在其他应用上层」、SHIZUKU 要 Shizuku 已启动并授权。
 */
object GlobalRotation {

    private const val TAG = "RotationLock"
    private val LOCK = Any()

    /** 进程级单例：无障碍服务与本应用同一进程（manifest 里没有 android:process）。 */
    private var watcher: OrientationWatcher? = null

    /** Shizuku 是阻塞的 binder + shell 调用，不能压在主线程上。 */
    private val worker = Executors.newSingleThreadExecutor()

    fun mode(context: Context): GlobalRotationMode = AppPrefs.globalMode(context)

    /** 当前档位是否在接管旋转。 */
    fun isEngaged(context: Context): Boolean = AppPrefs.globalMode(context).engages

    /** 全局档位当前是否在为横屏活动让路（接管痕迹已暂时还原）。 */
    fun isYielded(context: Context): Boolean = AppPrefs.globalYielded(context)

    /**
     * 切换档位并立刻生效。
     * @return true 表示已按新档位接管；切回「跟随系统」时返回还原是否成功。
     */
    fun setMode(context: Context, mode: GlobalRotationMode): Boolean {
        AppPrefs.setGlobalMode(context, mode)
        return if (mode.engages) apply(context) else release(context)
    }

    /**
     * 按当前档位 + 当前锁定方式接管旋转。可以安全重复调用（幂等）。
     * 自动档会在这里（重新）启动方向监听；固定档会把监听停掉。
     */
    fun apply(context: Context): Boolean {
        val mode = AppPrefs.globalMode(context)
        // 重新接管即解除让路（resumeFromYield 也是走这里恢复）
        if (AppPrefs.globalYielded(context)) AppPrefs.setGlobalYielded(context, false)
        if (!mode.engages) return false
        val engine = AppPrefs.engine(context)
        AppPrefs.journal(context, "global apply " + mode.storedName + " engine=" + engine.storedName)
        Log.i(TAG, "global apply " + mode.storedName + " engine=" + engine.storedName)
        return when (engine) {
            EngineKind.SETTINGS -> applySettings(context, mode)
            EngineKind.OVERLAY -> applyOverlay(context, mode)
            EngineKind.SHIZUKU -> applyShizuku(context, mode)
        }
    }

    /**
     * 让位给按应用规则：停掉方向监听，并把会盖住按应用规则的覆盖层撤掉。
     *
     * SETTINGS 和 SHIZUKU 都不用动 —— 两者都是靠写 `user_rotation` 生效的，按应用规则直接把角度覆盖掉即可，
     * 而 Shizuku 的 `fixed-to-user-rotation enabled` 正是按应用方式自己也要打开的那个开关，撤掉再开只会打架。
     * 只有 OVERLAY 必须收：覆盖层的方向请求优先级最高，不撤掉它会把按应用规则的方向压住。
     */
    fun pause(context: Context) {
        synchronized(LOCK) { watcher }?.stop()
        if (AppPrefs.engine(context) == EngineKind.OVERLAY) OverlayRotation.clear(context)
    }

    /**
     * 放弃接管，回到「跟随系统」。
     *
     * 清理**不按当前方式分支** —— 换锁定方式时本函数跑在"新方式"下，按方式分支清会把
     * 旧方式的痕迹留下来（悬浮窗窗口会继续盖住新方式写的角度，fixed-to-user-rotation
     * 会让屏幕一直被固定住）。所以三个通道各清各的，谁有痕迹清谁：
     *
     *  - 悬浮窗窗口：挂在就收，不管现在是不是悬浮窗方式；
     *  - Shizuku：记过 fixed 快照（或当前就是 Shizuku 方式）就把显示级固定还原；
     *    Shizuku 不可用时保留快照等下次 release / heal / cleanupStaleForce 再试；
     *  - 系统设置：有快照（accelerometer_rotation / user_rotation）就还原；
     *    写权限不在时同样保留快照，别把用户的原设置弄丢。
     */
    fun release(context: Context): Boolean {
        synchronized(LOCK) { watcher }?.stop()
        val engine = AppPrefs.engine(context)
        val baseAuto = AppPrefs.globalBaseAuto(context)
        val baseUser = AppPrefs.globalBaseUser(context)
        val baseFixed = AppPrefs.globalBaseFixed(context)

        // 悬浮窗通道（与方式无关）
        if (OverlayRotation.isActive()) OverlayRotation.clear(context)

        // Shizuku 通道：fixed-to-user-rotation 是显示级设置，不改回去整个屏幕会一直被固定住
        if (baseFixed != null || engine == EngineKind.SHIZUKU) {
            if (!ShizukuRotation.isReady(context)) {
                // 还不了就先留着快照，等下次 release / heal 再试，别把信息丢了
                AppPrefs.journal(context, "global release: shizuku not ready, fixed=" + baseFixed + " 未还原")
                return false
            }
            AppPrefs.clearGlobal(context)
            val commands = ArrayList<String>()
            commands.add("wm fixed-to-user-rotation " + (baseFixed ?: "default"))
            if (baseUser >= 0) commands.add("settings put system user_rotation " + baseUser)
            if (baseAuto >= 0) commands.add("settings put system accelerometer_rotation " + baseAuto)
            worker.execute {
                val result = ShizukuBridge.run(context, commands)
                AppPrefs.setLastResult(context, "global shizuku release ok=" + result.ok + " fixed=" + baseFixed)
                AppPrefs.journal(
                    context,
                    "global release fixed=" + baseFixed + " auto=" + baseAuto + " user=" + baseUser + " ok=" + result.ok,
                )
            }
            Log.i(TAG, "global release engine=shizuku")
            return true
        }

        // 系统设置通道
        if ((baseAuto >= 0 || baseUser >= 0) && !RotationController.canWrite(context)) {
            AppPrefs.journal(context, "global release: no WRITE_SETTINGS, 快照未还原")
            return false
        }
        AppPrefs.clearGlobal(context)
        restoreSettingsSnapshot(context, baseAuto, baseUser)
        AppPrefs.journal(context, "global release auto=" + baseAuto + " user=" + baseUser)
        Log.i(TAG, "global release engine=" + engine.storedName)
        return true
    }

    private fun restoreSettingsSnapshot(context: Context, baseAuto: Int, baseUser: Int) {
        if (baseUser >= 0) putUser(context, baseUser)
        if (baseAuto >= 0) putAuto(context, baseAuto)
    }

    /**
     * 自愈：全局档位要求的状态可能被别的途径改掉 —— 用户在快捷设置里开了自动旋转、
     * 某个应用自己写了 USER_ROTATION、或者覆盖层窗口被系统撤掉了。
     * 有按应用锁在身时不插手（那时由按应用规则说了算），调用方负责过滤。
     */
    fun heal(context: Context) {
        val mode = AppPrefs.globalMode(context)
        if (!mode.engages) return
        // 让路期间不插手：横屏活动要的是"原生旋转行为"，heal 会把让出去的状态抢回来
        if (AppPrefs.globalYielded(context)) return
        when (AppPrefs.engine(context)) {
            EngineKind.SETTINGS -> {
                if (!RotationController.canWrite(context)) return
                val autoOff = RotationController.autoRotate(context) == 0
                val userOk = mode.fixedRotation?.let { RotationController.userRotation(context) == it } ?: true
                if (autoOff && userOk) return
                AppPrefs.journal(
                    context,
                    "heal global " + mode.storedName +
                        " auto=" + RotationController.autoRotate(context) +
                        " user=" + RotationController.userRotation(context),
                )
                apply(context)
            }

            EngineKind.OVERLAY -> {
                if (OverlayRotation.isActive()) return
                AppPrefs.journal(context, "heal global " + mode.storedName + ": overlay gone")
                apply(context)
            }

            EngineKind.SHIZUKU -> {
                if (!ShizukuRotation.isReady(context)) return
                worker.execute {
                    val fixed = ShizukuBridge.fixedToUserRotation(context)
                    val rotationOk = mode.fixedRotation?.let { RotationController.userRotation(context) == it } ?: true
                    if (fixed == "enabled" && rotationOk) return@execute
                    AppPrefs.journal(context, "heal global " + mode.storedName + " fixed=" + fixed)
                    apply(context)
                }
            }
        }
    }

    // ---- 三种落地方式 ----

    /**
     * 为横屏活动让路：前台活动自己请求了横屏（或全方向旋转）时，把全局档位暂时收掉，
     * 让应用拿到它原生的旋转行为（横屏游戏、视频应用的全屏画面不再被压住）。
     * 各通道按锁定方式各自还原痕迹；全局快照保留，等 [resumeFromYield] 或下次 [apply] 恢复接管。
     * 自动档的方向监听在这里停掉，heal 在让路期间不插手。
     */
    fun yieldForLandscape(context: Context) {
        if (AppPrefs.globalYielded(context)) return
        if (!AppPrefs.globalMode(context).engages) return
        AppPrefs.setGlobalYielded(context, true)
        synchronized(LOCK) { watcher }?.stop()
        when (AppPrefs.engine(context)) {
            EngineKind.SETTINGS -> {
                val baseAuto = AppPrefs.globalBaseAuto(context)
                val baseUser = AppPrefs.globalBaseUser(context)
                if (baseAuto >= 0 || baseUser >= 0) {
                    restoreSettingsSnapshot(context, baseAuto, baseUser)
                } else {
                    // 没有快照（接管其实没落地过）：至少把自动旋转还回去
                    putAuto(context, 1)
                }
            }

            EngineKind.OVERLAY -> OverlayRotation.clear(context)

            EngineKind.SHIZUKU -> {
                if (!ShizukuRotation.isReady(context)) {
                    AppPrefs.journal(context, "global yield: shizuku not ready, 痕迹未还原")
                    return
                }
                val baseAuto = AppPrefs.globalBaseAuto(context)
                val baseUser = AppPrefs.globalBaseUser(context)
                val baseFixed = AppPrefs.globalBaseFixed(context)
                val commands = ArrayList<String>()
                commands.add("wm fixed-to-user-rotation " + (baseFixed ?: "default"))
                commands.add("settings put system accelerometer_rotation " + if (baseAuto >= 0) baseAuto else 1)
                if (baseUser >= 0) commands.add("settings put system user_rotation " + baseUser)
                worker.execute {
                    val result = ShizukuBridge.run(context, commands)
                    AppPrefs.setLastResult(context, "global yield ok=" + result.ok)
                    AppPrefs.journal(context, "global yield shizuku ok=" + result.ok)
                }
            }
        }
        AppPrefs.journal(
            context,
            "global yield for landscape activity engine=" + AppPrefs.engine(context).storedName,
        )
    }

    /** 横屏活动结束（切到竖屏活动/桌面）：重新接管全局档位。 */
    fun resumeFromYield(context: Context) {
        if (!AppPrefs.globalYielded(context)) return
        AppPrefs.journal(context, "global yield resume")
        apply(context)
    }

    /**
     * 「180° 自动（跟随应用方向）」的轴切换：由前台应用的清单方向驱动。
     * 悬浮窗 / Shizuku 方式下应用自己的方向请求到不了显示层（覆盖层方向请求优先级最高 /
     * fixed-to-user-rotation 让显示层忽略应用请求），轴只能由这里显式切换；
     * 「系统设置」方式下方向监听会从实际角度自适应收养新轴，不需要外部驱动。
     */
    fun setWatcherAxis(context: Context, landscapeAxis: Boolean) {
        if (AppPrefs.globalMode(context) != GlobalRotationMode.AUTO_180) return
        if (AppPrefs.engine(context) == EngineKind.SETTINGS) return
        synchronized(LOCK) { watcher }?.setLandscapeAxis(landscapeAxis)
    }

    /**
     * 「跟随应用方向」的种子角度：以前台应用的清单方向定轴，而不是盲目采用当前实际角度。
     * 横屏应用（视频全屏、横屏游戏）刚退到后台时屏幕往往还停在横屏——这时前台已经是
     * 桌面/竖屏应用了，盲目采当前角度会把轴错误地收养在横屏轴上，竖屏前台被锁在
     * 横屏轴里（表现为回桌面仍是横屏，要转一下手机才恢复）。前台信息还没建立时
     * （刚开机等）退回"按当前角度收养"的旧行为。
     */
    private fun followAxisSeed(context: Context): Int {
        val current = currentRotation(context)
        val currentIsLandscape = current == Surface.ROTATION_90 || current == Surface.ROTATION_270
        val landscapeAxis = if (ForegroundBus.currentForeground() == null) {
            currentIsLandscape
        } else {
            ForegroundBus.foregroundWantsLandscape(context)
        }
        return when {
            landscapeAxis -> if (currentIsLandscape) current else Surface.ROTATION_90
            else -> if (currentIsLandscape) Surface.ROTATION_0 else current
        }
    }

    private fun applySettings(context: Context, mode: GlobalRotationMode): Boolean {
        if (!RotationController.canWrite(context)) {
            AppPrefs.journal(context, "global apply " + mode.storedName + ": no WRITE_SETTINGS")
            return false
        }
        // 只在首次接管时记快照：否则会把我们自己写进去的值当成"用户原本的设置"存档
        if (AppPrefs.globalBaseAuto(context) < 0) AppPrefs.beginGlobal(context)
        // 自动旋转必须关掉，USER_ROTATION 才说了算（否则传感器会立刻把角度盖回去）
        if (RotationController.autoRotate(context) != 0) putAuto(context, 0)

        if (mode.isAuto) {
            val allowed = mode.allowedRotations.orEmpty()
            // 「跟随应用方向」的种子按前台应用方向定轴（防残留角度误导轴），其余档位照旧纠正到允许集合
            val seed = if (mode.followAppAxis) followAxisSeed(context) else seedRotation(context, allowed)
            putUser(context, seed)
            watcher(context).start(allowed, seed, { commitRotation(context, it) }, mode.followAppAxis)
        } else {
            watcher(context).stop()
            mode.fixedRotation?.let { putUser(context, it) }
        }
        AppPrefs.journal(context, "global settings " + mode.storedName + " rot=" + currentRotation(context))
        return true
    }

    private fun applyOverlay(context: Context, mode: GlobalRotationMode): Boolean {
        if (!OverlayRotation.isSupported()) {
            AppPrefs.journal(context, "global overlay: needs android 12+ (sdk=" + Build.VERSION.SDK_INT + ")")
            return false
        }
        if (!OverlayRotation.canDrawOverlay(context)) {
            AppPrefs.journal(context, "global overlay: no overlay permission")
            return false
        }
        val target: Int
        if (mode.isAuto) {
            val allowed = mode.allowedRotations.orEmpty()
            val seed = if (mode.followAppAxis) followAxisSeed(context) else seedRotation(context, allowed)
            target = seed
            if (!OverlayRotation.apply(context, OrientationMode.from(seed))) return false
            // 自动档 = 跟着姿态改窗口的方向请求，系统设置一下都不动
            watcher(context).start(allowed, seed, { commitRotation(context, it) }, mode.followAppAxis)
        } else {
            watcher(context).stop()
            target = mode.fixedRotation ?: return false
            if (!OverlayRotation.apply(context, OrientationMode.from(target))) return false
        }
        // 0×0 窗口看不见，通知栏这条是"全局档位已生效"的唯一可见反馈
        LockNotification.showGlobal(context, mode.label)
        AppPrefs.journal(context, "global overlay " + mode.storedName + " ori=" + target)
        return true
    }

    private fun applyShizuku(context: Context, mode: GlobalRotationMode): Boolean {
        if (!ShizukuRotation.isReady(context)) {
            AppPrefs.journal(context, "global shizuku: not ready")
            AppPrefs.setLastResult(context, "global shizuku apply failed: not ready")
            return false
        }
        val seed = when {
            mode.followAppAxis -> followAxisSeed(context)
            mode.isAuto -> seedRotation(context, mode.allowedRotations.orEmpty())
            else -> mode.fixedRotation ?: Surface.ROTATION_0
        }
        worker.execute {
            // 快照要在动手前记：fixed-to-user-rotation 是显示级设置，不改回去整个屏幕会一直被固定住
            if (AppPrefs.globalBaseFixed(context) == null) {
                AppPrefs.setGlobalBaseFixed(context, ShizukuBridge.fixedToUserRotation(context))
            }
            if (AppPrefs.globalBaseAuto(context) < 0) AppPrefs.beginGlobal(context)
            val result = ShizukuBridge.run(
                context,
                listOf(
                    "settings put system accelerometer_rotation 0",
                    "settings put system user_rotation " + seed,
                    "wm fixed-to-user-rotation enabled",
                ),
            )
            AppPrefs.setLastResult(context, "global shizuku apply ok=" + result.ok + " rot=" + seed)
            AppPrefs.journal(context, "global shizuku " + mode.storedName + " rot=" + seed + " ok=" + result.ok)
            Log.i(TAG, "global shizuku apply ok=" + result.ok + " rot=" + seed)
        }
        if (mode.isAuto) {
            watcher(context).start(mode.allowedRotations.orEmpty(), seed, { commitRotation(context, it) }, mode.followAppAxis)
        } else {
            watcher(context).stop()
        }
        return true
    }

    /**
     * 自动档跟随姿态落地一次角度：按当前锁定方式走各自的写入通道。
     * @return 是否真的落地了（没落地的话监听器会保留候选，下次姿态变化再试）
     */
    private fun commitRotation(context: Context, rotation: Int): Boolean {
        val engine = AppPrefs.engine(context)
        // 外来角度礼让：实际角度不在允许集合里，多半是应用自己把屏幕转过去了（全屏视频的横屏、
        // 横屏游戏）。这时别跟它抢 user_rotation，等它自己转回来（角度回到允许集合）再继续跟姿态。
        // 「跟随应用方向」不需要：允许集合本来就跟着实际角度所在的轴走（见 OrientationWatcher.syncAxisWithActual），
        // 任何角度都在当前轴的允许集合里。
        if (!AppPrefs.globalMode(context).followAppAxis) {
            val allowed = AppPrefs.globalMode(context).allowedRotations
            val actual = currentRotation(context)
            if (allowed != null && actual !in allowed) {
                AppPrefs.journal(
                    context,
                    "global auto defer: screen at " + actual + " (app request?), skip " + rotation,
                )
                return false
            }
        }
        val ok = when (engine) {
            EngineKind.SETTINGS -> {
                if (!RotationController.canWrite(context)) {
                    AppPrefs.journal(context, "global auto: no WRITE_SETTINGS")
                    false
                } else {
                    runCatching {
                        Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, rotation)
                        true
                    }.getOrDefault(false)
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
                        AppPrefs.journal(context, "global auto shizuku -> " + rotation + " ok=" + result.ok)
                    }
                    true
                }
            }
        }
        if (ok) AppPrefs.journal(context, "global auto -> " + rotation + " rot=" + currentRotation(context))
        return ok
    }

    /**
     * 自动档的起始角度：必须是"当前真实角度"，否则一关自动旋转屏幕会先跳一下；
     * 当前角度不被新档位允许时（例如横着拿手机切到"不含横向"）纠正到 0°。
     */
    private fun seedRotation(context: Context, allowed: Set<Int>): Int {
        val current = currentRotation(context)
        return if (current in allowed) current else Surface.ROTATION_0
    }

    /**
     * 按应用自动档与全局自动档共用同一个方向监听器（进程内单例）。
     * 进入按应用时 pause 已把全局监听停掉，调用方拿到实例后用按应用的配置重启。
     */
    fun acquireWatcher(context: Context): OrientationWatcher = synchronized(LOCK) {
        watcher ?: OrientationWatcher(context.applicationContext ?: context).also { watcher = it }
    }

    /** 停掉方向监听（按应用锁离开时调用；全局仍在接管时由 release 流程里的 apply 重新拉起）。 */
    fun stopWatcher(context: Context) {
        synchronized(LOCK) { watcher }?.stop()
    }

    private fun watcher(context: Context): OrientationWatcher = synchronized(LOCK) {
        watcher ?: OrientationWatcher(context.applicationContext ?: context).also { watcher = it }
    }

    private fun putAuto(context: Context, value: Int) {
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, value)
        }
    }

    private fun putUser(context: Context, value: Int) {
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, value)
        }
    }

    private fun currentRotation(context: Context): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .rotation
    }.getOrDefault(Surface.ROTATION_0)
}
