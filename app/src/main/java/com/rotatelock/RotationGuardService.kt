package com.rotatelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 后台守护：一条低优先级常驻通知的前台服务（界面「后台守护」开关控制，默认开）。
 *
 * 它做不到的事说清楚：**系统不允许应用自己打开无障碍服务**，所以守护不能"救回"被关掉的服务，
 * 只能在通知栏明确告诉用户去打开（点通知直达系统无障碍设置）。
 *
 * 它能做的事：
 *  1. **提进程优先级** —— 全局自动档的方向监听、Shizuku 会话都跑在本进程里，进程活着它们才活着；
 *  2. **被杀后自动重启**（START_STICKY），重启时把全局档位重放一遍；
 *  3. **定期兜底** —— 无障碍服务只在窗口切换时自愈（停在桌面时没人触发），这里每 60 秒查一次：
 *     全局档位要求的状态被改掉了就 heal，无障碍服务被关了就更新通知提示用户。
 */
class RotationGuardService : android.app.Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 通知文案只在"无障碍是否开着"这个状态变化时才重写，避免每分钟都重发通知。 */
    private var lastWarn: Boolean? = null

    /** 定期兜底任务。 */
    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppPrefs.journal(this, "guard onCreate pid=" + Process.myPid())
        // startForegroundService 要求 5 秒内转正，onCreate 里就做，不等 onStartCommand
        runCatching { startAsForeground(currentWarn()) }
            .onFailure { Log.w(TAG, "guard startForeground failed: " + it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏「退出守护」：只关守护，不动旋转状态，也不碰无障碍服务
            AppPrefs.setGuardEnabled(this, false)
            AppPrefs.journal(this, "guard stopped from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startAsForeground(currentWarn()) }
        // 开机自启 / 进程重启后立刻重放全局档位（幂等；无障碍服务随后被重新绑定时还会再做一次）
        if (AppPrefs.globalMode(this).engages) {
            AppPrefs.journal(this, "guard: replay global " + AppPrefs.globalMode(this).storedName)
            GlobalRotation.apply(this)
        }
        updateGuardNotification()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, CHECK_INTERVAL_MS)
        // 被系统杀掉后重启服务（intent 为 null 也走上面这条正常路径）
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        AppPrefs.journal(this, "guard onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 定期兜底 ----

    private fun check() {
        if (!RotationAccessibilityService.isEnabled(this)) {
            // 被系统/厂商 ROM 关掉了：应用无权自己打开，通知栏引导用户去打开
            updateGuardNotification(warn = true)
            return
        }
        // 有按应用锁在身时不插手（那时由按应用规则说了算）
        if (AppPrefs.globalMode(this).engages && AppPrefs.lockedPackage(this) == null) {
            GlobalRotation.heal(this)
        }
        updateGuardNotification(warn = false)
    }

    private fun currentWarn(): Boolean = !RotationAccessibilityService.isEnabled(this)

    private fun updateGuardNotification(warn: Boolean = currentWarn()) {
        if (lastWarn == warn) return
        lastWarn = warn
        runCatching { startAsForeground(warn) }
            .onFailure { AppPrefs.journal(this, "guard notify update failed: " + it) }
    }

    private fun startAsForeground(warn: Boolean) {
        ensureChannel()
        val notification = buildNotification(warn)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(warn: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (warn) {
            builder.setContentText("无障碍服务被关闭了，旋转锁定不会生效")
                .setContentIntent(openAccessibilityIntent())
                .addAction(0, "去打开无障碍", openAccessibilityIntent())
        } else {
            builder.setContentText("后台守护中：开机自动恢复，锁定被清理时自动补回")
                .setContentIntent(openAppIntent())
        }
        builder.addAction(0, "退出守护", stopGuardIntent())
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAccessibilityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopGuardIntent(): PendingIntent = PendingIntent.getForegroundService(
        this,
        2,
        Intent(this, RotationGuardService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台守护",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "开机自启动与后台守护的常驻提示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RotationLock"
        private const val CHANNEL_ID = "rotation_guard"
        private const val NOTIFICATION_ID = 0x4752 // "GR"
        /** 兜底间隔：无障碍服务只靠窗口事件触发自愈，停在桌面时靠这个补。 */
        private const val CHECK_INTERVAL_MS = 60_000L

        /** 通知栏「退出守护」动作。 */
        const val ACTION_STOP = "com.rotatelock.action.STOP_GUARD"

        /** 统一入口：前台方式启动（保证 5 秒内 startForeground），重复调用是幂等的。 */
        fun start(context: Context) {
            val intent = Intent(context, RotationGuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    AppPrefs.journal(context, "guard start failed: " + it)
                    Log.w(TAG, "guard start failed: " + it)
                }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RotationGuardService::class.java)) }
                .onFailure { AppPrefs.journal(context, "guard stop failed: " + it) }
        }
    }
}
