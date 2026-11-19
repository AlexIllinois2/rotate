package com.rotatelock

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo

/**
 * 判断某个 Activity 在清单里是否自己请求了横屏（或全方向旋转）。
 *
 * 数据来源只有 PackageManager 的静态 screenOrientation —— TYPE_WINDOW_STATE_CHANGED 事件本身就带
 * 组件名，所以不需要无障碍服务开窗口内容读取（canRetrieveWindowContent 保持 false）。
 * 应用运行中动态调 setRequestedOrientation 的场景（比如视频应用点全屏按钮）在清单里看不到，
 * 那部分由 GlobalRotation 的「外来角度礼让」兜底：应用成功把屏幕转出允许集合时自动档不去抢。
 *
 * 查不到的组件（对话框、输入法 Service、找不到的类）返回 null，调用方应保持现状。
 */
object ActivityOrientation {

    /** 组件名 → 是否请求横屏/全方向。窗口事件很频繁，命中缓存避免每个事件都查 PackageManager。 */
    private val cache = LinkedHashMap<String, Boolean>()

    /**
     * true = 该活动请求横屏族（含传感器横屏）或全方向族，全局档位应给它让路；
     * false = 竖屏族 / USER / NOSENSOR / LOCKED / 未指定，全局档位照常接管；
     * null = 查不到该组件，保持现状。
     */
    fun requestsFreeRotation(context: Context, packageName: String, className: String): Boolean? {
        val key = packageName + "/" + className
        synchronized(cache) { cache[key]?.let { return it } }
        val info = runCatching {
            context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
        }.getOrNull() ?: return null
        val wants = when (info.screenOrientation) {
            // 横屏族：固定横屏、传感器横屏、用户横屏（含允许 180° 翻转的横屏）
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
            // 全方向族：应用自己要跟传感器转满，全局档位不该压它
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            -> true
            else -> false
        }
        synchronized(cache) {
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[key] = wants
        }
        return wants
    }

    private const val MAX_CACHE = 512
}
