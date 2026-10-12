package com.rotatelock

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗锁定方式：加一个 0×0、不可聚焦、不可触摸的 TYPE_APPLICATION_OVERLAY 窗口，
 * 只给它一个固定的 screenOrientation。系统选屏幕方向时取"最上层那个有固定方向的可见窗口"
 * （android12 起为 DisplayArea.Tokens.getOrientation + mGetOrientingWindow），覆盖层在应用窗口之上，
 * 所以既能锁定普通应用，也能盖过"应用自己在清单里写死方向"的情况；全程不改系统旋转设置。
 *
 * 版本下限：android11 及更早把这类窗口放进 NonAppWindowContainers，
 * 那个容器的 getOrientation() 无条件返回 UNSET（源码里就写着 "NonAppWindowContainer cannot set
 * orientation"），所以 API 30 及以下这条路走不通 —— 必须 Android 12+。
 *
 * 大屏例外：显示器开了 ignore-orientation-request（最小宽度 ≥ 600dp 的设备默认开）时，
 * DisplayContent 会把这个固定方向请求整个丢掉；那种设备请改用系统设置方式。
 */
object OverlayRotation {

    private const val TAG = "RotationLock"

    /**
     * 窗口尺寸：按需求用 0（真正的 0×0）。个别 ROM 上 0×0 窗口拿不到 surface 时会不生效，
     * 那种情况下把这里改成 1 即可，不需要动其它逻辑。
     */
    private const val SIZE = 0

    /**
     * 悬浮窗自己的窗口会发出 TYPE_WINDOW_STATE_CHANGED 无障碍事件（packageName = 本应用，
     * className = 这个类名）。服务必须把它排除掉，否则"加窗口"会被自己判定成"切到别的应用"，
     * 于是刚锁上就立刻解锁 —— 表现就是"转到设定方向后马上弹回应用自己的方向"。
     */
    val WINDOW_CLASS_NAME: String = OverlayWindowView::class.java.name

    private var windowView: View? = null

    /** true 表示这次 detach 是我们自己调的 removeView，用来区分"系统把窗口弄没了"。 */
    private var selfRemoving = false

    /** 只有 Android 12+ 的窗口层级结构才会把覆盖层的固定方向当作方向来源。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canDrawOverlay(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun isActive(): Boolean = windowView != null

    /**
     * 锁定到目标方向。窗口已存在时只改 screenOrientation（重建会短暂丢掉方向请求）。
     * @return 是否已生效（无权限、系统版本过低、添加失败都返回 false）
     */
    fun apply(context: Context, mode: OrientationMode): Boolean = applyInternal(context, mode, checkVersion = true)

    /**
     * 自动化探针：跳过版本判断直接挂窗口。用来在 Android 11 这类"固定方向请求不被采纳"的系统上
     * 单独验证窗口本身的挂载/移除是否正常（方向是否真的生效要看系统版本）。
     */
    fun probe(context: Context, mode: OrientationMode): Boolean = applyInternal(context, mode, checkVersion = false)

    private fun applyInternal(context: Context, mode: OrientationMode, checkVersion: Boolean): Boolean {
        if (checkVersion && !isSupported()) {
            Log.w(TAG, "overlay engine needs android 12+ (sdk=" + Build.VERSION.SDK_INT + ")")
            return false
        }
        val orientation = activityOrientation(context, mode)
        // 用 application context：悬浮窗是进程级窗口，不该跟着 Activity 的生命周期走
        // （用 Activity 的 WindowManager 加窗，Activity 重建时窗口会一起没掉，而对象里的"已挂载"标记
        //  还以为它在，真实产品路径上就会出现"看起来锁了其实没锁"）
        val appContext = context.applicationContext ?: context
        val manager = appContext.getSystemService(WindowManager::class.java) ?: return false

        val existing = windowView
        if (existing != null) {
            val params = existing.layoutParams as? WindowManager.LayoutParams
            if (params != null && params.screenOrientation != orientation) {
                params.screenOrientation = orientation
                runCatching { manager.updateViewLayout(existing, params) }
                    .onFailure { Log.w(TAG, "overlay update failed: " + it) }
                journal(context, "win update ori=" + orientation)
            }
            return true
        }

        return runCatching {
            val params = WindowManager.LayoutParams(
                SIZE,
                SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = orientation
                setTitle("AppRotateOrientation")
            }
            val view = OverlayWindowView(appContext)
            // 系统把窗口撤掉（厂商策略 / 进程状态变化）时，我们这边也要知道，否则会以为它还挂着
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    val bySelf = selfRemoving
                    if (!bySelf && windowView === v) windowView = null
                    journal(context, "win detached self=" + bySelf)
                }
            })
            manager.addView(view, params)
            windowView = view
            Log.i(TAG, "overlay added screenOrientation=" + orientation)
            journal(context, "win add ok ori=" + orientation + " rot=" + currentRotation(context))
            true
        }.getOrElse { error ->
            windowView = null
            Log.w(TAG, "overlay add failed: " + error)
            journal(context, "win add FAILED " + error)
            false
        }
    }

    /** 离开目标应用时移除窗口，之后系统回到它本来的旋转行为（自动旋转/用户旋转锁都不受影响）。 */
    fun clear(context: Context) {
        // 先取消通知：下面在没有窗口时会提前 return，通知不能因此留着
        LockNotification.cancel(context)
        val view = windowView ?: return
        windowView = null
        selfRemoving = true
        runCatching {
            val appContext = context.applicationContext ?: context
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        }.onFailure { Log.w(TAG, "overlay remove failed: " + it) }
        selfRemoving = false
        Log.i(TAG, "overlay removed")
        journal(context, "win remove (self) rot=" + currentRotation(context))
    }

    private fun journal(context: Context, line: String) {
        if (!DEBUG_JOURNAL) return
        runCatching { AppPrefs.journal(context.applicationContext ?: context, line) }
    }

    private const val DEBUG_JOURNAL = true

    /**
     * OrientationMode(USER_ROTATION 0/1/2/3) → ActivityInfo 的固定方向常量。
     * DisplayRotation.rotationForOrientation() 里这几个常量分别映射回 ROTATION_0/90/180/270，
     * 与系统设置方式的取值一一对应。
     */
    private fun activityOrientation(context: Context, mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        OrientationMode.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        OrientationMode.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        OrientationMode.KEEP_CURRENT -> when (currentRotation(context)) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun currentRotation(context: Context): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .rotation
    }.getOrDefault(Surface.ROTATION_0)
}
