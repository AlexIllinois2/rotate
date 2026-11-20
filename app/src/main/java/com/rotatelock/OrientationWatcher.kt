package com.rotatelock

import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface

/**
 * 「全局自动旋转」的执行器：监听方向传感器，把设备当前姿态收敛到"允许集合"里的某一个
 * Surface.ROTATION_*，然后交给调用方按锁定方式落地。
 *
 * 为什么必须自己算：Android 没有「限制自动旋转范围」的公开开关。系统自动旋转
 * （ACCELEROMETER_ROTATION=1）能转哪几个方向由 DisplayRotation 的 config_allowAllRotations
 * 等设备配置决定，应用侧改不了。所以这里把自动旋转关掉（=0），改由我们按允许集合写角度，
 * 这样"不可上下颠倒""不含横向"这类范围限制才做得到 —— 也顺带绕开了部分机型默认不放行
 * 180° 的限制。
 *
 * 悬浮窗方式不关自动旋转：覆盖层的固定方向请求在窗口层级里优先级更高，也不需要动系统设置。
 *
 * 角度换算：OrientationEventListener 报的 orientation 与 Surface.ROTATION_* 一一对应
 * （0→ROTATION_0、90→ROTATION_90、180→ROTATION_180、270→ROTATION_270）。
 * 依据 AOSP 里两处公式对同一姿态给出同一结果：
 *   OrientationEventListener.onSensorChanged: angle = atan2(-Y, X); orientation = 90 - round(angle)
 *   DisplayRotation（加速度计分支）:           orientationAngle = atan2(-x, y); nearest = (angle + 45) / 90
 */
