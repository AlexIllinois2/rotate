package com.rotatelock

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 悬浮窗方式锁定期间的常驻通知。
 *
 * 0×0 的悬浮窗本身是看不见的，通知栏这条就是"锁已生效"的唯一可见反馈，点它能直接回到应用；
 * 离开目标应用（窗口被撤掉）时自动消失。只在悬浮窗方式下显示，其它方式不动通知栏。
 * 全局旋转用悬浮窗方式时也会显示一条，内容是当前的全局档位。
 */
object LockNotification {

    private const val CHANNEL_ID = "rotation_lock_active"
    private const val NOTIFICATION_ID = 0x524F

    /** Android 13 起通知要运行时权限；更低版本默认有。 */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, packageName: String, mode: OrientationMode) {
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        post(context, "正在为「" + label + "」锁定：" + mode.label)
    }

    /**
     * 全局旋转用：没有具体应用，直接说清是哪个档位在生效。
     * 自动档的角度会跟着姿态变，所以只说档位名、不说当前角度。
     */
    fun showGlobal(context: Context, modeLabel: String) {
        post(context, "全局旋转已生效：" + modeLabel)
    }

    private fun post(context: Context, text: String) {
        if (!canPost(context)) {
            AppPrefs.journal(context, "notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        runCatching { ensureChannel(manager) }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { AppPrefs.journal(context, "notification failed: " + it) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "锁定生效中",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "悬浮窗方式锁定方向时显示的常驻提示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