class OrientationWatcher(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** 允许落在哪些 Surface.ROTATION_*。 */
    private var allowed: Set<Int> = ALL_ROTATIONS

    /**
     * 「跟随应用方向」模式（180° 自动）：true 时允许集合不取 [allowed] 固定集合，
     * 而是取当前轴的两个方向（竖屏轴 0°/180° 或横屏轴 90°/270°），
     * 并从实际显示角度自适应收养跨轴变化（应用自己转到横屏/转回来）。
     */
    private var followAxis: Boolean = false

    /** 当前轴是否为横屏轴（followAxis 模式下决定允许集合）。 */
    private var landscapeAxis: Boolean = false

    /** 最近一次真正写进系统设置的角度。 */
    private var applied: Int = Surface.ROTATION_0

    /** 待确认的候选角度；-1 表示没有。 */
    private var candidate: Int = -1

    /** 上一次打过日志的角度，用于只在变化时打印。 */
    private var lastLoggedNearest: Int = -1

    /** 最后一次传感器报的姿态（原始 orientation 值；平放时常为 ORIENTATION_UNKNOWN）。 */
    private var lastOrientation: Int = OrientationEventListener.ORIENTATION_UNKNOWN

    /** 到点确认候选角度（防抖：抖动会被下一个样本取消掉）。 */
    private val confirmRunnable = Runnable { confirmPending() }

    private var listener: OrientationEventListener? = null

    /**
     * 每次 start/stop 自增。主线程排队任务执行时先核对代号，对不上说明自己已被后面的
     * start/stop 取代，直接放弃 —— 否则 release() 里 post 出去的 stop 会排在
     * 换方式后 start 的 enable 之后执行，把刚建好的监听器整个关掉，
     * 表现就是"切换锁定方式后自动档不跟手，重新选一次档位才恢复"。
     */
    private var generation = 0

    /**
     * 落地一次角度的回调，由调用方按当前锁定方式实现（写系统设置 / 改悬浮窗方向 / 走 Shizuku）。
     * 返回是否真的落地了；false 时监听器会保留候选，下次姿态变化再试。
     */
    private var onApplied: ((Int) -> Boolean)? = null

    fun isRunning(): Boolean = listener != null

    /**
     * @param allowedRotations 自动档允许落在哪些角度（followAppAxis 时只是初始参考，实际集合跟随当前轴）
     * @param seed 起始角度（应当是当前的真实角度，或当前角度不被允许时的兜底角度）
     * @param followAppAxis true = 「跟随应用方向」：允许集合跟随当前轴，并从实际角度收养跨轴变化
     */
    fun start(
        allowedRotations: Set<Int>,
        seed: Int,
        onApplied: ((Int) -> Boolean)? = null,
        followAppAxis: Boolean = false,
    ) {
        generation++
        val gen = generation
        allowed = allowedRotations
        followAxis = followAppAxis
        // 「跟随应用方向」的轴来源是前台变化（无障碍事件或 Shizuku 方式的 UsageStats 轮询）。
        // 前台来源不可用时绝不能从种子角度收养横屏轴：否则一次残留的横屏角度（比如重启前
        // 按应用锁留下的 user_rotation=90）会把整个系统锁进横屏翻转且无法恢复。
        val eventsAvailable = ForegroundBus.sourceAvailable(appContext)
        if (followAxis && !eventsAvailable &&
            (seed == Surface.ROTATION_90 || seed == Surface.ROTATION_270)
        ) {
            AppPrefs.journal(
                appContext,
                "global watcher: accessibility off, keep portrait axis (seed=" + seed + ")",
            )
        }
        landscapeAxis = followAxis && eventsAvailable &&
            (seed == Surface.ROTATION_90 || seed == Surface.ROTATION_270)
        this.onApplied = onApplied
        applied = if (followAxis) {
            seed // 跟随应用方向：当前实际角度就是轴的种子，不必落在 allowedRotations 里
        } else {
            if (seed in allowedRotations) seed else fallbackRotation(allowedRotations)
        }
        clearCandidate()

        // 构造和 enable 都在主线程 post 里做：OrientationEventListener 的回调跑在注册时的
        // Looper 上，而调用方可能在 worker 线程（Shizuku 通道的异步分支），那里没有 Looper，
        // 直接构造会崩。代号对不上就放弃，保证"最后一次 start/stop 谁说了算"。
        main.post {
            if (gen != generation) return@post
            stopInternal()
            this.onApplied = onApplied
            val created = object : OrientationEventListener(appContext, SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    handle(orientation)
                }
            }
            listener = created
            if (!created.canDetectOrientation()) {
                AppPrefs.journal(appContext, "global watcher: cannot detect orientation")
                return@post
            }
            runCatching { created.enable() }
                .onFailure { AppPrefs.journal(appContext, "global watcher enable failed: " + it) }
            Log.i(TAG, "global watcher enabled allowed=" + allowed + " seed=" + applied)
            // 启动时补一次重估：接管那一刻的 applied 可能还挂在跨轴的旧角度上
            //（横屏应用刚退后台就重新接管），不等第一帧传感器事件就纠正回来
            reevaluate()
        }
    }

    fun stop() {
        generation++
        val gen = generation
        onApplied = null
        clearCandidate()
        main.post {
            if (gen != generation) return@post
            stopInternal()
        }
    }

    /**
     * 「跟随应用方向」模式下由前台应用的清单方向驱动轴切换（悬浮窗 / Shizuku 方式用：
     * 这两种方式下应用自己的方向请求到不了显示层，实际角度自适应看不到跨轴变化）。
     * applied 留在旧轴上没关系：新轴的任何姿态样本都会因 nearest != applied 触发提交。
     */
    fun setLandscapeAxis(landscape: Boolean) {
        if (!followAxis || landscapeAxis == landscape) return
        landscapeAxis = landscape
        clearCandidate()
        Log.i(TAG, "global watcher axis -> " + (if (landscape) "landscape" else "portrait"))
        AppPrefs.journal(appContext, "global watcher axis -> " + (if (landscape) "landscape" else "portrait"))
        main.post { reevaluate() }
    }

    /**
     * 立即按"最后一次已知姿态 + 当前允许集合"重估一次落角。
     *
     * OrientationEventListener 只在姿态变化时回调——横屏应用（视频全屏）在前台时人往往
     * 竖着握或平放，应用退后台、轴切回竖屏的那一刻没有新的传感器事件，
     * applied 还挂在旧轴的角度上，屏幕就会一直停在横屏，直到用户 physically 转一下手机。
     * 所以轴切换 / 监听启动后必须主动补一次：
     *  - 有缓存姿态：照常走 handle 的候选-确认-提交流程；
     *  - 姿态未知（平放）：直接落到轴的基准角度（竖屏轴 0° / 横屏轴 90°），
     *    下一帧真实姿态事件来了会继续正常跟随。
     */
    private fun reevaluate() {
        val allowedNow = effectiveAllowed()
        if (lastOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            handle(lastOrientation)
            return
        }
        val base = fallbackRotation(allowedNow)
        if (base != applied && base in allowedNow) commit(base)
    }

    /** 当前生效的允许集合：固定集合模式取 [allowed]，「跟随应用方向」取当前轴的两个方向。 */
    private fun effectiveAllowed(): Set<Int> = when {
        !followAxis -> allowed
        landscapeAxis -> LANDSCAPE_AXIS
        else -> PORTRAIT_AXIS
    }

    /**
     * 「跟随应用方向」的轴自适应：应用自己把屏幕转到另一个轴（全屏视频、横屏游戏）时收养新轴，
     * 应用转回来时同样收养。我们自己写的角度永远在当前轴内，不会误触发。
     *
     * 只在「系统设置」方式下生效：只有那种方式显示层才跟着应用的方向请求走，跨轴变化才观察得到。
     * 悬浮窗 / Shizuku 方式下实际角度永远跟着我们的写入走（覆盖层方向请求优先级最高 /
     * fixed-to-user-rotation 让显示层忽略应用请求），如果在这里收养，会把外部设好的轴
     * （GlobalRotation.setWatcherAxis）在下一次传感器事件时就改回去——横屏锁定应用翻转失效就是它。
     */
    private fun syncAxisWithActual() {
        if (!followAxis) return
        if (AppPrefs.engine(appContext) != EngineKind.SETTINGS) return
        val actual = currentRotation()
        val actualLandscape = actual == Surface.ROTATION_90 || actual == Surface.ROTATION_270
        if (actualLandscape == landscapeAxis) return
        landscapeAxis = actualLandscape
        applied = actual
        clearCandidate()
        AppPrefs.journal(appContext, "global watcher axis adopted -> " + actual)
        Log.i(TAG, "global watcher axis adopted -> " + actual)
    }

    /** 不允许的角度上一律保持原状：这才是"不可上下颠倒""不含横向"想要的行为。 */
    private fun handle(orientation: Int) {
        lastOrientation = orientation
        syncAxisWithActual()
        val allowedNow = effectiveAllowed()
        if (DEBUG_LOG) {
            // 只在"算出来的角度变了"时打，否则 15Hz 的传感器会把 logcat 刷爆
            val nearest = if (orientation >= 0) surfaceRotation(orientation) else -1
            if (nearest != lastLoggedNearest) {
                lastLoggedNearest = nearest
                Log.i(
                    TAG,
                    "sensor orientation=" + orientation + " -> rotation=" + nearest +
                        " applied=" + applied + " allowed=" + allowedNow,
                )
            }
        }
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            clearCandidate()
            return
        }
        val nearest = surfaceRotation(orientation)
        if (nearest == applied || nearest !in allowedNow) {
            clearCandidate()
            return
        }
        if (nearest == candidate) return // 已经在等定时器了
        candidate = nearest
        // 定时确认，而不是"等第二个样本"：有的设备（含模拟器）只在姿态变化时上报一次，
        // 等第二个样本会永远等不到。抖动会在 SETTLE_MS 内被下一个不同的样本取消掉。
        main.removeCallbacks(confirmRunnable)
        main.postDelayed(confirmRunnable, SETTLE_MS)
    }

    private fun confirmPending() {
        val target = candidate
        candidate = -1
        if (target < 0) return
        if (target != applied && target in effectiveAllowed()) commit(target)
    }

    private fun clearCandidate() {
        candidate = -1
        main.removeCallbacks(confirmRunnable)
    }

    private fun commit(rotation: Int) {
        val landed = onApplied?.invoke(rotation) ?: false
        if (!landed) return
        applied = rotation
        clearCandidate()
        Log.i(TAG, "global auto rotation -> " + rotation)
    }

    private fun stopInternal() {
        onApplied = null
        clearCandidate()
        val current = listener ?: return
        listener = null
        runCatching { current.disable() }
            .onFailure { AppPrefs.journal(appContext, "global watcher disable failed: " + it) }
    }

    /**
     * OrientationEventListener 报的 orientation → Surface.ROTATION_*。
     *
     * 两者是"互补"关系，不能直接按 90° 一档去套：
     *   OrientationEventListener.onSensorChanged 算的是 90 - round(atan2(-Y, X))（X/Y 用的是取反后的加速度）
     *   DisplayRotation 的加速度计分支算的是 atan2(x, y)，再 (angle + 45) / 90
     * 展开后两者互为相反数（模 360）：orientation ≡ -angle。所以这里必须取 (4 - nearest) % 4，
     * 否则 ROTATION_90 与 ROTATION_270 会整个互换 —— 表现为"手机往左转、屏幕却往右翻"。
     *
     * 实测（MuMu / Android 15，1080x1920）：竖屏报 359、横屏报 271；
     * 系统自己在这两个姿态下给出的是 ROTATION_0 与 ROTATION_90，与上面的推导一致。
     */
    private fun surfaceRotation(orientation: Int): Int {
        val normalized = ((orientation % 360) + 360) % 360
        return (4 - ((normalized + 45) % 360 / 90)) % 4
    }

    private fun fallbackRotation(allowedRotations: Set<Int>): Int =
        if (Surface.ROTATION_0 in allowedRotations) {
            Surface.ROTATION_0
        } else {
            allowedRotations.firstOrNull() ?: Surface.ROTATION_0
        }

    private fun currentRotation(): Int = runCatching {
        appContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .rotation
    }.getOrDefault(Surface.ROTATION_0)

    private companion object {
        const val TAG = "RotationLock"
        /** 传感器事件很密（15Hz 左右），只在 debug 包里逐条打，方便排查"到底有没有收到事件"。 */
        const val DEBUG_LOG = true
        const val SETTLE_MS = 250L
        val ALL_ROTATIONS = setOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )

        /** 「跟随应用方向」的两个轴：竖屏轴（0°/180°）与横屏轴（90°/270°）。 */
        val PORTRAIT_AXIS = setOf(Surface.ROTATION_0, Surface.ROTATION_180)
        val LANDSCAPE_AXIS = setOf(Surface.ROTATION_90, Surface.ROTATION_270)
    }
}
